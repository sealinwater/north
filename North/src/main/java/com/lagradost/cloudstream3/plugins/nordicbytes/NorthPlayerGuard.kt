package com.lagradost.cloudstream3.plugins.nordicbytes

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.lagradost.cloudstream3.ui.player.AbstractPlayerFragment
import com.lagradost.cloudstream3.ui.player.CSPlayerEvent
import com.lagradost.cloudstream3.ui.player.ErrorEvent
import com.lagradost.cloudstream3.ui.player.IPlayer
import com.lagradost.cloudstream3.ui.player.PauseEvent
import com.lagradost.cloudstream3.ui.player.PlayEvent
import com.lagradost.cloudstream3.ui.player.PlayerEvent
import com.lagradost.cloudstream3.ui.player.PlayerEventSource
import com.lagradost.cloudstream3.ui.player.PositionEvent
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Playback state management guard & validation layer for CloudStream's player controller.
 *
 * Root cause of the 00:00 -> 27:00 rapid-scrub crash:
 * - CloudStream's `CS3IPlayer.seekTo(ms, source)` and `seekTime(ms, source)` forward raw millisecond
 *   positions directly to ExoPlayer without clamping to `[0, duration - margin]` or checking whether
 *   media duration / playback state is valid.
 * - Dragging the timeline bar to completion (`27:00`) in <2 seconds can request `ms >= duration`
 *   (or seek while `duration == TIME_UNSET`), triggering `IllegalSeekPositionException` / `IllegalStateException`
 *   or an abrupt EOF teardown mid-scrub.
 *
 * Protections installed by `NorthPlayerGuard`:
 * 1. Wraps `AbstractPlayerFragment.player` in `SafeValidatedIPlayer` (delegating to `CS3IPlayer`),
 *    clamping EVERY `seekTo` / `seekTime` strictly within `[0L, durationMs - END_SAFETY_MARGIN_MS]`.
 * 2. Wraps the underlying Media3 `ExoPlayer` interface in a dynamic proxy (`SafeExoPlayerProxy`) and
 *    attaches a `Player.Listener` proxy so even direct timeline-bar (`DefaultTimeBar` / `PlayerControlView`)
 *    seeks are range-checked and logged.
 * 3. Validation & Recovery Layer: continuously tracks `lastValidPositionMs`; if any seek request throws
 *    or transitions the player into an illegal state, forces an immediate guarded fallback back to
 *    `lastValidPositionMs` instead of crashing.
 */
object NorthPlayerGuard {

    // Keep timeline seeks at least 2 seconds before EOF so dragging to 27:00 never overshoots duration
    // or crashes ExoPlayer into an illegal EOF/teardown state mid-scrub.
    private const val END_SAFETY_MARGIN_MS = 2_000L
    private const val TIME_UNSET_MEDIA3 = Long.MIN_VALUE + 1L
    private const val SEEK_ERROR_FALLBACK_WINDOW_MS = 1_800L

