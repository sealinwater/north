package com.lagradost.cloudstream3.plugins.nordicbytes

import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.PluginManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

@CloudstreamPlugin
class NordicBytesPlugin : Plugin() {

    private fun purgeOtherNorthProviders(keepProvider: MainAPI?) {
        try {
            val staleProviders = synchronized(APIHolder.allProviders) {
                APIHolder.allProviders.filter { api ->
                    api !== keepProvider && (
                        api.name.equals("North", ignoreCase = true) ||
                            api.name.equals("NordicBytes", ignoreCase = true) ||
                            api.name.equals("NordicBytesPlugin", ignoreCase = true) ||
                            api.mainUrl.contains("nordicbytes.org", ignoreCase = true)
                        )
                }
            }
            for (stale in staleProviders) {
                try {
                    APIHolder.removePluginMapping(stale)
                } catch (_: Throwable) {
                    synchronized(APIHolder.allProviders) {
                        APIHolder.allProviders.remove(stale)
                    }
                }
            }
            if (keepProvider != null) {
                val existsInAll = synchronized(APIHolder.allProviders) {
                    APIHolder.allProviders.any { it === keepProvider }
                }
                if (!existsInAll) {
                    APIHolder.addPluginMapping(keepProvider)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun deleteLegacyDuplicatePluginFiles(appContext: Context) {
        thread(isDaemon = true) {
            try {
                // 1. Cleanly remove any legacy NordicBytesPlugin record from CloudStream's PluginManager DataStore
                val onlinePlugins = PluginManager.getPluginsOnline()
                val hasNorth = onlinePlugins.any { it.internalName.equals("North", ignoreCase = true) }
                if (hasNorth) {
                    for (pd in onlinePlugins) {
                        if (pd.internalName.equals("NordicBytesPlugin", ignoreCase = true) ||
                            pd.internalName.equals("NordicBytes", ignoreCase = true)
                        ) {
                            try {
                                runBlocking {
                                    PluginManager.deletePlugin(File(pd.filePath))
                                }
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }

                // 2. Also delete any leftover NordicBytesPlugin.cs3 / NordicBytes.cs3 file on disk when North.cs3 exists
                val rootDirs = listOfNotNull(
                    appContext.filesDir,
                    appContext.filesDir?.parentFile
                )
                for (root in rootDirs) {
                    val allCs3 = root.walkTopDown().maxDepth(4).filter {
                        it.isFile && it.name.endsWith(".cs3", ignoreCase = true)
                    }.toList()
                    val hasNorthCs3 = allCs3.any { it.name.equals("North.cs3", ignoreCase = true) }
                    if (hasNorthCs3) {
                        for (file in allCs3) {
                            if (file.name.equals("NordicBytesPlugin.cs3", ignoreCase = true) ||
                                file.name.equals("NordicBytes.cs3", ignoreCase = true)
                            ) {
                                try {
                                    file.delete()
                                } catch (_: Throwable) {
                                }
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    override fun load(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences("NordicBytesPrefs", Context.MODE_PRIVATE)
        NordicBytesProvider.torrentsCacheDirPath = "${appContext.cacheDir.path}/torrents"
        NordicBytesProvider.northIntroCacheFilePath = "${appContext.cacheDir.path}/north_intro.mp4"
        NordicBytesProvider.isSkipIntrosEnabled = prefs.getBoolean("skip_intros", true)
        NordicBytesProvider.playNorthIntroEnabled = prefs.getBoolean("play_north_intro", true)
        NordicBytesProvider.proxyApiBaseUrl = (prefs.getString("proxy_api_url", NordicBytesProvider.DEFAULT_PROXY_API_URL)
            ?: NordicBytesProvider.DEFAULT_PROXY_API_URL).trim().trimEnd('/')
        if (prefs.getInt("exo_cache_purged_version", 0) < 33) {
            try {
                File(appContext.cacheDir, "exoplayer").deleteRecursively()
                File(appContext.cacheDir, "http_cache").deleteRecursively()
                File(appContext.cacheDir, "north_intro.mp4").delete()
            } catch (_: Throwable) {
            }
            prefs.edit().putInt("exo_cache_purged_version", 33).apply()
        }
        NordicBytesProvider.purgeStaleExoPlayerSimpleCache()
        NordicBytesProvider.registerNorthSkipApi()
        NordicBytesProvider.ensureLocalProxyServer()
        NordicBytesProvider.precacheNorthIntroAsync()

        // Remove any stale v13 NordicBytesPlugin provider that loaded before North.cs3
        purgeOtherNorthProviders(null)

        val currentProvider = NordicBytesProvider(prefs)
        registerMainAPI(currentProvider)

        // Ensure no other plugin file overwrites our provider after startup
        purgeOtherNorthProviders(currentProvider)
        Handler(Looper.getMainLooper()).postDelayed({
            purgeOtherNorthProviders(currentProvider)
        }, 600L)
        Handler(Looper.getMainLooper()).postDelayed({
            purgeOtherNorthProviders(currentProvider)
        }, 2000L)

        deleteLegacyDuplicatePluginFiles(appContext)

        openSettings = { ctx ->
            var current: Context? = ctx
            var activity: AppCompatActivity? = null
            while (current is ContextWrapper) {
                if (current is AppCompatActivity) {
                    activity = current
                    break
                }
                current = current.baseContext
            }

            val uiContext = activity ?: ctx
            showSettingsDialog(uiContext)
        }
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    private fun restartApp(context: Context) {
        val packageManager = context.packageManager
        val intent = packageManager.getLaunchIntentForPackage(context.packageName)
        val componentName = intent?.component
        if (componentName != null) {
            val restartIntent = Intent.makeRestartActivityTask(componentName)
            context.startActivity(restartIntent)
            Runtime.getRuntime().exit(0)
        }
    }

    private fun openApiConnection(urlStr: String, apiToken: String): HttpURLConnection {
        val url = URL(urlStr)
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $apiToken")
            setRequestProperty("Accept", "application/json")
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            )
            connectTimeout = 8000
            readTimeout = 8000
        }
    }

    private fun buildConnectedSummary(
        ghostRatio: Boolean,
        freeleechOnly: Boolean,
        hasSeedBridge: Boolean,
        flCount: Int,
        doubleUpCount: Int
    ): String {
        val ghostLabel = if (ghostRatio) "Active (0 B Download • Auto-Clear Leeching)" else "Disabled"
        val modeLabel = if (freeleechOnly) "100% Freeleech Only" else "All Releases (Freeleech & 2x UP #1)"
        val bridgeLabel = if (hasSeedBridge) "Active (Auto-forwards played torrents)" else "Optional (Configure below)"
        return buildString {
            append("✅ API Token Active & Connected to NordicBytes\n")
            append("👻 Ghost Ratio Proxy: $ghostLabel\n")
            append("🎁 Feed Filter: $modeLabel\n")
            if (flCount > 0 || doubleUpCount > 0) {
                append("🔥 Feed Stats: $flCount Freeleech • $doubleUpCount Double Upload\n")
            }
            append("🌱 48h Auto-Seed Bridge: $bridgeLabel")
        }
    }

    private fun fetchLiveUserStats(
        apiToken: String,
        ghostRatio: Boolean,
        freeleechOnly: Boolean,
        hasSeedBridge: Boolean,
        onResult: (String) -> Unit
    ) {
        if (apiToken.isBlank()) {
            onResult(
                "⚠ ACCOUNT API KEY REQUIRED: North requires a NordicBytes account & API Key to work.\n" +
                    "1) Apply at https://nordicbytes.org/application (see full guide below).\n" +
                    "2) Copy your API Key from Profile → Settings → API Key on nordicbytes.org.\n" +
                    "3) Paste your token below and tap Save & Restart CloudStream."
            )
            return
        }

        if (NordicBytesProvider.lastSuccessfulApiTimestamp > 0L) {
            onResult(
                buildConnectedSummary(
                    ghostRatio = ghostRatio,
                    freeleechOnly = freeleechOnly,
                    hasSeedBridge = hasSeedBridge,
                    flCount = NordicBytesProvider.lastObservedFreeleechCount,
                    doubleUpCount = NordicBytesProvider.lastObservedDoubleUpCount
                )
            )
            return
        }

        thread {
            try {
                val encodedToken = URLEncoder.encode(apiToken, "UTF-8")
                val filterUrl = "https://nordicbytes.org/api/torrents/filter?categories[]=1&categories[]=2&perPage=10&api_token=$encodedToken"
                val filterConn = openApiConnection(filterUrl, apiToken)
                val filterCode = filterConn.responseCode
                if (filterCode in 200..299) {
                    val body = BufferedReader(InputStreamReader(filterConn.inputStream)).use { it.readText() }
                    filterConn.disconnect()
                    val root = JSONObject(body)
                    val dataArr = root.optJSONArray("data")
                    var flCount = 0
                    var doubleUpCount = 0
                    if (dataArr != null) {
                        for (i in 0 until dataArr.length()) {
                            val item = dataArr.optJSONObject(i) ?: continue
                            val attrs = item.optJSONObject("attributes") ?: item
                            val fl = attrs.optString("freeleech", "0%")
                            if (fl.isNotBlank() && fl != "0%" && fl != "0") flCount++
                            if (attrs.optBoolean("double_upload", false)) doubleUpCount++
                        }
                    }
                    NordicBytesProvider.lastSuccessfulApiTimestamp = System.currentTimeMillis()
                    val summary = buildConnectedSummary(ghostRatio, freeleechOnly, hasSeedBridge, flCount, doubleUpCount)
                    Handler(Looper.getMainLooper()).post { onResult(summary) }
                } else if (filterCode == 429) {
                    filterConn.disconnect()
                    val summary = buildConnectedSummary(
                        ghostRatio,
                        freeleechOnly,
                        hasSeedBridge,
                        NordicBytesProvider.lastObservedFreeleechCount,
                        NordicBytesProvider.lastObservedDoubleUpCount
                    )
                    Handler(Looper.getMainLooper()).post { onResult(summary) }
                } else {
                    filterConn.disconnect()
                    Handler(Looper.getMainLooper()).post {
                        onResult("⚠ API Token rejected (HTTP $filterCode). Please check your NordicBytes API token.")
                    }
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    onResult("⚠ Connection error: ${e.localizedMessage ?: "Could not reach nordicbytes.org"}")
                }
            }
        }
    }

    private fun showSettingsDialog(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences("NordicBytesPrefs", Context.MODE_PRIVATE)
        val currentToken = prefs.getString("api_token", "") ?: ""
        val currentGhostRatio = prefs.getBoolean("ghost_ratio", true)
        val currentFreeleechOnly = prefs.getBoolean("freeleech_only", false)
        val currentSkipIntros = prefs.getBoolean("skip_intros", true)
        val currentPlayNorthIntro = prefs.getBoolean("play_north_intro", true)
        val currentAutoSubtitles = prefs.getBoolean("auto_subtitles", true)
        val currentPrefLanguage = prefs.getString("pref_language", "DA") ?: "DA"
        val currentProxyApiUrl = prefs.getString("proxy_api_url", NordicBytesProvider.DEFAULT_PROXY_API_URL)
            ?: NordicBytesProvider.DEFAULT_PROXY_API_URL
        val currentQbitUrl = prefs.getString("qbit_url", "") ?: ""
        val currentQbitUser = prefs.getString("qbit_user", "") ?: ""
        val currentQbitPass = prefs.getString("qbit_pass", "") ?: ""
        val currentDiscordWebhook = prefs.getString("discord_webhook", "") ?: ""

        val outerContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 18), dp(context, 14), dp(context, 18), dp(context, 14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#040B16"))
                cornerRadius = dp(context, 16).toFloat()
                setStroke(dp(context, 1), Color.parseColor("#1E3A8A"))
            }
        }

        val subtitle = TextView(context).apply {
            text = "NORTH • SEALINWATER • V39"
            textSize = 11f
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
        }
        outerContainer.addView(subtitle)

        val headerTitle = TextView(context).apply {
            text = "North • Tracker & Ratio Hub"
            textSize = 19f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(context, 2), 0, dp(context, 8))
        }
        outerContainer.addView(headerTitle)

        val scrollView = ScrollView(context).apply {
            isFillViewport = false
        }
        val scrollParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        )
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(context, 6))
        }
        scrollView.addView(root)
        outerContainer.addView(scrollView, scrollParams)

        // 1. LIVE ACCOUNT & RATIO STATUS CARD
        val statsCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#081428"))
                cornerRadius = dp(context, 10).toFloat()
                setStroke(dp(context, 1), Color.parseColor("#1D4ED8"))
            }
        }

        val statsHeader = TextView(context).apply {
            text = "NORTH • LIVE TRACKER & RATIO STATUS"
            textSize = 10f
            setTextColor(Color.parseColor("#60A5FA"))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }
        statsCard.addView(statsHeader)

        val statsBody = TextView(context).apply {
            text = "Verifying North API connection..."
            textSize = 11.5f
            setTextColor(Color.parseColor("#DBEAFE"))
            setLineSpacing(dp(context, 2).toFloat(), 1.0f)
            setPadding(0, dp(context, 4), 0, 0)
        }
        statsCard.addView(statsBody)
        root.addView(statsCard)

        val hasBridgeConfigured = currentQbitUrl.isNotBlank() || currentDiscordWebhook.isNotBlank()
        fetchLiveUserStats(currentToken, currentGhostRatio, currentFreeleechOnly, hasBridgeConfigured) { resultText ->
            statsBody.text = resultText
        }

        // 2. API TOKEN & RATIO PROTECTION CARD
        val sectionLabel = TextView(context).apply {
            text = "ACCOUNT & GHOST RATIO PROTECTION"
            textSize = 10.5f
            setTextColor(Color.parseColor("#93C5FD"))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setPadding(0, dp(context, 10), 0, dp(context, 4))
        }
        root.addView(sectionLabel)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0B1B36"))
                cornerRadius = dp(context, 12).toFloat()
                setStroke(dp(context, 1), Color.parseColor("#1E3A8A"))
            }
        }

        val cardTitle = TextView(context).apply {
            text = "North (NordicBytes) API Key"
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        card.addView(cardTitle)

        val input = EditText(context).apply {
            hint = "Paste your UNIT3D API Token..."
            setHintTextColor(Color.parseColor("#60A5FA"))
            setText(currentToken)
            setTextColor(Color.WHITE)
            textSize = 12.5f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSingleLine(true)
            setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#050D1D"))
                cornerRadius = dp(context, 8).toFloat()
                setStroke(dp(context, 1), Color.parseColor("#2563EB"))
            }
        }
        val inputParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(context, 6) }
        card.addView(input, inputParams)

        val ghostRatioSwitch = Switch(context).apply {
            text = "Ghost Ratio Proxy (0 B Download & No Hit-and-Run)"
            setTextColor(Color.WHITE)
            textSize = 12.5f
            isChecked = currentGhostRatio
            setPadding(0, dp(context, 8), 0, dp(context, 2))
        }
        card.addView(ghostRatioSwitch)

        val freeleechSwitch = Switch(context).apply {
            text = "Strict 100% Freeleech Filter Only"
            setTextColor(Color.WHITE)
            textSize = 12.5f
            isChecked = currentFreeleechOnly
            setPadding(0, dp(context, 6), 0, dp(context, 2))
        }
        card.addView(freeleechSwitch)

        val freeleechNote = TextView(context).apply {
            text = "Ghost Ratio Proxy intercepts tracker announces locally on 127.0.0.1 so Real & Credited Download stay 0 B and torrents auto-clear from Leeching."
            textSize = 10.5f
            setTextColor(Color.parseColor("#93C5FD"))
        }
        card.addView(freeleechNote)
        root.addView(card)

        // 2B. HOW TO SIGN UP & GET YOUR API KEY GUIDE CARD
        val guideLabel = TextView(context).apply {
            text = "HOW TO SIGN UP & GET YOUR API KEY (REQUIRED)"
            textSize = 10.5f
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setPadding(0, dp(context, 10), 0, dp(context, 4))
        }
        root.addView(guideLabel)

        val guideCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#081428"))
                cornerRadius = dp(context, 12).toFloat()
                setStroke(dp(context, 1), Color.parseColor("#1D4ED8"))
            }
        }

        val guideTitle = TextView(context).apply {
            text = "🔑 Account Key Required: How to Sign Up & Get Your Key"
            textSize = 12.5f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(context, 4))
        }
        guideCard.addView(guideTitle)

