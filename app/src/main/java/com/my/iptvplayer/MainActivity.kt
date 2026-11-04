package com.my.iptvplayer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.net.HttpURLConnection
import java.net.URL

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

    private fun initExoPlayer() {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 50000, 1500, 3000)
            .build()

        exoPlayer = ExoPlayer.Builder(this)
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
                        webView.post {
                            webView.evaluateJavascript("window.onNativePlayerState && window.onNativePlayerState('$jsState');", null)
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val msg = (error.cause?.message ?: error.message ?: "Stream error").replace("'", "")
                        webView.post {
                            webView.evaluateJavascript("window.onNativePlayerError && window.onNativePlayerError('$msg');", null)
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

    inner class AndroidBridge(private val context: Context) {
        @JavascriptInterface
        fun playNativeStream(url: String, type: String, userAgent: String?, referer: String?, licenseUrl: String?) {
            val cleanUrl = url.trim()
            
            // Reject invalid URLs or HTML block pages like <noscript>
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://") || cleanUrl.contains("<") || cleanUrl.contains(">")) {
                runOnUiThread {
                    val msg = "Blocked invalid URL or HTML response: $cleanUrl"
                    webView.evaluateJavascript("window.onNativePlayerError && window.onNativePlayerError('$msg');", null)
                }
                return
            }

            val cookieValue = CookieManager.getInstance().getCookie(cleanUrl)

            runOnUiThread {
                val player = exoPlayer ?: return@runOnUiThread
                player.stop()
                player.clearMediaItems()

                val ua = if (!userAgent.isNullOrBlank()) userAgent else "TiviMate/5.3.3 (Linux; Android 13)"
                val headers = mutableMapOf<String, String>().apply {
                    put("User-Agent", ua)
                    put("Accept", "*/*")
                    put("Connection", "keep-alive")
                    if (!referer.isNullOrBlank()) put("Referer", referer)
                    if (!cookieValue.isNullOrBlank()) put("Cookie", cookieValue)
                }

                val dataSourceFactory = DefaultHttpDataSource.Factory()
                    .setUserAgent(ua)
                    .setDefaultRequestProperties(headers)
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(25000)
                    .setReadTimeoutMs(25000)

                // Dynamically select MIME type based on stream format (.mpd vs .m3u8)
                val isMpd = type.equals("mpd", ignoreCase = true) || cleanUrl.lowercase().contains(".mpd")
                val mediaItemBuilder = MediaItem.Builder()
                    .setUri(Uri.parse(cleanUrl))
                    .setMimeType(if (isMpd) MimeTypes.APPLICATION_MPD else MimeTypes.APPLICATION_M3U8)

                if (!licenseUrl.isNullOrBlank() && licenseUrl.startsWith("http")) {
                    mediaItemBuilder.setDrmConfiguration(
                        MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                            .setLicenseUri(licenseUrl)
                            .setLicenseRequestHeaders(headers)
                            .build()
                    )
                }

                val mediaSource = DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItemBuilder.build())
                player.setMediaSource(mediaSource)
                player.prepare()
                player.play()
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
            return try {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 20000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", userAgent ?: "TiviMate/5.3.3 (Linux; Android 13)")
                }
                conn.inputStream.bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                ""
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
    }
}
