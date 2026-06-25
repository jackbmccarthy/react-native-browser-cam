package com.webcamstream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.os.Build
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.facebook.react.bridge.ReactContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renders browser overlays without placing live WebView surfaces above CameraX.
 *
 * Android WebView and camera preview surfaces can fight for composition on older
 * devices. Keeping WebViews offscreen and drawing them into a plain View lets
 * the camera preview keep running while browser content is still visible and
 * capturable by the RTMP/recording path.
 */
class WebOverlayService(private val context: Context) {

    private var container: ViewGroup? = null
    private var webViewHost: FrameLayout? = null
    private var overlayView: OverlayCanvasView? = null
    private val webViews = linkedMapOf<String, WebView>()
    private var overlays = emptyList<OverlayConfig>()
    private var primaryUrl: String? = null

    var isTransparent: Boolean = true
        private set

    var updateThrottleMs: Int = 100
        private set

    var isReady: Boolean = false
        private set

    var onLoadStart: (() -> Unit)? = null
    var onLoadFinish: (() -> Unit)? = null
    var onLoadError: ((Int, String) -> Unit)? = null
    var onFrameReady: ((Bitmap) -> Unit)? = null

    fun attachTo(container: ViewGroup) {
        if (this.container === container && overlayView?.parent === container) return

        this.container = container
        val host = webViewHost ?: createHiddenWebViewHost().also { webViewHost = it }
        (host.parent as? ViewGroup)?.removeView(host)
        container.addView(
            host,
            0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val view = overlayView ?: OverlayCanvasView(context).also { overlayView = it }
        (view.parent as? ViewGroup)?.removeView(view)
        container.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        bringToFront()
        container.post { relayout() }
    }

    fun bringToFront() {
        overlayView?.bringToFront()
    }

    fun loadUrl(url: String) {
        primaryUrl = url
        updateConfig(mapOf("url" to url))
    }

    fun reload() {
        webViews.values.forEach { it.reload() }
    }

    fun injectJavaScript(js: String) {
        webViews.values.forEach { webView ->
            webView.evaluateJavascript(js) { result ->
                Log.d(TAG, "JS result: $result")
            }
        }
    }

    fun captureFrame(): Bitmap? {
        val host = container ?: return null
        if (host.width <= 0 || host.height <= 0) return null

        return Bitmap.createBitmap(host.width, host.height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.TRANSPARENT)
            overlayView?.draw(canvas)
            onFrameReady?.invoke(bitmap)
        }
    }

    fun captureFrameBytes(): ByteArray? = null

    fun setSize(width: Int, height: Int) {
        container?.post { relayout(width, height) }
    }

    fun updateTransparency(transparent: Boolean) {
        isTransparent = transparent
        webViews.values.forEach { applyTransparency(it, transparent) }
        overlayView?.invalidate()
    }

    fun updateConfig(config: Map<String, Any?>) {
        isTransparent = (config["transparent"] as? Boolean) ?: isTransparent
        updateThrottleMs = (config["updateThrottleMs"] as? Number)?.toInt() ?: updateThrottleMs

        val javaScriptEnabled = (config["javaScriptEnabled"] as? Boolean) ?: true
        overlays = parseOverlays(config)
        val activeIds = overlays.map { it.id }.toSet()

        webViews.keys.filterNot { it in activeIds }.forEach { id ->
            webViews.remove(id)?.let { webView ->
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.destroy()
            }
        }

        overlays.forEachIndexed { index, overlay ->
            val webView = webViews.getOrPut(overlay.id) { createWebView() }
            attachWebView(webView)
            webView.settings.javaScriptEnabled = javaScriptEnabled
            applyTransparency(webView, overlay.transparent)

            val normalizedUrl = normalizeUrl(overlay.url)
            if (webView.url != normalizedUrl) {
                webView.postDelayed({
                    if (webViews[overlay.id] === webView && webView.url != normalizedUrl) {
                        Log.d(TAG, "Loading overlay ${overlay.id}: $normalizedUrl")
                        webView.loadUrl(normalizedUrl)
                    }
                }, index * OVERLAY_LOAD_STAGGER_MS)
            }
        }

        (config["injectedJavaScript"] as? String)?.let { injectJavaScript(it) }
        overlayView?.setContent(overlays, webViews, updateThrottleMs)
        container?.post { relayout() }
    }

