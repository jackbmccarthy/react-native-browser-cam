package com.webcamstream

// OpenScoreboard Android GPU browser host v1

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Placement uses legacy normalized-or-absolute coordinates; viewport dimensions are CSS pixels.
data class GpuBrowserLayer(
    val id: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val zoom: Float,
    val opacity: Float
)

internal class GpuBrowserOverlayService(private val context: Context, private val renderer: GpuSceneRenderer) {
    private val main = Handler(Looper.getMainLooper())
    private val entries = linkedMapOf<String, Entry>()
    private var overlays = emptyList<Overlay>()
    private var transparent = true
    private var javaScriptEnabled = true
    private var injectedJavaScript: String? = null
    private var lastConfig = emptyMap<String, Any?>()
    private var primaryUrl: String? = null
    private var outputWidth = 0
    private var outputHeight = 0
    private var destroyed = false

    @Volatile var isReady: Boolean = true
        private set
    @Volatile private var active = false
    @Volatile var onLoadStart: (() -> Unit)? = null
    @Volatile var onLoadFinish: (() -> Unit)? = null
    @Volatile var onLoadError: ((Int, String) -> Unit)? = null

    fun hasActiveOverlays(): Boolean = active

    fun updateConfig(config: Map<String, Any?>) {
        // React callers may reuse their maps before this Main-thread command runs.
        val snapshot = config.toMutableMap().apply {
            this["overlays"] = (config["overlays"] as? List<*>)?.map { (it as? Map<*, *>)?.toMap() }
        }
        onMain {
            if (destroyed) return@onMain
            lastConfig = snapshot
            transparent = (snapshot["transparent"] as? Boolean) ?: transparent
            javaScriptEnabled = (snapshot["javaScriptEnabled"] as? Boolean) ?: true
            injectedJavaScript = snapshot["injectedJavaScript"] as? String
            overlays = parseOverlays(snapshot)
            val ids = overlays.map { it.layer.id }.toSet()
            entries.keys.filterNot { it in ids }.forEach { id -> entries.remove(id)?.let { dispose(it) } }
            val budget = minOf(MAX_INPUT_PIXELS, MAX_TOTAL_PIXELS / overlays.size.coerceAtLeast(1))
            overlays.forEach { overlay ->
                val entry = entries.getOrPut(overlay.layer.id) { Entry(overlay, allocation(overlay.layer, budget)) }
                entry.overlay = overlay
            }
            // Shrink before growing so adding another page does not transiently double the budget.
            val targets = overlays.associate { it.layer.id to allocation(it.layer, budget) }
            entries.values.sortedBy { targets.getValue(it.overlay.layer.id).pixels - it.size.pixels }.forEach { entry ->
                val size = targets.getValue(entry.overlay.layer.id)
                if (entry.size != size) resize(entry, size)
                if (!entry.inputRequested) create(entry)
                entry.webView?.let { view ->
                    applySettings(view, entry.overlay)
                    navigate(entry)
                }
            }
            publishLayers()
            updateReady()
        }
    }

    // Output geometry belongs to the compositor, not the browser document or display density.
    fun setSize(w: Int, h: Int) = onMain {
        if (!destroyed) {
            outputWidth = w.coerceAtLeast(0)
            outputHeight = h.coerceAtLeast(0)
        }
    }

    fun reload() = onMain {
        if (destroyed) return@onMain
        entries.values.forEach { entry ->
            if (!entry.inputRequested) create(entry)
            if (entry.webView != null) navigate(entry, forceReload = true)
        }
        updateReady()
    }

    fun loadUrl(url: String) = onMain {
        if (destroyed) return@onMain
        val normalizedUrl = normalizeUrl(url)
        primaryUrl = normalizedUrl
        val primary = overlays.firstOrNull()
        if (primary == null) {
            updateConfig(lastConfig - "overlays" + ("url" to normalizedUrl))
            return@onMain
        }
        // Navigation changes only the primary page URL, not its frame or CSS viewport.
        val updated = primary.copy(url = normalizedUrl)
        overlays = listOf(updated) + overlays.drop(1)
        val entry = entries.getValue(primary.layer.id)
        entry.overlay = updated
        if (!entry.inputRequested) create(entry)
        if (entry.webView != null) navigate(entry)
        updateReady()
    }

