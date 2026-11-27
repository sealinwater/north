package com.lagradost.cloudstream3.plugins.nordicbytes

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Local rotating debug logger + automatic crash & fatal-error uploader for North.
 *
 * - Captures API exceptions, tracker diagnostics, TorrServer recovery events, and player state / seek transitions
 *   into a persistent rotating debug file (`filesDir/north_debug.log`, max 256 KB).
 * - Redacts all API tokens, passkeys, Authorization headers, and passwords before writing to disk or uploading.
 * - Installs a chained `UncaughtExceptionHandler` that snapshots the crash + recent debug log to
 *   `filesDir/north_pending_crash.json` and automatically uploads it immediately (best effort) and again on the
 *   next startup until acknowledged by the North Companion API (`POST /api/crash-log`).
 */
object NorthDebugLogger {

    private const val PLUGIN_VERSION = 48
    private const val LOG_FILE_NAME = "north_debug.log"
    private const val PREV_LOG_FILE_NAME = "north_debug.prev.log"
    private const val PENDING_CRASH_FILE_NAME = "north_pending_crash.json"
    private const val MAX_LOG_FILE_BYTES = 256 * 1024L
    private const val MAX_RING_LINES = 220
    private const val ERROR_REPORT_THROTTLE_MS = 60_000L

    private val lock = Any()
    private val ringBuffer = ArrayDeque<String>(MAX_RING_LINES + 8)
    private val initialized = AtomicBoolean(false)
    private val crashHandlerInstalled = AtomicBoolean(false)
    private val lastErrorUploadBySig = ConcurrentHashMap<String, Long>()

    @Volatile
    private var logDir: File? = null

    @Volatile
    private var uploadBaseUrl: String = NordicBytesProvider.DEFAULT_PROXY_API_URL

    @Volatile
    private var lastStateSummary: String = "idle"

    @Volatile
    var lastValidPositionMs: Long = 0L

    @Volatile
    var lastKnownDurationMs: Long = 0L

    @Volatile
    var lastRequestedSeekMs: Long = -1L

    @Volatile
    var lastActiveTorrentHash: String = ""

