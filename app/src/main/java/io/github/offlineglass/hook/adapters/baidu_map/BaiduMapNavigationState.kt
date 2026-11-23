package io.github.offlineglass.hook.adapters.baidu_map

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.WindowGpuBackdropCapture
import io.github.offlineglass.hook.adapters.localizeNavigationLabel
import java.util.WeakHashMap
import kotlin.math.max

/** Per-host state, GPU window-strip capture, tab icons/labels, and detached AI touch forwarding. */
internal class BaiduMapNavigationState(
    private var host: GlassHostLayout? = null,
) : AppNavigationState {
    private val tabIcons = arrayOfNulls<Bitmap>(5)
    private val tabCapturedWhileSelected = BooleanArray(5)
    private val tabLabels = arrayOfNulls<String>(5)
    private var aiOrbBitmap: Bitmap? = null
    private var lastRetryAt = 0L
    private var lastSource: ViewGroup? = null
    private var lastActiveUfo: ViewGroup? = null
    private var lastUiBarAnimating = false
    private var pendingSelection: Int? = null
    private var selectionGuardUntil = 0L
    private var aiTouchActive = false
    private var aiPressed = false
    private var aiPressProgress = 0f
    private val aiTouchLoc = IntArray(2)
    private val hostLoc = IntArray(2)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var gpuCapture: WindowGpuBackdropCapture? = null
    private var captureEpoch = 0L
    private var captureGeneration = 0L
    private var surfaceDirty = true

    private var blurredBitmap: Bitmap? = null
    private var blurredGeneration = -1L
    private var blurredWidth = 0
    private var blurredHeight = 0
    private var blurredEffect: RenderEffect? = null
    private var blurredConfig: GlassConfig? = null
    private var backdropDirty = true

    private var outerRecordedBackdrop: Bitmap? = null
    private var outerRecordedGeneration = -1L
    private var outerRecordedWidth = 0
    private var outerRecordedHeight = 0

    private val savedNearbyMargins = WeakHashMap<View, Int>()
    private val savedNearbyPaddings = WeakHashMap<View, Int>()

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    override val readyForDrawing: Boolean
        get() = true

    override val persistentRestingIndicatorFill: Boolean get() = true
    override val retainHiddenSourceFillWhileExpanded: Boolean get() = true
    override val allowsSoftwareBackdropCapture: Boolean get() = false

    private fun ensureCapture(glass: GlassHostLayout): WindowGpuBackdropCapture =
        gpuCapture ?: WindowGpuBackdropCapture(
            host = glass,
            tag = "BaiduMap",
            downscale = 2f,
            activeIntervalMs = 16L,
            idleIntervalMs = 120L,
        ).also { gpuCapture = it }

    override fun requiresSampledBackdrop(): Boolean {
        val cfg = host?.config ?: return true
        return cfg.enabled && cfg.backdropCapture && !cfg.solidBarEnabled &&
            (cfg.liquidGlassEnabled || cfg.blurRadius > 0f)
    }

    override fun allowsOpticalSurfacePipeline(): Boolean =
        requiresSampledBackdrop() && gpuCapture?.isPermanentlyUnsupported != true

    override fun preparesOpticalSurfaceForWindowCapture(): Boolean = true
    override fun opticalCaptureEpoch(): Long = captureEpoch
    override fun opticalSurfaceCopyHandler(): Handler = mainHandler
    override fun pauseOpticalSurfaceProbe(): Boolean = true

    override fun onHostFrame(host: View) {
        val glass = host as? GlassHostLayout ?: return
        this.host = glass
        val source = glass.adapterNavigationSource
        val activeState = BaiduMapAdapter.resolveActiveUfo(glass.rootView as? ViewGroup, source)
        if (source != null) {
            BaiduMapAdapter.clearUfoChrome(source, expandHome = false)
            if (!activeState.isOverlay) {
                BaiduMapAdapter.expandTabPagesFullBleed(
                    source,
                    isNonHomeSelected = (glass.adapterSelectedIndex != 0),
                    savedMargins = savedNearbyMargins,
                    savedPaddings = savedNearbyPaddings,
                )
            }
        }
        val activeUfo = activeState.ufo
        if (activeUfo != null && activeUfo !== source) {
            BaiduMapAdapter.clearUfoChrome(activeUfo, expandHome = false)
        }
        prepare(activeUfo ?: source) { delay, action -> glass.postDelayed(action, delay) }
        updateBackdropRendererMode(glass)
    }

    override fun skipLiveBackdrop(selectedIndex: Int): Boolean = true
    override fun includeNavigationInIndicatorScene(surfaceActive: Boolean): Boolean = true

    override fun updateBackdropRendererMode(host: GlassHostLayout): Boolean {
        this.host = host
        val capture = ensureCapture(host)
        if (!requiresSampledBackdrop() || capture.isPermanentlyUnsupported) {
            capture.cancel()
            host.disableOpticalSurfaceNativeBlurForAdapter()
            host.disableOpticalSurfaceRendererForAdapter()
            return true
        }
        if (host.liveBackdropActiveForAdapter) host.clearLiveBackdropForAdapter()
        host.disableOpticalSurfaceNativeBlurForAdapter()
        host.enableOpticalSurfaceRendererForAdapter()
        if (host.isShown && host.hasWindowFocus()) {
            capture.request(captureEpoch)
        } else {
            capture.cancel()
        }
        return true
    }

    override fun onOpticalBackdropCaptured(barSource: Rect?) {
        gpuCapture?.commitPublishedGeometry()
        captureGeneration++
        backdropDirty = true
        surfaceDirty = false
    }

    override fun onOpticalBackdropChanged() {
        backdropDirty = true
        surfaceDirty = true
    }

    override fun onOpticalSurfaceFrameInvalidated() {
        backdropDirty = true
        surfaceDirty = true
        host?.postInvalidateOnAnimation()
        mainHandler.post {
            gpuCapture?.notifyMotion(1_500L, captureEpoch)
        }
    }

    override fun onOpticalRendererConfigurationChanged() {
        blurredBitmap = null
        blurredGeneration = -1L
        blurredConfig = null
        outerRecordedBackdrop = null
        outerRecordedGeneration = -1L
        outerRecordedWidth = 0
        outerRecordedHeight = 0
        backdropDirty = true
        surfaceDirty = true
        gpuCapture?.notifyMotion(400L, captureEpoch)
    }

    override fun drawSampledBackdrop(
        canvas: Canvas,
        bitmap: Bitmap,
        destination: RectF,
        paint: Paint,
    ): Boolean = gpuCapture?.drawBackdrop(canvas, bitmap, destination, paint) == true

    override fun reuseOuterBackdropForSurface(surfaceActive: Boolean): Boolean = surfaceActive

    override fun outerBackdropNeedsRecord(
        backdrop: Bitmap?,
        width: Int,
        height: Int,
        hasDisplayList: Boolean,
    ): Boolean = !hasDisplayList || outerRecordedBackdrop !== backdrop ||
        outerRecordedGeneration != captureGeneration ||
        outerRecordedWidth != width || outerRecordedHeight != height

    override fun onOuterBackdropRecorded(backdrop: Bitmap?, width: Int, height: Int) {
        outerRecordedBackdrop = backdrop
        outerRecordedGeneration = captureGeneration
        outerRecordedWidth = width
        outerRecordedHeight = height
    }

    override fun opticalBlurNodeNeedsRecord(
        prepared: Boolean,
        backdrop: Bitmap?,
        width: Int,
        height: Int,
        effect: RenderEffect?,
    ): Boolean = !prepared || backdropDirty || blurredBitmap !== backdrop ||
        blurredGeneration != captureGeneration ||
        blurredWidth != width || blurredHeight != height ||
        blurredEffect !== effect || blurredConfig != host?.config

    override fun onOpticalBlurNodeRecorded(
        backdrop: Bitmap?,
        width: Int,
        height: Int,
        effect: RenderEffect?,
    ) {
        backdropDirty = false
        blurredBitmap = backdrop
        blurredGeneration = captureGeneration
        blurredWidth = width
        blurredHeight = height
        blurredEffect = effect
        blurredConfig = host?.config
    }

    override fun shouldQueueOpticalRenderFromUi(animating: Boolean): Boolean {
        val queue = animating || lastUiBarAnimating || surfaceDirty
        lastUiBarAnimating = animating
        surfaceDirty = false
        return queue
    }

    override fun onContentScroll() {
        gpuCapture?.notifyMotion(1_200L, captureEpoch)
    }

    fun onWindowTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> gpuCapture?.notifyMotion(1_200L, captureEpoch)
        }
    }

    override fun onNavigationTabSwitched(index: Int, slotCount: Int, now: Long) {
        captureEpoch++
        backdropDirty = true
        surfaceDirty = true
        outerRecordedGeneration = -1L
        gpuCapture?.notifyMotion(1_200L, captureEpoch)
        host?.postInvalidateOnAnimation()
    }

    override fun onHostFocusLost() {
        gpuCapture?.cancel()
    }

    override fun onHostWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus) {
            gpuCapture?.cancel()
        } else {
            surfaceDirty = true
            backdropDirty = true
            gpuCapture?.notifyMotion(800L, captureEpoch)
            host?.postInvalidateOnAnimation()
        }
    }

    override fun actionButtonPressProgress(): Float = aiPressProgress

    private fun currentActiveUfo(source: ViewGroup?): ViewGroup? {
        val rootGroup = host?.rootView as? ViewGroup
        val state = BaiduMapAdapter.resolveActiveUfo(rootGroup, source)
        val active = state.ufo ?: source
        if (active !== lastActiveUfo) {
            lastActiveUfo = active
            if (active != null) {
                captureEpoch++
                backdropDirty = true
                surfaceDirty = true
                outerRecordedGeneration = -1L
                gpuCapture?.notifyMotion(1_200L, captureEpoch)
                host?.postInvalidateOnAnimation()
            }
        }
        return active
    }

    override fun suppressHostNativeChrome(source: ViewGroup?, root: View, selected: Int) {
        val activeState = BaiduMapAdapter.resolveActiveUfo(root as? ViewGroup, source)
        if (source != null) {
            BaiduMapAdapter.clearUfoChrome(source, expandHome = false)
            if (!activeState.isOverlay) {
                BaiduMapAdapter.expandTabPagesFullBleed(
                    source,
                    isNonHomeSelected = (selected != 0),
                    savedMargins = savedNearbyMargins,
                    savedPaddings = savedNearbyPaddings,
                )
            }
        }
        val activeUfo = activeState.ufo
        if (activeUfo != null && activeUfo !== source) {
            BaiduMapAdapter.clearUfoChrome(activeUfo, expandHome = false)
        }
    }

    override fun pageAllowsNavigation(root: View?): Boolean? {
        val rootGroup = root as? ViewGroup ?: return null
        val source = host?.adapterNavigationSource
        val state = BaiduMapAdapter.resolveActiveUfo(rootGroup, source)
        if (state.allowed) {
            currentActiveUfo(source)
        }
        return state.allowed
    }

    override fun prepare(source: ViewGroup?, schedule: (Long, () -> Unit) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper() || source == null) return
        if (lastSource !== source) {
            lastSource = source
            lastRetryAt = 0L
        }
        val now = SystemClock.uptimeMillis()
        val selectedNow = resolveSelectedIndex(source, 5)
        val missingNav = intArrayOf(0, 1, 3, 4).any {
            tabIcons[it] == null || (tabCapturedWhileSelected[it] && selectedNow != null && selectedNow != it)
        }
        val missingAi = aiOrbBitmap == null
        if (!missingNav && !missingAi) return
        if (now - lastRetryAt < 180L) return
        lastRetryAt = now

        val btns = BaiduMapAdapter.findNewBtns(source)
        var updated = false
        if (btns != null && btns.childCount == 5) {
            for (index in intArrayOf(0, 1, 3, 4)) {
                val cell = btns.getChildAt(index) as? ViewGroup ?: continue
                findLabelView(cell)?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { liveLabel ->
                    if (tabLabels[index] == null || (index == 0 && liveLabel == "首页" && tabLabels[index] == "上拉发现")) {
                        tabLabels[index] = liveLabel
                        updated = true
                    }
                }
                val canUpgradeFromSelected = tabCapturedWhileSelected[index] && selectedNow != null && selectedNow != index
                if (tabIcons[index] == null || canUpgradeFromSelected) {
                    captureNavIcon(cell)?.let { bmp ->
                        tabIcons[index]?.takeUnless(Bitmap::isRecycled)?.recycle()
                        tabIcons[index] = bmp
                        tabCapturedWhileSelected[index] = (selectedNow == index)
                        updated = true
                    }
                }
            }
        }
        if (aiOrbBitmap == null) {
            captureAiOrb(source)?.let {
                aiOrbBitmap = it
                updated = true
            }
        }
        if (updated) {
            host?.invalidate()
        } else if (missingNav || missingAi) {
            schedule(260L) {
                prepare(source, schedule)
                host?.postInvalidateOnAnimation()
            }
        }
    }

    override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int): Int? {
        val ufo = currentActiveUfo(source) ?: return null
        val btns = BaiduMapAdapter.findNewBtns(ufo) ?: return null
        if (btns.childCount < 5) return null
        val now = SystemClock.uptimeMillis()
        var detected: Int? = null

        // Check active SimpleDraweeView (Baidu Map shows animated drawee on selected tab).
        for (index in intArrayOf(0, 1, 3, 4)) {
            val cell = btns.getChildAt(index) as? ViewGroup ?: continue
            val drawee = findDraweeView(cell)
            val str = drawee?.toString().orEmpty()
            if (drawee != null && drawee.visibility == View.VISIBLE && drawee.isShown &&
                (drawee.drawable?.isVisible == true || str.contains("drawableVisible=true"))
            ) {
                detected = index
                break
            }
        }
        if (detected == null) {
            for (index in intArrayOf(0, 1, 3, 4)) {
                val cell = btns.getChildAt(index) as? ViewGroup ?: continue
                val tv = findLabelView(cell) ?: continue
                val c = tv.currentTextColor
                if (Color.blue(c) - Color.red(c) > 40 && Color.blue(c) > 150) {
                    detected = index
                    break
                }
            }
        }
        if (detected == null) {
            val colors = listOf(0, 1, 3, 4).mapNotNull { idx ->
                val cell = btns.getChildAt(idx) as? ViewGroup ?: return@mapNotNull null
                val tv = findLabelView(cell) ?: return@mapNotNull null
                idx to tv.currentTextColor
            }
            val majorityColor = colors.groupingBy { it.second }.eachCount().maxByOrNull { it.value }?.key
            detected = colors.firstOrNull { it.second != majorityColor }?.first
        }

        val pending = pendingSelection
        if (pending != null && now < selectionGuardUntil) {
            if (detected == pending) {
                pendingSelection = null
                return detected
            }
            return pending
        }
        pendingSelection = null
        return detected
    }

    override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int): Boolean {
        val ufo = currentActiveUfo(source) ?: return false
        if (index == BaiduMapAdapter.AI_INDEX) {
            val aiTarget = findAiTouchTarget(ufo) ?: return false
            gpuCapture?.notifyMotion(1_200L, captureEpoch)
            return aiTarget.performClick()
        }
        val btns = BaiduMapAdapter.findNewBtns(ufo) ?: return false
        val cell = btns.getChildAt(index) ?: return false
        pendingSelection = index
        selectionGuardUntil = SystemClock.uptimeMillis() + 600L
        var ok = cell.performClick()
        if (!ok) {
            val t = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, cell.width / 2f, cell.height / 2f, 0)
            val up = MotionEvent.obtain(t, t + 16L, MotionEvent.ACTION_UP, cell.width / 2f, cell.height / 2f, 0)
            ok = cell.dispatchTouchEvent(down)
            cell.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
        }
        gpuCapture?.notifyMotion(1_200L, captureEpoch)
        host.invalidate()
        return ok
    }

    override fun interceptNavigationTouch(host: View, event: MotionEvent): Boolean? {
        val glass = (host as? GlassHostLayout) ?: this.host ?: return null
        val ufo = currentActiveUfo(glass.adapterNavigationSource) ?: return null
        val barLayout = BaiduMapAdapter.barLayout(
            glass.config,
            glass.width,
            glass.height,
            glass.resources.displayMetrics.density,
            glass.adapterSlotCount,
        )
        val action = event.actionMasked
        val hitsAi = if (barLayout != null) {
            event.x >= barLayout.actionLeft && event.x <= glass.width && event.y in 0f..glass.height.toFloat()
        } else {
            val slotW = glass.width / 5f
            event.x >= slotW * 2f && event.x <= slotW * 3f && event.y in 0f..glass.height.toFloat()
        }
        if (action == MotionEvent.ACTION_DOWN) {
            if (!hitsAi) return null
            aiTouchActive = true
            setAiPressed(glass, true)
            forwardMotionToAi(glass, ufo, event)
            return true
        }
        if (!aiTouchActive) return null
        forwardMotionToAi(glass, ufo, event)
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            aiTouchActive = false
            setAiPressed(glass, false)
        }
        return true
    }

    private fun setAiPressed(glass: GlassHostLayout, pressed: Boolean) {
        if (aiPressed == pressed) return
        aiPressed = pressed
        aiPressProgress = if (pressed) 1f else 0f
        surfaceDirty = true
        glass.invalidate()
    }

    private fun forwardMotionToAi(glass: GlassHostLayout, ufo: ViewGroup, event: MotionEvent) {
        val target = findAiTouchTarget(ufo) ?: return
        glass.getLocationOnScreen(hostLoc)
        target.getLocationOnScreen(aiTouchLoc)
        val localX = (target.width * 0.5f).coerceIn(1f, (target.width - 1).coerceAtLeast(1).toFloat())
        val localY = (target.height * 0.5f).coerceIn(1f, (target.height - 1).coerceAtLeast(1).toFloat())
        val copy = MotionEvent.obtain(
            event.downTime,
            event.eventTime,
            event.action,
            localX,
            localY,
            event.metaState,
        )
        try {
            target.dispatchTouchEvent(copy)
        } finally {
            copy.recycle()
        }
    }

    override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) {
        drawTabs(
            canvas,
            frame,
            active = frame.hideSelectedForOptics,
            includeDetached = false,
            highlightSelected = frame.hideSelectedForOptics,
        )
    }

    override fun drawForeground(
        canvas: Canvas,
        frame: AdapterNavigationFrame,
        selectionPath: Path,
        drawSource: () -> Unit,
    ): Boolean {
        val detached = frame.barLayout
        if (selectionPath.isEmpty) {
            drawTabs(
                canvas,
                frame,
                active = false,
                includeDetached = detached != null,
                highlightSelected = true,
            )
            return true
        }
        val outside = canvas.save()
        canvas.clipOutPath(selectionPath)
        drawTabs(
            canvas,
            frame,
            active = false,
            includeDetached = detached != null,
            highlightSelected = false,
        )
        canvas.restoreToCount(outside)

        val hasBackdrop = canvas.isHardwareAccelerated &&
            host?.hasUsableBackdrop() == true &&
            host?.config?.backdropCapture == true &&
            host?.config?.solidBarEnabled != true
        val opticalMix = if (hasBackdrop) frame.navigationOpticalMix.coerceIn(0f, 1f) else 0f
        val plain = 1f - opticalMix
        if (plain > 0f) {
            val inside = canvas.save()
            canvas.clipPath(selectionPath)
            val layer = if (plain < 0.999f) {
                canvas.saveLayerAlpha(
                    0f, 0f, frame.width.toFloat(), frame.height.toFloat(),
                    (plain * 255f).toInt().coerceIn(0, 255),
                )
            } else -1
            try {
                drawTabs(
                    canvas,
                    frame,
                    active = true,
                    includeDetached = false,
                    highlightSelected = true,
                )
            } finally {
                if (layer >= 0) canvas.restoreToCount(layer)
                canvas.restoreToCount(inside)
            }
        }
        return true
    }

    private fun drawTabs(
        canvas: Canvas,
        frame: AdapterNavigationFrame,
        active: Boolean,
        includeDetached: Boolean,
        highlightSelected: Boolean,
    ) {
        if (frame.width <= 0 || frame.height <= 0 || frame.slotCount <= 0) return
        val heightScale = (frame.height / (64f * frame.density)).coerceIn(0.875f, 1f)
        val itemScale = frame.iconScale * (if (frame.hideSelectedForOptics) frame.extraScale else frame.extraScale * heightScale)
        val unselectedColor = if (frame.darkGlass) 0xFFE6E6E6.toInt() else 0xFF1F2329.toInt()
        val horizontalPadding = 4f * frame.density
        val defaultNames = arrayOf("首页", "周边", "对话", "打车", "我的")

        labelPaint.textSize = 10.5f * frame.scaledDensity
        val fm = labelPaint.fontMetrics
        val labelH = fm.descent - fm.ascent
        val iconViewport = 25f * frame.density
        val gap = 2f * frame.density
        val groupTop = (frame.height - (iconViewport + gap + labelH)) / 2f
        val iconCenterY = groupTop + iconViewport / 2f
        val labelBaseline = groupTop + iconViewport + gap - fm.ascent

        frame.enabledIndices.forEachIndexed { visualIndex, index ->
            val centerX = horizontalPadding + (visualIndex + 0.5f) * frame.slotWidth
            if (index == BaiduMapAdapter.AI_INDEX) {
                val orb = aiOrbBitmap?.takeUnless(Bitmap::isRecycled) ?: return@forEachIndexed
                val w = orb.width.toFloat().coerceAtLeast(1f)
                val h = orb.height.toFloat().coerceAtLeast(1f)
                val scale = (iconViewport * 1.15f) / max(w, h)
                val dw = w * scale
                val dh = h * scale
                canvas.save()
                canvas.scale(itemScale, itemScale, centerX, iconCenterY)
                orbPaint.alpha = 255
                canvas.drawBitmap(
                    orb, null,
                    RectF(centerX - dw / 2f, iconCenterY - dh / 2f, centerX + dw / 2f, iconCenterY + dh / 2f),
                    orbPaint,
                )
                val aiColor = if (active) frame.accentColor else unselectedColor
                labelPaint.color = aiColor
                labelPaint.alpha = if (active) 255 else 235
                canvas.drawText(frame.localizeNavigationLabel("对话"), centerX, labelBaseline, labelPaint)
                canvas.restore()
                return@forEachIndexed
            }

            val bitmap = tabIcons.getOrNull(index)?.takeUnless(Bitmap::isRecycled)
            val label = tabLabels.getOrNull(index) ?: defaultNames.getOrNull(index) ?: ""
            val selected = active || index == frame.selectedIndex
            val color = if (active || (highlightSelected && selected)) frame.accentColor else unselectedColor

            canvas.save()
            canvas.scale(itemScale, itemScale, centerX, frame.height / 2f)
            if (bitmap != null) {
                val w = bitmap.width.toFloat().coerceAtLeast(1f)
                val h = bitmap.height.toFloat().coerceAtLeast(1f)
                val scale = (iconViewport / max(w, h)).coerceAtMost(1.35f)
                val dw = w * scale
                val dh = h * scale
                iconPaint.colorFilter = BlendModeColorFilter(color, BlendMode.SRC_IN)
                iconPaint.alpha = if (selected) 255 else 235
                canvas.drawBitmap(
                    bitmap, null,
                    RectF(centerX - dw / 2f, iconCenterY - dh / 2f, centerX + dw / 2f, iconCenterY + dh / 2f),
                    iconPaint,
                )
            }
            if (label.isNotEmpty()) {
                labelPaint.color = color
                labelPaint.alpha = if (selected) 255 else 235
                canvas.drawText(frame.localizeNavigationLabel(label), centerX, labelBaseline, labelPaint)
            }
            canvas.restore()
        }

        if (includeDetached) {
            frame.barLayout?.let { layout ->
                val orb = aiOrbBitmap?.takeUnless(Bitmap::isRecycled) ?: return@let
                val cx = layout.actionLeft + layout.actionSize / 2f
                val cy = frame.height / 2f
                val targetSize = layout.actionSize * 0.72f * (1f + 0.08f * aiPressProgress)
                val w = orb.width.toFloat().coerceAtLeast(1f)
                val h = orb.height.toFloat().coerceAtLeast(1f)
                val scale = targetSize / max(w, h)
                val dw = w * scale
                val dh = h * scale
                orbPaint.alpha = 255
                canvas.drawBitmap(
                    orb, null,
                    RectF(cx - dw / 2f, cy - dh / 2f, cx + dw / 2f, cy + dh / 2f),
                    orbPaint,
                )
            }
        }
        iconPaint.colorFilter = null
        iconPaint.alpha = 255
        labelPaint.alpha = 255
    }

    private fun findLabelView(cell: ViewGroup): TextView? {
        for (i in 0 until cell.childCount) {
            val child = cell.getChildAt(i)
            if (child is TextView && child.text?.isNotBlank() == true) return child
        }
        return null
    }

    private fun findStaticIconView(cell: ViewGroup): ImageView? {
        for (i in 0 until cell.childCount) {
            val child = cell.getChildAt(i)
            if (child is ImageView && !child.javaClass.name.contains("Drawee")) return child
        }
        return null
    }

    private fun findDraweeView(cell: ViewGroup): ImageView? {
        for (i in 0 until cell.childCount) {
            val child = cell.getChildAt(i)
            if (child is ImageView && child.javaClass.name.contains("Drawee")) return child
        }
        return null
    }

    private fun findAiContainer(ufoRoot: ViewGroup): ViewGroup? {
        val directId = ufoRoot.resources.getIdentifier("home_ai_container", "id", ufoRoot.context.packageName)
        if (directId != 0) (ufoRoot.findViewById<View>(directId) as? ViewGroup)?.let { return it }
        for (i in 0 until ufoRoot.childCount) {
            val child = ufoRoot.getChildAt(i) as? ViewGroup ?: continue
            if (AdapterNavigationSearch.resourceEntryName(child) == "home_ai_container") return child
        }
        return null
    }

    private fun findAiTouchTarget(ufoRoot: ViewGroup): View? {
        val container = findAiContainer(ufoRoot) ?: return null
        val touchId = ufoRoot.resources.getIdentifier("new_home_ai_touch_view", "id", ufoRoot.context.packageName)
        if (touchId != 0) container.findViewById<View>(touchId)?.let { return it }
        return container
    }

    private fun captureNavIcon(cell: ViewGroup): Bitmap? {
        val candidates = listOfNotNull(findStaticIconView(cell), findDraweeView(cell))
        for (iconView in candidates) {
            captureImageView(iconView)?.let { return it }
        }
        return null
    }

    private fun captureImageView(iconView: ImageView): Bitmap? {
        val drawable = iconView.drawable ?: iconView.background
        val w = iconView.width.takeIf { it > 0 } ?: drawable?.intrinsicWidth?.takeIf { it > 0 } ?: return null
        val h = iconView.height.takeIf { it > 0 } ?: drawable?.intrinsicHeight?.takeIf { it > 0 } ?: return null
        return runCatching {
            val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(raw)
            val savedAlpha = iconView.alpha
            val savedVis = iconView.visibility
            try {
                iconView.alpha = 1f
                iconView.visibility = View.VISIBLE
                iconView.draw(canvas)
            } finally {
                iconView.alpha = savedAlpha
                iconView.visibility = savedVis
            }
            trimTransparent(raw) ?: drawable?.let { d ->
                val raw2 = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val savedBounds = Rect(d.bounds)
                val savedDAlpha = d.alpha
                try {
                    d.alpha = 255
                    d.setVisible(true, false)
                    d.setBounds(0, 0, w, h)
                    d.draw(Canvas(raw2))
                } finally {
                    d.bounds = savedBounds
                    d.alpha = savedDAlpha
                }
                trimTransparent(raw2)
            }
        }.getOrNull()
    }

    private fun captureAiOrb(ufoRoot: ViewGroup): Bitmap? {
        val container = findAiContainer(ufoRoot) ?: return null
        val drawee = findDraweeView(container) ?: return null
        return captureImageView(drawee)
    }

    private fun trimTransparent(bitmap: Bitmap): Bitmap? {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            val rowOffset = y * w
            for (x in 0 until w) {
                if ((pixels[rowOffset + x] ushr 24) > 12) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) {
            bitmap.recycle()
            return null
        }
        val tw = maxX - minX + 1
        val th = maxY - minY + 1
        if (tw == w && th == h) return bitmap
        val trimmed = Bitmap.createBitmap(bitmap, minX, minY, tw, th)
        bitmap.recycle()
        return trimmed
    }

    private fun restoreNearbyInsets() {
        savedNearbyMargins.forEach { (view, margin) ->
            if (view.isAttachedToWindow) {
                val lp = view.layoutParams as? ViewGroup.MarginLayoutParams
                if (lp != null && lp.bottomMargin != margin) {
                    lp.bottomMargin = margin
                    view.layoutParams = lp
                }
            }
        }
        savedNearbyMargins.clear()
        savedNearbyPaddings.forEach { (view, pad) ->
            if (view.isAttachedToWindow && view.paddingBottom != pad) {
                view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, pad)
            }
        }
        savedNearbyPaddings.clear()
    }

    override fun onHostDetached() {
        gpuCapture?.cancel()
        restoreNearbyInsets()
        lastSource = null
        lastActiveUfo = null
    }

    override fun dispose() {
        gpuCapture?.dispose()
        gpuCapture = null
        restoreNearbyInsets()
        blurredBitmap = null
        blurredGeneration = -1L
        blurredConfig = null
        outerRecordedBackdrop = null
        outerRecordedGeneration = -1L
        tabIcons.forEach { it?.takeUnless(Bitmap::isRecycled)?.recycle() }
        tabIcons.fill(null)
        aiOrbBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
        aiOrbBitmap = null
        lastSource = null
        lastActiveUfo = null
    }
}