    fun injectJavaScript(js: String) = onMain {
        if (!destroyed) entries.values.forEach { it.webView?.evaluateJavascript(js, null) }
    }

    fun destroy() = onMain {
        if (destroyed) return@onMain
        destroyed = true
        val removed = entries.values.toList()
        entries.clear()
        overlays = emptyList()
        renderer.setLayers(emptyList())
        removed.forEach { dispose(it) }
        active = false
        isReady = true
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }

    private fun current(entry: Entry): Boolean = !destroyed && entries[entry.overlay.layer.id] === entry

    private fun create(entry: Entry) {
        entry.inputRequested = true
        entry.failed = false
        val generation = ++entry.creationGeneration
        val inputId = "${entry.overlay.layer.id}:${nextInputSequence.incrementAndGet()}"
        entry.inputId = inputId
        val requestedSize = entry.size
        renderer.createInput(inputId, requestedSize.width, requestedSize.height, onFailure = { message ->
            onMain {
                if (current(entry) && entry.creationGeneration == generation) producerError(entry, message)
            }
        }) { input ->
            onMain {
                // Disposal already queued this creation's unique input key for retirement.
                // Never let an obsolete callback attach a producer to a replacement page.
                if (!current(entry) || entry.creationGeneration != generation) return@onMain
                try {
                    check(input.id == inputId && input.surface.isValid) { "Invalid GPU browser surface" }
                    val size = entry.size
                    if (size != requestedSize) renderer.resizeInput(input.id, size.width, size.height)
                    val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                    val display = manager.createVirtualDisplay(
                        "BrowserCam-${input.id}", size.width, size.height, size.densityDpi, input.surface,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                        object : VirtualDisplay.Callback() {
                            override fun onStopped() = onMain {
                                if (current(entry) && entry.creationGeneration == generation) producerError(entry, "GPU browser display stopped")
                            }
                        }, main
                    ) ?: error("Could not create GPU browser display")
                    entry.display = display
                    val presentation = Presentation(context, display.display, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
                    entry.presentation = presentation
                    val window = checkNotNull(presentation.window)
                    // Translucency is an opt-in device capability, not a portable VD guarantee.
                    window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    window.setFormat(PixelFormat.TRANSLUCENT)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                    val host = FrameLayout(presentation.context).apply { setBackgroundColor(Color.TRANSPARENT) }
                    val view = createWebView(presentation.context)
                    entry.webView = view
                    applySettings(view, entry.overlay)
                    host.addView(view, FrameLayout.LayoutParams(size.width, size.height))
                    presentation.setContentView(host)
                    presentation.setOnCancelListener {
                        if (current(entry) && entry.creationGeneration == generation) producerError(entry, "GPU browser presentation cancelled")
                    }
                    presentation.show()
                    window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    navigate(entry)
                    publishLayers()
                } catch (error: RuntimeException) {
                    producerError(entry, error.message ?: "GPU browser creation failed")
                }
                updateReady()
            }
        }
    }

    private fun resize(entry: Entry, size: Allocation) {
        entry.size = size
        if (entry.webView == null) return
        val inputId = entry.inputId ?: return
        try {
            renderer.resizeInput(inputId, size.width, size.height)
            entry.display?.resize(size.width, size.height, size.densityDpi)
            entry.webView?.layoutParams = FrameLayout.LayoutParams(size.width, size.height)
        } catch (error: RuntimeException) {
            producerError(entry, error.message ?: "GPU browser resize failed")
        }
    }

    private fun createWebView(displayContext: Context): WebView = WebView(displayContext).apply {
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = false
        settings.useWideViewPort = false
        settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
        settings.textZoom = 100
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) settings.offscreenPreRaster = false
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        setInitialScale(0)
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        webChromeClient = WebChromeClient()
    }

    private fun applySettings(view: WebView, overlay: Overlay) {
        view.settings.javaScriptEnabled = javaScriptEnabled
        view.setBackgroundColor(if (overlay.transparent) Color.TRANSPARENT else Color.WHITE)
    }

