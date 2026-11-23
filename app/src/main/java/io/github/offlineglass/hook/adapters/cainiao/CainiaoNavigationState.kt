package io.github.offlineglass.hook.adapters.cainiao

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.GlassHostLayout
import java.util.WeakHashMap

/** Per-host ownership of page padding and native artwork capture. */
internal class CainiaoNavigationState(private val glassHost: GlassHostLayout) : AppNavigationState {
    private val removedPadding = WeakHashMap<View, Int>()
    private var heights: Set<Int>? = null
    private var pageContainer: java.lang.ref.WeakReference<ViewGroup>? = null
    private var lastSurfaceMode: Boolean? = null
    @Volatile private var motionUntil = 0L
    private var lastUiAnimating = false
    private val frameCopyScheduler = CainiaoFrameCopyScheduler()
    private var surfaceRedrawPending = true
    private var outerBackdropDirty = true
    private var lastBackdropTab = -1
    private var settleRetryCount = 0

    override fun outerBackdropNeedsRecord(
        backdrop: Bitmap?, width: Int, height: Int, hasDisplayList: Boolean,
    ): Boolean? = if (outerBackdropDirty || !hasDisplayList) true else null

    override fun onOuterBackdropRecorded(backdrop: Bitmap?, width: Int, height: Int) {
        // The host only acknowledges reusable recordings. On the native path
        // this means the live scene is ready, not the old H5 bitmap fallback.
        outerBackdropDirty = false
    }

    override fun scheduleOpticalSurfaceCopy(root: View, copy: () -> Unit): Boolean =
        frameCopyScheduler.schedule(root, copy)

    override fun onOpticalRendererConfigurationChanged() { surfaceRedrawPending = true }
    override fun onOpticalSurfaceFrameInvalidated() { surfaceRedrawPending = true }

    override fun shouldQueueOpticalRenderFromUi(animating: Boolean): Boolean {
        // PixelCopy already renders changed H5 frames on the compositor worker.
        // UI dispatchDraw must never wait for the same Surface lock or submit
        // an identical buffer. Queue only bar animation, including its last frame.
        val queue = animating || lastUiAnimating || surfaceRedrawPending
        lastUiAnimating = animating
        surfaceRedrawPending = false
        return queue
    }

    // Chromium pages must not use a software View.draw screenshot on the UI thread.
    override val allowsSoftwareBackdropCapture: Boolean
        get() = !CainiaoAdapter.isOpticalSurfaceTab(glassHost.adapterSelectedIndex)

    override fun skipLiveBackdrop(selectedIndex: Int): Boolean =
        CainiaoAdapter.isOpticalSurfaceTab(selectedIndex)

    override fun updateBackdropRendererMode(host: GlassHostLayout): Boolean {
        if (lastBackdropTab != host.adapterSelectedIndex) {
            lastBackdropTab = host.adapterSelectedIndex
            outerBackdropDirty = true
        }
        val useSurface = CainiaoAdapter.isOpticalSurfaceTab(host.adapterSelectedIndex)
        if (lastSurfaceMode != useSurface) {
            // Clearing the live scene does not discard the outer glass display
            // list. Rebuild that wrapper once the new live source is available.
            outerBackdropDirty = true
            host.clearLiveBackdropForAdapter()
            frameCopyScheduler.cancel()
            surfaceRedrawPending = true
            lastSurfaceMode = useSurface
        }
        if (useSurface) {
            host.disableOpticalSurfaceNativeBlurForAdapter()
            host.enableOpticalSurfaceRendererForAdapter()
        } else host.disableOpticalSurfaceRendererForAdapter()
        return true
    }