    private val watcherInstalled = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val watchedActivities = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Activity, Boolean>()))
    private val wrappedExoPlayers = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Any, Boolean>()))
    private val watchedEventHandlers = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Any, Boolean>()))

    fun startActivityLifecycleWatcher(context: Context) {
        if (!watcherInstalled.compareAndSet(false, true)) return
        val app = context.applicationContext as? Application ?: return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                attachFragmentWatcherIfNeeded(activity)
            }

            override fun onActivityStarted(activity: Activity) {
                attachFragmentWatcherIfNeeded(activity)
            }

            override fun onActivityResumed(activity: Activity) {
                attachFragmentWatcherIfNeeded(activity)
                if (activity is AppCompatActivity) {
                    scanAndGuardExistingFragments(activity.supportFragmentManager)
                }
            }

            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun attachFragmentWatcherIfNeeded(activity: Activity) {
        if (activity !is AppCompatActivity) return
        if (!watchedActivities.add(activity)) return
        try {
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(
                object : FragmentManager.FragmentLifecycleCallbacks() {
                    override fun onFragmentViewCreated(
                        fm: FragmentManager,
                        f: Fragment,
                        v: View,
                        savedInstanceState: Bundle?
                    ) {
                        if (f is AbstractPlayerFragment<*>) {
                            guardPlayerFragment(f)
                        }
                    }

                    override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                        if (f is AbstractPlayerFragment<*>) {
                            guardPlayerFragment(f)
                        }
                    }
                },
                true
            )
        } catch (t: Throwable) {
            NorthDebugLogger.log("WARN", "PLAYER_GUARD", "Failed registering fragment lifecycle watcher", t)
        }
    }

    private fun scanAndGuardExistingFragments(fm: FragmentManager?) {
        if (fm == null) return
        try {
            for (frag in fm.fragments) {
                if (frag is AbstractPlayerFragment<*> && frag.isAdded) {
                    guardPlayerFragment(frag)
                }
                if (frag.isAdded) {
                    scanAndGuardExistingFragments(frag.childFragmentManager)
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun unwrapIPlayer(player: IPlayer?): IPlayer? {
        return if (player is SafeValidatedIPlayer) player.delegate else player
    }

    fun clampSeekPositionMs(requestedMs: Long, durationMs: Long, lastValidPosMs: Long): Long {
        if (requestedMs == TIME_UNSET_MEDIA3) {
            return lastValidPosMs.coerceAtLeast(0L)
        }
        if (durationMs <= 0L || durationMs == TIME_UNSET_MEDIA3) {
            // Duration is not resolved yet (e.g. scrubbing right after launch before Cues/headers load):
            // never allow jumping out to 27:00 blindly; stay at lastValidPosMs or 0.
            return if (requestedMs <= 0L) 0L else lastValidPosMs.coerceAtLeast(0L)
        }
        val safeUpper = if (durationMs > END_SAFETY_MARGIN_MS + 1_000L) {
            durationMs - END_SAFETY_MARGIN_MS
        } else {
            (durationMs - 250L).coerceAtLeast(0L)
        }
        return requestedMs.coerceIn(0L, safeUpper)
    }

    fun guardPlayerFragment(playerFrag: AbstractPlayerFragment<*>?) {
        if (playerFrag == null) return
        mainHandler.post {
            try {
                val currentPlayer = playerFrag.player ?: return@post
                val guarded: SafeValidatedIPlayer = if (currentPlayer is SafeValidatedIPlayer) {
                    currentPlayer
                } else {
                    val wrapper = SafeValidatedIPlayer(currentPlayer, playerFrag)
                    playerFrag.player = wrapper
                    // Also update AbstractPlayerFragment.playerHostView's player reference if accessible
                    try {
                        val hostField = AbstractPlayerFragment::class.java.getDeclaredField("playerHostView")
                        hostField.isAccessible = true
                        val hostView = hostField.get(playerFrag)
                        if (hostView != null) {
                            val pField = hostView.javaClass.getDeclaredField("player")
                            pField.isAccessible = true
                            val hostPlayer = pField.get(hostView)
                            if (hostPlayer === currentPlayer) {
                                pField.set(hostView, wrapper)
                            }
                        }
                    } catch (_: Throwable) {
                    }
                    NorthDebugLogger.logStateTransition("GUARD_ATTACHED", "Wrapped ${currentPlayer.javaClass.simpleName}")
                    wrapper
                }

                guarded.wrapExistingEventHandlerIfNeeded()
                guarded.attachExoPlayerProxyIfReady()
                // ExoPlayer is often created asynchronously after link resolution, so probe a few times
                for (delayMs in longArrayOf(400L, 1_200L, 2_500L, 4_500L)) {
                    mainHandler.postDelayed({
                        try {
                            if (playerFrag.isAdded) {
                                guarded.wrapExistingEventHandlerIfNeeded()
                                guarded.attachExoPlayerProxyIfReady()
                            }
                        } catch (_: Throwable) {
                        }
                    }, delayMs)
                }
            } catch (t: Throwable) {
                NorthDebugLogger.log("WARN", "PLAYER_GUARD", "guardPlayerFragment failed", t)
            }
        }
    }

    /**
     * Validating `IPlayer` controller wrapper that:
     * - Clamps every `seekTo` / `seekTime` within `[0, duration - 2s]`.
     * - Tracks `lastValidPositionMs` and `lastKnownDurationMs` from healthy playback.
     * - Detects illegal seek exceptions or post-seek illegal states and forces fallback to `lastValidPositionMs`.
     */
    class SafeValidatedIPlayer(
        val delegate: IPlayer,
        private val fragment: AbstractPlayerFragment<*>
    ) : IPlayer by delegate {

        @Volatile
        private var lastValidPositionMs: Long = 0L

        @Volatile
        private var lastKnownDurationMs: Long = 0L

        @Volatile
        private var lastSeekAtMs: Long = 0L

        @Volatile
        private var isRecoveringFallback: Boolean = false

        @Volatile
        private var lastFallbackToastMs: Long = 0L

        private fun resolveDurationMs(): Long {
            val liveDur = try {
                delegate.getDuration() ?: 0L
            } catch (_: Throwable) {
                0L
            }
            if (liveDur > 0L && liveDur != TIME_UNSET_MEDIA3) {
                lastKnownDurationMs = liveDur
                NorthDebugLogger.lastKnownDurationMs = liveDur
                return liveDur
            }
            return lastKnownDurationMs
        }

        private fun recordHealthyPosition(posMs: Long, durMs: Long) {
            if (durMs > 0L && durMs != TIME_UNSET_MEDIA3) {
                lastKnownDurationMs = durMs
                NorthDebugLogger.lastKnownDurationMs = durMs
            }
            val effectiveDur = if (durMs > 0L && durMs != TIME_UNSET_MEDIA3) durMs else lastKnownDurationMs
            val maxValid = if (effectiveDur > END_SAFETY_MARGIN_MS) effectiveDur - END_SAFETY_MARGIN_MS else Long.MAX_VALUE
            if (posMs in 0L..maxValid) {
                lastValidPositionMs = posMs
                NorthDebugLogger.lastValidPositionMs = posMs
            }
        }

        private fun isIllegalSeekOrStateThrowable(t: Throwable): Boolean {
            var cur: Throwable? = t
            var depth = 0
            while (cur != null && depth < 6) {
                val name = cur.javaClass.name
                val msg = cur.message.orEmpty().lowercase()
                if (cur is IllegalStateException ||
                    cur is IllegalArgumentException ||
                    cur is IndexOutOfBoundsException ||
                    name.contains("IllegalSeekPositionException", ignoreCase = true) ||
                    msg.contains("illegal") ||
                    msg.contains("seek") ||
                    msg.contains("out of range") ||
                    msg.contains("behind live window")
                ) {
                    return true
                }
                cur = cur.cause
                depth++
            }
            return false
        }

        private fun forceFallbackToLastValidPosition(reason: String, cause: Throwable? = null) {
            if (isRecoveringFallback) return
            isRecoveringFallback = true
            val dur = resolveDurationMs()
            val fallbackPos = clampSeekPositionMs(lastValidPositionMs, dur, 0L)
            NorthDebugLogger.log(
                "WARN",
                "SEEK_FALLBACK",
                "Illegal seek/state ($reason) -> reverting to lastValidPositionMs=${fallbackPos}ms (dur=${dur}ms)",
                cause
            )
            if (cause != null) {
                NorthDebugLogger.reportHandledFatalAsync("SEEK_ILLEGAL_STATE", reason, cause)
            }

            mainHandler.post {
                try {
                    if (delegate.isActive()) {
                        delegate.seekTo(fallbackPos, PlayerEventSource.Player)
                        delegate.handleEvent(CSPlayerEvent.Play, PlayerEventSource.Player)
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastFallbackToastMs > 5_000L) {
                        lastFallbackToastMs = now
                        val ctx = fragment.context?.applicationContext
                        if (ctx != null) {
                            Toast.makeText(
                                ctx,
                                "North: Seek exceeded valid range; restored last valid position.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } catch (recoverErr: Throwable) {
                    NorthDebugLogger.log("ERROR", "SEEK_FALLBACK", "Fallback seek failed", recoverErr)
                } finally {
                    mainHandler.postDelayed({ isRecoveringFallback = false }, 400L)
                }
            }
        }

        override fun seekTo(ms: Long, source: PlayerEventSource) {
            val dur = resolveDurationMs()
            val curPos = try {
                delegate.getPosition() ?: lastValidPositionMs
            } catch (_: Throwable) {
                lastValidPositionMs
            }
            recordHealthyPosition(curPos, dur)

            val clamped = clampSeekPositionMs(ms, dur, lastValidPositionMs)
            NorthDebugLogger.lastRequestedSeekMs = ms
            if (clamped != ms) {
                NorthDebugLogger.logSeekEvent("IPlayer.seekTo clamped raw=${ms}ms -> ${clamped}ms (dur=${dur}ms, lastValid=${lastValidPositionMs}ms, src=$source)")
            } else {
                NorthDebugLogger.logSeekEvent("IPlayer.seekTo ${clamped}ms (dur=${dur}ms, src=$source)")
            }

            if (!delegate.isActive()) {
                NorthDebugLogger.logSeekEvent("IPlayer.seekTo blocked because player is inactive")
                return
            }

            lastSeekAtMs = System.currentTimeMillis()
            try {
                delegate.seekTo(clamped, source)
                // Validate post-seek position did not enter an out-of-bounds / negative state
                val afterPos = try {
                    delegate.getPosition()
                } catch (_: Throwable) {
                    null
                }
                if (afterPos != null && (afterPos < 0L || (dur > 0L && afterPos > dur + 1_000L))) {
                    forceFallbackToLastValidPosition("Post-seek position out of bounds ($afterPos / $dur)")
                }
            } catch (t: Throwable) {
                if (isIllegalSeekOrStateThrowable(t)) {
                    forceFallbackToLastValidPosition("Exception during IPlayer.seekTo($clamped)", t)
                } else {
                    NorthDebugLogger.reportHandledFatalAsync("PLAYER_SEEK_ERROR", "IPlayer.seekTo failed", t)
                    forceFallbackToLastValidPosition("Unexpected seek failure", t)
                }
            }
        }

        override fun seekTime(ms: Long, source: PlayerEventSource) {
            val dur = resolveDurationMs()
            val curPos = try {
                delegate.getPosition() ?: lastValidPositionMs
            } catch (_: Throwable) {
                lastValidPositionMs
            }
            recordHealthyPosition(curPos, dur)
            val targetMs = curPos + ms
            seekTo(targetMs, source)
        }

        override fun handleEvent(event: CSPlayerEvent, source: PlayerEventSource) {
            NorthDebugLogger.logStateTransition("HANDLE_EVENT", "event=$event, src=$source, pos=${lastValidPositionMs}ms")
            try {
                delegate.handleEvent(event, source)
            } catch (t: Throwable) {
                NorthDebugLogger.reportHandledFatalAsync("PLAYER_EVENT_ERROR", "handleEvent($event) threw", t)
                if (isIllegalSeekOrStateThrowable(t)) {
                    forceFallbackToLastValidPosition("IllegalState during handleEvent($event)", t)
                }
            }
        }

        override fun initCallbacks(
            eventHandler: (PlayerEvent) -> Unit,
            requestedListeningPercentages: List<Int>?
        ) {
            val wrappedHandler: (PlayerEvent) -> Unit = { ev ->
                observePlayerEvent(ev)
                eventHandler(ev)
            }
            watchedEventHandlers.add(wrappedHandler)
            delegate.initCallbacks(wrappedHandler, requestedListeningPercentages)
        }

        override fun onStop() {
            NorthDebugLogger.logStateTransition("ON_STOP", "pos=${lastValidPositionMs}ms, dur=${lastKnownDurationMs}ms")
            delegate.onStop()
        }

        override fun release() {
            NorthDebugLogger.logStateTransition("RELEASE", "pos=${lastValidPositionMs}ms, dur=${lastKnownDurationMs}ms")
            delegate.release()
        }

        private fun observePlayerEvent(ev: PlayerEvent) {
            try {
                when (ev) {
                    is PositionEvent -> {
                        recordHealthyPosition(ev.toMs, ev.durationMs)
                    }
                    is PlayEvent -> {
                        NorthDebugLogger.logStateTransition("PLAY", "src=${ev.source}, pos=${lastValidPositionMs}ms")
                        attachExoPlayerProxyIfReady()
                    }
                    is PauseEvent -> {
                        NorthDebugLogger.logStateTransition("PAUSE", "src=${ev.source}, pos=${lastValidPositionMs}ms")
                    }
                    is ErrorEvent -> {
                        val err = ev.error
                        val sinceSeekMs = System.currentTimeMillis() - lastSeekAtMs
                        NorthDebugLogger.log(
                            "ERROR",
                            "PLAYER_EVENT",
                            "ErrorEvent(src=${ev.source}, sinceSeek=${sinceSeekMs}ms, pos=${lastValidPositionMs}ms, dur=${lastKnownDurationMs}ms)",
                            err
                        )
                        if (sinceSeekMs in 0L..SEEK_ERROR_FALLBACK_WINDOW_MS && isIllegalSeekOrStateThrowable(err)) {
                            forceFallbackToLastValidPosition("Player ErrorEvent right after seek", err)
                        } else {
                            NorthDebugLogger.reportHandledFatalAsync(
                                "PLAYER_ERROR_EVENT",
                                "Player ErrorEvent: ${err.javaClass.simpleName}: ${err.message}",
                                err
                            )
                        }
                    }
                    else -> {
                        val clsName = ev.javaClass.simpleName
                        if (clsName.contains("Status", ignoreCase = true) ||
                            clsName.contains("Ended", ignoreCase = true) ||
                            clsName.contains("Attached", ignoreCase = true)
                        ) {
                            NorthDebugLogger.logStateTransition(clsName, ev.toString().take(160))
                            if (clsName.contains("Attached", ignoreCase = true)) {
                                mainHandler.post { attachExoPlayerProxyIfReady() }
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }

        fun wrapExistingEventHandlerIfNeeded() {
            try {
                val field = delegate.javaClass.getDeclaredField("eventHandler")
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val existing = field.get(delegate) as? ((PlayerEvent) -> Unit) ?: return
                if (watchedEventHandlers.contains(existing)) return
                val wrapped: (PlayerEvent) -> Unit = { ev ->
                    observePlayerEvent(ev)
                    existing(ev)
                }
                watchedEventHandlers.add(wrapped)
                field.set(delegate, wrapped)
            } catch (_: Throwable) {
            }
        }

        /**
         * Wraps `CS3IPlayer.exoPlayer` and `playerFrag.playerView?.player` in a dynamic `ExoPlayer`
         * proxy so even direct Media3 timeline scrubber (`DefaultTimeBar` / `PlayerControlView`) seeks
         * pass through our range clamp and illegal-state recovery layer.
         */
        fun attachExoPlayerProxyIfReady() {
            try {
                val exoField = delegate.javaClass.getDeclaredField("exoPlayer")
                exoField.isAccessible = true
                val rawExo = exoField.get(delegate) ?: return
                if (wrappedExoPlayers.contains(rawExo)) return
                if (Proxy.isProxyClass(rawExo.javaClass)) {
                    wrappedExoPlayers.add(rawExo)
                    return
                }

                val classLoader = rawExo.javaClass.classLoader ?: return
                val exoInterface = Class.forName("androidx.media3.exoplayer.ExoPlayer", false, classLoader)
                if (!exoInterface.isAssignableFrom(rawExo.javaClass)) return

                attachMedia3PlayerListener(rawExo, classLoader)

                val handler = SafeExoPlayerInvocationHandler(rawExo)
                val proxy = Proxy.newProxyInstance(classLoader, arrayOf(exoInterface), handler)
                wrappedExoPlayers.add(rawExo)
                wrappedExoPlayers.add(proxy)

                exoField.set(delegate, proxy)

                // Rebind Media3 PlayerView so DefaultTimeBar / PlayerControlView calls seekTo on our guarded proxy!
                try {
                    val getPlayerViewMethod = AbstractPlayerFragment::class.java.getMethod("getPlayerView")
                    val pv: Any? = getPlayerViewMethod.invoke(fragment)
                    if (pv != null) {
                        val setPlayerMethod = pv.javaClass.getMethod(
                            "setPlayer",
                            Class.forName("androidx.media3.common.Player", false, classLoader)
                        )
                        setPlayerMethod.invoke(pv, proxy)
                    }
                } catch (pvErr: Throwable) {
                    NorthDebugLogger.log("WARN", "PLAYER_GUARD", "Could not rebind PlayerView to ExoPlayer proxy", pvErr)
                }

                NorthDebugLogger.logStateTransition("EXO_PROXY_ATTACHED", "Guarding direct ExoPlayer timeline seeks")
            } catch (_: Throwable) {
            }
        }

        private fun attachMedia3PlayerListener(rawExo: Any, classLoader: ClassLoader) {
            try {
                val listenerIface = Class.forName("androidx.media3.common.Player\$Listener", false, classLoader)
                val listenerProxy = Proxy.newProxyInstance(
                    classLoader,
                    arrayOf(listenerIface)
                ) { proxyObj, method, args ->
                    when (method.name) {
                        "onPlaybackStateChanged" -> {
                            val stateInt = (args?.firstOrNull() as? Int) ?: -1
                            val stateName = when (stateInt) {
                                1 -> "STATE_IDLE"
                                2 -> "STATE_BUFFERING"
                                3 -> "STATE_READY"
                                4 -> "STATE_ENDED"
                                else -> "STATE_$stateInt"
                            }
                            val pos = invokeLongNoArgs(rawExo, "getCurrentPosition")
                            val dur = invokeLongNoArgs(rawExo, "getDuration")
                            if (stateInt == 3 && pos >= 0L) {
                                recordHealthyPosition(pos, dur)
                            }
                            NorthDebugLogger.logStateTransition("EXO_$stateName", "pos=${pos}ms, dur=${dur}ms")
                            null
                        }
                        "onIsPlayingChanged" -> {
                            val playing = (args?.firstOrNull() as? Boolean) ?: false
                            val pos = invokeLongNoArgs(rawExo, "getCurrentPosition")
                            val dur = invokeLongNoArgs(rawExo, "getDuration")
                            if (playing && pos >= 0L) recordHealthyPosition(pos, dur)
                            NorthDebugLogger.logStateTransition(if (playing) "EXO_PLAYING" else "EXO_PAUSED", "pos=${pos}ms, dur=${dur}ms")
                            null
                        }
                        "onPlayerError" -> {
                            val err = args?.firstOrNull() as? Throwable
                            val sinceSeek = System.currentTimeMillis() - lastSeekAtMs
                            NorthDebugLogger.log("ERROR", "EXO_ERROR", "onPlayerError(sinceSeek=${sinceSeek}ms, pos=${lastValidPositionMs}ms)", err)
                            if (err != null && sinceSeek in 0L..SEEK_ERROR_FALLBACK_WINDOW_MS && isIllegalSeekOrStateThrowable(err)) {
                                forceFallbackToLastValidPosition("ExoPlayer onPlayerError right after seek", err)
                            }
                            null
                        }
                        "equals" -> proxyObj === args?.firstOrNull()
                        "hashCode" -> System.identityHashCode(proxyObj)
                        "toString" -> "NorthExoListenerProxy"
                        else -> defaultReturnValue(method.returnType)
                    }
                }
                val addListenerMethod = rawExo.javaClass.getMethod("addListener", listenerIface)
                addListenerMethod.invoke(rawExo, listenerProxy)
            } catch (_: Throwable) {
            }
        }

        private inner class SafeExoPlayerInvocationHandler(
            private val target: Any
        ) : InvocationHandler {

            override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
                val name = method.name
                if (name == "equals") {
                    val other = args?.firstOrNull()
                    return other === proxy || other === target
                }
                if (name == "hashCode") {
                    return System.identityHashCode(target)
                }

                // Intercept every ExoPlayer seekTo overload:
                //   seekTo(long positionMs)
                //   seekTo(int mediaItemIndex, long positionMs)
                if (name == "seekTo" && args != null && args.isNotEmpty()) {
                    return handleGuardedExoSeek(method, args)
                }

                return try {
                    if (args == null) method.invoke(target) else method.invoke(target, *args)
                } catch (ite: InvocationTargetException) {
                    throw ite.targetException ?: ite
                }
            }

            private fun handleGuardedExoSeek(method: Method, args: Array<out Any?>): Any? {
                val dur = invokeLongNoArgs(target, "getDuration").let { d ->
                    if (d > 0L && d != TIME_UNSET_MEDIA3) d else resolveDurationMs()
                }
                val curPos = invokeLongNoArgs(target, "getCurrentPosition")
                if (curPos >= 0L) recordHealthyPosition(curPos, dur)

                val playbackState = invokeIntNoArgs(target, "getPlaybackState")
                val mediaItemCount = invokeIntNoArgs(target, "getMediaItemCount")

                // Validate player state before allowing seek
                if (playbackState == 1 /* STATE_IDLE */ || mediaItemCount <= 0) {
                    NorthDebugLogger.logSeekEvent("ExoPlayer.seekTo blocked in illegal state (state=$playbackState, items=$mediaItemCount)")
                    return null
                }

                val patchedArgs = Array<Any?>(args.size) { i -> args[i] }
                var requestedMs = -1L
                var clampedMs = -1L

                if (args.size == 1 && args[0] is Long) {
                    requestedMs = args[0] as Long
                    clampedMs = clampSeekPositionMs(requestedMs, dur, lastValidPositionMs)
                    patchedArgs[0] = clampedMs
                } else if (args.size >= 2 && args[0] is Int && args[1] is Long) {
                    val reqWindow = (args[0] as Int).coerceIn(0, (mediaItemCount - 1).coerceAtLeast(0))
                    requestedMs = args[1] as Long
                    clampedMs = clampSeekPositionMs(requestedMs, dur, lastValidPositionMs)
                    patchedArgs[0] = reqWindow
                    patchedArgs[1] = clampedMs
                }

                NorthDebugLogger.lastRequestedSeekMs = requestedMs
                if (clampedMs != requestedMs && requestedMs != -1L) {
                    NorthDebugLogger.logSeekEvent("ExoPlayer.seekTo clamped raw=${requestedMs}ms -> ${clampedMs}ms (dur=${dur}ms, lastValid=${lastValidPositionMs}ms)")
                } else if (clampedMs >= 0L) {
                    NorthDebugLogger.logSeekEvent("ExoPlayer.seekTo ${clampedMs}ms (dur=${dur}ms)")
                }

                lastSeekAtMs = System.currentTimeMillis()
                return try {
                    method.invoke(target, *patchedArgs)
                } catch (ite: InvocationTargetException) {
                    val realErr = ite.targetException ?: ite
                    forceFallbackToLastValidPosition("ExoPlayer.seekTo threw ${realErr.javaClass.simpleName}", realErr)
                    null
                } catch (t: Throwable) {
                    forceFallbackToLastValidPosition("ExoPlayer.seekTo failed", t)
                    null
                }
            }
        }
    }

    private fun invokeLongNoArgs(target: Any, methodName: String): Long {
        return try {
            val m = target.javaClass.getMethod(methodName)
            (m.invoke(target) as? Long) ?: -1L
        } catch (_: Throwable) {
            -1L
        }
    }

    private fun invokeIntNoArgs(target: Any, methodName: String): Int {
        return try {
            val m = target.javaClass.getMethod(methodName)
            (m.invoke(target) as? Int) ?: -1
        } catch (_: Throwable) {
            -1
        }
    }

    private fun defaultReturnValue(type: Class<*>): Any? {
        return when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> '\u0000'
            else -> null
        }
    }
}
