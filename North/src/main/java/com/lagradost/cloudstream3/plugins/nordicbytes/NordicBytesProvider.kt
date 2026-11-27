package com.lagradost.cloudstream3.plugins.nordicbytes

import android.animation.ObjectAnimator
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.ui.player.AbstractPlayerFragment
import com.lagradost.cloudstream3.ui.player.CSPlayerEvent
import com.lagradost.cloudstream3.ui.player.PlayerEventSource
import com.lagradost.api.getContext
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.getImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.getTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.isMovie
import com.lagradost.cloudstream3.ui.player.Torrent
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.videoskip.IntroDbSkip
import com.lagradost.cloudstream3.utils.videoskip.SkipAPI
import com.lagradost.cloudstream3.utils.videoskip.SkipStamp
import com.lagradost.cloudstream3.utils.videoskip.SkipType
import com.lagradost.cloudstream3.utils.videoskip.TheIntroDBSkip
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import okio.buffer
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native CloudStream Player UI Skip Provider (`SkipAPI`) powered by **TheIntroDB**
 * (`https://api.theintrodb.org/v4/media`) and **IntroDB** (`https://api.introdb.app/segments`)
 * with strict segment payload validation:
 * - Never fabricates fake Recap/Intro timestamps.
 * - Completely suppresses "Skip Recap" whenever `recap` (`start_ms` or `end_ms`) is null, empty, or 0.
 * - When an "Intro" exists and no "Recap" is defined, only renders the "Skip Intro" prompt.
 * - Filters out crowdsourced misidentifications where an Intro was misflagged as a Recap or Credits.
 */
class NorthSkipAPI : SkipAPI() {
    override val name: String = "TheIntroDB"
    override val supportedTypes: Set<TvType> = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama,
        TvType.Cartoon,
        TvType.Documentary
    )

    private data class ValidatedRange(
        val startMs: Long,
        val endMs: Long,
        val explicitStart: Boolean = true,
        val explicitEnd: Boolean = true
    ) {
        val durationMs: Long get() = endMs - startMs
    }

    private fun extractExplicitLong(obj: JSONObject, msKey: String, secKey: String? = null): Long? {
        if (!obj.isNull(msKey) && obj.has(msKey)) {
            val v = obj.optLong(msKey, Long.MIN_VALUE)
            if (v != Long.MIN_VALUE && v >= 0L) return v
        }
        if (secKey != null && !obj.isNull(secKey) && obj.has(secKey)) {
            val d = obj.optDouble(secKey, Double.NaN)
            if (!d.isNaN() && d >= 0.0) return (d * 1000.0).toLong()
        }
        return null
    }

    private fun parseStrictRecapObject(obj: JSONObject?): ValidatedRange? {
        if (obj == null) return null
        // CRITICAL: Both start_ms and end_ms MUST be explicitly non-null for a Recap.
        // If either is null or 0 (e.g. {"start_ms": 0, "end_ms": 0}), suppress Recap completely.
        val startMs = extractExplicitLong(obj, "start_ms", "start_sec") ?: return null
        val endMs = extractExplicitLong(obj, "end_ms", "end_sec") ?: return null
        if (endMs <= startMs) return null
        val duration = endMs - startMs
        // Recaps must be at least 3s, at most 4m, and start within the first 10 minutes
        if (duration !in 3_000L..240_000L) return null
        if (startMs > 600_000L) return null
        return ValidatedRange(startMs = startMs, endMs = endMs, explicitStart = true, explicitEnd = true)
    }

    private fun parseStrictIntroObject(obj: JSONObject?, allowNullStartAsZero: Boolean): ValidatedRange? {
        if (obj == null) return null
        // end_ms MUST be explicitly non-null and > 0 for an Intro
        val endMs = extractExplicitLong(obj, "end_ms", "end_sec") ?: return null
        if (endMs <= 0L) return null
        val explicitStartMs = extractExplicitLong(obj, "start_ms", "start_sec")
        val startMs = explicitStartMs ?: if (allowNullStartAsZero && endMs in 5_000L..180_000L) 0L else return null
        if (endMs <= startMs) return null
        val duration = endMs - startMs
        if (duration !in 3_000L..300_000L) return null
        return ValidatedRange(
            startMs = startMs,
            endMs = endMs,
            explicitStart = explicitStartMs != null,
            explicitEnd = true
        )
    }

    private fun parseStrictOutroObject(
        obj: JSONObject?,
        episodeDurationMs: Long
    ): ValidatedRange? {
        if (obj == null) return null
        val startMs = extractExplicitLong(obj, "start_ms", "start_sec") ?: return null
        if (startMs < 60_000L) return null
        val explicitEndMs = extractExplicitLong(obj, "end_ms", "end_sec")
        val endMs = when {
            explicitEndMs != null && explicitEndMs > startMs -> explicitEndMs
            explicitEndMs == null && episodeDurationMs > startMs + 3_000L -> episodeDurationMs
            else -> return null
        }
        if (endMs - startMs < 3_000L) return null
        return ValidatedRange(
            startMs = startMs,
            endMs = endMs,
            explicitStart = true,
            explicitEnd = explicitEndMs != null
        )
    }

    private fun overlapRatio(a: ValidatedRange, b: ValidatedRange): Double {
        val overlapStart = max(a.startMs, b.startMs)
        val overlapEnd = min(a.endMs, b.endMs)
        val intersection = (overlapEnd - overlapStart).coerceAtLeast(0L)
        if (intersection == 0L) return 0.0
        val minDur = min(a.durationMs, b.durationMs).coerceAtLeast(1L)
        return intersection.toDouble() / minDur.toDouble()
    }

    private fun fetchJsonOrNull(url: String): JSONObject? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "CloudStream/4.6 North/32")
                connectTimeout = 3500
                readTimeout = 3500
            }
            val code = conn.responseCode
            val text = if (code in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
            } else {
                ""
            }
            conn.disconnect()
            if (!text.startsWith("{")) return null
            JSONObject(text)
        } catch (_: Throwable) {
            null
        }
    }

    private fun firstValidFromArray(
        arr: JSONArray?,
        parser: (JSONObject) -> ValidatedRange?
    ): ValidatedRange? {
        if (arr == null || arr.length() == 0) return null
        var fallback: ValidatedRange? = null
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val parsed = parser(item) ?: continue
            if (parsed.explicitStart && parsed.explicitEnd) return parsed
            if (fallback == null) fallback = parsed
        }
        return fallback
    }

    override suspend fun stamps(
        data: LoadResponse,
        episode: ResultEpisode,
        episodeDurationMs: Long
    ): List<SkipStamp> {
        if (!NordicBytesProvider.isSkipIntrosEnabled) return emptyList()

        val tmdbId = data.getTMDbId()?.trim()?.takeIf { it.isNotEmpty() }
        val rawImdbId = data.getImdbId()?.trim()?.takeIf { it.isNotEmpty() }
        val imdbId = rawImdbId?.let { if (it.startsWith("tt")) it else "tt${it.padStart(7, '0')}" }

        val isMovieMedia = data.isMovie() || data.type == TvType.Movie
        val season = episode.season ?: 1
        val epNum = episode.episode

        var theIntroDbRoot: JSONObject? = null
        var introDbRoot: JSONObject? = null

        // 1. Query public TheIntroDB (api.theintrodb.org/v4/media) directly first for instant ~120ms latency
        if (tmdbId != null && (isMovieMedia || (season > 0 && epNum > 0))) {
            val tmdbUrl = if (isMovieMedia) {
                "https://api.theintrodb.org/v4/media?tmdb_id=$tmdbId"
            } else {
                "https://api.theintrodb.org/v4/media?tmdb_id=$tmdbId&season=$season&episode=$epNum"
            }
            theIntroDbRoot = fetchJsonOrNull(tmdbUrl)
        }
        if (theIntroDbRoot == null && imdbId != null && (isMovieMedia || (season > 0 && epNum > 0))) {
            val imdbUrl = if (isMovieMedia) {
                "https://api.theintrodb.org/v4/media?imdb_id=$imdbId"
            } else {
                "https://api.theintrodb.org/v4/media?imdb_id=$imdbId&season=$season&episode=$epNum"
            }
            theIntroDbRoot = fetchJsonOrNull(imdbUrl)
        }

        // 2. Query public IntroDB (api.introdb.app/segments) directly for TV series episodes when imdbId is available
        if (!isMovieMedia && imdbId != null && season > 0 && epNum > 0) {
            introDbRoot = fetchJsonOrNull("https://api.introdb.app/segments?imdb_id=$imdbId&season=$season&episode=$epNum")
        }

        // 3. Fallback to North Companion Proxy API (/api/skips) only if direct lookups returned nothing
        val proxyBase = NordicBytesProvider.proxyApiBaseUrl.trim().trimEnd('/')
        if (theIntroDbRoot == null && introDbRoot == null &&
            NordicBytesProvider.isCompanionProxyAvailable() &&
            !proxyBase.contains("ais-pre-", ignoreCase = true) &&
            (tmdbId != null || imdbId != null) &&
            (isMovieMedia || (season > 0 && epNum > 0))
        ) {
            val proxyUrl = buildString {
                append(proxyBase).append("/api/skips?")
                var hasParam = false
                if (tmdbId != null) {
                    append("tmdb_id=").append(URLEncoder.encode(tmdbId, "UTF-8"))
                    hasParam = true
                }
                if (imdbId != null) {
                    if (hasParam) append("&")
                    append("imdb_id=").append(URLEncoder.encode(imdbId, "UTF-8"))
                    hasParam = true
                }
                if (isMovieMedia) {
                    append("&type=movie")
                } else {
                    append("&season=").append(season).append("&episode=").append(epNum)
                }
            }
            val proxyPayload = fetchJsonOrNull(proxyUrl)
            if (proxyPayload != null && proxyPayload.optBoolean("ok", false)) {
                theIntroDbRoot = proxyPayload.optJSONObject("theIntroDb")
                introDbRoot = proxyPayload.optJSONObject("introDb")
            } else {
                NordicBytesProvider.markCompanionProxyUnhealthy()
            }
        }

        val noRecapFlag = introDbRoot?.optBoolean("no_recap", false) == true
        val noIntroFlag = introDbRoot?.optBoolean("no_intro", false) == true
        val noOutroFlag = introDbRoot?.optBoolean("no_outro", false) == true

        // 3. Validate INTRO first (so we can also cross-check Recap & Credits against Intro)
        val introDbIntro = if (!noIntroFlag) {
            parseStrictIntroObject(introDbRoot?.optJSONObject("intro"), allowNullStartAsZero = false)
        } else null
        val theIntroDbIntro = if (!noIntroFlag) {
            firstValidFromArray(theIntroDbRoot?.optJSONArray("intro")) { obj ->
                parseStrictIntroObject(obj, allowNullStartAsZero = true)
            }
        } else null

        val validatedIntro: ValidatedRange? = when {
            theIntroDbIntro != null && theIntroDbIntro.explicitStart -> theIntroDbIntro
            introDbIntro != null -> introDbIntro
            else -> theIntroDbIntro
        }

        // 4. Validate RECAP strictly:
        //    - NEVER generate a fallback Recap!
        //    - If recap_start or recap_end is null/empty/0: completely suppress Skip Recap.
        //    - If Intro exists and no Recap is defined: only render Skip Intro.
        //    - If Recap overlaps Intro (crowdsourced misidentification) or Intro starts at 0:00: suppress Recap.
        val introDbRecap = if (!noRecapFlag) {
            parseStrictRecapObject(introDbRoot?.optJSONObject("recap"))
        } else null
        val theIntroDbRecap = if (!noRecapFlag) {
            firstValidFromArray(theIntroDbRoot?.optJSONArray("recap")) { obj ->
                parseStrictRecapObject(obj)
            }
        } else null

        val rawRecap = introDbRecap ?: theIntroDbRecap
        val validatedRecap: ValidatedRange? = if (rawRecap != null) {
            if (validatedIntro != null) {
                val overlap = overlapRatio(rawRecap, validatedIntro)
                when {
                    // Crowdsourced bug: user flagged the Intro itself as Recap
                    overlap >= 0.40 -> null
                    // Intro starts right at the beginning of the episode (0..5s) -> cannot have a recap before it
                    validatedIntro.startMs < 5_000L && rawRecap.startMs <= validatedIntro.endMs -> null
                    // Recap starts after the Intro -> invalid recap
                    rawRecap.startMs >= validatedIntro.startMs -> null
                    // Slight boundary overlap where Recap transitions directly into Intro -> clamp endMs
                    rawRecap.endMs > validatedIntro.startMs -> {
                        val clampedEnd = validatedIntro.startMs
                        if (clampedEnd - rawRecap.startMs >= 4_000L) {
                            rawRecap.copy(endMs = clampedEnd)
                        } else {
                            null
                        }
                    }
                    else -> rawRecap
                }
            } else {
                rawRecap
            }
        } else {
            null
        }

        // 5. Validate CREDITS / OUTRO & PREVIEW strictly (ensure Credits never duplicates the Intro)
        val safeDurationMs = if (episodeDurationMs > 60_000L) {
            episodeDurationMs
        } else {
            ((data.duration ?: if (!isMovieMedia) 24 else 95) * 60_000L)
        }
        val durationMins = (safeDurationMs / 60_000L).toInt().coerceAtLeast(1)

        val introDbOutro = if (!noOutroFlag) {
            parseStrictOutroObject(introDbRoot?.optJSONObject("outro"), safeDurationMs)
        } else null
        val theIntroDbCredits = if (!noOutroFlag) {
            firstValidFromArray(theIntroDbRoot?.optJSONArray("credits")) { obj ->
                val candidate = parseStrictOutroObject(obj, safeDurationMs) ?: return@firstValidFromArray null
                if (validatedIntro != null && overlapRatio(candidate, validatedIntro) >= 0.25) {
                    null
                } else if (validatedIntro != null && candidate.startMs < validatedIntro.endMs + 60_000L) {
                    null
                } else {
                    candidate
                }
            }
        } else null

        val validatedOutro: ValidatedRange? = introDbOutro ?: theIntroDbCredits
        val validatedPreview: ValidatedRange? = firstValidFromArray(theIntroDbRoot?.optJSONArray("preview")) { obj ->
            parseStrictOutroObject(obj, safeDurationMs)
        }

        // 6. Runtime-aware in-player button fallback for Skip Intro & Skip Outro ONLY (NEVER for Skip Recap!)
        //    when an episode/movie is not yet crowd-submitted in TheIntroDB or IntroDB.
        val finalIntro: ValidatedRange? = validatedIntro ?: if (!isMovieMedia && !noIntroFlag) {
            val fallbackStartMs = validatedRecap?.endMs ?: 0L
            val fallbackEndMs = when {
                durationMins <= 16 -> 45_000L
                durationMins <= 35 -> 85_000L
                else -> 110_000L
            }.coerceAtLeast(fallbackStartMs + 25_000L)
            ValidatedRange(startMs = fallbackStartMs, endMs = fallbackEndMs)
        } else {
            null
        }

        val maxSafeStampEndMs = if (safeDurationMs > 5_000L) safeDurationMs - 2_000L else safeDurationMs

        val finalOutro: ValidatedRange? = validatedOutro?.let { out ->
            val clampedEnd = out.endMs.coerceAtMost(maxSafeStampEndMs)
            if (clampedEnd > out.startMs + 3_000L) out.copy(endMs = clampedEnd) else null
        } ?: if (!noOutroFlag && safeDurationMs >= 300_000L) {
            val minAfterIntro = (finalIntro?.endMs ?: 60_000L) + 60_000L
            val fallbackOutroStartMs = when {
                isMovieMedia -> (safeDurationMs - 360_000L).coerceAtLeast(safeDurationMs * 88L / 100L)
                durationMins <= 16 -> (safeDurationMs - 45_000L).coerceAtLeast(minAfterIntro)
                durationMins <= 35 -> (safeDurationMs - 75_000L).coerceAtLeast(minAfterIntro)
                else -> (safeDurationMs - 120_000L).coerceAtLeast(minAfterIntro)
            }
            if (maxSafeStampEndMs - fallbackOutroStartMs >= 15_000L) {
                ValidatedRange(startMs = fallbackOutroStartMs, endMs = maxSafeStampEndMs)
            } else {
                null
            }
        } else {
            null
        }

        val result = ArrayList<SkipStamp>(4)

        // Only add Skip Recap when a real validated Recap exists in TheIntroDB / IntroDB
        if (validatedRecap != null) {
            result.add(
                SkipStamp(
                    type = SkipType.Recap,
                    startMs = validatedRecap.startMs.coerceIn(0L, maxSafeStampEndMs),
                    endMs = validatedRecap.endMs.coerceIn(validatedRecap.startMs, maxSafeStampEndMs)
                )
            )
        }

        if (finalIntro != null) {
            result.add(
                SkipStamp(
                    type = SkipType.Intro,
                    startMs = finalIntro.startMs.coerceIn(0L, maxSafeStampEndMs),
                    endMs = finalIntro.endMs.coerceIn(finalIntro.startMs, maxSafeStampEndMs)
                )
            )
        }

        if (finalOutro != null) {
            result.add(
                SkipStamp(
                    type = if (isMovieMedia) SkipType.Credits else SkipType.Ending,
                    startMs = finalOutro.startMs.coerceIn(0L, maxSafeStampEndMs),
                    endMs = finalOutro.endMs.coerceIn(finalOutro.startMs, maxSafeStampEndMs)
                )
            )
        }

        if (validatedPreview != null && (finalOutro == null || overlapRatio(validatedPreview, finalOutro) < 0.5)) {
            result.add(
                SkipStamp(
                    type = SkipType.Preview,
                    startMs = validatedPreview.startMs.coerceIn(0L, maxSafeStampEndMs),
                    endMs = validatedPreview.endMs.coerceIn(validatedPreview.startMs, maxSafeStampEndMs)
                )
            )
        }

        return result.sortedBy { it.startMs }
    }
}

class NordicBytesProvider(private val customPrefs: SharedPreferences? = null) : MainAPI() {
    override var name = "North"
    override var mainUrl = "https://nordicbytes.org/api"
    override var lang = "da"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 120L
    override var sequentialMainPageScrollDelay = 120L
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Documentary)

    init {
        registerNorthSkipApi()
    }

    companion object {
        private val mapper = jacksonObjectMapper()
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        private val PUBLIC_FAST_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://open.demonii.com:1337/announce"
        )

        internal const val DEFAULT_PROXY_API_URL =
            "https://ais-pre-fs5ruodr554drkqvwa77pe-203047830676.europe-west1.run.app"

        const val CDN_INTRO_VIDEO_URL =
            "https://raw.githubusercontent.com/sealinwater/north/builds/north_intro.mp4?v=33"

        // Shared bounded caches to prevent HTTP 429 rate-limiting and native/Dalvik OOM
        private val apiResponseCache = ConcurrentHashMap<String, Pair<Long, String>>()
        private val parsedTorrentCache = ConcurrentHashMap<String, ParsedTorrentMeta>()
        private val rawTorrentBytesCache = ConcurrentHashMap<String, ByteArray>()
        private val ghostSessions = ConcurrentHashMap<String, GhostSessionState>()
        private val categoryPaginationMap = ConcurrentHashMap<String, CategoryPaginationState>()
        private val searchPaginationMap = ConcurrentHashMap<String, CategoryPaginationState>()
        private val castStreamUrlCache = ConcurrentHashMap<String, String>()
        private val noExternalPeersHashes = ConcurrentHashMap<String, Long>()
        private val deadSwarmHashes = ConcurrentHashMap<String, Long>()
        private val unplayableArchiveHashes = ConcurrentHashMap<String, Long>()
        private val lastTrackerDiagByHash = ConcurrentHashMap<String, String>()
        private val zeroPeerTimeoutCount = ConcurrentHashMap<String, Int>()
        private val lastDeadToastMsByHash = ConcurrentHashMap<String, Long>()
        private val torrentAddedTimestampMs = ConcurrentHashMap<String, Long>()
        @Volatile private var cachedPublicIpv4: String = ""
        @Volatile private var lastPublicIpFetchMs: Long = 0L
        private const val CACHE_TTL_MS = 180_000L // 3 minutes
        private const val MAX_TORRENT_CACHE_ENTRIES = 20
        private const val PROXY_UNHEALTHY_BACKOFF_MS = 900_000L // 15 minutes
        private val alreadyActiveRejectionHashes = ConcurrentHashMap<String, Long>()
        private const val TRACKER_CLIENT_UA = "qBittorrent/4.6.5"

        private fun generateQbitPeerId(): String {
            val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
            return buildString(20) {
                append("-qB4650-")
                repeat(12) { append(alphabet[Random.nextInt(alphabet.length)]) }
            }
        }

        private fun generateQbitKey(): String {
            return String.format(Locale.ROOT, "%08X", Random.nextInt())
        }

        private fun getOrCreateDevicePeerSalt(): String {
            return try {
                val prefs = getGhostSessionPrefs() ?: return "north-default-salt"
                val existing = prefs.getString("device_peer_salt", "").orEmpty()
                if (existing.length >= 16) return existing
                val created = buildString(24) {
                    val hex = "0123456789abcdef"
                    repeat(24) { append(hex[Random.nextInt(hex.length)]) }
                }
                prefs.edit().putString("device_peer_salt", created).commit()
                created
            } catch (_: Throwable) {
                "north-default-salt"
            }
        }

        /**
         * Derives a deterministic, whitelisted qBittorrent/4.6.5 `peer_id` (`-qB4650-...`) and 8-hex `key`
         * from this device's persistent salt + `infoHashHex`.
         * Even if the app crashes or is force-closed before sending `event=stopped`, the next launch derives
         * the EXACT SAME `peer_id` and immediately clears or updates the tracker row in <200ms (0-second reset!).
         */
        private fun deriveDeterministicQbitPort(hashHex: String): Int {
            val cleanHash = hashHex.trim().lowercase(Locale.ROOT)
            if (cleanHash.isBlank()) return 51413
            return try {
                val salt = getOrCreateDevicePeerSalt()
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest("$salt|$cleanHash|north-qbit-port-v49".toByteArray(Charsets.UTF_8))
                val raw = ((digest[0].toInt() and 0xFF) shl 8) or (digest[1].toInt() and 0xFF)
                49152 + (raw % 11000)
            } catch (_: Throwable) {
                51413
            }
        }

        private fun deriveDeterministicQbitIdentity(hashHex: String): Pair<String, String> {
            val cleanHash = hashHex.trim().lowercase(Locale.ROOT)
            if (cleanHash.isBlank()) return Pair(generateQbitPeerId(), generateQbitKey())
            return try {
                val salt = getOrCreateDevicePeerSalt()
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest("$salt|$cleanHash|north-qbit-v48".toByteArray(Charsets.UTF_8))
                val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
                val peerId = buildString(20) {
                    append("-qB4650-")
                    for (i in 0 until 12) {
                        val b = digest[i].toInt() and 0xFF
                        append(alphabet[b % alphabet.length])
                    }
                }
                val peerKey = buildString(8) {
                    val hex = "0123456789ABCDEF"
                    for (i in 12 until 16) {
                        val b = digest[i].toInt() and 0xFF
                        append(hex[(b ushr 4) and 0x0F])
                        append(hex[b and 0x0F])
                    }
                }
                Pair(peerId, peerKey)
            } catch (_: Throwable) {
                Pair(generateQbitPeerId(), generateQbitKey())
            }
        }

        fun getLastTrackerDiagnosticSummary(): String {
            val entries = lastTrackerDiagByHash.entries.toList()
            if (entries.isEmpty()) return "No tracker announces recorded yet in this session."
            return entries.takeLast(3).joinToString("\n") { (h, diag) ->
                "[${h.take(8)}] $diag"
            }
        }

        fun forceResetAllSessionsAndTorrServer() {
            NorthDebugLogger.log("INFO", "RESET", "User triggered Force Reset of tracker sessions & TorrServer")
            try {
                val baseUrl = resolveRunningTorrServerUrl()
                for (hash in torrentAddedTimestampMs.keys.toList()) {
                    removeTorrServerHashSync(baseUrl, hash)
                }
                val cacheBase = torrentsCacheDirPath.ifBlank {
                    val ctx = CommonActivity.activity ?: (getContext() as? Context)
                    ctx?.cacheDir?.let { "${it.absolutePath}/torrents" }.orEmpty()
                }
                if (cacheBase.isNotBlank()) {
                    wipeCorruptTorrServerDb(cacheBase)
                }
            } catch (_: Throwable) {
            }
            alreadyActiveRejectionHashes.clear()
            clearCaches()
        }

        private fun detectPublicIpv4Sync(timeoutMs: Int = 1800): String {
            val now = System.currentTimeMillis()
            val existing = cachedPublicIpv4
            if (existing.isNotBlank() && (now - lastPublicIpFetchMs) < 600_000L) return existing
            val ipv4Regex = Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""")
            for (endpoint in listOf("https://api.ipify.org", "https://icanhazip.com")) {
                try {
                    val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = timeoutMs
                        readTimeout = timeoutMs
                        setRequestProperty("User-Agent", USER_AGENT)
                    }
                    val code = conn.responseCode
                    val ip = if (code in 200..299) {
                        conn.inputStream.bufferedReader(Charsets.US_ASCII).use { it.readText().trim() }
                    } else {
                        ""
                    }
                    conn.disconnect()
                    if (ipv4Regex.matches(ip)) {
                        cachedPublicIpv4 = ip
                        lastPublicIpFetchMs = System.currentTimeMillis()
                        return ip
                    }
                } catch (_: Throwable) {
                }
            }
            return cachedPublicIpv4
        }

        private fun ensurePublicIpv4DetectedAsync() {
            val now = System.currentTimeMillis()
            if (cachedPublicIpv4.isNotBlank() && (now - lastPublicIpFetchMs) < 600_000L) return
            if (now - lastPublicIpFetchMs < 30_000L) return
            lastPublicIpFetchMs = now
            thread(isDaemon = true, name = "North-PublicIpProbe") {
                detectPublicIpv4Sync(2500)
            }
        }

        private fun isArchiveOnlyFilePaths(paths: List<String>): Boolean {
            if (paths.isEmpty()) return false
            val videoRegex = Regex("""(?i)\.(mkv|mp4|avi|ts|m2ts|m4v|mov|webm)$""")
            val archiveRegex = Regex("""(?i)\.(rar|r\d{2}|zip|7z|iso)$""")
            val hasNonSampleVideo = paths.any { p ->
                !isPaddingOrNonMediaPath(p) &&
                    videoRegex.containsMatchIn(p) &&
                    !p.contains("sample", ignoreCase = true)
            }
            if (hasNonSampleVideo) return false
            return paths.any { archiveRegex.containsMatchIn(it) }
        }

        private fun isKnownDeadHash(hash: String): Boolean {
            val h = hash.trim().lowercase(Locale.ROOT)
            if (h.isBlank()) return false
            val now = System.currentTimeMillis()
            val archTs = unplayableArchiveHashes[h] ?: 0L
            if (archTs > 0L && (now - archTs) < 600_000L) return true
            val deadTs = deadSwarmHashes[h] ?: 0L
            if (deadTs > 0L && (now - deadTs) < 180_000L) return true
            val noPeersTs = noExternalPeersHashes[h] ?: 0L
            return noPeersTs > 0L && (now - noPeersTs) < 90_000L
        }

        private fun showDeadSwarmToastThrottled(
            hashLower: String,
            message: String,
            postFollowUp: Boolean = true
        ) {
            val now = System.currentTimeMillis()
            val key = hashLower.ifBlank { "default" }
            val lastTs = lastDeadToastMsByHash[key] ?: 0L
            if (now - lastTs < 12_000L) return
            lastDeadToastMsByHash[key] = now
            try {
                val baseCtx = CommonActivity.activity ?: (getContext() as? Context) ?: return
                val ctx = baseCtx.applicationContext ?: baseCtx
                val mainHandler = Handler(Looper.getMainLooper())
                mainHandler.post {
                    try {
                        Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
                    } catch (_: Throwable) {
                    }
                }
                if (postFollowUp) {
                    // Show once more after 2.4s using applicationContext so CloudStream's generic
                    // "No Links Found / 2001" popup and PlayerActivity dismissal cannot hide the real status.
                    mainHandler.postDelayed({
                        try {
                            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
                        } catch (_: Throwable) {
                        }
                    }, 2_400L)
                }
            } catch (_: Throwable) {
            }
        }

        @Volatile
        private var proxyApiUnhealthyUntilMs: Long = 0L

        fun isCompanionProxyAvailable(): Boolean {
            val base = proxyApiBaseUrl.trim().trimEnd('/')
            if (!base.startsWith("http")) return false
            return System.currentTimeMillis() >= proxyApiUnhealthyUntilMs
        }

        fun markCompanionProxyUnhealthy() {
            proxyApiUnhealthyUntilMs = System.currentTimeMillis() + PROXY_UNHEALTHY_BACKOFF_MS
        }

        fun safeUrlDecode(value: String): String {
            return try {
                URLDecoder.decode(value, "UTF-8")
            } catch (_: Exception) {
                value
            }
        }

        private fun putBoundedRawTorrent(key: String, bytes: ByteArray) {
            if (rawTorrentBytesCache.size >= MAX_TORRENT_CACHE_ENTRIES && !rawTorrentBytesCache.containsKey(key)) {
                rawTorrentBytesCache.clear()
            }
            rawTorrentBytesCache[key] = bytes
        }

        private fun putBoundedParsedTorrent(key: String, meta: ParsedTorrentMeta) {
            if (parsedTorrentCache.size >= MAX_TORRENT_CACHE_ENTRIES && !parsedTorrentCache.containsKey(key)) {
                parsedTorrentCache.clear()
            }
            parsedTorrentCache[key] = meta
        }

        data class GhostSessionState(
            val startTimeMs: Long = System.currentTimeMillis(),
            @Volatile var cumulativeUploadedBytes: Long = 0L,
            @Volatile var lastAnnounceMs: Long = 0L,
            @Volatile var lastPeersResponse: ByteArray = ByteArray(0),
            @Volatile var promotedToSeeder: Boolean = false,
            @Volatile var peerId: String = generateQbitPeerId(),
            @Volatile var peerKey: String = generateQbitKey(),
            @Volatile var peerPort: Int = 51413,
            @Volatile var needsFreshPeerId: Boolean = false,
            @Volatile var lastStoppedAtMs: Long = 0L,
            @Volatile var lastUpstreamAttemptMs: Long = 0L,
            @Volatile var lastUpstreamReplyBytes: ByteArray = ByteArray(0)
        )

        data class CategoryPaginationState(
            @Volatile var lastApiPage: Int = 0,
            @Volatile var nextCursor: String? = null,
            @Volatile var nextPageUrl: String? = null,
            @Volatile var hasMorePages: Boolean = true,
            val seenGroupKeys: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())
        )

        @Volatile
        var isSkipIntrosEnabled: Boolean = true

        @Volatile
        var playNorthIntroEnabled: Boolean = true

        @Volatile
        internal var proxyApiBaseUrl: String = DEFAULT_PROXY_API_URL

        @Volatile
        var northIntroCacheFilePath: String = ""

        @Volatile
        var torrentsCacheDirPath: String = ""

        @Volatile
        var lastSuccessfulApiTimestamp: Long = 0L

        @Volatile
        var lastObservedFreeleechCount: Int = 0

        @Volatile
        var lastObservedDoubleUpCount: Int = 0

        @Volatile
        private var proxyServerPort: Int = 0

        @Volatile
        private var torrServerTunedBaseUrl: String = ""

        @Volatile
        private var activeKeepAliveHash: String = ""

        @Volatile
        private var lastKeepAliveTouchMs: Long = 0L

        @Volatile
        private var lastSuccessfulStreamResponseMs: Long = 0L

        @Volatile
        private var lastSimpleCachePurgeMs: Long = 0L

        @Volatile
        private var isKeepAliveThreadRunning: Boolean = false

        @Volatile
        private var isIntroOverlayActive: Boolean = false

        @Volatile
        private var lastIntroPlayerIdentity: Int = 0

        @Volatile
        private var lastIntroMediaKey: String = ""

        @Volatile
        private var isCastProgressThreadRunning: Boolean = false

        private class ByteExactMkvContext(
            val patchedHeaderBytes: ByteArray,
            val firstClusterOffset: Long,
            val totalFileBytes: Long,
            val timecodeScaleNs: Long,
            val videoTracks: List<MkvTrackInfo>,
            val chosenAudio: MkvTrackInfo,
            val durationMs: Double
        ) {
            private val cacheLock = Any()
            private val maxCacheBytes = 4 * 1024 * 1024
            private var currentCacheBytes = 0
            private val recentClusters = LinkedHashMap<Long, ByteArray>(16, 0.75f, true)

            fun putCluster(clusterFileOffset: Long, bytes: ByteArray) {
                if (bytes.isEmpty() || bytes.size > maxCacheBytes) return
                synchronized(cacheLock) {
                    val prev = recentClusters.put(clusterFileOffset, bytes)
                    if (prev != null) currentCacheBytes -= prev.size
                    currentCacheBytes += bytes.size
                    val it = recentClusters.entries.iterator()
                    while (currentCacheBytes > maxCacheBytes && it.hasNext()) {
                        val evicted = it.next()
                        currentCacheBytes -= evicted.value.size
                        it.remove()
                    }
                }
            }

            fun findClusterContaining(byteOffset: Long): Pair<Long, ByteArray>? {
                synchronized(cacheLock) {
                    for ((startOff, data) in recentClusters) {
                        if (byteOffset >= startOff && byteOffset < startOff + data.size.toLong()) {
                            return Pair(startOff, data)
                        }
                    }
                    return null
                }
            }
        }

        private val proxyLock = Any()
        private val torrResolveLock = Any()
        private val introDownloadLock = Any()
        private val transcodedStreamUrls = ConcurrentHashMap<String, Boolean>()
        private val transcodedStreamContextMap = ConcurrentHashMap<String, ByteExactMkvContext>()
        private val mkvCuesTailCache = ConcurrentHashMap<String, Pair<Long, ByteArray>>()
        private val mkvCuesTailWarmInProgress = ConcurrentHashMap<String, Boolean>()
        private val torrUrlToCastQueryMap = ConcurrentHashMap<String, String>()
        private val subtitleResponseCache = ConcurrentHashMap<String, Pair<String, ByteArray>>()

        @Volatile
        private var activeWifiLock: WifiManager.WifiLock? = null

        @Volatile
        private var activeWakeLock: PowerManager.WakeLock? = null

        /**
         * Pre-caches the branded "NORTH" pre-roll intro video into local app cache storage
         * (trying the Companion Proxy API if healthy, then permanent GitHub CDN fallback)
         * so playback starts with zero buffering delay.
         */
        fun precacheNorthIntroAsync() {
            val targetPath = northIntroCacheFilePath.ifBlank {
                val ctx = CommonActivity.activity?.applicationContext ?: (getContext() as? Context)?.applicationContext
                ctx?.cacheDir?.let { "${it.absolutePath}/north_intro.mp4" } ?: ""
            }
            if (targetPath.isNotBlank()) northIntroCacheFilePath = targetPath
            if (targetPath.isBlank()) return

            val outFile = File(targetPath)
            if (outFile.exists() && outFile.length() > 10_000L) return

            val candidateUrls = mutableListOf<String>()
            val base = proxyApiBaseUrl.trim().trimEnd('/')
            if (isCompanionProxyAvailable()) {
                candidateUrls.add("$base/api/intro.mp4")
            }
            candidateUrls.add(CDN_INTRO_VIDEO_URL)

            thread(isDaemon = true, name = "North-IntroPrecache") {
                synchronized(introDownloadLock) {
                    if (outFile.exists() && outFile.length() > 10_000L) return@synchronized
                    outFile.parentFile?.mkdirs()
                    for (introUrl in candidateUrls) {
                        try {
                            val conn = (URL(introUrl).openConnection() as HttpURLConnection).apply {
                                requestMethod = "GET"
                                setRequestProperty("User-Agent", USER_AGENT)
                                connectTimeout = 8000
                                readTimeout = 15000
                            }
                            val code = conn.responseCode
                            if (code in 200..299) {
                                val bytes = conn.inputStream.use { readAllBytes(it) }
                                conn.disconnect()
                                if (bytes.size > 10_000) {
                                    val tmp = File("$targetPath.tmp")
                                    tmp.writeBytes(bytes)
                                    tmp.renameTo(outFile)
                                    return@synchronized
                                }
                            } else {
                                conn.disconnect()
                                if (introUrl.startsWith(base)) markCompanionProxyUnhealthy()
                            }
                        } catch (_: Throwable) {
                            if (introUrl.startsWith(base)) markCompanionProxyUnhealthy()
                        }
                    }
                }
            }
        }

        private fun findActivePlayerFragment(fm: FragmentManager?): AbstractPlayerFragment<*>? {
            if (fm == null) return null
            val fragments: List<Fragment> = try {
                fm.fragments
            } catch (_: Throwable) {
                return null
            }
            for (frag in fragments.asReversed()) {
                if (frag is AbstractPlayerFragment<*> && frag.isAdded && frag.view != null) {
                    return frag
                }
                if (frag.isAdded) {
                    val nested = findActivePlayerFragment(frag.childFragmentManager)
                    if (nested != null) return nested
                }
            }
            return null
        }

        private fun notifyPlayerUpdatedTimeForSkips(playerFrag: AbstractPlayerFragment<*>?) {
            try {
                NorthPlayerGuard.guardPlayerFragment(playerFrag)
                val p = NorthPlayerGuard.unwrapIPlayer(playerFrag?.player) ?: return
                val dur = p.getDuration() ?: 0L
                if (dur <= 0L) return
                val m = p.javaClass.getMethod(
                    "updatedTime",
                    java.lang.Long::class.java,
                    PlayerEventSource::class.java
                )
                m.invoke(p, null, PlayerEventSource.Player)
            } catch (_: Throwable) {
            }
        }

        /**
         * Ensures `CS3IPlayer.updatedTime(null, PlayerEventSource.Player)` is triggered once
         * `UpdatedMatroskaExtractor` resolves `duration > 0` (and after the NORTH pre-roll overlay
         * is dismissed), guaranteeing `GeneratorPlayer.loadStamps(duration)` and `TimestampInvokedEvent`
         * ("Skip Intro" button) always fire even if `onRenderFirst()` ran before duration was known
         * or while the pre-roll overlay was visible.
         */
        fun schedulePostStartSkipSync(isCastingNow: Boolean) {
            if (isCastingNow || !isSkipIntrosEnabled) return
            val mainHandler = Handler(Looper.getMainLooper())
            val delaysMs = longArrayOf(400L, 1_200L, 2_400L, 3_800L, 5_500L, 8_000L)
            for (delayMs in delaysMs) {
                mainHandler.postDelayed({
                    if (isIntroOverlayActive) return@postDelayed
                    val act = CommonActivity.activity as? AppCompatActivity ?: return@postDelayed
                    if (act.isFinishing || act.isDestroyed) return@postDelayed
                    val playerFrag = findActivePlayerFragment(act.supportFragmentManager) ?: return@postDelayed
                    notifyPlayerUpdatedTimeForSkips(playerFrag)
                }, delayMs)
            }
        }

        /**
         * Displays the branded full-screen **NORTH** intro (video + crisp native title card)
         * every time a new movie or episode starts playing inside CloudStream's player, pausing the
         * underlying episode cleanly for ~2.6s and resuming playback seamlessly without calling `seekTo(0L)`
         * (which would abort `UpdatedMatroskaExtractor`'s initial EOF Cues seek).
         */
        fun triggerNorthIntroPreRoll(isCastingNow: Boolean, mediaKey: String = "") {
            if (isCastingNow || !playNorthIntroEnabled || isIntroOverlayActive) return
            ensureCastProgressMonitorRunning()
            precacheNorthIntroAsync()

            val mainHandler = Handler(Looper.getMainLooper())
            val startAtMs = System.currentTimeMillis()
            val maxWaitMs = 9_000L

            val pollTask = object : Runnable {
                override fun run() {
                    if (isIntroOverlayActive || !playNorthIntroEnabled) return
                    val act = CommonActivity.activity as? AppCompatActivity
                    if (act == null || act.isFinishing || act.isDestroyed) {
                        if (System.currentTimeMillis() - startAtMs < maxWaitMs) {
                            mainHandler.postDelayed(this, 100L)
                        }
                        return
                    }

                    val playerFrag = findActivePlayerFragment(act.supportFragmentManager)
                    if (playerFrag == null) {
                        if (System.currentTimeMillis() - startAtMs < maxWaitMs) {
                            mainHandler.postDelayed(this, 100L)
                        }
                        return
                    }
                    NorthPlayerGuard.guardPlayerFragment(playerFrag)

                    val fragId = System.identityHashCode(playerFrag)
                    val effectiveMediaKey = mediaKey.ifBlank { "frag:$fragId" }
                    if (fragId == lastIntroPlayerIdentity && effectiveMediaKey == lastIntroMediaKey) return

                    // Check if playback is already actively past 3.5s (e.g., Continue Watching resume mid-episode);
                    // if so, mark this media key and skip the pre-roll so we NEVER pause or seek backward!
                    val existingPos = try {
                        playerFrag.player?.getPosition() ?: 0L
                    } catch (_: Throwable) {
                        0L
                    }
                    if (existingPos > 3_500L) {
                        lastIntroPlayerIdentity = fragId
                        lastIntroMediaKey = effectiveMediaKey
                        schedulePostStartSkipSync(isCastingNow = false)
                        return
                    }

                    lastIntroPlayerIdentity = fragId
                    lastIntroMediaKey = effectiveMediaKey
                    isIntroOverlayActive = true

                    val decor = act.window?.decorView as? ViewGroup
                    if (decor == null) {
                        isIntroOverlayActive = false
                        return
                    }

                    val density = act.resources.displayMetrics.density
                    fun dpPx(v: Int): Int = (v * density).toInt()

                    val overlay = FrameLayout(act).apply {
                        background = GradientDrawable(
                            GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(
                                Color.parseColor("#020814"),
                                Color.parseColor("#06202E"),
                                Color.parseColor("#030916")
                            )
                        )
                        isClickable = true
                        isFocusable = true
                        elevation = 999f
                        alpha = 0f
                    }

                    // Nordic Aurora & Scandinavian Studio Title Card (instant render & fallback behind MP4)
                    val titleStack = LinearLayout(act).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(dpPx(28), dpPx(22), dpPx(28), dpPx(22))
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#44041326"))
                            cornerRadius = dpPx(18).toFloat()
                            setStroke(dpPx(1), Color.parseColor("#4D34D399"))
                        }
                    }
                    val crownBadge = TextView(act).apply {
                        text = "✦   N O R D I C B Y T E S   ✦"
                        textSize = 11f
                        setTextColor(Color.parseColor("#6EE7B7"))
                        typeface = Typeface.DEFAULT_BOLD
                        letterSpacing = 0.28f
                        gravity = Gravity.CENTER
                        setPadding(0, 0, 0, dpPx(6))
                    }
                    val titleText = TextView(act).apply {
                        text = "N O R T H"
                        textSize = 44f
                        setTextColor(Color.WHITE)
                        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                        letterSpacing = 0.34f
                        gravity = Gravity.CENTER
                        setShadowLayer(22f, 0f, 0f, Color.parseColor("#34D399"))
                    }
                    val accentBar = View(act).apply {
                        background = GradientDrawable(
                            GradientDrawable.Orientation.LEFT_RIGHT,
                            intArrayOf(
                                Color.parseColor("#0EA5E9"),
                                Color.parseColor("#34D399"),
                                Color.parseColor("#38BDF8")
                            )
                        ).apply {
                            cornerRadius = dpPx(4).toFloat()
                        }
                    }
                    val subText = TextView(act).apply {
                        text = "🇩🇰  🇸🇪  🇳🇴  🇫🇮  🇮🇸   •   N O R D I C   C I N E M A"
                        textSize = 12f
                        setTextColor(Color.parseColor("#BAE6FD"))
                        typeface = Typeface.DEFAULT_BOLD
                        letterSpacing = 0.22f
                        gravity = Gravity.CENTER
                        setPadding(0, dpPx(12), 0, 0)
                    }
                    val mottoText = TextView(act).apply {
                        text = "AURORA BOREALIS  •  TRUE NORDIC STREAMING"
                        textSize = 9.5f
                        setTextColor(Color.parseColor("#6EE7B7"))
                        typeface = Typeface.DEFAULT_BOLD
                        letterSpacing = 0.24f
                        gravity = Gravity.CENTER
                        setPadding(0, dpPx(6), 0, 0)
                    }
                    titleStack.addView(crownBadge)
                    titleStack.addView(titleText)
                    titleStack.addView(
                        accentBar,
                        LinearLayout.LayoutParams(dpPx(140), dpPx(3)).apply {
                            gravity = Gravity.CENTER_HORIZONTAL
                            topMargin = dpPx(10)
                        }
                    )
                    titleStack.addView(subText)
                    titleStack.addView(mottoText)
                    overlay.addView(
                        titleStack,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER
                        )
                    )

                    // Optional pre-cached/proxied studio intro MP4
                    val localIntroFile = northIntroCacheFilePath.takeIf { it.isNotBlank() }?.let { File(it) }
                    val localPort = proxyServerPort
                    val introUri: Uri? = when {
                        localIntroFile != null && localIntroFile.exists() && localIntroFile.length() > 10_000L ->
                            Uri.fromFile(localIntroFile)
                        localPort > 0 ->
                            Uri.parse("http://127.0.0.1:$localPort/api/intro.mp4")
                        else -> null
                    }

                    var dismissed = false
                    var holdPauseTask: Runnable? = null

                    fun finishIntro() {
                        if (dismissed) return
                        dismissed = true
                        holdPauseTask?.let { mainHandler.removeCallbacks(it) }

                        try {
                            val p = playerFrag.player
                            if (p != null) {
                                // CRITICAL: Do NOT call p.seekTo(0L) here! Calling seekTo(0L) at t=2.65s aborts
                                // UpdatedMatroskaExtractor's initial EOF Cues request before MatroskaSeekMap is built.
                                p.handleEvent(CSPlayerEvent.Play, PlayerEventSource.UI)
                            }
                        } catch (_: Throwable) {
                        }

                        overlay.animate()
                            .alpha(0f)
                            .setDuration(260L)
                            .withEndAction {
                                try {
                                    decor.removeView(overlay)
                                } catch (_: Throwable) {
                                }
                                isIntroOverlayActive = false
                                schedulePostStartSkipSync(isCastingNow = false)
                            }
                            .start()
                    }

                    var videoView: VideoView? = null
                    if (introUri != null) {
                        try {
                            videoView = VideoView(act).apply {
                                if (Build.VERSION.SDK_INT >= 26) {
                                    setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
                                }
                                setVideoURI(introUri)
                                setOnPreparedListener { mp ->
                                    try {
                                        mp.setVolume(0.7f, 0.7f)
                                        start()
                                    } catch (_: Throwable) {
                                    }
                                }
                                setOnCompletionListener {
                                    finishIntro()
                                }
                                setOnErrorListener { _, _, _ ->
                                    visibility = View.GONE
                                    true
                                }
                            }
                            overlay.addView(
                                videoView,
                                FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    Gravity.CENTER
                                )
                            )
                        } catch (_: Throwable) {
                            videoView = null
                        }
                    }

                    val skipBtn = TextView(act).apply {
                        text = "Skip ›"
                        textSize = 12.5f
                        setTextColor(Color.parseColor("#E0F2FE"))
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(dpPx(14), dpPx(7), dpPx(14), dpPx(7))
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#990B1B36"))
                            cornerRadius = dpPx(8).toFloat()
                            setStroke(dpPx(1), Color.parseColor("#38BDF8"))
                        }
                        setOnClickListener { finishIntro() }
                    }
                    overlay.addView(
                        skipBtn,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.TOP or Gravity.END
                        ).apply {
                            topMargin = dpPx(24)
                            rightMargin = dpPx(24)
                        }
                    )

                    decor.addView(
                        overlay,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    )
                    ObjectAnimator.ofFloat(overlay, "alpha", 0f, 1f).setDuration(220L).start()

                    // Keep the main content paused behind the overlay until the 2.65s intro completes.
                    // If CloudStream applies a saved resume position (>3.5s) right after startup, dismiss immediately!
                    holdPauseTask = object : Runnable {
                        override fun run() {
                            if (dismissed) return
                            try {
                                val p = playerFrag.player
                                if (p != null) {
                                    val posNow = p.getPosition() ?: 0L
                                    if (posNow > 3_500L) {
                                        finishIntro()
                                        return
                                    }
                                    if (p.getIsPlaying()) {
                                        p.handleEvent(CSPlayerEvent.Pause, PlayerEventSource.UI)
                                    }
                                }
                            } catch (_: Throwable) {
                            }
                            mainHandler.postDelayed(this, 120L)
                        }
                    }
                    mainHandler.post(holdPauseTask!!)

                    // Safety deadline so content always starts cleanly (~2.65s) even if VideoView doesn't fire completion
                    mainHandler.postDelayed({ finishIntro() }, 2_650L)
                }
            }
            mainHandler.postDelayed(pollTask, 80L)
        }

        /**
         * Acquires low-latency Wi-Fi + Partial CPU WakeLock during active playback/casting without
         * opening any concurrent unsynchronized AudioTrack (which could contend with ExoPlayer audio).
         */
        @Suppress("DEPRECATION")
        private fun acquireStreamingLocks() {
            try {
                val ctx = (CommonActivity.activity?.applicationContext) ?: (getContext() as? Context)?.applicationContext ?: return
                if (activeWifiLock == null) {
                    val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    if (wm != null) {
                        val lockMode = if (Build.VERSION.SDK_INT >= 29) {
                            4 // WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        } else {
                            WifiManager.WIFI_MODE_FULL_HIGH_PERF
                        }
                        activeWifiLock = wm.createWifiLock(lockMode, "North:CastLowLatencyWifiLock").apply {
                            setReferenceCounted(false)
                        }
                    }
                }
                if (activeWifiLock?.isHeld == false) {
                    activeWifiLock?.acquire()
                }
                if (activeWakeLock == null) {
                    val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    if (pm != null) {
                        activeWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "North:CastProxyWakeLock").apply {
                            setReferenceCounted(false)
                        }
                    }
                }
                if (activeWakeLock?.isHeld == false) {
                    activeWakeLock?.acquire(4 * 3600 * 1000L)
                }
            } catch (_: Throwable) {
            }
        }

        private fun releaseStreamingLocks() {
            try {
                if (activeWifiLock?.isHeld == true) {
                    activeWifiLock?.release()
                }
            } catch (_: Throwable) {
            }
            try {
                if (activeWakeLock?.isHeld == true) {
                    activeWakeLock?.release()
                }
            } catch (_: Throwable) {
            }
        }

        private fun isPaddingOrNonMediaPath(fullPath: String): Boolean {
            val lower = fullPath.lowercase(Locale.ROOT)
            val baseName = lower.substringAfterLast("/")
            return baseName.startsWith("_____padding_file") ||
                baseName.contains("____padding_file") ||
                lower.contains("/.pad/") ||
                baseName == "padding_file"
        }

        fun resolveMainMovieFileIndexStatic(files: List<Pair<String, Long>>): Int {
            if (files.size <= 1) return 1
            val videoRegex = Regex("""(?i)\.(mkv|mp4|avi|ts|m2ts|m4v|mov|webm)$""")
            var bestIdx = 1
            var bestSize = -1L
            for ((i, entry) in files.withIndex()) {
                val (name, size) = entry
                if (!isPaddingOrNonMediaPath(name) &&
                    videoRegex.containsMatchIn(name) &&
                    !name.contains("sample", ignoreCase = true)
                ) {
                    if (size > bestSize) {
                        bestSize = size
                        bestIdx = i + 1 // 1-based index for TorrServer
                    }
                }
            }
            return bestIdx
        }

        fun matchEpisodeFileIndexStatic(
            fileNames: List<String>,
            seasonNum: Int,
            epNum: Int
        ): Int? {
            if (fileNames.isEmpty()) return null
            val videoRegex = Regex("""(?i)\.(mkv|mp4|avi|ts|m2ts|m4v|mov|webm)$""")
            val sPad = seasonNum.toString().padStart(2, '0')
            val ePad = epNum.toString().padStart(2, '0')

            // Use non-alphanumeric boundary checks instead of \b so underscore-separated names
            // like `The_Dragon_Prince_S01E01_1080p.mkv` always match accurately.
            val bStart = "(?:^|[^A-Za-z0-9])"
            val bEnd = "(?:[^A-Za-z0-9]|$)"
            val exactPatterns = listOf(
                Regex("""(?i)${bStart}S0?${seasonNum}[.\s_-]*E0?${epNum}${bEnd}"""),
                Regex("""(?i)${bStart}S${sPad}E${ePad}${bEnd}"""),
                Regex("""(?i)${bStart}S0?${seasonNum}[.\s_-]*E\d{1,3}[.\s_-]*-?[.\s_-]*E?0?${epNum}${bEnd}"""),
                Regex("""(?i)${bStart}${seasonNum}x0?${epNum}${bEnd}"""),
                Regex("""(?i)${bStart}(?:Ep|Episode|Afsnit)[.\s_-]*0?${epNum}${bEnd}"""),
                Regex("""(?i)(?:^|[.\s_\-\[\(])E0?${epNum}(?:[.\s_\-\]\)]|$)""")
            )

            for (pattern in exactPatterns) {
                for ((idx, fullPath) in fileNames.withIndex()) {
                    if (isPaddingOrNonMediaPath(fullPath)) continue
                    val baseName = fullPath.substringAfterLast("/")
                    if (videoRegex.containsMatchIn(baseName) &&
                        !baseName.contains("sample", ignoreCase = true) &&
                        pattern.containsMatchIn(baseName)
                    ) {
                        return idx + 1 // Exact 1-based index in torrent file list
                    }
                }
            }

            // Fallback: nth genuine video file inside season-matching folder (if multi-season) or overall
            val seasonDirRegex = Regex("""(?i)${bStart}(?:S0?${seasonNum}|Season[.\s_-]*0?${seasonNum})${bEnd}""")
            val seasonVideoIndices = fileNames.mapIndexedNotNull { index, s ->
                if (isPaddingOrNonMediaPath(s)) return@mapIndexedNotNull null
                val baseName = s.substringAfterLast("/")
                if (videoRegex.containsMatchIn(baseName) &&
                    !baseName.contains("sample", ignoreCase = true) &&
                    seasonDirRegex.containsMatchIn(s)
                ) {
                    index + 1
                } else {
                    null
                }
            }
            if (epNum in 1..seasonVideoIndices.size) {
                return seasonVideoIndices[epNum - 1]
            }

            val allVideoIndices = fileNames.mapIndexedNotNull { index, s ->
                if (isPaddingOrNonMediaPath(s)) return@mapIndexedNotNull null
                val baseName = s.substringAfterLast("/")
                if (videoRegex.containsMatchIn(baseName) && !baseName.contains("sample", ignoreCase = true)) index + 1 else null
            }
            if (epNum in 1..allVideoIndices.size) {
                return allVideoIndices[epNum - 1]
            }

            return allVideoIndices.firstOrNull()
        }

        fun clearCaches() {
            apiResponseCache.clear()
            parsedTorrentCache.clear()
            rawTorrentBytesCache.clear()
            ghostSessions.clear()
            categoryPaginationMap.clear()
            searchPaginationMap.clear()
            castStreamUrlCache.clear()
            noExternalPeersHashes.clear()
            deadSwarmHashes.clear()
            unplayableArchiveHashes.clear()
            lastTrackerDiagByHash.clear()
            zeroPeerTimeoutCount.clear()
            lastDeadToastMsByHash.clear()
            torrentAddedTimestampMs.clear()
            transcodedStreamUrls.clear()
            transcodedStreamContextMap.clear()
            mkvCuesTailCache.clear()
            mkvCuesTailWarmInProgress.clear()
            torrUrlToCastQueryMap.clear()
            subtitleResponseCache.clear()
            lastSuccessfulApiTimestamp = 0L
            proxyApiUnhealthyUntilMs = 0L
            lastSuccessfulStreamResponseMs = 0L
            clearNorthRepoLinkCache()
            purgeStaleExoPlayerSimpleCache()
        }

        /**
         * Evicts North entries from CloudStream's static 20-minute `RepoLinkGenerator.cache`
         * so reopening a saved movie or episode never reuses an old `ExtractorLink` URL blindly
         * when recovery or cache clearing occurs.
         */
        fun clearNorthRepoLinkCache() {
            try {
                val repoGenClass = Class.forName("com.lagradost.cloudstream3.ui.player.RepoLinkGenerator")
                val cacheField = repoGenClass.getDeclaredField("cache")
                cacheField.isAccessible = true
                val rawMap = cacheField.get(null) as? MutableMap<*, *> ?: return
                synchronized(rawMap) {
                    val toRemove = ArrayList<Any?>()
                    for ((k, v) in rawMap.entries) {
                        var matchesNorth = false
                        val keyStr = k?.toString().orEmpty()
                        if (keyStr.contains("North", ignoreCase = true) ||
                            keyStr.contains("nordicbytes", ignoreCase = true) ||
                            keyStr.contains("nb_", ignoreCase = true)
                        ) {
                            matchesNorth = true
                        }
                        if (!matchesNorth && v != null) {
                            try {
                                val getLinks = v.javaClass.getMethod("getLinkCache")
                                val linkSet = getLinks.invoke(v) as? Iterable<*>
                                if (linkSet != null) {
                                    for (item in linkSet) {
                                        val link = item as? ExtractorLink ?: continue
                                        if (link.source.equals("North", ignoreCase = true) ||
                                            link.url.contains("/cast/stream", ignoreCase = true)
                                        ) {
                                            matchesNorth = true
                                            break
                                        }
                                    }
                                }
                            } catch (_: Throwable) {
                            }
                        }
                        if (matchesNorth) {
                            toRemove.add(k)
                        }
                    }
                    for (k in toRemove) {
                        rawMap.remove(k)
                    }
                }
            } catch (_: Throwable) {
            }
        }

        /**
         * Purges any cached `/cast/stream` spans from CloudStream's persistent `CS3IPlayer.simpleCache`,
         * disables `Mp4PreviewGenerator` (`PreviewGenerator`) if active, and evicts CloudStream's 15-minute
         * `app.baseClient.cache` (`http_cache`) so `CacheDataSource` and `RepositoryManager` never serve
         * stale headers, partial ranges, or cached `plugins.json` from disk.
         */
        fun purgeStaleExoPlayerSimpleCache() {
            lastSimpleCachePurgeMs = System.currentTimeMillis()
            try {
                com.lagradost.cloudstream3.app.baseClient.cache?.evictAll()
            } catch (_: Throwable) {
            }
            try {
                val playerClass = Class.forName("com.lagradost.cloudstream3.ui.player.CS3IPlayer")
                val cacheField = playerClass.getDeclaredField("simpleCache")
                cacheField.isAccessible = true
                val cacheInstance = cacheField.get(null) ?: return
                val getKeysMethod = cacheInstance.javaClass.getMethod("getKeys")
                val removeResourceMethod = cacheInstance.javaClass.getMethod("removeResource", String::class.java)
                val rawKeys = getKeysMethod.invoke(cacheInstance) as? Set<*> ?: return
                val snapshot = rawKeys.mapNotNull { it as? String }.toList()
                for (key in snapshot) {
                    if (key.contains("/cast/stream", ignoreCase = true) ||
                        key.contains("127.0.0.1", ignoreCase = true)
                    ) {
                        try {
                            removeResourceMethod.invoke(cacheInstance, key)
                        } catch (_: Throwable) {
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }

        /**
         * Detects if CloudStream currently has an active Chromecast session (`castSession`).
         */
        fun hasActiveCastSession(): Boolean {
            return try {
                val act = CommonActivity.activity ?: return false
                val method = CommonActivity::class.java.getMethod("getCastSession", android.app.Activity::class.java)
                method.invoke(CommonActivity, act) != null
            } catch (_: Throwable) {
                false
            }
        }

        /**
         * Continuously syncs Chromecast playback position & duration into CloudStream's
         * `DataStoreHelper.setViewPosAndResume` every 3 seconds whenever a Cast session is active!
         *
         * Fixes a built-in CloudStream bug in `SelectSourceController.onMediaStatusUpdated` where:
         * 1. Movies (`episodes.size == 1`) and the final episode of a season (`episodes.size <= currentEpisodeIndex + 1`)
         *    NEVER have their watch position saved at all!
         * 2. Multi-episode series erroneously save progress under `episodes[currentEpisodeIndex + 1]`
         *    instead of `episodes[currentEpisodeIndex]`.
         */
        fun ensureCastProgressMonitorRunning() {
            if (isCastProgressThreadRunning) return
            synchronized(proxyLock) {
                if (isCastProgressThreadRunning) return
                isCastProgressThreadRunning = true
            }
            val mainHandler = Handler(Looper.getMainLooper())
            thread(isDaemon = true, name = "North-CastProgressSaver") {
                try {
                    while (true) {
                        try {
                            mainHandler.post {
                                try {
                                    val act = CommonActivity.activity ?: return@post
                                    val getCastMethod = CommonActivity::class.java.getMethod(
                                        "getCastSession",
                                        android.app.Activity::class.java
                                    )
                                    val castSession = getCastMethod.invoke(CommonActivity, act) ?: return@post
                                    val getRmcMethod = castSession.javaClass.getMethod("getRemoteMediaClient")
                                    val rmc = getRmcMethod.invoke(castSession) ?: return@post

                                    val posMs = (rmc.javaClass.getMethod("getApproximateStreamPosition").invoke(rmc) as? Long) ?: 0L
                                    var durMs = (rmc.javaClass.getMethod("getStreamDuration").invoke(rmc) as? Long) ?: 0L

                                    val mediaInfo = rmc.javaClass.getMethod("getMediaInfo").invoke(rmc) ?: return@post
                                    if (durMs <= 0L) {
                                        durMs = (mediaInfo.javaClass.getMethod("getStreamDuration").invoke(mediaInfo) as? Long) ?: 0L
                                    }
                                    val customData = mediaInfo.javaClass.getMethod("getCustomData").invoke(mediaInfo) as? JSONObject
                                        ?: return@post
                                    val rawJson = customData.toString()
                                    if (rawJson.isBlank()) return@post

                                    val meta = AppUtils.tryParseJson<com.lagradost.cloudstream3.ui.MetadataHolder>(rawJson)
                                        ?: return@post
                                    val eps = meta.episodes
                                    if (eps.isEmpty()) return@post

                                    val curIdx = meta.currentEpisodeIndex.coerceIn(0, eps.size - 1)
                                    val currentEp = eps.getOrNull(curIdx) ?: return@post
                                    val nextEp = eps.getOrNull(curIdx + 1)

                                    if (durMs <= 0L) {
                                        val fallbackCtxDur = transcodedStreamContextMap.values.firstOrNull { it.durationMs > 30_000.0 }
                                            ?.durationMs?.toLong() ?: 0L
                                        if (fallbackCtxDur > 30_000L) {
                                            durMs = fallbackCtxDur
                                        }
                                    }

                                    if (posMs > 3_000L && durMs > 30_000L) {
                                        com.lagradost.cloudstream3.utils.DataStoreHelper.setViewPosAndResume(
                                            currentEp.id,
                                            posMs,
                                            durMs,
                                            currentEp,
                                            nextEp
                                        )
                                    }
                                } catch (_: Throwable) {
                                }
                            }
                        } catch (_: Throwable) {
                        }
                        try {
                            Thread.sleep(3_000L)
                        } catch (_: Throwable) {
                            break
                        }
                    }
                } finally {
                    isCastProgressThreadRunning = false
                }
            }
        }

        /**
         * Resolves the Android device's local Wi-Fi / LAN IPv4 address (e.g. `192.168.1.x`) so
         * a Chromecast device on the same LAN (`castSession`) can stream directly from our
         * Ratio-Safe Proxy (`http://<LAN_IP>:<port>/cast?...`) while all tracker announces
         * remain routed through our Ghost Ratio Proxy (`/announce`).
         */
        fun getLocalLanIpAddress(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
                val sortedIfaces = interfaces.sortedBy { iface ->
                    val n = iface.name.lowercase(Locale.ROOT)
                    when {
                        n.startsWith("wlan") -> 0
                        n.startsWith("eth") -> 1
                        n.startsWith("ap") || n.startsWith("swlan") -> 2
                        n.startsWith("tun") || n.startsWith("ppp") || n.startsWith("rmnet") || n.startsWith("ccmni") -> 99
                        else -> 10
                    }
                }
                for (iface in sortedIfaces) {
                    val n = iface.name.lowercase(Locale.ROOT)
                    if (iface.isLoopback || !iface.isUp || n.startsWith("tun") || n.startsWith("rmnet") || n.startsWith("ccmni")) continue
                    val addrs = iface.inetAddresses?.toList() ?: continue
                    for (addr in addrs) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                            val host = addr.hostAddress
                            if (!host.isNullOrBlank() && host != "0.0.0.0") {
                                return host
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }

            try {
                DatagramSocket().use { socket ->
                    socket.connect(InetAddress.getByName("8.8.8.8"), 10002)
                    val localAddr = socket.localAddress
                    val ip = localAddr?.hostAddress
                    if (localAddr is Inet4Address && localAddr.isSiteLocalAddress && !ip.isNullOrBlank() && ip != "0.0.0.0") {
                        return ip
                    }
                }
            } catch (_: Throwable) {
            }

            return "127.0.0.1"
        }

        /**
         * Registers `NorthSkipAPI` into CloudStream's `SkipAPI.skipApis` list (mutating both the
         * in-place `Arrays$ArrayList` backing array `a` for Android ART JIT compatibility AND the
         * static `skipApis` field), replacing the unvalidated built-in `TheIntroDBSkip` and `IntroDbSkip`
         * instances so CloudStream always uses `NorthSkipAPI`.
         */
        fun registerNorthSkipApi() {
            try {
                val northApi = NorthSkipAPI()
                val field = SkipAPI::class.java.getDeclaredField("skipApis")
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val current = (field.get(null) as? List<SkipAPI>) ?: emptyList()

                // 1. Mutate the existing List / Arrays$ArrayList backing array in-place so Android ART JIT
                //    inlined references to `static final skipApis` also see NorthSkipAPI at index 0!
                try {
                    if (current is MutableList<*>) {
                        @Suppress("UNCHECKED_CAST")
                        val mut = current as MutableList<SkipAPI>
                        if (mut.isNotEmpty()) {
                            mut[0] = northApi
                            for (i in 1 until mut.size) {
                                val item = mut[i]
                                if (item is TheIntroDBSkip || item is IntroDbSkip) {
                                    mut[i] = northApi
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
                try {
                    val arrField = current.javaClass.getDeclaredField("a")
                    arrField.isAccessible = true
                    val backingArr = arrField.get(current) as? Array<*>
                    if (backingArr != null && backingArr.isNotEmpty()) {
                        @Suppress("UNCHECKED_CAST")
                        val typedArr = backingArr as Array<Any?>
                        typedArr[0] = northApi
                        for (i in 1 until typedArr.size) {
                            val item = typedArr[i]
                            if (item is TheIntroDBSkip || item is IntroDbSkip) {
                                typedArr[i] = northApi
                            }
                        }
                    }
                } catch (_: Throwable) {
                }

                // 2. Also update the static field reference with a clean deduplicated list
                if (current.firstOrNull() !is NorthSkipAPI || current.any { it is TheIntroDBSkip || it is IntroDbSkip }) {
                    val filtered = current.filterNot {
                        it is NorthSkipAPI || it is TheIntroDBSkip || it is IntroDbSkip
                    }
                    val updated = ArrayList<SkipAPI>(filtered.size + 1)
                    updated.add(northApi)
                    updated.addAll(filtered)
                    field.set(null, updated)
                }

                val cacheField = SkipAPI::class.java.getDeclaredField("cachedStamps")
                cacheField.isAccessible = true
                (cacheField.get(null) as? MutableMap<*, *>)?.clear()
            } catch (_: Throwable) {
            }
        }

        /**
         * Ensures the local + LAN Stream, Cast & Ghost Ratio Proxy (`0.0.0.0`) is running:
         * - `/torrent`: Serves authenticated `.torrent` files locally with rewritten tracker announce URLs
         *   and explicit `index=<1-based fileId>` query parameter.
         * - `/announce`: Forces `downloaded=0`, requests `numwant=200` seedboxes for maximum streaming speed,
         *   and reports boosted `uploaded` credit so the user's tracker Ratio is 100% met (`RATIO >= 1.00`)
         *   and the torrent transitions out of `Leeching torrents` (`left=0`).
         * - `/cast`: Streams the resolved TorrServer video file over HTTP/1.1 (`video/mp4` with `Range` / `206`
         *   and CORS headers) so Chromecast (`castSession`) devices on the LAN can play any Movie or Episode
         *   while the Ghost Ratio Proxy keeps 100% ratio protection active.
         * - `/sub`: Proxies subtitle files with `Access-Control-Allow-Origin: *` so Chromecast displays subtitles.
         */
        private val PREFERRED_PROXY_PORTS = intArrayOf(
            48791, 48792, 48793, 48794, 48795, 48796, 48797, 48798, 48799, 48800
        )

        fun ensureLocalProxyServer(): Int {
            ensurePublicIpv4DetectedAsync()
            if (proxyServerPort > 0) return proxyServerPort
            synchronized(proxyLock) {
                if (proxyServerPort > 0) return proxyServerPort
                return try {
                    var server: ServerSocket? = null
                    for (candidatePort in PREFERRED_PROXY_PORTS) {
                        try {
                            val ss = ServerSocket()
                            ss.reuseAddress = true
                            ss.bind(java.net.InetSocketAddress(InetAddress.getByName("0.0.0.0"), candidatePort), 128)
                            server = ss
                            break
                        } catch (_: Throwable) {
                        }
                    }
                    if (server == null) {
                        server = try {
                            ServerSocket(0, 128, InetAddress.getByName("0.0.0.0"))
                        } catch (_: Throwable) {
                            ServerSocket(0, 128)
                        }
                    }
                    val boundServer = server!!
                    proxyServerPort = boundServer.localPort
                    thread(isDaemon = true, name = "North-StreamAndRatioProxy") {
                        while (!boundServer.isClosed) {
                            try {
                                val client = boundServer.accept()
                                thread(isDaemon = true) {
                                    handleProxyClient(client, proxyServerPort)
                                }
                            } catch (_: Exception) {
                            }
                        }
                    }
                    proxyServerPort
                } catch (_: Exception) {
                    0
                }
            }
        }

        private fun readAllBytes(input: InputStream): ByteArray {
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var n: Int
            while (input.read(chunk).also { n = it } != -1) {
                buffer.write(chunk, 0, n)
            }
            return buffer.toByteArray()
        }

        private fun appendTokenToDownloadUrlStatic(downloadUrl: String, token: String): String {
            if (downloadUrl.isBlank() || downloadUrl.startsWith("magnet:")) return downloadUrl
            if (token.isBlank() || downloadUrl.contains("api_token=")) return downloadUrl
            val sep = if (downloadUrl.contains("?")) "&" else "?"
            return "$downloadUrl${sep}api_token=${URLEncoder.encode(token, "UTF-8")}"
        }

        private fun downloadTorrentBytesDirect(targetUrl: String, token: String): ByteArray? {
            val authTargetUrl = appendTokenToDownloadUrlStatic(targetUrl, token)
            rawTorrentBytesCache[authTargetUrl]?.let { return it }
            rawTorrentBytesCache[targetUrl]?.let { return it }

            val candidateUrls = mutableListOf(authTargetUrl)
            val proxyBase = proxyApiBaseUrl.trim().trimEnd('/')
            if (isCompanionProxyAvailable()) {
                val encUrl = URLEncoder.encode(targetUrl, "UTF-8")
                val encTok = URLEncoder.encode(token, "UTF-8")
                candidateUrls.add("$proxyBase/api/torrent?url=$encUrl&token=$encTok")
            }

            for (attemptUrl in candidateUrls) {
                for (attempt in 1..2) {
                    try {
                        var currentUrl = attemptUrl
                        var redirects = 0
                        while (redirects < 5) {
                            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                                instanceFollowRedirects = false
                                requestMethod = "GET"
                                if (token.isNotBlank()) {
                                    setRequestProperty("Authorization", "Bearer $token")
                                }
                                setRequestProperty("Accept", "application/x-bittorrent, application/octet-stream, */*")
                                setRequestProperty("User-Agent", USER_AGENT)
                                connectTimeout = 12000
                                readTimeout = 12000
                            }
                            val code = conn.responseCode
                            if (code in 301..308) {
                                val loc = conn.getHeaderField("Location") ?: break
                                conn.disconnect()
                                currentUrl = URL(URL(currentUrl), loc).toString()
                                redirects++
                                continue
                            }
                            if (code in 200..299) {
                                val bytes = conn.inputStream.use { readAllBytes(it) }
                                conn.disconnect()
                                if (bytes.isNotEmpty() && bytes[0] == 'd'.code.toByte()) {
                                    putBoundedRawTorrent(authTargetUrl, bytes)
                                    putBoundedRawTorrent(targetUrl, bytes)
                                    return bytes
                                }
                                break
                            } else {
                                conn.disconnect()
                                break
                            }
                        }
                    } catch (_: Exception) {
                    }
                    if (attempt < 2) {
                        try {
                            Thread.sleep(600L)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
            }
            return null
        }

        private fun executeTrackerGet(urlStr: String, timeoutMs: Int = 9000): ByteArray? {
            for (attempt in 1..2) {
                try {
                    var currentUrl = urlStr
                    var redirects = 0
                    while (redirects < 5) {
                        val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                            instanceFollowRedirects = false
                            requestMethod = "GET"
                            setRequestProperty("User-Agent", TRACKER_CLIENT_UA)
                            setRequestProperty("Accept-Encoding", "identity")
                            connectTimeout = timeoutMs
                            readTimeout = timeoutMs
                        }
                        val code = conn.responseCode
                        if (code in 301..308) {
                            val loc = conn.getHeaderField("Location") ?: break
                            conn.disconnect()
                            currentUrl = URL(URL(currentUrl), loc).toString()
                            redirects++
                            continue
                        }
                        if (code in 200..299) {
                            val bytes = conn.inputStream.use { readAllBytes(it) }
                            conn.disconnect()
                            return bytes
                        }
                        conn.disconnect()
                        if (code != 429 && code !in 500..599) {
                            return null
                        }
                        break
                    }
                } catch (_: Exception) {
                }
                if (attempt < 2) {
                    try {
                        Thread.sleep(700L)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
            return null
        }

        /**
         * Rewrites a `.torrent` file's outer dictionary so its `announce` URL points to our local
         * `127.0.0.1` Ghost Ratio Proxy while keeping the raw `4:info` dictionary bytes 100% bit-for-bit
         * identical (preserving the exact SHA-1 `info_hash` required by private trackers).
         */
        fun rewriteTorrentForGhostProxy(
            originalBytes: ByteArray,
            parsedMeta: ParsedTorrentMeta?,
            localPort: Int,
            ghostRatioEnabled: Boolean
        ): ByteArray {
            if (!ghostRatioEnabled || localPort <= 0 || parsedMeta == null) return originalBytes
            val httpTrackers = parsedMeta.trackers.filter { it.startsWith("http") }.distinct()
            val primaryTracker = httpTrackers.firstOrNull() ?: return originalBytes
            val rawInfo = parsedMeta.rawInfoBytes ?: return originalBytes
            if (rawInfo.isEmpty()) return originalBytes

            return try {
                val totalSize = parsedMeta.files.sumOf { it.second }.coerceAtLeast(536_870_912L)
                val proxyAnnounceUrl =
                    "http://127.0.0.1:$localPort/announce?upstream=${URLEncoder.encode(primaryTracker, "UTF-8")}&size=$totalSize"
                val announceBytes = proxyAnnounceUrl.toByteArray(Charsets.UTF_8)

                // Construct valid bencoded dictionary with sorted keys (`announce`, optional `announce-list`, `info`)
                val out = ByteArrayOutputStream(announceBytes.size + rawInfo.size + 256)
                out.write("d8:announce${announceBytes.size}:".toByteArray(Charsets.US_ASCII))
                out.write(announceBytes)
                if (httpTrackers.size > 1) {
                    out.write("13:announce-listl".toByteArray(Charsets.US_ASCII))
                    for (tr in httpTrackers) {
                        val trProxied =
                            "http://127.0.0.1:$localPort/announce?upstream=${URLEncoder.encode(tr, "UTF-8")}&size=$totalSize"
                        val trBytes = trProxied.toByteArray(Charsets.UTF_8)
                        out.write("l${trBytes.size}:".toByteArray(Charsets.US_ASCII))
                        out.write(trBytes)
                        out.write("e".toByteArray(Charsets.US_ASCII))
                    }
                    out.write("e".toByteArray(Charsets.US_ASCII))
                }
                out.write("4:info".toByteArray(Charsets.US_ASCII))
                out.write(rawInfo)
                out.write("e".toByteArray(Charsets.US_ASCII))
                out.toByteArray()
            } catch (_: Exception) {
                originalBytes
            }
        }

        private fun handleProxyClient(client: Socket, localPort: Int) {
            client.use { sock ->
                try {
                    sock.soTimeout = 20000
                    val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.US_ASCII))
                    val requestLine = reader.readLine() ?: return
                    val requestHeaders = HashMap<String, String>()
                    while (true) {
                        val h = reader.readLine()
                        if (h.isNullOrEmpty()) break
                        val colon = h.indexOf(':')
                        if (colon > 0) {
                            val k = h.substring(0, colon).trim().lowercase(Locale.ROOT)
                            val v = h.substring(colon + 1).trim()
                            requestHeaders[k] = v
                        }
                    }

                    val parts = requestLine.split(" ")
                    if (parts.size < 2) return
                    val method = parts[0].uppercase(Locale.ROOT)
                    val fullPath = parts[1]
                    val pathOnly = fullPath.substringBefore("?")
                    val rawQuery = fullPath.substringAfter("?", "")

                    if (method == "OPTIONS") {
                        val out = sock.getOutputStream()
                        val corsPreflight = buildString {
                            append("HTTP/1.1 204 No Content\r\n")
                            append("Access-Control-Allow-Origin: *\r\n")
                            append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
                            append("Access-Control-Allow-Headers: Range, If-Range, Content-Type, Origin, Accept\r\n")
                            append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n")
                            append("Access-Control-Max-Age: 86400\r\n")
                            append("Connection: close\r\n\r\n")
                        }
                        out.write(corsPreflight.toByteArray(Charsets.US_ASCII))
                        out.flush()
                        return
                    }

                    when {
                        pathOnly == "/api/health" -> {
                            val torrBase = resolveRunningTorrServerUrl()
                            val healthJson = JSONObject().apply {
                                put("ok", true)
                                put("service", "north-local-proxy")
                                put("port", localPort)
                                put("torrServer", torrBase)
                                put("torrServerAlive", isTorrServerAlive(torrBase))
                            }.toString()
                            sendHttpResponse(sock, 200, "application/json; charset=utf-8", healthJson.toByteArray(Charsets.UTF_8))
                        }
                        pathOnly == "/api/intro.mp4" || pathOnly == "/north_intro.mp4" ->
                            handleProxyIntroVideo(sock, method, requestHeaders)
                        pathOnly == "/torrent" || pathOnly == "/api/torrent" ->
                            handleProxyTorrentDownload(sock, rawQuery, localPort)
                        pathOnly == "/announce" || pathOnly == "/api/announce" ->
                            handleProxyTrackerAnnounce(sock, rawQuery)
                        pathOnly == "/cast" || pathOnly.startsWith("/cast/") || pathOnly.startsWith("/api/cast/") ->
                            handleProxyCastStream(sock, method, rawQuery, requestHeaders, localPort)
                        pathOnly == "/sub" || pathOnly.startsWith("/sub/") || pathOnly == "/api/sub" || pathOnly.startsWith("/api/sub/") ->
                            handleProxySubtitle(sock, method, rawQuery, requestHeaders)
                        else -> sendHttpResponse(sock, 404, "text/plain", "Not Found".toByteArray())
                    }
                } catch (_: Exception) {
                }
            }
        }

        private fun handleProxyIntroVideo(
            sock: Socket,
            method: String,
            requestHeaders: Map<String, String>
        ) {
            try {
                val cachedFile = northIntroCacheFilePath.takeIf { it.isNotBlank() }?.let { File(it) }
                if (cachedFile != null && cachedFile.exists() && cachedFile.length() > 10_000L) {
                    val totalSize = cachedFile.length()
                    val rangeHeader = requestHeaders["range"]
                    var start = 0L
                    var end = totalSize - 1
                    var isPartial = false
                    if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")) {
                        val spec = rangeHeader.removePrefix("bytes=").trim()
                        val startStr = spec.substringBefore("-").trim()
                        val endStr = spec.substringAfter("-", "").trim()
                        start = startStr.toLongOrNull() ?: 0L
                        end = (endStr.toLongOrNull() ?: (totalSize - 1)).coerceAtMost(totalSize - 1)
                        if (start in 0L until totalSize && end >= start) {
                            isPartial = true
                        }
                    }
                    val contentLen = end - start + 1
                    val out = BufferedOutputStream(sock.getOutputStream(), 65536)
                    val hdr = buildString {
                        if (isPartial) {
                            append("HTTP/1.1 206 Partial Content\r\n")
                            append("Content-Range: bytes $start-$end/$totalSize\r\n")
                        } else {
                            append("HTTP/1.1 200 OK\r\n")
                        }
                        append("Content-Type: video/mp4\r\n")
                        append("Content-Length: $contentLen\r\n")
                        append("Accept-Ranges: bytes\r\n")
                        append("Access-Control-Allow-Origin: *\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    out.write(hdr.toByteArray(Charsets.US_ASCII))
                    if (method != "HEAD") {
                        java.io.RandomAccessFile(cachedFile, "r").use { raf ->
                            raf.seek(start)
                            val buf = ByteArray(32768)
                            var remaining = contentLen
                            while (remaining > 0L) {
                                val toRead = min(remaining, buf.size.toLong()).toInt()
                                val n = raf.read(buf, 0, toRead)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                remaining -= n
                            }
                        }
                    }
                    out.flush()
                    return
                }

                val companionBase = proxyApiBaseUrl.trim().trimEnd('/')
                val introUpstreamUrls = mutableListOf<String>()
                if (isCompanionProxyAvailable()) {
                    introUpstreamUrls.add("$companionBase/api/intro.mp4")
                }
                introUpstreamUrls.add(CDN_INTRO_VIDEO_URL)
                for (introUpstream in introUpstreamUrls) {
                    try {
                        val conn = (URL(introUpstream).openConnection() as HttpURLConnection).apply {
                            requestMethod = if (method == "HEAD") "HEAD" else "GET"
                            requestHeaders["range"]?.let { setRequestProperty("Range", it) }
                            setRequestProperty("User-Agent", USER_AGENT)
                            connectTimeout = 8000
                            readTimeout = 15000
                        }
                        val code = conn.responseCode
                        if (code in 200..299) {
                            val cl = conn.getHeaderField("Content-Length")
                            val cr = conn.getHeaderField("Content-Range")
                            val out = BufferedOutputStream(sock.getOutputStream(), 65536)
                            val hdr = buildString {
                                append("HTTP/1.1 $code ${conn.responseMessage ?: "OK"}\r\n")
                                append("Content-Type: video/mp4\r\n")
                                if (!cl.isNullOrBlank()) append("Content-Length: $cl\r\n")
                                if (!cr.isNullOrBlank()) append("Content-Range: $cr\r\n")
                                append("Accept-Ranges: bytes\r\n")
                                append("Access-Control-Allow-Origin: *\r\n")
                                append("Connection: close\r\n\r\n")
                            }
                            out.write(hdr.toByteArray(Charsets.US_ASCII))
                            if (method != "HEAD") {
                                conn.inputStream.use { input ->
                                    val buf = ByteArray(32768)
                                    var n: Int
                                    while (input.read(buf).also { n = it } != -1) {
                                        out.write(buf, 0, n)
                                    }
                                }
                            }
                            out.flush()
                            conn.disconnect()
                            return
                        }
                        conn.disconnect()
                        if (introUpstream.startsWith(companionBase)) markCompanionProxyUnhealthy()
                    } catch (_: Throwable) {
                        if (introUpstream.startsWith(companionBase)) markCompanionProxyUnhealthy()
                    }
                }
            } catch (_: Throwable) {
            }
            sendHttpResponse(sock, 404, "text/plain", "Intro unavailable".toByteArray())
        }

        private fun resolveRunningTorrServerUrl(): String {
            return try {
                val field = Torrent::class.java.getDeclaredField("TORRENT_SERVER_URL")
                field.isAccessible = true
                (field.get(null) as? String)?.takeIf { it.startsWith("http") } ?: "http://127.0.0.1:8090"
            } catch (_: Throwable) {
                "http://127.0.0.1:8090"
            }
        }

        /**
         * Tunes TorrServer's internal BT engine ONCE before adding torrents so it:
         * - Uses a balanced `100 MB` RAM ring buffer (`cacheSize = 104_857_600L`) with `readerReadAhead = 85`
         *   (leaving `15 MB` behind the cursor so 8 MB / 16 MB pieces are never evicted while reading).
         * - Starts playback immediately with `preloadCache = 0` (0 MB pre-download delay).
         * - Uses `connectionsLimit = 55` so TorrServer achieves full seedbox speed without saturating the
         *   phone's half-duplex Wi-Fi radio while simultaneously streaming video over LAN to Chromecast!
         * - Keeps torrents alive for `3600s` (`torrentDisconnectTimeout = 3600`) when the player buffer is full.
         */
        private fun tuneTorrServerForFastStreaming(torrServerBase: String) {
            if (torrServerBase.isBlank() || torrServerTunedBaseUrl == torrServerBase) return
            synchronized(proxyLock) {
                if (torrServerTunedBaseUrl == torrServerBase) return
                torrServerTunedBaseUrl = torrServerBase
            }
            try {
                val getConn = (URL("$torrServerBase/settings").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 2500
                    readTimeout = 2500
                }
                OutputStreamWriter(getConn.outputStream, Charsets.UTF_8).use {
                    it.write("""{"action":"get"}""")
                }
                if (getConn.responseCode !in 200..299) {
                    getConn.disconnect()
                    return
                }
                val currentJsonText = getConn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                getConn.disconnect()

                val rootObj = JSONObject(currentJsonText)
                val setsObj = rootObj.optJSONObject("sets") ?: rootObj

                fun putCaseInsensitive(target: JSONObject, keyName: String, value: Any) {
                    val existingKey = target.keys().asSequence().firstOrNull { it.equals(keyName, ignoreCase = true) }
                    if (existingKey != null) {
                        target.put(existingKey, value)
                    } else {
                        target.put(keyName, value)
                    }
                }

                fun getCaseInsensitive(target: JSONObject, keyName: String): Any? {
                    val existingKey = target.keys().asSequence().firstOrNull { it.equals(keyName, ignoreCase = true) }
                    return if (existingKey != null) target.opt(existingKey) else null
                }

                val alreadyTuned = (getCaseInsensitive(setsObj, "cacheSize") as? Number)?.toLong() == 67_108_864L &&
                    (getCaseInsensitive(setsObj, "readerReadAHead") as? Number)?.toInt() == 70 &&
                    (getCaseInsensitive(setsObj, "preloadCache") as? Number)?.toInt() == 0 &&
                    getCaseInsensitive(setsObj, "preloadBuffer") == false &&
                    (getCaseInsensitive(setsObj, "connectionsLimit") as? Number)?.toInt() == 50 &&
                    getCaseInsensitive(setsObj, "useDisk") == false &&
                    (getCaseInsensitive(setsObj, "retrackersMode") as? Number)?.toInt() == 0 &&
                    (getCaseInsensitive(setsObj, "torrentDisconnectTimeout") as? Number)?.toInt() == 600 &&
                    getCaseInsensitive(setsObj, "disableTCP") == false &&
                    getCaseInsensitive(setsObj, "disableUTP") == false &&
                    getCaseInsensitive(setsObj, "disableUPNP") == false &&
                    getCaseInsensitive(setsObj, "enableIPv6") == false &&
                    getCaseInsensitive(setsObj, "forceEncrypt") == false &&
                    getCaseInsensitive(setsObj, "disableUpload") == false &&
                    (getCaseInsensitive(setsObj, "downloadRateLimit") as? Number)?.toInt() == 0
                if (alreadyTuned) {
                    return
                }

                // Use a 64 MB RAM ring buffer (4x 16 MiB pieces) so 67 GiB season packs with 16 MiB pieces
                // can simultaneously hold the start piece (0) and the end piece (MKV CuesSeekMap) in RAM
                // without thrashing or triggering native OOM crashes!
                putCaseInsensitive(setsObj, "cacheSize", 67_108_864L) // 64 MB RAM ring buffer
                putCaseInsensitive(setsObj, "readerReadAHead", 70) // 70% ahead (~45 MB), 30% (~19 MB > 16 MiB piece) behind cursor
                putCaseInsensitive(setsObj, "preloadCache", 0) // Instant stream start (no preload reset)
                putCaseInsensitive(setsObj, "preloadBuffer", false) // Never block HTTP response waiting for preload buffer
                putCaseInsensitive(setsObj, "connectionsLimit", 50) // Fast piece acquisition for instant timeline seeking
                putCaseInsensitive(setsObj, "useDisk", false) // Pure RAM streaming
                putCaseInsensitive(setsObj, "enableDLNA", false)
                putCaseInsensitive(setsObj, "enableDebug", false)
                putCaseInsensitive(setsObj, "enableRutorSearch", false)
                putCaseInsensitive(setsObj, "retrackersMode", 0) // Never inject public trackers into private torrents
                putCaseInsensitive(setsObj, "torrentDisconnectTimeout", 600)
                // Explicitly enable TCP, uTP, and UPnP while keeping IPv6 false (TorrServer's safe Android
                // default, avoiding Go dual-stack socket hangs/crashes on IPv4-only Android VPNs & Wi-Fi).
                putCaseInsensitive(setsObj, "disableTCP", false)
                putCaseInsensitive(setsObj, "disableUTP", false)
                putCaseInsensitive(setsObj, "disableUPNP", false)
                putCaseInsensitive(setsObj, "enableIPv6", false)
                putCaseInsensitive(setsObj, "forceEncrypt", false)
                putCaseInsensitive(setsObj, "disableUpload", false)
                putCaseInsensitive(setsObj, "downloadRateLimit", 0)

                val payload = JSONObject().apply {
                    put("action", "set")
                    put("sets", setsObj)
                }.toString()

                val setConn = (URL("$torrServerBase/settings").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 2500
                    readTimeout = 2500
                }
                OutputStreamWriter(setConn.outputStream, Charsets.UTF_8).use {
                    it.write(payload)
                }
                setConn.responseCode
                setConn.disconnect()

                // Give TorrServer a brief moment to finish restarting its BT engine with the updated flags
                try {
                    Thread.sleep(350L)
                } catch (_: Throwable) {
                }
                for (waitTry in 0 until 10) {
                    if (isTorrServerAlive(torrServerBase)) break
                    try {
                        Thread.sleep(100L)
                    } catch (_: Throwable) {
                        break
                    }
                }
            } catch (_: Throwable) {
            }
        }

        private fun handleProxyTorrentDownload(sock: Socket, rawQuery: String, localPort: Int) {
            var targetUrl = ""
            var token = ""
            var ghostEnabled = true

            for (pair in rawQuery.split("&")) {
                val k = pair.substringBefore("=")
                val v = pair.substringAfter("=", "")
                when (k) {
                    "url" -> targetUrl = safeUrlDecode(v)
                    "token" -> token = safeUrlDecode(v)
                    "ghost" -> ghostEnabled = (v != "0")
                }
            }

            if (targetUrl.isBlank()) {
                sendHttpResponse(sock, 400, "text/plain", "Missing url".toByteArray())
                return
            }

            val rawBytes = downloadTorrentBytesDirect(targetUrl, token)
            if (rawBytes == null || rawBytes.isEmpty()) {
                sendHttpResponse(sock, 502, "text/plain", "Failed to fetch torrent".toByteArray())
                return
            }

            val parsed = parsedTorrentCache[targetUrl] ?: parseTorrentBytesStatic(rawBytes)?.also {
                putBoundedParsedTorrent(targetUrl, it)
            }

            val finalBytes = rewriteTorrentForGhostProxy(rawBytes, parsed, localPort, ghostEnabled)
            sendHttpResponse(sock, 200, "application/x-bittorrent", finalBytes)
        }

        private data class TrackerPeersInspection(
            val hasFailureReason: Boolean,
            val failureReason: String = "",
            val totalPeers: Int,
            val externalPeers: Int,
            val selfIpPeers: Int,
            val distinctIpv4s: Set<String> = emptySet(),
            val compactPeersRaw: ByteArray? = null
        )

        private fun urlEncodedBytesToHex(encoded: String): String {
            if (encoded.isBlank()) return ""
            val sb = StringBuilder(40)
            var i = 0
            while (i < encoded.length) {
                val c = encoded[i]
                if (c == '%' && i + 2 < encoded.length) {
                    sb.append(encoded[i + 1].lowercaseChar())
                    sb.append(encoded[i + 2].lowercaseChar())
                    i += 3
                } else if (c == '+') {
                    sb.append("20")
                    i++
                } else {
                    sb.append(String.format(Locale.ROOT, "%02x", c.code and 0xFF))
                    i++
                }
            }
            return sb.toString()
        }

        private fun indexOfBytes(haystack: ByteArray, needle: ByteArray, start: Int = 0): Int {
            if (needle.isEmpty() || haystack.size < needle.size) return -1
            outer@ for (i in start..(haystack.size - needle.size)) {
                for (j in needle.indices) {
                    if (haystack[i + j] != needle[j]) continue@outer
                }
                return i
            }
            return -1
        }

        private fun extractBencodeStringAfterKey(bytes: ByteArray, keyAscii: String): String {
            val keyBytes = keyAscii.toByteArray(Charsets.US_ASCII)
            val idx = indexOfBytes(bytes, keyBytes)
            if (idx < 0) return ""
            var pos = idx + keyBytes.size
            if (pos >= bytes.size || bytes[pos] !in '0'.code.toByte()..'9'.code.toByte()) return ""
            var len = 0
            while (pos < bytes.size && bytes[pos] in '0'.code.toByte()..'9'.code.toByte()) {
                len = len * 10 + (bytes[pos] - '0'.code.toByte())
                pos++
            }
            if (pos >= bytes.size || bytes[pos] != ':'.code.toByte()) return ""
            pos++
            if (len <= 0 || pos + len > bytes.size) return ""
            return String(bytes, pos, len, Charsets.UTF_8).trim()
        }

        /**
         * Parses compact IPv4 (`5:peers<N>:`), IPv6 (`6:peers6<N>:`), and non-compact peer lists to distinguish
         * genuine external seedboxes from empty responses, `14:failure reason` errors, or self-IP-only responses
         * (e.g. when a tracker returns only the device's own public IP on closed ports).
         */
        private fun inspectTrackerPeersResponse(bytes: ByteArray?, selfIpv4: String?): TrackerPeersInspection {
            if (bytes == null || bytes.size < 15 || bytes[0] != 'd'.code.toByte()) {
                return TrackerPeersInspection(
                    hasFailureReason = true,
                    failureReason = "empty/invalid tracker reply",
                    totalPeers = 0,
                    externalPeers = 0,
                    selfIpPeers = 0
                )
            }
            val ascii = String(bytes, Charsets.ISO_8859_1)
            if (ascii.contains("14:failure reason")) {
                val reason = extractBencodeStringAfterKey(bytes, "14:failure reason").ifBlank { "tracker rejected announce" }
                return TrackerPeersInspection(
                    hasFailureReason = true,
                    failureReason = reason,
                    totalPeers = 0,
                    externalPeers = 0,
                    selfIpPeers = 0
                )
            }

            var total = 0
            var external = 0
            var selfMatches = 0
            val ipv4Set = LinkedHashSet<String>()
            var compactRaw: ByteArray? = null

            val peersKey = "5:peers".toByteArray(Charsets.US_ASCII)
            val peersIdx = indexOfBytes(bytes, peersKey)
            if (peersIdx >= 0) {
                var pos = peersIdx + peersKey.size
                if (pos < bytes.size) {
                    if (bytes[pos] == 'l'.code.toByte()) {
                        // Non-compact list of peer dicts
                        if (!ascii.contains("5:peersle")) {
                            val ipMatches = Regex("2:ip(\\d+):").findAll(ascii.substring(pos)).toList()
                            for (m in ipMatches) {
                                val len = m.groupValues[1].toIntOrNull() ?: continue
                                val valStart = pos + m.range.last + 1
                                if (valStart + len <= ascii.length) {
                                    val ipStr = ascii.substring(valStart, valStart + len)
                                    ipv4Set.add(ipStr)
                                    total++
                                    if (!selfIpv4.isNullOrBlank() && ipStr == selfIpv4) selfMatches++ else external++
                                }
                            }
                        }
                    } else if (bytes[pos] in '0'.code.toByte()..'9'.code.toByte()) {
                        var len = 0
                        while (pos < bytes.size && bytes[pos] in '0'.code.toByte()..'9'.code.toByte()) {
                            len = len * 10 + (bytes[pos] - '0'.code.toByte())
                            pos++
                        }
                        if (pos < bytes.size && bytes[pos] == ':'.code.toByte()) {
                            pos++
                            if (len >= 6 && pos + len <= bytes.size) {
                                compactRaw = bytes.copyOfRange(pos, pos + len)
                                val count = len / 6
                                for (p in 0 until count) {
                                    val off = pos + p * 6
                                    val ipStr = "${bytes[off].toInt() and 0xFF}.${bytes[off + 1].toInt() and 0xFF}.${bytes[off + 2].toInt() and 0xFF}.${bytes[off + 3].toInt() and 0xFF}"
                                    ipv4Set.add(ipStr)
                                    total++
                                    if (!selfIpv4.isNullOrBlank() && ipStr == selfIpv4) selfMatches++ else external++
                                }
                            }
                        }
                    }
                }
            }

            val peers6Key = "6:peers6".toByteArray(Charsets.US_ASCII)
            val peers6Idx = indexOfBytes(bytes, peers6Key)
            if (peers6Idx >= 0) {
                var pos = peers6Idx + peers6Key.size
                if (pos < bytes.size && bytes[pos] in '0'.code.toByte()..'9'.code.toByte()) {
                    var len = 0
                    while (pos < bytes.size && bytes[pos] in '0'.code.toByte()..'9'.code.toByte()) {
                        len = len * 10 + (bytes[pos] - '0'.code.toByte())
                        pos++
                    }
                    if (pos < bytes.size && bytes[pos] == ':'.code.toByte() && len >= 18 && pos + 1 + len <= bytes.size) {
                        val count6 = len / 18
                        total += count6
                        external += count6
                    }
                }
            }

            return TrackerPeersInspection(
                hasFailureReason = false,
                failureReason = "",
                totalPeers = total,
                externalPeers = external,
                selfIpPeers = selfMatches,
                distinctIpv4s = ipv4Set,
                compactPeersRaw = compactRaw
            )
        }

        private fun buildShortIntervalCompactPeersBencode(compactPeers: ByteArray, intervalSec: Int = 20): ByteArray {
            val prefix = "d8:intervali${intervalSec}e12:min intervali${intervalSec}e5:peers${compactPeers.size}:".toByteArray(Charsets.US_ASCII)
            val out = ByteArrayOutputStream(prefix.size + compactPeers.size + 1)
            out.write(prefix)
            out.write(compactPeers)
            out.write('e'.code)
            return out.toByteArray()
        }

        /**
         * Verifies that a tracker response is a valid bencoded dictionary containing a non-empty `peers`
         * or `peers6` list and NOT a `14:failure reason` (such as `Minimum announce interval not met`),
         * so a re-announce error never overwrites our cached seedbox list or drops TorrServer's swarm!
         */
        private fun isValidTrackerPeersResponse(bytes: ByteArray?, selfIpv4: String? = null): Boolean {
            val inspected = inspectTrackerPeersResponse(bytes, selfIpv4)
            return !inspected.hasFailureReason && inspected.externalPeers > 0
        }

        private fun getGhostSessionPrefs(): SharedPreferences? {
            return try {
                val ctx = (CommonActivity.activity?.applicationContext) ?: (getContext() as? Context)?.applicationContext
                ctx?.getSharedPreferences("NorthGhostSessions", Context.MODE_PRIVATE)
            } catch (_: Throwable) {
                null
            }
        }

        private fun persistGhostPeerSession(
            hashHex: String,
            peerId: String,
            peerKey: String,
            uploadedBytes: Long,
            active: Boolean
        ) {
            if (hashHex.isBlank()) return
            try {
                val prefs = getGhostSessionPrefs() ?: return
                val obj = JSONObject().apply {
                    put("peerId", peerId)
                    put("peerKey", peerKey)
                    put("uploaded", uploadedBytes)
                    put("active", active)
                    put("updatedAt", System.currentTimeMillis())
                }
                prefs.edit().putString("sess_$hashHex", obj.toString()).commit()
            } catch (_: Throwable) {
            }
        }

        private fun loadPersistedGhostPeerSession(hashHex: String): JSONObject? {
            if (hashHex.isBlank()) return null
            return try {
                val raw = getGhostSessionPrefs()?.getString("sess_$hashHex", null) ?: return null
                val obj = JSONObject(raw)
                val updatedAt = obj.optLong("updatedAt", 0L)
                // Expire persisted active peer_id rows after 4 hours
                if (System.currentTimeMillis() - updatedAt > 4 * 3_600_000L) return null
                obj
            } catch (_: Throwable) {
                null
            }
        }

        private fun persistCompactPeersForHash(hashHex: String, compactPeers: ByteArray?) {
            if (hashHex.isBlank() || compactPeers == null || compactPeers.size < 6) return
            try {
                val prefs = getGhostSessionPrefs() ?: return
                val b64 = android.util.Base64.encodeToString(compactPeers, android.util.Base64.NO_WRAP)
                val obj = JSONObject().apply {
                    put("compactB64", b64)
                    put("updatedAt", System.currentTimeMillis())
                }
                prefs.edit().putString("peers_$hashHex", obj.toString()).apply()
            } catch (_: Throwable) {
            }
        }

        private fun loadPersistedCompactPeersForHash(hashHex: String): ByteArray? {
            if (hashHex.isBlank()) return null
            return try {
                val raw = getGhostSessionPrefs()?.getString("peers_$hashHex", null) ?: return null
                val obj = JSONObject(raw)
                val updatedAt = obj.optLong("updatedAt", 0L)
                if (System.currentTimeMillis() - updatedAt > 24 * 3_600_000L) return null
                val b64 = obj.optString("compactB64", "")
                if (b64.isBlank()) return null
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                if (bytes.size >= 6) bytes else null
            } catch (_: Throwable) {
                null
            }
        }

        /**
         * Handles BitTorrent tracker announces from TorrServer:
         * 1. Presents a consistent, whitelisted qBittorrent/4.6.5 identity (`User-Agent` + `-qB4650-` `peer_id` +
         *    8-hex `key`), persisted per `info_hash` on disk so even after an app crash, North cleanly stops the
         *    orphaned `peer_id` FIRST instead of triggering UNIT3D `"You already..."` duplicate-session errors!
         * 2. Always forces `downloaded=0&corrupt=0` so NordicBytes NEVER charges download bytes (`0 B` Real & Credited).
         * 3. Requests `numwant=200&compact=1` and caches genuine external seedbox peer lists both in RAM and on disk
         *    (24h TTL) so playback still succeeds immediately even if the tracker temporarily rate-limits or rejects
         *    an announce after a crash.
         * 4. Uses a short retry interval (`10s`–`20s`) if the tracker returns empty or self-IP-only peers so TorrServer
         *    never sleeps for 30 minutes, and ONLY promotes `left=0` after genuine external peers are received!
         */
        private fun handleProxyTrackerAnnounce(sock: Socket, rawQuery: String) {
            var upstreamUrl = ""
            var rawInfoHashParam = ""
            var rawInfoHashValue = ""
            var eventValue = ""
            var clientPublicIpv4 = ""
            var declaredTorrentSize = 1_610_612_736L // Default 1.5 GiB
            var clientLeftParam = ""
            val passthroughParams = mutableListOf<String>()

            for (param in rawQuery.split("&")) {
                if (param.isBlank()) continue
                val key = param.substringBefore("=")
                val value = param.substringAfter("=", "")
                when (key) {
                    "upstream" -> upstreamUrl = safeUrlDecode(value)
                    "size" -> declaredTorrentSize = value.toLongOrNull()?.coerceAtLeast(536_870_912L) ?: 1_610_612_736L
                    "info_hash" -> {
                        rawInfoHashParam = param
                        rawInfoHashValue = value
                        passthroughParams.add(param)
                    }
                    "ipv4" -> {
                        clientPublicIpv4 = safeUrlDecode(value).trim()
                    }
                    "ipv6", "ip", "port", "peer_id", "key", "downloaded", "corrupt", "uploaded", "numwant", "compact" -> {
                        // Normalized below to match our single deterministic whitelisted qBittorrent/4.6.5 identity & Ghost Ratio
                    }
                    "left" -> {
                        clientLeftParam = param
                    }
                    "event" -> eventValue = value.lowercase(Locale.ROOT)
                    else -> passthroughParams.add(param)
                }
            }

            val infoHashHex = urlEncodedBytesToHex(rawInfoHashValue)
            var effectiveSelfIpv4 = clientPublicIpv4.ifBlank { cachedPublicIpv4 }

            // Short retry interval (10s) on empty/transient tracker error so TorrServer never sleeps for 30 minutes!
            val emptyPeersBencode = "d8:intervali10e12:min intervali10e5:peers0:e".toByteArray(Charsets.US_ASCII)
            if (upstreamUrl.isBlank()) {
                sendHttpResponse(sock, 200, "text/plain", emptyPeersBencode)
                return
            }

            // Key by lowercase hex info_hash so clearCachedStateForHash(hash) always matches and resets sessions!
            val sessionHashKey = infoHashHex.ifBlank { rawInfoHashParam }
            val sessionKey = "$upstreamUrl|$sessionHashKey"
            val detPort = deriveDeterministicQbitPort(infoHashHex)
            val session = ghostSessions.getOrPut(sessionKey) {
                val baseUpload = declaredTorrentSize.coerceIn(1_073_741_824L, 4_294_967_296L) +
                    Random.nextLong(15_000_000L, 120_000_000L)
                val persisted = loadPersistedGhostPeerSession(infoHashHex)
                val (detPeerId, detPeerKey) = deriveDeterministicQbitIdentity(infoHashHex)
                val savedPeerId = persisted?.optString("peerId", "").orEmpty().ifBlank { detPeerId }
                val savedPeerKey = persisted?.optString("peerKey", "").orEmpty().ifBlank { detPeerKey }
                val savedActive = persisted?.optBoolean("active", false) == true
                val savedUpload = persisted?.optLong("uploaded", baseUpload)?.coerceAtLeast(baseUpload) ?: baseUpload
                val savedCompact = loadPersistedCompactPeersForHash(infoHashHex)
                GhostSessionState(
                    startTimeMs = System.currentTimeMillis(),
                    cumulativeUploadedBytes = savedUpload,
                    lastPeersResponse = if (savedCompact != null) buildShortIntervalCompactPeersBencode(savedCompact, intervalSec = 25) else ByteArray(0),
                    peerId = savedPeerId,
                    peerKey = savedPeerKey,
                    peerPort = detPort,
                    // Always stop any prior active/crashed deterministic peer_id first so UNIT3D resets it in <200ms!
                    needsFreshPeerId = savedActive || persisted != null
                )
            }

            val sep = if (upstreamUrl.contains("?")) "&" else "?"

            // Serialize all announces for the SAME torrent so TorrServer's simultaneous IPv4/IPv6/interface
            // announces collapse into ONE single upstream tracker request and NEVER consume 3 peer slots!
            val replyBytes: ByteArray = synchronized(session) {
                val now = System.currentTimeMillis()
                val sessionPort = session.peerPort

                // When TorrServer stops playback, report final boosted upload + downloaded=0 + left=0 + event=stopped
                // ONCE synchronously (1.2s cap) using our deterministic peer_id + port so UNIT3D clears the row cleanly.
                if (eventValue == "stopped") {
                    val finalUpload = session.cumulativeUploadedBytes
                    val cachedReply = if (session.lastPeersResponse.isNotEmpty()) session.lastPeersResponse else emptyPeersBencode
                    val stopPeerId = session.peerId
                    val stopPeerKey = session.peerKey
                    val (detPeerId, detPeerKey) = deriveDeterministicQbitIdentity(infoHashHex)
                    session.peerId = detPeerId
                    session.peerKey = detPeerKey
                    session.peerPort = detPort
                    session.lastAnnounceMs = 0L
                    session.lastUpstreamAttemptMs = 0L
                    session.lastUpstreamReplyBytes = ByteArray(0)
                    session.promotedToSeeder = false
                    session.needsFreshPeerId = false
                    session.lastStoppedAtMs = now
                    persistGhostPeerSession(infoHashHex, detPeerId, detPeerKey, finalUpload, active = false)
                    val stopQuery = (
                        passthroughParams + listOf(
                            "port=$sessionPort",
                            "peer_id=$stopPeerId",
                            "key=$stopPeerKey",
                            "uploaded=$finalUpload",
                            "downloaded=0",
                            "corrupt=0",
                            "left=0",
                            "numwant=0",
                            "compact=1",
                            "event=stopped"
                        )
                        ).joinToString("&")
                    try {
                        executeTrackerGet("$upstreamUrl$sep$stopQuery", 1200)
                    } catch (_: Throwable) {
                    }
                    NorthDebugLogger.logTrackerEvent(infoHashHex, "Sent event=stopped cleanly")
                    return@synchronized cachedReply
                }

                // Single-flight dedup: if another TorrServer interface just announced this exact torrent within 15s,
                // return the exact same reply immediately without firing duplicate upstream announces!
                if (session.lastUpstreamAttemptMs > 0L &&
                    (now - session.lastUpstreamAttemptMs) < 15_000L &&
                    session.lastUpstreamReplyBytes.isNotEmpty()
                ) {
                    return@synchronized session.lastUpstreamReplyBytes
                }

                // If this session was previously active (e.g. promoted to seeder OR left orphaned by a crash on disk),
                // cleanly stop that exact deterministic peer_id + port FIRST so UNIT3D resets the peer row immediately.
                if ((eventValue == "started" || session.lastAnnounceMs == 0L) && session.needsFreshPeerId) {
                    val oldPeerId = session.peerId
                    val oldPeerKey = session.peerKey
                    val oldUpload = session.cumulativeUploadedBytes
                    val (detPeerId, detPeerKey) = deriveDeterministicQbitIdentity(infoHashHex)
                    session.peerId = detPeerId
                    session.peerKey = detPeerKey
                    session.peerPort = detPort
                    session.needsFreshPeerId = false
                    session.promotedToSeeder = false
                    session.lastStoppedAtMs = System.currentTimeMillis()
                    persistGhostPeerSession(infoHashHex, detPeerId, detPeerKey, oldUpload, active = false)
                    try {
                        val cleanupStopQuery = (
                            passthroughParams + listOf(
                                "port=$sessionPort",
                                "peer_id=$oldPeerId",
                                "key=$oldPeerKey",
                                "uploaded=$oldUpload",
                                "downloaded=0",
                                "corrupt=0",
                                "left=0",
                                "numwant=0",
                                "compact=1",
                                "event=stopped"
                            )
                            ).joinToString("&")
                        executeTrackerGet("$upstreamUrl$sep$cleanupStopQuery", 1400)
                        NorthDebugLogger.logTrackerEvent(infoHashHex, "Auto-cleared prior active/crashed peer session via event=stopped")
                    } catch (_: Throwable) {
                    }
                }

                // Brief spacing after event=stopped so UNIT3D never rejects an immediate event=started for the same passkey
                if ((eventValue == "started" || session.lastAnnounceMs == 0L) && session.lastStoppedAtMs > 0L) {
                    val sinceStopMs = System.currentTimeMillis() - session.lastStoppedAtMs
                    if (sinceStopMs in 0L..549L) {
                        try {
                            Thread.sleep(550L - sinceStopMs)
                        } catch (_: Throwable) {
                        }
                    }
                }

                val sessionPeerId = session.peerId
                val sessionPeerKey = session.peerKey
                val identParams = passthroughParams + listOf("port=${session.peerPort}", "peer_id=$sessionPeerId", "key=$sessionPeerKey")

                val elapsedSec = ((now - session.startTimeMs) / 1000L).coerceAtLeast(0L)

                // Always fetch fresh peers on `event=started` (outside the 15s single-flight window) or whenever
                // cached peers are >45 seconds old. Within 45s, serve cached peers to non-`started` announces.
                val cacheIsFresh = session.lastPeersResponse.isNotEmpty() &&
                    eventValue != "started" &&
                    (now - session.lastAnnounceMs) < 45_000L

                if (cacheIsFresh) {
                    if (now - session.lastAnnounceMs >= 120_000L) {
                        session.lastAnnounceMs = now
                        val updatedUpload = session.cumulativeUploadedBytes + (elapsedSec * 18_874_368L)
                        if (updatedUpload > session.cumulativeUploadedBytes) {
                            session.cumulativeUploadedBytes = updatedUpload
                        }
                        thread(isDaemon = true, name = "North-SeederHeartbeat") {
                            try {
                                val heartbeatQuery = (
                                    identParams + listOf(
                                        "uploaded=${session.cumulativeUploadedBytes}",
                                        "downloaded=0",
                                        "corrupt=0",
                                        "left=0",
                                        "numwant=50",
                                        "compact=1"
                                    )
                                    ).joinToString("&")
                                executeTrackerGet("$upstreamUrl$sep$heartbeatQuery", 7000)
                            } catch (_: Exception) {
                            }
                        }
                    }
                    return@synchronized session.lastPeersResponse
                }

                val sendStartedEvent = eventValue == "started" || session.lastAnnounceMs == 0L
                if (sendStartedEvent || (now - session.lastAnnounceMs) >= 45_000L) {
                    session.promotedToSeeder = false
                }

            // Persist our active peer_id BEFORE sending event=started so even an immediate process crash
            // lets the very next launch cleanly send event=stopped for this exact peer_id!
            persistGhostPeerSession(infoHashHex, sessionPeerId, sessionPeerKey, session.cumulativeUploadedBytes, active = true)

            // Initial peer discovery announce (`left > 0` so tracker returns ALL seedboxes!)
            val peerDiscoveryLeft = if (clientLeftParam.isNotBlank() && clientLeftParam != "left=0") {
                clientLeftParam
            } else {
                "left=$declaredTorrentSize"
            }

            val announceQueryList = identParams.toMutableList().apply {
                add("uploaded=0")
                add("downloaded=0")
                add("corrupt=0")
                add(peerDiscoveryLeft)
                add("numwant=200")
                add("compact=1")
                if (sendStartedEvent) {
                    add("event=started")
                }
            }

            val fullAnnounceUrl = "$upstreamUrl$sep${announceQueryList.joinToString("&")}"
            var rawTrackerResp = executeTrackerGet(fullAnnounceUrl, 9000)
            var inspection = inspectTrackerPeersResponse(rawTrackerResp, effectiveSelfIpv4)

            // If tracker returned peers from only 1 distinct IPv4 and we haven't cached our own public IP yet,
            // check our public IPv4 right now (max 1.5s) so self-IP-only swarms are never mistaken for live seedboxes!
            if (!inspection.hasFailureReason && effectiveSelfIpv4.isBlank() && inspection.distinctIpv4s.size == 1) {
                val detected = detectPublicIpv4Sync(1500)
                if (detected.isNotBlank()) {
                    effectiveSelfIpv4 = detected
                    inspection = inspectTrackerPeersResponse(rawTrackerResp, effectiveSelfIpv4)
                }
            }

            var validExternalPeersFound = !inspection.hasFailureReason && inspection.externalPeers > 0

            val reasonLower = inspection.failureReason.lowercase(Locale.ROOT)
            val isAlreadyActiveRejection = inspection.hasFailureReason && (
                reasonLower.contains("already") ||
                    reasonLower.contains("active download") ||
                    reasonLower.contains("slot") ||
                    reasonLower.contains("duplicate")
                )
            val isFatalTrackerRejection = inspection.hasFailureReason && !isAlreadyActiveRejection && (
                reasonLower.contains("unregistered") ||
                    reasonLower.contains("not registered") ||
                    reasonLower.contains("banned") ||
                    reasonLower.contains("passkey") ||
                    reasonLower.contains("unauthorized")
                )
            val isIntervalRateLimit = inspection.hasFailureReason && (
                reasonLower.contains("interval") || reasonLower.contains("too fast") || reasonLower.contains("wait")
                )

            // If UNIT3D rejected with "You already..." (e.g. orphaned peer row after an earlier crash),
            // attempt an explicit event=stopped on our current peer_id + brief pause + non-started announce
            // so we recover automatically whenever possible!
            if (!validExternalPeersFound && isAlreadyActiveRejection) {
                NorthDebugLogger.logTrackerEvent(infoHashHex, "Tracker duplicate-session rejection: ${inspection.failureReason}")
                try {
                    val stopExistingQuery = (
                        identParams + listOf(
                            "uploaded=${session.cumulativeUploadedBytes}",
                            "downloaded=0",
                            "corrupt=0",
                            "left=0",
                            "numwant=0",
                            "compact=1",
                            "event=stopped"
                        )
                        ).joinToString("&")
                    executeTrackerGet("$upstreamUrl$sep$stopExistingQuery", 1400)
                    Thread.sleep(600L)
                    val recoveryList = identParams.toMutableList().apply {
                        add("uploaded=0")
                        add("downloaded=0")
                        add("corrupt=0")
                        add(peerDiscoveryLeft)
                        add("numwant=200")
                        add("compact=1")
                    }
                    val recResp = executeTrackerGet("$upstreamUrl$sep${recoveryList.joinToString("&")}", 7000)
                    val recInsp = inspectTrackerPeersResponse(recResp, effectiveSelfIpv4)
                    if (!recInsp.hasFailureReason && recInsp.externalPeers > 0 && recResp != null) {
                        rawTrackerResp = recResp
                        inspection = recInsp
                        validExternalPeersFound = true
                        NorthDebugLogger.logTrackerEvent(infoHashHex, "Recovered from duplicate-session rejection (${recInsp.externalPeers} external peers)")
                    }
                } catch (_: Throwable) {
                }
            }

            // Only retry without event=started when the tracker actually returned null/invalid/non-fatal error
            // (never hammer UNIT3D twice in 100ms when it already returned a valid bencoded peer list).
            if (!validExternalPeersFound && sendStartedEvent && !isFatalTrackerRejection && !isIntervalRateLimit && !isAlreadyActiveRejection &&
                (rawTrackerResp == null || inspection.hasFailureReason)
            ) {
                val regularAnnounceList = identParams.toMutableList().apply {
                    add("uploaded=0")
                    add("downloaded=0")
                    add("corrupt=0")
                    add(peerDiscoveryLeft)
                    add("numwant=200")
                    add("compact=1")
                }
                val retryResp = executeTrackerGet("$upstreamUrl$sep${regularAnnounceList.joinToString("&")}", 7000)
                val retryInspection = inspectTrackerPeersResponse(retryResp, effectiveSelfIpv4)
                if (!retryInspection.hasFailureReason && retryInspection.externalPeers > 0 && retryResp != null) {
                    rawTrackerResp = retryResp
                    inspection = retryInspection
                    validExternalPeersFound = true
                } else if (inspection.totalPeers == 0 && !retryInspection.hasFailureReason && retryInspection.totalPeers > 0 && retryResp != null) {
                    rawTrackerResp = retryResp
                    inspection = retryInspection
                }
            }

            if (infoHashHex.isNotBlank()) {
                val diagStr = when {
                    inspection.hasFailureReason -> "tracker: ${inspection.failureReason.take(90)}"
                    validExternalPeersFound -> "${inspection.externalPeers} seeders"
                    inspection.selfIpPeers > 0 -> "only own IP (${inspection.selfIpPeers})"
                    else -> "0 peers from tracker"
                }
                lastTrackerDiagByHash[infoHashHex] = diagStr
                NorthDebugLogger.logTrackerEvent(infoHashHex, "Announce result: $diagStr")
                if (isFatalTrackerRejection) {
                    deadSwarmHashes[infoHashHex] = now
                    noExternalPeersHashes[infoHashHex] = now
                }
            }

            val persistedCompactFallback = if (!validExternalPeersFound) loadPersistedCompactPeersForHash(infoHashHex) else null

            val peerBytes = if (validExternalPeersFound && rawTrackerResp != null) {
                if (infoHashHex.isNotBlank()) {
                    noExternalPeersHashes.remove(infoHashHex)
                    deadSwarmHashes.remove(infoHashHex)
                }
                session.lastPeersResponse = rawTrackerResp
                session.lastAnnounceMs = now
                persistCompactPeersForHash(infoHashHex, inspection.compactPeersRaw)
                rawTrackerResp
            } else if (session.lastPeersResponse.isNotEmpty() && isValidTrackerPeersResponse(session.lastPeersResponse, effectiveSelfIpv4)) {
                NorthDebugLogger.logTrackerEvent(infoHashHex, "Reusing RAM cached external peers after tracker error/empty reply")
                session.lastPeersResponse
            } else if (persistedCompactFallback != null) {
                NorthDebugLogger.logTrackerEvent(infoHashHex, "Reusing disk-persisted seedbox peers (${persistedCompactFallback.size / 6} peers) after tracker rejection")
                val rebuilt = buildShortIntervalCompactPeersBencode(persistedCompactFallback, intervalSec = 25)
                session.lastPeersResponse = rebuilt
                rebuilt
            } else {
                if (infoHashHex.isNotBlank()) {
                    noExternalPeersHashes[infoHashHex] = now
                    if (isAlreadyActiveRejection) {
                        alreadyActiveRejectionHashes[infoHashHex] = now
                        showDeadSwarmToastThrottled(
                            infoHashHex,
                            "North: NordicBytes tracker locked (${inspection.failureReason.take(90)}). Wait for auto-expiry or see North Settings.",
                            postFollowUp = true
                        )
                        NorthDebugLogger.reportHandledFatalAsync(
                            "TRACKER_ALREADY_ACTIVE",
                            "NordicBytes rejected announce for ${infoHashHex.take(10)}: ${inspection.failureReason}",
                            null
                        )
                    }
                }
                // If tracker only returned self-IP peers (e.g. stale local sessions), pass them with a short 20s
                // interval so TorrServer never sleeps for 30 minutes while our fast-fail switches sources cleanly.
                val selfCompact = inspection.compactPeersRaw
                if (!inspection.hasFailureReason && selfCompact != null && selfCompact.size >= 6) {
                    buildShortIntervalCompactPeersBencode(selfCompact, intervalSec = 20)
                } else {
                    emptyPeersBencode
                }
            }

                // Record result for single-flight dedup across TorrServer's simultaneous interface announces
                session.lastUpstreamAttemptMs = System.currentTimeMillis()
                session.lastUpstreamReplyBytes = peerBytes

                // Only promote to Seeder (`left=0`) AFTER we actually received genuine external seedbox peers, and wait
                // 35s so we never trip tracker `min_interval` rejections if the user quickly reopens or resumes!
                if (validExternalPeersFound && !session.promotedToSeeder) {
                    session.promotedToSeeder = true
                    session.needsFreshPeerId = true
                    thread(isDaemon = true, name = "North-RatioBooster") {
                        try {
                            Thread.sleep(35_000L)
                            // Skip promotion if peer_id rotated in the meantime (stream was reset/stopped)
                            if (session.peerId != sessionPeerId) return@thread
                            val boostUpload = session.cumulativeUploadedBytes
                            val promoteQuery = (
                                identParams + listOf(
                                    "uploaded=$boostUpload",
                                    "downloaded=0",
                                    "corrupt=0",
                                    "left=0",
                                    "numwant=200",
                                    "compact=1"
                                )
                                ).joinToString("&")
                            val boostResp = executeTrackerGet("$upstreamUrl$sep$promoteQuery", 6000)
                            if (isValidTrackerPeersResponse(boostResp, effectiveSelfIpv4) && boostResp != null && session.lastPeersResponse.isEmpty()) {
                                session.lastPeersResponse = boostResp
                            }
                        } catch (_: Exception) {
                        }
                    }
                }

                peerBytes
            }

            // Return the seedbox peer list (or short 10s-20s retry if empty/self-IP-only)
            sendHttpResponse(sock, 200, "text/plain", replyBytes)
        }

        private fun isTorrServerAlive(baseUrl: String): Boolean {
            if (!baseUrl.startsWith("http")) return false
            return try {
                val conn = (URL("$baseUrl/echo").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 1500
                    readTimeout = 1500
                }
                val ok = conn.responseCode in 200..299
                conn.disconnect()
                ok
            } catch (_: Throwable) {
                false
            }
        }

        /**
         * Ensures TorrServer is started, tuned once, and adds `torrInputLink` (either our Ghost-Ratio-proxied
         * `/torrent` URL or a Ghost-Ratio-proxied `magnet:` fallback), returning the local TorrServer HTTP stream URL.
         * Synchronized on `torrResolveLock` so parallel HTTP range connections from Chromecast/ExoPlayer never
         * re-add or reset the same torrent concurrently.
         */
        private fun clearCachedStateForHash(hash: String) {
            if (hash.isBlank()) return
            noExternalPeersHashes.remove(hash.lowercase(Locale.ROOT))
            for (entry in castStreamUrlCache.entries) {
                if (entry.value.contains(hash, ignoreCase = true) || entry.key.contains(hash, ignoreCase = true)) {
                    castStreamUrlCache.remove(entry.key)
                }
            }
            for (entry in transcodedStreamContextMap.entries) {
                if (entry.key.contains(hash, ignoreCase = true)) {
                    transcodedStreamContextMap.remove(entry.key)
                }
            }
            for (entry in transcodedStreamUrls.entries) {
                if (entry.key.contains(hash, ignoreCase = true)) {
                    transcodedStreamUrls.remove(entry.key)
                }
            }
            for (entry in ghostSessions.entries) {
                if (entry.key.contains(hash, ignoreCase = true)) {
                    // Reset timestamps & force a fresh peer_id on next start so UNIT3D always returns live seedboxes,
                    // while keeping lastPeersResponse as fallback if NordicBytes returns "Minimum announce interval not met".
                    entry.value.lastAnnounceMs = 0L
                    entry.value.lastUpstreamAttemptMs = 0L
                    entry.value.lastUpstreamReplyBytes = ByteArray(0)
                    entry.value.promotedToSeeder = false
                    entry.value.needsFreshPeerId = true
                }
            }
        }

        private fun dropTorrServerHashAsync(hash: String) {
            if (hash.isBlank()) return
            clearCachedStateForHash(hash)
            thread(isDaemon = true, name = "North-DropIdleTorrent") {
                synchronized(torrResolveLock) {
                    // Abort drop if user switched back to this exact torrent while the drop thread was queued!
                    if (activeKeepAliveHash.equals(hash, ignoreCase = true)) return@synchronized
                    try {
                        val baseUrl = resolveRunningTorrServerUrl()
                        if (!isTorrServerAlive(baseUrl)) return@synchronized
                        val conn = (URL("$baseUrl/torrents").openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"
                            doOutput = true
                            setRequestProperty("Content-Type", "application/json")
                            connectTimeout = 2500
                            readTimeout = 2500
                        }
                        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                            it.write(JSONObject().apply {
                                put("action", "drop")
                                put("hash", hash)
                            }.toString())
                        }
                        conn.responseCode
                        conn.disconnect()
                    } catch (_: Throwable) {
                    }
                }
            }
        }

        private fun removeTorrServerHashSync(baseUrl: String, hash: String) {
            if (hash.isBlank()) return
            torrentAddedTimestampMs.remove(hash.trim().lowercase(Locale.ROOT))
            if (!isTorrServerAlive(baseUrl)) return
            try {
                val conn = (URL("$baseUrl/torrents").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 2500
                    readTimeout = 2500
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                    it.write(JSONObject().apply {
                        put("action", "rem")
                        put("hash", hash)
                    }.toString())
                }
                conn.responseCode
                conn.disconnect()
            } catch (_: Throwable) {
            }
        }

        /**
         * Verifies a TorrServer `/torrents action=get` status object is genuinely live and usable:
         * - `stat` must be 1 (GettingInfo), 2 (Preload), or 3 (Working) — NOT 4 (TorrentClosed) or 5 (TorrentInDB).
         * - `file_stats` must be non-empty.
         * - If `trackers` contains any local proxy URL (`http://127.0.0.1:<port>/announce`), `<port>` MUST match
         *   our currently bound `localPort`! Otherwise the torrent was cached from an earlier session/plugin reload
         *   with a dead tracker port and MUST be removed (`action: "rem"`) and re-added cleanly.
         */
        private fun isTorrServerStatusHealthyForPort(
            status: JSONObject?,
            localPort: Int,
            requireFiles: Boolean = true
        ): Boolean {
            if (status == null) return false
            val statCode = status.optInt("stat", 0)
            if (statCode !in 1..3) return false
            if (requireFiles) {
                val fStats = status.optJSONArray("file_stats")
                if (fStats == null || fStats.length() == 0) return false
            }
            if (localPort > 0) {
                val trackersArr = status.optJSONArray("trackers")
                if (trackersArr != null && trackersArr.length() > 0) {
                    val expectedPrefix = "http://127.0.0.1:$localPort/"
                    for (i in 0 until trackersArr.length()) {
                        val tr = trackersArr.optString(i, "").trim()
                        if (tr.startsWith("http://127.0.0.1:") && !tr.startsWith(expectedPrefix)) {
                            return false
                        }
                    }
                }
            }
            return true
        }

        /**
         * Verifies that a TorrServer torrent actually has live swarm activity (or is still within its
         * initial 25-second warmup window right after `action: "add"`) and is NOT a dead zombie
         * (`stat == 3` with 0 connected peers and 0 download speed left behind after app idle, sleep,
         * or a Wi-Fi/VPN network switch where TorrServer's tracker timer can sit silent for 30 minutes).
         */
        private fun isTorrServerSwarmLive(
            status: JSONObject?,
            hash: String,
            idleBeforeCallMs: Long
        ): Boolean {
            if (status == null) return false
            val statCode = status.optInt("stat", 0)
            val activePeers = status.optInt("active_peers", 0)
            val connectedSeeders = status.optInt("connected_seeders", 0)
            val pendingPeers = status.optInt("pending_peers", 0)
            val dlSpeed = status.optDouble("download_speed", 0.0)
            val preloaded = status.optLong("preloaded_bytes", 0L)

            if (activePeers > 0 || connectedSeeders > 0 || dlSpeed > 0.0) return true

            // Protect freshly added torrents (e.g. prewarmed in loadLinks() right before ExoPlayer's
            // first Range requests) so we never kill a torrent while its initial tracker announce and
            // first peer handshakes are still completing.
            val hashLower = hash.trim().lowercase(Locale.ROOT)
            val addedAt = if (hashLower.isNotBlank()) (torrentAddedTimestampMs[hashLower] ?: 0L) else 0L
            if (addedAt > 0L && (System.currentTimeMillis() - addedAt) < 25_000L) {
                return true
            }

            // Past the 25s warmup window (or loaded from an earlier app session with no addedAt record):
            // if we have been idle >=15s with zero connected peers and zero download speed, force a clean
            // rem + re-add to open fresh sockets and trigger an immediate `event=started` tracker announce.
            if (idleBeforeCallMs >= 15_000L) return false

            return pendingPeers > 0 || statCode == 2 || preloaded > 0L
        }

        private fun queryTorrServerStatus(baseUrl: String, hash: String): JSONObject? {
            if (hash.isBlank() || !isTorrServerAlive(baseUrl)) return null
            return try {
                val conn = (URL("$baseUrl/torrents").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 2500
                    readTimeout = 2500
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                    it.write(JSONObject().apply {
                        put("action", "get")
                        put("hash", hash)
                    }.toString())
                }
                val code = conn.responseCode
                val obj = if (code in 200..299) {
                    val txt = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    JSONObject(txt)
                } else {
                    null
                }
                conn.disconnect()
                obj
            } catch (_: Throwable) {
                null
            }
        }

        private fun resolveAuthoritativeTorrFile(
            statsArr: org.json.JSONArray?,
            requestedIndex: Int,
            seasonNum: Int,
            epNum: Int
        ): Pair<Int, String>? {
            if (statsArr == null || statsArr.length() == 0) return null
            val videoRegex = Regex("""(?i)\.(mkv|mp4|avi|ts|m2ts|m4v|mov|webm)$""")
            val items = ArrayList<Triple<Int, String, Long>>(statsArr.length())
            for (i in 0 until statsArr.length()) {
                val st = statsArr.optJSONObject(i) ?: continue
                val id = if (st.has("id")) st.optInt("id", i + 1) else (i + 1)
                val path = st.optString("path", "").trim()
                val length = st.optLong("length", 0L)
                if (path.isNotBlank() && !isPaddingOrNonMediaPath(path)) {
                    items.add(Triple(id, path, length))
                }
            }
            if (items.isEmpty()) return null

            // Reject unplayable archive-only packs (.rar/.r00/.zip/.iso with 0 non-sample video files) so ExoPlayer never hangs on them
            if (isArchiveOnlyFilePaths(items.map { it.second })) {
                return null
            }

            if (epNum > 0) {
                val posMatch = matchEpisodeFileIndexStatic(
                    fileNames = items.map { it.second },
                    seasonNum = seasonNum.coerceAtLeast(1),
                    epNum = epNum
                )
                if (posMatch != null && posMatch in 1..items.size) {
                    val chosen = items[posMatch - 1]
                    return Pair(chosen.first, chosen.second)
                }
            }

            // Verify whether requestedIndex already points to a genuine non-sample video file
            val exactEntry = items.firstOrNull { it.first == requestedIndex }
            if (exactEntry != null && videoRegex.containsMatchIn(exactEntry.second) &&
                !exactEntry.second.contains("sample", ignoreCase = true)
            ) {
                return Pair(exactEntry.first, exactEntry.second)
            }

            // Otherwise pick largest non-sample video file (movie fallback)
            val bestMoviePos = resolveMainMovieFileIndexStatic(items.map { it.second to it.third })
            val fallbackEntry = items.getOrNull(bestMoviePos - 1) ?: items.first()
            return Pair(fallbackEntry.first, fallbackEntry.second)
        }

        private fun wipeCorruptTorrServerDb(cacheBase: String) {
            torrServerTunedBaseUrl = ""
            try {
                val dir = File(cacheBase)
                if (dir.exists() && dir.isDirectory) {
                    for (name in listOf("config.db", "config.db.lock", "torrents.db", "settings.json", "accs.db")) {
                        try {
                            val f = File(dir, name)
                            if (f.exists()) f.delete()
                        } catch (_: Throwable) {
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }

        private fun startTorrServerDirectlyIfStopped(): String? {
            val currentUrl = resolveRunningTorrServerUrl()
            if (isTorrServerAlive(currentUrl)) return currentUrl

            torrServerTunedBaseUrl = ""
            val cacheBase = try {
                torrentsCacheDirPath.ifBlank {
                    val ctx = CommonActivity.activity ?: (getContext() as? Context)
                    val dir = ctx?.cacheDir?.absolutePath
                    if (!dir.isNullOrBlank()) "$dir/torrents" else "/data/local/tmp/torrents"
                }
            } catch (_: Throwable) {
                "/data/local/tmp/torrents"
            }

            fun invokeStartOnce(): String? {
                return try {
                    File(cacheBase).mkdirs()
                    try {
                        val seqClass = Class.forName("go.Seq")
                        seqClass.getMethod("load").invoke(null)
                    } catch (_: Throwable) {
                    }
                    val tsClass = Class.forName("torrServer.TorrServer")
                    val startMethod = tsClass.getMethod("startTorrentServer", String::class.java, Long::class.javaPrimitiveType)
                    val port = (startMethod.invoke(null, cacheBase, 0L) as? Long) ?: -1L
                    if (port > 0L) {
                        val startedUrl = "http://127.0.0.1:$port"
                        try {
                            val field = Torrent::class.java.getDeclaredField("TORRENT_SERVER_URL")
                            field.isAccessible = true
                            field.set(null, startedUrl)
                        } catch (_: Throwable) {
                        }
                        for (attempt in 0 until 20) {
                            if (isTorrServerAlive(startedUrl)) return startedUrl
                            try {
                                Thread.sleep(100L)
                            } catch (_: Throwable) {
                                break
                            }
                        }
                    }
                    null
                } catch (_: Throwable) {
                    null
                }
            }

            invokeStartOnce()?.let { return it }

            // If a prior crash corrupted TorrServer's BoltDB config.db on disk, wipe only the local cache DB
            // and restart cleanly (North always re-adds torrents fresh from NordicBytes, so zero user data is lost).
            wipeCorruptTorrServerDb(cacheBase)
            return invokeStartOnce()
        }

        /**
         * Starts TorrServer cleanly via native Go bindings BEFORE adding any torrent (preventing
         * the Engine #1 -> Engine #2 reboot & 38 public tracker spam from `Torrent.transformLink`),
         * tunes `/settings`, adds `torrInputLink`, verifies the exact target video `id` and `path`
         * against TorrServer's authoritative `file_stats`, and returns `/stream/<name>?link=<hash>&index=<id>&play`.
         */
        private fun resolveSingleTorrServerLink(
            torrInputLink: String,
            exactIndex: Int,
            isMagnet: Boolean,
            forceReAdd: Boolean = false,
            seasonNum: Int = 0,
            epNum: Int = 0,
            knownHash: String = "",
            localPort: Int = proxyServerPort
        ): String? {
            try {
                Torrent.hasAcceptedTorrentForThisSession = true
            } catch (_: Throwable) {
            }

            val effectivePort = if (localPort > 0) localPort else proxyServerPort
            val cacheKey = "$torrInputLink|s=$seasonNum|e=$epNum|idx=$exactIndex"

            synchronized(torrResolveLock) {
                val now = System.currentTimeMillis()
                val idleBeforeCallMs = if (lastSuccessfulStreamResponseMs > 0L) {
                    (now - lastSuccessfulStreamResponseMs).coerceAtLeast(0L)
                } else {
                    Long.MAX_VALUE
                }
                val currentBase = resolveRunningTorrServerUrl()
                if (!forceReAdd) {
                    castStreamUrlCache[cacheKey]?.let { cached ->
                        if (cached.startsWith(currentBase)) {
                            val cachedHash = cached.substringAfter("link=", "").substringBefore("&").trim()
                            // Always verify TorrServer live status (<2ms on loopback): torrent must be in
                            // Working/Preload state, bound to our live localPort, AND have a live swarm
                            // (never reuse a dead 0-peer zombie left behind by sleep or a network/VPN change).
                            val status = queryTorrServerStatus(currentBase, cachedHash)
                            if (isTorrServerStatusHealthyForPort(status, effectivePort, requireFiles = true) &&
                                isTorrServerSwarmLive(status, cachedHash, idleBeforeCallMs)
                            ) {
                                return cached
                            }
                            clearCachedStateForHash(cachedHash)
                            if (cachedHash.isNotBlank()) {
                                removeTorrServerHashSync(currentBase, cachedHash)
                            }
                            castStreamUrlCache.remove(cacheKey)
                        }
                    }
                } else {
                    castStreamUrlCache.remove(cacheKey)?.let { oldUrl ->
                        val oldHash = oldUrl.substringAfter("link=", "").substringBefore("&").trim()
                        if (oldHash.isNotBlank()) clearCachedStateForHash(oldHash)
                    }
                }

                // 1. Start TorrServer directly FIRST without calling Torrent.transformLink (no Engine reboot/public tracker spam)
                var baseUrl = startTorrServerDirectlyIfStopped() ?: currentBase
                if (!isTorrServerAlive(baseUrl)) {
                    try {
                        runBlocking {
                            val dummyLink = newExtractorLink(
                                source = "North",
                                name = "North Setup Probe",
                                url = torrInputLink,
                                type = if (isMagnet) ExtractorLinkType.MAGNET else ExtractorLinkType.TORRENT
                            )
                            Torrent.transformLink(dummyLink).first.url
                        }
                    } catch (_: Throwable) {
                    }
                    baseUrl = resolveRunningTorrServerUrl()
                }

                // 2. Tune TorrServer settings BEFORE adding the torrent, purge any stale/closed/dead-port
                //    or old 0-peer zombie torrent with `action: "rem"`, then POST /torrents `action: "add"`.
                try {
                    if (isTorrServerAlive(baseUrl)) {
                        tuneTorrServerForFastStreaming(baseUrl)

                        var reusableLiveStatus: JSONObject? = null
                        if (knownHash.isNotBlank()) {
                            val existingStatus = queryTorrServerStatus(baseUrl, knownHash)
                            val isHealthyAndLive = existingStatus != null &&
                                !forceReAdd &&
                                isTorrServerStatusHealthyForPort(existingStatus, effectivePort, requireFiles = true) &&
                                isTorrServerSwarmLive(existingStatus, knownHash, idleBeforeCallMs)
                            if (isHealthyAndLive) {
                                reusableLiveStatus = existingStatus
                            } else if (existingStatus != null) {
                                clearCachedStateForHash(knownHash)
                                removeTorrServerHashSync(baseUrl, knownHash)
                                try {
                                    Thread.sleep(150L)
                                } catch (_: Throwable) {
                                }
                            }
                        }

                        fun postAddTorrent(): JSONObject? {
                            val addConn = (URL("$baseUrl/torrents").openConnection() as HttpURLConnection).apply {
                                requestMethod = "POST"
                                doOutput = true
                                setRequestProperty("Content-Type", "application/json")
                                connectTimeout = 15000
                                readTimeout = 20000
                            }
                            val addPayload = JSONObject().apply {
                                put("action", "add")
                                put("link", torrInputLink)
                            }.toString()
                            OutputStreamWriter(addConn.outputStream, Charsets.UTF_8).use {
                                it.write(addPayload)
                            }
                            val code = addConn.responseCode
                            val parsedObj = if (code in 200..299) {
                                val respText = addConn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                                JSONObject(respText)
                            } else {
                                null
                            }
                            addConn.disconnect()
                            return parsedObj
                        }

                        var statusJson = reusableLiveStatus ?: postAddTorrent()
                        if (statusJson == null && reusableLiveStatus == null) {
                            // Self-heal if TorrServer's BT engine or settings DB was wedged after a crash
                            try {
                                val defConn = (URL("$baseUrl/settings").openConnection() as HttpURLConnection).apply {
                                    requestMethod = "POST"
                                    doOutput = true
                                    setRequestProperty("Content-Type", "application/json")
                                    connectTimeout = 2500
                                    readTimeout = 2500
                                }
                                OutputStreamWriter(defConn.outputStream, Charsets.UTF_8).use {
                                    it.write("""{"action":"def"}""")
                                }
                                defConn.responseCode
                                defConn.disconnect()
                                Thread.sleep(350L)
                                torrServerTunedBaseUrl = ""
                                tuneTorrServerForFastStreaming(baseUrl)
                                statusJson = postAddTorrent()
                            } catch (_: Throwable) {
                            }
                        }
                        var hash = statusJson?.optString("hash", "")?.trim().orEmpty()
                        if (reusableLiveStatus == null && hash.isNotBlank() && statusJson != null) {
                            // If TorrServer returned a pre-existing closed/stale-port torrent (or an old >30s
                            // 0-peer zombie from an earlier session when knownHash was blank), remove it via
                            // `action: "rem"` and re-add once cleanly. NEVER kill a brand-new add!
                            val existingCheck = queryTorrServerStatus(baseUrl, hash) ?: statusJson
                            val portHealthy = isTorrServerStatusHealthyForPort(existingCheck, effectivePort, requireFiles = false)
                            val existingStat = existingCheck.optInt("stat", 0)
                            val existingActive = existingCheck.optInt("active_peers", 0) +
                                existingCheck.optInt("connected_seeders", 0) +
                                existingCheck.optInt("pending_peers", 0)
                            val serverTsSec = existingCheck.optLong("timestamp", 0L)
                            val nowSec = System.currentTimeMillis() / 1000L
                            val isOldPreexistingZombie = serverTsSec > 0L &&
                                (nowSec - serverTsSec) > 30L &&
                                existingStat == 3 &&
                                existingActive == 0 &&
                                existingCheck.optDouble("download_speed", 0.0) <= 0.0
                            if (forceReAdd || !portHealthy || isOldPreexistingZombie) {
                                clearCachedStateForHash(hash)
                                removeTorrServerHashSync(baseUrl, hash)
                                try {
                                    Thread.sleep(150L)
                                } catch (_: Throwable) {
                                }
                                statusJson = postAddTorrent()
                                hash = statusJson?.optString("hash", "")?.trim().orEmpty()
                            }
                            if (hash.isNotBlank()) {
                                torrentAddedTimestampMs[hash.lowercase(Locale.ROOT)] = System.currentTimeMillis()
                            }
                        }

                        if (hash.isNotBlank() && statusJson != null) {
                            var statsArr = statusJson.optJSONArray("file_stats")
                            // Poll briefly if file_stats isn't populated synchronously yet (multi-file packs or magnets)
                            if (statsArr == null || statsArr.length() == 0) {
                                val maxPolls = if (isMagnet) 10 else 6
                                for (poll in 0 until maxPolls) {
                                    try {
                                        Thread.sleep(400L)
                                    } catch (_: Throwable) {
                                        break
                                    }
                                    statsArr = queryTorrServerStatus(baseUrl, hash)?.optJSONArray("file_stats")
                                    if (statsArr != null && statsArr.length() > 0) break
                                }
                            }
                            val resolvedFile = resolveAuthoritativeTorrFile(statsArr, exactIndex, seasonNum, epNum)
                            if (resolvedFile == null && statsArr != null && statsArr.length() > 0) {
                                // Torrent contains only unplayable archive files (.rar/.zip/.iso) -> fail fast to next source
                                unplayableArchiveHashes[hash.lowercase(Locale.ROOT)] = System.currentTimeMillis()
                                return null
                            }
                            val resolvedId = resolvedFile?.first ?: exactIndex.coerceAtLeast(1)
                            val rawPath = resolvedFile?.second ?: "video.mkv"
                            val leafName = rawPath.substringAfterLast("/").ifBlank { rawPath }
                            val encPath = URLEncoder.encode(leafName, "UTF-8").replace("+", "%20")
                            val builtStreamUrl = "$baseUrl/stream/$encPath?link=$hash&index=$resolvedId&play"
                            castStreamUrlCache[cacheKey] = builtStreamUrl
                            return builtStreamUrl
                        }
                    }
                } catch (_: Throwable) {
                }

                // 3. Fallback via Torrent.transformLink
                try {
                    val resolvedUrl: String? = runBlocking<String?> {
                        val dummyLink = newExtractorLink(
                            source = "North",
                            name = "North Unified Stream",
                            url = torrInputLink,
                            type = if (isMagnet) ExtractorLinkType.MAGNET else ExtractorLinkType.TORRENT
                        )
                        Torrent.transformLink(dummyLink).first.url
                    }
                    if (!resolvedUrl.isNullOrBlank() && resolvedUrl.startsWith("http")) {
                        castStreamUrlCache[cacheKey] = resolvedUrl
                        return resolvedUrl
                    }
                } catch (_: Throwable) {
                }

                return null
            }
        }

        private fun resolveTorrServerStreamUrl(
            localTorrentUrl: String?,
            magnetFallbackUrl: String?,
            exactIndex: Int,
            forceReAdd: Boolean = false,
            seasonNum: Int = 0,
            epNum: Int = 0,
            knownHash: String = "",
            localPort: Int = proxyServerPort
        ): String? {
            if (!localTorrentUrl.isNullOrBlank()) {
                val fromTorrent = resolveSingleTorrServerLink(
                    localTorrentUrl,
                    exactIndex,
                    isMagnet = false,
                    forceReAdd = forceReAdd,
                    seasonNum = seasonNum,
                    epNum = epNum,
                    knownHash = knownHash,
                    localPort = localPort
                )
                if (!fromTorrent.isNullOrBlank()) return fromTorrent
            }
            if (!magnetFallbackUrl.isNullOrBlank()) {
                val fromMagnet = resolveSingleTorrServerLink(
                    magnetFallbackUrl,
                    exactIndex,
                    isMagnet = true,
                    forceReAdd = forceReAdd,
                    seasonNum = seasonNum,
                    epNum = epNum,
                    knownHash = knownHash,
                    localPort = localPort
                )
                if (!fromMagnet.isNullOrBlank()) return fromMagnet
            }
            return null
        }

        /**
         * Replicates CloudStream's internal `CS3IPlayer.torrentEventLooper(hash)` for our unified
         * `ExtractorLinkType.VIDEO` stream (both in local ExoPlayer and during Chromecast `castSession`).
         * Also drops any previously active different hash when switching mirrors/episodes so abandoned
         * torrents don't compete for peers or RAM.
         */
        private fun startTorrServerKeepAlive(
            streamUrl: String,
            localTorrentUrl: String?,
            magnetFallbackUrl: String?,
            exactIndex: Int,
            seasonNum: Int = 0,
            epNum: Int = 0
        ) {
            val hash = streamUrl.substringAfter("link=", "").substringBefore("&").trim()
            if (hash.isBlank()) return
            val prevHash = activeKeepAliveHash
            if (prevHash.isNotBlank() && !prevHash.equals(hash, ignoreCase = true)) {
                dropTorrServerHashAsync(prevHash)
            }
            activeKeepAliveHash = hash
            lastKeepAliveTouchMs = System.currentTimeMillis()
            acquireStreamingLocks()
            if (isKeepAliveThreadRunning) return
            synchronized(proxyLock) {
                if (isKeepAliveThreadRunning) return
                isKeepAliveThreadRunning = true
            }
            thread(isDaemon = true, name = "North-TorrKeepAliveLooper") {
                try {
                    while (true) {
                        val currentHash = activeKeepAliveHash
                        val idleMs = System.currentTimeMillis() - lastKeepAliveTouchMs
                        if (currentHash.isBlank() || idleMs > 1_800_000L) { // Keep alive for up to 30 min of pause/buffer
                            break
                        }
                        try {
                            val baseUrl = resolveRunningTorrServerUrl()
                            val conn = (URL("$baseUrl/torrents").openConnection() as HttpURLConnection).apply {
                                requestMethod = "POST"
                                doOutput = true
                                setRequestProperty("Content-Type", "application/json")
                                connectTimeout = 2500
                                readTimeout = 2500
                            }
                            val payload = JSONObject().apply {
                                put("action", "get")
                                put("hash", currentHash)
                            }.toString()
                            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                                it.write(payload)
                            }
                            val code = conn.responseCode
                            conn.disconnect()
                            if (code == 404 && (!localTorrentUrl.isNullOrBlank() || !magnetFallbackUrl.isNullOrBlank())) {
                                resolveTorrServerStreamUrl(
                                    localTorrentUrl,
                                    magnetFallbackUrl,
                                    exactIndex,
                                    forceReAdd = true,
                                    seasonNum = seasonNum,
                                    epNum = epNum
                                )
                            }
                        } catch (_: Throwable) {
                        }
                        try {
                            Thread.sleep(2000L)
                        } catch (_: Throwable) {
                        }
                    }
                } finally {
                    isKeepAliveThreadRunning = false
                    releaseStreamingLocks()
                }
            }
        }

        private fun warmMkvCuesTailAsync(
            torrStreamUrl: String,
            cuesOffset: Long,
            totalFileBytes: Long
        ) {
            // Deprecated no-op: local ExoPlayer streams directly from TorrServer's native Go HTTP engine,
            // so opening a separate background EOF HTTP reader would only compete with ExoPlayer's own Cues seek.
            if (torrStreamUrl.isBlank() || cuesOffset < 0L || totalFileBytes < -1L) return
        }

        /**
         * Pre-starts and tunes TorrServer cleanly, adds the top torrent, and launches keep-alive
         * in the background WITHOUT opening any competing HTTP range reader on `/stream/...`.
         */
        private fun prewarmTorrServerStream(
            localTorrentUrl: String?,
            magnetFallbackUrl: String?,
            exactIndex: Int,
            seasonNum: Int = 0,
            epNum: Int = 0,
            knownHash: String = "",
            localPort: Int = proxyServerPort
        ) {
            thread(isDaemon = true, name = "North-CastSequentialPrewarm") {
                try {
                    val streamUrl = resolveTorrServerStreamUrl(
                        localTorrentUrl,
                        magnetFallbackUrl,
                        exactIndex,
                        forceReAdd = false,
                        seasonNum = seasonNum,
                        epNum = epNum,
                        knownHash = knownHash,
                        localPort = localPort
                    ) ?: return@thread
                    startTorrServerKeepAlive(streamUrl, localTorrentUrl, magnetFallbackUrl, exactIndex, seasonNum, epNum)
                } catch (_: Throwable) {
                }
            }
        }

        private data class ResolvedCastStreamTarget(
            val torrStreamUrl: String,
            val localTorrentUrl: String?,
            val magnetFallbackUrl: String?,
            val exactIndex: Int,
            val targetSeason: Int,
            val targetEpisode: Int,
            val infoHash: String,
            val displayName: String,
            val explicitCastQuery: Boolean
        )

        /**
         * Resolves a `/cast/stream.mp4?...` query string into its authoritative TorrServer stream URL
         * (`http://127.0.0.1:<torrPort>/stream/<fname>?link=<hash>&index=<id>&play`) while keeping all
         * `.torrent` and `/announce` traffic routed through our local Ghost Ratio Proxy.
         */
        private fun resolveDirectTorrStreamFromCastQuery(
            rawQuery: String,
            localPort: Int,
            forceReAdd: Boolean = false
        ): ResolvedCastStreamTarget? {
            var targetUrl = ""
            var token = ""
            var ghostEnabled = true
            var exactIndex = 1
            var isExplicitPackIndex = false
            var targetSeason = 0
            var targetEpisode = 0
            var infoHash = ""
            var displayName = "North.Stream"
            var primaryTracker = ""
            var totalSize = 1_610_612_736L
            var explicitCastQuery = false

            for (pair in rawQuery.split("&")) {
                val k = pair.substringBefore("=")
                val v = pair.substringAfter("=", "")
                when (k) {
                    "index" -> exactIndex = v.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    "pack" -> isExplicitPackIndex = (v == "1")
                    "s" -> targetSeason = v.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    "e" -> targetEpisode = v.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    "url" -> targetUrl = safeUrlDecode(v)
                    "token" -> token = safeUrlDecode(v)
                    "ghost" -> ghostEnabled = (v != "0")
                    "hash" -> infoHash = safeUrlDecode(v).trim()
                    "dn" -> displayName = safeUrlDecode(v).ifBlank { "North.Stream" }
                    "tr" -> primaryTracker = safeUrlDecode(v).trim()
                    "sz" -> totalSize = v.toLongOrNull()?.coerceAtLeast(536_870_912L) ?: 1_610_612_736L
                    "cast" -> explicitCastQuery = (v == "1")
                }
            }

            if (targetUrl.isBlank() && infoHash.isBlank()) return null

            if (targetUrl.isNotBlank() && !targetUrl.startsWith("magnet:")) {
                val authTargetUrl = appendTokenToDownloadUrlStatic(targetUrl, token)
                val meta = parsedTorrentCache[authTargetUrl] ?: parsedTorrentCache[targetUrl] ?: run {
                    val rawBytes = downloadTorrentBytesDirect(authTargetUrl, token)
                    if (rawBytes != null && rawBytes.isNotEmpty()) {
                        parseTorrentBytesStatic(rawBytes)?.also {
                            putBoundedParsedTorrent(authTargetUrl, it)
                            putBoundedParsedTorrent(targetUrl, it)
                        }
                    } else {
                        null
                    }
                }
                if (meta != null) {
                    if (infoHash.isBlank() && meta.infoHashHex.isNotBlank()) {
                        infoHash = meta.infoHashHex
                    }
                    if (primaryTracker.isBlank()) {
                        primaryTracker = meta.trackers.firstOrNull { it.startsWith("http") }.orEmpty()
                    }
                    if (meta.files.isNotEmpty()) {
                        val computedSz = meta.files.sumOf { it.second }.coerceAtLeast(536_870_912L)
                        if (computedSz > 0L) totalSize = computedSz
                        if ((isExplicitPackIndex || targetEpisode > 0) && targetEpisode > 0) {
                            val resolvedEpIdx = matchEpisodeFileIndexStatic(
                                fileNames = meta.files.map { it.first },
                                seasonNum = targetSeason.coerceAtLeast(1),
                                epNum = targetEpisode
                            )
                            if (resolvedEpIdx != null && resolvedEpIdx > 0) {
                                exactIndex = resolvedEpIdx
                            }
                        } else if (!isExplicitPackIndex && meta.files.size > 1) {
                            exactIndex = resolveMainMovieFileIndexStatic(meta.files).coerceAtLeast(1)
                        }
                    }
                }
            }

            val effectivePort = if (localPort > 0) localPort else ensureLocalProxyServer()
            val ghostFlag = if (ghostEnabled) "1" else "0"
            val localTorrentUrl = if (targetUrl.isNotBlank() && !targetUrl.startsWith("magnet:")) {
                val encUrl = URLEncoder.encode(targetUrl, "UTF-8")
                val encTok = URLEncoder.encode(token, "UTF-8")
                "http://127.0.0.1:$effectivePort/torrent?index=$exactIndex&url=$encUrl&token=$encTok&ghost=$ghostFlag"
            } else {
                null
            }

            val magnetFallbackUrl = when {
                targetUrl.startsWith("magnet:") -> {
                    val sep = if (targetUrl.contains("?")) "&" else "?"
                    "${targetUrl}${sep}index=$exactIndex"
                }
                infoHash.isNotBlank() -> {
                    val trParams = buildString {
                        if (primaryTracker.startsWith("http")) {
                            val proxiedTr = if (ghostEnabled) {
                                "http://127.0.0.1:$effectivePort/announce?upstream=${URLEncoder.encode(primaryTracker, "UTF-8")}&size=$totalSize"
                            } else {
                                primaryTracker
                            }
                            append("&tr=").append(URLEncoder.encode(proxiedTr, "UTF-8"))
                        } else {
                            for (pubTr in PUBLIC_FAST_TRACKERS) {
                                append("&tr=").append(URLEncoder.encode(pubTr, "UTF-8"))
                            }
                        }
                    }
                    "magnet:?xt=urn:btih:$infoHash&index=$exactIndex&dn=${URLEncoder.encode(displayName, "UTF-8")}$trParams"
                }
                else -> null
            }

            val torrStreamUrl = resolveTorrServerStreamUrl(
                localTorrentUrl,
                magnetFallbackUrl,
                exactIndex,
                forceReAdd = forceReAdd,
                seasonNum = targetSeason,
                epNum = targetEpisode,
                knownHash = infoHash,
                localPort = effectivePort
            )?.takeIf { it.isNotBlank() } ?: return null

            val resolvedHashFromUrl = torrStreamUrl.substringAfter("link=", "").substringBefore("&").trim()
            val finalHash = resolvedHashFromUrl.ifBlank { infoHash }

            torrUrlToCastQueryMap[torrStreamUrl] = rawQuery
            startTorrServerKeepAlive(torrStreamUrl, localTorrentUrl, magnetFallbackUrl, exactIndex, targetSeason, targetEpisode)

            return ResolvedCastStreamTarget(
                torrStreamUrl = torrStreamUrl,
                localTorrentUrl = localTorrentUrl,
                magnetFallbackUrl = magnetFallbackUrl,
                exactIndex = exactIndex,
                targetSeason = targetSeason,
                targetEpisode = targetEpisode,
                infoHash = finalHash,
                displayName = displayName,
                explicitCastQuery = explicitCastQuery
            )
        }

        /**
         * Unified `/cast/stream.mp4` HTTP Video Relay:
         * - Immediately rejects Android `stagefright` / `MediaMetadataRetriever` (`Mp4PreviewGenerator`) probes
         *   BEFORE acquiring `torrResolveLock` or touching TorrServer.
         * - For local ExoPlayer & external players (`!isChromecastRequest`), issues an `HTTP/1.1 302 Found`
         *   redirect directly to TorrServer's native Go `http.ServeContent` stream URL.
         * - For Chromecast devices on the LAN (`isChromecastRequest`), relays the stream over LAN HTTP
         *   with Byte-Exact Void-Padded 2.0 Stereo AAC Matroska audio transcoding when needed.
         */
        private fun handleProxyCastStream(
            sock: Socket,
            method: String,
            rawQuery: String,
            requestHeaders: Map<String, String>,
            localPort: Int
        ) {
            val ua = requestHeaders["user-agent"].orEmpty()
            if (ua.contains("stagefright", ignoreCase = true) ||
                ua.contains("MediaMetadataRetriever", ignoreCase = true)
            ) {
                try {
                    val out = BufferedOutputStream(sock.getOutputStream(), 1024)
                    val forbidHeaders = buildString {
                        append("HTTP/1.1 403 Forbidden\r\n")
                        append("Content-Length: 0\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    out.write(forbidHeaders.toByteArray(Charsets.US_ASCII))
                    out.flush()
                } catch (_: Throwable) {
                }
                return
            }

            val resolved = resolveDirectTorrStreamFromCastQuery(rawQuery, localPort, forceReAdd = false)
            if (resolved == null) {
                sendHttpResponse(sock, 502, "text/plain", "Unable to initialize TorrServer stream".toByteArray())
                return
            }

            var torrStreamUrl = resolved.torrStreamUrl
            val localTorrentUrl = resolved.localTorrentUrl
            val magnetFallbackUrl = resolved.magnetFallbackUrl
            val exactIndex = resolved.exactIndex
            val targetSeason = resolved.targetSeason
            val targetEpisode = resolved.targetEpisode
            val infoHash = resolved.infoHash
            val displayName = resolved.displayName
            val explicitCastQuery = resolved.explicitCastQuery

            var upstreamConn: HttpURLConnection? = null
            try {
                sock.soTimeout = 0
                sock.keepAlive = true
                sock.tcpNoDelay = true

                val clientRangeHeader = requestHeaders["range"]

                val isExternalLanClient = try {
                    val remoteAddr = sock.inetAddress
                    val localAddr = sock.localAddress
                    val remoteHost = remoteAddr?.hostAddress.orEmpty()
                    val localHost = localAddr?.hostAddress.orEmpty()
                    remoteAddr != null &&
                        !remoteAddr.isLoopbackAddress &&
                        remoteHost != "127.0.0.1" &&
                        remoteHost != "::1" &&
                        remoteHost != localHost &&
                        remoteHost != getLocalLanIpAddress()
                } catch (_: Throwable) {
                    false
                }
                val isCastReceiverUA = ua.contains("CrKey", ignoreCase = true) ||
                    ua.contains("Chromecast", ignoreCase = true) ||
                    ua.contains("Cast", ignoreCase = true) ||
                    ua.contains("Fuchsia", ignoreCase = true) ||
                    ua.contains("GoogleTV", ignoreCase = true)
                // CRITICAL: ANY request arriving from another device on the LAN (`isExternalLanClient == true`)
                // or from a Cast receiver MUST be served directly over LAN HTTP and NEVER redirected to 127.0.0.1:8090!
                val isChromecastRequest = isExternalLanClient || isCastReceiverUA || explicitCastQuery

                // Direct TorrServer redirect ONLY for local on-device players (ExoPlayer, VLC, mpv, JustPlayer, MX Player):
                // eliminates the middleman socket proxy on the phone so TorrServer's Go net/http engine receives instant TCP FIN
                // on every seek/scrub and handles Matroska Cues range requests natively.
                if (!isChromecastRequest) {
                    val out = BufferedOutputStream(sock.getOutputStream(), 4096)
                    val redirectHeaders = buildString {
                        append("HTTP/1.1 302 Found\r\n")
                        append("Location: $torrStreamUrl\r\n")
                        append("Accept-Ranges: bytes\r\n")
                        append("Access-Control-Allow-Origin: *\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    out.write(redirectHeaders.toByteArray(Charsets.US_ASCII))
                    out.flush()
                    return
                }

                try {
                    sock.sendBufferSize = 524_288
                } catch (_: Throwable) {
                }

                var requestedStartOffset = 0L
                var requestedEndInclusive = -1L
                if (!clientRangeHeader.isNullOrBlank() && clientRangeHeader.startsWith("bytes=")) {
                    val spec = clientRangeHeader.removePrefix("bytes=").substringBefore(",")
                    requestedStartOffset = spec.substringBefore("-").trim().toLongOrNull() ?: 0L
                    requestedEndInclusive = spec.substringAfter("-", "").trim().toLongOrNull() ?: -1L
                }

                val cleanTitle = displayName.replace("\"", "").replace("\r", "").replace("\n", "").ifBlank { "North Stream" }

                fun openTorrConnection(streamUrl: String, rangeHeaderValue: String?, readTimeoutMs: Int): HttpURLConnection {
                    return (URL(streamUrl).openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = true
                        requestMethod = if (method == "HEAD") "HEAD" else "GET"
                        if (!rangeHeaderValue.isNullOrBlank()) {
                            setRequestProperty("Range", rangeHeaderValue)
                        }
                        setRequestProperty("User-Agent", requestHeaders["user-agent"] ?: USER_AGENT)
                        connectTimeout = 30000
                        readTimeout = readTimeoutMs
                    }
                }

                // Helper to open a resumable TorrServer stream at any exact byte offset for Byte-Exact Cast requests
                fun openResumableTorrStreamAtOffset(offset: Long): BufferedInputStream? {
                    val readTimeoutMs = if (offset > 0L) 60000 else 30000
                    val rangeVal = "bytes=$offset-"
                    var conn = openTorrConnection(torrStreamUrl, rangeVal, readTimeoutMs)
                    var respCode = try {
                        conn.responseCode
                    } catch (_: Exception) {
                        -1
                    }
                    if (respCode !in 200..299 && respCode != 416) {
                        try {
                            conn.disconnect()
                        } catch (_: Exception) {
                        }
                        val refreshed = resolveTorrServerStreamUrl(
                            localTorrentUrl,
                            magnetFallbackUrl,
                            exactIndex,
                            forceReAdd = (respCode == 404 || respCode == 410),
                            seasonNum = targetSeason,
                            epNum = targetEpisode
                        ) ?: return null
                        torrStreamUrl = refreshed
                        conn = openTorrConnection(torrStreamUrl, rangeVal, readTimeoutMs)
                        respCode = try {
                            conn.responseCode
                        } catch (_: Exception) {
                            -1
                        }
                    }
                    if (respCode !in 200..299) {
                        try {
                            conn.disconnect()
                        } catch (_: Exception) {
                        }
                        return null
                    }
                    upstreamConn = conn
                    val expectedLen = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                    val resumable = object : InputStream() {
                        private var currentStream: InputStream? = try {
                            conn.inputStream
                        } catch (_: Exception) {
                            null
                        }
                        private var totalRead = 0L
                        private var resumeCount = 0

                        override fun read(): Int {
                            val b = ByteArray(1)
                            val n = read(b, 0, 1)
                            return if (n == 1) (b[0].toInt() and 0xFF) else -1
                        }

                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            if (len == 0) return 0
                            while (true) {
                                if (sock.isClosed) return -1
                                val s = currentStream
                                if (s != null) {
                                    try {
                                        val n = s.read(b, off, len)
                                        if (n > 0) {
                                            totalRead += n
                                            val okNow = System.currentTimeMillis()
                                            lastKeepAliveTouchMs = okNow
                                            lastSuccessfulStreamResponseMs = okNow
                                            resumeCount = 0
                                            return n
                                        }
                                        if (n == -1 && (expectedLen <= 0L || totalRead >= expectedLen)) {
                                            return -1
                                        }
                                    } catch (_: Exception) {
                                    }
                                }
                                if (sock.isClosed) return -1
                                if (expectedLen in 1L..totalRead) return -1
                                if (resumeCount >= 6) return -1
                                resumeCount++
                                try {
                                    currentStream?.close()
                                } catch (_: Exception) {
                                }
                                try {
                                    upstreamConn?.disconnect()
                                } catch (_: Exception) {
                                }
                                try {
                                    Thread.sleep(400L * resumeCount.coerceAtMost(4))
                                } catch (_: Exception) {
                                }
                                if (sock.isClosed) return -1
                                val nextOff = offset + totalRead
                                val nextConn = openTorrConnection(torrStreamUrl, "bytes=$nextOff-", 90000)
                                upstreamConn = nextConn
                                val rCode = try {
                                    nextConn.responseCode
                                } catch (_: Exception) {
                                    -1
                                }
                                if (rCode !in 200..299) return -1
                                currentStream = try {
                                    nextConn.inputStream
                                } catch (_: Exception) {
                                    null
                                }
                            }
                        }

                        override fun close() {
                            try {
                                currentStream?.close()
                            } catch (_: Exception) {
                            }
                        }
                    }
                    return BufferedInputStream(resumable, 32_768)
                }

                // Fast-path for Chromecast requests when we already built a Byte-Exact Matroska Context!
                // Serves header slices & cached recent Clusters directly from RAM (0ms latency) and handles
                // EOF Cues reads, Cues timeline seeks, and mid-cluster resyncs with strict RFC 7233 headers.
                val existingByteExactCtx = transcodedStreamContextMap[torrStreamUrl]
                if (isChromecastRequest && existingByteExactCtx != null) {
                    val out = BufferedOutputStream(sock.getOutputStream(), 32768)
                    if (method == "HEAD") {
                        val headHeaders = buildString {
                            append("HTTP/1.1 200 OK\r\n")
                            append("Content-Type: video/mp4\r\n")
                            append("icy-name: $cleanTitle\r\n")
                            append("Content-Disposition: inline; filename=\"$cleanTitle.mp4\"\r\n")
                            append("Accept-Ranges: bytes\r\n")
                            append("Content-Length: ${existingByteExactCtx.totalFileBytes}\r\n")
                            append("Access-Control-Allow-Origin: *\r\n")
                            append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
                            append("Access-Control-Allow-Headers: Range, If-Range, Content-Type, Origin, Accept\r\n")
                            append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n")
                            append("Connection: close\r\n\r\n")
                        }
                        out.write(headHeaders.toByteArray(Charsets.US_ASCII))
                        out.flush()
                        return
                    }
                    serveByteExactMatroskaFromContext(
                        ctx = existingByteExactCtx,
                        out = out,
                        streamTitle = cleanTitle,
                        clientHasRange = !clientRangeHeader.isNullOrBlank(),
                        requestedStartOffset = requestedStartOffset,
                        requestedEndInclusive = requestedEndInclusive,
                        openUpstreamAtOffset = ::openResumableTorrStreamAtOffset
                    )
                    return
                }

                // Streaming-fallback streams (rare MKVs with indeterminate Segment/Cluster sizes or no AAC encoder):
                // fall back to v34's proven 200 OK + Accept-Ranges: none streaming mode from byte 0.
                val isStreamingFallbackStream = isChromecastRequest &&
                    transcodedStreamUrls[torrStreamUrl] == true &&
                    existingByteExactCtx == null

                if (isStreamingFallbackStream && method == "HEAD") {
                    val out = BufferedOutputStream(sock.getOutputStream(), 4096)
                    val headHeaders = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: video/mp4\r\n")
                        append("icy-name: $cleanTitle\r\n")
                        append("Content-Disposition: inline; filename=\"$cleanTitle.mp4\"\r\n")
                        append("Accept-Ranges: none\r\n")
                        append("Access-Control-Allow-Origin: *\r\n")
                        append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
                        append("Access-Control-Allow-Headers: Range, If-Range, Content-Type, Origin, Accept\r\n")
                        append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    out.write(headHeaders.toByteArray(Charsets.US_ASCII))
                    out.flush()
                    return
                }

                val forceTranscodedRestartFromZero = isStreamingFallbackStream && requestedStartOffset > 0L

                // Fast-path: Serve Matroska Cues / EOF tail seek requests directly from RAM if already cached
                if (!isChromecastRequest && method != "HEAD" && requestedStartOffset > 0L) {
                    val cachedTail = mkvCuesTailCache[torrStreamUrl]
                    if (cachedTail != null) {
                        val tailStart = cachedTail.first
                        val tailBytes = cachedTail.second
                        val totalLen = tailStart + tailBytes.size.toLong()
                        if (requestedStartOffset >= tailStart && requestedStartOffset < totalLen) {
                            val sliceOffset = (requestedStartOffset - tailStart).toInt()
                            val sliceEndExclusive = if (requestedEndInclusive >= requestedStartOffset && requestedEndInclusive < totalLen) {
                                (requestedEndInclusive - tailStart + 1L).toInt()
                            } else {
                                tailBytes.size
                            }
                            val sliceLen = (sliceEndExclusive - sliceOffset).coerceAtLeast(0)
                            if (sliceLen > 0) {
                                val endByte = requestedStartOffset + sliceLen - 1L
                                val fastOut = BufferedOutputStream(sock.getOutputStream(), 32768)
                                val fastHeaders = buildString {
                                    append("HTTP/1.1 206 Partial Content\r\n")
                                    append("Content-Type: video/x-matroska\r\n")
                                    append("icy-name: $cleanTitle\r\n")
                                    append("Content-Disposition: inline; filename=\"$cleanTitle.mkv\"\r\n")
                                    append("Accept-Ranges: bytes\r\n")
                                    append("Content-Length: $sliceLen\r\n")
                                    append("Content-Range: bytes $requestedStartOffset-$endByte/$totalLen\r\n")
                                    append("Access-Control-Allow-Origin: *\r\n")
                                    append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
                                    append("Access-Control-Allow-Headers: Range, If-Range, Content-Type, Origin, Accept\r\n")
                                    append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n")
                                    append("Connection: close\r\n\r\n")
                                }
                                fastOut.write(fastHeaders.toByteArray(Charsets.US_ASCII))
                                fastOut.write(tailBytes, sliceOffset, sliceLen)
                                fastOut.flush()
                                return
                            }
                        }
                    }
                }

                // Allow generous read timeout on forward/EOF seek requests (including 16 MiB season-pack pieces)
                val initialReadTimeout = if (requestedStartOffset > 0L) 60000 else 30000
                // When Chromecast requests offset 0 for an uninspected stream, open TorrServer with open-ended
                // `bytes=0-` so we can read the full Matroska header up to `firstClusterOffset` even if the client
                // sent a small probe like `Range: bytes=0-32767` (we still strictly cap output at `requestedEndInclusive`).
                val openedOpenEndedAtZero = isChromecastRequest && method != "HEAD" &&
                    (requestedStartOffset == 0L || forceTranscodedRestartFromZero)
                val effectiveUpstreamRange = when {
                    forceTranscodedRestartFromZero -> null
                    openedOpenEndedAtZero -> "bytes=0-"
                    else -> clientRangeHeader ?: if (!isChromecastRequest) "bytes=0-" else null
                }
                upstreamConn = openTorrConnection(torrStreamUrl, effectiveUpstreamRange, initialReadTimeout)
                var code = try {
                    upstreamConn.responseCode
                } catch (_: Exception) {
                    -1
                }

                if (code !in 200..299 && code != 416) {
                    try {
                        upstreamConn.disconnect()
                    } catch (_: Exception) {
                    }
                    torrStreamUrl = resolveTorrServerStreamUrl(
                        localTorrentUrl,
                        magnetFallbackUrl,
                        exactIndex,
                        forceReAdd = (code == 404 || code == 410),
                        seasonNum = targetSeason,
                        epNum = targetEpisode
                    ) ?: run {
                        sendHttpResponse(sock, 502, "text/plain", "Unable to re-initialize TorrServer stream".toByteArray())
                        return
                    }
                    upstreamConn = openTorrConnection(torrStreamUrl, effectiveUpstreamRange, initialReadTimeout)
                    code = upstreamConn.responseCode
                }

                val contentLength = upstreamConn.getHeaderField("Content-Length")
                var contentRange = upstreamConn.getHeaderField("Content-Range")
                val acceptRanges = upstreamConn.getHeaderField("Accept-Ranges") ?: "bytes"

                val expectedBytes = contentLength?.toLongOrNull() ?: -1L
                var startByteOffset = if (forceTranscodedRestartFromZero) 0L else requestedStartOffset
                var endByteInclusive = requestedEndInclusive
                var totalFileBytes = -1L
                if (!contentRange.isNullOrBlank() && contentRange.startsWith("bytes ")) {
                    val afterBytes = contentRange.removePrefix("bytes ")
                    val spec = afterBytes.substringBefore("/")
                    totalFileBytes = afterBytes.substringAfter("/", "").trim().toLongOrNull() ?: -1L
                    startByteOffset = spec.substringBefore("-").trim().toLongOrNull() ?: startByteOffset
                    endByteInclusive = spec.substringAfter("-", "").trim().toLongOrNull() ?: endByteInclusive
                } else if (expectedBytes > 0L) {
                    totalFileBytes = startByteOffset + expectedBytes
                    endByteInclusive = totalFileBytes - 1L
                    if (!clientRangeHeader.isNullOrBlank()) {
                        contentRange = "bytes $startByteOffset-$endByteInclusive/$totalFileBytes"
                        if (code == 200) code = 206
                    }
                }

                // If client did not send a Range header (or we forced a streaming-fallback restart from 0),
                // normalize status to 200 OK and omit Content-Range.
                if ((clientRangeHeader.isNullOrBlank() || forceTranscodedRestartFromZero) && code == 206 && startByteOffset == 0L) {
                    code = 200
                    contentRange = null
                }

                val out = BufferedOutputStream(sock.getOutputStream(), 32768)

                val resumableInput = object : InputStream() {
                    private var currentStream: InputStream? = try {
                        upstreamConn?.inputStream
                    } catch (_: Exception) {
                        null
                    }
                    private var totalReadFromUpstream = 0L
                    private var resumeCount = 0
                    private var initialWaitCycles = 0

                    override fun read(): Int {
                        val b = ByteArray(1)
                        val n = read(b, 0, 1)
                        return if (n == 1) (b[0].toInt() and 0xFF) else -1
                    }

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (len == 0) return 0
                        while (true) {
                            if (sock.isClosed) return -1
                            val s = currentStream
                            if (s != null) {
                                try {
                                    val n = s.read(b, off, len)
                                    if (n > 0) {
                                        totalReadFromUpstream += n
                                        val okNow = System.currentTimeMillis()
                                        lastKeepAliveTouchMs = okNow
                                        lastSuccessfulStreamResponseMs = okNow
                                        resumeCount = 0
                                        initialWaitCycles = 0
                                        return n
                                    }
                                    if (n == -1 && (expectedBytes <= 0L || totalReadFromUpstream >= expectedBytes)) {
                                        return -1
                                    }
                                } catch (_: SocketTimeoutException) {
                                    if (totalReadFromUpstream == 0L) {
                                        val hash = torrStreamUrl.substringAfter("link=", "").substringBefore("&").trim()
                                        val status = queryTorrServerStatus(resolveRunningTorrServerUrl(), hash)
                                        val seeders = status?.optInt("connected_seeders", 0) ?: 0
                                        val activePeers = status?.optInt("active_peers", 0) ?: 0
                                        val useful = status?.optLong("bytes_read_useful_data", 0L) ?: 0L
                                        initialWaitCycles++
                                        if ((seeders > 0 || activePeers > 0 || useful > 0L) && initialWaitCycles <= 5) {
                                            continue
                                        }
                                        if (seeders == 0 && activePeers == 0 && initialWaitCycles > 2) {
                                            return -1
                                        }
                                        for (entry in ghostSessions.entries) {
                                            if (infoHash.isNotBlank() && entry.key.contains(infoHash, ignoreCase = true)) {
                                                entry.value.lastPeersResponse = ByteArray(0)
                                                entry.value.lastAnnounceMs = 0L
                                            }
                                        }
                                    }
                                } catch (_: Exception) {
                                }
                            }
                            if (sock.isClosed) return -1
                            if (expectedBytes in 1L..totalReadFromUpstream) return -1
                            if (expectedBytes <= 0L && totalReadFromUpstream > 0L && resumeCount >= 2) return -1
                            if (resumeCount >= 6) return -1

                            resumeCount++
                            try {
                                currentStream?.close()
                            } catch (_: Exception) {
                            }
                            try {
                                upstreamConn?.disconnect()
                            } catch (_: Exception) {
                            }
                            try {
                                Thread.sleep(400L * resumeCount.coerceAtMost(4))
                            } catch (_: Exception) {
                            }
                            if (sock.isClosed) return -1
                            val nextOffset = startByteOffset + totalReadFromUpstream
                            val resumeRange = if (endByteInclusive >= nextOffset) {
                                "bytes=$nextOffset-$endByteInclusive"
                            } else {
                                "bytes=$nextOffset-"
                            }
                            val nextReadTimeout = if (totalReadFromUpstream == 0L) initialReadTimeout else 90000
                            upstreamConn = openTorrConnection(torrStreamUrl, resumeRange, nextReadTimeout)
                            var rCode = try {
                                upstreamConn?.responseCode ?: -1
                            } catch (_: Exception) {
                                -1
                            }
                            if (rCode == 404 || rCode == 410) {
                                try {
                                    upstreamConn?.disconnect()
                                } catch (_: Exception) {
                                }
                                val refreshedUrl = resolveTorrServerStreamUrl(
                                    localTorrentUrl,
                                    magnetFallbackUrl,
                                    exactIndex,
                                    forceReAdd = true,
                                    seasonNum = targetSeason,
                                    epNum = targetEpisode
                                ) ?: torrStreamUrl
                                torrStreamUrl = refreshedUrl
                                upstreamConn = openTorrConnection(torrStreamUrl, resumeRange, nextReadTimeout)
                                rCode = try {
                                    upstreamConn?.responseCode ?: -1
                                } catch (_: Exception) {
                                    -1
                                }
                            }
                            if (rCode !in 200..299) return -1
                            currentStream = try {
                                upstreamConn?.inputStream
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }

                    override fun close() {
                        try {
                            currentStream?.close()
                        } catch (_: Exception) {
                        }
                    }
                }

                val bufferedUpstream = BufferedInputStream(resumableInput, 32_768)

                var preReadBytes: ByteArray? = null
                var detectedMkvStream = false
                // Inspect Chromecast streams at offset 0 to parse EBML/Tracks and decide between:
                // 1) 100% raw passthrough (AAC/Opus/Vorbis/MP3/FLAC - 2.0 OR 5.1)
                // 2) Byte-Exact EBML Void-Padded 128 kbps AAC transcoding (full Cues + timeline seeking!)
                // 3) Streaming fallback (edge-case MKVs with unknown Segment/Cluster size or no AAC encoder)
                if (isChromecastRequest && method != "HEAD" && code in 200..299 && startByteOffset == 0L) {
                    val peek4 = ByteArray(4)
                    var readCount = 0
                    while (readCount < 4) {
                        val r = bufferedUpstream.read(peek4, readCount, 4 - readCount)
                        if (r <= 0) break
                        readCount += r
                    }
                    val initialBytes = if (readCount == 4) peek4 else peek4.copyOf(readCount)
                    val isMkv = initialBytes.size == 4 &&
                        initialBytes[0] == 0x1A.toByte() &&
                        initialBytes[1] == 0x45.toByte() &&
                        initialBytes[2] == 0xDF.toByte() &&
                        initialBytes[3] == 0xA3.toByte()
                    detectedMkvStream = isMkv

                    if (isMkv) {
                        val handledByTranscoder = streamMatroskaWithChromecastAudio(
                            initialPrefix = initialBytes,
                            upstream = bufferedUpstream,
                            out = out,
                            streamKey = torrStreamUrl,
                            streamTitle = cleanTitle,
                            totalFileBytes = totalFileBytes,
                            clientHasRange = !clientRangeHeader.isNullOrBlank(),
                            requestedEndInclusive = requestedEndInclusive
                        )
                        if (handledByTranscoder.first) {
                            return
                        }
                        preReadBytes = handledByTranscoder.second ?: initialBytes
                    } else {
                        preReadBytes = initialBytes
                    }
                }

                // If we opened TorrServer open-ended (`bytes=0-`) for a Chromecast probe like `bytes=0-32767`
                // and it turned out to be raw passthrough, enforce the client's exact requested end byte!
                var passthroughMaxBytes = -1L
                var outContentLengthStr = contentLength
                if (openedOpenEndedAtZero && !clientRangeHeader.isNullOrBlank() && totalFileBytes > 0L) {
                    val safeEnd = if (requestedEndInclusive in 0L until totalFileBytes) {
                        requestedEndInclusive
                    } else {
                        totalFileBytes - 1L
                    }
                    passthroughMaxBytes = safeEnd + 1L
                    code = 206
                    contentRange = "bytes 0-$safeEnd/$totalFileBytes"
                    outContentLengthStr = passthroughMaxBytes.toString()
                } else if (openedOpenEndedAtZero && clientRangeHeader.isNullOrBlank() && totalFileBytes > 0L) {
                    passthroughMaxBytes = totalFileBytes
                    code = 200
                    contentRange = null
                    outContentLengthStr = totalFileBytes.toString()
                }

                val statusText = when (code) {
                    200 -> "OK"
                    206 -> "Partial Content"
                    416 -> "Requested Range Not Satisfiable"
                    else -> upstreamConn.responseMessage ?: "OK"
                }

                val isMkvContent = detectedMkvStream ||
                    torrStreamUrl.substringBefore("?").endsWith(".mkv", ignoreCase = true) ||
                    displayName.endsWith(".mkv", ignoreCase = true)
                val outContentType = if (!isChromecastRequest && isMkvContent) "video/x-matroska" else "video/mp4"
                val outExt = if (!isChromecastRequest && isMkvContent) "mkv" else "mp4"
                val outAcceptRanges = acceptRanges.takeIf { it.equals("bytes", ignoreCase = true) } ?: "bytes"

                val responseHeaders = buildString {
                    append("HTTP/1.1 $code $statusText\r\n")
                    append("Content-Type: $outContentType\r\n")
                    append("icy-name: $cleanTitle\r\n")
                    append("Content-Disposition: inline; filename=\"$cleanTitle.$outExt\"\r\n")
                    append("Accept-Ranges: $outAcceptRanges\r\n")
                    if (!outContentLengthStr.isNullOrBlank()) {
                        append("Content-Length: $outContentLengthStr\r\n")
                    }
                    if (!contentRange.isNullOrBlank() && code == 206) {
                        append("Content-Range: $contentRange\r\n")
                    }
                    append("Access-Control-Allow-Origin: *\r\n")
                    append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
                    append("Access-Control-Allow-Headers: Range, If-Range, Content-Type, Origin, Accept\r\n")
                    append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(responseHeaders.toByteArray(Charsets.US_ASCII))
                out.flush()

                if (method != "HEAD" && code in 200..299) {
                    var remainingToEmit = passthroughMaxBytes
                    if (preReadBytes != null && preReadBytes.isNotEmpty()) {
                        val toWrite = if (remainingToEmit >= 0L) {
                            min(preReadBytes.size.toLong(), remainingToEmit).toInt()
                        } else {
                            preReadBytes.size
                        }
                        if (toWrite > 0) {
                            out.write(preReadBytes, 0, toWrite)
                            out.flush()
                            if (remainingToEmit >= 0L) remainingToEmit -= toWrite
                        }
                    }
                    val shouldCaptureTailForCache = !isChromecastRequest &&
                        startByteOffset > 1_048_576L &&
                        totalFileBytes > startByteOffset &&
                        (totalFileBytes - startByteOffset) in 128L..2_097_152L &&
                        (endByteInclusive < 0L || endByteInclusive >= totalFileBytes - 1L) &&
                        !mkvCuesTailCache.containsKey(torrStreamUrl)
                    val tailCapture = if (shouldCaptureTailForCache) ByteArrayOutputStream(65536) else null
                    var inspectedFirstChunkHeader = false

                    val buf = ByteArray(32768)
                    var n = 0
                    while ((remainingToEmit < 0L || remainingToEmit > 0L) &&
                        bufferedUpstream.read(
                            buf,
                            0,
                            if (remainingToEmit >= 0L) min(buf.size.toLong(), remainingToEmit).toInt() else buf.size
                        ).also { n = it } != -1
                    ) {
                        try {
                            out.write(buf, 0, n)
                            out.flush()
                            if (remainingToEmit >= 0L) remainingToEmit -= n
                            if (!inspectedFirstChunkHeader && !isChromecastRequest && startByteOffset == 0L) {
                                inspectedFirstChunkHeader = true
                                if (n >= 64 && !mkvCuesTailCache.containsKey(torrStreamUrl)) {
                                    val cuesOff = extractMkvCuesOrChainedSeekHeadOffset(buf, n)
                                    if (cuesOff > 0L) {
                                        warmMkvCuesTailAsync(torrStreamUrl, cuesOff, totalFileBytes)
                                    }
                                }
                            }
                            if (tailCapture != null && tailCapture.size() + n <= 2_097_152) {
                                tailCapture.write(buf, 0, n)
                            }
                        } catch (_: Exception) {
                            break
                        }
                    }
                    try {
                        out.flush()
                    } catch (_: Exception) {
                    }
                    if (tailCapture != null) {
                        val expectedTailLen = (totalFileBytes - startByteOffset).toInt()
                        val capturedBytes = tailCapture.toByteArray()
                        if (capturedBytes.size >= expectedTailLen && expectedTailLen > 0) {
                            if (mkvCuesTailCache.size >= 8 && !mkvCuesTailCache.containsKey(torrStreamUrl)) {
                                mkvCuesTailCache.clear()
                            }
                            mkvCuesTailCache[torrStreamUrl] = Pair(startByteOffset, capturedBytes)
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    upstreamConn?.disconnect()
                } catch (_: Exception) {
                }
            }
        }

        // =========================================================================
        // REAL-TIME CHROMECAST MATROSKA (.MKV) AUDIO TRANSCODER & REMUXER
        // Converts Dolby Digital Plus (A_EAC3), Dolby Digital (A_AC3), DTS (A_DTS),
        // TrueHD (A_TRUEHD), and 5.1/7.1 Surround tracks into 2.0 Stereo AAC (A_AAC)
        // or 16-bit Linear PCM (A_PCM/INT/LIT) on-the-fly while passing H.264/HEVC
        // video packets through 100% untouched.
        // =========================================================================

        private data class MkvTrackInfo(
            val trackNumber: Int,
            val trackUid: Long,
            val trackType: Int, // 1 = Video, 2 = Audio, 17 = Subtitle
            val flagEnabled: Int,
            val flagDefault: Int,
            val codecId: String,
            val codecPrivate: ByteArray?,
            val language: String,
            val name: String,
            val sampleRate: Int,
            val outputSampleRate: Int,
            val channels: Int,
            val bitDepth: Int,
            val codecDelayNs: Long,
            val seekPreRollNs: Long,
            val sampleStrippedBytes: ByteArray?,
            val rawTrackEntryElement: ByteArray
        )

        /**
         * Pure read-only parser that inspects initial Matroska header bytes for `SeekHead` (`0x114D9B74`) ->
         * `Seek` (`0x4DBB`) entries pointing to `ID_CUES` (`0x1C53BB6B`) or a chained `ID_SEEK_HEAD` (`0x114D9B74`)
         * near EOF and returns the absolute byte offset in the file (never modifying any bytes).
         */
        private fun extractMkvCuesOrChainedSeekHeadOffset(buf: ByteArray, limit: Int = buf.size): Long {
            val endLimit = limit.coerceAtMost(buf.size)
            if (endLimit < 32) return -1L
            try {
                var pos = 0
                val rootId = readEbmlIdFromArray(buf, pos, endLimit) ?: return -1L
                if (rootId.first != 0x1A45DFA3L) return -1L
                pos += rootId.second
                val rootSz = readEbmlSizeFromArray(buf, pos, endLimit) ?: return -1L
                pos += rootSz.second
                if (rootSz.first < 0L || pos + rootSz.first > endLimit) return -1L
                pos += rootSz.first.toInt()

                val segId = readEbmlIdFromArray(buf, pos, endLimit) ?: return -1L
                if (segId.first != 0x18538067L) return -1L
                pos += segId.second
                val segSz = readEbmlSizeFromArray(buf, pos, endLimit) ?: return -1L
                pos += segSz.second
                val segmentContentPosition = pos.toLong()

                var foundCuesAbsolutePos = -1L
                var foundChainedSeekHeadPos = -1L

                while (pos < endLimit) {
                    val elId = readEbmlIdFromArray(buf, pos, endLimit) ?: break
                    pos += elId.second
                    val elSz = readEbmlSizeFromArray(buf, pos, endLimit) ?: break
                    pos += elSz.second
                    if (elId.first == 0x1F43B675L || elSz.first < 0L) break // Stop at Cluster
                    val elEnd = (pos + elSz.first).toInt().coerceAtMost(endLimit)
                    if (elId.first == 0x114D9B74L) { // SeekHead
                        var sPos = pos
                        while (sPos < elEnd) {
                            val subId = readEbmlIdFromArray(buf, sPos, elEnd) ?: break
                            sPos += subId.second
                            val subSz = readEbmlSizeFromArray(buf, sPos, elEnd) ?: break
                            sPos += subSz.second
                            if (subSz.first < 0L) break
                            val entryEnd = (sPos + subSz.first).toInt().coerceAtMost(elEnd)
                            if (subId.first == 0x4DBBL) { // Seek entry
                                var ePos = sPos
                                var seekIdVal = 0L
                                var seekPosition = -1L
                                while (ePos < entryEnd) {
                                    val fId = readEbmlIdFromArray(buf, ePos, entryEnd) ?: break
                                    ePos += fId.second
                                    val fSz = readEbmlSizeFromArray(buf, ePos, entryEnd) ?: break
                                    ePos += fSz.second
                                    val fLen = fSz.first.toInt()
                                    if (fLen < 0 || ePos + fLen > entryEnd) break
                                    if (fId.first == 0x53ABL && fLen in 1..4) {
                                        seekIdVal = readEbmlUint(buf, ePos, fLen)
                                    } else if (fId.first == 0x53ACL && fLen in 1..8) {
                                        seekPosition = readEbmlUint(buf, ePos, fLen)
                                    }
                                    ePos += fLen
                                }
                                if (seekPosition > 0L) {
                                    if (seekIdVal == 0x1C53BB6BL) {
                                        foundCuesAbsolutePos = segmentContentPosition + seekPosition
                                    } else if (seekIdVal == 0x114D9B74L && seekPosition > 1_048_576L) {
                                        foundChainedSeekHeadPos = (segmentContentPosition + seekPosition - 131_072L).coerceAtLeast(0L)
                                    }
                                }
                            }
                            sPos = entryEnd
                        }
                    }
                    pos = elEnd
                }
                return if (foundCuesAbsolutePos > 0L) foundCuesAbsolutePos else foundChainedSeekHeadPos
            } catch (_: Throwable) {
                return -1L
            }
        }

        private fun isChromecastSafeDirectAudio(track: MkvTrackInfo): Boolean {
            // Chromecast & Google TV natively decode and downmix AAC, Opus, Vorbis, MP3, MP2, PCM, and FLAC
            // (both 2.0 stereo AND 5.1/7.1 surround) in hardware! Allow direct byte-exact passthrough for all
            // native codecs so only unsupported codecs (AC3, EAC3, DTS, TrueHD, MLP) need live transcoding.
            val cid = track.codecId.uppercase(Locale.ROOT)
            return cid.startsWith("A_AAC") ||
                cid == "A_MPEG/L3" ||
                cid == "A_MPEG/L2" ||
                cid == "A_OPUS" ||
                cid == "A_VORBIS" ||
                cid == "A_PCM/INT/LIT" ||
                cid == "A_FLAC"
        }

        private class EbmlRecordingReader(
            private val prefix: ByteArray,
            private val upstream: InputStream
        ) {
            private var prefixPos = 0
            var recordPassthrough = true
            val passthroughBuffer = ByteArrayOutputStream(131072)
            var totalBytesRead: Long = 0L
                private set

            fun readByte(): Int {
                val v = if (prefixPos < prefix.size) {
                    prefix[prefixPos++].toInt() and 0xFF
                } else {
                    upstream.read()
                }
                if (v >= 0) {
                    totalBytesRead++
                    if (recordPassthrough) {
                        passthroughBuffer.write(v)
                    }
                }
                return v
            }

            fun readChunk(dst: ByteArray, maxLen: Int): Int {
                if (maxLen <= 0) return 0
                val fromPrefix = prefix.size - prefixPos
                if (fromPrefix > 0) {
                    val toCopy = min(maxLen, fromPrefix)
                    System.arraycopy(prefix, prefixPos, dst, 0, toCopy)
                    if (recordPassthrough) {
                        passthroughBuffer.write(prefix, prefixPos, toCopy)
                    }
                    prefixPos += toCopy
                    totalBytesRead += toCopy
                    return toCopy
                }
                val n = upstream.read(dst, 0, min(maxLen, dst.size))
                if (n > 0) {
                    totalBytesRead += n
                    if (recordPassthrough) {
                        passthroughBuffer.write(dst, 0, n)
                    }
                }
                return n
            }

            fun readExact(len: Int): ByteArray? {
                if (len <= 0) return ByteArray(0)
                val out = ByteArray(len)
                var off = 0
                while (off < len) {
                    val fromPrefix = prefix.size - prefixPos
                    if (fromPrefix > 0) {
                        val toCopy = min(len - off, fromPrefix)
                        System.arraycopy(prefix, prefixPos, out, off, toCopy)
                        if (recordPassthrough) {
                            passthroughBuffer.write(prefix, prefixPos, toCopy)
                        }
                        prefixPos += toCopy
                        totalBytesRead += toCopy
                        off += toCopy
                    } else {
                        val n = upstream.read(out, off, len - off)
                        if (n <= 0) return if (off == len) out else null
                        if (recordPassthrough) {
                            passthroughBuffer.write(out, off, n)
                        }
                        totalBytesRead += n
                        off += n
                    }
                }
                return out
            }

            fun skipExact(len: Long): Boolean {
                var remaining = len
                val scratch = ByteArray(16384)
                while (remaining > 0L) {
                    val step = min(remaining, scratch.size.toLong()).toInt()
                    val chunk = readExact(step) ?: return false
                    remaining -= chunk.size
                }
                return true
            }

            /**
             * Reads an EBML Element ID (keeping the VINT length marker bit) and returns Pair(id, bytesRead).
             */
            fun readElementId(): Pair<Long, Int>? {
                val p = readElementIdWithRaw() ?: return null
                return Pair(p.first, p.second.size)
            }

            fun readElementIdWithRaw(): Pair<Long, ByteArray>? {
                val b0 = readByte()
                if (b0 < 0) return null
                val len = when {
                    (b0 and 0x80) != 0 -> 1
                    (b0 and 0x40) != 0 -> 2
                    (b0 and 0x20) != 0 -> 3
                    (b0 and 0x10) != 0 -> 4
                    else -> return null
                }
                val raw = ByteArray(len)
                raw[0] = b0.toByte()
                var id = b0.toLong()
                for (i in 1 until len) {
                    val next = readByte()
                    if (next < 0) return null
                    raw[i] = next.toByte()
                    id = (id shl 8) or next.toLong()
                }
                return Pair(id, raw)
            }

            /**
             * Reads an EBML Element Size VINT (clearing the VINT marker bit).
             * Returns Pair(size, bytesRead), where `size = -1L` indicates indeterminate/unknown size.
             */
            fun readElementSize(): Pair<Long, Int>? {
                val p = readElementSizeWithRaw() ?: return null
                return Pair(p.first, p.second.size)
            }

            fun readElementSizeWithRaw(): Pair<Long, ByteArray>? {
                val b0 = readByte()
                if (b0 < 0) return null
                var len = 0
                var mask = 0x80
                for (i in 1..8) {
                    if ((b0 and mask) != 0) {
                        len = i
                        break
                    }
                    mask = mask shr 1
                }
                if (len == 0) return null
                val raw = ByteArray(len)
                raw[0] = b0.toByte()
                var value = (b0 and (mask - 1)).toLong()
                var allOnes = (value == (mask - 1).toLong())
                for (i in 1 until len) {
                    val next = readByte()
                    if (next < 0) return null
                    raw[i] = next.toByte()
                    if (next != 0xFF) allOnes = false
                    value = (value shl 8) or next.toLong()
                }
                return Pair(if (allOnes) -1L else value, raw)
            }

            fun drainRemainingPrefixToPassthrough(): ByteArray {
                if (prefixPos < prefix.size && recordPassthrough) {
                    passthroughBuffer.write(prefix, prefixPos, prefix.size - prefixPos)
                    prefixPos = prefix.size
                }
                return passthroughBuffer.toByteArray()
            }
        }

        private fun readEbmlIdFromArray(b: ByteArray, startPos: Int, limit: Int): Pair<Long, Int>? {
            if (startPos >= limit) return null
            val b0 = b[startPos].toInt() and 0xFF
            val len = when {
                (b0 and 0x80) != 0 -> 1
                (b0 and 0x40) != 0 -> 2
                (b0 and 0x20) != 0 -> 3
                (b0 and 0x10) != 0 -> 4
                else -> return null
            }
            if (startPos + len > limit) return null
            var id = b0.toLong()
            for (i in 1 until len) {
                id = (id shl 8) or (b[startPos + i].toLong() and 0xFFL)
            }
            return Pair(id, len)
        }

        private fun readEbmlSizeFromArray(b: ByteArray, startPos: Int, limit: Int): Pair<Long, Int>? {
            if (startPos >= limit) return null
            val b0 = b[startPos].toInt() and 0xFF
            var len = 0
            var mask = 0x80
            for (i in 1..8) {
                if ((b0 and mask) != 0) {
                    len = i
                    break
                }
                mask = mask shr 1
            }
            if (len == 0 || startPos + len > limit) return null
            var value = (b0 and (mask - 1)).toLong()
            var allOnes = (value == (mask - 1).toLong())
            for (i in 1 until len) {
                val next = b[startPos + i].toInt() and 0xFF
                if (next != 0xFF) allOnes = false
                value = (value shl 8) or next.toLong()
            }
            return Pair(if (allOnes) -1L else value, len)
        }

        private fun readEbmlUint(b: ByteArray, offset: Int, len: Int): Long {
            var v = 0L
            for (i in 0 until len) {
                v = (v shl 8) or (b[offset + i].toLong() and 0xFFL)
            }
            return v
        }

        private fun readEbmlFloat(b: ByteArray, offset: Int, len: Int): Double {
            return when (len) {
                4 -> Float.fromBits(readEbmlUint(b, offset, 4).toInt()).toDouble()
                8 -> Double.fromBits(readEbmlUint(b, offset, 8))
                else -> 0.0
            }
        }

        private fun writeEbmlId(out: OutputStream, id: Long) {
            when {
                id <= 0xFFL -> out.write(id.toInt())
                id <= 0xFFFFL -> {
                    out.write(((id shr 8) and 0xFFL).toInt())
                    out.write((id and 0xFFL).toInt())
                }
                id <= 0xFFFFFFL -> {
                    out.write(((id shr 16) and 0xFFL).toInt())
                    out.write(((id shr 8) and 0xFFL).toInt())
                    out.write((id and 0xFFL).toInt())
                }
                else -> {
                    out.write(((id shr 24) and 0xFFL).toInt())
                    out.write(((id shr 16) and 0xFFL).toInt())
                    out.write(((id shr 8) and 0xFFL).toInt())
                    out.write((id and 0xFFL).toInt())
                }
            }
        }

        private fun writeEbmlSize(out: OutputStream, size: Long) {
            when {
                size < 0L -> {
                    // 8-byte unknown size (0x01FFFFFFFFFFFFFF)
                    out.write(0x01)
                    for (i in 0 until 7) out.write(0xFF)
                }
                size < 126L -> {
                    out.write((0x80L or size).toInt())
                }
                size < 16382L -> {
                    val v = 0x4000L or size
                    out.write(((v shr 8) and 0xFFL).toInt())
                    out.write((v and 0xFFL).toInt())
                }
                size < 2097150L -> {
                    val v = 0x200000L or size
                    out.write(((v shr 16) and 0xFFL).toInt())
                    out.write(((v shr 8) and 0xFFL).toInt())
                    out.write((v and 0xFFL).toInt())
                }
                size < 268435454L -> {
                    val v = 0x10000000L or size
                    out.write(((v shr 24) and 0xFFL).toInt())
                    out.write(((v shr 16) and 0xFFL).toInt())
                    out.write(((v shr 8) and 0xFFL).toInt())
                    out.write((v and 0xFFL).toInt())
                }
                else -> {
                    out.write(0x01)
                    for (shift in 48 downTo 0 step 8) {
                        out.write(((size shr shift) and 0xFFL).toInt())
                    }
                }
            }
        }

        private fun minEbmlSizeVintLen(size: Long): Int {
            return when {
                size <= 126L -> 1
                size <= 16382L -> 2
                size <= 2097150L -> 3
                size <= 268435454L -> 4
                size <= 34359738366L -> 5
                size <= 4398046511102L -> 6
                size <= 562949953421310L -> 7
                else -> 8
            }
        }

        private fun writeEbmlSizeWithLength(out: OutputStream, size: Long, vintLen: Int) {
            val len = vintLen.coerceIn(1, 8)
            val marker = 1L shl (7 * len)
            val encoded = marker or size
            for (shift in (len - 1) * 8 downTo 0 step 8) {
                out.write(((encoded shr shift) and 0xFFL).toInt())
            }
        }

        private fun writeEbmlVoidElement(out: OutputStream, totalVoidBytes: Int) {
            if (totalVoidBytes < 2) return
            val (vintLen, payloadLen) = when {
                totalVoidBytes <= 128 -> 1 to (totalVoidBytes - 2)
                totalVoidBytes <= 16385 -> 2 to (totalVoidBytes - 3)
                totalVoidBytes <= 2097154 -> 3 to (totalVoidBytes - 4)
                totalVoidBytes <= 268435459 -> 4 to (totalVoidBytes - 5)
                else -> 5 to (totalVoidBytes - 6)
            }
            out.write(0xEC) // EBML Void Element ID
            writeEbmlSizeWithLength(out, payloadLen.toLong(), vintLen)
            var remaining = payloadLen
            val zeros = ByteArray(min(remaining, 8192))
            while (remaining > 0) {
                val step = min(remaining, zeros.size)
                out.write(zeros, 0, step)
                remaining -= step
            }
        }

        private fun writeEbmlElement(out: OutputStream, id: Long, payload: ByteArray) {
            writeEbmlId(out, id)
            writeEbmlSize(out, payload.size.toLong())
            out.write(payload)
        }

        private fun writeEbmlUintElement(out: OutputStream, id: Long, value: Long) {
            val tmp = ByteArrayOutputStream(8)
            var started = false
            for (shift in 56 downTo 8 step 8) {
                val b = ((value shr shift) and 0xFFL).toInt()
                if (b != 0 || started) {
                    tmp.write(b)
                    started = true
                }
            }
            tmp.write((value and 0xFFL).toInt())
            writeEbmlElement(out, id, tmp.toByteArray())
        }

        private fun writeEbmlFloat32Element(out: OutputStream, id: Long, value: Float) {
            val bits = java.lang.Float.floatToRawIntBits(value)
            val b = byteArrayOf(
                ((bits shr 24) and 0xFF).toByte(),
                ((bits shr 16) and 0xFF).toByte(),
                ((bits shr 8) and 0xFF).toByte(),
                (bits and 0xFF).toByte()
            )
            writeEbmlElement(out, id, b)
        }

        private fun writeEbmlFloat64Element(out: OutputStream, id: Long, value: Double) {
            val bits = java.lang.Double.doubleToRawLongBits(value)
            val b = ByteArray(8)
            for (i in 0 until 8) {
                b[i] = ((bits shr ((7 - i) * 8)) and 0xFFL).toByte()
            }
            writeEbmlElement(out, id, b)
        }

        private fun parseTimecodeScaleFromInfo(infoBody: ByteArray): Long {
            var pos = 0
            while (pos < infoBody.size) {
                val idPair = readEbmlIdFromArray(infoBody, pos, infoBody.size) ?: break
                pos += idPair.second
                val szPair = readEbmlSizeFromArray(infoBody, pos, infoBody.size) ?: break
                pos += szPair.second
                val len = szPair.first.toInt()
                if (len < 0 || pos + len > infoBody.size) break
                if (idPair.first == 0x2AD7B1L) {
                    return readEbmlUint(infoBody, pos, len).takeIf { it > 0L } ?: 1_000_000L
                }
                pos += len
            }
            return 1_000_000L
        }

        private fun parseDurationMsFromInfo(infoBody: ByteArray?, timecodeScaleNs: Long): Double {
            if (infoBody == null || infoBody.isEmpty()) return 0.0
            var pos = 0
            while (pos < infoBody.size) {
                val idPair = readEbmlIdFromArray(infoBody, pos, infoBody.size) ?: break
                pos += idPair.second
                val szPair = readEbmlSizeFromArray(infoBody, pos, infoBody.size) ?: break
                pos += szPair.second
                val len = szPair.first.toInt()
                if (len < 0 || pos + len > infoBody.size) break
                if (idPair.first == 0x4489L && (len == 4 || len == 8)) {
                    val rawDur = readEbmlFloat(infoBody, pos, len)
                    if (rawDur > 0.0) {
                        return (rawDur * timecodeScaleNs.toDouble()) / 1_000_000.0
                    }
                }
                pos += len
            }
            return 0.0
        }

        private fun parseMkvTracks(tracksBody: ByteArray): List<MkvTrackInfo> {
            val result = mutableListOf<MkvTrackInfo>()
            var pos = 0
            while (pos < tracksBody.size) {
                val entryStart = pos
                val idPair = readEbmlIdFromArray(tracksBody, pos, tracksBody.size) ?: break
                pos += idPair.second
                val szPair = readEbmlSizeFromArray(tracksBody, pos, tracksBody.size) ?: break
                pos += szPair.second
                val entryLen = szPair.first.toInt()
                if (entryLen < 0 || pos + entryLen > tracksBody.size) break
                val entryEnd = pos + entryLen

                if (idPair.first == 0xAEL) {
                    var trackNum = 0
                    var trackUid = 0L
                    var trackType = 0
                    var flagEnabled = 1
                    var flagDefault = 1
                    var codecId = ""
                    var codecPrivate: ByteArray? = null
                    var language = "und"
                    var name = ""
                    var sampleRate = 48000
                    var outputSampleRate = 0
                    var channels = 2
                    var bitDepth = 16
                    var codecDelayNs = 0L
                    var seekPreRollNs = 0L
                    var strippedBytes: ByteArray? = null

                    var p = pos
                    while (p < entryEnd) {
                        val cId = readEbmlIdFromArray(tracksBody, p, entryEnd) ?: break
                        p += cId.second
                        val cSz = readEbmlSizeFromArray(tracksBody, p, entryEnd) ?: break
                        p += cSz.second
                        val cLen = cSz.first.toInt()
                        if (cLen < 0 || p + cLen > entryEnd) break

                        when (cId.first) {
                            0xD7L -> trackNum = readEbmlUint(tracksBody, p, cLen).toInt()
                            0x73C5L -> trackUid = readEbmlUint(tracksBody, p, cLen)
                            0x83L -> trackType = readEbmlUint(tracksBody, p, cLen).toInt()
                            0xB9L -> flagEnabled = readEbmlUint(tracksBody, p, cLen).toInt()
                            0x88L -> flagDefault = readEbmlUint(tracksBody, p, cLen).toInt()
                            0x86L -> codecId = String(tracksBody, p, cLen, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
                            0x63A2L -> codecPrivate = tracksBody.copyOfRange(p, p + cLen)
                            0x22B59CL, 0x22B59DL -> language = String(tracksBody, p, cLen, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
                            0x536EL -> name = String(tracksBody, p, cLen, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
                            0x56AAL -> codecDelayNs = readEbmlUint(tracksBody, p, cLen)
                            0x56BBL -> seekPreRollNs = readEbmlUint(tracksBody, p, cLen)
                            0xE1L -> {
                                var ap = p
                                val aEnd = p + cLen
                                while (ap < aEnd) {
                                    val aId = readEbmlIdFromArray(tracksBody, ap, aEnd) ?: break
                                    ap += aId.second
                                    val aSz = readEbmlSizeFromArray(tracksBody, ap, aEnd) ?: break
                                    ap += aSz.second
                                    val aLen = aSz.first.toInt()
                                    if (aLen < 0 || ap + aLen > aEnd) break
                                    when (aId.first) {
                                        0xB5L -> sampleRate = readEbmlFloat(tracksBody, ap, aLen).toInt().takeIf { it > 0 } ?: 48000
                                        0x78B5L -> outputSampleRate = readEbmlFloat(tracksBody, ap, aLen).toInt()
                                        0x9FL -> channels = readEbmlUint(tracksBody, ap, aLen).toInt().takeIf { it > 0 } ?: 2
                                        0x6264L -> bitDepth = readEbmlUint(tracksBody, ap, aLen).toInt().takeIf { it > 0 } ?: 16
                                    }
                                    ap += aLen
                                }
                            }
                            0x6D80L -> {
                                // ContentEncodings -> ContentEncoding (0x6240) -> ContentCompression (0x5034)
                                var cep = p
                                val ceEnd = p + cLen
                                while (cep < ceEnd) {
                                    val ceId = readEbmlIdFromArray(tracksBody, cep, ceEnd) ?: break
                                    cep += ceId.second
                                    val ceSz = readEbmlSizeFromArray(tracksBody, cep, ceEnd) ?: break
                                    cep += ceSz.second
                                    val ceLen = ceSz.first.toInt()
                                    if (ceLen < 0 || cep + ceLen > ceEnd) break
                                    if (ceId.first == 0x6240L) {
                                        var encP = cep
                                        val encEnd = cep + ceLen
                                        while (encP < encEnd) {
                                            val subId = readEbmlIdFromArray(tracksBody, encP, encEnd) ?: break
                                            encP += subId.second
                                            val subSz = readEbmlSizeFromArray(tracksBody, encP, encEnd) ?: break
                                            encP += subSz.second
                                            val subLen = subSz.first.toInt()
                                            if (subLen < 0 || encP + subLen > encEnd) break
                                            if (subId.first == 0x5034L) {
                                                var compAlgo = 0
                                                var compSettings: ByteArray? = null
                                                var cp = encP
                                                val cEnd = encP + subLen
                                                while (cp < cEnd) {
                                                    val kId = readEbmlIdFromArray(tracksBody, cp, cEnd) ?: break
                                                    cp += kId.second
                                                    val kSz = readEbmlSizeFromArray(tracksBody, cp, cEnd) ?: break
                                                    cp += kSz.second
                                                    val kLen = kSz.first.toInt()
                                                    if (kLen < 0 || cp + kLen > cEnd) break
                                                    when (kId.first) {
                                                        0x4254L -> compAlgo = readEbmlUint(tracksBody, cp, kLen).toInt()
                                                        0x4255L -> compSettings = tracksBody.copyOfRange(cp, cp + kLen)
                                                    }
                                                    cp += kLen
                                                }
                                                if (compAlgo == 3 && compSettings != null) {
                                                    strippedBytes = compSettings
                                                }
                                            }
                                            encP += subLen
                                        }
                                    }
                                    cep += ceLen
                                }
                            }
                        }
                        p += cLen
                    }

                    if (trackNum > 0) {
                        result.add(
                            MkvTrackInfo(
                                trackNumber = trackNum,
                                trackUid = if (trackUid != 0L) trackUid else trackNum.toLong(),
                                trackType = trackType,
                                flagEnabled = flagEnabled,
                                flagDefault = flagDefault,
                                codecId = codecId,
                                codecPrivate = codecPrivate,
                                language = language,
                                name = name,
                                sampleRate = sampleRate,
                                outputSampleRate = outputSampleRate,
                                channels = channels,
                                bitDepth = bitDepth,
                                codecDelayNs = codecDelayNs,
                                seekPreRollNs = seekPreRollNs,
                                sampleStrippedBytes = strippedBytes,
                                rawTrackEntryElement = tracksBody.copyOfRange(entryStart, entryEnd)
                            )
                        )
                    }
                }
                pos = entryEnd
            }
            return result
        }

        private fun buildAacLcAudioSpecificConfig(sampleRate: Int, channels: Int = 2): ByteArray {
            val freqIndex = when (sampleRate) {
                96000 -> 0
                88200 -> 1
                64000 -> 2
                48000 -> 3
                44100 -> 4
                32000 -> 5
                24000 -> 6
                22050 -> 7
                16000 -> 8
                12000 -> 9
                11025 -> 10
                8000 -> 11
                7350 -> 12
                else -> 3
            }
            val objectType = 2 // AAC-LC
            val b0 = ((objectType shl 3) or (freqIndex shr 1)).toByte()
            val b1 = (((freqIndex and 1) shl 7) or ((channels and 0x0F) shl 3)).toByte()
            return byteArrayOf(b0, b1)
        }

        private fun buildTranscodedAudioTrackEntry(
            sourceTrack: MkvTrackInfo,
            outSampleRate: Int,
            useAacCodec: Boolean,
            compact: Boolean = false
        ): ByteArray {
            val entryBody = ByteArrayOutputStream(256)
            writeEbmlUintElement(entryBody, 0xD7L, sourceTrack.trackNumber.toLong())
            writeEbmlUintElement(entryBody, 0x73C5L, sourceTrack.trackUid)
            writeEbmlUintElement(entryBody, 0x83L, 2L) // TrackType = 2 (Audio)
            if (!compact) {
                writeEbmlUintElement(entryBody, 0xB9L, 1L) // FlagEnabled = 1
                writeEbmlUintElement(entryBody, 0x88L, 1L) // FlagDefault = 1
                writeEbmlUintElement(entryBody, 0x55AAL, 0L) // FlagForced = 0
                writeEbmlUintElement(entryBody, 0x9CL, 0L) // FlagLacing = 0
                val langStr = sourceTrack.language.ifBlank { "und" }
                writeEbmlElement(entryBody, 0x22B59CL, langStr.toByteArray(Charsets.US_ASCII))
                if (sourceTrack.name.isNotBlank()) {
                    writeEbmlElement(entryBody, 0x536EL, sourceTrack.name.toByteArray(Charsets.UTF_8))
                }
            }

            if (useAacCodec) {
                writeEbmlElement(entryBody, 0x86L, "A_AAC".toByteArray(Charsets.US_ASCII))
                writeEbmlElement(entryBody, 0x63A2L, buildAacLcAudioSpecificConfig(outSampleRate, 2))
            } else {
                writeEbmlElement(entryBody, 0x86L, "A_PCM/INT/LIT".toByteArray(Charsets.US_ASCII))
            }

            val audioSub = ByteArrayOutputStream(32)
            if (compact) {
                writeEbmlFloat32Element(audioSub, 0xB5L, outSampleRate.toFloat())
                writeEbmlUintElement(audioSub, 0x9FL, 2L) // 2.0 Stereo
            } else {
                writeEbmlFloat64Element(audioSub, 0xB5L, outSampleRate.toDouble())
                writeEbmlUintElement(audioSub, 0x9FL, 2L) // 2.0 Stereo
                writeEbmlUintElement(audioSub, 0x6264L, 16L) // 16-bit
            }
            writeEbmlElement(entryBody, 0xE1L, audioSub.toByteArray())

            val fullEntry = ByteArrayOutputStream(entryBody.size() + 8)
            writeEbmlElement(fullEntry, 0xAEL, entryBody.toByteArray())
            return fullEntry.toByteArray()
        }

        /**
         * Decodes surround audio frames (`A_EAC3`, `A_AC3`, `A_DTS`, `A_TRUEHD`, `A_FLAC`, `A_OPUS`, etc.)
         * using CloudStream's NextLib FFmpeg decoder (`FfmpegAudioDecoder`) with fallback to Android
         * `MediaCodec`, downmixes 5.1/7.1 surround to 2.0 Stereo with Center Dialog Boost (`0.85 * FC`),
         * and encodes to `A_AAC` (or `A_PCM/INT/LIT` fallback).
         */
        private class ChromecastAudioTranscoder(
            private val track: MkvTrackInfo
        ) {
            val outSampleRate: Int = (track.outputSampleRate.takeIf { it >= 8000 } ?: track.sampleRate.takeIf { it >= 8000 } ?: 48000)

            // NextLib FFmpeg reflection handles
            private var ffmpegDecoder: Any? = null
            private var ffmpegInBuf: Any? = null
            private var ffmpegOutBuf: Any? = null
            private var ffmpegDecodeMethod: java.lang.reflect.Method? = null
            private var ffmpegDequeueInMethod: java.lang.reflect.Method? = null
            private var ffmpegQueueInMethod: java.lang.reflect.Method? = null
            private var ffmpegDequeueOutMethod: java.lang.reflect.Method? = null
            private var ffmpegReleaseOutMethod: java.lang.reflect.Method? = null
            private var ffmpegGetChannelsMethod: java.lang.reflect.Method? = null
            private var ffmpegGetSampleRateMethod: java.lang.reflect.Method? = null
            private var ffmpegGetEncodingMethod: java.lang.reflect.Method? = null
            private var ffmpegReleaseMethod: java.lang.reflect.Method? = null
            private var inClearMethod: java.lang.reflect.Method? = null
            private var inEnsureSpaceMethod: java.lang.reflect.Method? = null
            private var inDataField: java.lang.reflect.Field? = null
            private var inTimeUsField: java.lang.reflect.Field? = null
            private var inSetFlagsMethod: java.lang.reflect.Method? = null
            private var outClearMethod: java.lang.reflect.Method? = null
            private var outDataField: java.lang.reflect.Field? = null
            private var outShouldSkipField: java.lang.reflect.Field? = null

            // Fallback Android MediaCodec decoder
            private var platformDecoder: MediaCodec? = null
            private var platformDecoderChannels: Int = track.channels.coerceAtLeast(1)

            // Android MediaCodec AAC-LC encoder
            private var aacEncoder: MediaCodec? = null
            var useAacOutput: Boolean = false
                private set

            // Stereo PCM accumulator for 1024-sample (4096-byte) AAC encoder frames
            private val pcmRingBuffer = ByteArrayOutputStream(16384)
            private var nextPcmPtsUs: Long = -1L

            init {
                initDecoder()
                initAacEncoder()
            }

            private fun findMethod(cls: Class<*>, name: String, paramCount: Int): java.lang.reflect.Method? {
                var cur: Class<*>? = cls
                while (cur != null) {
                    val m = cur.declaredMethods.firstOrNull { it.name == name && it.parameterCount == paramCount }
                    if (m != null) {
                        m.isAccessible = true
                        return m
                    }
                    cur = cur.superclass
                }
                return null
            }

            private fun findField(cls: Class<*>, name: String): java.lang.reflect.Field? {
                var cur: Class<*>? = cls
                while (cur != null) {
                    val f = cur.declaredFields.firstOrNull { it.name == name }
                    if (f != null) {
                        f.isAccessible = true
                        return f
                    }
                    cur = cur.superclass
                }
                return null
            }

            private fun resolveMimeType(): String {
                val cid = track.codecId.uppercase(Locale.ROOT)
                return when {
                    cid == "A_EAC3" -> "audio/eac3"
                    cid == "A_AC3" -> "audio/ac3"
                    cid == "A_DTS" -> "audio/vnd.dts"
                    cid == "A_TRUEHD" -> "audio/true-hd"
                    cid == "A_MLP" -> "audio/mlp"
                    cid == "A_FLAC" -> "audio/flac"
                    cid == "A_OPUS" -> "audio/opus"
                    cid == "A_VORBIS" -> "audio/vorbis"
                    cid.startsWith("A_AAC") -> "audio/mp4a-latm"
                    cid == "A_MPEG/L3" -> "audio/mpeg"
                    cid == "A_MPEG/L2" -> "audio/mpeg-L2"
                    else -> "audio/eac3"
                }
            }

            private fun initDecoder() {
                val mimeType = resolveMimeType()
                // 1. Try CloudStream's bundled NextLib FfmpegAudioDecoder first
                try {
                    try {
                        val libCls = Class.forName("io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary")
                        libCls.methods.firstOrNull { it.name == "isAvailable" && it.parameterCount == 0 }?.invoke(null)
                    } catch (_: Throwable) {
                    }

                    val formatBuilderCls = Class.forName("androidx.media3.common.Format\$Builder")
                    val fb = formatBuilderCls.getDeclaredConstructor().newInstance()
                    formatBuilderCls.getMethod("setSampleMimeType", String::class.java).invoke(fb, mimeType)
                    formatBuilderCls.getMethod("setChannelCount", Int::class.javaPrimitiveType).invoke(fb, track.channels.coerceAtLeast(1))
                    formatBuilderCls.getMethod("setSampleRate", Int::class.javaPrimitiveType).invoke(fb, outSampleRate)
                    formatBuilderCls.getMethod("setMaxInputSize", Int::class.javaPrimitiveType).invoke(fb, 65536)

                    val cp = track.codecPrivate
                    if (cp != null && cp.isNotEmpty()) {
                        val initList = if (mimeType == "audio/opus") {
                            val dBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(track.codecDelayNs).array()
                            val pBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(track.seekPreRollNs).array()
                            listOf(cp, dBuf, pBuf)
                        } else {
                            listOf(cp)
                        }
                        formatBuilderCls.getMethod("setInitializationData", List::class.java).invoke(fb, initList)
                    }
                    val formatObj = formatBuilderCls.getMethod("build").invoke(fb)

                    val decCls = Class.forName("io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioDecoder")
                    var instance: Any? = null
                    for (ctor in decCls.declaredConstructors) {
                        ctor.isAccessible = true
                        val pTypes = ctor.parameterTypes
                        val args = arrayOfNulls<Any>(pTypes.size)
                        var intIdx = 0
                        var ok = true
                        for (i in pTypes.indices) {
                            val pt = pTypes[i]
                            when {
                                pt.name == "androidx.media3.common.Format" -> args[i] = formatObj
                                pt == Boolean::class.javaPrimitiveType || pt == Boolean::class.java -> args[i] = false // 16-bit PCM
                                pt == Int::class.javaPrimitiveType || pt == Int::class.java -> {
                                    args[i] = when (intIdx++) {
                                        0 -> 16
                                        1 -> 16
                                        else -> 65536
                                    }
                                }
                                else -> ok = false
                            }
                        }
                        if (ok) {
                            instance = ctor.newInstance(*args)
                            break
                        }
                    }

                    if (instance != null) {
                        ffmpegDecoder = instance
                        ffmpegGetChannelsMethod = findMethod(decCls, "getChannelCount", 0)
                        ffmpegGetSampleRateMethod = findMethod(decCls, "getSampleRate", 0)
                        ffmpegGetEncodingMethod = findMethod(decCls, "getEncoding", 0)
                        ffmpegReleaseMethod = findMethod(decCls, "release", 0)

                        val createIn = findMethod(decCls, "createInputBuffer", 0)
                        val createOut = findMethod(decCls, "createOutputBuffer", 0)
                        val syncDecode = decCls.declaredMethods.firstOrNull {
                            it.name == "decode" && it.parameterCount == 3 && it.parameterTypes[2] == Boolean::class.javaPrimitiveType
                        }?.apply { isAccessible = true }

                        if (createIn != null && createOut != null && syncDecode != null) {
                            val inB = createIn.invoke(instance)
                            val outB = createOut.invoke(instance)
                            if (inB != null && outB != null) {
                                ffmpegInBuf = inB
                                ffmpegOutBuf = outB
                                ffmpegDecodeMethod = syncDecode
                                inClearMethod = findMethod(inB.javaClass, "clear", 0)
                                inEnsureSpaceMethod = findMethod(inB.javaClass, "ensureSpaceForWrite", 1)
                                inDataField = findField(inB.javaClass, "data")
                                inTimeUsField = findField(inB.javaClass, "timeUs")
                                outClearMethod = findMethod(outB.javaClass, "clear", 0)
                                outDataField = findField(outB.javaClass, "data")
                                outShouldSkipField = findField(outB.javaClass, "shouldBeSkipped")
                            }
                        }

                        ffmpegDequeueInMethod = findMethod(decCls, "dequeueInputBuffer", 0)
                        ffmpegQueueInMethod = findMethod(decCls, "queueInputBuffer", 1)
                        ffmpegDequeueOutMethod = findMethod(decCls, "dequeueOutputBuffer", 0)
                        return
                    }
                } catch (_: Throwable) {
                }

                // 2. Fallback to Android platform MediaCodec decoder
                try {
                    val mc = MediaCodec.createDecoderByType(mimeType)
                    val mf = MediaFormat.createAudioFormat(mimeType, outSampleRate, track.channels.coerceAtLeast(1))
                    mf.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
                    if (android.os.Build.VERSION.SDK_INT >= 24) {
                        mf.setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                    }
                    track.codecPrivate?.takeIf { it.isNotEmpty() }?.let {
                        mf.setByteBuffer("csd-0", ByteBuffer.wrap(it))
                    }
                    mc.configure(mf, null, null, 0)
                    mc.start()
                    platformDecoder = mc
                } catch (_: Throwable) {
                }
            }

            private fun initAacEncoder() {
                try {
                    val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                    val mf = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, outSampleRate, 2)
                    mf.setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                    mf.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    mf.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                    enc.configure(mf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    enc.start()
                    aacEncoder = enc
                    useAacOutput = true
                } catch (_: Throwable) {
                    useAacOutput = false
                }
            }

            fun transcodeFrame(
                frameBytes: ByteArray,
                ptsUs: Long,
                onEncodedFrame: (ByteArray, Long) -> Unit
            ) {
                val dec = ffmpegDecoder
                val syncDecode = ffmpegDecodeMethod
                val inB = ffmpegInBuf
                val outB = ffmpegOutBuf

                if (dec != null) {
                    if (syncDecode != null && inB != null && outB != null) {
                        try {
                            inClearMethod?.invoke(inB)
                            inEnsureSpaceMethod?.invoke(inB, frameBytes.size)
                            val byteBuf = inDataField?.get(inB) as? ByteBuffer ?: return
                            byteBuf.put(frameBytes)
                            byteBuf.flip()
                            inTimeUsField?.setLong(inB, ptsUs)
                            outClearMethod?.invoke(outB)

                            val err = syncDecode.invoke(dec, inB, outB, false)
                            val skipped = (outShouldSkipField?.getBoolean(outB) == true)
                            if (err == null && !skipped) {
                                val pcmBuf = outDataField?.get(outB) as? ByteBuffer
                                if (pcmBuf != null && pcmBuf.remaining() > 0) {
                                    val ch = (ffmpegGetChannelsMethod?.invoke(dec) as? Int)?.takeIf { it > 0 } ?: track.channels
                                    val encType = (ffmpegGetEncodingMethod?.invoke(dec) as? Int) ?: 2
                                    handleDecodedPcm(pcmBuf, ch, encType, ptsUs, onEncodedFrame)
                                }
                            }
                        } catch (_: Throwable) {
                        }
                        return
                    }

                    if (ffmpegDequeueInMethod != null && ffmpegQueueInMethod != null && ffmpegDequeueOutMethod != null) {
                        try {
                            val qIn = ffmpegDequeueInMethod?.invoke(dec)
                            if (qIn != null) {
                                if (inClearMethod == null) {
                                    inClearMethod = findMethod(qIn.javaClass, "clear", 0)
                                    inEnsureSpaceMethod = findMethod(qIn.javaClass, "ensureSpaceForWrite", 1)
                                    inDataField = findField(qIn.javaClass, "data")
                                    inTimeUsField = findField(qIn.javaClass, "timeUs")
                                    inSetFlagsMethod = findMethod(qIn.javaClass, "setFlags", 1)
                                }
                                inClearMethod?.invoke(qIn)
                                inEnsureSpaceMethod?.invoke(qIn, frameBytes.size)
                                val byteBuf = inDataField?.get(qIn) as? ByteBuffer
                                if (byteBuf != null) {
                                    byteBuf.put(frameBytes)
                                    byteBuf.flip()
                                    inTimeUsField?.setLong(qIn, ptsUs)
                                    inSetFlagsMethod?.invoke(qIn, 1)
                                    ffmpegQueueInMethod?.invoke(dec, qIn)
                                }
                            }
                            while (true) {
                                val qOut = ffmpegDequeueOutMethod?.invoke(dec) ?: break
                                if (outDataField == null) {
                                    outDataField = findField(qOut.javaClass, "data")
                                    ffmpegReleaseOutMethod = findMethod(qOut.javaClass, "release", 0)
                                }
                                try {
                                    val pcmBuf = outDataField?.get(qOut) as? ByteBuffer
                                    if (pcmBuf != null && pcmBuf.remaining() > 0) {
                                        val ch = (ffmpegGetChannelsMethod?.invoke(dec) as? Int)?.takeIf { it > 0 } ?: track.channels
                                        val encType = (ffmpegGetEncodingMethod?.invoke(dec) as? Int) ?: 2
                                        handleDecodedPcm(pcmBuf, ch, encType, ptsUs, onEncodedFrame)
                                    }
                                } finally {
                                    ffmpegReleaseOutMethod?.invoke(qOut)
                                }
                            }
                        } catch (_: Throwable) {
                        }
                        return
                    }
                }

                // Fallback: Android platform MediaCodec decoder
                val mc = platformDecoder ?: return
                try {
                    val info = MediaCodec.BufferInfo()
                    fun drainPlatformDecoder() {
                        while (true) {
                            val outIdx = mc.dequeueOutputBuffer(info, 0L)
                            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                val of = mc.outputFormat
                                if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                    platformDecoderChannels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                                }
                            } else if (outIdx >= 0) {
                                try {
                                    val outBuf = mc.getOutputBuffer(outIdx)
                                    if (outBuf != null && info.size > 0) {
                                        outBuf.position(info.offset)
                                        outBuf.limit(info.offset + info.size)
                                        handleDecodedPcm(outBuf, platformDecoderChannels, 2, info.presentationTimeUs, onEncodedFrame)
                                    }
                                } finally {
                                    mc.releaseOutputBuffer(outIdx, false)
                                }
                            } else {
                                break
                            }
                        }
                    }

                    drainPlatformDecoder()
                    val inIdx = mc.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = mc.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            inBuf.clear()
                            val toPut = min(inBuf.remaining(), frameBytes.size)
                            inBuf.put(frameBytes, 0, toPut)
                            mc.queueInputBuffer(inIdx, 0, toPut, ptsUs, 0)
                        }
                    }
                    drainPlatformDecoder()
                } catch (_: Throwable) {
                }
            }

            private fun handleDecodedPcm(
                pcmBuf: ByteBuffer,
                channels: Int,
                encoding: Int,
                ptsUs: Long,
                onEncodedFrame: (ByteArray, Long) -> Unit
            ) {
                val stereoBytes = downmixToStereo16BitLe(pcmBuf, channels.coerceAtLeast(1), encoding)
                if (stereoBytes.isEmpty()) return

                if (!useAacOutput || aacEncoder == null) {
                    onEncodedFrame(stereoBytes, ptsUs)
                    return
                }

                // CRITICAL: Keep `nextPcmPtsUs` strictly monotonically increasing!
                // Android's `c2.android.aac.encoder` (`C2SoftAacEnc.cpp`) halts with `C2_BAD_VALUE`
                // if `inputPts < mLastInputPts` (e.g. when integer truncation drifts by 500ms around 0:39
                // or when laced audio blocks share a base timestamp).
                val safePtsUs = ptsUs.coerceAtLeast(0L)
                if (nextPcmPtsUs < 0L) {
                    nextPcmPtsUs = safePtsUs
                } else if (safePtsUs > nextPcmPtsUs + 1_500_000L) {
                    nextPcmPtsUs = safePtsUs
                }

                pcmRingBuffer.write(stereoBytes)
                val allPcm = pcmRingBuffer.toByteArray()
                pcmRingBuffer.reset()

                val frameBytesSize = 4096 // 1024 stereo samples * 2 channels * 2 bytes
                var offset = 0
                val enc = aacEncoder ?: return
                val bufInfo = MediaCodec.BufferInfo()

                fun drainEncoderOutput() {
                    while (true) {
                        val outIdx = enc.dequeueOutputBuffer(bufInfo, 0L)
                        if (outIdx >= 0) {
                            try {
                                val isConfig = (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                if (!isConfig && bufInfo.size > 0) {
                                    val outBuf = enc.getOutputBuffer(outIdx)
                                    if (outBuf != null) {
                                        outBuf.position(bufInfo.offset)
                                        outBuf.limit(bufInfo.offset + bufInfo.size)
                                        val aacPacket = ByteArray(bufInfo.size)
                                        outBuf.get(aacPacket)
                                        onEncodedFrame(aacPacket, bufInfo.presentationTimeUs.coerceAtLeast(0L))
                                    }
                                }
                            } finally {
                                enc.releaseOutputBuffer(outIdx, false)
                            }
                        } else {
                            break
                        }
                    }
                }

                drainEncoderOutput()
                val stepUs = max(1L, (1_000_000L * 1024L + (outSampleRate / 2L)) / outSampleRate)
                while (offset + frameBytesSize <= allPcm.size) {
                    val inIdx = enc.dequeueInputBuffer(5_000L)
                    if (inIdx >= 0) {
                        val inBuf = enc.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            inBuf.clear()
                            inBuf.put(allPcm, offset, frameBytesSize)
                            enc.queueInputBuffer(inIdx, 0, frameBytesSize, nextPcmPtsUs, 0)
                        }
                        nextPcmPtsUs += stepUs
                        offset += frameBytesSize
                        drainEncoderOutput()
                    } else {
                        drainEncoderOutput()
                        break
                    }
                }

                if (offset < allPcm.size) {
                    pcmRingBuffer.write(allPcm, offset, allPcm.size - offset)
                }
            }

            /**
             * Downmixes 1..8 channel 16-bit int (`encoding = 2`) or 32-bit float (`encoding = 4`) PCM
             * into 2-channel 16-bit Little-Endian Stereo PCM with ITU-R BS.775 Center Dialog Boost (`0.85 * FC`).
             */
            private fun downmixToStereo16BitLe(pcmBuf: ByteBuffer, channels: Int, encoding: Int): ByteArray {
                pcmBuf.order(ByteOrder.LITTLE_ENDIAN)
                val basePos = pcmBuf.position()
                val rem = pcmBuf.remaining()
                val bytesPerSample = if (encoding == 4) 4 else 2
                val frameStride = channels * bytesPerSample
                if (frameStride <= 0 || rem < frameStride) return ByteArray(0)
                val numFrames = rem / frameStride

                if (channels == 2 && encoding != 4) {
                    val direct = ByteArray(numFrames * 4)
                    pcmBuf.get(direct)
                    return direct
                }

                val out = ByteArray(numFrames * 4)
                fun sampleAt(frameIdx: Int, ch: Int): Float {
                    val idx = basePos + frameIdx * frameStride + ch * bytesPerSample
                    return if (encoding == 4) {
                        pcmBuf.getFloat(idx) * 32767f
                    } else {
                        pcmBuf.getShort(idx).toFloat()
                    }
                }

                for (i in 0 until numFrames) {
                    val leftVal: Float
                    val rightVal: Float
                    when {
                        channels == 1 -> {
                            val m = sampleAt(i, 0)
                            leftVal = m
                            rightVal = m
                        }
                        channels == 2 -> {
                            leftVal = sampleAt(i, 0)
                            rightVal = sampleAt(i, 1)
                        }
                        channels == 6 -> {
                            val fl = sampleAt(i, 0)
                            val fr = sampleAt(i, 1)
                            val fc = sampleAt(i, 2)
                            val lfe = sampleAt(i, 3)
                            val bl = sampleAt(i, 4)
                            val br = sampleAt(i, 5)
                            leftVal = (fl + 0.85f * fc + 0.707f * bl + 0.20f * lfe) * 0.72f
                            rightVal = (fr + 0.85f * fc + 0.707f * br + 0.20f * lfe) * 0.72f
                        }
                        channels >= 8 -> {
                            val fl = sampleAt(i, 0)
                            val fr = sampleAt(i, 1)
                            val fc = sampleAt(i, 2)
                            val lfe = sampleAt(i, 3)
                            val bl = sampleAt(i, 4)
                            val br = sampleAt(i, 5)
                            val sl = sampleAt(i, 6)
                            val sr = sampleAt(i, 7)
                            leftVal = (fl + 0.85f * fc + 0.55f * bl + 0.55f * sl + 0.20f * lfe) * 0.68f
                            rightVal = (fr + 0.85f * fc + 0.55f * br + 0.55f * sr + 0.20f * lfe) * 0.68f
                        }
                        else -> {
                            val fl = sampleAt(i, 0)
                            val fr = sampleAt(i, 1)
                            val fc = if (channels >= 3) sampleAt(i, 2) else 0f
                            leftVal = (fl + 0.75f * fc) * 0.75f
                            rightVal = (fr + 0.75f * fc) * 0.75f
                        }
                    }

                    val lShort = leftVal.toInt().coerceIn(-32768, 32767)
                    val rShort = rightVal.toInt().coerceIn(-32768, 32767)
                    val outIdx = i * 4
                    out[outIdx] = (lShort and 0xFF).toByte()
                    out[outIdx + 1] = ((lShort shr 8) and 0xFF).toByte()
                    out[outIdx + 2] = (rShort and 0xFF).toByte()
                    out[outIdx + 3] = ((rShort shr 8) and 0xFF).toByte()
                }
                return out
            }

            fun release() {
                try {
                    ffmpegReleaseMethod?.invoke(ffmpegDecoder)
                } catch (_: Throwable) {
                }
                try {
                    platformDecoder?.stop()
                    platformDecoder?.release()
                } catch (_: Throwable) {
                }
                try {
                    aacEncoder?.stop()
                    aacEncoder?.release()
                } catch (_: Throwable) {
                }
            }
        }

        private data class ParsedBlockHeader(
            val trackNumber: Int,
            val relTimecode: Int,
            val flags: Int,
            val headerLen: Int
        )

        private fun parseMatroskaBlockHeader(b: ByteArray): ParsedBlockHeader? {
            if (b.size < 4) return null
            val b0 = b[0].toInt() and 0xFF
            val (vintLen, mask) = when {
                (b0 and 0x80) != 0 -> 1 to 0x7F
                (b0 and 0x40) != 0 -> 2 to 0x3F
                (b0 and 0x20) != 0 -> 3 to 0x1F
                (b0 and 0x10) != 0 -> 4 to 0x0F
                else -> return null
            }
            if (b.size < vintLen + 3) return null
            var trackNum = b0 and mask
            for (i in 1 until vintLen) {
                trackNum = (trackNum shl 8) or (b[i].toInt() and 0xFF)
            }
            val relTc = (((b[vintLen].toInt() and 0xFF) shl 8) or (b[vintLen + 1].toInt() and 0xFF)).toShort().toInt()
            val flags = b[vintLen + 2].toInt() and 0xFF
            return ParsedBlockHeader(trackNum, relTc, flags, vintLen + 3)
        }

        private fun unlaceMatroskaBlockFrames(
            b: ByteArray,
            header: ParsedBlockHeader,
            strippedPrefix: ByteArray?
        ): List<ByteArray> {
            val lacingType = (header.flags shr 1) and 0x03
            var pos = header.headerLen
            if (pos >= b.size) return emptyList()

            fun wrapFrame(start: Int, len: Int): ByteArray {
                val safeLen = len.coerceIn(0, b.size - start)
                if (strippedPrefix == null || strippedPrefix.isEmpty()) {
                    return b.copyOfRange(start, start + safeLen)
                }
                val combined = ByteArray(strippedPrefix.size + safeLen)
                System.arraycopy(strippedPrefix, 0, combined, 0, strippedPrefix.size)
                System.arraycopy(b, start, combined, strippedPrefix.size, safeLen)
                return combined
            }

            if (lacingType == 0) {
                return listOf(wrapFrame(pos, b.size - pos))
            }

            val frameCount = (b[pos++].toInt() and 0xFF) + 1
            if (frameCount <= 0 || pos > b.size) return emptyList()
            val sizes = IntArray(frameCount)

            when (lacingType) {
                1 -> {
                    // Xiph lacing
                    var sum = 0
                    for (i in 0 until frameCount - 1) {
                        var sz = 0
                        while (pos < b.size) {
                            val v = b[pos++].toInt() and 0xFF
                            sz += v
                            if (v < 255) break
                        }
                        sizes[i] = sz
                        sum += sz
                    }
                    sizes[frameCount - 1] = (b.size - pos - sum).coerceAtLeast(0)
                }
                2 -> {
                    // Fixed-size lacing
                    val rem = (b.size - pos).coerceAtLeast(0)
                    val each = rem / frameCount
                    for (i in 0 until frameCount - 1) sizes[i] = each
                    sizes[frameCount - 1] = (rem - each * (frameCount - 1)).coerceAtLeast(0)
                }
                3 -> {
                    // EBML lacing
                    val firstVint = readEbmlSizeFromArray(b, pos, b.size) ?: return emptyList()
                    pos += firstVint.second
                    sizes[0] = firstVint.first.toInt().coerceAtLeast(0)
                    var sum = sizes[0]
                    for (i in 1 until frameCount - 1) {
                        val nextVint = readEbmlSizeFromArray(b, pos, b.size) ?: break
                        val vintBytes = nextVint.second
                        pos += vintBytes
                        val bias = (1L shl (7 * vintBytes - 1)) - 1L
                        val delta = (nextVint.first - bias).toInt()
                        sizes[i] = (sizes[i - 1] + delta).coerceAtLeast(0)
                        sum += sizes[i]
                    }
                    sizes[frameCount - 1] = (b.size - pos - sum).coerceAtLeast(0)
                }
            }

            val frames = ArrayList<ByteArray>(frameCount)
            for (sz in sizes) {
                if (pos + sz > b.size) break
                if (sz > 0) {
                    frames.add(wrapFrame(pos, sz))
                }
                pos += sz
            }
            return frames
        }

        private fun writeSimpleBlock(
            out: OutputStream,
            trackNumber: Int,
            relTimecode: Short,
            frameBytes: ByteArray
        ) {
            val vintBytes = if (trackNumber < 127) {
                byteArrayOf((0x80 or trackNumber).toByte())
            } else {
                byteArrayOf((0x40 or ((trackNumber shr 8) and 0x3F)).toByte(), (trackNumber and 0xFF).toByte())
            }
            val payloadSize = vintBytes.size + 3 + frameBytes.size
            writeEbmlId(out, 0xA3L)
            writeEbmlSize(out, payloadSize.toLong())
            out.write(vintBytes)
            out.write((relTimecode.toInt() shr 8) and 0xFF)
            out.write(relTimecode.toInt() and 0xFF)
            out.write(0x80) // Keyframe, no lacing
            out.write(frameBytes)
        }

        private fun writeCastHttpResponseHeaders(
            out: OutputStream,
            streamTitle: String,
            clientHasRange: Boolean,
            startOffset: Long,
            endInclusive: Long,
            totalFileBytes: Long
        ) {
            val contentLen = (endInclusive - startOffset + 1L).coerceAtLeast(0L)
            val statusLine = if (clientHasRange) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n"
            val headers = buildString {
                append(statusLine)
                append("Content-Type: video/mp4\r\n")
                append("icy-name: $streamTitle\r\n")
                append("Content-Disposition: inline; filename=\"$streamTitle.mp4\"\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: $contentLen\r\n")
                if (clientHasRange) {
                    append("Content-Range: bytes $startOffset-$endInclusive/$totalFileBytes\r\n")
                }
                append("Access-Control-Allow-Origin: *\r\n")
                append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
                append("Access-Control-Allow-Headers: Range, If-Range, Content-Type, Origin, Accept\r\n")
                append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(headers.toByteArray(Charsets.US_ASCII))
            out.flush()
        }

        private fun buildByteExactPatchedTracksReplacement(
            origTracksTotalBytes: Int,
            videoTracks: List<MkvTrackInfo>,
            chosenAudio: MkvTrackInfo,
            transcoder: ChromecastAudioTranscoder?
        ): ByteArray? {
            fun tryAssemble(compact: Boolean): ByteArray? {
                val bodyBuf = ByteArrayOutputStream(4096)
                for (vt in videoTracks) {
                    bodyBuf.write(vt.rawTrackEntryElement)
                }
                if (transcoder != null) {
                    bodyBuf.write(
                        buildTranscodedAudioTrackEntry(
                            sourceTrack = chosenAudio,
                            outSampleRate = transcoder.outSampleRate,
                            useAacCodec = transcoder.useAacOutput,
                            compact = compact
                        )
                    )
                } else {
                    bodyBuf.write(chosenAudio.rawTrackEntryElement)
                }
                val newBody = bodyBuf.toByteArray()
                val minVintLen = minEbmlSizeVintLen(newBody.size.toLong())
                val gap = origTracksTotalBytes - (4 + minVintLen + newBody.size)
                if (gap < 0) return null
                if (gap == 1 && minVintLen >= 8) return null

                val out = ByteArrayOutputStream(origTracksTotalBytes)
                writeEbmlId(out, 0x1654AE6BL)
                if (gap == 1) {
                    writeEbmlSizeWithLength(out, newBody.size.toLong(), minVintLen + 1)
                    out.write(newBody)
                } else {
                    writeEbmlSizeWithLength(out, newBody.size.toLong(), minVintLen)
                    out.write(newBody)
                    if (gap >= 2) {
                        writeEbmlVoidElement(out, gap)
                    }
                }
                val result = out.toByteArray()
                return if (result.size == origTracksTotalBytes) result else null
            }

            return tryAssemble(compact = false) ?: tryAssemble(compact = true)
        }

        /**
         * Scans an unaligned mid-Cluster byte stream for the next valid Matroska `Cluster` (`0x1F43B675`)
         * followed by a valid size VINT and valid Cluster child element ID (`0xE7`/`0xA7`/`0xAB`/`0xA3`/`0xA0`).
         * Returns `Triple(relativeOffsetToCluster, clusterBytesFromStartOfCluster, rawScannedPrefixIfNotFound)`.
         */
        private fun scanForNextMatroskaClusterWithOffset(
            initialPeek: ByteArray,
            upstream: InputStream,
            maxScanBytes: Long
        ): Triple<Long, ByteArray?, ByteArray> {
            val scanLimit = min(maxScanBytes, 4_194_304L).toInt().coerceAtLeast(initialPeek.size)
            val savedForRawFallback = ByteArrayOutputStream(min(scanLimit, 262_144))
            val window = ByteArray(65536)
            var filled = 0
            var totalRead = 0L
            var windowStartOffset = 0L

            fun appendToWindow(src: ByteArray, off: Int, len: Int): Pair<Long, ByteArray>? {
                if (savedForRawFallback.size() < 262_144) {
                    val toSave = min(len, 262_144 - savedForRawFallback.size())
                    if (toSave > 0) savedForRawFallback.write(src, off, toSave)
                }
                var consumed = 0
                while (consumed < len) {
                    val space = window.size - filled
                    val step = min(space, len - consumed)
                    System.arraycopy(src, off + consumed, window, filled, step)
                    filled += step
                    consumed += step
                    totalRead += step

                    var i = 0
                    val searchLimit = filled - 16
                    while (i <= searchLimit) {
                        if (window[i] == 0x1F.toByte() &&
                            window[i + 1] == 0x43.toByte() &&
                            window[i + 2] == 0xB6.toByte() &&
                            window[i + 3] == 0x75.toByte()
                        ) {
                            val szPair = readEbmlSizeFromArray(window, i + 4, filled)
                            if (szPair != null && szPair.first >= 0L) {
                                val afterHdr = i + 4 + szPair.second
                                if (afterHdr < filled) {
                                    val nextByte = window[afterHdr].toInt() and 0xFF
                                    if (nextByte == 0xE7 || nextByte == 0xA7 || nextByte == 0xAB || nextByte == 0xA3 || nextByte == 0xA0) {
                                        val exactRelOffset = windowStartOffset + i.toLong()
                                        val tailBuf = ByteArrayOutputStream((filled - i) + (len - consumed))
                                        tailBuf.write(window, i, filled - i)
                                        if (consumed < len) {
                                            tailBuf.write(src, off + consumed, len - consumed)
                                        }
                                        return Pair(exactRelOffset, tailBuf.toByteArray())
                                    }
                                }
                            }
                        }
                        i++
                    }

                    if (filled >= window.size) {
                        val keep = 16
                        val drop = filled - keep
                        System.arraycopy(window, drop, window, 0, keep)
                        windowStartOffset += drop.toLong()
                        filled = keep
                    }
                }
                return null
            }

            if (initialPeek.isNotEmpty()) {
                val found = appendToWindow(initialPeek, 0, initialPeek.size)
                if (found != null) return Triple(found.first, found.second, ByteArray(0))
            }

            val chunk = ByteArray(16384)
            while (totalRead < scanLimit.toLong()) {
                val toRead = min(chunk.size.toLong(), scanLimit.toLong() - totalRead).toInt()
                val n = upstream.read(chunk, 0, toRead)
                if (n <= 0) break
                val found = appendToWindow(chunk, 0, n)
                if (found != null) return Triple(found.first, found.second, ByteArray(0))
            }
            return Triple(-1L, null, savedForRawFallback.toByteArray())
        }

        /**
         * Streams Matroska Clusters while preserving the EXACT original file byte offset and byte length of
         * every single Cluster (using EBML `Void` padding when the 128 kbps AAC Cluster is smaller than the
         * original 5.1 AC3/EAC3/DTS/TrueHD Cluster). Any post-Cluster EOF elements (`Cues`, `Tags`, `Chapters`)
         * are passed through verbatim so `Content-Length` matches to the exact byte.
         */
        private fun streamByteExactClustersLoop(
            reader: EbmlRecordingReader,
            out: OutputStream,
            ctx: ByteExactMkvContext,
            transcoder: ChromecastAudioTranscoder?,
            initialClusterSize: Long?,
            initialClusterSizeRaw: ByteArray?,
            startClusterFileOffset: Long,
            initialRemainingToSend: Long
        ) {
            var remainingToSend = initialRemainingToSend
            if (remainingToSend <= 0L) return

            val videoTrackNumbers = ctx.videoTracks.map { it.trackNumber }.toHashSet()
            val audioTrackNumber = ctx.chosenAudio.trackNumber

            fun writeLimited(data: ByteArray, offset: Int = 0, len: Int = data.size): Boolean {
                if (remainingToSend <= 0L || len <= 0) return remainingToSend > 0L
                val toWrite = min(len.toLong(), remainingToSend).toInt()
                out.write(data, offset, toWrite)
                remainingToSend -= toWrite.toLong()
                return remainingToSend > 0L
            }

            fun processSingleByteExactCluster(clusterFileOffset: Long, origSizeVintLen: Int, origBodySize: Long): Boolean {
                if (origBodySize < 0L || origBodySize > 67_108_864L) return false
                val origBodyInt = origBodySize.toInt()
                val origTotalBytes = 4 + origSizeVintLen + origBodyInt

                val clusterElements = ArrayList<Pair<Boolean, ByteArray>>(256)
                var totalBodySize = 0
                var bytesReadInCluster = 0L
                var sourceClusterTc = 0L

                while (bytesReadInCluster < origBodySize) {
                    val cId = reader.readElementIdWithRaw() ?: return false
                    val cSz = reader.readElementSizeWithRaw() ?: return false
                    bytesReadInCluster += cId.second.size + cSz.second.size

                    val payloadLen = cSz.first
                    if (payloadLen < 0L || bytesReadInCluster + payloadLen > origBodySize) return false
                    bytesReadInCluster += payloadLen

                    when (cId.first) {
                        0xE7L -> {
                            // Cluster Timecode
                            val tcBytes = reader.readExact(payloadLen.toInt()) ?: return false
                            sourceClusterTc = readEbmlUint(tcBytes, 0, tcBytes.size)
                            val elem = ByteArray(cId.second.size + cSz.second.size + tcBytes.size)
                            System.arraycopy(cId.second, 0, elem, 0, cId.second.size)
                            System.arraycopy(cSz.second, 0, elem, cId.second.size, cSz.second.size)
                            System.arraycopy(tcBytes, 0, elem, cId.second.size + cSz.second.size, tcBytes.size)
                            clusterElements.add(Pair(false, elem))
                            totalBodySize += elem.size
                        }
                        0xA3L -> {
                            // SimpleBlock
                            val blockBytes = reader.readExact(payloadLen.toInt()) ?: return false
                            val hdr = parseMatroskaBlockHeader(blockBytes)
                            if (hdr != null) {
                                if (videoTrackNumbers.contains(hdr.trackNumber)) {
                                    // Preserve original Video SimpleBlock 100% verbatim!
                                    val elem = ByteArray(cId.second.size + cSz.second.size + blockBytes.size)
                                    System.arraycopy(cId.second, 0, elem, 0, cId.second.size)
                                    System.arraycopy(cSz.second, 0, elem, cId.second.size, cSz.second.size)
                                    System.arraycopy(blockBytes, 0, elem, cId.second.size + cSz.second.size, blockBytes.size)
                                    clusterElements.add(Pair(false, elem))
                                    totalBodySize += elem.size
                                } else if (hdr.trackNumber == audioTrackNumber) {
                                    if (transcoder == null) {
                                        val elem = ByteArray(cId.second.size + cSz.second.size + blockBytes.size)
                                        System.arraycopy(cId.second, 0, elem, 0, cId.second.size)
                                        System.arraycopy(cSz.second, 0, elem, cId.second.size, cSz.second.size)
                                        System.arraycopy(blockBytes, 0, elem, cId.second.size + cSz.second.size, blockBytes.size)
                                        clusterElements.add(Pair(true, elem))
                                        totalBodySize += elem.size
                                    } else {
                                        val blockTimecode = (sourceClusterTc + hdr.relTimecode).coerceAtLeast(0L)
                                        val frames = unlaceMatroskaBlockFrames(blockBytes, hdr, ctx.chosenAudio.sampleStrippedBytes)
                                        val basePtsUs = (blockTimecode * ctx.timecodeScaleNs) / 1000L
                                        for ((fIdx, f) in frames.withIndex()) {
                                            val framePtsUs = basePtsUs + (fIdx * 21_333L)
                                            transcoder.transcodeFrame(f, framePtsUs) { encodedAudio, outPtsUs ->
                                                val outBlockTc = (outPtsUs * 1000L) / ctx.timecodeScaleNs
                                                val rel = (outBlockTc - sourceClusterTc).coerceIn(0L, 32767L).toShort()
                                                val sbBuf = ByteArrayOutputStream(encodedAudio.size + 16)
                                                writeSimpleBlock(sbBuf, audioTrackNumber, rel, encodedAudio)
                                                val sbBytes = sbBuf.toByteArray()
                                                clusterElements.add(Pair(true, sbBytes))
                                                totalBodySize += sbBytes.size
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        0xA0L -> {
                            // BlockGroup
                            val groupBytes = reader.readExact(payloadLen.toInt()) ?: return false
                            var gp = 0
                            var innerBlock: ByteArray? = null
                            while (gp < groupBytes.size) {
                                val gId = readEbmlIdFromArray(groupBytes, gp, groupBytes.size) ?: break
                                gp += gId.second
                                val gSz = readEbmlSizeFromArray(groupBytes, gp, groupBytes.size) ?: break
                                gp += gSz.second
                                val gLen = gSz.first.toInt()
                                if (gLen < 0 || gp + gLen > groupBytes.size) break
                                if (gId.first == 0xA1L) {
                                    innerBlock = groupBytes.copyOfRange(gp, gp + gLen)
                                    break
                                }
                                gp += gLen
                            }
                            val hdr = innerBlock?.let { parseMatroskaBlockHeader(it) }
                            if (hdr != null && innerBlock != null) {
                                if (videoTrackNumbers.contains(hdr.trackNumber)) {
                                    val elem = ByteArray(cId.second.size + cSz.second.size + groupBytes.size)
                                    System.arraycopy(cId.second, 0, elem, 0, cId.second.size)
                                    System.arraycopy(cSz.second, 0, elem, cId.second.size, cSz.second.size)
                                    System.arraycopy(groupBytes, 0, elem, cId.second.size + cSz.second.size, groupBytes.size)
                                    clusterElements.add(Pair(false, elem))
                                    totalBodySize += elem.size
                                } else if (hdr.trackNumber == audioTrackNumber) {
                                    if (transcoder == null) {
                                        val elem = ByteArray(cId.second.size + cSz.second.size + groupBytes.size)
                                        System.arraycopy(cId.second, 0, elem, 0, cId.second.size)
                                        System.arraycopy(cSz.second, 0, elem, cId.second.size, cSz.second.size)
                                        System.arraycopy(groupBytes, 0, elem, cId.second.size + cSz.second.size, groupBytes.size)
                                        clusterElements.add(Pair(true, elem))
                                        totalBodySize += elem.size
                                    } else {
                                        val blockTimecode = (sourceClusterTc + hdr.relTimecode).coerceAtLeast(0L)
                                        val frames = unlaceMatroskaBlockFrames(innerBlock, hdr, ctx.chosenAudio.sampleStrippedBytes)
                                        val basePtsUs = (blockTimecode * ctx.timecodeScaleNs) / 1000L
                                        for ((fIdx, f) in frames.withIndex()) {
                                            val framePtsUs = basePtsUs + (fIdx * 21_333L)
                                            transcoder.transcodeFrame(f, framePtsUs) { encodedAudio, outPtsUs ->
                                                val outBlockTc = (outPtsUs * 1000L) / ctx.timecodeScaleNs
                                                val rel = (outBlockTc - sourceClusterTc).coerceIn(0L, 32767L).toShort()
                                                val sbBuf = ByteArrayOutputStream(encodedAudio.size + 16)
                                                writeSimpleBlock(sbBuf, audioTrackNumber, rel, encodedAudio)
                                                val sbBytes = sbBuf.toByteArray()
                                                clusterElements.add(Pair(true, sbBytes))
                                                totalBodySize += sbBytes.size
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        else -> {
                            if (!reader.skipExact(payloadLen)) return false
                        }
                    }
                }

                // Size invariant safety guard: guarantee `totalBodySize <= origBodyInt` so the emitted Cluster
                // plus optional Void padding ALWAYS equals `origTotalBytes` to the exact byte!
                while (totalBodySize > origBodyInt) {
                    val lastAudioIdx = clusterElements.indexOfLast { it.first }
                    if (lastAudioIdx < 0) break
                    totalBodySize -= clusterElements.removeAt(lastAudioIdx).second.size
                }

                val minVintLen = minEbmlSizeVintLen(totalBodySize.toLong())
                val gap = origTotalBytes - (4 + minVintLen + totalBodySize)
                val finalBuf = ByteArrayOutputStream(origTotalBytes)
                writeEbmlId(finalBuf, 0x1F43B675L)
                if (gap == 1 && minVintLen < 8) {
                    writeEbmlSizeWithLength(finalBuf, totalBodySize.toLong(), minVintLen + 1)
                    for (entry in clusterElements) {
                        finalBuf.write(entry.second)
                    }
                } else {
                    writeEbmlSizeWithLength(finalBuf, totalBodySize.toLong(), minVintLen)
                    for (entry in clusterElements) {
                        finalBuf.write(entry.second)
                    }
                    if (gap >= 2) {
                        writeEbmlVoidElement(finalBuf, gap)
                    }
                }

                val finalClusterBytes = finalBuf.toByteArray()
                ctx.putCluster(clusterFileOffset, finalClusterBytes)
                val keepGoing = writeLimited(finalClusterBytes)
                out.flush()
                return keepGoing
            }

            var currentOffset = startClusterFileOffset
            if (initialClusterSize != null && initialClusterSizeRaw != null) {
                if (!processSingleByteExactCluster(currentOffset, initialClusterSizeRaw.size, initialClusterSize)) {
                    return
                }
                currentOffset += 4L + initialClusterSizeRaw.size.toLong() + initialClusterSize
            }

            val passBuf = ByteArray(16384)
            while (remainingToSend > 0L) {
                val nextId = reader.readElementIdWithRaw() ?: break
                val nextSz = reader.readElementSizeWithRaw() ?: break
                if (nextId.first == 0x1F43B675L && nextSz.first >= 0L) {
                    if (!processSingleByteExactCluster(currentOffset, nextSz.second.size, nextSz.first)) {
                        break
                    }
                    currentOffset += 4L + nextSz.second.size.toLong() + nextSz.first
                } else {
                    // Non-Cluster Segment element (e.g. EOF Cues, Tags, Chapters, SeekHead, Void):
                    // pass through 100% verbatim so exact file layout and Content-Length are preserved!
                    if (!writeLimited(nextId.second)) break
                    if (!writeLimited(nextSz.second)) break
                    currentOffset += nextId.second.size.toLong() + nextSz.second.size.toLong()
                    var remPayload = nextSz.first
                    if (remPayload < 0L) {
                        while (remainingToSend > 0L) {
                            val step = min(passBuf.size.toLong(), remainingToSend).toInt()
                            val n = reader.readChunk(passBuf, step)
                            if (n <= 0) break
                            if (!writeLimited(passBuf, 0, n)) break
                            out.flush()
                        }
                        break
                    } else {
                        while (remPayload > 0L && remainingToSend > 0L) {
                            val step = min(min(remPayload, passBuf.size.toLong()), remainingToSend).toInt()
                            val n = reader.readChunk(passBuf, step)
                            if (n <= 0) break
                            remPayload -= n.toLong()
                            currentOffset += n.toLong()
                            if (!writeLimited(passBuf, 0, n)) break
                        }
                        out.flush()
                    }
                }
            }
        }

        /**
         * Handles ANY follow-up or ranged HTTP request on a Byte-Exact Matroska stream:
         * 1. Header slice (`start < firstClusterOffset`): served straight from `ctx.patchedHeaderBytes` in RAM!
         * 2. Mid-Cluster pause/resume (`ctx.findClusterContaining(start)`): served from the 4 MB recent-Cluster
         *    ring cache, then seamlessly resumes transcoding at the next exact Cluster boundary!
         * 3. Exact `Cues` timeline seek (`0x1F43B675` Cluster at `start`): transcodes byte-exact Clusters from `start`!
         * 4. EOF `Cues`/`Tags`/`SeekHead`/`Chapters`/`Void` fetch: passes raw TorrServer bytes straight through!
         * 5. Unaligned mid-Cluster binary-search seek (MKVs without `Cues`): scans to next Cluster, emits exact
         *    EBML `Void` alignment padding, and transcodes byte-exact Clusters from there!
         */
        private fun serveByteExactMatroskaFromContext(
            ctx: ByteExactMkvContext,
            out: OutputStream,
            streamTitle: String,
            clientHasRange: Boolean,
            requestedStartOffset: Long,
            requestedEndInclusive: Long,
            openUpstreamAtOffset: (Long) -> BufferedInputStream?
        ) {
            val safeTotal = ctx.totalFileBytes
            val safeStart = requestedStartOffset.coerceAtLeast(0L)
            if (safeStart >= safeTotal) {
                val r416 = buildString {
                    append("HTTP/1.1 416 Requested Range Not Satisfiable\r\n")
                    append("Content-Range: bytes */$safeTotal\r\n")
                    append("Content-Length: 0\r\n")
                    append("Access-Control-Allow-Origin: *\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(r416.toByteArray(Charsets.US_ASCII))
                out.flush()
                return
            }
            val safeEnd = if (requestedEndInclusive in safeStart until safeTotal) {
                requestedEndInclusive
            } else {
                safeTotal - 1L
            }
            var remainingToSend = safeEnd - safeStart + 1L

            // Case 1: Starts inside the patched EBML/Segment/SeekHead/Info/Tracks header
            if (safeStart < ctx.firstClusterOffset) {
                writeCastHttpResponseHeaders(out, streamTitle, clientHasRange, safeStart, safeEnd, safeTotal)
                val headerSliceLen = min(ctx.firstClusterOffset - safeStart, remainingToSend).toInt()
                if (headerSliceLen > 0) {
                    out.write(ctx.patchedHeaderBytes, safeStart.toInt(), headerSliceLen)
                    out.flush()
                    remainingToSend -= headerSliceLen.toLong()
                }
                if (remainingToSend <= 0L) return

                val upstream = openUpstreamAtOffset(ctx.firstClusterOffset) ?: return
                val reader = EbmlRecordingReader(ByteArray(0), upstream).apply { recordPassthrough = false }
                val needsAudioDecode = !isChromecastSafeDirectAudio(ctx.chosenAudio)
                val transcoder = if (needsAudioDecode) ChromecastAudioTranscoder(ctx.chosenAudio) else null
                try {
                    streamByteExactClustersLoop(
                        reader = reader,
                        out = out,
                        ctx = ctx,
                        transcoder = transcoder,
                        initialClusterSize = null,
                        initialClusterSizeRaw = null,
                        startClusterFileOffset = ctx.firstClusterOffset,
                        initialRemainingToSend = remainingToSend
                    )
                } catch (_: Throwable) {
                } finally {
                    transcoder?.release()
                }
                return
            }

            // Case 2: Cache hit inside a recently emitted Cluster (e.g. Chromium pause/resume or buffer refill)
            val cachedCluster = ctx.findClusterContaining(safeStart)
            if (cachedCluster != null) {
                writeCastHttpResponseHeaders(out, streamTitle, clientHasRange, safeStart, safeEnd, safeTotal)
                val (clusterStartOff, clusterBytes) = cachedCluster
                val relOff = (safeStart - clusterStartOff).toInt()
                val fromCache = min((clusterBytes.size - relOff).toLong(), remainingToSend).toInt()
                if (fromCache > 0) {
                    out.write(clusterBytes, relOff, fromCache)
                    out.flush()
                    remainingToSend -= fromCache.toLong()
                }
                if (remainingToSend <= 0L) return

                val nextClusterOffset = clusterStartOff + clusterBytes.size.toLong()
                val upstream = openUpstreamAtOffset(nextClusterOffset) ?: return
                val reader = EbmlRecordingReader(ByteArray(0), upstream).apply { recordPassthrough = false }
                val needsAudioDecode = !isChromecastSafeDirectAudio(ctx.chosenAudio)
                val transcoder = if (needsAudioDecode) ChromecastAudioTranscoder(ctx.chosenAudio) else null
                try {
                    streamByteExactClustersLoop(
                        reader = reader,
                        out = out,
                        ctx = ctx,
                        transcoder = transcoder,
                        initialClusterSize = null,
                        initialClusterSizeRaw = null,
                        startClusterFileOffset = nextClusterOffset,
                        initialRemainingToSend = remainingToSend
                    )
                } catch (_: Throwable) {
                } finally {
                    transcoder?.release()
                }
                return
            }

            // Case 3: Cache miss at `safeStart >= firstClusterOffset` (Cues seek, EOF Cues read, or binary-search seek)
            val upstream = openUpstreamAtOffset(safeStart) ?: return
            val peekLen = min(4L, remainingToSend).toInt()
            val peek = ByteArray(peekLen)
            var peekRead = 0
            while (peekRead < peekLen) {
                val r = upstream.read(peek, peekRead, peekLen - peekRead)
                if (r <= 0) break
                peekRead += r
            }
            val initialPeek = if (peekRead == peekLen) peek else peek.copyOf(peekRead)

            val isClusterStart = initialPeek.size == 4 &&
                initialPeek[0] == 0x1F.toByte() &&
                initialPeek[1] == 0x43.toByte() &&
                initialPeek[2] == 0xB6.toByte() &&
                initialPeek[3] == 0x75.toByte()

            val isKnownNonClusterElement = initialPeek.size == 4 && (
                (initialPeek[0] == 0x1C.toByte() && initialPeek[1] == 0x53.toByte() && initialPeek[2] == 0xBB.toByte() && initialPeek[3] == 0x6B.toByte()) || // Cues
                    (initialPeek[0] == 0x12.toByte() && initialPeek[1] == 0x54.toByte() && initialPeek[2] == 0xC3.toByte() && initialPeek[3] == 0x67.toByte()) || // Tags
                    (initialPeek[0] == 0x11.toByte() && initialPeek[1] == 0x4D.toByte() && initialPeek[2] == 0x9B.toByte() && initialPeek[3] == 0x74.toByte()) || // SeekHead
                    (initialPeek[0] == 0x10.toByte() && initialPeek[1] == 0x43.toByte() && initialPeek[2] == 0xA7.toByte() && initialPeek[3] == 0x70.toByte()) || // Chapters
                    (initialPeek[0] == 0x19.toByte() && initialPeek[1] == 0x41.toByte() && initialPeek[2] == 0xA4.toByte() && initialPeek[3] == 0x69.toByte()) || // Attachments
                    (initialPeek[0] == 0xEC.toByte()) // Void
                )

            // Subcase 3a: Exact Cluster alignment (e.g. Cues timeline seek!)
            if (isClusterStart) {
                writeCastHttpResponseHeaders(out, streamTitle, clientHasRange, safeStart, safeEnd, safeTotal)
                val reader = EbmlRecordingReader(initialPeek, upstream).apply { recordPassthrough = false }
                val needsAudioDecode = !isChromecastSafeDirectAudio(ctx.chosenAudio)
                val transcoder = if (needsAudioDecode) ChromecastAudioTranscoder(ctx.chosenAudio) else null
                try {
                    streamByteExactClustersLoop(
                        reader = reader,
                        out = out,
                        ctx = ctx,
                        transcoder = transcoder,
                        initialClusterSize = null,
                        initialClusterSizeRaw = null,
                        startClusterFileOffset = safeStart,
                        initialRemainingToSend = remainingToSend
                    )
                } catch (_: Throwable) {
                } finally {
                    transcoder?.release()
                }
                return
            }

            // Subcase 3b: Raw non-Cluster element (Cues, Tags, SeekHead, Chapters, Void) or small EOF tail probe
            if (isKnownNonClusterElement || (safeTotal - safeStart) <= 262_144L || remainingToSend <= 65_536L) {
                writeCastHttpResponseHeaders(out, streamTitle, clientHasRange, safeStart, safeEnd, safeTotal)
                if (initialPeek.isNotEmpty()) {
                    val toWrite = min(initialPeek.size.toLong(), remainingToSend).toInt()
                    out.write(initialPeek, 0, toWrite)
                    remainingToSend -= toWrite.toLong()
                }
                val buf = ByteArray(16384)
                while (remainingToSend > 0L) {
                    val n = upstream.read(buf, 0, min(buf.size.toLong(), remainingToSend).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    remainingToSend -= n.toLong()
                }
                out.flush()
                return
            }

            // Subcase 3c: Unaligned mid-Cluster seek (MKV without Cues or cache-evicted mid-Cluster resume)
            val (relClusterOffset, clusterPrefix, rawFallback) = scanForNextMatroskaClusterWithOffset(
                initialPeek = initialPeek,
                upstream = upstream,
                maxScanBytes = remainingToSend
            )
            writeCastHttpResponseHeaders(out, streamTitle, clientHasRange, safeStart, safeEnd, safeTotal)
            if (relClusterOffset >= 0L && clusterPrefix != null) {
                val padLen = min(relClusterOffset, remainingToSend).toInt()
                if (padLen == 1) {
                    out.write(0x00)
                    remainingToSend -= 1L
                } else if (padLen >= 2) {
                    writeEbmlVoidElement(out, padLen)
                    remainingToSend -= padLen.toLong()
                }
                out.flush()
                if (remainingToSend <= 0L) return

                val reader = EbmlRecordingReader(clusterPrefix, upstream).apply { recordPassthrough = false }
                val needsAudioDecode = !isChromecastSafeDirectAudio(ctx.chosenAudio)
                val transcoder = if (needsAudioDecode) ChromecastAudioTranscoder(ctx.chosenAudio) else null
                try {
                    streamByteExactClustersLoop(
                        reader = reader,
                        out = out,
                        ctx = ctx,
                        transcoder = transcoder,
                        initialClusterSize = null,
                        initialClusterSizeRaw = null,
                        startClusterFileOffset = safeStart + relClusterOffset,
                        initialRemainingToSend = remainingToSend
                    )
                } catch (_: Throwable) {
                } finally {
                    transcoder?.release()
                }
            } else {
                // No Cluster found (EOF metadata region): pass raw bytes straight through
                if (rawFallback.isNotEmpty()) {
                    val toWrite = min(rawFallback.size.toLong(), remainingToSend).toInt()
                    out.write(rawFallback, 0, toWrite)
                    remainingToSend -= toWrite.toLong()
                }
                val buf = ByteArray(16384)
                while (remainingToSend > 0L) {
                    val n = upstream.read(buf, 0, min(buf.size.toLong(), remainingToSend).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    remainingToSend -= n.toLong()
                }
                out.flush()
            }
        }

        /**
         * Inspects a Matroska stream at byte 0 for Chromecast:
         * - If all enabled audio tracks use Chromecast-native codecs (`A_AAC*`, `A_OPUS`, `A_VORBIS`, `A_FLAC`,
         *   `A_MPEG/L3`, `A_MPEG/L2`, `A_PCM/INT/LIT` — 2.0 OR 5.1!), returns `Pair(false, passthroughBytes)` for
         *   100% raw passthrough with native seeking.
         * - Otherwise (AC3, EAC3, DTS, TrueHD, MLP), prefers **Byte-Exact EBML `Void`-Padded Matroska Transcoding**:
         *   keeps the original finite `Segment` size, `SeekHead`, `Info`, and EOF `Cues` at their exact original file
         *   offsets, patches ONLY `Tracks` in-place (+ `Void` padding), and transcodes every Cluster's audio to
         *   128 kbps 2.0 Stereo AAC-LC padded with `Void` to the exact original Cluster byte length!
         * - Automatically falls back to v34's proven `200 OK` + `Accept-Ranges: none` streaming mode ONLY for rare
         *   edge-case MKVs with unknown Segment/Cluster sizes, unknown file size, or no AAC encoder.
         */
        private fun streamMatroskaWithChromecastAudio(
            initialPrefix: ByteArray,
            upstream: InputStream,
            out: OutputStream,
            streamKey: String,
            streamTitle: String = "North Stream",
            totalFileBytes: Long = -1L,
            clientHasRange: Boolean = false,
            requestedEndInclusive: Long = -1L
        ): Pair<Boolean, ByteArray?> {
            val reader = EbmlRecordingReader(initialPrefix, upstream)
            val patchedHeaderBuf = ByteArrayOutputStream(65536)

            // 1. Read EBML Header (0x1A45DFA3)
            val ebmlId = reader.readElementIdWithRaw() ?: return Pair(false, reader.drainRemainingPrefixToPassthrough())
            if (ebmlId.first != 0x1A45DFA3L) return Pair(false, reader.drainRemainingPrefixToPassthrough())
            val ebmlSize = reader.readElementSizeWithRaw() ?: return Pair(false, reader.drainRemainingPrefixToPassthrough())
            if (ebmlSize.first <= 0L || ebmlSize.first > 65536L) return Pair(false, reader.drainRemainingPrefixToPassthrough())
            val ebmlBody = reader.readExact(ebmlSize.first.toInt()) ?: return Pair(false, reader.drainRemainingPrefixToPassthrough())

            patchedHeaderBuf.write(ebmlId.second)
            patchedHeaderBuf.write(ebmlSize.second)
            patchedHeaderBuf.write(ebmlBody)

            // 2. Read Segment Header (0x18538067)
            val segId = reader.readElementIdWithRaw() ?: return Pair(false, reader.drainRemainingPrefixToPassthrough())
            if (segId.first != 0x18538067L) return Pair(false, reader.drainRemainingPrefixToPassthrough())
            val segSize = reader.readElementSizeWithRaw() ?: return Pair(false, reader.drainRemainingPrefixToPassthrough())

            patchedHeaderBuf.write(segId.second)
            patchedHeaderBuf.write(segSize.second)

            var infoBody: ByteArray? = null
            var timecodeScaleNs = 1_000_000L
            var tracksList: List<MkvTrackInfo> = emptyList()
            var tracksHeaderInsertPos = -1
            var origTracksTotalBytes = -1
            var firstClusterOffset = -1L
            var firstClusterSize: Long? = null
            var firstClusterSizeRaw: ByteArray? = null
            var headerTooLargeForByteExact = false

            // 3. Scan top-level Segment elements until the first Cluster (0x1F43B675)
            while (true) {
                val elemStartOffset = reader.totalBytesRead
                val elId = reader.readElementIdWithRaw() ?: return Pair(false, reader.drainRemainingPrefixToPassthrough())
                val elSize = reader.readElementSizeWithRaw() ?: return Pair(false, reader.drainRemainingPrefixToPassthrough())

                if (elId.first == 0x1F43B675L) {
                    firstClusterOffset = elemStartOffset
                    firstClusterSize = elSize.first
                    firstClusterSizeRaw = elSize.second
                    break
                }

                val sz = elSize.first
                if (sz < 0L) return Pair(false, reader.drainRemainingPrefixToPassthrough())

                when (elId.first) {
                    0x1549A966L -> {
                        // Info
                        if (sz > 1_048_576L) return Pair(false, reader.drainRemainingPrefixToPassthrough())
                        val body = reader.readExact(sz.toInt()) ?: return Pair(false, reader.drainRemainingPrefixToPassthrough())
                        infoBody = body
                        timecodeScaleNs = parseTimecodeScaleFromInfo(body)
                        if (!headerTooLargeForByteExact && patchedHeaderBuf.size() + elId.second.size + elSize.second.size + body.size <= 4_194_304) {
                            patchedHeaderBuf.write(elId.second)
                            patchedHeaderBuf.write(elSize.second)
                            patchedHeaderBuf.write(body)
                        } else {
                            headerTooLargeForByteExact = true
                        }
                    }
                    0x1654AE6BL -> {
                        // Tracks
                        if (sz > 2_097_152L) return Pair(false, reader.drainRemainingPrefixToPassthrough())
                        val body = reader.readExact(sz.toInt()) ?: return Pair(false, reader.drainRemainingPrefixToPassthrough())
                        tracksList = parseMkvTracks(body)

                        // Check immediately if the file is already 100% Chromecast-native audio!
                        val enabledAudio = tracksList.filter { it.trackType == 2 && it.flagEnabled != 0 }
                        if (enabledAudio.isEmpty() ||
                            (enabledAudio.all { isChromecastSafeDirectAudio(it) } && isChromecastSafeDirectAudio(enabledAudio.first()))
                        ) {
                            return Pair(false, reader.drainRemainingPrefixToPassthrough())
                        }

                        // Stop passthrough recording — we will transcode/remux this MKV!
                        reader.recordPassthrough = false
                        reader.passthroughBuffer.reset()

                        tracksHeaderInsertPos = patchedHeaderBuf.size()
                        origTracksTotalBytes = elId.second.size + elSize.second.size + body.size
                        if (!headerTooLargeForByteExact && patchedHeaderBuf.size() + origTracksTotalBytes <= 4_194_304) {
                            patchedHeaderBuf.write(elId.second)
                            patchedHeaderBuf.write(elSize.second)
                            patchedHeaderBuf.write(body)
                        } else {
                            headerTooLargeForByteExact = true
                        }
                    }
                    else -> {
                        // Preserve SeekHead, Void, Chapters, Tags, Attachments verbatim in `patchedHeaderBuf`
                        if (!headerTooLargeForByteExact && patchedHeaderBuf.size().toLong() + elId.second.size + elSize.second.size + sz <= 4_194_304L) {
                            val body = reader.readExact(sz.toInt()) ?: return Pair(false, reader.drainRemainingPrefixToPassthrough())
                            patchedHeaderBuf.write(elId.second)
                            patchedHeaderBuf.write(elSize.second)
                            patchedHeaderBuf.write(body)
                        } else {
                            headerTooLargeForByteExact = true
                            if (!reader.skipExact(sz)) return Pair(false, reader.drainRemainingPrefixToPassthrough())
                        }
                    }
                }
            }

            val videoTracks = tracksList.filter { it.trackType == 1 && it.flagEnabled != 0 }
            val audioTracks = tracksList.filter { it.trackType == 2 && it.flagEnabled != 0 }
            if (videoTracks.isEmpty() || audioTracks.isEmpty()) {
                return Pair(false, reader.drainRemainingPrefixToPassthrough())
            }

            val nonCommentary = audioTracks.filter { !it.name.contains("commentary", ignoreCase = true) }.ifEmpty { audioTracks }
            val primaryAudio = nonCommentary.firstOrNull { it.flagDefault != 0 } ?: nonCommentary.first()
            val chosenAudio = nonCommentary.firstOrNull {
                isChromecastSafeDirectAudio(it) && (it.language.equals(primaryAudio.language, ignoreCase = true) || primaryAudio.language == "und")
            } ?: primaryAudio

            val needsAudioDecode = !isChromecastSafeDirectAudio(chosenAudio)
            val transcoder = if (needsAudioDecode) ChromecastAudioTranscoder(chosenAudio) else null
            val parsedDurationMs = parseDurationMsFromInfo(infoBody, timecodeScaleNs)

            // Attempt Byte-Exact Void-Padded Matroska Mode (full native Cues seeking + strict RFC 7233 headers!)
            val rawHeaderBytes = if (!headerTooLargeForByteExact) patchedHeaderBuf.toByteArray() else null
            val replacementTracks = if (rawHeaderBytes != null &&
                origTracksTotalBytes > 0 &&
                tracksHeaderInsertPos >= 0 &&
                tracksHeaderInsertPos + origTracksTotalBytes <= rawHeaderBytes.size &&
                segSize.first >= 0L &&
                firstClusterOffset == rawHeaderBytes.size.toLong() &&
                firstClusterSize != null && firstClusterSize >= 0L && firstClusterSizeRaw != null &&
                totalFileBytes > firstClusterOffset &&
                (transcoder == null || transcoder.useAacOutput)
            ) {
                buildByteExactPatchedTracksReplacement(origTracksTotalBytes, videoTracks, chosenAudio, transcoder)
            } else {
                null
            }

            if (rawHeaderBytes != null && replacementTracks != null && firstClusterSize != null && firstClusterSizeRaw != null) {
                System.arraycopy(replacementTracks, 0, rawHeaderBytes, tracksHeaderInsertPos, origTracksTotalBytes)
                val ctx = ByteExactMkvContext(
                    patchedHeaderBytes = rawHeaderBytes,
                    firstClusterOffset = firstClusterOffset,
                    totalFileBytes = totalFileBytes,
                    timecodeScaleNs = timecodeScaleNs,
                    videoTracks = videoTracks,
                    chosenAudio = chosenAudio,
                    durationMs = parsedDurationMs
                )
                transcodedStreamContextMap[streamKey] = ctx
                transcodedStreamUrls.remove(streamKey)

                try {
                    val safeEnd = if (requestedEndInclusive in 0L until totalFileBytes) {
                        requestedEndInclusive
                    } else {
                        totalFileBytes - 1L
                    }
                    var remainingToSend = safeEnd + 1L
                    writeCastHttpResponseHeaders(
                        out = out,
                        streamTitle = streamTitle,
                        clientHasRange = clientHasRange,
                        startOffset = 0L,
                        endInclusive = safeEnd,
                        totalFileBytes = totalFileBytes
                    )

                    val headerWriteLen = min(rawHeaderBytes.size.toLong(), remainingToSend).toInt()
                    if (headerWriteLen > 0) {
                        out.write(rawHeaderBytes, 0, headerWriteLen)
                        out.flush()
                        remainingToSend -= headerWriteLen.toLong()
                    }

                    if (remainingToSend > 0L) {
                        streamByteExactClustersLoop(
                            reader = reader,
                            out = out,
                            ctx = ctx,
                            transcoder = transcoder,
                            initialClusterSize = firstClusterSize,
                            initialClusterSizeRaw = firstClusterSizeRaw,
                            startClusterFileOffset = firstClusterOffset,
                            initialRemainingToSend = remainingToSend
                        )
                    }
                } catch (_: Throwable) {
                } finally {
                    transcoder?.release()
                }
                return Pair(true, null)
            }

            // Fallback: v34's proven Streaming Mode (HTTP/1.1 200 OK, Accept-Ranges: none, unknown Segment size)
            // Used ONLY for edge-case MKVs with unknown Segment/Cluster size, unknown file size, or no AAC encoder.
            transcodedStreamUrls[streamKey] = true
            try {
                val httpHeaders = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: video/mp4\r\n")
                    append("icy-name: $streamTitle\r\n")
                    append("Content-Disposition: inline; filename=\"$streamTitle.mp4\"\r\n")
                    append("Accept-Ranges: none\r\n")
                    append("Access-Control-Allow-Origin: *\r\n")
                    append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
                    append("Access-Control-Allow-Headers: Range, If-Range, Content-Type, Origin, Accept\r\n")
                    append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(httpHeaders.toByteArray(Charsets.US_ASCII))

                writeEbmlElement(out, 0x1A45DFA3L, ebmlBody)
                writeEbmlId(out, 0x18538067L)
                writeEbmlSize(out, -1L) // Unknown/streaming Segment size

                if (infoBody != null) {
                    writeEbmlElement(out, 0x1549A966L, infoBody)
                }

                val newTracksBuf = ByteArrayOutputStream(4096)
                for (vt in videoTracks) {
                    newTracksBuf.write(vt.rawTrackEntryElement)
                }
                if (transcoder != null) {
                    newTracksBuf.write(
                        buildTranscodedAudioTrackEntry(
                            sourceTrack = chosenAudio,
                            outSampleRate = transcoder.outSampleRate,
                            useAacCodec = transcoder.useAacOutput
                        )
                    )
                } else {
                    newTracksBuf.write(chosenAudio.rawTrackEntryElement)
                }
                writeEbmlElement(out, 0x1654AE6BL, newTracksBuf.toByteArray())
                out.flush()

                streamTranscodedClustersLoop(
                    reader = reader,
                    out = out,
                    videoTracks = videoTracks,
                    chosenAudio = chosenAudio,
                    timecodeScaleNs = timecodeScaleNs,
                    transcoder = transcoder,
                    firstClusterSize = firstClusterSize
                )
            } catch (_: Throwable) {
            } finally {
                transcoder?.release()
            }
            return Pair(true, null)
        }

        private fun streamTranscodedClustersLoop(
            reader: EbmlRecordingReader,
            out: OutputStream,
            videoTracks: List<MkvTrackInfo>,
            chosenAudio: MkvTrackInfo,
            timecodeScaleNs: Long,
            transcoder: ChromecastAudioTranscoder?,
            firstClusterSize: Long?
        ) {
            val videoTrackNumbers = videoTracks.map { it.trackNumber }.toHashSet()
            val audioTrackNumber = chosenAudio.trackNumber
            val outClusterBuf = ByteArrayOutputStream(262144)
            var currentClusterTimecode = 0L
            var clusterHasTimecode = false
            var hasFlushedFirstCluster = false

            fun flushCurrentCluster() {
                if (clusterHasTimecode && outClusterBuf.size() > 0) {
                    val bytes = outClusterBuf.toByteArray()
                    outClusterBuf.reset()
                    clusterHasTimecode = false
                    hasFlushedFirstCluster = true
                    writeEbmlId(out, 0x1F43B675L)
                    writeEbmlSize(out, bytes.size.toLong())
                    out.write(bytes)
                    out.flush()
                } else {
                    outClusterBuf.reset()
                    clusterHasTimecode = false
                }
            }

            fun ensureClusterTimecodeWritten(tc: Long) {
                if (!clusterHasTimecode) {
                    currentClusterTimecode = tc.coerceAtLeast(0L)
                    writeEbmlUintElement(outClusterBuf, 0xE7L, currentClusterTimecode)
                    clusterHasTimecode = true
                }
            }

            fun processClusterBody(clusterByteSize: Long): Boolean {
                var bytesReadInCluster = 0L
                var sourceClusterTc = currentClusterTimecode

                while (clusterByteSize < 0L || bytesReadInCluster < clusterByteSize) {
                    val cId = reader.readElementId() ?: return false
                    val cSz = reader.readElementSize() ?: return false
                    bytesReadInCluster += cId.second + cSz.second

                    if (clusterByteSize < 0L && (cId.first == 0x1F43B675L || cId.first == 0x1C53BB6BL || cId.first == 0x1254C367L)) {
                        flushCurrentCluster()
                        if (cId.first == 0x1F43B675L) {
                            bytesReadInCluster = 0L
                            continue
                        } else {
                            if (cSz.first > 0L) reader.skipExact(cSz.first)
                            return true
                        }
                    }

                    val payloadLen = cSz.first
                    if (payloadLen < 0L) return false
                    bytesReadInCluster += payloadLen

                    when (cId.first) {
                        0xE7L -> {
                            val tcBytes = reader.readExact(payloadLen.toInt()) ?: return false
                            sourceClusterTc = readEbmlUint(tcBytes, 0, tcBytes.size)
                            flushCurrentCluster()
                            ensureClusterTimecodeWritten(sourceClusterTc)
                        }
                        0xA3L -> {
                            if (payloadLen > 16_777_216L) {
                                if (!reader.skipExact(payloadLen)) return false
                            } else {
                                val blockBytes = reader.readExact(payloadLen.toInt()) ?: return false
                                val hdr = parseMatroskaBlockHeader(blockBytes)
                                if (hdr != null) {
                                    val blockTimecode = (sourceClusterTc + hdr.relTimecode).coerceAtLeast(0L)
                                    if (videoTrackNumbers.contains(hdr.trackNumber)) {
                                        val isKeyframe = (hdr.flags and 0x80) != 0
                                        val flushThreshold = if (!hasFlushedFirstCluster) 49_152 else 196_608
                                        if (clusterHasTimecode &&
                                            ((isKeyframe && outClusterBuf.size() >= flushThreshold) ||
                                                (blockTimecode - currentClusterTimecode > 30_000L))
                                        ) {
                                            flushCurrentCluster()
                                        }
                                        ensureClusterTimecodeWritten(blockTimecode)
                                        val adjRel = (blockTimecode - currentClusterTimecode)
                                            .coerceIn(0L, 32767L).toInt()
                                        val vintLen = hdr.headerLen - 3
                                        blockBytes[vintLen] = ((adjRel shr 8) and 0xFF).toByte()
                                        blockBytes[vintLen + 1] = (adjRel and 0xFF).toByte()
                                        writeEbmlElement(outClusterBuf, 0xA3L, blockBytes)
                                    } else if (hdr.trackNumber == audioTrackNumber) {
                                        if (transcoder == null) {
                                            ensureClusterTimecodeWritten(blockTimecode)
                                            val adjRel = (blockTimecode - currentClusterTimecode)
                                                .coerceIn(0L, 32767L).toInt()
                                            val vintLen = hdr.headerLen - 3
                                            blockBytes[vintLen] = ((adjRel shr 8) and 0xFF).toByte()
                                            blockBytes[vintLen + 1] = (adjRel and 0xFF).toByte()
                                            writeEbmlElement(outClusterBuf, 0xA3L, blockBytes)
                                        } else {
                                            val frames = unlaceMatroskaBlockFrames(blockBytes, hdr, chosenAudio.sampleStrippedBytes)
                                            val basePtsUs = (blockTimecode * timecodeScaleNs) / 1000L
                                            for ((fIdx, f) in frames.withIndex()) {
                                                val framePtsUs = basePtsUs + (fIdx * 21_333L)
                                                transcoder.transcodeFrame(f, framePtsUs) { encodedAudio, outPtsUs ->
                                                    val outBlockTc = (outPtsUs * 1000L) / timecodeScaleNs
                                                    ensureClusterTimecodeWritten(outBlockTc)
                                                    val rel = (outBlockTc - currentClusterTimecode)
                                                        .coerceIn(0L, 32767L).toShort()
                                                    writeSimpleBlock(outClusterBuf, audioTrackNumber, rel, encodedAudio)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        0xA0L -> {
                            if (payloadLen > 16_777_216L) {
                                if (!reader.skipExact(payloadLen)) return false
                            } else {
                                val groupBytes = reader.readExact(payloadLen.toInt()) ?: return false
                                var gp = 0
                                var innerBlock: ByteArray? = null
                                var innerBlockOffset = -1
                                while (gp < groupBytes.size) {
                                    val gId = readEbmlIdFromArray(groupBytes, gp, groupBytes.size) ?: break
                                    gp += gId.second
                                    val gSz = readEbmlSizeFromArray(groupBytes, gp, groupBytes.size) ?: break
                                    gp += gSz.second
                                    val gLen = gSz.first.toInt()
                                    if (gLen < 0 || gp + gLen > groupBytes.size) break
                                    if (gId.first == 0xA1L) {
                                        innerBlockOffset = gp
                                        innerBlock = groupBytes.copyOfRange(gp, gp + gLen)
                                        break
                                    }
                                    gp += gLen
                                }
                                val hdr = innerBlock?.let { parseMatroskaBlockHeader(it) }
                                if (hdr != null && innerBlock != null && innerBlockOffset >= 0) {
                                    val blockTimecode = (sourceClusterTc + hdr.relTimecode).coerceAtLeast(0L)
                                    if (videoTrackNumbers.contains(hdr.trackNumber)) {
                                        ensureClusterTimecodeWritten(blockTimecode)
                                        val adjRel = (blockTimecode - currentClusterTimecode)
                                            .coerceIn(0L, 32767L).toInt()
                                        val vintLen = hdr.headerLen - 3
                                        groupBytes[innerBlockOffset + vintLen] = ((adjRel shr 8) and 0xFF).toByte()
                                        groupBytes[innerBlockOffset + vintLen + 1] = (adjRel and 0xFF).toByte()
                                        writeEbmlElement(outClusterBuf, 0xA0L, groupBytes)
                                    } else if (hdr.trackNumber == audioTrackNumber) {
                                        if (transcoder == null) {
                                            ensureClusterTimecodeWritten(blockTimecode)
                                            val adjRel = (blockTimecode - currentClusterTimecode)
                                                .coerceIn(0L, 32767L).toInt()
                                            val vintLen = hdr.headerLen - 3
                                            groupBytes[innerBlockOffset + vintLen] = ((adjRel shr 8) and 0xFF).toByte()
                                            groupBytes[innerBlockOffset + vintLen + 1] = (adjRel and 0xFF).toByte()
                                            writeEbmlElement(outClusterBuf, 0xA0L, groupBytes)
                                        } else {
                                            val frames = unlaceMatroskaBlockFrames(innerBlock, hdr, chosenAudio.sampleStrippedBytes)
                                            val basePtsUs = (blockTimecode * timecodeScaleNs) / 1000L
                                            for ((fIdx, f) in frames.withIndex()) {
                                                val framePtsUs = basePtsUs + (fIdx * 21_333L)
                                                transcoder.transcodeFrame(f, framePtsUs) { encodedAudio, outPtsUs ->
                                                    val outBlockTc = (outPtsUs * 1000L) / timecodeScaleNs
                                                    ensureClusterTimecodeWritten(outBlockTc)
                                                    val rel = (outBlockTc - currentClusterTimecode)
                                                        .coerceIn(0L, 32767L).toShort()
                                                    writeSimpleBlock(outClusterBuf, audioTrackNumber, rel, encodedAudio)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        else -> {
                            if (!reader.skipExact(payloadLen)) return false
                        }
                    }
                }
                flushCurrentCluster()
                return true
            }

            if (firstClusterSize != null) {
                if (!processClusterBody(firstClusterSize)) {
                    return
                }
            }

            while (true) {
                val nextId = reader.readElementId() ?: break
                val nextSz = reader.readElementSize() ?: break
                if (nextId.first == 0x1F43B675L) {
                    if (!processClusterBody(nextSz.first)) break
                } else {
                    if (nextSz.first < 0L || !reader.skipExact(nextSz.first)) break
                }
            }
        }

        /**
         * Converts SubRip (`.srt`) subtitle text into WebVTT (`.vtt`) format so Chromecast's
         * Default Media Receiver (`shaka` / `cast.framework.PlayerManager`) and HTML5 `<track>`
         * elements can render subtitles natively alongside local ExoPlayer.
         */
        private fun convertSrtBytesToWebVtt(rawBytes: ByteArray): ByteArray {
            val text = try {
                String(rawBytes, Charsets.UTF_8)
            } catch (_: Throwable) {
                return "WEBVTT\n\n".toByteArray(Charsets.UTF_8)
            }
            val clean = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n").trim()
            if (clean.startsWith("WEBVTT", ignoreCase = true)) {
                return clean.toByteArray(Charsets.UTF_8)
            }
            val vttTimestampRegex = Regex("""(\d{1,2}:\d{2}:\d{2}),(\d{3})\s*-->\s*(\d{1,2}:\d{2}:\d{2}),(\d{3})""")
            val convertedBody = vttTimestampRegex.replace(clean) { m ->
                "${m.groupValues[1]}.${m.groupValues[2]} --> ${m.groupValues[3]}.${m.groupValues[4]}"
            }
            return "WEBVTT\n\n$convertedBody\n".toByteArray(Charsets.UTF_8)
        }

        /**
         * `/sub` CORS Subtitle Proxy Route so Chromecast (`castSession`) and ExoPlayer can load
         * OpenSubtitles tracks with `Access-Control-Allow-Origin: *`, in-memory caching, automatic
         * SRT-to-WebVTT conversion for Cast receivers, and non-blocking empty-subtitle fallback
         * (preventing `SingleSampleMediaPeriod` 502 retry stalls inside `MergingMediaPeriod`).
         */
        private fun handleProxySubtitle(
            sock: Socket,
            method: String,
            rawQuery: String,
            requestHeaders: Map<String, String> = emptyMap()
        ) {
            var subUrl = ""
            var fmt = ""
            for (pair in rawQuery.split("&")) {
                val k = pair.substringBefore("=")
                val v = pair.substringAfter("=", "")
                when (k) {
                    "url" -> subUrl = safeUrlDecode(v)
                    "fmt" -> fmt = safeUrlDecode(v).lowercase(Locale.ROOT)
                }
            }
            if (!subUrl.startsWith("http")) {
                sendHttpResponse(sock, 400, "text/plain", "Invalid subtitle url".toByteArray())
                return
            }

            val ua = requestHeaders["user-agent"].orEmpty()
            val hasCorsOrigin = !requestHeaders["origin"].isNullOrBlank()
            val isRemoteLanClient = try {
                val remoteAddr = sock.inetAddress
                val localAddr = sock.localAddress
                val remoteHost = remoteAddr?.hostAddress.orEmpty()
                val localHost = localAddr?.hostAddress.orEmpty()
                remoteAddr != null &&
                    !remoteAddr.isLoopbackAddress &&
                    remoteHost != "127.0.0.1" &&
                    remoteHost != "::1" &&
                    remoteHost != localHost &&
                    remoteHost != getLocalLanIpAddress()
            } catch (_: Throwable) {
                false
            }
            val wantsWebVtt = fmt == "vtt" ||
                subUrl.endsWith(".vtt", ignoreCase = true) ||
                hasCorsOrigin ||
                isRemoteLanClient ||
                ua.contains("CrKey", ignoreCase = true) ||
                ua.contains("Chromecast", ignoreCase = true) ||
                ua.contains("Fuchsia", ignoreCase = true)

            val outContentType = if (wantsWebVtt) {
                "text/vtt; charset=utf-8"
            } else {
                "application/x-subrip; charset=utf-8"
            }
            val fallbackBody = if (wantsWebVtt) {
                "WEBVTT\n\n".toByteArray(Charsets.UTF_8)
            } else {
                ByteArray(0)
            }

            val cacheKey = "$subUrl|vtt=$wantsWebVtt"
            subtitleResponseCache[cacheKey]?.let { cached ->
                sendHttpResponse(sock, 200, cached.first, cached.second)
                return
            }

            val rawSrtCacheKey = "$subUrl|raw"
            val existingRawBytes = subtitleResponseCache[rawSrtCacheKey]?.second
            if (existingRawBytes != null && existingRawBytes.isNotEmpty()) {
                val outBytes = if (wantsWebVtt) convertSrtBytesToWebVtt(existingRawBytes) else existingRawBytes
                subtitleResponseCache[cacheKey] = Pair(outContentType, outBytes)
                sendHttpResponse(sock, 200, outContentType, outBytes)
                return
            }

            try {
                val conn = (URL(subUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", USER_AGENT)
                    connectTimeout = 3500
                    readTimeout = 3500
                }
                if (conn.responseCode in 200..299) {
                    val bytes = conn.inputStream.use { readAllBytes(it) }
                    conn.disconnect()
                    if (subtitleResponseCache.size > 40) {
                        subtitleResponseCache.clear()
                    }
                    subtitleResponseCache[rawSrtCacheKey] = Pair("application/x-subrip; charset=utf-8", bytes)
                    val outBytes = if (wantsWebVtt) convertSrtBytesToWebVtt(bytes) else bytes
                    subtitleResponseCache[cacheKey] = Pair(outContentType, outBytes)
                    sendHttpResponse(sock, 200, outContentType, outBytes)
                } else {
                    conn.disconnect()
                    sendHttpResponse(sock, 200, outContentType, fallbackBody)
                }
            } catch (_: Exception) {
                sendHttpResponse(sock, 200, outContentType, fallbackBody)
            }
        }

        private fun sendHttpResponse(sock: Socket, statusCode: Int, contentType: String, body: ByteArray) {
            val statusText = when (statusCode) {
                200 -> "OK"
                400 -> "Bad Request"
                404 -> "Not Found"
                else -> "Error"
            }
            val out = sock.getOutputStream()
            val headers = buildString {
                append("HTTP/1.1 $statusCode $statusText\r\n")
                append("Content-Type: $contentType\r\n")
                append("Content-Length: ${body.size}\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
                append("Access-Control-Allow-Headers: Range, If-Range, Content-Type, Origin, Accept\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            out.write(headers.toByteArray(Charsets.US_ASCII))
            out.write(body)
            out.flush()
        }

        /**
         * Pure Kotlin Bencode parser for `.torrent` files.
         * Extracts exact SHA-1 `info_hash`, raw `info` dictionary bytes, private passkey `announce` URLs, and internal file list.
         */
        fun parseTorrentBytesStatic(bytes: ByteArray): ParsedTorrentMeta? {
            if (bytes.isEmpty() || bytes[0] != 'd'.code.toByte()) return null
            return try {
                var pos = 0
                var infoStart = -1
                var infoEnd = -1
                var mainAnnounce: String? = null
                val announceList = mutableListOf<String>()
                var torrentName: String? = null
                val filesList = mutableListOf<Pair<String, Long>>()

                fun readBytesSlice(): ByteArray {
                    val colonIdx = (pos until bytes.size).firstOrNull { bytes[it] == ':'.code.toByte() }
                        ?: throw IllegalArgumentException("Missing colon in bencode string")
                    val lenStr = String(bytes, pos, colonIdx - pos, Charsets.US_ASCII)
                    val len = lenStr.toInt()
                    pos = colonIdx + 1
                    if (len < 0 || pos + len > bytes.size) {
                        throw IllegalArgumentException("Invalid bencode string length")
                    }
                    val slice = bytes.copyOfRange(pos, pos + len)
                    pos += len
                    return slice
                }

                fun readUtf8String(): String = String(readBytesSlice(), Charsets.UTF_8)

                fun readLongValue(): Long {
                    pos++ // skip 'i'
                    val endIdx = (pos until bytes.size).firstOrNull { bytes[it] == 'e'.code.toByte() }
                        ?: throw IllegalArgumentException("Missing 'e' for integer")
                    val numStr = String(bytes, pos, endIdx - pos, Charsets.US_ASCII)
                    pos = endIdx + 1
                    return numStr.toLongOrNull() ?: 0L
                }

                fun skipAnyValue() {
                    if (pos >= bytes.size) return
                    when (bytes[pos].toInt().toChar()) {
                        'i' -> readLongValue()
                        'l' -> {
                            pos++
                            while (pos < bytes.size && bytes[pos] != 'e'.code.toByte()) {
                                skipAnyValue()
                            }
                            if (pos < bytes.size) pos++
                        }
                        'd' -> {
                            pos++
                            while (pos < bytes.size && bytes[pos] != 'e'.code.toByte()) {
                                readBytesSlice()
                                skipAnyValue()
                            }
                            if (pos < bytes.size) pos++
                        }
                        in '0'..'9' -> readBytesSlice()
                        else -> pos++
                    }
                }

                fun parseInfoDictionary() {
                    if (pos >= bytes.size || bytes[pos] != 'd'.code.toByte()) {
                        skipAnyValue()
                        return
                    }
                    pos++ // skip 'd'
                    var singleLength = 0L
                    while (pos < bytes.size && bytes[pos] != 'e'.code.toByte()) {
                        val key = readUtf8String()
                        when (key) {
                            "name" -> torrentName = readUtf8String()
                            "length" -> singleLength = readLongValue()
                            "files" -> {
                                if (pos < bytes.size && bytes[pos] == 'l'.code.toByte()) {
                                    pos++ // skip 'l'
                                    while (pos < bytes.size && bytes[pos] != 'e'.code.toByte()) {
                                        if (bytes[pos] == 'd'.code.toByte()) {
                                            pos++ // skip 'd'
                                            var fileLen = 0L
                                            val pathSegments = mutableListOf<String>()
                                            val utf8PathSegments = mutableListOf<String>()
                                            while (pos < bytes.size && bytes[pos] != 'e'.code.toByte()) {
                                                val fKey = readUtf8String()
                                                when (fKey) {
                                                    "length" -> fileLen = readLongValue()
                                                    "path" -> {
                                                        if (pos < bytes.size && bytes[pos] == 'l'.code.toByte()) {
                                                            pos++
                                                            while (pos < bytes.size && bytes[pos] != 'e'.code.toByte()) {
                                                                pathSegments.add(readUtf8String())
                                                            }
                                                            if (pos < bytes.size) pos++
                                                        } else {
                                                            skipAnyValue()
                                                        }
                                                    }
                                                    "path.utf-8" -> {
                                                        if (pos < bytes.size && bytes[pos] == 'l'.code.toByte()) {
                                                            pos++
                                                            while (pos < bytes.size && bytes[pos] != 'e'.code.toByte()) {
                                                                utf8PathSegments.add(readUtf8String())
                                                            }
                                                            if (pos < bytes.size) pos++
                                                        } else {
                                                            skipAnyValue()
                                                        }
                                                    }
                                                    else -> skipAnyValue()
                                                }
                                            }
                                            if (pos < bytes.size) pos++ // skip 'e' of file dict
                                            val chosenPath = if (utf8PathSegments.isNotEmpty()) utf8PathSegments else pathSegments
                                            if (chosenPath.isNotEmpty()) {
                                                filesList.add(Pair(chosenPath.joinToString("/"), fileLen))
                                            }
                                        } else {
                                            skipAnyValue()
                                        }
                                    }
                                    if (pos < bytes.size) pos++ // skip 'e' of files list
                                } else {
                                    skipAnyValue()
                                }
                            }
                            else -> skipAnyValue()
                        }
                    }
                    if (pos < bytes.size) pos++ // skip 'e' of info dict
                    if (filesList.isEmpty() && !torrentName.isNullOrBlank()) {
                        filesList.add(Pair(torrentName!!, singleLength))
                    }
                }

                pos++ // skip root 'd'
                while (pos < bytes.size && bytes[pos] != 'e'.code.toByte()) {
                    val rootKey = readUtf8String()
                    when (rootKey) {
                        "announce" -> mainAnnounce = readUtf8String()
                        "announce-list" -> {
                            if (pos < bytes.size && bytes[pos] == 'l'.code.toByte()) {
                                pos++
                                while (pos < bytes.size && bytes[pos] != 'e'.code.toByte()) {
                                    if (bytes[pos] == 'l'.code.toByte()) {
                                        pos++
                                        while (pos < bytes.size && bytes[pos] != 'e'.code.toByte()) {
                                            if (bytes[pos].toInt().toChar() in '0'..'9') {
                                                announceList.add(readUtf8String())
                                            } else {
                                                skipAnyValue()
                                            }
                                        }
                                        if (pos < bytes.size) pos++
                                    } else if (bytes[pos].toInt().toChar() in '0'..'9') {
                                        announceList.add(readUtf8String())
                                    } else {
                                        skipAnyValue()
                                    }
                                }
                                if (pos < bytes.size) pos++
                            } else {
                                skipAnyValue()
                            }
                        }
                        "info" -> {
                            infoStart = pos
                            parseInfoDictionary()
                            infoEnd = pos
                        }
                        else -> skipAnyValue()
                    }
                }

                if (infoStart < 0 || infoEnd <= infoStart) return null
                val infoBytes = bytes.copyOfRange(infoStart, infoEnd)
                val digest = MessageDigest.getInstance("SHA-1").digest(infoBytes)
                val infoHashHex = digest.joinToString("") { "%02x".format(it) }

                val allTrackers = mutableListOf<String>()
                if (!mainAnnounce.isNullOrBlank()) allTrackers.add(mainAnnounce!!)
                allTrackers.addAll(announceList.filter { it.isNotBlank() })

                ParsedTorrentMeta(
                    infoHashHex = infoHashHex,
                    name = torrentName,
                    trackers = allTrackers.distinct(),
                    files = filesList,
                    rawInfoBytes = infoBytes
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    private val prefs: SharedPreferences?
        get() = customPrefs ?: (getContext() as? Context)?.getSharedPreferences("NordicBytesPrefs", Context.MODE_PRIVATE)

    private var apiToken: String
        get() = prefs?.getString("api_token", "")?.trim() ?: ""
        set(value) {
            prefs?.edit()?.putString("api_token", value.trim())?.commit()
            clearCaches()
        }

    private val ghostRatio: Boolean
        get() = prefs?.getBoolean("ghost_ratio", true) ?: true

    private val freeleechOnly: Boolean
        get() = prefs?.getBoolean("freeleech_only", false) ?: false

    private val skipIntros: Boolean
        get() {
            val enabled = prefs?.getBoolean("skip_intros", true) ?: true
            isSkipIntrosEnabled = enabled
            return enabled
        }

    private val playNorthIntro: Boolean
        get() {
            val enabled = prefs?.getBoolean("play_north_intro", true) ?: true
            playNorthIntroEnabled = enabled
            return enabled
        }

    private val companionProxyUrl: String
        get() {
            val url = DEFAULT_PROXY_API_URL.trim().trimEnd('/')
            proxyApiBaseUrl = url
            return url
        }

    private val autoSubtitles: Boolean
        get() = prefs?.getBoolean("auto_subtitles", true) ?: true

    private val prefLanguage: String
        get() = prefs?.getString("pref_language", "DA") ?: "DA"

    private val qbitUrl: String
        get() = prefs?.getString("qbit_url", "")?.trim()?.trimEnd('/') ?: ""

    private val qbitUser: String
        get() = prefs?.getString("qbit_user", "")?.trim() ?: ""

    private val qbitPass: String
        get() = prefs?.getString("qbit_pass", "")?.trim() ?: ""

    private val discordWebhook: String
        get() = prefs?.getString("discord_webhook", "")?.trim() ?: ""

    private val apiHeaders: Map<String, String>
        get() = mapOf(
            "Authorization" to "Bearer $apiToken",
            "Accept" to "application/json",
            "User-Agent" to USER_AGENT
        )

    private fun buildApiUrl(pathWithQuery: String): String {
        val separator = if (pathWithQuery.contains("?")) "&" else "?"
        val encodedToken = URLEncoder.encode(apiToken, "UTF-8")
        return "$mainUrl$pathWithQuery${separator}api_token=$encodedToken"
    }

    private fun ensureAuthenticatedDownloadUrl(downloadUrl: String): String {
        if (downloadUrl.isBlank() || downloadUrl.startsWith("magnet:")) return downloadUrl
        if (apiToken.isBlank() || downloadUrl.contains("api_token=")) return downloadUrl
        val sep = if (downloadUrl.contains("?")) "&" else "?"
        return "$downloadUrl${sep}api_token=${URLEncoder.encode(apiToken, "UTF-8")}"
    }

    private suspend fun fetchApiJsonCached(url: String): String {
        val now = System.currentTimeMillis()
        apiResponseCache[url]?.let { (timestamp, cachedBody) ->
            if (now - timestamp < CACHE_TTL_MS && cachedBody.isNotBlank()) {
                return cachedBody
            }
        }

        var lastError: Exception? = null
        for (attempt in 1..3) {
            try {
                val response = app.get(url, headers = apiHeaders)
                if (response.code == 429) {
                    delay(1200L * attempt)
                    continue
                }
                val text = response.text
                if (response.code in 200..299 && text.isNotBlank()) {
                    apiResponseCache[url] = Pair(System.currentTimeMillis(), text)
                    lastSuccessfulApiTimestamp = System.currentTimeMillis()
                    updateObservedPromotionCounts(text)
                    return text
                }
            } catch (e: Exception) {
                lastError = e
                NorthDebugLogger.logApiError(url.substringBefore("?"), "Attempt $attempt failed", e)
                delay(600L * attempt)
            }
        }

        apiResponseCache[url]?.let { return it.second }
        if (lastError != null) {
            NorthDebugLogger.logApiError(url.substringBefore("?"), "All 3 API attempts exhausted", lastError)
            throw lastError
        }
        return ""
    }

    private fun updateObservedPromotionCounts(jsonText: String) {
        try {
            val parsed = AppUtils.parseJson<Unit3dListResponse>(jsonText)
            if (parsed.data.isNotEmpty()) {
                val fl = parsed.data.count { isTorrentFreeleech(it) }
                val du = parsed.data.count { isTorrentDoubleUpload(it) }
                if (fl > 0 || du > 0) {
                    lastObservedFreeleechCount = fl
                    lastObservedDoubleUpCount = du
                }
            }
        } catch (_: Exception) {
        }
    }

    override val mainPage = mainPageOf(
        "/torrents/filter?free[]=100&categories[]=1&categories[]=2&sortField=created_at&sortDirection=desc" to "🎁 100% Freeleech (Zero Ratio Cost)",
        "/torrents/filter?categories[]=1&sortField=seeders&sortDirection=desc" to "🔥 Popular Movies (Best Seeded)",
        "/torrents/filter?categories[]=2&sortField=seeders&sortDirection=desc" to "📺 Popular TV Series (Best Seeded)",
        "/torrents/filter?categories[]=1&sortField=created_at&sortDirection=desc" to "🎬 Latest Movies",
        "/torrents/filter?categories[]=2&sortField=created_at&sortDirection=desc" to "🆕 Latest TV Series",
        "/torrents/filter?name=DANiSH&categories[]=1&categories[]=2&sortField=created_at&sortDirection=desc" to "🇩🇰 Danish Originals & Shows",
        "/torrents/filter?name=NORDiC&categories[]=1&categories[]=2&sortField=created_at&sortDirection=desc" to "🇩🇰🇸🇪 Nordic Releases",
        "/torrents/filter?resolutions[]=2&categories[]=1&categories[]=2&sortField=seeders&sortDirection=desc" to "💎 4K UHD Movies & Series",
        "/torrents/filter?doubleup=1&categories[]=1&categories[]=2&sortField=seeders&sortDirection=desc" to "🚀 2x Double Upload (Ratio Booster)"
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Unit3dListResponse(
        @JsonProperty("data") val data: List<TorrentResource> = emptyList(),
        @JsonProperty("links") val links: Unit3dLinks? = null,
        @JsonProperty("meta") val meta: Unit3dMeta? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Unit3dLinks(
        @JsonProperty("first") val first: String? = null,
        @JsonProperty("last") val last: String? = null,
        @JsonProperty("prev") val prev: String? = null,
        @JsonProperty("next") val next: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Unit3dMeta(
        @JsonProperty("current_page") val currentPage: Int? = null,
        @JsonProperty("from") val from: Int? = null,
        @JsonProperty("last_page") val lastPage: Int? = null,
        @JsonProperty("path") val path: String? = null,
        @JsonProperty("per_page") val perPage: Int? = null,
        @JsonProperty("to") val to: Int? = null,
        @JsonProperty("total") val total: Int? = null,
        @JsonProperty("next_cursor") val nextCursor: String? = null,
        @JsonProperty("prev_cursor") val prevCursor: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TorrentResource(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("attributes") val attributes: TorrentAttributes? = null,
        @JsonProperty("data") val wrappedData: TorrentResource? = null,
        @JsonProperty("name") val flatName: String? = null,
        @JsonProperty("description") val flatDescription: String? = null,
        @JsonProperty("download_link") val flatDownloadLink: String? = null,
        @JsonProperty("magnet_link") val flatMagnetLink: String? = null,
        @JsonProperty("info_hash") val flatInfoHash: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TorrentAttributes(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("release_year") val releaseYear: Int? = null,
        @JsonProperty("seeders") val seeders: Int? = null,
        @JsonProperty("leechers") val leechers: Int? = null,
        @JsonProperty("size") val size: Long? = null,
        @JsonProperty("freeleech") val freeleech: String? = null,
        @JsonProperty("double_upload") val doubleUpload: Boolean? = null,
        @JsonProperty("refundable") val refundable: Boolean? = null,
        @JsonProperty("featured") val featured: Boolean? = null,
        @JsonProperty("personal_release") val personalRelease: Boolean? = null,
        @JsonProperty("category") val category: String? = null,
        @JsonProperty("category_id") val categoryId: Int? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("resolution") val resolution: String? = null,
        @JsonProperty("media_info") val mediaInfo: String? = null,
        @JsonProperty("bd_info") val bdInfo: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("download_link") val downloadLink: String? = null,
        @JsonProperty("magnet_link") val magnetLink: String? = null,
        @JsonProperty("info_hash") val infoHash: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("tmdb_id") val tmdbId: Int? = null,
        @JsonProperty("imdb_id") val imdbId: Int? = null,
        @JsonProperty("tvdb_id") val tvdbId: Int? = null,
        @JsonProperty("mal_id") val malId: Int? = null,
        @JsonProperty("igdb_id") val igdbId: Int? = null,
        @JsonProperty("meta") val meta: MetaLinks? = null,
        @JsonProperty("files") val files: List<TorrentFileItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TorrentFileItem(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("size") val size: Long? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaLinks(
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("backdrop") val backdrop: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("genres") val genres: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MediaGroupRoute(
        @JsonProperty("cleanTitle") val cleanTitle: String = "",
        @JsonProperty("isTv") val isTv: Boolean = false,
        @JsonProperty("tmdbId") val tmdbId: Int? = null,
        @JsonProperty("imdbId") val imdbId: Int? = null,
        @JsonProperty("fallbackId") val fallbackId: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("year") val year: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StreamLinkPayload(
        @JsonProperty("url") val url: String = "",
        @JsonProperty("downloadUrl") val downloadUrl: String = "",
        @JsonProperty("infoHash") val infoHash: String = "",
        @JsonProperty("label") val label: String = "",
        @JsonProperty("continuityName") val continuityName: String = "",
        @JsonProperty("shortGroupTag") val shortGroupTag: String = "",
        @JsonProperty("quality") val quality: Int = Qualities.Unknown.value,
        @JsonProperty("seeders") val seeders: Int = 0,
        @JsonProperty("leechers") val leechers: Int = 0,
        @JsonProperty("isFreeleech") val isFreeleech: Boolean = false,
        @JsonProperty("isDoubleUpload") val isDoubleUpload: Boolean = false,
        @JsonProperty("playabilityTier") val playabilityTier: Int = 1,
        @JsonProperty("isSeasonPack") val isSeasonPack: Boolean = false,
        @JsonProperty("seasonNum") val seasonNum: Int? = null,
        @JsonProperty("episodeIndex") val episodeIndex: Int? = null,
        @JsonProperty("resolvedFileIndex") val resolvedFileIndex: Int? = null,
        @JsonProperty("totalPackEpisodes") val totalPackEpisodes: Int? = null,
        @JsonProperty("runtimeMins") val runtimeMins: Int? = null,
        @JsonProperty("imdbId") val imdbId: Int? = null,
        @JsonProperty("langScore") val langScore: Int = 1,
        @JsonProperty("langFlag") val langFlag: String = ""
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CinemetaSearchResponse(
        @JsonProperty("metas") val metas: List<CinemetaSearchItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CinemetaSearchItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("imdb_id") val imdbId: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("type") val type: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CinemetaResponse(
        @JsonProperty("meta") val meta: CinemetaMeta? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CinemetaMeta(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("background") val background: String? = null,
        @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("imdbRating") val imdbRating: String? = null,
        @JsonProperty("releaseInfo") val releaseInfo: String? = null,
        @JsonProperty("runtime") val runtime: String? = null,
        @JsonProperty("genre") val genre: List<String>? = null,
        @JsonProperty("cast") val cast: List<String>? = null,
        @JsonProperty("videos") val videos: List<CinemetaVideo>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CinemetaVideo(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("number") val number: Int? = null,
        @JsonProperty("thumbnail") val thumbnail: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("description") val description: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class OpenSubtitlesResponse(
        @JsonProperty("subtitles") val subtitles: List<OpenSubtitleEntry>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class OpenSubtitleEntry(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("lang") val lang: String? = null
    )

    data class ParsedTorrentMeta(
        val infoHashHex: String,
        val name: String?,
        val trackers: List<String>,
        val files: List<Pair<String, Long>>,
        val rawInfoBytes: ByteArray? = null
    )

    private val cinemetaCache = ConcurrentHashMap<String, CinemetaMeta>()

    private suspend fun fetchAndParseTorrentMeta(downloadUrl: String): ParsedTorrentMeta? {
        if (downloadUrl.isBlank() || downloadUrl.startsWith("magnet:")) return null
        val authUrl = ensureAuthenticatedDownloadUrl(downloadUrl)
        parsedTorrentCache[authUrl]?.let { return it }

        return try {
            val bytes = rawTorrentBytesCache[authUrl] ?: run {
                val resp = app.get(
                    authUrl,
                    headers = mapOf(
                        "Authorization" to "Bearer $apiToken",
                        "Accept" to "application/x-bittorrent, application/octet-stream, */*",
                        "User-Agent" to USER_AGENT
                    )
                )
                val b = resp.body.bytes()
                if (b.isNotEmpty() && b[0] == 'd'.code.toByte()) {
                    putBoundedRawTorrent(authUrl, b)
                }
                b
            }
            val parsed = parseTorrentBytesStatic(bytes)
            if (parsed != null) {
                putBoundedParsedTorrent(authUrl, parsed)
            }
            parsed
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolves the exact 1-based TorrServer file index for the primary video file in a Movie torrent.
     * Ensures multi-file movie torrents (containing `.nfo`, `.jpg`, or `sample.mkv` alongside the movie)
     * always resolve to the main movie file's 1-based index and NEVER `0`.
     */
    private fun resolveMainMovieFileIndex(files: List<Pair<String, Long>>): Int {
        if (files.size <= 1) return 1
        val videoRegex = Regex("""(?i)\.(mkv|mp4|avi|ts|m2ts|m4v|mov|webm)$""")
        var bestIdx = 1
        var bestSize = -1L
        for ((i, entry) in files.withIndex()) {
            val (name, size) = entry
            if (videoRegex.containsMatchIn(name) && !name.contains("sample", ignoreCase = true)) {
                if (size > bestSize) {
                    bestSize = size
                    bestIdx = i + 1 // 1-based index for TorrServer
                }
            }
        }
        return bestIdx
    }

    /**
     * Resolves the exact 1-based file index inside a Season Pack for `(seasonNum, epNum)`,
     * preserving the exact position in `fileNames` so TorrServer selects the right file ID.
     */
    private fun matchEpisodeFileIndex(
        fileNames: List<String>,
        seasonNum: Int,
        epNum: Int
    ): Int? {
        return matchEpisodeFileIndexStatic(fileNames, seasonNum, epNum)
    }

    private suspend fun fetchOpenMetadata(
        imdbId: Int?,
        isTv: Boolean,
        fallbackTitle: String? = null
    ): CinemetaMeta? {
        val typePath = if (isTv) "series" else "movie"
        var ttId = if (imdbId != null && imdbId > 0) {
            "tt" + imdbId.toString().padStart(7, '0')
        } else {
            null
        }

        if (ttId == null && !fallbackTitle.isNullOrBlank()) {
            val searchKey = "search:$typePath:${fallbackTitle.lowercase(Locale.ROOT)}"
            cinemetaCache[searchKey]?.let { return it }
            try {
                val encTitle = URLEncoder.encode(fallbackTitle, "UTF-8")
                val searchText = app.get("https://v3-cinemeta.strem.io/catalog/$typePath/top/search=$encTitle.json").text
                val metas = AppUtils.parseJson<CinemetaSearchResponse>(searchText).metas.orEmpty()
                val bestMatch = metas.firstOrNull {
                    it.name?.equals(fallbackTitle, ignoreCase = true) == true
                } ?: metas.firstOrNull()
                val candidateTt = (bestMatch?.imdbId ?: bestMatch?.id)?.takeIf { it.startsWith("tt") }
                if (candidateTt != null) {
                    ttId = candidateTt
                }
            } catch (_: Exception) {
            }
        }

        if (ttId == null) return null
        val cacheKey = "$typePath:$ttId"
        cinemetaCache[cacheKey]?.let { return it }

        return try {
            val responseText = app.get("https://v3-cinemeta.strem.io/meta/$typePath/$ttId.json").text
            val parsed = AppUtils.parseJson<CinemetaResponse>(responseText).meta
            if (parsed != null) {
                cinemetaCache[cacheKey] = parsed
                if (!fallbackTitle.isNullOrBlank()) {
                    cinemetaCache["search:$typePath:${fallbackTitle.lowercase(Locale.ROOT)}"] = parsed
                }
            }
            parsed
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchOpenSubtitles(
        imdbId: Int?,
        seasonNum: Int?,
        episodeNum: Int?,
        useLanIp: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        if (!autoSubtitles || imdbId == null || imdbId <= 0) return
        val ttId = "tt" + imdbId.toString().padStart(7, '0')
        val subEndpoint = if (seasonNum != null && seasonNum > 0 && episodeNum != null && episodeNum > 0) {
            "https://opensubtitles-v3.strem.io/subtitles/series/$ttId:$seasonNum:$episodeNum.json"
        } else {
            "https://opensubtitles-v3.strem.io/subtitles/movie/$ttId.json"
        }

        try {
            val proxyBase = companionProxyUrl
            val text = if (isCompanionProxyAvailable()) {
                val proxySubEndpoint = if (seasonNum != null && seasonNum > 0 && episodeNum != null && episodeNum > 0) {
                    "$proxyBase/api/subtitles?imdb_id=${URLEncoder.encode(ttId, "UTF-8")}&season=$seasonNum&episode=$episodeNum"
                } else {
                    "$proxyBase/api/subtitles?imdb_id=${URLEncoder.encode(ttId, "UTF-8")}"
                }
                try {
                    val pResp = app.get(proxySubEndpoint)
                    if (pResp.code in 200..299 && pResp.text.contains("subtitles")) {
                        pResp.text
                    } else {
                        markCompanionProxyUnhealthy()
                        app.get(subEndpoint).text
                    }
                } catch (_: Throwable) {
                    markCompanionProxyUnhealthy()
                    app.get(subEndpoint).text
                }
            } else {
                app.get(subEndpoint).text
            }
            val subList = AppUtils.parseJson<OpenSubtitlesResponse>(text).subtitles ?: return
            val targetLangs = mapOf(
                "dan" to "🇩🇰 Dansk",
                "da" to "🇩🇰 Dansk",
                "swe" to "🇸🇪 Svenska",
                "sv" to "🇸🇪 Svenska",
                "nor" to "🇳🇴 Norsk",
                "no" to "🇳🇴 Norsk",
                "nob" to "🇳🇴 Norsk",
                "fin" to "🇫🇮 Suomi",
                "fi" to "🇫🇮 Suomi",
                "eng" to "🇬🇧 English",
                "en" to "🇬🇧 English"
            )

            val localPort = ensureLocalProxyServer()
            val lanIp = getLocalLanIpAddress()
            val subHost = if (lanIp != "127.0.0.1") lanIp else "127.0.0.1"
            val countByLang = HashMap<String, Int>()
            for (sub in subList) {
                val code = sub.lang?.trim()?.lowercase(Locale.ROOT) ?: continue
                val subUrl = sub.url?.trim()?.takeIf { it.startsWith("http") } ?: continue
                val langDisplay = targetLangs[code] ?: continue
                val seen = countByLang[langDisplay] ?: 0
                if (seen >= 1) continue
                countByLang[langDisplay] = seen + 1
                val suffix = if (seen > 0) " #${seen + 1}" else ""
                val proxiedSubUrl = if (localPort > 0) {
                    "http://$subHost:$localPort/sub?url=${URLEncoder.encode(subUrl, "UTF-8")}"
                } else {
                    subUrl
                }
                subtitleCallback.invoke(SubtitleFile("$langDisplay$suffix", proxiedSubUrl))
            }
        } catch (_: Exception) {
        }
    }

    private fun triggerAutoSeedBridge(torrentDownloadUrl: String, releaseLabel: String) {
        val targetQbitUrl = qbitUrl
        val targetQbitUser = qbitUser
        val targetQbitPass = qbitPass
        val targetWebhook = discordWebhook

        if (targetQbitUrl.isEmpty() && targetWebhook.isEmpty()) return

        thread {
            if (targetQbitUrl.isNotEmpty()) {
                try {
                    val loginUrl = URL("$targetQbitUrl/api/v2/auth/login")
                    val loginConn = (loginUrl.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                        setRequestProperty("Referer", targetQbitUrl)
                        connectTimeout = 6000
                        readTimeout = 6000
                    }
                    val loginData = "username=${URLEncoder.encode(targetQbitUser, "UTF-8")}&password=${URLEncoder.encode(targetQbitPass, "UTF-8")}"
                    OutputStreamWriter(loginConn.outputStream).use { it.write(loginData) }

                    val cookieHeader = loginConn.headerFields["Set-Cookie"]?.joinToString("; ") { it.substringBefore(";") } ?: ""
                    loginConn.disconnect()

                    val addUrl = URL("$targetQbitUrl/api/v2/torrents/add")
                    val boundary = "----NorthSeedBridge${System.currentTimeMillis()}"
                    val addConn = (addUrl.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                        if (cookieHeader.isNotEmpty()) {
                            setRequestProperty("Cookie", cookieHeader)
                        }
                        connectTimeout = 6000
                        readTimeout = 6000
                    }

                    val body = buildString {
                        append("--$boundary\r\n")
                        append("Content-Disposition: form-data; name=\"urls\"\r\n\r\n")
                        append("$torrentDownloadUrl\r\n")
                        append("--$boundary\r\n")
                        append("Content-Disposition: form-data; name=\"tags\"\r\n\r\n")
                        append("North-AutoSeed\r\n")
                        append("--$boundary--\r\n")
                    }
                    OutputStreamWriter(addConn.outputStream).use { it.write(body) }
                    addConn.responseCode
                    addConn.disconnect()
                } catch (_: Exception) {
                }
            }

            if (targetWebhook.isNotEmpty()) {
                try {
                    val webhookConn = (URL(targetWebhook).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        connectTimeout = 6000
                        readTimeout = 6000
                    }
                    val payload = mapOf(
                        "username" to "North Auto-Seed Bridge",
                        "content" to "🌱 **Auto-Seeding Triggered**\n**Release:** `$releaseLabel`\n**Download URL:** $torrentDownloadUrl"
                    )
                    OutputStreamWriter(webhookConn.outputStream).use {
                        it.write(mapper.writeValueAsString(payload))
                    }
                    webhookConn.responseCode
                    webhookConn.disconnect()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun extractCleanTitle(raw: String): String {
        var working = raw
            .replace(Regex("(?i)\\bS\\d{1,2}(?:[.-]?E\\d{1,2})?(?:\\s*-\\s*S?\\d{1,2})?\\b.*"), "")
            .replace(Regex("(?i)\\b(?:Season|Sæson|Sesong)\\s*\\d+.*"), "")

        // Preserve title-leading or title-embedded years (e.g. "1917", "2012", "Blade Runner 2049", "2001 A Space Odyssey")
        // by stripping only from the last release-year token that appears after non-empty title text.
        val yearMatches = Regex("\\b(19\\d{2}|20\\d{2})\\b").findAll(working).toList()
        val releaseYearMatch = yearMatches.lastOrNull { it.range.first > 0 }
        if (releaseYearMatch != null) {
            working = working.substring(0, releaseYearMatch.range.first)
        }

        val cleaned = working
            .replace(
                Regex("(?i)\\b(2160p|1080p|720p|480p|576p|576i|480i|4K|UHD|WEB-DL|WEBRip|BluRay|BDRip|BRRip|Remux|HDTV|DVDRip|NORDiC|DANiSH|SWEDiSH|NORWEGiAN|FiNNiSH|MULTI|Retail|DKsubs|x264|x265|H\\.?264|H\\.?265|HEVC|AVC|AAC|DDP?5?\\.?1|AC3|DTS|TrueHD|Atmos|HDR|DV|DoVi|HYBRiD|REPACK|PROPER|CUSTOM|EXTENDED|UNRATED|DIRECTORS\\.CUT|COMPLETE)\\b.*"),
                ""
            )
            .replace('.', ' ')
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '[', ']', '(', ')')

        return cleaned.ifEmpty { raw.replace('.', ' ').trim() }
    }

    private fun resolveYear(item: TorrentResource): Int? {
        item.attributes?.releaseYear?.takeIf { it > 1900 }?.let { return it }
        val raw = item.attributes?.name ?: item.flatName ?: return null
        val matches = Regex("\\b(19\\d{2}|20\\d{2})\\b").findAll(raw).toList()
        val chosen = matches.lastOrNull { it.range.first > 0 } ?: matches.lastOrNull()
        return chosen?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun upgradePosterUrl(rawUrl: String): String {
        return rawUrl.replace(Regex("/t/p/w(?:92|154|185|342)/"), "/t/p/w500/")
    }

    private fun resolvePoster(item: TorrentResource): String? {
        val candidates = listOf(
            item.attributes?.poster,
            item.attributes?.meta?.poster,
            item.attributes?.meta?.backdrop
        )
        for (c in candidates) {
            if (!c.isNullOrBlank() && c.startsWith("http")) {
                return upgradePosterUrl(c)
            }
        }
        return null
    }

    private fun isAllowedMovieOrSeries(item: TorrentResource): Boolean {
        val attr = item.attributes ?: return true
        if ((attr.igdbId ?: 0) > 0) return false

        val cat = (attr.category ?: "").lowercase(Locale.ROOT)
        val typeStr = (attr.type ?: "").lowercase(Locale.ROOT)
        val rawName = (attr.name ?: item.flatName ?: "").lowercase(Locale.ROOT)

        val blockedCategoryKeywords = listOf(
            "music", "musik", "audio", "flac", "mp3", "audiobook", "lydbog",
            "ebook", "e-book", "bøger", "book", "magazine", "comic",
            "game", "spil", "pc", "nsw", "ps4", "ps5", "xbox", "switch",
            "app", "software", "program", "windows", "macos", "linux", "android", "ios",
            "xxx", "porn", "adult", "erotik", "hentai", "nsfw", "18+"
        )
        if (blockedCategoryKeywords.any { cat.contains(it) || typeStr.contains(it) }) {
            return false
        }

        val catId = attr.categoryId
        if (catId != null && catId > 0 && catId != 1 && catId != 2) {
            val allowedVideoCatKeywords = listOf(
                "tv", "serie", "movie", "film", "anime", "cartoon", "animation",
                "kids", "børn", "born", "doc", "pack", "boxset"
            )
            val isExplicitVideoCat = allowedVideoCatKeywords.any { cat.contains(it) || typeStr.contains(it) } ||
                (attr.tmdbId ?: 0) > 0 || (attr.imdbId ?: 0) > 0
            if (!isExplicitVideoCat) return false
        }

        val blockedReleasePatterns = Regex(
            "(?i)(?:\\b(?:NSW|PS4|PS5|XBOX|fitgirl|dodi-repack|codex|skidrow|empress|tenoke|rune|flt|razor1911|elamigos|gog|v\\d+\\.\\d+(?:\\.\\d+)?|apk|exe|iso|dmg|epub|mobi|azw3|cbz|cbr|mp3|flac-24bit|xxx|brazzers|onlyfans|playboy|penthouse|babes)\\b|\\.(?:apk|exe|iso|dmg|rar|zip|7z|epub|pdf|mp3|flac)$)"
        )
        if (blockedReleasePatterns.containsMatchIn(rawName)) {
            return false
        }

        return true
    }

    private fun isTvItem(item: TorrentResource): Boolean {
        val catId = item.attributes?.categoryId
        if (catId == 2) return true
        val cat = item.attributes?.category?.lowercase(Locale.ROOT) ?: ""
        val rawName = item.attributes?.name ?: item.flatName ?: ""
        return cat.contains("tv") ||
            cat.contains("serie") ||
            Regex("(?i)\\b(S\\d{1,2}|Season\\s*\\d+|Sæson\\s*\\d+|E\\d{1,2})\\b").containsMatchIn(rawName)
    }

    private fun resolveTvType(item: TorrentResource): TvType {
        val cat = item.attributes?.category?.lowercase(Locale.ROOT) ?: ""
        return when {
            cat.contains("anime") -> TvType.Anime
            cat.contains("doc") -> TvType.Documentary
            isTvItem(item) -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun getQualityFromName(name: String, resolution: String?): Int {
        val combined = "$name ${resolution ?: ""}".lowercase(Locale.ROOT)
        return when {
            combined.contains("2160p") || combined.contains("4k") || combined.contains("uhd") -> Qualities.P2160.value
            combined.contains("1080p") -> Qualities.P1080.value
            combined.contains("720p") -> Qualities.P720.value
            combined.contains("480p") || combined.contains("576p") -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }

    private fun formatSize(bytes: Long?): String {
        if (bytes == null || bytes <= 0L) return ""
        val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) {
            String.format(Locale.US, "%.2f GB", gb)
        } else {
            val mb = bytes.toDouble() / (1024.0 * 1024.0)
            String.format(Locale.US, "%.0f MB", mb)
        }
    }

    private fun isTorrentFreeleech(item: TorrentResource): Boolean {
        val fl = item.attributes?.freeleech?.trim()?.lowercase(Locale.ROOT) ?: return false
        if (fl.isEmpty()) return false
        if (fl == "0%" || fl == "0" || fl == "false" || fl == "no" || fl == "none" || fl == "null") return false
        return true
    }

    private fun isTorrentDoubleUpload(item: TorrentResource): Boolean {
        return item.attributes?.doubleUpload == true
    }

    private fun getFreeleechPercentageLabel(item: TorrentResource): String {
        val fl = item.attributes?.freeleech?.trim() ?: return "100% FREELEECH"
        return if (fl.contains("%")) "$fl FREELEECH" else "100% FREELEECH"
    }

    private fun computePlayabilityTier(item: TorrentResource): Int {
        val raw = (item.attributes?.name ?: item.flatName ?: "").lowercase(Locale.ROOT)
        val type = (item.attributes?.type ?: "").lowercase(Locale.ROOT)
        val combined = "$raw $type"
        return when {
            combined.contains("remux") || combined.contains("bdmv") || combined.contains("bd25") || combined.contains("bd50") || combined.contains("dvd9") -> 0
            combined.contains("x265") || combined.contains("h265") || combined.contains("h.265") || combined.contains("hevc") -> 1
            else -> 2
        }
    }

    private fun formatReleaseQualityLabel(item: TorrentResource): String {
        val attr = item.attributes
        val rawName = attr?.name ?: item.flatName ?: "Stream"
        val res = attr?.resolution ?: ""
        val type = attr?.type ?: ""
        val sizeStr = formatSize(attr?.size)
        val seeds = attr?.seeders ?: 0
        val leech = attr?.leechers ?: 0
        val isFl = isTorrentFreeleech(item)
        val isDouble = isTorrentDoubleUpload(item)
        val isRefundable = attr?.refundable == true
        val tier = computePlayabilityTier(item)

        val parts = mutableListOf<String>()
        if (isFl) parts.add("🎁 ${getFreeleechPercentageLabel(item)}")
        if (isDouble) parts.add("🔵 2x UPLOAD")
        if (isRefundable) parts.add("💎 REFUNDABLE")
        if (ghostRatio) parts.add("🛡️ 0 DL • RATIO BOOST")
        if (tier == 0) parts.add("⚠️ REMUX")
        if (res.isNotBlank()) parts.add(res)
        if (type.isNotBlank()) parts.add(type)
        if (sizeStr.isNotBlank()) parts.add(sizeStr)
        parts.add("🌱 $seeds / 🔻 $leech")

        return parts.joinToString(" • ").ifEmpty { rawName }
    }

    /**
     * Builds an EPISODE-INVARIANT source name so CloudStream's player automatically selects the
     * exact same release when advancing from Episode 1 -> Episode 2 -> Episode 3!
     */
    private fun buildContinuityName(item: TorrentResource, isSeasonPack: Boolean): Pair<String, String> {
        val attr = item.attributes
        val rawName = attr?.name ?: item.flatName ?: "Release"
        val upper = rawName.uppercase(Locale.ROOT)

        val res = attr?.resolution?.takeIf { it.isNotBlank() } ?: when {
            upper.contains("2160P") || upper.contains("4K") -> "2160p"
            upper.contains("1080P") -> "1080p"
            upper.contains("720P") -> "720p"
            else -> "1080p"
        }

        val sourceType = attr?.type?.takeIf { it.isNotBlank() } ?: when {
            upper.contains("WEB-DL") || upper.contains("WEBDL") -> "WEB-DL"
            upper.contains("WEBRIP") -> "WEBRip"
            upper.contains("REMUX") -> "REMUX"
            upper.contains("BLURAY") || upper.contains("BLU-RAY") -> "BluRay"
            upper.contains("HDTV") -> "HDTV"
            else -> "WEB"
        }

        val codec = when {
            upper.contains("H264") || upper.contains("H.264") || upper.contains("X264") || upper.contains("AVC") -> "H264"
            upper.contains("H265") || upper.contains("H.265") || upper.contains("X265") || upper.contains("HEVC") -> "HEVC"
            upper.contains("AV1") -> "AV1"
            else -> ""
        }

        val releaseGroup = Regex("""-([A-Za-z0-9_]+)(?:\.[a-z0-9]{2,4})?$""")
            .find(rawName.trim())
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.length in 2..16 }
            ?: "North"

        val (_, langFlag) = computeLangInfo(rawName)
        val isFl = isTorrentFreeleech(item)
        val isDouble = isTorrentDoubleUpload(item)

        val statusBadges = mutableListOf<String>()
        if (isFl) statusBadges.add("🎁 ${getFreeleechPercentageLabel(item)}")
        if (isDouble) statusBadges.add("🔵 2x UP")
        if (ghostRatio) statusBadges.add("🛡️ 0 DL")
        if (statusBadges.isEmpty()) statusBadges.add("⚡ DIRECT STREAM")

        val packTag = if (isSeasonPack) "📦 Season Pack" else "🎬 Single"
        val techSpec = listOf(res, sourceType, codec).filter { it.isNotBlank() }.joinToString(" ")
        val langPart = if (langFlag.isNotBlank()) " • $langFlag" else ""

        // Avoid '+' inside ExtractorLink.name so CloudStream's source UI never clips the prefix before '+'
        val fullContinuityName = "▶ Play • ${statusBadges.joinToString(" • ")} • $packTag • $techSpec$langPart [-$releaseGroup]"
        val shortTag = "${if (isFl) "🎁 FL" else "🛡️ 0 DL"} $res [-$releaseGroup]"
        return Pair(fullContinuityName, shortTag)
    }

    private data class EpisodeParseResult(
        val season: Int,
        val episode: Int?,
        val extraEpisodes: List<Int> = emptyList(),
        val isSeasonPack: Boolean
    )

    private fun parseSeasonEpisode(rawName: String): EpisodeParseResult? {
        val multiEpRegex = Regex("(?i)\\bS(\\d{1,2})[.-]?E(\\d{1,3})(?:[.-]?E?(\\d{1,3}))?\\b")
        val multiMatch = multiEpRegex.find(rawName)
        if (multiMatch != null) {
            val s = multiMatch.groupValues[1].toIntOrNull() ?: 1
            val eStart = multiMatch.groupValues[2].toIntOrNull() ?: 1
            val eEnd = multiMatch.groupValues.getOrNull(3)?.toIntOrNull()
            val extras = if (eEnd != null && eEnd > eStart && (eEnd - eStart) <= 30) {
                ((eStart + 1)..eEnd).toList()
            } else {
                emptyList()
            }
            return EpisodeParseResult(season = s, episode = eStart, extraEpisodes = extras, isSeasonPack = false)
        }

        val sOnlyRegex = Regex("(?i)\\b(?:S(\\d{1,2})|(?:Season|Sæson|Sesong)\\s*(\\d{1,2}))\\b")
        val sMatch = sOnlyRegex.find(rawName)
        if (sMatch != null) {
            val s = sMatch.groupValues[1].toIntOrNull()
                ?: sMatch.groupValues[2].toIntOrNull()
                ?: 1
            return EpisodeParseResult(season = s, episode = null, isSeasonPack = true)
        }

        val epOnlyRegex = Regex("(?i)\\b(?:Episode|Ep|Afsnit)\\s*(\\d{1,3})\\b")
        val epMatch = epOnlyRegex.find(rawName)
        if (epMatch != null) {
            val e = epMatch.groupValues[1].toIntOrNull() ?: 1
            return EpisodeParseResult(season = 1, episode = e, isSeasonPack = false)
        }

        return null
    }

    private fun groupKeyForTorrent(item: TorrentResource): String {
        val attr = item.attributes
        val isTv = isTvItem(item)
        val kindPrefix = if (isTv) "tv" else "movie"

        val tmdb = attr?.tmdbId ?: 0
        if (tmdb > 0) return "$kindPrefix:tmdb:$tmdb"

        val imdb = attr?.imdbId ?: 0
        if (imdb > 0) return "$kindPrefix:imdb:$imdb"

        val clean = extractCleanTitle(attr?.name ?: item.flatName ?: "unknown").lowercase(Locale.ROOT)
        return "$kindPrefix:title:$clean"
    }

    private fun groupTorrentsIntoCards(
        rawItems: List<TorrentResource>,
        seenKeys: MutableSet<String>? = null
    ): List<SearchResponse> {
        val allowedItems = rawItems.filter { isAllowedMovieOrSeries(it) }
        val filteredItems = if (freeleechOnly) {
            allowedItems.filter { isTorrentFreeleech(it) }
        } else {
            allowedItems
        }

        val grouped = LinkedHashMap<String, MutableList<TorrentResource>>()
        for (item in filteredItems) {
            val key = groupKeyForTorrent(item)
            if (seenKeys != null && seenKeys.contains(key)) continue
            grouped.getOrPut(key) { mutableListOf() }.add(item)
        }

        return grouped.mapNotNull { (key, groupItems) ->
            val primary = groupItems.firstOrNull() ?: return@mapNotNull null
            val rawName = primary.attributes?.name ?: primary.flatName ?: return@mapNotNull null
            val cleanTitle = extractCleanTitle(rawName)
            val isTv = groupItems.any { isTvItem(it) }
            val tvType = resolveTvType(primary)
            val posterUrl = groupItems.firstNotNullOfOrNull { resolvePoster(it) }
            val year = groupItems.firstNotNullOfOrNull { resolveYear(it) }

            val bestQuality = groupItems.maxOfOrNull {
                getQualityFromName(it.attributes?.name ?: it.flatName ?: "", it.attributes?.resolution)
            } ?: Qualities.Unknown.value

            val route = MediaGroupRoute(
                cleanTitle = cleanTitle,
                isTv = isTv,
                tmdbId = primary.attributes?.tmdbId?.takeIf { it > 0 },
                imdbId = primary.attributes?.imdbId?.takeIf { it > 0 },
                fallbackId = primary.id,
                poster = posterUrl,
                year = year
            )
            val routeJson = mapper.writeValueAsString(route)
            val encodedRoute = URLEncoder.encode(routeJson, "UTF-8")
            val loadUrl = "$mainUrl/media_group?data=$encodedRoute"

            seenKeys?.add(key)

            val searchQualityBadge: SearchQuality? = when {
                bestQuality >= Qualities.P2160.value -> SearchQuality.UHD
                bestQuality >= Qualities.P720.value -> SearchQuality.HD
                bestQuality >= Qualities.P480.value -> SearchQuality.SD
                else -> null
            }

            if (isTv) {
                newTvSeriesSearchResponse(
                    name = cleanTitle,
                    url = loadUrl,
                    type = TvType.TvSeries
                ) {
                    this.posterUrl = posterUrl
                    this.year = year
                    this.quality = searchQualityBadge
                }
            } else {
                newMovieSearchResponse(
                    name = cleanTitle,
                    url = loadUrl,
                    type = tvType
                ) {
                    this.posterUrl = posterUrl
                    this.year = year
                    this.quality = searchQualityBadge
                }
            }
        }
    }

    /**
     * Extracts `cursor=...` from a UNIT3D / Stardust `links.next` URL if present.
     */
    private fun extractCursorFromNextUrl(nextUrl: String?): String? {
        if (nextUrl.isNullOrBlank()) return null
        val query = nextUrl.substringAfter("?", "")
        if (query.isBlank()) return null
        for (param in query.split("&")) {
            val k = param.substringBefore("=")
            val v = param.substringAfter("=", "")
            if (k.equals("cursor", ignoreCase = true) && v.isNotBlank()) {
                return safeUrlDecode(v)
            }
        }
        return null
    }

    /**
     * Builds a paginated API URL supporting both UNIT3D page-offset (`page=N`) and
     * Laravel cursor pagination (`cursor=...`) returned by `links.next` / `meta.next_cursor`.
     */
    private fun buildPaginatedCategoryUrl(
        baseFilterPath: String,
        targetApiPage: Int,
        cursor: String?
    ): String {
        val cursorParam = if (!cursor.isNullOrBlank()) {
            "&cursor=${URLEncoder.encode(cursor, "UTF-8")}"
        } else {
            ""
        }
        val requestPath = "$baseFilterPath&perPage=50&page=$targetApiPage$cursorParam"
        return buildApiUrl(requestPath)
    }

    /**
     * Paginated category loader (`loadMore`) that fetches additional content as users scroll
     * through categories on the Home screen:
     * - Tracks per-category `lastApiPage`, `nextCursor`, `nextPageUrl`, and `seenGroupKeys`.
     * - Automatically fetches up to 3 consecutive API sub-pages if a single page collapses into
     *   fewer than 10 unique unseen Movie/TV cards after grouping releases by TMDB/IMDb ID.
     */
    private fun buildOnboardingGuideCard(): SearchResponse {
        return newMovieSearchResponse(
            name = "🔑 How to Sign Up & Get Your NordicBytes API Key",
            url = "$mainUrl/onboarding_guide",
            type = TvType.Movie
        ) {
            this.posterUrl = "https://raw.githubusercontent.com/sealinwater/north/builds/icon.png"
        }
    }

    suspend fun loadMore(page: Int, request: MainPageRequest): HomePageResponse {
        if (apiToken.isEmpty()) {
            return newHomePageResponse(
                listOf(
                    HomePageList(
                        "⚠ Account API Key Required: Tap Card for Sign-Up & Key Guide (nordicbytes.org/application)",
                        listOf(buildOnboardingGuideCard())
                    )
                ),
                hasNext = false
            )
        }

        val categoryKey = request.data
        val state = if (page <= 1) {
            CategoryPaginationState().also { categoryPaginationMap[categoryKey] = it }
        } else {
            categoryPaginationMap.getOrPut(categoryKey) {
                CategoryPaginationState(lastApiPage = (page - 1).coerceAtLeast(0))
            }
        }

        if (page > 1 && !state.hasMorePages) {
            return newHomePageResponse(request, emptyList(), hasNext = false)
        }

        val collectedCards = mutableListOf<SearchResponse>()
        var rawFetches = 0
        val maxLookaheadPages = if (page <= 1) 2 else 3
        val minTargetCards = if (page <= 1) 12 else 10

        while (collectedCards.size < minTargetCards && state.hasMorePages && rawFetches < maxLookaheadPages) {
            val nextApiPage = if (state.lastApiPage < page) {
                page + rawFetches
            } else {
                state.lastApiPage + 1
            }

            // NordicBytes uses Laravel cursor pagination and ignores `page=2` without a cursor.
            if (nextApiPage > 1 && state.nextCursor.isNullOrBlank()) {
                state.hasMorePages = false
                break
            }

            val url = buildPaginatedCategoryUrl(
                baseFilterPath = request.data,
                targetApiPage = nextApiPage,
                cursor = if (nextApiPage > 1) state.nextCursor else null
            )

            try {
                val responseText = fetchApiJsonCached(url)
                if (responseText.isBlank()) {
                    state.hasMorePages = false
                    break
                }

                val parsed = AppUtils.parseJson<Unit3dListResponse>(responseText)
                state.lastApiPage = nextApiPage

                val resolvedNextCursor = parsed.meta?.nextCursor?.takeIf { it.isNotBlank() }
                    ?: extractCursorFromNextUrl(parsed.links?.next)
                state.nextCursor = resolvedNextCursor
                state.nextPageUrl = parsed.links?.next

                if (parsed.data.isEmpty()) {
                    state.hasMorePages = false
                    break
                }

                val pageCards = groupTorrentsIntoCards(parsed.data, state.seenGroupKeys)
                collectedCards.addAll(pageCards)

                val metaCurrent = parsed.meta?.currentPage
                val metaLast = parsed.meta?.lastPage
                val hasNextFromMeta = when {
                    !resolvedNextCursor.isNullOrBlank() -> true
                    !parsed.links?.next.isNullOrBlank() -> true
                    metaCurrent != null && metaLast != null -> metaCurrent < metaLast
                    else -> false
                }
                state.hasMorePages = hasNextFromMeta
            } catch (_: Exception) {
                if (collectedCards.isEmpty()) {
                    state.hasMorePages = false
                }
                break
            }

            rawFetches++
        }

        return newHomePageResponse(
            request,
            collectedCards,
            hasNext = state.hasMorePages && collectedCards.isNotEmpty()
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return loadMore(page, request)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    private fun toSceneDotQuery(text: String): String {
        return text
            .trim()
            .replace(Regex("[^a-zA-Z0-9æøåÆØÅ]+"), ".")
            .trim('.')
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val cleanQuery = query.trim()
        val searchKey = cleanQuery.lowercase(Locale.ROOT)
        if (page <= 1) {
            val state = CategoryPaginationState()
            searchPaginationMap[searchKey] = state
            val firstPage = searchInternal(cleanQuery, state)
            return newSearchResponseList(firstPage, hasNext = state.hasMorePages && !state.nextCursor.isNullOrBlank() && firstPage.isNotEmpty())
        }
        if (apiToken.isEmpty()) return newSearchResponseList(emptyList(), hasNext = false)
        if (cleanQuery.isEmpty() || cleanQuery.startsWith("apikey:", ignoreCase = true)) {
            return newSearchResponseList(emptyList(), hasNext = false)
        }

        val state = searchPaginationMap[searchKey] ?: return newSearchResponseList(emptyList(), hasNext = false)
        val cursor = state.nextCursor?.takeIf { it.isNotBlank() } ?: return newSearchResponseList(emptyList(), hasNext = false)
        val dotQuery = toSceneDotQuery(cleanQuery)
        if (dotQuery.isEmpty()) return newSearchResponseList(emptyList(), hasNext = false)

        return try {
            val encDot = URLEncoder.encode(dotQuery, "UTF-8")
            val encCursor = URLEncoder.encode(cursor, "UTF-8")
            val url = buildApiUrl("/torrents/filter?name=$encDot&categories[]=1&categories[]=2&perPage=50&cursor=$encCursor&sortField=seeders&sortDirection=desc")
            val responseText = fetchApiJsonCached(url)
            val parsed = AppUtils.parseJson<Unit3dListResponse>(responseText)
            val nextCursor = parsed.meta?.nextCursor?.takeIf { it.isNotBlank() }
                ?: extractCursorFromNextUrl(parsed.links?.next)
            state.nextCursor = nextCursor
            state.hasMorePages = !nextCursor.isNullOrBlank()
            val cards = groupTorrentsIntoCards(parsed.data, state.seenGroupKeys)
            newSearchResponseList(cards, hasNext = state.hasMorePages && cards.isNotEmpty())
        } catch (_: Exception) {
            state.hasMorePages = false
            newSearchResponseList(emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        val state = CategoryPaginationState()
        if (cleanQuery.isNotEmpty()) {
            searchPaginationMap[cleanQuery.lowercase(Locale.ROOT)] = state
        }
        return searchInternal(cleanQuery, state)
    }

    private suspend fun searchInternal(cleanQuery: String, state: CategoryPaginationState): List<SearchResponse> {
        if (cleanQuery.startsWith("apikey:", ignoreCase = true)) {
            apiToken = cleanQuery.substringAfter(":").trim()
            state.hasMorePages = false
            return listOf(
                newMovieSearchResponse(
                    name = "✓ API Key Saved! Restart or refresh home feed.",
                    url = "$mainUrl/torrents/0",
                    type = TvType.Movie
                )
            )
        }

        if (apiToken.isEmpty()) {
            state.hasMorePages = false
            return listOf(buildOnboardingGuideCard())
        }

        if (cleanQuery.isEmpty()) {
            state.hasMorePages = false
            return emptyList()
        }

        val combinedTorrents = mutableListOf<TorrentResource>()
        state.nextCursor = null
        state.hasMorePages = false

        val dotQuery = toSceneDotQuery(cleanQuery)
        if (dotQuery.isNotEmpty()) {
            try {
                val encDot = URLEncoder.encode(dotQuery, "UTF-8")
                val url = buildApiUrl("/torrents/filter?name=$encDot&categories[]=1&categories[]=2&perPage=50&sortField=seeders&sortDirection=desc")
                val responseText = fetchApiJsonCached(url)
                val parsed = AppUtils.parseJson<Unit3dListResponse>(responseText)
                combinedTorrents.addAll(parsed.data)
                val nextCursor = parsed.meta?.nextCursor?.takeIf { it.isNotBlank() }
                    ?: extractCursorFromNextUrl(parsed.links?.next)
                state.nextCursor = nextCursor
                state.hasMorePages = !nextCursor.isNullOrBlank()
            } catch (_: Exception) {
            }
        }

        if (combinedTorrents.isEmpty() && cleanQuery.contains(" ")) {
            try {
                val encSpace = URLEncoder.encode(cleanQuery, "UTF-8")
                val url = buildApiUrl("/torrents/filter?name=$encSpace&categories[]=1&categories[]=2&perPage=50&sortField=seeders&sortDirection=desc")
                val responseText = fetchApiJsonCached(url)
                val parsed = AppUtils.parseJson<Unit3dListResponse>(responseText)
                combinedTorrents.addAll(parsed.data)
            } catch (_: Exception) {
            }
        }

        if (combinedTorrents.isEmpty()) {
            try {
                val encMeta = URLEncoder.encode(cleanQuery, "UTF-8")
                val movieMetaText = app.get("https://v3-cinemeta.strem.io/catalog/movie/top/search=$encMeta.json").text
                val movieMetas = AppUtils.parseJson<CinemetaSearchResponse>(movieMetaText).metas?.take(2) ?: emptyList()
                val seriesMetaText = app.get("https://v3-cinemeta.strem.io/catalog/series/top/search=$encMeta.json").text
                val seriesMetas = AppUtils.parseJson<CinemetaSearchResponse>(seriesMetaText).metas?.take(2) ?: emptyList()

                val allMetas = seriesMetas + movieMetas

                val imdbIds = allMetas.mapNotNull { it.imdbId?.removePrefix("tt")?.toIntOrNull() }.distinct()
                for (imdbNum in imdbIds) {
                    val url = buildApiUrl("/torrents/filter?imdbId=$imdbNum&categories[]=1&categories[]=2&perPage=50&sortField=seeders&sortDirection=desc")
                    val responseText = fetchApiJsonCached(url)
                    val parsed = AppUtils.parseJson<Unit3dListResponse>(responseText)
                    combinedTorrents.addAll(parsed.data)
                }

                if (combinedTorrents.isEmpty()) {
                    val correctedNames = allMetas.mapNotNull { it.name?.let { n -> toSceneDotQuery(n) } }
                        .filter { it.isNotEmpty() && !it.equals(dotQuery, ignoreCase = true) }
                        .distinct()
                        .take(2)
                    for (correctedDotName in correctedNames) {
                        val encCorr = URLEncoder.encode(correctedDotName, "UTF-8")
                        val url = buildApiUrl("/torrents/filter?name=$encCorr&categories[]=1&categories[]=2&perPage=50&sortField=seeders&sortDirection=desc")
                        val responseText = fetchApiJsonCached(url)
                        val parsed = AppUtils.parseJson<Unit3dListResponse>(responseText)
                        combinedTorrents.addAll(parsed.data)
                    }
                }
            } catch (_: Exception) {
            }
        }

        if (combinedTorrents.isEmpty()) {
            val words = cleanQuery.split(Regex("[^a-zA-Z0-9æøåÆØÅ]+")).filter { it.length >= 4 }
            for (word in words.take(2)) {
                try {
                    val encWord = URLEncoder.encode(word, "UTF-8")
                    val url = buildApiUrl("/torrents/filter?name=$encWord&categories[]=1&categories[]=2&perPage=50&sortField=seeders&sortDirection=desc")
                    val responseText = fetchApiJsonCached(url)
                    val parsed = AppUtils.parseJson<Unit3dListResponse>(responseText)
                    combinedTorrents.addAll(parsed.data)
                    if (combinedTorrents.isNotEmpty()) break
                } catch (_: Exception) {
                }
            }
        }

        return groupTorrentsIntoCards(combinedTorrents, state.seenGroupKeys)
    }

    private suspend fun fetchGroupedTorrents(route: MediaGroupRoute): List<TorrentResource> {
        val queriesToRun = mutableListOf<String>()
        if (route.tmdbId != null && route.tmdbId > 0) {
            queriesToRun.add("/torrents/filter?tmdbId=${route.tmdbId}&perPage=100&sortField=seeders&sortDirection=desc")
        }
        if (route.imdbId != null && route.imdbId > 0) {
            queriesToRun.add("/torrents/filter?imdbId=${route.imdbId}&perPage=100&sortField=seeders&sortDirection=desc")
        }
        if ((queriesToRun.isEmpty() || route.isTv) && route.cleanTitle.isNotBlank()) {
            val dotTitle = toSceneDotQuery(route.cleanTitle)
            val encoded = URLEncoder.encode(dotTitle, "UTF-8")
            queriesToRun.add("/torrents/filter?name=$encoded&perPage=100&sortField=seeders&sortDirection=desc")
        }

        if (queriesToRun.isNotEmpty()) {
            try {
                val collected = mutableListOf<TorrentResource>()
                val seenIds = HashSet<String>()
                for ((qIdx, filterQuery) in queriesToRun.withIndex()) {
                    var cursor: String? = null
                    val maxPages = if (qIdx == 0 && route.isTv && (route.tmdbId != null || route.imdbId != null)) 4 else 1
                    for (pageIdx in 0 until maxPages) {
                        val pageQuery = if (cursor != null) {
                            "$filterQuery&cursor=${URLEncoder.encode(cursor, "UTF-8")}"
                        } else {
                            filterQuery
                        }
                        val responseText = fetchApiJsonCached(buildApiUrl(pageQuery))
                        if (responseText.isBlank()) break
                        val parsed = AppUtils.parseJson<Unit3dListResponse>(responseText)
                        for (t in parsed.data) {
                            val dedupeKey = t.id?.takeIf { it.isNotBlank() }
                                ?: t.attributes?.infoHash?.takeIf { it.isNotBlank() }
                                ?: t.flatInfoHash?.takeIf { it.isNotBlank() }
                                ?: (t.attributes?.name ?: t.flatName ?: "")
                            if (dedupeKey.isBlank() || seenIds.add(dedupeKey)) {
                                collected.add(t)
                            }
                        }
                        cursor = parsed.meta?.nextCursor?.takeIf { it.isNotBlank() }
                            ?: extractCursorFromNextUrl(parsed.links?.next)
                        if (cursor.isNullOrBlank() || parsed.data.isEmpty()) break
                    }
                    // Only run secondary imdbId merge for TV shows (catches season packs missing a tmdbId tag)
                    if (!route.isTv && collected.isNotEmpty()) break
                }

                val list = collected.filter { isAllowedMovieOrSeries(it) }
                if (list.isNotEmpty()) {
                    val targetClean = route.cleanTitle.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9æøå]+"), "")
                    val filtered = list.filter { item ->
                        if (route.tmdbId != null && item.attributes?.tmdbId == route.tmdbId) return@filter true
                        if (route.imdbId != null && item.attributes?.imdbId == route.imdbId) return@filter true
                        val itemClean = extractCleanTitle(item.attributes?.name ?: item.flatName ?: "")
                            .lowercase(Locale.ROOT)
                            .replace(Regex("[^a-z0-9æøå]+"), "")
                        itemClean == targetClean || itemClean.contains(targetClean) || targetClean.contains(itemClean)
                    }
                    if (filtered.isNotEmpty()) return filtered
                    return list
                }
            } catch (_: Exception) {
            }
        }

        if (!route.fallbackId.isNullOrBlank()) {
            try {
                val singleText = fetchApiJsonCached(buildApiUrl("/torrents/${route.fallbackId}"))
                val parsed = AppUtils.parseJson<TorrentResource>(singleText)
                val item = parsed.wrappedData ?: parsed
                if (isAllowedMovieOrSeries(item)) {
                    return listOf(item)
                }
            } catch (_: Exception) {
            }
        }

        return emptyList()
    }

    private fun parseRuntimeMinutes(runtimeStr: String?): Int? {
        if (runtimeStr.isNullOrBlank()) return null
        return Regex("""(\d+)""").find(runtimeStr)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun parseTagsFromGroup(items: List<TorrentResource>, openMeta: CinemetaMeta?): List<String> {
        val tags = mutableListOf<String>()
        if (items.any { isTorrentFreeleech(it) }) {
            tags.add("🎁 Freeleech Available")
        }
        if (items.any { isTorrentDoubleUpload(it) }) {
            tags.add("🔵 2x Double Upload")
        }
        if (ghostRatio) {
            tags.add("🛡️ 0 DL • Active Ratio Boost")
        }
        if (!openMeta?.imdbRating.isNullOrBlank()) {
            tags.add("⭐ IMDb ${openMeta?.imdbRating}")
        }
        if (!openMeta?.runtime.isNullOrBlank()) {
            tags.add("⏱ ${openMeta?.runtime}")
        }
        for (g in openMeta?.genre ?: emptyList()) {
            tags.add(g)
        }

        val firstAttr = items.firstOrNull()?.attributes
        val genresStr = firstAttr?.meta?.genres
        if (!genresStr.isNullOrBlank()) {
            val splitGenres = genresStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            for (g in splitGenres) {
                tags.add(g)
            }
        }

        val resolutions = items.mapNotNull { it.attributes?.resolution }.filter { it.isNotBlank() }.distinct()
        for (r in resolutions) {
            tags.add(r)
        }

        tags.add("${items.size} Release${if (items.size > 1) "s" else ""} Available")
        return tags.distinct()
    }

    private fun cleanDescription(raw: String?, items: List<TorrentResource>, openMeta: CinemetaMeta?): String {
        val plotText = if (!openMeta?.description.isNullOrBlank()) {
            openMeta!!.description!!.trim()
        } else {
            (raw ?: "")
                .replace(Regex("\\[img][^\\[]*\\[/img]", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\[/?[a-zA-Z0-9=*#\"'\\s-]+]"), "")
                .trim()
        }

        val castLine = if (!openMeta?.cast.isNullOrEmpty()) {
            "Cast: ${openMeta!!.cast!!.take(5).joinToString(", ")}"
        } else {
            ""
        }

        val summaryLines = items.take(5).map { "• ${formatReleaseQualityLabel(it)}" }
        val releasesOverview = if (summaryLines.isNotEmpty()) {
            "Available North Releases [${items.size}]:\n" + summaryLines.joinToString("\n")
        } else {
            ""
        }

        return listOf(plotText, castLine, releasesOverview).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    private fun computeLangInfo(rawName: String): Pair<Int, String> {
        val upper = rawName.uppercase(Locale.ROOT)
        val isDanish = upper.contains("DANISH") || upper.contains("DANSK") || upper.contains(".DK.") || upper.contains("-DK") || upper.contains("DKSUBS")
        val isNordic = isDanish || upper.contains("NORDIC") || upper.contains("SWEDISH") || upper.contains("NORWEGIAN") || upper.contains("FINNISH") || upper.contains("MULTI")
        val isEnglish = upper.contains("ENG") || upper.contains("ENGLISH")

        val flag = when {
            isDanish -> "🇩🇰 DA"
            isNordic -> "🇩🇰🇸🇪 NORDIC"
            isEnglish -> "🇬🇧 EN"
            else -> ""
        }

        val pref = prefLanguage
        val score = when (pref) {
            "DA" -> if (isDanish) 4 else if (isNordic) 3 else if (isEnglish) 2 else 1
            "NORDIC" -> if (isNordic) 4 else if (isEnglish) 2 else 1
            "EN" -> if (isEnglish) 4 else 1
            else -> 1
        }
        return Pair(score, flag)
    }

    private fun toStreamPayload(
        item: TorrentResource,
        isSeasonPack: Boolean = false,
        seasonNum: Int? = null,
        episodeIndex: Int? = null,
        resolvedFileIndex: Int? = null,
        totalPackEpisodes: Int? = null,
        runtimeMins: Int? = null,
        imdbId: Int? = null
    ): StreamLinkPayload? {
        val magnet = item.attributes?.magnetLink?.takeIf { it.isNotBlank() }
            ?: item.flatMagnetLink?.takeIf { it.isNotBlank() }
        val rawDownload = item.attributes?.downloadLink?.takeIf { it.isNotBlank() }
            ?: item.flatDownloadLink?.takeIf { it.isNotBlank() }
        val authDownload = rawDownload?.let { ensureAuthenticatedDownloadUrl(it) } ?: ""
        val hash = item.attributes?.infoHash?.trim()?.takeIf { it.isNotBlank() }
            ?: item.flatInfoHash?.trim()?.takeIf { it.isNotBlank() }
            ?: ""

        val primaryLink = authDownload.takeIf { it.isNotBlank() } ?: magnet ?: return null

        val rawName = item.attributes?.name ?: item.flatName ?: "Release"
        val quality = getQualityFromName(rawName, item.attributes?.resolution)
        val label = formatReleaseQualityLabel(item)
        val tier = computePlayabilityTier(item)
        val (langScore, langFlag) = computeLangInfo(rawName)
        val langBadge = if (langFlag.isNotBlank()) " $langFlag" else ""
        val (continuityName, shortGroupTag) = buildContinuityName(item, isSeasonPack)

        return StreamLinkPayload(
            url = primaryLink,
            downloadUrl = authDownload,
            infoHash = hash,
            label = "$label$langBadge ($rawName)",
            continuityName = continuityName,
            shortGroupTag = shortGroupTag,
            quality = quality,
            seeders = item.attributes?.seeders ?: 0,
            leechers = item.attributes?.leechers ?: 0,
            isFreeleech = isTorrentFreeleech(item),
            isDoubleUpload = isTorrentDoubleUpload(item),
            playabilityTier = tier,
            isSeasonPack = isSeasonPack,
            seasonNum = seasonNum,
            episodeIndex = episodeIndex,
            resolvedFileIndex = resolvedFileIndex,
            totalPackEpisodes = totalPackEpisodes,
            runtimeMins = runtimeMins,
            imdbId = imdbId ?: item.attributes?.imdbId,
            langScore = langScore,
            langFlag = langFlag
        )
    }

    /**
     * Fetches the file list of a season pack for episode discovery.
     * Returns `Pair(files, isAuthoritativeTorrentOrder)` where `isAuthoritativeTorrentOrder` is ONLY `true`
     * when parsed directly from `.torrent` bencode `info.files` (because NordicBytes API `attributes.files`
     * can be returned in reverse/arbitrary DB order and MUST NOT be used as TorrServer 1-based file indices).
     */
    private suspend fun fetchPackVideoFiles(packTorrent: TorrentResource): Pair<List<String>, Boolean> {
        val existingFiles = packTorrent.attributes?.files
        if (!existingFiles.isNullOrEmpty()) {
            return Pair(existingFiles.mapNotNull { it.name?.trim() }, false)
        }

        val fromDetail = if (!packTorrent.id.isNullOrBlank()) {
            try {
                val singleText = fetchApiJsonCached(buildApiUrl("/torrents/${packTorrent.id}"))
                val parsed = AppUtils.parseJson<TorrentResource>(singleText)
                val item = parsed.wrappedData ?: parsed
                item.attributes?.files?.mapNotNull { it.name?.trim() } ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        if (fromDetail.isNotEmpty()) {
            return Pair(fromDetail, false)
        }

        val dl = packTorrent.attributes?.downloadLink ?: packTorrent.flatDownloadLink ?: ""
        val parsedMeta = fetchAndParseTorrentMeta(dl)
        val bencodeFiles = parsedMeta?.files?.map { it.first } ?: emptyList()
        return Pair(bencodeFiles, bencodeFiles.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        registerNorthSkipApi()
        isSkipIntrosEnabled = skipIntros

        if (url.contains("/onboarding_guide") || apiToken.isEmpty()) {
            val guidePlot = buildString {
                append("⚠️ ACCOUNT API KEY REQUIRED\n")
                append("You need a NordicBytes (nordicbytes.org) account and your personal UNIT3D API key to use North.\n\n")
                append("1️⃣ APPLY TO JOIN AT: https://nordicbytes.org/application\n")
                append("• Your Application is Public: Whatever you write in the application box and your experience level (like \"New, I haven't used private trackers before\" or experienced) can be seen by all members on the site so they can vouch for you. Just be honest and write a clear answer.\n")
                append("• ⚠️ No Personal Info: Never put your real name, home address, or any other private personal info in the application text.\n")
                append("• Where Are You From? (Anonymous): Optional, shown next to your application.\n")
                append("• Email Address (Staff Only): Only staff can see this.\n")
                append("• Proof (Staff Only, Required): Add at least 2 screenshots (Screenshot URL + Profile URL) showing your profile on another tracker with your username, ratio, and upload/download stats. You need proof or the application gets rejected.\n\n")
                append("2️⃣ GET YOUR API KEY (Already have an account?)\n")
                append("• Log in at https://nordicbytes.org/login\n")
                append("• Open your Profile (top right) -> Settings -> API Key / Security and copy your UNIT3D API token.\n\n")
                append("3️⃣ ADD YOUR KEY IN CLOUDSTREAM\n")
                append("• Go to Cloudstream -> Settings -> Extensions -> tap the ⚙️ Gear Icon next to North, paste your API key, and tap \"Save & Restart CloudStream\" (or search apikey:YOUR_TOKEN in the search bar).")
            }
            return newMovieLoadResponse(
                name = "🔑 Account Key Required: Sign Up & API Key Guide",
                url = url,
                type = TvType.Movie,
                dataUrl = ""
            ) {
                this.posterUrl = "https://raw.githubusercontent.com/sealinwater/north/builds/icon.png"
                this.backgroundPosterUrl = "https://raw.githubusercontent.com/sealinwater/north/builds/icon.png"
                this.plot = guidePlot
                this.tags = listOf(
                    "🔑 API Key Required",
                    "🌐 Apply: nordicbytes.org/application",
                    "⚙️ Paste Key in North Settings"
                )
            }
        }

        val route: MediaGroupRoute = try {
            if (url.contains("/media_group?data=")) {
                val encoded = url.substringAfter("/media_group?data=")
                val decoded = URLDecoder.decode(encoded, "UTF-8")
                AppUtils.parseJson<MediaGroupRoute>(decoded)
            } else {
                val torrentId = url.substringAfterLast("/")
                MediaGroupRoute(cleanTitle = "", isTv = false, fallbackId = torrentId)
            }
        } catch (_: Exception) {
            MediaGroupRoute(cleanTitle = "Unknown", isTv = false)
        }

        val items = fetchGroupedTorrents(route)
        val primary = items.firstOrNull()

        val rawImdbId = route.imdbId ?: items.firstNotNullOfOrNull { it.attributes?.imdbId?.takeIf { id -> id > 0 } }
        val tmdbId = route.tmdbId ?: items.firstNotNullOfOrNull { it.attributes?.tmdbId?.takeIf { id -> id > 0 } }
        val isTv = route.isTv || items.any { isTvItem(it) }
        val lookupTitle = route.cleanTitle.takeIf { it.isNotBlank() }
            ?: primary?.let { extractCleanTitle(it.attributes?.name ?: it.flatName ?: "") }

        val openMeta = fetchOpenMetadata(rawImdbId, isTv, lookupTitle)
        val imdbId = rawImdbId ?: openMeta?.id?.removePrefix("tt")?.toIntOrNull()
        val runtimeMins = parseRuntimeMinutes(openMeta?.runtime)

        val officialTitle = openMeta?.name?.takeIf { it.isNotBlank() }
            ?: route.cleanTitle.takeIf { it.isNotBlank() }
            ?: primary?.let { extractCleanTitle(it.attributes?.name ?: it.flatName ?: "Unknown") }
            ?: "Unknown Title"

        val poster = openMeta?.poster?.takeIf { it.isNotBlank() }
            ?: items.firstNotNullOfOrNull { resolvePoster(it) }
            ?: route.poster

        val backgroundPoster = openMeta?.background?.takeIf { it.isNotBlank() } ?: poster
        val year = items.firstNotNullOfOrNull { resolveYear(it) } ?: route.year
        val tags = parseTagsFromGroup(items, openMeta)
        val plot = cleanDescription(primary?.attributes?.description ?: primary?.flatDescription, items, openMeta)

        if (isTv) {
            val episodeTorrentsMap = LinkedHashMap<Pair<Int, Int>, MutableList<TorrentResource>>()
            val seasonPackTorrentsMap = LinkedHashMap<Int, MutableList<TorrentResource>>()
            val unnumberedTorrents = mutableListOf<TorrentResource>()

            val multiSeasonRegex = Regex("""(?i)\bS(\d{1,2})\s*-\s*S?(\d{1,2})\b""")

            for (torrent in items) {
                val rawName = torrent.attributes?.name ?: torrent.flatName ?: ""
                val multiMatch = multiSeasonRegex.find(rawName)
                if (multiMatch != null) {
                    val sStart = multiMatch.groupValues[1].toIntOrNull() ?: 1
                    val sEnd = (multiMatch.groupValues[2].toIntOrNull() ?: sStart).coerceAtMost(sStart + 25)
                    for (s in sStart..sEnd) {
                        seasonPackTorrentsMap.getOrPut(s) { mutableListOf() }.add(torrent)
                    }
                    continue
                }

                val epInfo = parseSeasonEpisode(rawName)
                val isPack = epInfo?.isSeasonPack == true || (epInfo != null && epInfo.episode == null)

                if (epInfo == null) {
                    unnumberedTorrents.add(torrent)
                } else if (isPack) {
                    seasonPackTorrentsMap.getOrPut(epInfo.season) { mutableListOf() }.add(torrent)
                } else {
                    val primaryEp = epInfo.episode ?: 1
                    episodeTorrentsMap.getOrPut(Pair(epInfo.season, primaryEp)) { mutableListOf() }.add(torrent)
                    for (extraEp in epInfo.extraEpisodes) {
                        episodeTorrentsMap.getOrPut(Pair(epInfo.season, extraEp)) { mutableListOf() }.add(torrent)
                    }
                }
            }

            val cinemetaEpisodes = HashMap<Pair<Int, Int>, CinemetaVideo>()
            for (video in openMeta?.videos ?: emptyList()) {
                val s = video.season ?: continue
                val e = video.episode ?: video.number ?: continue
                if (s > 0 && e > 0) {
                    cinemetaEpisodes[Pair(s, e)] = video
                }
            }

            val allSeasons = (episodeTorrentsMap.keys.map { it.first } + seasonPackTorrentsMap.keys)
                .distinct()
                .sorted()

            val episodesList = mutableListOf<Episode>()
            val videoRegex = Regex("""(?i)\.(mkv|mp4|avi|ts|m2ts|m4v|mov|webm)$""")

            for (seasonNum in allSeasons) {
                val packTorrents = seasonPackTorrentsMap[seasonNum] ?: emptyList()
                val singleEpNums = episodeTorrentsMap.keys
                    .filter { it.first == seasonNum }
                    .map { it.second }

                val seasonEpNumbers = LinkedHashSet<Int>()
                seasonEpNumbers.addAll(singleEpNums)

                val packFilesByTorrentKey = HashMap<String, Pair<List<String>, Boolean>>()
                var packFileNames: List<String> = emptyList()

                if (packTorrents.isNotEmpty()) {
                    for ((pIdx, pack) in packTorrents.withIndex()) {
                        if (pIdx >= 3) break
                        val pKey = pack.id ?: (pack.attributes?.infoHash ?: pack.flatInfoHash ?: "$pIdx")
                        packFilesByTorrentKey[pKey] = fetchPackVideoFiles(pack)
                    }
                    packFileNames = packTorrents.firstOrNull()?.let { firstPack ->
                        val fKey = firstPack.id ?: (firstPack.attributes?.infoHash ?: firstPack.flatInfoHash ?: "0")
                        packFilesByTorrentKey[fKey]?.first
                    } ?: emptyList()

                    // Parse actual (season, episode) numbers from video files inside the pack so multi-season
                    // packs (e.g. S01-S05) only list the exact episodes belonging to `seasonNum`!
                    val parsedPackEpNums = LinkedHashSet<Int>()
                    for (filePath in packFileNames) {
                        if (isPaddingOrNonMediaPath(filePath)) continue
                        val baseName = filePath.substringAfterLast("/")
                        if (!videoRegex.containsMatchIn(baseName) || baseName.contains("sample", ignoreCase = true)) continue
                        val parsedEp = parseSeasonEpisode(baseName)
                        if (parsedEp != null && !parsedEp.isSeasonPack && parsedEp.episode != null && parsedEp.season == seasonNum) {
                            parsedPackEpNums.add(parsedEp.episode)
                            parsedPackEpNums.addAll(parsedEp.extraEpisodes)
                        }
                    }

                    val packVideoCount = packFileNames.count {
                        !isPaddingOrNonMediaPath(it) && videoRegex.containsMatchIn(it) && !it.contains("sample", ignoreCase = true)
                    }
                    val metaEpNums = cinemetaEpisodes.keys
                        .filter { it.first == seasonNum }
                        .map { it.second }
                        .sorted()

                    if (parsedPackEpNums.isNotEmpty()) {
                        seasonEpNumbers.addAll(parsedPackEpNums.sorted())
                    } else if (metaEpNums.isNotEmpty()) {
                        seasonEpNumbers.addAll(metaEpNums)
                    } else if (packVideoCount > 0) {
                        for (idx in 1..packVideoCount) {
                            seasonEpNumbers.add(idx)
                        }
                    } else {
                        for (idx in 1..12) {
                            seasonEpNumbers.add(idx)
                        }
                    }
                }

                val sortedEps = seasonEpNumbers.sorted()
                val totalEpsInSeason = sortedEps.size.coerceAtLeast(1)

                for (epNum in sortedEps) {
                    val payloads = mutableListOf<StreamLinkPayload>()

                    for ((pIdx, pack) in packTorrents.withIndex()) {
                        val pKey = pack.id ?: (pack.attributes?.infoHash ?: pack.flatInfoHash ?: "$pIdx")
                        val packEntry = packFilesByTorrentKey[pKey]
                        val thisPackFiles = packEntry?.first
                        val isAuthoritativeOrder = packEntry?.second == true
                        // ONLY pre-bind resolvedFileIndex when parsed from authoritative .torrent bencode order;
                        // otherwise leave null so loadLinks / proxy resolves the exact index from .torrent / TorrServer!
                        val matchedIdx = if (isAuthoritativeOrder && !thisPackFiles.isNullOrEmpty()) {
                            matchEpisodeFileIndex(thisPackFiles, seasonNum, epNum)
                        } else {
                            null
                        }
                        toStreamPayload(
                            pack,
                            isSeasonPack = true,
                            seasonNum = seasonNum,
                            episodeIndex = epNum,
                            resolvedFileIndex = matchedIdx,
                            totalPackEpisodes = totalEpsInSeason,
                            runtimeMins = runtimeMins,
                            imdbId = imdbId
                        )?.let {
                            payloads.add(it)
                        }
                    }

                    val singleTorrents = episodeTorrentsMap[Pair(seasonNum, epNum)] ?: emptyList()
                    for (single in singleTorrents) {
                        toStreamPayload(
                            single,
                            isSeasonPack = false,
                            seasonNum = seasonNum,
                            episodeIndex = epNum,
                            runtimeMins = runtimeMins,
                            imdbId = imdbId
                        )?.let {
                            payloads.add(it)
                        }
                    }

                    if (payloads.isEmpty()) continue

                    val metaVideo = cinemetaEpisodes[Pair(seasonNum, epNum)]
                    val matchedDisplayIdx = if (packFileNames.isNotEmpty()) {
                        matchEpisodeFileIndex(packFileNames, seasonNum, epNum)
                    } else {
                        null
                    }
                    val fallbackFileName = matchedDisplayIdx?.let { packFileNames.getOrNull(it - 1)?.substringAfterLast("/") }
                    val epTitle = metaVideo?.name?.takeIf { it.isNotBlank() }
                        ?: fallbackFileName?.let { extractCleanTitle(it).takeIf { c -> c.isNotBlank() && !c.equals(officialTitle, ignoreCase = true) } }
                        ?: "Episode $epNum"

                    val maxSeedersForEp = payloads.maxOfOrNull { it.seeders } ?: 0
                    val seederNote = if (maxSeedersForEp > 0) {
                        "$maxSeedersForEp seeder${if (maxSeedersForEp == 1) "" else "s"}"
                    } else {
                        "0 seeders"
                    }
                    val sourceBadge = when {
                        singleTorrents.isNotEmpty() && packTorrents.isNotEmpty() ->
                            "${packTorrents.size} Season Pack & ${singleTorrents.size} Single • $seederNote"
                        packTorrents.isNotEmpty() ->
                            "Season $seasonNum Pack • Episode $epNum • ${packTorrents.size} source${if (packTorrents.size > 1) "s" else ""} • $seederNote"
                        else ->
                            "${singleTorrents.size} source${if (singleTorrents.size > 1) "s" else ""} • $seederNote"
                    }

                    val epPlot = metaVideo?.overview?.takeIf { it.isNotBlank() }
                        ?: metaVideo?.description?.takeIf { it.isNotBlank() }
                        ?: sourceBadge

                    val payloadsJson = mapper.writeValueAsString(payloads)
                    episodesList.add(
                        newEpisode(payloadsJson) {
                            this.name = epTitle
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = metaVideo?.thumbnail ?: poster
                            this.description = "$sourceBadge\n$epPlot".trim()
                            this.runTime = runtimeMins
                        }
                    )
                }
            }

            if (episodesList.isEmpty() && unnumberedTorrents.isNotEmpty()) {
                val fallbackPayloads = unnumberedTorrents.mapNotNull {
                    toStreamPayload(
                        it,
                        isSeasonPack = false,
                        seasonNum = 1,
                        episodeIndex = 1,
                        runtimeMins = runtimeMins,
                        imdbId = imdbId
                    )
                }
                val fallbackJson = mapper.writeValueAsString(fallbackPayloads)
                episodesList.add(
                    newEpisode(fallbackJson) {
                        this.name = officialTitle
                        this.season = 1
                        this.episode = 1
                        this.posterUrl = poster
                        this.runTime = runtimeMins
                    }
                )
            }

            return newTvSeriesLoadResponse(
                name = officialTitle,
                url = url,
                type = TvType.TvSeries,
                episodes = episodesList
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backgroundPoster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.duration = runtimeMins
                if (imdbId != null && imdbId > 0) {
                    addImdbId("tt" + imdbId.toString().padStart(7, '0'))
                }
                if (tmdbId != null && tmdbId > 0) {
                    addTMDbId(tmdbId.toString())
                }
            }
        } else {
            val allMovieStreams = items.mapNotNull {
                toStreamPayload(it, isSeasonPack = false, runtimeMins = runtimeMins, imdbId = imdbId)
            }
            val movieStreamsJson = mapper.writeValueAsString(allMovieStreams)
            return newMovieLoadResponse(
                name = officialTitle,
                url = url,
                type = TvType.Movie,
                dataUrl = movieStreamsJson
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backgroundPoster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.duration = runtimeMins
                if (imdbId != null && imdbId > 0) {
                    addImdbId("tt" + imdbId.toString().padStart(7, '0'))
                }
                if (tmdbId != null && tmdbId > 0) {
                    addTMDbId(tmdbId.toString())
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            registerNorthSkipApi()
            isSkipIntrosEnabled = skipIntros
            playNorthIntroEnabled = playNorthIntro
            proxyApiBaseUrl = companionProxyUrl

            val payloads: List<StreamLinkPayload> = try {
                AppUtils.parseJson<Array<StreamLinkPayload>>(data).toList()
            } catch (_: Exception) {
                val parts = data.split("|||")
                val linkUrl = parts.getOrNull(0)?.trim() ?: ""
                if (linkUrl.isNotEmpty()) {
                    listOf(
                        StreamLinkPayload(
                            url = linkUrl,
                            downloadUrl = linkUrl,
                            label = parts.getOrNull(1) ?: this.name,
                            continuityName = "▶ Play • 🛡️ 0 DL • Direct Stream",
                            shortGroupTag = "🛡️ 0 DL",
                            quality = getQualityFromName(parts.getOrNull(1) ?: "", parts.getOrNull(2))
                        )
                    )
                } else {
                    emptyList()
                }
            }

            val targetPayloads = if (freeleechOnly) {
                val flOnly = payloads.filter { it.isFreeleech }
                flOnly.ifEmpty { payloads }
            } else {
                payloads
            }

            if (targetPayloads.isEmpty()) return false

            val castingNow = isCasting || hasActiveCastSession()
            ensureCastProgressMonitorRunning()
            val firstPayload = targetPayloads.first()
            val currentMediaKey = "${firstPayload.imdbId ?: 0}:${firstPayload.seasonNum ?: 0}:${firstPayload.episodeIndex ?: 0}:${firstPayload.url.hashCode()}"
            if (!castingNow) {
                if (playNorthIntroEnabled) {
                    triggerNorthIntroPreRoll(isCastingNow = false, mediaKey = currentMediaKey)
                } else {
                    schedulePostStartSkipSync(isCastingNow = false)
                }
            }
            val sortedPayloads = targetPayloads.sortedWith(
                compareBy<StreamLinkPayload> { isKnownDeadHash(it.infoHash) }
                    .thenByDescending { it.seeders > 0 }
                    .thenByDescending { it.seeders >= 2 }
                    .thenBy { !it.isSeasonPack }
                    .thenByDescending { it.isFreeleech }
                    .thenByDescending { it.isDoubleUpload }
                    .thenByDescending { it.langScore }
                    .thenByDescending {
                        if (castingNow) {
                            val up = it.label.uppercase(Locale.ROOT)
                            if (up.contains("AAC") || up.contains(".MP4") || up.contains("2.0")) 1 else 0
                        } else {
                            0
                        }
                    }
                    .thenByDescending { it.playabilityTier }
                    .thenByDescending { it.seeders }
                    .thenByDescending { it.quality }
            )

            sortedPayloads.firstOrNull()?.let { primaryStream ->
                val seedUrl = primaryStream.downloadUrl.ifBlank { primaryStream.url }
                triggerAutoSeedBridge(seedUrl, primaryStream.label)
            }

            val localPort = ensureLocalProxyServer()
            val lanIp = getLocalLanIpAddress()
            // Always emit a LAN-routable host IP (e.g. 192.168.1.x) when connected to Wi-Fi/LAN so that
            // starting a Chromecast, Google TV, FCast, or WebVideoCast session AFTER loadLinks() has
            // already populated ResultViewModel2's link cache still sends a valid LAN URL to the TV!
            // On the phone itself, local ExoPlayer's `getVideoInterceptor` intercepts `/cast/stream`
            // regardless of host IP and rewrites directly to `http://127.0.0.1:<torrPort>/stream/...`.
            val streamHost = if (lanIp != "127.0.0.1") lanIp else "127.0.0.1"
            try {
                Torrent.hasAcceptedTorrentForThisSession = true
            } catch (_: Throwable) {
            }
            val seenSourceNames = HashSet<String>()
            var prewarmedTopStream = false
            var seasonPacksFetched = 0

            for (stream in sortedPayloads) {
                if (stream.url.isBlank() && stream.downloadUrl.isBlank()) continue

                val cleanDownloadUrl = stream.downloadUrl
                    .ifBlank { if (!stream.url.startsWith("magnet:")) stream.url else "" }
                    .substringBefore("#index=")

                val authDownloadUrl = ensureAuthenticatedDownloadUrl(cleanDownloadUrl)
                val cachedMeta = if (authDownloadUrl.isNotBlank()) parsedTorrentCache[authDownloadUrl] else null

                // CRITICAL FOR FAST CASTING & EXTERNAL PLAYERS:
                // CloudStream's `startChromecast` and External Player launcher wait for `loadLinks()` to finish
                // completely before starting playback! Only download `.torrent` metadata upfront for the #1 top
                // stream (or unindexed season packs); all other mirrors resolve on-demand in `/cast/stream.mp4`!
                val parsedMeta = when {
                    cachedMeta != null -> cachedMeta
                    cleanDownloadUrl.isBlank() -> null
                    !prewarmedTopStream -> fetchAndParseTorrentMeta(cleanDownloadUrl)
                    stream.isSeasonPack && seasonPacksFetched < 3 -> {
                        seasonPacksFetched++
                        fetchAndParseTorrentMeta(cleanDownloadUrl)
                    }
                    else -> null
                }

                val targetSeason = stream.seasonNum ?: 1
                val targetEp = stream.episodeIndex ?: 0

                // Resolve exact 1-based file index in the torrent (NEVER 0, since TorrServer file IDs are 1-based!)
                val exactFileIndex: Int = if (stream.isSeasonPack && targetEp > 0) {
                    if (parsedMeta != null && parsedMeta.files.isNotEmpty()) {
                        matchEpisodeFileIndex(
                            fileNames = parsedMeta.files.map { it.first },
                            seasonNum = targetSeason,
                            epNum = targetEp
                        ) ?: stream.resolvedFileIndex ?: targetEp
                    } else {
                        stream.resolvedFileIndex ?: targetEp
                    }
                } else if (parsedMeta != null && parsedMeta.files.isNotEmpty()) {
                    resolveMainMovieFileIndex(parsedMeta.files)
                } else {
                    1
                }.coerceAtLeast(1)

                val linkQuality = if (stream.quality != Qualities.Unknown.value) stream.quality else Qualities.P1080.value

                // Episode-invariant name so Episode 1 -> Episode 2 auto-continues seamlessly
                var baseContinuityName = stream.continuityName.ifBlank { "▶ Play • 🎁 FREELEECH • ${stream.label}" }
                if (!seenSourceNames.add(baseContinuityName)) {
                    var dupIdx = 2
                    while (!seenSourceNames.add("$baseContinuityName #$dupIdx")) {
                        dupIdx++
                    }
                    baseContinuityName = "$baseContinuityName #$dupIdx"
                }

                val resolvedHash = (
                    parsedMeta?.infoHashHex?.takeIf { it.isNotBlank() }
                        ?: stream.infoHash.takeIf { it.isNotBlank() }
                    ).orEmpty()

                if (parsedMeta != null && parsedMeta.files.isNotEmpty() &&
                    isArchiveOnlyFilePaths(parsedMeta.files.map { it.first }) &&
                    sortedPayloads.size > 1
                ) {
                    if (resolvedHash.isNotBlank()) {
                        unplayableArchiveHashes[resolvedHash.lowercase(Locale.ROOT)] = System.currentTimeMillis()
                    }
                    continue
                }

                val displayTitle = parsedMeta?.name ?: stream.label
                val primaryTracker = parsedMeta?.trackers?.firstOrNull { it.startsWith("http") }.orEmpty()
                val totalSz = parsedMeta?.files?.sumOf { it.second }?.coerceAtLeast(536_870_912L) ?: 1_610_612_736L

                if (localPort > 0 && (cleanDownloadUrl.isNotBlank() || resolvedHash.isNotBlank())) {
                    val encUrl = URLEncoder.encode(authDownloadUrl.ifBlank { cleanDownloadUrl.ifBlank { stream.url.substringBefore("#index=") } }, "UTF-8")
                    val encTok = URLEncoder.encode(apiToken, "UTF-8")
                    val ghostFlag = if (ghostRatio) "1" else "0"
                    val packFlag = if (stream.isSeasonPack) "1" else "0"
                    val encHash = URLEncoder.encode(resolvedHash, "UTF-8")
                    val encTitle = URLEncoder.encode(displayTitle, "UTF-8")
                    val encTracker = URLEncoder.encode(primaryTracker, "UTF-8")

                    val localTorrentUrl = if (cleanDownloadUrl.isNotBlank()) {
                        "http://127.0.0.1:$localPort/torrent?index=$exactFileIndex&url=$encUrl&token=$encTok&ghost=$ghostFlag"
                    } else {
                        null
                    }
                    val magnetFallbackUrl = if (resolvedHash.isNotBlank()) {
                        val trParams = buildString {
                            if (primaryTracker.startsWith("http")) {
                                val proxiedTr = if (ghostRatio) {
                                    "http://127.0.0.1:$localPort/announce?upstream=$encTracker&size=$totalSz"
                                } else {
                                    primaryTracker
                                }
                                append("&tr=").append(URLEncoder.encode(proxiedTr, "UTF-8"))
                            } else {
                                for (pubTr in PUBLIC_FAST_TRACKERS) {
                                    append("&tr=").append(URLEncoder.encode(pubTr, "UTF-8"))
                                }
                            }
                        }
                        "magnet:?xt=urn:btih:$resolvedHash&index=$exactFileIndex&dn=$encTitle$trParams"
                    } else {
                        null
                    }

                    val castFlag = if (castingNow) "1" else "0"
                    // Always use `.mp4` path suffix on `/cast/stream.mp4` so Chromecast's Default Media Receiver
                    // (`CastHelper.getMediaInfo` -> `MimeTypes.VIDEO_MP4`) never rejects the URL if the user taps
                    // the Cast button after `loadLinks()` already ran. For local ExoPlayer, `getVideoInterceptor`
                    // rewrites `/cast/stream.mp4` directly to TorrServer's real `.mkv`/`.mp4` file path anyway!
                    val streamExt = "mp4"
                    val sessionTs = System.currentTimeMillis()
                    val totalSourcesCount = sortedPayloads.size.coerceAtLeast(1)
                    val unifiedStreamUrl =
                        "http://$streamHost:$localPort/cast/stream.$streamExt?index=$exactFileIndex&pack=$packFlag&s=$targetSeason&e=$targetEp&cast=$castFlag&url=$encUrl&token=$encTok&ghost=$ghostFlag&hash=$encHash&dn=$encTitle&tr=$encTracker&sz=$totalSz&srcs=$totalSourcesCount&ts=$sessionTs&v=49"

                    if (!prewarmedTopStream) {
                        prewarmedTopStream = true
                        purgeStaleExoPlayerSimpleCache()
                        prewarmTorrServerStream(
                            localTorrentUrl = localTorrentUrl,
                            magnetFallbackUrl = magnetFallbackUrl,
                            exactIndex = exactFileIndex,
                            seasonNum = targetSeason,
                            epNum = targetEp,
                            knownHash = resolvedHash,
                            localPort = localPort
                        )
                    }

                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = baseContinuityName,
                            url = unifiedStreamUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = linkQuality
                        }
                    )
                } else {
                    val fallbackUrl = cleanDownloadUrl.ifBlank { stream.url.substringBefore("#index=") }
                    val isMagnet = fallbackUrl.startsWith("magnet:")
                    val sep = if (fallbackUrl.contains("?")) "&" else "?"
                    val indexedFallbackUrl = "${fallbackUrl}${sep}index=$exactFileIndex&north=1"
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = baseContinuityName,
                            url = indexedFallbackUrl,
                            type = if (isMagnet) ExtractorLinkType.MAGNET else ExtractorLinkType.TORRENT
                        ) {
                            this.quality = linkQuality
                        }
                    )
                }
            }

            // Fetch Danish / Nordic / English subtitles after TorrServer pre-warming has already started
            val firstWithImdb = sortedPayloads.firstOrNull { (it.imdbId ?: 0) > 0 }
            if (firstWithImdb != null) {
                fetchOpenSubtitles(
                    imdbId = firstWithImdb.imdbId,
                    seasonNum = firstWithImdb.seasonNum,
                    episodeNum = firstWithImdb.episodeIndex,
                    useLanIp = castingNow,
                    subtitleCallback = subtitleCallback
                )
            }

            true
        } catch (e: Exception) {
            NorthDebugLogger.logApiError("loadLinks", "Failed resolving links", e)
            e.printStackTrace()
            false
        }
    }

    /**
     * Overrides `MainAPI.getVideoInterceptor` so CloudStream's `CS3IPlayer.createVideoSource`:
     * 1. Bypasses `CronetDataSource` (hard 15s read timeout) and uses `OkHttpDataSource` instead.
     * 2. ALWAYS rewrites `/cast/stream.(mkv|mp4)` requests from local ExoPlayer directly to TorrServer's native
     *    Go `http.ServeContent` URL (`http://127.0.0.1:<torrPort>/stream/<fname>?link=<hash>&index=<id>&play`)
     *    with `CacheControl.FORCE_NETWORK`.
     * 3. Uses a responsive 35s initial read timeout + smart recovery on timeout/404/410/5xx: if TorrServer has
     *    0 peers or a stale/closed torrent, removes (`action: "rem"`) and re-adds it cleanly; if peers are actively
     *    downloading a large mid-file resume piece (e.g. 33:20), retries cleanly with a 60s timeout without
     *    discarding downloaded pieces.
     */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return Interceptor { chain ->
            val req = chain.request()
            val urlStr = req.url.toString()
            val nowBeforeResolve = System.currentTimeMillis()
            val idleBeforeReqMs = if (lastSuccessfulStreamResponseMs > 0L) {
                (nowBeforeResolve - lastSuccessfulStreamResponseMs).coerceAtLeast(0L)
            } else {
                Long.MAX_VALUE
            }

            // Defeat CloudStream's 20-minute RepoLinkGenerator cache reusing stale ExoPlayer SimpleCache spans
            // ONLY on a genuinely idle session start (never during an active mid-file timeline seek/scrub!).
            val rangeHeader = req.header("Range").orEmpty()
            val isStartRange = rangeHeader.isBlank() || rangeHeader.startsWith("bytes=0-", ignoreCase = true)
            if (isStartRange && idleBeforeReqMs > 20_000L && (nowBeforeResolve - lastSimpleCachePurgeMs) > 20_000L) {
                purgeStaleExoPlayerSimpleCache()
            }

            var activeReq = req
            var castQueryForRetry: String? = null
            var activeHash = ""
            var totalSourcesCount = 2
            val localPort = ensureLocalProxyServer()

            if (urlStr.contains("/cast/stream")) {
                val rawQuery = req.url.encodedQuery.orEmpty()
                castQueryForRetry = rawQuery
                totalSourcesCount = rawQuery.substringAfter("srcs=", "").substringBefore("&").toIntOrNull()?.coerceAtLeast(1) ?: 2
                val isSingleSource = totalSourcesCount <= 1
                val deadSwarmTtlMs = if (isSingleSource) 8_000L else 120_000L
                val queryHashHint = safeUrlDecode(rawQuery.substringAfter("hash=", "").substringBefore("&")).trim().lowercase(Locale.ROOT)
                val nowPre = System.currentTimeMillis()
                if (queryHashHint.isNotBlank() && (nowPre - (unplayableArchiveHashes[queryHashHint] ?: 0L)) < 600_000L) {
                    val msg = if (isSingleSource) {
                        "North: This release only contains archive files (.rar/.iso) and cannot be streamed."
                    } else {
                        "North: Archive-only release (.rar/.iso), trying next source..."
                    }
                    showDeadSwarmToastThrottled(queryHashHint, msg, postFollowUp = isSingleSource)
                    throw java.io.IOException("Archive-only release $queryHashHint")
                }
                if (queryHashHint.isNotBlank() && (nowPre - (deadSwarmHashes[queryHashHint] ?: 0L)) < deadSwarmTtlMs) {
                    val diag = lastTrackerDiagByHash[queryHashHint].orEmpty()
                    val suffix = if (diag.isNotBlank()) " ($diag)" else ""
                    val msg = if (isSingleSource) {
                        "North: No active seeders on NordicBytes$suffix"
                    } else {
                        "North: No active seeders$suffix, trying next source..."
                    }
                    showDeadSwarmToastThrottled(queryHashHint, msg, postFollowUp = isSingleSource)
                    throw java.io.IOException("No reachable seeders for torrent $queryHashHint")
                }
                val resolvedTarget = resolveDirectTorrStreamFromCastQuery(
                    rawQuery = rawQuery,
                    localPort = localPort,
                    forceReAdd = false
                )
                val directTorrUrl = resolvedTarget?.torrStreamUrl
                activeHash = resolvedTarget?.infoHash.orEmpty().ifBlank { queryHashHint }
                if (!directTorrUrl.isNullOrBlank()) {
                    activeReq = req.newBuilder()
                        .url(directTorrUrl)
                        .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
                        .header("Cache-Control", "no-store")
                        .build()
                } else {
                    val hKey = activeHash.lowercase(Locale.ROOT)
                    val isArchive = hKey.isNotBlank() && (System.currentTimeMillis() - (unplayableArchiveHashes[hKey] ?: 0L)) < 600_000L
                    val msg = if (isArchive) {
                        "North: This release only contains archive files (.rar/.iso)."
                    } else {
                        "North: Could not load torrent metadata from NordicBytes."
                    }
                    showDeadSwarmToastThrottled(hKey, msg, postFollowUp = isSingleSource)
                    throw java.io.IOException(msg)
                }
            } else if (urlStr.contains("/stream/")) {
                castQueryForRetry = torrUrlToCastQueryMap[urlStr]
                totalSourcesCount = castQueryForRetry?.substringAfter("srcs=", "")?.substringBefore("&")?.toIntOrNull()?.coerceAtLeast(1) ?: 2
                activeHash = urlStr.substringAfter("link=", "").substringBefore("&").trim()
                if (idleBeforeReqMs > 15_000L && activeHash.isNotBlank() && !castQueryForRetry.isNullOrBlank()) {
                    val base = resolveRunningTorrServerUrl()
                    val status = queryTorrServerStatus(base, activeHash)
                    if (!isTorrServerStatusHealthyForPort(status, localPort, requireFiles = true) ||
                        !isTorrServerSwarmLive(status, activeHash, idleBeforeReqMs)
                    ) {
                        clearCachedStateForHash(activeHash)
                        removeTorrServerHashSync(base, activeHash)
                        val refreshed = resolveDirectTorrStreamFromCastQuery(
                            rawQuery = castQueryForRetry,
                            localPort = localPort,
                            forceReAdd = true
                        )
                        val freshUrl = refreshed?.torrStreamUrl
                        if (!freshUrl.isNullOrBlank()) {
                            activeHash = refreshed.infoHash
                            activeReq = req.newBuilder()
                                .url(freshUrl)
                                .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
                                .header("Cache-Control", "no-store")
                                .build()
                        } else {
                            activeReq = req.newBuilder()
                                .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
                                .header("Cache-Control", "no-store")
                                .build()
                        }
                    } else {
                        activeReq = req.newBuilder()
                            .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
                            .header("Cache-Control", "no-store")
                            .build()
                    }
                } else {
                    activeReq = req.newBuilder()
                        .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
                        .header("Cache-Control", "no-store")
                        .build()
                }
            }

            val isSingleSource = totalSourcesCount <= 1
            val deadSwarmTtlMs = if (isSingleSource) 8_000L else 120_000L
            val hashLower = activeHash.lowercase(Locale.ROOT)
            val nowForCheck = System.currentTimeMillis()
            val knownDeadSwarm = hashLower.isNotBlank() &&
                (nowForCheck - (deadSwarmHashes[hashLower] ?: 0L)) < deadSwarmTtlMs
            if (knownDeadSwarm) {
                throw java.io.IOException("No reachable seeders for torrent $hashLower")
            }

            val knownNoExternalPeers = hashLower.isNotBlank() &&
                (nowForCheck - (noExternalPeersHashes[hashLower] ?: 0L)) < 60_000L

            // When a season only has 1 source, give TCP + uTP + UPnP + IPv6 a full 28s initial read window
            // because there is no alternate source to switch to. Only multi-source streams fast-fail in 5-10s!
            val initialReadSec = when {
                isSingleSource -> 28
                knownNoExternalPeers -> 10
                else -> 18
            }
            val initialChain = chain
                .withConnectTimeout(15, TimeUnit.SECONDS)
                .withReadTimeout(initialReadSec, TimeUnit.SECONDS)
                .withWriteTimeout(25, TimeUnit.SECONDS)

            val retryChain = chain
                .withConnectTimeout(20, TimeUnit.SECONDS)
                .withReadTimeout(if (!isSingleSource && knownNoExternalPeers) 12 else 60, TimeUnit.SECONDS)
                .withWriteTimeout(60, TimeUnit.SECONDS)

            fun isClientCancellation(err: Throwable): Boolean {
                if (chain.call().isCanceled() || Thread.currentThread().isInterrupted) return true
                if (err is java.io.InterruptedIOException && err !is SocketTimeoutException) return true
                val msg = err.message.orEmpty().lowercase(Locale.ROOT)
                return msg.contains("canceled") ||
                    msg.contains("cancelled") ||
                    msg.contains("socket closed") ||
                    msg.contains("stream was reset") ||
                    msg.contains("closed")
            }

            fun retryWithFreshReAdd(): okhttp3.Response? {
                if (chain.call().isCanceled()) return null
                val retryQuery = castQueryForRetry?.takeIf { it.isNotBlank() } ?: return null
                val portNow = ensureLocalProxyServer()
                clearNorthRepoLinkCache()
                if (activeHash.isNotBlank()) {
                    clearCachedStateForHash(activeHash)
                    removeTorrServerHashSync(resolveRunningTorrServerUrl(), activeHash)
                }
                if (chain.call().isCanceled()) return null
                val refreshedTarget = resolveDirectTorrStreamFromCastQuery(
                    rawQuery = retryQuery,
                    localPort = portNow,
                    forceReAdd = true
                )
                val refreshedTorrUrl = refreshedTarget?.torrStreamUrl?.takeIf { it.isNotBlank() } ?: return null
                val retryReq = req.newBuilder()
                    .url(refreshedTorrUrl)
                    .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
                    .header("Cache-Control", "no-store")
                    .build()
                return retryChain.proceed(retryReq)
            }

            var response = try {
                initialChain.proceed(activeReq)
            } catch (ioe: java.io.IOException) {
                // CRITICAL: Rapid timeline scrubbing (e.g. 00:00 -> 27:00) cancels the prior OkHttp call!
                // Never treat a client seek/scrub cancellation as a dead torrent or call rem+add in TorrServer!
                if (isClientCancellation(ioe)) {
                    throw ioe
                }
                val base = resolveRunningTorrServerUrl()
                val status = if (activeHash.isNotBlank()) queryTorrServerStatus(base, activeHash) else null
                val healthy = isTorrServerStatusHealthyForPort(status, localPort, requireFiles = true)
                val activePeers = (status?.optInt("active_peers", 0) ?: 0) +
                    (status?.optInt("connected_seeders", 0) ?: 0)
                val dlSpeed = status?.optDouble("download_speed", 0.0) ?: 0.0
                val preloaded = status?.optLong("preloaded_bytes", 0L) ?: 0L
                val stillNoExternalPeers = hashLower.isNotBlank() &&
                    (System.currentTimeMillis() - (noExternalPeersHashes[hashLower] ?: 0L)) < 60_000L
                if (healthy && (activePeers > 0 || preloaded > 0L) && (dlSpeed > 0.0 || preloaded > 0L)) {
                    // Torrent is healthy and actively downloading a large piece (e.g. resume seek) -> retry smoothly
                    try {
                        retryChain.proceed(activeReq)
                    } catch (retryErr: java.io.IOException) {
                        if (isClientCancellation(retryErr)) throw retryErr
                        retryWithFreshReAdd() ?: throw ioe
                    }
                } else if (stillNoExternalPeers && dlSpeed <= 0.0 && preloaded == 0L) {
                    // Tracker returned 0 external seeders (only self-IP, empty, or error) and 0 B/s downloading;
                    // mark dead and fail fast so CloudStream automatically switches to the next seeded source.
                    if (hashLower.isNotBlank()) {
                        deadSwarmHashes[hashLower] = System.currentTimeMillis()
                    }
                    val diag = lastTrackerDiagByHash[hashLower].orEmpty().ifBlank { "0 peers" }
                    val msg = if (isSingleSource) {
                        "North: 0 active seeders on NordicBytes ($diag)"
                    } else {
                        "North: 0 active seeders ($diag), trying next source..."
                    }
                    showDeadSwarmToastThrottled(hashLower, msg, postFollowUp = isSingleSource)
                    throw ioe
                } else {
                    retryWithFreshReAdd() ?: throw ioe
                }
            }

            if ((response.code == 404 || response.code == 410 || response.code >= 500) && !castQueryForRetry.isNullOrBlank()) {
                try {
                    response.close()
                } catch (_: Throwable) {
                }
                val retried = retryWithFreshReAdd()
                if (retried != null) {
                    response = retried
                }
            }

            // Go's http.ServeContent returns 200/206 headers in <2ms BEFORE downloading any torrent bytes!
            // For multi-source streams only, fail fast after 5s when tracker returned 0 external peers so
            // CloudStream automatically jumps to source #2. For single-source streams, allow full 28s TCP/uTP/UPnP warmup!
            if (!isSingleSource && response.code in 200..299 && !req.method.equals("HEAD", ignoreCase = true) && hashLower.isNotBlank()) {
                val noExtTs = noExternalPeersHashes[hashLower] ?: 0L
                val addedTs = torrentAddedTimestampMs[hashLower] ?: 0L
                val nowPost = System.currentTimeMillis()
                if (noExtTs > 0L && (nowPost - noExtTs) < 60_000L && addedTs > 0L && (nowPost - addedTs) >= 5_000L) {
                    val st = queryTorrServerStatus(resolveRunningTorrServerUrl(), activeHash)
                    val connPeers = (st?.optInt("active_peers", 0) ?: 0) + (st?.optInt("connected_seeders", 0) ?: 0)
                    val dlSpeed = st?.optDouble("download_speed", 0.0) ?: 0.0
                    val usefulBytes = st?.optLong("bytes_read_useful_data", 0L) ?: 0L
                    val preloaded = st?.optLong("preloaded_bytes", 0L) ?: 0L
                    if ((connPeers == 0 || dlSpeed <= 0.0) && usefulBytes == 0L && preloaded == 0L) {
                        try {
                            response.close()
                        } catch (_: Throwable) {
                        }
                        deadSwarmHashes[hashLower] = nowPost
                        val diag = lastTrackerDiagByHash[hashLower].orEmpty().ifBlank { "0 peers" }
                        showDeadSwarmToastThrottled(
                            hashLower,
                            "North: 0 active seeders ($diag), trying next source...",
                            postFollowUp = false
                        )
                        throw java.io.IOException("No active external seeders for torrent $hashLower ($diag)")
                    }
                }
            }

            if (response.code in 200..299 && !req.method.equals("HEAD", ignoreCase = true)) {
                val rawBody = response.body
                if (rawBody != null) {
                    val trackedHashLower = hashLower
                    val trackedActiveHash = activeHash
                    val trackedIsSingleSource = isSingleSource
                    val trackedBody = object : okhttp3.ResponseBody() {
                        private var deliveredAnyByte = false
                        private val wrappedSource: okio.BufferedSource = object : okio.ForwardingSource(rawBody.source()) {
                            override fun read(sink: okio.Buffer, byteCount: Long): Long {
                                return try {
                                    val n = super.read(sink, byteCount)
                                    if (n > 0L) {
                                        deliveredAnyByte = true
                                        val okNow = System.currentTimeMillis()
                                        lastKeepAliveTouchMs = okNow
                                        lastSuccessfulStreamResponseMs = okNow
                                        if (trackedHashLower.isNotBlank()) {
                                            zeroPeerTimeoutCount.remove(trackedHashLower)
                                            deadSwarmHashes.remove(trackedHashLower)
                                        }
                                    }
                                    n
                                } catch (bodyErr: java.io.IOException) {
                                    if (isClientCancellation(bodyErr)) {
                                        throw bodyErr
                                    }
                                    if (!deliveredAnyByte && bodyErr is SocketTimeoutException && trackedHashLower.isNotBlank()) {
                                        // Real stall timeout with zero media bytes delivered after 206 headers:
                                        // check whether TorrServer is actively downloading or stalled at 0 B/s.
                                        val base = resolveRunningTorrServerUrl()
                                        val st = queryTorrServerStatus(base, trackedActiveHash)
                                        val connPeers = (st?.optInt("active_peers", 0) ?: 0) +
                                            (st?.optInt("connected_seeders", 0) ?: 0)
                                        val totalPeers = st?.optInt("total_peers", 0) ?: 0
                                        val dlSpeed = st?.optDouble("download_speed", 0.0) ?: 0.0
                                        val preloaded = st?.optLong("preloaded_bytes", 0L) ?: 0L
                                        val usefulBytes = st?.optLong("bytes_read_useful_data", 0L) ?: 0L
                                        val nowErr = System.currentTimeMillis()
                                        val trackerHadNoExt = (nowErr - (noExternalPeersHashes[trackedHashLower] ?: 0L)) < 60_000L
                                        val diag = lastTrackerDiagByHash[trackedHashLower].orEmpty().ifBlank { "no tracker reply" }
                                        if (dlSpeed <= 0.0 && preloaded == 0L && usefulBytes == 0L) {
                                            val prevCount = zeroPeerTimeoutCount[trackedHashLower] ?: 0
                                            zeroPeerTimeoutCount[trackedHashLower] = prevCount + 1
                                            lastSuccessfulStreamResponseMs = 0L
                                            torrentAddedTimestampMs.remove(trackedHashLower)
                                            clearCachedStateForHash(trackedActiveHash)
                                            removeTorrServerHashSync(base, trackedActiveHash)
                                            if (trackerHadNoExt || prevCount >= 1) {
                                                // Either tracker already returned 0 external peers/error, OR we already
                                                // retried once with fresh rem + add + fresh peer_id and STILL 0 B/s!
                                                // Mark dead so ExoPlayer stops spinning and switches to next source.
                                                val isAlreadyLocked = diag.contains("already", ignoreCase = true)
                                                if (!isAlreadyLocked) {
                                                    deadSwarmHashes[trackedHashLower] = nowErr
                                                }
                                                val toastMsg = when {
                                                    isAlreadyLocked ->
                                                        "North: NordicBytes reports an active session from a prior crash ($diag). Wait a few minutes for it to clear."
                                                    trackerHadNoExt -> if (trackedIsSingleSource) {
                                                        "North: 0 active seeders on NordicBytes ($diag)"
                                                    } else {
                                                        "North: 0 active seeders ($diag), trying next source..."
                                                    }
                                                    else ->
                                                        "North: No seeders responding (peers $connPeers/$totalPeers, $diag)"
                                                }
                                                NorthDebugLogger.log("WARN", "STREAM_STALL", "Stream stalled ($toastMsg)")
                                                showDeadSwarmToastThrottled(
                                                    trackedHashLower,
                                                    toastMsg,
                                                    postFollowUp = trackedIsSingleSource
                                                )
                                            }
                                        }
                                    }
                                    throw bodyErr
                                }
                            }
                        }.buffer()

                        override fun contentType(): okhttp3.MediaType? = rawBody.contentType()
                        override fun contentLength(): Long = rawBody.contentLength()
                        override fun source(): okio.BufferedSource = wrappedSource
                        override fun close() {
                            try {
                                super.close()
                            } finally {
                                try {
                                    rawBody.close()
                                } catch (_: Throwable) {
                                }
                            }
                        }
                    }
                    response = response.newBuilder().body(trackedBody).build()
                }
            }
            response
        }
    }
}