    private fun navigate(entry: Entry, forceReload: Boolean = false) {
        val view = entry.webView ?: return
        val url = entry.overlay.url
        val revision = entry.overlay.reloadRevision
        val previousRevision = entry.reloadRevision
        if (revision != null && (previousRevision == null || revision > previousRevision)) entry.reloadRevision = revision
        val changedUrl = entry.requestedUrl != url
        val revised = revision != null && previousRevision != null && revision > previousRevision
        if (!changedUrl && !revised && !forceReload) return
        val generation = ++entry.navigationGeneration
        entry.requestedUrl = url
        entry.finished = false
        entry.failed = false
        updateReady()
        view.stopLoading()
        view.webViewClient = object : WebViewClient() {
            private fun live(): Boolean = current(entry) && entry.webView === view && entry.navigationGeneration == generation
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (!live()) return
                entry.finished = false
                updateReady()
                onLoadStart?.invoke()
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                if (!live() || entry.failed || url != view?.url || (view?.progress ?: 0) < 100) return
                entry.finished = true
                view?.scrollTo(0, 0)
                injectedJavaScript?.let { view?.evaluateJavascript(it, null) }
                updateReady()
                onLoadFinish?.invoke()
            }
            @Suppress("DEPRECATION")
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M && live()) pageError(entry, errorCode, description ?: "Unknown WebView error")
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (live() && request?.isForMainFrame == true) {
                    pageError(entry, error?.errorCode ?: -1, error?.description?.toString() ?: "Unknown WebView error")
                }
            }
        }
        if (changedUrl) view.loadUrl(url) else view.reload()
    }

    private fun pageError(entry: Entry, code: Int, message: String) {
        if (entry.failed) return
        entry.failed = true
        entry.finished = false
        updateReady()
        onLoadError?.invoke(code, message)
    }

    private fun producerError(entry: Entry, message: String) {
        dispose(entry)
        entry.requestedUrl = null
        pageError(entry, -1, message)
        publishLayers()
    }

    private fun dispose(entry: Entry) {
        ++entry.creationGeneration
        ++entry.navigationGeneration
        entry.finished = false
        val view = entry.webView
        entry.webView = null
        val presentation = entry.presentation
        entry.presentation = null
        val display = entry.display
        entry.display = null
        val inputId = entry.inputId
        entry.inputId = null
        // Stop the producer and detach its target before asking the GL owner to retire it.
        cleanup { view?.stopLoading() }
        cleanup { (view?.parent as? ViewGroup)?.removeView(view) }
        cleanup { view?.destroy() }
        cleanup { presentation?.dismiss() }
        cleanup { display?.surface = null }
        cleanup { display?.release() }
        if (entry.inputRequested && inputId != null) renderer.releaseInput(inputId)
        entry.inputRequested = false
    }

    private fun cleanup(action: () -> Unit) {
        try { action() } catch (error: RuntimeException) { Log.w(TAG, "GPU browser cleanup failed") }
    }

    private fun updateReady() {
        active = overlays.isNotEmpty()
        isReady = overlays.all { entries[it.layer.id]?.let { entry -> entry.finished && !entry.failed } == true }
    }

    private fun publishLayers() {
        renderer.setLayers(overlays.mapNotNull { overlay ->
            val entry = entries[overlay.layer.id] ?: return@mapNotNull null
            val inputId = entry.inputId ?: return@mapNotNull null
            if (entry.webView == null) null else overlay.layer.copy(id = inputId)
        })
    }

    private fun allocation(layer: GpuBrowserLayer, budget: Int): Allocation {
        val pixels = layer.viewportWidth.toLong() * layer.viewportHeight
        val scale = minOf(1.0, sqrt(budget.coerceAtLeast(1).toDouble() / pixels))
        val dpi = floor(BASE_DENSITY_DPI * scale).toInt().coerceIn(1, BASE_DENSITY_DPI)
        // The same rational density on both axes preserves CSS-pixel geometry (within rounding).
        val width = (layer.viewportWidth.toLong() * dpi / BASE_DENSITY_DPI).toInt().coerceAtLeast(1)
        val height = (layer.viewportHeight.toLong() * dpi / BASE_DENSITY_DPI).toInt().coerceAtLeast(1)
        return Allocation(width, height, dpi)
    }

    private fun parseOverlays(config: Map<String, Any?>): List<Overlay> {
        val parsed = (config["overlays"] as? List<*>)?.mapIndexedNotNull { index, value ->
            (value as? Map<*, *>)?.toOverlay("overlay-$index", config)
        }.orEmpty()
        // An explicitly empty list clears the scene; do not revive an old primary URL.
        if (parsed.isNotEmpty() || config["overlays"] is List<*>) return parsed.distinctBy { it.layer.id }
        val url = (config["url"] as? String)?.takeIf { it.isNotBlank() } ?: primaryUrl
        return listOfNotNull((config + ("url" to url)).toOverlay("primary", config))
    }

    private fun Map<*, *>.toOverlay(fallbackId: String, parent: Map<String, Any?>): Overlay? {
        if (this["visible"] == false) return null
        val url = (this["url"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val width = number(this["width"], 1f)
        val height = number(this["height"], 1f)
        val declaredWidth = dimension(this["viewportWidth"], 160, 7680)
        val declaredHeight = dimension(this["viewportHeight"], 90, 4320)
        val explicit = this["viewportMode"] != "responsive" && declaredWidth != null && declaredHeight != null
        val referenceWidth = dimension(parent["browserReferenceWidth"], 160, 7680) ?: 1920
        val referenceHeight = dimension(parent["browserReferenceHeight"], 90, 4320) ?: 1080
        val id = (this["id"] as? String)?.takeIf { it.isNotBlank() } ?: fallbackId
        return Overlay(
            GpuBrowserLayer("browser:$id", number(this["x"], 0f), number(this["y"], 0f), width, height,
                if (explicit) declaredWidth!! else resolveSize(width, referenceWidth).coerceIn(160, 7680),
                if (explicit) declaredHeight!! else resolveSize(height, referenceHeight).coerceIn(90, 4320),
                number(this["zoom"], 1f).coerceIn(0.1f, 5f), number(this["opacity"], 1f).coerceIn(0f, 1f)),
            normalizeUrl(url), parseReloadRevision(this["reloadRevision"]),
            (this["transparent"] as? Boolean) ?: (parent["transparent"] as? Boolean) ?: transparent
        )
    }

    private fun dimension(value: Any?, min: Int, max: Int): Int? {
        val number = (value as? Number)?.toDouble() ?: return null
        return if (number.isFinite() && number > 0) number.coerceIn(min.toDouble(), max.toDouble()).roundToInt() else null
    }

    private fun number(value: Any?, fallback: Float): Float =
        (value as? Number)?.toFloat()?.takeIf { it.isFinite() } ?: fallback

    private fun resolveSize(value: Float, base: Int): Int =
        if (value in 0f..1f) (value * base).roundToInt() else value.roundToInt()

    private fun normalizeUrl(url: String): String = when {
        url.startsWith("http://", true) -> "http://${url.substringAfter("://")}"
        url.startsWith("https://", true) -> "https://${url.substringAfter("://")}"
        else -> "https://$url"
    }

    private fun parseReloadRevision(value: Any?): Long? {
        val revision = (value as? Number)?.toDouble() ?: return null
        if (!revision.isFinite() || revision < 0 || revision > 9_007_199_254_740_991.0 || revision % 1.0 != 0.0) return null
        return revision.toLong()
    }

    private data class Overlay(val layer: GpuBrowserLayer, val url: String, val reloadRevision: Long?, val transparent: Boolean)
    private data class Allocation(val width: Int, val height: Int, val densityDpi: Int) {
        val pixels: Long get() = width.toLong() * height
    }
    private class Entry(var overlay: Overlay, var size: Allocation) {
        var creationGeneration = 0L
        var navigationGeneration = 0L
        var inputRequested = false
        var inputId: String? = null
        var webView: WebView? = null
        var display: VirtualDisplay? = null
        var presentation: Presentation? = null
        var requestedUrl: String? = null
        var reloadRevision: Long? = null
        var finished = false
        var failed = false
    }

    companion object {
        private const val TAG = "GpuBrowserOverlay"
        private val nextInputSequence = AtomicLong()
        private const val BASE_DENSITY_DPI = 160
        private const val MAX_INPUT_PIXELS = 2_097_152
        private const val MAX_TOTAL_PIXELS = 4_194_304
    }
}
