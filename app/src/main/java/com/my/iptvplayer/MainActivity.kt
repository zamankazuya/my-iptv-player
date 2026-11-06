package com.my.iptvplayer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.util.Base64
import android.util.Log
import android.util.TypedValue
import android.util.Xml
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.CookieHandler
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * Shared real-time sync hub between the Phone instance (MainActivity)
 * and the Android Auto Car instance (MainCarActivity).
 */
object SharedSyncStore {
    private const val PREF_NAME = "coolwalk_shared_sync"
    private const val KEY_CONFIG_JSON = "config_json"
    private const val PLAYLIST_CACHE_FILE = "cached_playlist.m3u"

    val activeControllers = CopyOnWriteArrayList<IptvSessionController>()

    fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun saveConfigJson(context: Context, jsonStr: String, source: IptvSessionController?) {
        getPrefs(context).edit().putString(KEY_CONFIG_JSON, jsonStr).apply()
        notifyOtherInstances(source)
    }

    fun loadConfigJson(context: Context): String {
        return getPrefs(context).getString(KEY_CONFIG_JSON, "{}") ?: "{}"
    }

    fun savePlaylistCache(context: Context, rawM3u: String, source: IptvSessionController?) {
        try {
            File(context.applicationContext.filesDir, PLAYLIST_CACHE_FILE).writeText(rawM3u, Charsets.UTF_8)
            notifyOtherInstances(source)
        } catch (e: Exception) {
            Log.e("SyncStore", "Failed saving M3U cache", e)
        }
    }

    fun loadPlaylistCache(context: Context): String {
        return try {
            val file = File(context.applicationContext.filesDir, PLAYLIST_CACHE_FILE)
            if (file.exists()) file.readText(Charsets.UTF_8) else ""
        } catch (e: Exception) {
            ""
        }
    }

    fun notifyOtherInstances(source: IptvSessionController?) {
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            for (ctrl in activeControllers) {
                if (ctrl !== source) {
                    ctrl.syncFromStore()
                }
            }
        }
    }
}

