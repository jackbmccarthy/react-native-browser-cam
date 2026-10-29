package com.webcamstream

// OpenScoreboard browser page lifetime patch v1

// OpenScoreboard browser viewport patch v10
// OpenScoreboard browser animation cadence patch v3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
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
internal interface BrowserOverlayFrameSource {
    val browserFrameRevision: Long
    val hasBrowserFrames: Boolean
    fun retainBrowserOutput()
    fun releaseBrowserOutput()
}

class WebOverlayService(private val context: Context) {

    private var container: ViewGroup? = null
    private var webViewHost: FrameLayout? = null
    private var overlayView: OverlayCanvasView? = null
    private val webViews = linkedMapOf<String, WebView>()
    private val requestedUrls = mutableMapOf<String, String>()
    private val loadedUrls = mutableMapOf<String, String>()
    private val navigationGenerations = mutableMapOf<String, Long>()
    private val reloadRevisions = mutableMapOf<String, Long>()
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
                Log.d(TAG, "JavaScript evaluation completed")
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

    fun hasActiveOverlays(): Boolean {
        return overlays.any { it.url.isNotBlank() }
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
            requestedUrls.remove(id)
            loadedUrls.remove(id)
            navigationGenerations.remove(id)
            reloadRevisions.remove(id)
        }

        overlays.forEachIndexed { index, overlay ->
            val webView = webViews.getOrPut(overlay.id) { createWebView() }
            attachWebView(webView)
            webView.settings.javaScriptEnabled = javaScriptEnabled
            applyTransparency(webView, overlay.transparent)

            val normalizedUrl = normalizeUrl(overlay.url)
            val previousRevision = reloadRevisions[overlay.id]
            val revision = overlay.reloadRevision
            if (revision != null && (previousRevision == null || revision > previousRevision)) {
                reloadRevisions[overlay.id] = revision
            }
            if (requestedUrls[overlay.id] != normalizedUrl) {
                requestedUrls[overlay.id] = normalizedUrl
                val generation = (navigationGenerations[overlay.id] ?: 0L) + 1L
                navigationGenerations[overlay.id] = generation
                webView.postDelayed({
                    if (
                        webViews[overlay.id] === webView
                        && requestedUrls[overlay.id] == normalizedUrl
                        && navigationGenerations[overlay.id] == generation
                    ) {
                        Log.d(TAG, "Loading overlay")
                        loadedUrls[overlay.id] = normalizedUrl
                        webView.loadUrl(normalizedUrl)
                    }
                }, index * OVERLAY_LOAD_STAGGER_MS)
            } else if (revision != null && previousRevision != null && revision > previousRevision
                && loadedUrls[overlay.id] == normalizedUrl) {
                webView.reload()
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
        // Compatibility alias: camera restarts do not invalidate browser documents.
        overlayView?.setContent(overlays, webViews, updateThrottleMs)
    }

    private fun createHiddenWebViewHost(): FrameLayout {
        return object : FrameLayout(context) {
            // Keep Chromium visible/attached, but do not paint hidden children twice.
            override fun dispatchDraw(canvas: Canvas) = Unit
        }.apply {
            clipChildren = false
            clipToPadding = false
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    }

    private fun createWebView(): WebView {
        // Must be enabled before constructing WebViews, not inside WebView.apply.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            WebView.enableSlowWholeDocumentDraw()
        }
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // CSS layout follows the declared viewport, not a legacy 980px browser width.
            settings.loadWithOverviewMode = false
            settings.useWideViewPort = false
            settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
            settings.textZoom = 100
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            // Overlapping attached WebViews must not rely on visible HW compositor tiles.
            setLayerType(View.LAYER_TYPE_NONE, null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Chromium tiles are additional to the bounded bitmap cache; profile this trial.
                settings.offscreenPreRaster = false
            }
            // Default page scale includes display density; explicit 100 does not.
            setInitialScale(0)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            applyTransparency(this, isTransparent)

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
                    // Size/zoom updates never navigate or mutate page-authored DOM styles.
                    view?.scrollTo(0, 0)
                    relayout()
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
        requestedUrls.clear()
        loadedUrls.clear()
        navigationGenerations.clear()
        reloadRevisions.clear()
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
                reloadRevision = parseReloadRevision(config["reloadRevision"]),
                url = url,
                x = number(config["x"], 0f),
                y = number(config["y"], 0f),
                width = number(config["width"], 1f),
                height = number(config["height"], 1f),
                viewportWidth = viewportDimension(config["viewportWidth"], 160, 7680),
                viewportHeight = viewportDimension(config["viewportHeight"], 90, 4320),
                referenceWidth = viewportDimension(config["browserReferenceWidth"], 160, 7680) ?: 1920,
                referenceHeight = viewportDimension(config["browserReferenceHeight"], 90, 4320) ?: 1080,
                responsive = config["viewportMode"] == "responsive",
                zoom = number(config["zoom"], 1f).coerceIn(0.1f, 5f),
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
            reloadRevision = parseReloadRevision(this["reloadRevision"]),
            url = url,
            x = number(this["x"], 0f),
            y = number(this["y"], 0f),
            width = number(this["width"], 1f),
            height = number(this["height"], 1f),
            viewportWidth = viewportDimension(this["viewportWidth"], 160, 7680),
            viewportHeight = viewportDimension(this["viewportHeight"], 90, 4320),
            referenceWidth = viewportDimension(parentConfig["browserReferenceWidth"], 160, 7680) ?: 1920,
            referenceHeight = viewportDimension(parentConfig["browserReferenceHeight"], 90, 4320) ?: 1080,
            responsive = this["viewportMode"] == "responsive",
            zoom = number(this["zoom"], 1f).coerceIn(0.1f, 5f),
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
            val cssWidth = if (!overlay.responsive && overlay.viewportWidth != null && overlay.viewportHeight != null) {
                overlay.viewportWidth
            } else {
                resolveSize(overlay.width, overlay.referenceWidth).coerceIn(160, 7680)
            }
            val cssHeight = if (!overlay.responsive && overlay.viewportWidth != null && overlay.viewportHeight != null) {
                overlay.viewportHeight
            } else {
                resolveSize(overlay.height, overlay.referenceHeight).coerceIn(90, 4320)
            }
            val density = context.resources.displayMetrics.density
            val width = max(1, (cssWidth * density).roundToInt())
            val height = max(1, (cssHeight * density).roundToInt())
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
                webView.scrollTo(0, 0)
            }
        }