    private val isoFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat {
            return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }
    }

    private val tokenParamRegex = Regex("""(?i)(api_token|token|passkey|password|pass|key)=([^&\s"']+)""")
    private val bearerRegex = Regex("""(?i)(Bearer\s+)([A-Za-z0-9._\-+/=]+)""")
    private val announcePasskeyRegex = Regex("""(?i)(/announce/)([0-9a-f]{16,})""")
    private val torrentPasskeyRegex = Regex("""(?i)(/torrents/download/\d+\.)([0-9a-f]{16,})""")

    fun redactSecrets(raw: String): String {
        if (raw.isBlank()) return raw
        var s = raw
        s = tokenParamRegex.replace(s, "$1=[REDACTED]")
        s = bearerRegex.replace(s, "$1[REDACTED]")
        s = announcePasskeyRegex.replace(s, "$1[REDACTED]")
        s = torrentPasskeyRegex.replace(s, "$1[REDACTED]")
        return s
    }

    fun init(context: Context, proxyBaseUrl: String) {
        val appCtx = context.applicationContext ?: context
        logDir = appCtx.filesDir
        updateUploadBaseUrl(proxyBaseUrl)

        if (initialized.compareAndSet(false, true)) {
            loadTailFromDiskIntoRing()
            log("INFO", "LIFECYCLE", "NorthDebugLogger initialized (v$PLUGIN_VERSION, Android ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER} ${Build.MODEL})")
        }

        installUncaughtCrashHandler()
        flushPendingCrashReportAsync()
    }

    fun updateUploadBaseUrl(proxyBaseUrl: String) {
        val cleaned = proxyBaseUrl.trim().trimEnd('/')
        if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) {
            uploadBaseUrl = cleaned
        }
    }

    fun getDebugLogFile(): File? {
        val dir = logDir ?: return null
        return File(dir, LOG_FILE_NAME)
    }

    fun log(level: String, category: String, message: String, error: Throwable? = null) {
        val ts = try {
            isoFormat.get()?.format(Date()) ?: System.currentTimeMillis().toString()
        } catch (_: Throwable) {
            System.currentTimeMillis().toString()
        }
        val safeMsg = redactSecrets(message).replace("\r", " ").replace("\n", " | ").take(900)
        val errSuffix = if (error != null) {
            val cls = error.javaClass.simpleName
            val em = redactSecrets(error.message.orEmpty()).replace("\r", " ").replace("\n", " ").take(240)
            " :: $cls($em)"
        } else {
            ""
        }
        val line = "$ts | $level | $category | $safeMsg$errSuffix"

        synchronized(lock) {
            while (ringBuffer.size >= MAX_RING_LINES) {
                ringBuffer.removeFirst()
            }
            ringBuffer.addLast(line)
            appendLineToDiskLocked(line)
        }
    }

    fun logStateTransition(state: String, detail: String = "") {
        lastStateSummary = if (detail.isBlank()) state else "$state ($detail)"
        log("STATE", "PLAYER", if (detail.isBlank()) state else "$state | $detail")
    }

    fun logApiError(endpoint: String, message: String, error: Throwable? = null) {
        log("ERROR", "API", "$endpoint -> $message", error)
    }

    fun logTrackerEvent(hash: String, message: String) {
        val shortHash = hash.take(10)
        if (hash.isNotBlank()) lastActiveTorrentHash = shortHash
        log("INFO", "TRACKER", "[$shortHash] $message")
    }

    fun logSeekEvent(message: String) {
        log("STATE", "SEEK", message)
    }

    fun getRecentLines(maxLines: Int = 150): List<String> {
        synchronized(lock) {
            val all = ringBuffer.toList()
            return if (all.size <= maxLines) all else all.subList(all.size - maxLines, all.size)
        }
    }

    private fun loadTailFromDiskIntoRing() {
        val file = getDebugLogFile() ?: return
        if (!file.exists() || !file.isFile) return
        try {
            val lines = file.readLines(Charsets.UTF_8)
            val start = (lines.size - 80).coerceAtLeast(0)
            synchronized(lock) {
                for (i in start until lines.size) {
                    if (ringBuffer.size >= MAX_RING_LINES) ringBuffer.removeFirst()
                    ringBuffer.addLast(lines[i])
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun appendLineToDiskLocked(line: String) {
        val dir = logDir ?: return
        try {
            if (!dir.exists()) dir.mkdirs()
            val logFile = File(dir, LOG_FILE_NAME)
            if (logFile.exists() && logFile.length() >= MAX_LOG_FILE_BYTES) {
                val prevFile = File(dir, PREV_LOG_FILE_NAME)
                if (prevFile.exists()) prevFile.delete()
                logFile.renameTo(prevFile)
            }
            FileWriter(logFile, true).use { fw ->
                fw.append(line).append('\n')
            }
        } catch (_: Throwable) {
        }
    }

    private fun formatStackTrace(throwable: Throwable): String {
        return try {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            redactSecrets(sw.toString()).take(6000)
        } catch (_: Throwable) {
            redactSecrets("${throwable.javaClass.name}: ${throwable.message}")
        }
    }

    private fun buildCrashPayload(
        reportType: String,
        threadName: String,
        throwable: Throwable?,
        customSummary: String
    ): JSONObject {
        val recent = getRecentLines(160)
        val logsArr = JSONArray()
        for (l in recent) logsArr.put(l)

        val playerState = JSONObject().apply {
            put("stateSummary", redactSecrets(lastStateSummary).take(240))
            put("lastValidPositionMs", lastValidPositionMs)
            put("lastKnownDurationMs", lastKnownDurationMs)
            put("lastRequestedSeekMs", lastRequestedSeekMs)
            put("activeHashPrefix", lastActiveTorrentHash.take(12))
        }

        return JSONObject().apply {
            put("pluginVersion", PLUGIN_VERSION)
            put("reportType", reportType)
            put("timestampUtc", isoFormat.get()?.format(Date()) ?: System.currentTimeMillis().toString())
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("androidRelease", Build.VERSION.RELEASE ?: "")
            put("androidSdk", Build.VERSION.SDK_INT)
            put("threadName", threadName.take(80))
            put("summary", redactSecrets(customSummary).take(400))
            put("exceptionClass", throwable?.javaClass?.name.orEmpty().take(160))
            put("exceptionMessage", redactSecrets(throwable?.message.orEmpty()).take(400))
            put("stackTrace", if (throwable != null) formatStackTrace(throwable) else "")
            put("playerState", playerState)
            put("recentLogs", logsArr)
        }
    }

    private fun installUncaughtCrashHandler() {
        if (!crashHandlerInstalled.compareAndSet(false, true)) return
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                log("FATAL", "CRASH", "Uncaught exception on thread ${thread.name}", throwable)
                val payload = buildCrashPayload(
                    reportType = "uncaught_crash",
                    threadName = thread.name,
                    throwable = throwable,
                    customSummary = "Uncaught ${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}"
                )
                val dir = logDir
                if (dir != null) {
                    val pendingFile = File(dir, PENDING_CRASH_FILE_NAME)
                    pendingFile.writeText(payload.toString(), Charsets.UTF_8)
                }
                // Best-effort quick synchronous upload (capped at 1.8s so OS crash dialog is not delayed)
                val uploaded = AtomicBoolean(false)
                val t = thread(isDaemon = true, name = "North-CrashUploadImmediate") {
                    if (postCrashPayloadSync(payload, timeoutMs = 1800)) {
                        uploaded.set(true)
                        try {
                            logDir?.let { File(it, PENDING_CRASH_FILE_NAME).delete() }
                        } catch (_: Throwable) {
                        }
                    }
                }
                try {
                    t.join(1900L)
                } catch (_: Throwable) {
                }
            } catch (_: Throwable) {
            } finally {
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    /**
     * Automatically sends a diagnostic crash/error report for handled fatal player or tracker failures
     * (such as IllegalSeekPositionException, IllegalStateException, or persistent tracker rejections),
     * throttled by signature so it never floods the server.
     */
    fun reportHandledFatalAsync(
        category: String,
        summary: String,
        throwable: Throwable? = null,
        bypassThrottle: Boolean = false
    ) {
        val sig = "$category:${throwable?.javaClass?.simpleName ?: summary.take(48)}"
        val now = System.currentTimeMillis()
        if (!bypassThrottle) {
            val prev = lastErrorUploadBySig[sig] ?: 0L
            if (now - prev < ERROR_REPORT_THROTTLE_MS) return
            lastErrorUploadBySig[sig] = now
        }
        log("ERROR", category, summary, throwable)
        val payload = buildCrashPayload(
            reportType = "handled_error",
            threadName = Thread.currentThread().name,
            throwable = throwable,
            customSummary = "$category: $summary"
        )
        thread(isDaemon = true, name = "North-ErrorReportUpload") {
            val ok = postCrashPayloadSync(payload, timeoutMs = 5000)
            if (!ok) {
                try {
                    logDir?.let { dir ->
                        val pending = File(dir, PENDING_CRASH_FILE_NAME)
                        if (!pending.exists()) {
                            pending.writeText(payload.toString(), Charsets.UTF_8)
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun sendManualDiagnosticAsync(onComplete: (Boolean) -> Unit) {
        val payload = buildCrashPayload(
            reportType = "manual_debug_log",
            threadName = Thread.currentThread().name,
            throwable = null,
            customSummary = "Manual debug log upload from North Settings (v$PLUGIN_VERSION)"
        )
        thread(isDaemon = true, name = "North-ManualDebugUpload") {
            val ok = postCrashPayloadSync(payload, timeoutMs = 6000)
            onComplete(ok)
        }
    }

    fun flushPendingCrashReportAsync() {
        val dir = logDir ?: return
        val pendingFile = File(dir, PENDING_CRASH_FILE_NAME)
        if (!pendingFile.exists() || !pendingFile.isFile) return

        thread(isDaemon = true, name = "North-CrashFlush") {
            try {
                val raw = pendingFile.readText(Charsets.UTF_8).trim()
                if (!raw.startsWith("{")) {
                    pendingFile.delete()
                    return@thread
                }
                val payload = JSONObject(raw)
                if (postCrashPayloadSync(payload, timeoutMs = 6000)) {
                    pendingFile.delete()
                    log("INFO", "CRASH_SYNC", "Uploaded pending crash report to North API successfully.")
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun postCrashPayloadSync(payload: JSONObject, timeoutMs: Int): Boolean {
        val candidates = LinkedHashSet<String>()
        val configured = uploadBaseUrl.trim().trimEnd('/')
        if (configured.startsWith("http://") || configured.startsWith("https://")) {
            candidates.add("$configured/api/crash-log")
        }
        val fallback = NordicBytesProvider.DEFAULT_PROXY_API_URL.trim().trimEnd('/')
        if (fallback.startsWith("http://") || fallback.startsWith("https://")) {
            candidates.add("$fallback/api/crash-log")
        }

        val jsonBody = payload.toString()
        for (endpoint in candidates) {
            try {
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("User-Agent", "North-Plugin/$PLUGIN_VERSION")
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(jsonBody)
                }
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299) {
                    return true
                }
            } catch (_: Throwable) {
            }
        }
        return false
    }
}