        val guideSteps = TextView(context).apply {
            text = buildString {
                append("1. Apply at https://nordicbytes.org/application\n")
                append("   • Public Vouching: Your application text and experience level (like \"New, I haven't used private trackers before\") are visible to all members so they can vouch for you. Just be honest and specific, and don't put your real name, home address, or personal info in there.\n")
                append("   • Staff-Only Fields: Your email address and proof links are only seen by staff.\n")
                append("   • Required Proofs: Add at least 2 screenshots (Screenshot URL + Profile URL) showing your tracker profile (username, ratio, upload/download). Applications without proof get rejected.\n\n")
                append("2. Grab Your API Key (Already have an account?)\n")
                append("   • Log in at nordicbytes.org, go to Profile -> Settings -> API Key / Security, and copy your UNIT3D API token.\n\n")
                append("3. Add It to North\n")
                append("   • Paste your API token into the box above and tap \"Save & Restart CloudStream\" (or search apikey:YOUR_TOKEN).")
            }
            textSize = 10.5f
            setTextColor(Color.parseColor("#DBEAFE"))
            setLineSpacing(dp(context, 2).toFloat(), 1.0f)
        }
        guideCard.addView(guideSteps)

        val guideButtonsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        val guideButtonsParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(context, 8) }

        fun openExternalUrl(urlStr: String) {
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(urlStr)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(browserIntent)
            } catch (_: Exception) {
                Toast.makeText(context, urlStr, Toast.LENGTH_LONG).show()
            }
        }

        val applyBtn = TextView(context).apply {
            text = "🌐 Apply (nordicbytes.org)"
            textSize = 11f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1D4ED8"))
                cornerRadius = dp(context, 8).toFloat()
                setStroke(dp(context, 1), Color.parseColor("#38BDF8"))
            }
            setOnClickListener {
                openExternalUrl("https://nordicbytes.org/application")
            }
        }
        val applyBtnParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(context, 4)
        }
        guideButtonsRow.addView(applyBtn, applyBtnParams)

        val loginKeyBtn = TextView(context).apply {
            text = "🔑 Log In & Get API Key"
            textSize = 11f
            setTextColor(Color.parseColor("#DBEAFE"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F2447"))
                cornerRadius = dp(context, 8).toFloat()
                setStroke(dp(context, 1), Color.parseColor("#2563EB"))
            }
            setOnClickListener {
                openExternalUrl("https://nordicbytes.org/login")
            }
        }
        val loginKeyBtnParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(context, 4)
        }
        guideButtonsRow.addView(loginKeyBtn, loginKeyBtnParams)

        guideCard.addView(guideButtonsRow, guideButtonsParams)
        root.addView(guideCard)

        // 3. PLAYBACK, SKIP RESUMÉ/INTRO & SUBTITLES CARD
        val prefLabel = TextView(context).apply {
            text = "PLAYBACK, SKIP RESUMÉ/INTRO & SUBTITLES"
            textSize = 10.5f
            setTextColor(Color.parseColor("#93C5FD"))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setPadding(0, dp(context, 10), 0, dp(context, 4))
        }
        root.addView(prefLabel)

        val prefCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0B1B36"))
                cornerRadius = dp(context, 12).toFloat()
                setStroke(dp(context, 1), Color.parseColor("#1E3A8A"))
            }
        }

        val langTitle = TextView(context).apply {
            text = "Preferred Audio & Subtitle Language"
            textSize = 12.5f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(context, 6))
        }
        prefCard.addView(langTitle)

        var selectedLang = currentPrefLanguage
        val langRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 4f
        }

        val langOptions = listOf(
            "DA" to "🇩🇰 Danish",
            "NORDIC" to "🇩🇰🇸🇪 Nordic",
            "EN" to "🇬🇧 English",
            "ALL" to "🌐 All"
        )

        val langBtnViews = mutableListOf<TextView>()

        fun updateLangBtnStyles() {
            for ((key, btnView) in langBtnViews.zip(langOptions)) {
                val isSel = (btnView.first == selectedLang)
                key.background = GradientDrawable().apply {
                    setColor(if (isSel) Color.parseColor("#1D4ED8") else Color.parseColor("#050D1D"))
                    cornerRadius = dp(context, 6).toFloat()
                    setStroke(dp(context, 1), if (isSel) Color.parseColor("#3B82F6") else Color.parseColor("#1E40AF"))
                }
                key.setTextColor(if (isSel) Color.WHITE else Color.parseColor("#93C5FD"))
            }
        }

        for ((key, labelText) in langOptions) {
            val langBtn = TextView(context).apply {
                text = labelText
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(context, 4), dp(context, 7), dp(context, 4), dp(context, 7))
                setOnClickListener {
                    selectedLang = key
                    updateLangBtnStyles()
                }
            }
            langBtnViews.add(langBtn)
            val btnParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(context, 2), 0, dp(context, 2), 0)
            }
            langRow.addView(langBtn, btnParams)
        }
        updateLangBtnStyles()
        prefCard.addView(langRow)

        val playNorthIntroSwitch = Switch(context).apply {
            text = "Play 'NORTH' Studio Intro Before Content"
            setTextColor(Color.WHITE)
            textSize = 12.5f
            isChecked = currentPlayNorthIntro
            setPadding(0, dp(context, 10), 0, dp(context, 2))
        }
        prefCard.addView(playNorthIntroSwitch)

        val skipIntroSwitch = Switch(context).apply {
            text = "In-Player Skip Resumé, Skip Intro & Skip Outro UI"
            setTextColor(Color.WHITE)
            textSize = 12.5f
            isChecked = currentSkipIntros
            setPadding(0, dp(context, 6), 0, dp(context, 2))
        }
        prefCard.addView(skipIntroSwitch)

        val autoSubsSwitch = Switch(context).apply {
            text = "Auto-Load Danish, Nordic & English Subtitles"
            setTextColor(Color.WHITE)
            textSize = 12.5f
            isChecked = currentAutoSubtitles
            setPadding(0, dp(context, 6), 0, dp(context, 2))
        }
        prefCard.addView(autoSubsSwitch)

        val proxyApiTitle = TextView(context).apply {
            text = "North Companion Proxy API URL"
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(context, 8), 0, dp(context, 4))
        }
        prefCard.addView(proxyApiTitle)

        val proxyApiInput = EditText(context).apply {
            hint = NordicBytesProvider.DEFAULT_PROXY_API_URL
            setHintTextColor(Color.parseColor("#64748B"))
            setText(currentProxyApiUrl)
            setTextColor(Color.WHITE)
            textSize = 11.5f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setPadding(dp(context, 10), dp(context, 7), dp(context, 10), dp(context, 7))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#050D1D"))
                cornerRadius = dp(context, 8).toFloat()
                setStroke(dp(context, 1), Color.parseColor("#1E40AF"))
            }
        }
        prefCard.addView(proxyApiInput)

        val continuityNote = TextView(context).apply {
            text = "✓ Seekable Timeline & Companion API: Plays the NORTH title intro before content, listens on the North Proxy API + local 0.0.0.0 proxy for skip stamps/subtitles/streams, and preserves full forward/backward timeline seeking."
            textSize = 10.5f
            setTextColor(Color.parseColor("#93C5FD"))
            setPadding(0, dp(context, 6), 0, 0)
        }
        prefCard.addView(continuityNote)

        root.addView(prefCard)

        // 4. WORRY-FREE AUTO-SEED BRIDGE (SEEDBOX / QBITTORRENT / WEBHOOK)
        val seedLabel = TextView(context).apply {
            text = "WORRY-FREE 48H AUTO-SEED BRIDGE (OPTIONAL)"
            textSize = 10.5f
            setTextColor(Color.parseColor("#93C5FD"))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setPadding(0, dp(context, 10), 0, dp(context, 4))
        }
        root.addView(seedLabel)

        val seedCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0B1B36"))
                cornerRadius = dp(context, 12).toFloat()
                setStroke(dp(context, 1), Color.parseColor("#1E3A8A"))
            }
        }

        val seedDesc = TextView(context).apply {
            text = "Forward played torrents to your Seedbox / qBittorrent WebUI (or Discord/Autobrr Webhook) so they seed for 48h automatically."
            textSize = 10.5f
            setTextColor(Color.parseColor("#93C5FD"))
            setPadding(0, 0, 0, dp(context, 6))
        }
        seedCard.addView(seedDesc)

        fun createStyledInput(hintText: String, currentVal: String, isPassword: Boolean = false): EditText {
            return EditText(context).apply {
                hint = hintText
                setHintTextColor(Color.parseColor("#64748B"))
                setText(currentVal)
                setTextColor(Color.WHITE)
                textSize = 12f
                inputType = if (isPassword) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                }
                setSingleLine(true)
                setPadding(dp(context, 10), dp(context, 7), dp(context, 10), dp(context, 7))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#050D1D"))
                    cornerRadius = dp(context, 8).toFloat()
                    setStroke(dp(context, 1), Color.parseColor("#1E40AF"))
                }
            }
        }

        val qbitUrlInput = createStyledInput("qBittorrent WebUI URL (e.g. https://seedbox.host:8080)", currentQbitUrl)
        val qbitUserInput = createStyledInput("qBittorrent Username", currentQbitUser)
        val qbitPassInput = createStyledInput("qBittorrent Password", currentQbitPass, isPassword = true)
        val webhookInput = createStyledInput("Or Webhook URL (Discord / Autobrr / n8n)", currentDiscordWebhook)

        fun newFieldParams(): LinearLayout.LayoutParams {
            return LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 5) }
        }

        seedCard.addView(qbitUrlInput, newFieldParams())
        seedCard.addView(qbitUserInput, newFieldParams())
        seedCard.addView(qbitPassInput, newFieldParams())
        seedCard.addView(webhookInput, newFieldParams())

        root.addView(seedCard)

        var dialogInstance: AlertDialog? = null

        // 5. PINNED STICKY FOOTER BUTTONS
        val footerContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(context, 10), 0, 0)
        }

        val saveBtn = TextView(context).apply {
            text = "✓  Save & Restart CloudStream"
            textSize = 13.5f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(context, 14), dp(context, 11), dp(context, 14), dp(context, 11))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1D4ED8"))
                cornerRadius = dp(context, 10).toFloat()
                setStroke(dp(context, 1), Color.parseColor("#3B82F6"))
            }
            setOnClickListener {
                val token = input.text.toString().trim()
                val proxyApiUrl = proxyApiInput.text.toString().trim().trimEnd('/')
                prefs.edit()
                    .putString("api_token", token)
                    .putBoolean("ghost_ratio", ghostRatioSwitch.isChecked)
                    .putBoolean("freeleech_only", freeleechSwitch.isChecked)
                    .putBoolean("play_north_intro", playNorthIntroSwitch.isChecked)
                    .putBoolean("skip_intros", skipIntroSwitch.isChecked)
                    .putBoolean("auto_subtitles", autoSubsSwitch.isChecked)
                    .putString("pref_language", selectedLang)
                    .putString("proxy_api_url", proxyApiUrl)
                    .putString("qbit_url", qbitUrlInput.text.toString().trim().trimEnd('/'))
                    .putString("qbit_user", qbitUserInput.text.toString().trim())
                    .putString("qbit_pass", qbitPassInput.text.toString().trim())
                    .putString("discord_webhook", webhookInput.text.toString().trim())
                    .commit()
                NordicBytesProvider.playNorthIntroEnabled = playNorthIntroSwitch.isChecked
                NordicBytesProvider.proxyApiBaseUrl = proxyApiUrl
                NordicBytesProvider.precacheNorthIntroAsync()
                NordicBytesProvider.clearCaches()
                Toast.makeText(context, "North settings saved! Restarting...", Toast.LENGTH_SHORT).show()
                try {
                    dialogInstance?.dismiss()
                } catch (_: Exception) {
                }
                restartApp(context)
            }
        }
        footerContainer.addView(
            saveBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val secondaryRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        val secondaryRowParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(context, 6) }

        val clearBtn = TextView(context).apply {
            text = "Clear API Key"
            textSize = 12.5f
            setTextColor(Color.parseColor("#F87171"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(context, 10), dp(context, 9), dp(context, 10), dp(context, 9))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F2447"))
                cornerRadius = dp(context, 8).toFloat()
                setStroke(dp(context, 1), Color.parseColor("#1E40AF"))
            }
            setOnClickListener {
                prefs.edit().remove("api_token").commit()
                NordicBytesProvider.clearCaches()
                input.setText("")
                Toast.makeText(context, "North API Key cleared", Toast.LENGTH_SHORT).show()
            }
        }
        val clearBtnParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(context, 4)
        }
        secondaryRow.addView(clearBtn, clearBtnParams)

        val cancelBtn = TextView(context).apply {
            text = "Close"
            textSize = 12.5f
            setTextColor(Color.parseColor("#BFDBFE"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(context, 10), dp(context, 9), dp(context, 10), dp(context, 9))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F2447"))
                cornerRadius = dp(context, 8).toFloat()
                setStroke(dp(context, 1), Color.parseColor("#1E40AF"))
            }
            setOnClickListener {
                try {
                    dialogInstance?.dismiss()
                } catch (_: Exception) {
                }
            }
        }
        val cancelBtnParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(context, 4)
        }
        secondaryRow.addView(cancelBtn, cancelBtnParams)
        footerContainer.addView(secondaryRow, secondaryRowParams)

        outerContainer.addView(
            footerContainer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        try {
            if (context is AppCompatActivity && (context.isFinishing || context.isDestroyed)) {
                return
            }
            dialogInstance = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setView(outerContainer)
                .create()
            dialogInstance?.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialogInstance?.show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
