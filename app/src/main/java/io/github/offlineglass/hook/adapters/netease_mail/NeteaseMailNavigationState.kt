package io.github.offlineglass.hook.adapters.netease_mail

import android.app.Activity
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
import android.graphics.drawable.Animatable
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.localizeNavigationLabel
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.max

/** Per-host state: live Lottie icon rendering, native Mail Agent purple accent, unified PixelCopy control, FAB lift, drawer hide. */
internal class NeteaseMailNavigationState(
    private val host: GlassHostLayout? = null,
) : AppNavigationState {
    private val tabIconViews = arrayOfNulls<WeakReference<ImageView>>(5)
    private val tabTextViews = arrayOfNulls<WeakReference<TextView>>(5)
    private val tabIcons = arrayOfNulls<Bitmap>(5)
    private val tabLabels = arrayOfNulls<String>(5)
    private val badgeTexts = arrayOfNulls<String>(5)
    private val badgeDots = BooleanArray(5)

    private var aiAccentColor: Int = DEFAULT_AI_PURPLE
    private var activeHost: GlassHostLayout? = host
    private var lastSource: ViewGroup? = null
    private var lastRetryAt = 0L
    private var pendingSelection: Int? = null
    private var selectionGuardUntil = 0L
    private var lastNativeSelected: Int? = null
    private var lottieAnimatingUntil = 0L
    private var lastUiBarAnimating = false

    private val cachedFabs = ArrayList<WeakReference<View>>()
    private var cachedDateIndicator: WeakReference<View>? = null
    private var lastFabProbeAt = 0L
    private val appliedOffsets = WeakHashMap<View, Float>()
    private val paddedScrollViews = WeakHashMap<View, Int>()
    private var lastScrollProbeAt = 0L
    private var cachedDrawer: WeakReference<View>? = null
    private var lastDrawerProbeAt = 0L

    private var blurredBitmap: Bitmap? = null
    private var blurredGeneration = -1
    private var blurredWidth = 0
    private var blurredHeight = 0
    private var blurredEffect: RenderEffect? = null
    private var blurredConfig: GlassConfig? = null
    private var backdropDirty = true

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF3B30.toInt()
        style = Paint.Style.FILL
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    override val readyForDrawing: Boolean
        get() = true

    override val persistentRestingIndicatorFill: Boolean get() = true
    override val retainHiddenSourceFillWhileExpanded: Boolean get() = true
    override val allowsSoftwareBackdropCapture: Boolean get() = false

    override fun skipLiveBackdrop(selectedIndex: Int): Boolean = true
    override fun includeNavigationInIndicatorScene(surfaceActive: Boolean): Boolean = true

    override fun updateBackdropRendererMode(host: GlassHostLayout): Boolean {
        if (host.liveBackdropActiveForAdapter) host.clearLiveBackdropForAdapter()
        host.disableOpticalSurfaceNativeBlurForAdapter()
        host.enableOpticalSurfaceRendererForAdapter()
        return true
    }

    override fun onOpticalBackdropChanged() {
        backdropDirty = true
    }

    override fun onOpticalRendererConfigurationChanged() {
        blurredBitmap = null
        blurredGeneration = -1
        blurredConfig = null
        backdropDirty = true
    }

    override fun opticalBlurNodeNeedsRecord(
        prepared: Boolean, backdrop: Bitmap?, width: Int, height: Int, effect: RenderEffect?,
    ): Boolean = !prepared || backdropDirty || blurredBitmap !== backdrop ||
        blurredGeneration != (backdrop?.generationId ?: -1) ||
        blurredWidth != width || blurredHeight != height ||
        blurredEffect !== effect || blurredConfig != activeHost?.config

    override fun onOpticalBlurNodeRecorded(
        backdrop: Bitmap?, width: Int, height: Int, effect: RenderEffect?,
    ) {
        backdropDirty = false
        blurredBitmap = backdrop
        blurredGeneration = backdrop?.generationId ?: -1
        blurredWidth = width
        blurredHeight = height
        blurredEffect = effect
        blurredConfig = activeHost?.config
    }

    override fun shouldQueueOpticalRenderFromUi(animating: Boolean): Boolean {
        val lottieActive = isLottieAnimating()
        val queue = animating || lastUiBarAnimating || lottieActive
        lastUiBarAnimating = animating || lottieActive
        return queue
    }

    override fun onHostFrame(host: View) {
        val glass = host as? GlassHostLayout ?: return
        activeHost = glass
        prepare(glass.adapterNavigationSource) { delay, action -> glass.postDelayed(action, delay) }
        updateBackdropRendererMode(glass)
        if (isLottieAnimating()) {
            glass.postInvalidateOnAnimation()
        }
    }

    private fun isLottieAnimating(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now < lottieAnimatingUntil) return true
        for (i in 0 until 5) {
            val d = tabIconViews[i]?.get()?.drawable
            if (d is Animatable && d.isRunning) return true
        }
        return false
    }

    override fun suppressHostNativeChrome(source: ViewGroup?, root: View, selected: Int) {
        val nav = source ?: return
        if (nav.alpha != 0f) nav.alpha = 0f
        NeteaseMailAdapter.expandTabContentFullBleed(nav)
        refreshLiveBadges(nav)
    }

    override fun adjustFloatingActions(host: View, source: ViewGroup?) {
        val glass = host as? GlassHostLayout ?: return
        if (!glass.isAttachedToWindow || glass.width <= 0 || glass.height <= 0) return
        val root = glass.rootView as? ViewGroup ?: return
        val density = glass.resources.displayMetrics.density
        val hostRect = Rect()
        if (!glass.getGlobalVisibleRect(hostRect) || hostRect.top <= 0) return

        val now = SystemClock.uptimeMillis()
        if (now - lastFabProbeAt >= 240L || cachedFabs.none { it.get()?.let { v -> v.isAttachedToWindow && v.isShown } == true }) {
            lastFabProbeAt = now
            cachedFabs.clear()
            for (fabId in arrayOf("fab_compose", "fab_add_todo", "fab_add")) {
                findByIdName(root, fabId, glass)?.let { cachedFabs += WeakReference(it) }
            }
            findFloatingActionButtons(root, glass).forEach { btn ->
                if (cachedFabs.none { it.get() === btn }) cachedFabs += WeakReference(btn)
            }
            cachedDateIndicator = findByIdName(root, "date_indicator", glass)?.let { WeakReference(it) }
        }
        val fabPadding = 12f * density
        cachedFabs.forEach { ref -> liftVerticalClearance(ref.get(), hostRect.top, fabPadding) }
        liftVerticalClearance(cachedDateIndicator?.get(), hostRect.top, 0f)

        if (now - lastScrollProbeAt >= 320L) {
            lastScrollProbeAt = now
            val navH = source?.height?.takeIf { it > 0 } ?: (glass.height + (14f * density).toInt())
            applyScrollClearance(root, glass, hostRect, navH)
        }
    }

    private fun liftVerticalClearance(target: View?, hostTop: Int, clearancePx: Float) {
        if (target == null || !target.isAttachedToWindow || !target.isShown ||
            target.visibility != View.VISIBLE || target.height <= 0
        ) return
        val applied = appliedOffsets[target] ?: 0f
        val loc = IntArray(2).also(target::getLocationInWindow)
        val naturalBottom = loc[1] + target.height - applied
        val neededOffset = minOf(0f, hostTop - clearancePx - naturalBottom)
        if (abs(neededOffset - applied) > 0.5f) {
            target.translationY = (target.translationY - applied) + neededOffset
            appliedOffsets[target] = neededOffset
        }
    }

    private fun applyScrollClearance(root: ViewGroup, glass: GlassHostLayout, hostRect: Rect, minBottomPad: Int) {
        val stack = ArrayDeque<View>()
        stack += root
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 1024) {
            val v = stack.removeLast()
            if (v === glass || !v.isShown) continue
            val isList = v is ListView || v.javaClass.name.contains("RecyclerView")
            if (isList && v is ViewGroup) {
                val r = Rect()
                if (v.getGlobalVisibleRect(r) && r.bottom >= hostRect.top && v.paddingBottom < minBottomPad) {
                    paddedScrollViews.putIfAbsent(v, v.paddingBottom)
                    v.clipToPadding = false
                    v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, minBottomPad)
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) stack += v.getChildAt(i)
            }
        }
    }

    override fun pageAllowsNavigation(root: View?): Boolean? {
        val rootGroup = root as? ViewGroup ?: return null
        val nav = lastSource ?: activeHost?.adapterNavigationSource
        if (nav != null && (!nav.isAttachedToWindow || !nav.isShown)) {
            return false
        }
        val drawer = findDrawer(rootGroup) ?: return true
        if (!drawer.isShown || drawer.width <= 0) return true
        val rootLoc = IntArray(2).also(rootGroup::getLocationOnScreen)
        val drawerLoc = IntArray(2).also(drawer::getLocationOnScreen)
        val drawerRight = drawerLoc[0] + drawer.width
        return drawerRight <= rootLoc[0] + 24
    }

    override fun transientPageAllowsNavigation(activity: Activity?): Boolean {
        val root = activity?.window?.decorView as? ViewGroup ?: return true
        return pageAllowsNavigation(root) != false
    }

    private fun findDrawer(root: ViewGroup): View? {
        val cached = cachedDrawer?.get()?.takeIf { it.isAttachedToWindow }
        if (cached != null) return cached
        val now = SystemClock.uptimeMillis()
        if (now - lastDrawerProbeAt < 300L) return null
        lastDrawerProbeAt = now
        val found = findByIdName(root, "drawer_fragment_container", activeHost)
        cachedDrawer = found?.let { WeakReference(it) }
        return found
    }

    override fun prepare(source: ViewGroup?, schedule: (Long, () -> Unit) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper() || source == null) return
        if (lastSource !== source) {
            lastSource = source
            tabIconViews.fill(null)
            tabTextViews.fill(null)
            lastRetryAt = 0L
        }
        refreshLiveBadges(source)
        val now = SystemClock.uptimeMillis()
        val missingRefs = (0 until 5).any {
            tabIconViews[it]?.get()?.isAttachedToWindow != true || tabLabels[it] == null
        }
        val missingFallbacks = (0 until 5).any { tabIcons[it] == null }
        if (!missingRefs && !missingFallbacks) return
        if (now - lastRetryAt < 160L) return
        lastRetryAt = now

        var updated = false
        for (i in 0 until minOf(5, source.childCount)) {
            val cell = source.getChildAt(i) as? ViewGroup ?: continue
            if (tabTextViews[i]?.get()?.isAttachedToWindow != true) {
                findTabText(cell, "tab_text")?.let { tv ->
                    tabTextViews[i] = WeakReference(tv)
                    tv.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        tabLabels[i] = it
                    }
                    updated = true
                }
            }
            if (tabIconViews[i]?.get()?.isAttachedToWindow != true) {
                findTabIconView(cell)?.let { icon ->
                    tabIconViews[i] = WeakReference(icon)
                    updated = true
                }
            }
            if (tabIcons[i] == null) {
                captureTabIcon(cell)?.let { bmp ->
                    tabIcons[i] = bmp
                    updated = true
                }
            }
        }
        if (updated) activeHost?.invalidate()
        if ((0 until 5).any { tabIconViews[it]?.get() == null || tabIcons[it] == null }) {
            schedule(260L) {
                prepare(source, schedule)
                activeHost?.postInvalidateOnAnimation()
            }
        }
    }

    override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int): Int? {
        val nav = source ?: return null
        val now = SystemClock.uptimeMillis()
        var coloredSelected: Int? = null
        var viewSelected: Int? = null
        for (i in 0 until minOf(slotCount, nav.childCount)) {
            val cell = nav.getChildAt(i) as? ViewGroup ?: continue
            if (cell.isSelected) viewSelected = i
            val tv = tabTextViews[i]?.get()?.takeIf { it.isAttachedToWindow } ?: findTabText(cell, "tab_text")
            if (tv != null) {
                if (tv.isSelected) viewSelected = i
                val c = tv.currentTextColor
                val maxC = maxOf(Color.red(c), Color.green(c), Color.blue(c))
                val minC = minOf(Color.red(c), Color.green(c), Color.blue(c))
                if (maxC - minC > 40) {
                    coloredSelected = i
                    if (i == AI_AGENT_INDEX && Color.blue(c) > Color.green(c) + 25) {
                        aiAccentColor = c or 0xFF000000.toInt()
                    }
                }
            }
        }
        val detected = coloredSelected ?: viewSelected
        if (detected != null && detected != lastNativeSelected) {
            lastNativeSelected = detected
            lottieAnimatingUntil = maxOf(lottieAnimatingUntil, now + 850L)
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
        val nav = source ?: return false
        if (index !in 0 until nav.childCount) return false
        val cell = nav.getChildAt(index) ?: return false
        pendingSelection = index
        selectionGuardUntil = SystemClock.uptimeMillis() + 450L
        lottieAnimatingUntil = SystemClock.uptimeMillis() + 900L
        var dispatched = cell.performClick()
        if (!dispatched) {
            val cx = cell.width / 2f
            val cy = cell.height / 2f
            val t = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, cx, cy, 0)
            val up = MotionEvent.obtain(t, t + 16L, MotionEvent.ACTION_UP, cx, cy, 0)
            dispatched = cell.dispatchTouchEvent(down)
            cell.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
        }
        host.invalidate()
        return dispatched
    }

    override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) {
        drawTabs(canvas, frame, active = frame.hideSelectedForOptics)
    }

    override fun drawForeground(
        canvas: Canvas,
        frame: AdapterNavigationFrame,
        selectionPath: Path,
        drawSource: () -> Unit,
    ): Boolean {
        if (selectionPath.isEmpty) {
            drawTabs(canvas, frame, active = false)
            return true
        }
        val outside = canvas.save()
        canvas.clipOutPath(selectionPath)
        drawTabs(canvas, frame, active = false)
        canvas.restoreToCount(outside)

        val plain = 1f - frame.navigationOpticalMix.coerceIn(0f, 1f)
        if (plain > 0f) {
            val inside = canvas.save()
            canvas.clipPath(selectionPath)
            canvas.saveLayerAlpha(
                0f, 0f, frame.width.toFloat(), frame.height.toFloat(),
                (plain * 255f).toInt(),
            )
            drawTabs(canvas, frame, active = true)
            canvas.restoreToCount(inside)
        }
        return true
    }

    private fun drawTabs(canvas: Canvas, frame: AdapterNavigationFrame, active: Boolean) {
        if (frame.width <= 0 || frame.height <= 0 || frame.slotCount <= 0) return
        val heightScale = (frame.height / (64f * frame.density)).coerceIn(0.875f, 1f)
        val itemScale = frame.iconScale * frame.extraScale * heightScale
        val unselectedColor = if (frame.darkGlass) 0xFFE6E6E6.toInt() else 0xFF1F2329.toInt()
        val horizontalPadding = 4f * frame.density
        val defaultNames = arrayOf("邮件", "文件", "Mail Agent", "待办", "我")

        labelPaint.textSize = 10f * frame.scaledDensity
        val fm = labelPaint.fontMetrics
        val labelH = fm.descent - fm.ascent
        val liveIconViewport = 30f * frame.density
        val trimmedIconViewport = 24f * frame.density
        val gap = 1.5f * frame.density
        val visualIconH = 24f * frame.density
        val groupTop = (frame.height - (visualIconH + gap + labelH)) / 2f
        val iconCenterY = groupTop + visualIconH / 2f
        val labelBaseline = groupTop + visualIconH + gap - fm.ascent
        val nativeSelected = lastNativeSelected ?: frame.selectedIndex

        frame.enabledIndices.forEachIndexed { visualIndex, index ->
            val centerX = horizontalPadding + (visualIndex + 0.5f) * frame.slotWidth
            val label = tabLabels.getOrNull(index) ?: defaultNames.getOrNull(index) ?: ""
            val selected = active || index == frame.selectedIndex
            val slotAccent = if (index == AI_AGENT_INDEX) aiAccentColor else frame.accentColor
            val color = if (active || (frame.navigationOpticalMix < 0.5f && selected)) slotAccent else unselectedColor

            canvas.save()
            canvas.scale(itemScale, itemScale, centerX, frame.height / 2f)

            val liveIcon = tabIconViews.getOrNull(index)?.get()?.takeIf {
                it.isAttachedToWindow && it.width > 0 && it.height > 0 && it.drawable != null
            }
            if (liveIcon != null) {
                val w = liveIcon.width.toFloat().coerceAtLeast(1f)
                val h = liveIcon.height.toFloat().coerceAtLeast(1f)
                val scale = liveIconViewport / max(w, h)
                val dw = w * scale
                val dh = h * scale
                val needsTint = when {
                    index == nativeSelected && selected -> false // Keep native Lottie active color (red or Mail Agent purple)
                    active -> true                               // Pill dragging over another slot -> tint with slotAccent
                    frame.darkGlass -> true                      // Dark glass unselected -> bright tint
                    else -> false                                // Light glass unselected -> native Lottie dark outline & reverse anim
                }
                canvas.save()
                canvas.translate(centerX - dw / 2f, iconCenterY - dh / 2f)
                canvas.scale(scale, scale)
                val savedAlpha = liveIcon.alpha
                val savedVis = liveIcon.visibility
                try {
                    liveIcon.alpha = 1f
                    liveIcon.visibility = View.VISIBLE
                    if (needsTint) {
                        val layer = canvas.saveLayer(0f, 0f, w, h, null)
                        liveIcon.draw(canvas)
                        tintPaint.color = color
                        tintPaint.blendMode = BlendMode.SRC_IN
                        canvas.drawRect(0f, 0f, w, h, tintPaint)
                        canvas.restoreToCount(layer)
                    } else {
                        liveIcon.draw(canvas)
                    }
                } finally {
                    liveIcon.alpha = savedAlpha
                    liveIcon.visibility = savedVis
                }
                canvas.restore()
            } else {
                val bitmap = tabIcons.getOrNull(index)?.takeUnless(Bitmap::isRecycled)
                if (bitmap != null) {
                    val w = bitmap.width.toFloat().coerceAtLeast(1f)
                    val h = bitmap.height.toFloat().coerceAtLeast(1f)
                    val scale = (trimmedIconViewport / max(w, h)).coerceAtMost(1.35f)
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
            }

            if (label.isNotEmpty()) {
                labelPaint.color = color
                labelPaint.alpha = if (selected) 255 else 235
                canvas.drawText(frame.localizeNavigationLabel(label), centerX, labelBaseline, labelPaint)
            }

            val badgeText = badgeTexts.getOrNull(index)
            if (!badgeText.isNullOrEmpty()) {
                drawBadgeCount(
                    canvas, centerX + visualIconH * 0.45f, iconCenterY - visualIconH * 0.42f, badgeText, frame.density,
                )
            } else if (badgeDots.getOrNull(index) == true) {
                val r = 3.5f * frame.density
                canvas.drawCircle(centerX + visualIconH * 0.45f, iconCenterY - visualIconH * 0.42f, r, badgeBgPaint)
            }

            canvas.restore()
        }
        iconPaint.colorFilter = null
        iconPaint.alpha = 255
        labelPaint.alpha = 255
    }

    private fun drawBadgeCount(canvas: Canvas, cx: Float, cy: Float, text: String, density: Float) {
        badgeTextPaint.textSize = 9f * density
        val textWidth = badgeTextPaint.measureText(text)
        val badgeH = 14f * density
        val badgeW = max(badgeH, textWidth + 7f * density)
        val rect = RectF(cx - badgeW / 2f, cy - badgeH / 2f, cx + badgeW / 2f, cy + badgeH / 2f)
        canvas.drawRoundRect(rect, badgeH / 2f, badgeH / 2f, badgeBgPaint)
        val fm = badgeTextPaint.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, cx, baseline, badgeTextPaint)
    }

    private fun refreshLiveBadges(nav: ViewGroup) {
        for (i in 0 until minOf(5, nav.childCount)) {
            val cell = nav.getChildAt(i) as? ViewGroup ?: continue
            val countView = findTabText(cell, "count_view")
            badgeTexts[i] = if (countView != null && countView.visibility == View.VISIBLE) {
                countView.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            } else {
                null
            }
            val newView = findChildByIdName(cell, "new_view")
            badgeDots[i] = newView != null && newView.visibility == View.VISIBLE
        }
    }

    private fun findFloatingActionButtons(root: ViewGroup, exclude: View): List<View> {
        val result = ArrayList<View>()
        val stack = ArrayDeque<View>()
        stack += root
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 2048) {
            val v = stack.removeLast()
            if (v === exclude || !v.isShown) continue
            if (v.javaClass.name.endsWith("FloatingActionButton")) result += v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) stack += v.getChildAt(i)
            }
        }
        return result
    }

    private fun findByIdName(root: ViewGroup, entryName: String, exclude: View?): View? {
        val directId = root.resources.getIdentifier(entryName, "id", root.context.packageName)
        if (directId != 0) {
            root.findViewById<View>(directId)?.takeIf { it !== exclude }?.let { return it }
        }
        val stack = ArrayDeque<View>()
        stack += root
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 2048) {
            val v = stack.removeLast()
            if (v === exclude) continue
            if (AdapterNavigationSearch.resourceEntryName(v) == entryName) return v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) stack += v.getChildAt(i)
            }
        }
        return null
    }

    private fun findChildByIdName(cell: ViewGroup, entryName: String): View? {
        val stack = ArrayDeque<View>()
        stack += cell
        while (stack.isNotEmpty()) {
            val v = stack.removeLast()
            if (AdapterNavigationSearch.resourceEntryName(v) == entryName) return v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) stack += v.getChildAt(i)
            }
        }
        return null
    }

    private fun findTabText(cell: ViewGroup, entryName: String): TextView? =
        findChildByIdName(cell, entryName) as? TextView

    private fun findTabIconView(cell: ViewGroup): ImageView? {
        (findChildByIdName(cell, "tab_icon") as? ImageView)?.let { return it }
        val stack = ArrayDeque<View>()
        stack += cell
        while (stack.isNotEmpty()) {
            val v = stack.removeLast()
            if (v is ImageView && v.width > 0 && v.height > 0) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) stack += v.getChildAt(i)
        }
        return null
    }

    private fun captureTabIcon(cell: ViewGroup): Bitmap? {
        val iconView = findTabIconView(cell) ?: return null
        val w = iconView.width.takeIf { it > 0 } ?: iconView.drawable?.intrinsicWidth?.takeIf { it > 0 } ?: return null
        val h = iconView.height.takeIf { it > 0 } ?: iconView.drawable?.intrinsicHeight?.takeIf { it > 0 } ?: return null
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
            trimTransparent(raw) ?: iconView.drawable?.let { drawable ->
                val raw2 = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val savedBounds = Rect(drawable.bounds)
                val savedDAlpha = drawable.alpha
                try {
                    drawable.alpha = 255
                    drawable.setVisible(true, false)
                    drawable.setBounds(0, 0, w, h)
                    drawable.draw(Canvas(raw2))
                } finally {
                    drawable.bounds = savedBounds
                    drawable.alpha = savedDAlpha
                }
                trimTransparent(raw2)
            }
        }.getOrNull()
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

    override fun dispose() {
        appliedOffsets.forEach { (view, applied) ->
            if (view.isAttachedToWindow) view.translationY -= applied
        }
        appliedOffsets.clear()
        cachedDateIndicator = null
        blurredBitmap = null
        blurredGeneration = -1
        blurredConfig = null
        paddedScrollViews.forEach { (view, base) ->
            if (view.isAttachedToWindow) {
                view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, base)
            }
        }
        paddedScrollViews.clear()
        cachedFabs.clear()
        cachedDrawer = null
        tabIconViews.fill(null)
        tabTextViews.fill(null)
        tabIcons.forEach { it?.takeUnless(Bitmap::isRecycled)?.recycle() }
        tabIcons.fill(null)
        lastSource = null
    }

    private companion object {
        const val AI_AGENT_INDEX = 2
        const val DEFAULT_AI_PURPLE = 0xFF5D54F7.toInt()
    }
}
