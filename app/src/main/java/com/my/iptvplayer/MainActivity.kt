package com.my.iptvplayer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.Gravity
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
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.net.CookieHandler
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private var exoPlayer: ExoPlayer? = null
    private lateinit var webView: WebView
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        fileUploadCallback?.onReceiveValue(uris)
        fileUploadCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 1. Trust all self-signed IPTV panel SSL certificates (like Sparkle TV / TiviMate)
        trustAllCertificates()

        // 2. Persist session cookies across 302 redirects (critical for PHP IPTV panels)
        CookieHandler.setDefault(java.net.CookieManager(null, CookiePolicy.ACCEPT_ALL))

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }

        webView = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        root.addView(playerView)
        root.addView(webView)
        setContentView(root)

        initExoPlayer()
        setupWebView()
        webView.loadUrl("file:///android_asset/index.html")
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
            Log.e("SSLTrust", "Failed to trust all certs", e)
        }
    }

    private fun logToJs(msg: String, isError: Boolean = false) {
        Log.d("IPTV_Native", msg)
        val safeMsg = JSONObject.quote("[Native] $msg")
        val fn = if (isError) "console.error" else "console.log"
        webView.post {
            webView.evaluateJavascript("$fn($safeMsg);", null)
        }
    }

    private fun initExoPlayer() {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 50000, 1000, 2500)
            .build()

        // Enable decoder fallback like TiviMate/Sparkle TV for tricky H.264/HEVC profiles
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .build()
            .also { player ->
                playerView.player = player
                player.playWhenReady = true
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        val jsState = when (state) {
                            Player.STATE_BUFFERING -> "BUFFERING"
                            Player.STATE_READY -> "READY"
                            Player.STATE_ENDED -> "ENDED"
                            else -> "IDLE"
                        }
                        logToJs("Player State -> $jsState")
                        webView.post {
                            webView.evaluateJavascript("window.onNativePlayerState && window.onNativePlayerState('$jsState');", null)
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val causeMsg = error.cause?.message ?: error.message ?: "Unknown error"
                        val fullMsg = "${error.errorCodeName}: $causeMsg"
                        logToJs("ExoPlayer Error -> $fullMsg", isError = true)
                        val safe = fullMsg.replace("'", "").replace("\n", " ")
                        webView.post {
                            webView.evaluateJavascript("window.onNativePlayerError && window.onNativePlayerError('$safe');", null)
                        }
                    }
                })
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

        webView.addJavascriptInterface(AndroidBridge(this), "AndroidBridge")
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback
                return try {
                    val intent = fileChooserParams?.createIntent()
                    if (intent != null) {
                        filePickerLauncher.launch(intent)
                        true
                    } else false
                } catch (e: Exception) {
                    fileUploadCallback = null
                    false
                }
            }
        }
    }

    private fun hexToBase64Url(hex: String): String {
        val clean = hex.trim().replace("-", "")
        if (clean.length % 2 != 0 || !clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            // Already Base64 or Base64Url
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
        // If the M3U or remote key server already provided valid JSON {"keys":[...]}
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
        logToJs("Built ClearKey JSON: $json")
        return json.toByteArray(Charsets.UTF_8)
    }

    private data class ResolvedStream(
        val finalUrl: String,
        val detectedType: String,
        val statusCode: Int
    )

    // Pre-flight resolver: follows HTTP<->HTTPS redirects and checks Content-Type / first bytes
    private fun resolveStreamUrl(urlStr: String, headers: Map<String, String>, hintType: String): ResolvedStream {
        var currentUrl = urlStr
        var status = 0
        var contentType = ""
        var firstChunk = ""
        try {
            for (hop in 0 until 5) {
                val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    connectTimeout = 10000
                    readTimeout = 10000
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                }
                status = conn.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val loc = conn.getHeaderField("Location") ?: break
                    currentUrl = URL(URL(currentUrl), loc).toString()
                    logToJs("Redirect ($status) -> $currentUrl")
                    conn.disconnect()
                    continue
                }
                contentType = (conn.contentType ?: "").lowercase()
                if (status in 200..299) {
                    val buf = ByteArray(256)
                    val read = conn.inputStream.read(buf)
                    if (read > 0) {
                        firstChunk = String(buf, 0, read, Charsets.UTF_8).trim()
                    }
                }
                conn.disconnect()
                break
            }
        } catch (e: Exception) {
            logToJs("Pre-flight probe note: ${e.message}")
        }

        val lowerUrl = currentUrl.lowercase()
        val detected = when {
            hintType.equals("mpd", true) || lowerUrl.contains(".mpd") || contentType.contains("dash+xml") || firstChunk.contains("<MPD") -> "mpd"
            hintType.equals("hls", true) || lowerUrl.contains(".m3u8") || contentType.contains("mpegurl") || firstChunk.startsWith("#EXTM3U") -> "hls"
            else -> "auto"
        }
        return ResolvedStream(currentUrl, detected, status)
    }

    private fun fetchUrlTextWithHeaders(urlStr: String, headers: Map<String, String>): String {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 15000
                instanceFollowRedirects = true
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = conn.responseCode
            logToJs("Fetch [$code] $urlStr")
            if (code >= 400) return ""
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            logToJs("Fetch failed: ${e.message}", isError = true)
            ""
        }
    }

    inner class AndroidBridge(private val context: Context) {
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
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                logToJs("Blocked invalid URL: $cleanUrl", isError = true)
                return
            }

            val ua = if (!userAgent.isNullOrBlank()) userAgent.trim() else "TiviMate/4.7.0 (Linux; Android 13)"
            val cookieValue = CookieManager.getInstance().getCookie(cleanUrl)

            val headers = mutableMapOf<String, String>().apply {
                put("User-Agent", ua)
                put("Accept", "*/*")
                put("Connection", "keep-alive")
                if (!referer.isNullOrBlank()) {
                    put("Referer", referer.trim())
                    try {
                        val u = URL(referer.trim())
                        put("Origin", "${u.protocol}://${u.Authority}")
                    } catch (_: Exception) {}
                }
                if (!cookieValue.isNullOrBlank()) put("Cookie", cookieValue)
            }

            logToJs("Tuning URL: $cleanUrl | HintType: $type | UA: $ua | ClearKey: ${!clearKey.isNullOrBlank()} | LicenseUrl: ${!licenseUrl.isNullOrBlank()}")

            thread {
                // 1. Resolve redirects & actual manifest type (MPD vs HLS vs raw TS)
                val resolved = resolveStreamUrl(cleanUrl, headers, type)
                logToJs("Resolved -> Type: ${resolved.detectedType} | Status: ${resolved.statusCode} | FinalURL: ${resolved.finalUrl}")

                // 2. If ClearKey is a remote URL (common on Astro PHP panels), try fetching the ClearKey JSON first
                var resolvedClearKey = clearKey?.trim() ?: ""
                var clearKeyHttpUrl = ""
                if (resolvedClearKey.startsWith("http://") || resolvedClearKey.startsWith("https://")) {
                    val cleanCkUrl = resolvedClearKey.split("|")[0].trim()
                    val body = fetchUrlTextWithHeaders(cleanCkUrl, headers).trim()
                    if (body.startsWith("{") || body.contains(":")) {
                        resolvedClearKey = body
                        logToJs("Fetched remote ClearKey payload: $body")
                    } else {
                        clearKeyHttpUrl = cleanCkUrl
                    }
                }

                runOnUiThread {
                    val player = exoPlayer ?: return@runOnUiThread
                    player.stop()
                    player.clearMediaItems()

                    val dataSourceFactory = DefaultHttpDataSource.Factory()
                        .setUserAgent(ua)
                        .setDefaultRequestProperties(headers)
                        .setAllowCrossProtocolRedirects(true)
                        .setConnectTimeoutMs(25000)
                        .setReadTimeoutMs(25000)

                    val mediaItemBuilder = MediaItem.Builder()
                        .setUri(Uri.parse(resolved.finalUrl))

                    when (resolved.detectedType) {
                        "mpd" -> mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
                        "hls" -> mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
                        // "auto" leaves MimeType unset so ExoPlayer handles raw .ts or progressive streams natively
                    }

                    val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

                    // 3. Configure Multi-Session ClearKey or Widevine DRM
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
                            logToJs("ClearKey parse error: ${e.message}", isError = true)
                        }
                    } else if (!licenseUrl.isNullOrBlank() && licenseUrl.startsWith("http")) {
                        val cleanLicUrl = licenseUrl.split("|")[0].trim()
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

        @JavascriptInterface
        fun togglePlayPause(): Boolean {
            var playing = false
            runOnUiThread {
                exoPlayer?.let {
                    if (it.isPlaying) it.pause() else it.play()
                    playing = it.isPlaying
                }
            }
            return playing
        }

        @JavascriptInterface
        fun setMuted(muted: Boolean) {
            runOnUiThread { exoPlayer?.volume = if (muted) 0f else 1f }
        }

        @JavascriptInterface
        fun fetchRemoteText(urlStr: String, userAgent: String?): String {
            val ua = if (!userAgent.isNullOrBlank()) userAgent else "TiviMate/4.7.0 (Linux; Android 13)"
            return fetchUrlTextWithHeaders(urlStr, mapOf("User-Agent" to ua, "Accept" to "*/*"))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
    }
}