@UnstableApi
class IptvSessionController(
    private val context: Context,
    private val isCarInstance: Boolean,
    private val onPickFileRequest: ((Intent) -> Boolean)?,
    private val onVoiceRequest: ((String) -> Unit)?
) {
    val rootView: FrameLayout
    private val playerView: PlayerView
    private var exoPlayer: ExoPlayer? = null
    val webView: WebView
    private val mainHandler = Handler(Looper.getMainLooper())

    private data class StreamRequest(
        val url: String,
        val format: String,
        val userAgent: String,
        val referer: String,
        val licenseUrl: String,
        val clearKey: String,
        val fallbackAttempt: Int = 0
    )

    private var lastRequest: StreamRequest? = null
    private var tracksOverrideApplied: Boolean = false
    private var preferredSubMode: String = "ms_en"
    private var preferredAudioMode: String = "ms_en"
    private var maxVideoHeight: Int = 0
    var bgAudioPlayOnExit: Boolean = false

    init {
        trustAllCertificates()
        if (CookieHandler.getDefault() == null) {
            CookieHandler.setDefault(java.net.CookieManager(null, CookiePolicy.ACCEPT_ALL))
        }

        rootView = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        playerView = PlayerView(context).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
            subtitleView?.apply {
                setStyle(
                    CaptionStyleCompat(
                        Color.WHITE,
                        Color.TRANSPARENT,
                        Color.TRANSPARENT,
                        CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                        Color.BLACK,
                        Typeface.DEFAULT_BOLD
                    )
                )
                setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setBottomPaddingFraction(0.08f)
            }
        }

        webView = WebView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        rootView.addView(playerView)
        rootView.addView(webView)

        SharedSyncStore.activeControllers.add(this)
        initExoPlayer()
        setupWebView()
        val modeParam = if (isCarInstance) "car" else "phone"
        webView.loadUrl("file:///android_asset/index.html?mode=$modeParam")
    }

    fun getRootView(): View = rootView

    fun syncFromStore() {
        val cfg = SharedSyncStore.loadConfigJson(context)
        val quotedCfg = JSONObject.quote(cfg)
        webView.post {
            webView.evaluateJavascript("window.onSharedConfigUpdated && window.onSharedConfigUpdated($quotedCfg);", null)
        }
    }

    private fun trustAllCertificates() {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
        } catch (e: Exception) {
            Log.e("SSLTrust", "Failed to trust certs", e)
        }
    }

    private fun initExoPlayer() {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 50000, 1000, 2500)
            .build()

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        exoPlayer = ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .build()
            .also { player ->
                playerView.player = player
                player.playWhenReady = true

                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setPreferredTextLanguages("ms", "may", "msa", "zsm", "en", "eng")
                    .setPreferredAudioLanguages("ms", "may", "msa", "zsm", "en", "eng")
                    .setPreferredTextRoleFlags(C.ROLE_FLAG_SUBTITLE or C.ROLE_FLAG_CAPTION)
                    .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_FORCED)
                    .build()

                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        val jsState = when (state) {
                            Player.STATE_BUFFERING -> "BUFFERING"
                            Player.STATE_READY -> "READY"
                            Player.STATE_ENDED -> "ENDED"
                            else -> "IDLE"
                        }
                        webView.post {
                            webView.evaluateJavascript("window.onNativePlayerState && window.onNativePlayerState('$jsState');", null)
                        }
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        applyPreferredTracks(player, tracks)
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        val h = videoSize.height
                        val label = if (h > 0) "${h}p" else ""
                        webView.post {
                            webView.evaluateJavascript("window.onVideoResolutionChanged && window.onVideoResolutionChanged('$label');", null)
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val causeMsg = error.cause?.message ?: error.message ?: "Unknown error"
                        val fullMsg = "${error.errorCodeName}: $causeMsg"

                        val req = lastRequest
                        val isFormatError = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                                error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
                                error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED

                        if (req != null && isFormatError && req.fallbackAttempt == 0) {
                            val nextFormat = when (req.format) {
                                "hls" -> "mpd"
                                "mpd" -> "raw"
                                else -> "hls"
                            }
                            val retryReq = req.copy(format = nextFormat, fallbackAttempt = 1)
                            lastRequest = retryReq
                            startPlayback(retryReq)
                            return
                        }

                        val safe = fullMsg.replace("'", "").replace("\n", " ")
                        webView.post {
                            webView.evaluateJavascript("window.onNativePlayerError && window.onNativePlayerError('$safe');", null)
                        }
                    }
                })
            }
    }

    private fun isMalayTrack(lang: String, label: String): Boolean {
        return lang in listOf("ms", "may", "msa", "zsm") ||
                lang.startsWith("ms-") ||
                label.contains("malay") ||
                label.contains("melayu") ||
                label.contains("bahasa") ||
                label == "bm"
    }

    private fun isEnglishTrack(lang: String, label: String): Boolean {
        return lang in listOf("en", "eng") ||
                lang.startsWith("en-") ||
                label.contains("english") ||
                label == "eng" ||
                label == "en"
    }

    private fun scoreLanguage(mode: String, isMalay: Boolean, isEnglish: Boolean): Pair<Int, String> {
        return when (mode) {
            "ms_en" -> when {
                isMalay -> 2 to "MALAY"
                isEnglish -> 1 to "ENG"
                else -> 0 to ""
            }
            "en_ms" -> when {
                isEnglish -> 2 to "ENG"
                isMalay -> 1 to "MALAY"
                else -> 0 to ""
            }
            "ms" -> if (isMalay) 2 to "MALAY" else 0 to ""
            "en" -> if (isEnglish) 2 to "ENG" else 0 to ""
            else -> 0 to ""
        }
    }

    private fun applyPreferredTracks(player: ExoPlayer, tracks: Tracks) {
        if (tracks.groups.isEmpty()) return

        var bestSubGroup: TrackGroup? = null
        var bestSubIndex = -1
        var bestSubScore = 0
        var bestSubLabel = ""

        var bestAudioGroup: TrackGroup? = null
        var bestAudioIndex = -1
        var bestAudioScore = 0
        var bestAudioLabel = ""

        for (group in tracks.groups) {
            val mediaGroup = group.mediaTrackGroup
            if (group.type == C.TRACK_TYPE_TEXT && preferredSubMode != "off") {
                for (i in 0 until mediaGroup.length) {
                    if (!group.isTrackSupported(i)) continue
                    val fmt = mediaGroup.getFormat(i)
                    val lang = (fmt.language ?: "").lowercase(Locale.ROOT)
                    val label = (fmt.label ?: "").lowercase(Locale.ROOT)
                    val (score, tag) = scoreLanguage(preferredSubMode, isMalayTrack(lang, label), isEnglishTrack(lang, label))
                    if (score > bestSubScore) {
                        bestSubScore = score
                        bestSubGroup = mediaGroup
                        bestSubIndex = i
                        bestSubLabel = tag
                    }
                }
            } else if (group.type == C.TRACK_TYPE_AUDIO && preferredAudioMode != "default") {
                for (i in 0 until mediaGroup.length) {
                    if (!group.isTrackSupported(i)) continue
                    val fmt = mediaGroup.getFormat(i)
                    val lang = (fmt.language ?: "").lowercase(Locale.ROOT)
                    val label = (fmt.label ?: "").lowercase(Locale.ROOT)
                    val (score, tag) = scoreLanguage(preferredAudioMode, isMalayTrack(lang, label), isEnglishTrack(lang, label))
                    if (score > bestAudioScore) {
                        bestAudioScore = score
                        bestAudioGroup = mediaGroup
                        bestAudioIndex = i
                        bestAudioLabel = tag
                    }
                }
            }
        }

        if (!tracksOverrideApplied) {
            tracksOverrideApplied = true
            val paramsBuilder = player.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)

            if (maxVideoHeight > 0) {
                paramsBuilder.setMaxVideoSize(Int.MAX_VALUE, maxVideoHeight)
            } else {
                paramsBuilder.clearVideoSizeConstraints()
            }

            if (preferredSubMode == "off") {
                paramsBuilder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            } else {
                paramsBuilder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                if (bestSubGroup != null && bestSubIndex >= 0) {
                    paramsBuilder.addOverride(TrackSelectionOverride(bestSubGroup, bestSubIndex))
                }
            }

            if (preferredAudioMode != "default" && bestAudioGroup != null && bestAudioIndex >= 0) {
                paramsBuilder.addOverride(TrackSelectionOverride(bestAudioGroup, bestAudioIndex))
            }

            player.trackSelectionParameters = paramsBuilder.build()
        }

        val finalSubBadge = if (preferredSubMode == "off") "" else bestSubLabel
        val finalAudioBadge = if (preferredAudioMode == "default") "" else bestAudioLabel

        webView.post {
            webView.evaluateJavascript("window.onTrackBadgesUpdated && window.onTrackBadgesUpdated('$finalSubBadge', '$finalAudioBadge');", null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        WebView.setWebContentsDebuggingEnabled(true)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                val intent = fileChooserParams?.createIntent() ?: return false
                MainActivity.pendingFileCallback?.onReceiveValue(null)
                MainActivity.pendingFileCallback = filePathCallback
                val launched = onPickFileRequest?.invoke(intent) ?: false
                if (!launched) {
                    MainActivity.pendingFileCallback = null
                }
                return launched
            }
        }
    }

    private fun hexToBase64Url(hex: String): String {
        val clean = hex.trim().replace("-", "")
        val isHex = clean.length % 2 == 0 && clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        if (!isHex) {
            return clean.replace("+", "-").replace("/", "_").replace("=", "")
        }
        val bytes = ByteArray(clean.length / 2)
        for (i in bytes.indices) {
            val idx = i * 2
            bytes[i] = clean.substring(idx, idx + 2).toInt(16).toByte()
        }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun buildClearKeyJson(rawClearKey: String): ByteArray {
        val trimmed = rawClearKey.trim()
        if (trimmed.startsWith("{") && trimmed.contains("\"keys\"")) {
            return trimmed.toByteArray(Charsets.UTF_8)
        }
        val keysArray = mutableListOf<String>()
        trimmed.split(",").forEach { pair ->
            val parts = pair.trim().split(":")
            if (parts.size == 2) {
                val kidB64 = hexToBase64Url(parts[0])
                val keyB64 = hexToBase64Url(parts[1])
                keysArray.add("""{"kty":"oct","kid":"$kidB64","k":"$keyB64"}""")
            }
        }
        val json = """{"keys":[${keysArray.joinToString(",")}],"type":"temporary"}"""
        return json.toByteArray(Charsets.UTF_8)
    }

    private fun wrapGzipIfNeeded(inputStream: InputStream): InputStream {
        val pb = PushbackInputStream(inputStream, 2)
        val signature = ByteArray(2)
        val len = pb.read(signature)
        if (len > 0) pb.unread(signature, 0, len)
        return if (len == 2 && signature[0] == 0x1f.toByte() && signature[1] == 0x8b.toByte()) {
            GZIPInputStream(pb)
        } else {
            pb
        }
    }

    private fun fetchUrlTextWithHeaders(urlStr: String, headers: Map<String, String>): String {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 20000
                instanceFollowRedirects = true
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = conn.responseCode
            if (code !in 200..399) return ""
            wrapGzipIfNeeded(conn.inputStream).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
    }

    private fun parseXmltvTime(raw: String): Long {
        val clean = raw.trim()
        if (clean.length < 14) return 0L
        return try {
            if (clean.length >= 19 && (clean.contains("+") || clean.contains("-"))) {
                SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).parse(clean)?.time ?: 0L
            } else {
                val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                sdf.parse(clean.substring(0, 14))?.time ?: 0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun formatShortTime(ms: Long): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
    }

    private fun parseFastEpgFromUrl(epgUrl: String, userAgent: String) {
        thread {
            try {
                val conn = (URL(epgUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Accept", "*/*")
                }
                if (conn.responseCode !in 200..399) return@thread

                val nowMs = System.currentTimeMillis()
                val windowEndMs = nowMs + 6 * 3600_000L

                data class ProgSlot(
                    var nowTitle: String = "",
                    var nowTime: String = "",
                    var nextTitle: String = "",
                    var nextStartMs: Long = Long.MAX_VALUE
                )

                val epgMap = mutableMapOf<String, ProgSlot>()
                val displayNameToId = mutableMapOf<String, String>()

                wrapGzipIfNeeded(conn.inputStream).use { stream ->
                    val parser = Xml.newPullParser()
                    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                    parser.setInput(stream, null)

                    var eventType = parser.eventType
                    var currentChannelId = ""
                    var inChannel = false
                    var inProgramme = false
                    var progChannel = ""
                    var progStart = 0L
                    var progStop = 0L
                    var currentTag = ""

                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        when (eventType) {
                            XmlPullParser.START_TAG -> {
                                currentTag = parser.name ?: ""
                                if (currentTag.equals("channel", true)) {
                                    inChannel = true
                                    currentChannelId = (parser.getAttributeValue(null, "id") ?: "").trim().lowercase(Locale.ROOT)
                                } else if (currentTag.equals("programme", true)) {
                                    val startStr = parser.getAttributeValue(null, "start") ?: ""
                                    val stopStr = parser.getAttributeValue(null, "stop") ?: ""
                                    progStart = parseXmltvTime(startStr)
                                    progStop = parseXmltvTime(stopStr)
                                    if (progStop > nowMs && progStart < windowEndMs) {
                                        inProgramme = true
                                        progChannel = (parser.getAttributeValue(null, "channel") ?: "").trim().lowercase(Locale.ROOT)
                                    } else {
                                        inProgramme = false
                                    }
                                }
                            }
                            XmlPullParser.TEXT -> {
                                val text = parser.text?.trim() ?: ""
                                if (text.isNotEmpty()) {
                                    if (inChannel && currentTag.equals("display-name", true) && currentChannelId.isNotEmpty()) {
                                        displayNameToId[text.lowercase(Locale.ROOT)] = currentChannelId
                                    } else if (inProgramme && currentTag.equals("title", true) && progChannel.isNotEmpty()) {
                                        val slot = epgMap.getOrPut(progChannel) { ProgSlot() }
                                        if (progStart <= nowMs && progStop > nowMs) {
                                            slot.nowTitle = text
                                            slot.nowTime = "${formatShortTime(progStart)} - ${formatShortTime(progStop)}"
                                        } else if (progStart > nowMs && progStart < slot.nextStartMs) {
                                            slot.nextStartMs = progStart
                                            slot.nextTitle = "${formatShortTime(progStart)} $text"
                                        }
                                    }
                                }
                            }
                            XmlPullParser.END_TAG -> {
                                val endName = parser.name ?: ""
                                if (endName.equals("channel", true)) {
                                    inChannel = false
                                    currentChannelId = ""
                                } else if (endName.equals("programme", true)) {
                                    inProgramme = false
                                }
                                currentTag = ""
                            }
                        }
                        eventType = parser.next()
                    }
                }

                val rootJson = JSONObject()
                val channelsJson = JSONObject()
                for ((chId, slot) in epgMap) {
                    if (slot.nowTitle.isNotEmpty() || slot.nextTitle.isNotEmpty()) {
                        val item = JSONObject()
                        item.put("now", slot.nowTitle)
                        item.put("time", slot.nowTime)
                        item.put("next", slot.nextTitle)
                        channelsJson.put(chId, item)
                    }
                }
                for ((dispName, chId) in displayNameToId) {
                    if (!channelsJson.has(dispName) && channelsJson.has(chId)) {
                        channelsJson.put(dispName, channelsJson.getJSONObject(chId))
                    }
                }
                rootJson.put("epg", channelsJson)
                val quoted = JSONObject.quote(rootJson.toString())
                webView.post {
                    webView.evaluateJavascript("window.onNativeEpgLoaded && window.onNativeEpgLoaded($quoted);", null)
                }
            } catch (e: Exception) {
                Log.e("IPTV_EPG", "EPG parse failed: ${e.message}")
            }
        }
    }

    private fun startPlayback(req: StreamRequest) {
        tracksOverrideApplied = false
        val cookieValue = CookieManager.getInstance().getCookie(req.url)
        val headers = mutableMapOf<String, String>().apply {
            put("User-Agent", req.userAgent)
            put("Accept", "*/*")
            put("Connection", "keep-alive")
            if (req.referer.isNotEmpty()) {
                put("Referer", req.referer)
                try {
                    val u = URL(req.referer)
                    if (!u.authority.isNullOrEmpty()) {
                        put("Origin", "${u.protocol}://${u.authority}")
                    }
                } catch (_: Exception) {}
            }
            if (!cookieValue.isNullOrBlank()) put("Cookie", cookieValue)
        }

        thread {
            var resolvedClearKey = req.clearKey
            var clearKeyHttpUrl = ""

            if (resolvedClearKey.startsWith("http://") || resolvedClearKey.startsWith("https://")) {
                val cleanCkUrl = resolvedClearKey.split("|")[0].trim()
                val body = fetchUrlTextWithHeaders(cleanCkUrl, headers).trim()
                if (body.startsWith("{") || body.contains(":")) {
                    resolvedClearKey = body
                } else {
                    clearKeyHttpUrl = cleanCkUrl
                }
            }

            mainHandler.post {
                val player = exoPlayer ?: return@post
                player.stop()
                player.clearMediaItems()

                val subLangs = when (preferredSubMode) {
                    "en_ms" -> arrayOf("en", "eng", "ms", "may", "msa", "zsm")
                    "ms" -> arrayOf("ms", "may", "msa", "zsm")
                    "en" -> arrayOf("en", "eng")
                    else -> arrayOf("ms", "may", "msa", "zsm", "en", "eng")
                }

                val audLangs = when (preferredAudioMode) {
                    "en_ms" -> arrayOf("en", "eng", "ms", "may", "msa", "zsm")
                    "ms" -> arrayOf("ms", "may", "msa", "zsm")
                    "en" -> arrayOf("en", "eng")
                    else -> arrayOf("ms", "may", "msa", "zsm", "en", "eng")
                }

                val paramBuilder = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, preferredSubMode == "off")
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .setPreferredTextLanguages(*subLangs)
                    .setPreferredAudioLanguages(*audLangs)

                if (maxVideoHeight > 0) {
                    paramBuilder.setMaxVideoSize(Int.MAX_VALUE, maxVideoHeight)
                } else {
                    paramBuilder.clearVideoSizeConstraints()
                }

                player.trackSelectionParameters = paramBuilder.build()

                val dataSourceFactory = DefaultHttpDataSource.Factory()
                    .setUserAgent(req.userAgent)
                    .setDefaultRequestProperties(headers)
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(25000)
                    .setReadTimeoutMs(25000)

                val mediaItemBuilder = MediaItem.Builder()
                    .setUri(Uri.parse(req.url))

                when (req.format) {
                    "mpd" -> mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
                    "hls" -> mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
                }

                val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

                if (clearKeyHttpUrl.isNotEmpty()) {
                    val drmCallback = HttpMediaDrmCallback(clearKeyHttpUrl, dataSourceFactory)
                    headers.forEach { (k, v) -> drmCallback.setKeyRequestProperty(k, v) }
                    val drmSessionManager = DefaultDrmSessionManager.Builder()
                        .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                        .setMultiSession(true)
                        .setPlayClearSamplesWithoutKeys(true)
                        .build(drmCallback)
                    mediaSourceFactory.setDrmSessionManagerProvider { drmSessionManager }
                } else if (resolvedClearKey.isNotEmpty() && (resolvedClearKey.contains(":") || resolvedClearKey.startsWith("{"))) {
                    try {
                        val clearKeyBytes = buildClearKeyJson(resolvedClearKey)
                        val drmCallback = LocalMediaDrmCallback(clearKeyBytes)
                        val drmSessionManager = DefaultDrmSessionManager.Builder()
                            .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                            .setMultiSession(true)
                            .setPlayClearSamplesWithoutKeys(true)
                            .build(drmCallback)
                        mediaSourceFactory.setDrmSessionManagerProvider { drmSessionManager }
                    } catch (e: Exception) {
                        Log.e("IPTV_Native", "ClearKey error: ${e.message}")
                    }
                } else if (req.licenseUrl.startsWith("http")) {
                    val cleanLicUrl = req.licenseUrl.split("|")[0].trim()
                    mediaItemBuilder.setDrmConfiguration(
                        MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                            .setLicenseUri(cleanLicUrl)
                            .setLicenseRequestHeaders(headers)
                            .setMultiSession(true)
                            .build()
                    )
                }

                val mediaSource = mediaSourceFactory.createMediaSource(mediaItemBuilder.build())
                player.setMediaSource(mediaSource)
                player.prepare()
                player.play()
            }
        }
    }

    fun stopStream() {
        mainHandler.post {
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
        }
    }

    fun release() {
        SharedSyncStore.activeControllers.remove(this)
        mainHandler.post {
            exoPlayer?.release()
            exoPlayer = null
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun isCarMode(): Boolean = isCarInstance

        @JavascriptInterface
        fun saveSharedConfig(jsonStr: String) {
            SharedSyncStore.saveConfigJson(context, jsonStr, this@IptvSessionController)
        }

        @JavascriptInterface
        fun loadSharedConfig(): String {
            return SharedSyncStore.loadConfigJson(context)
        }

        @JavascriptInterface
        fun savePlaylistCache(rawM3u: String) {
            SharedSyncStore.savePlaylistCache(context, rawM3u, this@IptvSessionController)
        }

        @JavascriptInterface
        fun loadPlaylistCache(): String {
            return SharedSyncStore.loadPlaylistCache(context)
        }

        @JavascriptInterface
        fun playNativeStream(
            url: String,
            type: String,
            userAgent: String?,
            referer: String?,
            licenseUrl: String?,
            clearKey: String?
        ) {
            val cleanUrl = url.trim()
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) return

            val ua = if (!userAgent.isNullOrBlank()) userAgent.trim() else "TiviMate/4.7.0 (Linux; Android 13)"
            val ref = referer?.trim() ?: ""
            val lic = licenseUrl?.trim() ?: ""
            val ck = clearKey?.trim() ?: ""

            val lowerUrl = cleanUrl.lowercase()
            val initialFormat = when {
                type.equals("mpd", true) || lowerUrl.contains(".mpd") || ck.isNotEmpty() || lic.isNotEmpty() -> "mpd"
                type.equals("hls", true) || lowerUrl.contains(".m3u8") -> "hls"
                else -> "raw"
            }

            val req = StreamRequest(cleanUrl, initialFormat, ua, ref, lic, ck, 0)
            lastRequest = req
            startPlayback(req)
        }

        @JavascriptInterface
        fun stopNativeStream() {
            stopStream()
        }

        @JavascriptInterface
        fun setLanguagePreferences(subMode: String?, audioMode: String?) {
            preferredSubMode = if (!subMode.isNullOrBlank()) subMode.trim() else "ms_en"
            preferredAudioMode = if (!audioMode.isNullOrBlank()) audioMode.trim() else "ms_en"
            mainHandler.post {
                exoPlayer?.let { player ->
                    tracksOverrideApplied = false
                    applyPreferredTracks(player, player.currentTracks)
                }
            }
        }

        @JavascriptInterface
        fun setPlayerOptions(resHeight: Int, bgAudio: Boolean, fontScalePercent: Int, resizeMode: Int) {
            maxVideoHeight = resHeight
            bgAudioPlayOnExit = bgAudio
            val clampedScale = fontScalePercent.coerceIn(80, 140)
            val spSize = 18f * (clampedScale / 100f)

            mainHandler.post {
                playerView.subtitleView?.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, spSize)
                playerView.resizeMode = when (resizeMode) {
                    1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
                exoPlayer?.let { player ->
                    val b = player.trackSelectionParameters.buildUpon()
                    if (maxVideoHeight > 0) {
                        b.setMaxVideoSize(Int.MAX_VALUE, maxVideoHeight)
                    } else {
                        b.clearVideoSizeConstraints()
                    }
                    player.trackSelectionParameters = b.build()
                }
            }
        }

        @JavascriptInterface
        fun startVoiceRecognition(lang: String?) {
            val code = if (!lang.isNullOrBlank()) lang.trim() else "ms-MY"
            mainHandler.post {
                onVoiceRequest?.invoke(code)
            }
        }

        @JavascriptInterface
        fun loadNativeEpg(epgUrl: String, userAgent: String?) {
            val clean = epgUrl.trim()
            if (!clean.startsWith("http://") && !clean.startsWith("https://")) return
            val ua = if (!userAgent.isNullOrBlank()) userAgent.trim() else "TiviMate/4.7.0 (Linux; Android 13)"
            parseFastEpgFromUrl(clean, ua)
        }

        @JavascriptInterface
        fun togglePlayPause(): Boolean {
            var playing = false
            mainHandler.post {
                exoPlayer?.let {
                    if (it.isPlaying) it.pause() else it.play()
                    playing = it.isPlaying
                }
            }
            return playing
        }

        @JavascriptInterface
        fun fetchRemoteText(urlStr: String, userAgent: String?): String {
            val ua = if (!userAgent.isNullOrBlank()) userAgent else "TiviMate/4.7.0 (Linux; Android 13)"
            return fetchUrlTextWithHeaders(urlStr, mapOf("User-Agent" to ua, "Accept" to "*/*"))
        }
    }
}

@UnstableApi
class MainActivity : AppCompatActivity() {

    companion object {
        var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    }

    private var sessionController: IptvSessionController? = null
    private var isPickingFile: Boolean = false

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isPickingFile = false
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        pendingFileCallback?.onReceiveValue(uris)
        pendingFileCallback = null
    }

    private val speechRecognizerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isPickingFile = false
        if (result.resultCode == RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = matches?.firstOrNull() ?: ""
            if (spokenText.isNotEmpty()) {
                val safeText = JSONObject.quote(spokenText)
                for (ctrl in SharedSyncStore.activeControllers) {
                    ctrl.webView.post {
                        ctrl.webView.evaluateJavascript("window.executeVoiceCommand && window.executeVoiceCommand($safeText);", null)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val ctrl = IptvSessionController(
            context = this,
            isCarInstance = false,
            onPickFileRequest = { intent ->
                try {
                    isPickingFile = true
                    filePickerLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    isPickingFile = false
                    false
                }
            },
            onVoiceRequest = { langCode ->
                startMalayVoiceRecognition(langCode)
            }
        )
        sessionController = ctrl
        setContentView(ctrl.getRootView())
    }

    override fun onResume() {
        super.onResume()
        sessionController?.syncFromStore()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    override fun onStop() {
        super.onStop()
        val ctrl = sessionController ?: return
        val carConnected = SharedSyncStore.activeControllers.any { it !== ctrl }
        if (!isPickingFile && !isChangingConfigurations && !carConnected) {
            if (!ctrl.bgAudioPlayOnExit) {
                ctrl.release()
                sessionController = null
                finishAndRemoveTask()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
            sessionController?.webView?.post {
                sessionController?.webView?.evaluateJavascript("window.zapFavouriteChannel && window.zapFavouriteChannel($direction);", null)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun startMalayVoiceRecognition(langCode: String = "ms-MY") {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, langCode)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Sebut nama saluran...")
        }
        try {
            isPickingFile = true
            speechRecognizerLauncher.launch(intent)
        } catch (e: Exception) {
            isPickingFile = false
        }
    }

    override fun onDestroy() {
        sessionController?.release()
        sessionController = null
        super.onDestroy()
    }
}
// === END OF MAINACTIVITY.KT ===