    fun destroy() {
        (overlayView?.parent as? ViewGroup)?.removeView(overlayView)
        overlayView?.setContent(emptyList(), emptyMap(), updateThrottleMs)
        overlayView = null
        (webViewHost?.parent as? ViewGroup)?.removeView(webViewHost)
        clearWebViews()
        webViewHost = null

        overlays = emptyList()
        container = null
        Log.d(TAG, "Destroyed")
    }

    fun resetForCameraStart() {
        clearWebViews()
        overlayView?.setContent(emptyList(), emptyMap(), updateThrottleMs)
        isReady = false
        Log.d(TAG, "Reset WebViews before camera start")
    }

    private fun createHiddenWebViewHost(): FrameLayout {
        return FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    }

    private fun createWebView(): WebView {
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            applyTransparency(this, isTransparent)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                WebView.enableSlowWholeDocumentDraw()
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                WebView.setWebContentsDebuggingEnabled(true)
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    isReady = false
                    onLoadStart?.invoke()
                    overlayView?.scheduleNextFrame()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    isReady = true
                    onLoadFinish?.invoke()
                    overlayView?.scheduleNextFrame()
                }

                @Suppress("DEPRECATION")
                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    onLoadError?.invoke(errorCode, description ?: "Unknown WebView error")
                    overlayView?.scheduleNextFrame()
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        onLoadError?.invoke(error?.errorCode ?: -1, error?.description?.toString() ?: "Unknown WebView error")
                        overlayView?.scheduleNextFrame()
                    }
                }
            }

            webChromeClient = WebChromeClient()
        }
    }

    private fun attachWebView(webView: WebView) {
        val host = webViewHost ?: return
        if (webView.parent === host) return

        (webView.parent as? ViewGroup)?.removeView(webView)
        host.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun clearWebViews() {
        webViews.values.forEach { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.stopLoading()
            view.destroy()
        }
        webViews.clear()
        webViewHost?.removeAllViews()
    }

    private fun parseOverlays(config: Map<String, Any?>): List<OverlayConfig> {
        val parsed = (config["overlays"] as? List<*>)?.mapIndexedNotNull { index, value ->
            (value as? Map<*, *>)?.toOverlayConfig(index, config)
        }.orEmpty()

        if (parsed.isNotEmpty()) return parsed

        val url = (config["url"] as? String)?.takeIf { it.isNotBlank() } ?: primaryUrl ?: return emptyList()
        return listOf(
            OverlayConfig(
                id = "primary",
                url = url,
                x = number(config["x"], 0f),
                y = number(config["y"], 0f),
                width = number(config["width"], 1f),
                height = number(config["height"], 1f),
                zoom = number(config["zoom"], 1f),
                opacity = number(config["opacity"], 1f),
                transparent = (config["transparent"] as? Boolean) ?: isTransparent
            )
        )
    }

    private fun Map<*, *>.toOverlayConfig(index: Int, parentConfig: Map<String, Any?>): OverlayConfig? {
        if (this["visible"] == false) return null

        val url = (this["url"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        return OverlayConfig(
            id = (this["id"] as? String)?.takeIf { it.isNotBlank() } ?: "overlay-$index",
            url = url,
            x = number(this["x"], 0f),
            y = number(this["y"], 0f),
            width = number(this["width"], 1f),
            height = number(this["height"], 1f),
            zoom = number(this["zoom"], 1f),
            opacity = number(this["opacity"], 1f).coerceIn(0f, 1f),
            transparent = (this["transparent"] as? Boolean)
                ?: (parentConfig["transparent"] as? Boolean)
                ?: isTransparent
        )
    }

    private fun relayout(widthOverride: Int? = null, heightOverride: Int? = null) {
        val host = container ?: return
        val hostWidth = widthOverride ?: host.width
        val hostHeight = heightOverride ?: host.height
        if (hostWidth <= 0 || hostHeight <= 0) return

        overlays.forEach { overlay ->
            val width = max(1, resolveSize(overlay.width, hostWidth))
            val height = max(1, resolveSize(overlay.height, hostHeight))
            webViews[overlay.id]?.let { webView ->
                val params = webView.layoutParams as? FrameLayout.LayoutParams
                if (params == null || params.width != width || params.height != height) {
                    webView.layoutParams = FrameLayout.LayoutParams(width, height)
                }
                webView.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                )
                webView.layout(0, 0, width, height)
                webView.setInitialScale((overlay.zoom * 100f).roundToInt())
            }
        }

        overlayView?.setContent(overlays, webViews, updateThrottleMs)
    }

    private fun resolveSize(value: Float, base: Int): Int {
        return if (value in 0f..1f) (value * base).roundToInt() else value.roundToInt()
    }

    private fun applyTransparency(webView: WebView, transparent: Boolean) {
        webView.setBackgroundColor(if (transparent) Color.TRANSPARENT else Color.WHITE)
    }

    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.startsWith("http://", ignoreCase = true)) {
            return "http://${trimmed.substringAfter("://")}"
        }
        if (trimmed.startsWith("https://", ignoreCase = true)) {
            return "https://${trimmed.substringAfter("://")}"
        }
        return "https://$trimmed"
    }

    private fun number(value: Any?, fallback: Float): Float {
        return (value as? Number)?.toFloat() ?: fallback
    }

    data class OverlayConfig(
        val id: String,
        val url: String,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val zoom: Float,
        val opacity: Float,
        val transparent: Boolean
    )

    private class OverlayCanvasView(context: Context) : View(context) {
        private var overlays: List<OverlayConfig> = emptyList()
        private var webViews: Map<String, WebView> = emptyMap()
        private var updateThrottleMs: Int = 100

        init {
            setWillNotDraw(false)
        }

        fun setContent(
            overlays: List<OverlayConfig>,
            webViews: Map<String, WebView>,
            updateThrottleMs: Int
        ) {
            this.overlays = overlays
            this.webViews = webViews
            this.updateThrottleMs = updateThrottleMs.coerceAtLeast(16)
            scheduleNextFrame()
        }

        fun scheduleNextFrame() {
            removeCallbacks(frameRunnable)
            postInvalidateOnAnimation()
            if (overlays.isNotEmpty()) {
                postDelayed(frameRunnable, updateThrottleMs.toLong())
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0) return

            overlays.forEach { overlay ->
                val webView = webViews[overlay.id] ?: return@forEach
                val left = resolvePosition(overlay.x, width).toFloat()
                val top = resolvePosition(overlay.y, height).toFloat()

                canvas.save()
                canvas.translate(left, top)
                canvas.clipRect(0, 0, webView.width, webView.height)
                if (overlay.opacity < 1f) {
                    canvas.saveLayerAlpha(
                        0f,
                        0f,
                        webView.width.toFloat(),
                        webView.height.toFloat(),
                        (overlay.opacity * 255).roundToInt()
                    )
                }
                drawWebView(webView, canvas)
                if (overlay.opacity < 1f) {
                    canvas.restore()
                }
                canvas.restore()
            }
        }

        private fun drawWebView(webView: WebView, canvas: Canvas) {
            webView.draw(canvas)
        }

        private fun resolvePosition(value: Float, base: Int): Int {
            return if (value in -1f..1f) (value * base).roundToInt() else value.roundToInt()
        }

        private val frameRunnable = Runnable { scheduleNextFrame() }
    }

    companion object {
        private const val TAG = "WebOverlayService"
        private const val OVERLAY_LOAD_STAGGER_MS = 250L

        fun hasRenderableContent(config: Map<String, Any?>): Boolean {
            val overlays = config["overlays"] as? List<*>
            if (overlays?.any {
                    val overlay = it as? Map<*, *> ?: return@any false
                    overlay["visible"] != false && overlay["url"] is String
                } == true) {
                return true
            }

            return (config["url"] as? String)?.isNotBlank() == true
        }
    }
}