    override fun onContentScroll() { motionUntil = android.os.SystemClock.uptimeMillis() + 600L }
    override fun contentMotionHoldUntil(): Long = motionUntil
    fun onWindowTouch(event: android.view.MotionEvent) {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN ||
            event.actionMasked == android.view.MotionEvent.ACTION_MOVE ||
            event.actionMasked == android.view.MotionEvent.ACTION_UP) {
            motionUntil = android.os.SystemClock.uptimeMillis() + 1_000L
        }
    }

    override fun resolveBackdropScene(host: View): View? = contentContainer(host)

    private fun contentContainer(host: View): ViewGroup? =
        pageContainer?.get()?.takeIf { it.isAttachedToWindow && it.rootView === host.rootView }
            ?: (host.rootView.findNamedView("above_tab_container") as? ViewGroup)?.also {
                pageContainer = java.lang.ref.WeakReference(it)
            }

    override fun onHostFrame(host: View) {
        updateBackdropRendererMode(glassHost)
        val container = contentContainer(host) ?: return
        val barHeights = heights ?: listOf("navigation_bar_height", "navigation_bar_height_v81012")
            .mapNotNull { name ->
                val id = host.resources.getIdentifier(name, "dimen", "com.cainiao.wireless")
                if (id == 0) null else host.resources.getDimension(id).toInt()
            }.toSet().also { heights = it }
        for (i in 0 until container.childCount) {
            val page = container.getChildAt(i)
            val bottom = page.paddingBottom
            if (bottom > 0 && bottom in barHeights) {
                removedPadding[page] = bottom
                page.setPadding(page.paddingLeft, page.paddingTop, page.paddingRight, 0)
            }
        }
    }

    override fun onNavigationSelectionRequested(index: Int, slotCount: Int, now: Long): Boolean {
        settleRetryCount = 0
        return false
    }

    override fun inspectNavigationCaptureLabels(
        source: ViewGroup,
        hasSnapshot: Boolean,
    ): io.github.offlineglass.hook.adapters.NavigationCaptureLabels? {
        val tabs = CainiaoArtworkCapture.findVisibleTabs(source)
        if (tabs.isEmpty() || CainiaoArtworkCapture.needsCaptureSettling(tabs)) {
            if (settleRetryCount < 15) {
                settleRetryCount++
                return io.github.offlineglass.hook.adapters.NavigationCaptureLabels(emptyList(), retryDelayMs = 48L)
            }
        } else {
            settleRetryCount = 0
        }
        return null
    }

    override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int): Int? {
        val root = source ?: return null
        val tabs = CainiaoArtworkCapture.findVisibleTabs(root)
        if (tabs.isEmpty()) return null
        var bestIndex = -1
        var bestScore = 0
        tabs.forEachIndexed { index, tab ->
            var score = 0
            if (tab.slot.isSelected) score += 2
            if (tab.slot.isActivated) score += 1
            if (tab.icon.isSelected) score += 3
            if (tab.icon.isActivated) score += 1
            if (tab.label?.isSelected == true) score += 2
            if (tab.label?.isActivated == true) score += 1
            if (score == 0) {
                val stack = ArrayDeque<View>()
                stack += tab.slot
                while (stack.isNotEmpty()) {
                    val view = stack.removeLast()
                    if (view.visibility != View.VISIBLE) continue
                    if (view.isSelected) score += 2
                    if (view.isActivated) score += 1
                    if (view is ViewGroup) {
                        for (i in 0 until view.childCount) stack += view.getChildAt(i)
                    }
                }
            }
            if (score > bestScore) {
                bestScore = score
                bestIndex = index
            }
        }
        return bestIndex.takeIf { it >= 0 }
    }

    override fun dispatchNativeNavigationTap(
        host: GlassHostLayout,
        source: ViewGroup?,
        index: Int,
        slotCount: Int,
    ): Boolean {
        val root = source ?: return false
        val tabs = CainiaoArtworkCapture.findVisibleTabs(root)
        val tab = tabs.getOrNull(index) ?: return false
        if (tab.slot.isClickable && tab.slot.performClick()) return true
        val stack = ArrayDeque<View>()
        stack += tab.slot
        while (stack.isNotEmpty()) {
            val view = stack.removeLast()
            if (view.visibility != View.VISIBLE) continue
            if (view !== tab.slot && view.isClickable && view.performClick()) return true
            if (view is ViewGroup) {
                for (i in view.childCount - 1 downTo 0) stack += view.getChildAt(i)
            }
        }
        return false
    }

    override fun captureNavigation(source: ViewGroup, target: Bitmap): Boolean =
        CainiaoArtworkCapture.capture(source, target, glassHost.config.managerEnglishNavigationLabels)

    override fun dispose() {
        frameCopyScheduler.cancel()
        removedPadding.forEach { (page, original) ->
            if (page.paddingBottom == 0) page.setPadding(page.paddingLeft, page.paddingTop, page.paddingRight, original)
        }
        removedPadding.clear()
        heights = null
        pageContainer = null
        lastSurfaceMode = null
        motionUntil = 0L
        lastUiAnimating = false
        surfaceRedrawPending = true
        outerBackdropDirty = true
        lastBackdropTab = -1
        settleRetryCount = 0
    }
}