        overlayView?.setContent(overlays, webViews, updateThrottleMs)
    }

    private fun parseReloadRevision(value: Any?): Long? {
        val revision = (value as? Number)?.toDouble() ?: return null
        if (!revision.isFinite() || revision < 0.0 || revision > 9_007_199_254_740_991.0 || revision % 1.0 != 0.0) return null
        return revision.toLong()
    }

    private fun viewportDimension(value: Any?, min: Int, max: Int): Int? {
        val dimension = (value as? Number)?.toDouble() ?: return null
        if (!dimension.isFinite() || dimension <= 0.0) return null
        return dimension.coerceIn(min.toDouble(), max.toDouble()).roundToInt()
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
        val parsed = (value as? Number)?.toFloat() ?: return fallback
        return if (parsed.isFinite()) parsed else fallback
    }

    data class OverlayConfig(
        val id: String,
        val reloadRevision: Long?,
        val url: String,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val viewportWidth: Int?,
        val viewportHeight: Int?,
        val referenceWidth: Int,
        val referenceHeight: Int,
        val responsive: Boolean,
        val zoom: Float,
        val opacity: Float,
        val transparent: Boolean
    )

    private class OverlayCanvasView(context: Context) : View(context), BrowserOverlayFrameSource {
        override var browserFrameRevision = 0L
            private set
        private var browserOutputConsumers = 0
        override val hasBrowserFrames: Boolean
            get() = overlays.any { rasterFrames[webViews[it.id]] != null }

        override fun retainBrowserOutput() {
            browserOutputConsumers++
            scheduleCapture()
        }

        override fun releaseBrowserOutput() {
            browserOutputConsumers = (browserOutputConsumers - 1).coerceAtLeast(0)
            if (!canCapture()) {
                removeCallbacks(frameRunnable)
                capturePosted = false
                nextCaptureDeadlineMs = 0.0
            }
        }

        override fun onVisibilityChanged(changedView: View, visibility: Int) {
            super.onVisibilityChanged(changedView, visibility)
            if (isShown || browserOutputConsumers > 0) scheduleCapture()
            else {
                removeCallbacks(frameRunnable)
                capturePosted = false
                nextCaptureDeadlineMs = 0.0
            }
        }
        private var overlays: List<OverlayConfig> = emptyList()
        private var webViews: Map<String, WebView> = emptyMap()
        private var updateThrottleMs: Int = 100
        private val rasterFrames = mutableMapOf<WebView, RasterFrame>()
        private val loggedGeometry = mutableSetOf<WebView>()
        private val rasterPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private var pixelsPerRaster = MAX_RASTER_PIXELS
        private var capturePosted = false
        private var nextCaptureDeadlineMs = 0.0
        private var lastMetricsAtMs = 0L
        private var capturesSinceMetrics = 0
        private var captureTimeSinceMetricsMs = 0L

        private class RasterFrame(val bitmap: Bitmap) {
            val canvas = Canvas(bitmap)
            var capturedAtMs = -1L
            var metricsLogged = false
            var alphaLogged = false
            var alphaReadyAtMs = -1L
        }

        init {
            setWillNotDraw(false)
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(frameRunnable)
            capturePosted = false
            nextCaptureDeadlineMs = 0.0
            clearRasterFrames()
            super.onDetachedFromWindow()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            scheduleNextFrame()
        }

        private fun clearRasterFrames() {
            // HW display lists can retain bitmap pixels after this draw returns.
            rasterFrames.clear()
            browserFrameRevision++
        }

        fun setContent(
            overlays: List<OverlayConfig>,
            webViews: Map<String, WebView>,
            updateThrottleMs: Int
        ) {
            val activeViews = webViews.values.toSet()
            loggedGeometry.retainAll(activeViews)
            val nextBudget = minOf(MAX_RASTER_PIXELS, MAX_TOTAL_RASTER_PIXELS / activeViews.size.coerceAtLeast(1))
            if (nextBudget != pixelsPerRaster) {
                clearRasterFrames()
                pixelsPerRaster = nextBudget
            } else {
                rasterFrames.keys.filterNot { it in activeViews }.forEach { view ->
                    rasterFrames.remove(view)
                }
            }
            browserFrameRevision++
            this.overlays = overlays
            this.webViews = webViews
            this.updateThrottleMs = updateThrottleMs.coerceAtLeast(16)
            if (overlays.isEmpty()) {
                removeCallbacks(frameRunnable)
                capturePosted = false
            }
            scheduleNextFrame()
        }

        fun scheduleNextFrame() {
            postInvalidateOnAnimation()
            scheduleCapture()
        }

        private fun canCapture(): Boolean =
            isAttachedToWindow && windowVisibility == View.VISIBLE && (isShown || browserOutputConsumers > 0) && overlays.isNotEmpty()

        private fun scheduleCapture() {
            if (!canCapture() || capturePosted) return
            val now = SystemClock.uptimeMillis()
            if (nextCaptureDeadlineMs <= now) {
                nextCaptureDeadlineMs = now + captureIntervalMs()
            }
            capturePosted = true
            postDelayed(frameRunnable, max(1L, kotlin.math.ceil(nextCaptureDeadlineMs - now).toLong()))
        }

        private fun captureIntervalMs(): Double = max(1000.0 / 30.0, updateThrottleMs.toDouble())

        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            if (visibility == View.VISIBLE) scheduleCapture()
            else {
                removeCallbacks(frameRunnable)
                capturePosted = false
                nextCaptureDeadlineMs = 0.0
            }
        }

        private fun captureNextRaster() {
            capturePosted = false
            if (!canCapture()) return
            val active = overlays.mapNotNull { webViews[it.id] }.distinct()
            if (active.isEmpty()) return
            val startedAt = SystemClock.uptimeMillis()
            // Every eligible browser gets this animation frame, not one slot per tick.
            active.forEach { webView ->
                try {
                    refreshRaster(webView)
                } catch (error: RuntimeException) {
                    Log.w("WebOverlayPerformance", "Browser capture failed")
                }
            }
            browserFrameRevision++
            val completedAt = SystemClock.uptimeMillis()
            capturesSinceMetrics += active.size
            captureTimeSinceMetricsMs += completedAt - startedAt
            if (completedAt - lastMetricsAtMs >= 10_000L) {
                Log.d("WebOverlayPerformance", "captures=$capturesSinceMetrics captureMs=$captureTimeSinceMetricsMs cachePixels=${rasterFrames.values.sumOf { it.bitmap.width.toLong() * it.bitmap.height }}")
                capturesSinceMetrics = 0
                captureTimeSinceMetricsMs = 0L
                lastMetricsAtMs = completedAt
            }
            nextCaptureDeadlineMs += captureIntervalMs()
            // Slow painting skips the missed deadline. Never queue catch-up batches.
            if (nextCaptureDeadlineMs <= completedAt) {
                nextCaptureDeadlineMs = completedAt + 1.0
            }
            postInvalidateOnAnimation()
            scheduleCapture()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0) return

            overlays.forEach { overlay ->
                val webView = webViews[overlay.id] ?: return@forEach
                val left = resolvePosition(overlay.x, width).toFloat()
                val top = resolvePosition(overlay.y, height).toFloat()

                if (webView.width <= 0 || webView.height <= 0) return@forEach
                val frameWidth = resolveSize(overlay.width, width).coerceAtLeast(1).toFloat()
                val frameHeight = resolveSize(overlay.height, height).coerceAtLeast(1).toFloat()
                val fitScale = minOf(frameWidth / webView.width, frameHeight / webView.height)
                val contentScale = fitScale * overlay.zoom.coerceIn(0.1f, 5f)
                if (webView.progress == 100 && webView.contentHeight > 0 && loggedGeometry.add(webView)) {
                    Log.d("WebOverlayFrame",
                        "slot=${overlays.indexOf(overlay)} x=${overlay.x} y=${overlay.y} w=${overlay.width} h=${overlay.height} " +
                        "view=${width}x${height} frame=${frameWidth}x${frameHeight} fit=$fitScale " +
                        "zoom=${overlay.zoom} contentScale=$contentScale webView=${webView.width}x${webView.height}")
                }

                canvas.save()
                canvas.translate(left, top)
                // Clip in destination space before scaling; never clip at the phone-sized WebView bounds.
                canvas.clipRect(0f, 0f, frameWidth, frameHeight)
                if (overlay.opacity < 1f) {
                    canvas.saveLayerAlpha(
                        0f,
                        0f,
                        frameWidth,
                        frameHeight,
                        (overlay.opacity * 255).roundToInt()
                    )
                }
                // One transform scales every rendered DOM element, including fixed content and iframes.
                canvas.save()
                canvas.scale(contentScale, contentScale)
                drawWebView(webView, canvas)
                canvas.restore()
                if (overlay.opacity < 1f) {
                    canvas.restore()
                }
                canvas.restore()
            }
        }

        private fun refreshRaster(webView: WebView) {
            if (webView.width <= 0 || webView.height <= 0) return
            val sourcePixels = webView.width.toLong() * webView.height.toLong()
            val budgetScale = kotlin.math.sqrt(pixelsPerRaster.toDouble() / sourcePixels)
            // Capture at the largest projected output size, not CSS pixels times phone density.
            // Preserve detail when zoom magnifies the document, with the same aggregate ceiling.
            val outputScale = overlays.filter { webViews[it.id] === webView }.maxOfOrNull { overlay ->
                val outputWidth = max(width, overlay.referenceWidth)
                val outputHeight = max(height, overlay.referenceHeight)
                val frameWidth = resolveSize(overlay.width, outputWidth).coerceAtLeast(1).toDouble()
                val frameHeight = resolveSize(overlay.height, outputHeight).coerceAtLeast(1).toDouble()
                minOf(frameWidth / webView.width, frameHeight / webView.height) * overlay.zoom.coerceIn(0.1f, 5f)
            } ?: 1.0
            val rasterScale = minOf(1.0, budgetScale, outputScale)
            val rasterWidth = max(1, (webView.width * rasterScale).toInt())
            val rasterHeight = max(1, (webView.height * rasterScale).toInt())
            var frame = rasterFrames[webView]
            if (frame == null || frame.bitmap.width != rasterWidth || frame.bitmap.height != rasterHeight) {
                val bitmap = Bitmap.createBitmap(rasterWidth, rasterHeight, Bitmap.Config.ARGB_8888)
                bitmap.density = context.resources.displayMetrics.densityDpi
                frame = RasterFrame(bitmap)
                rasterFrames[webView] = frame
            }
            frame.bitmap.eraseColor(Color.TRANSPARENT)
            val rasterCanvas = frame.canvas
            rasterCanvas.save()
            try {
                rasterCanvas.scale(rasterWidth.toFloat() / webView.width, rasterHeight.toFloat() / webView.height)
                webView.draw(rasterCanvas)
                frame.capturedAtMs = SystemClock.uptimeMillis()
            } finally {
                rasterCanvas.restore()
            }
        }

        private fun drawWebView(webView: WebView, canvas: Canvas) {
            // Preview and encoder consumers never invoke Chromium painting.
            val frame = rasterFrames[webView] ?: return
            canvas.drawBitmap(frame.bitmap, null, RectF(0f, 0f, webView.width.toFloat(), webView.height.toFloat()), rasterPaint)
        }

        companion object {
            private const val MAX_RASTER_PIXELS = 2_097_152
            private const val MAX_TOTAL_RASTER_PIXELS = 4_194_304
        }

        private fun resolveSize(value: Float, base: Int): Int {
            return if (value in 0f..1f) (value * base).roundToInt() else value.roundToInt()
        }

        private fun resolvePosition(value: Float, base: Int): Int {
            return if (value in -1f..1f) (value * base).roundToInt() else value.roundToInt()
        }

        private val frameRunnable = Runnable { captureNextRaster() }
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
