package io.github.offlineglass.hook.adapters.share

import android.content.res.Configuration
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XposedHelpers
import io.github.offlineglass.config.ConfigContract
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.globalGlassBottomGapPx
import java.util.WeakHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/** Projects Share's CustomBottomBar tabs, hides chrome for navigation drawer, and lifts FAB cleanly. */
internal class ShareNavigationState(private val host: GlassHostLayout) : AppNavigationState {
    private data class TabItem(
        val root: View,
        val icon: View?,
        val label: TextView?,
        val badges: List<View>,
    )

    private var source: ViewGroup? = null
    private var items: List<TabItem> = emptyList()
    private var refreshAt = 0L
    private var reportedLabels = emptyList<String>()
    private var reportingLabels = false
    private var nextLabelProbe = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val iconLayerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val iconBounds = RectF()
    private val hostRect = Rect()
    private val coordinatorLocation = IntArray(2)
    private val iconPosition = IntArray(2)
    private val badgePosition = IntArray(2)
    private val baseFabMargins = WeakHashMap<View, Int>()
    private var cachedFab: View? = null

    override fun prepare(source: ViewGroup?, schedule: (Long, () -> Unit) -> Unit) {
        val visible = ShareAdapter.visibleTabs(source)
        val now = SystemClock.uptimeMillis()
        if (this.source === source && items.size == visible.size && now < refreshAt &&
            items.zip(visible).all { (item, view) -> item.root === view && item.root.isAttachedToWindow }) {
            return
        }
        this.source = source
        refreshAt = now + 250L
        items = visible.map(::inspectTabItem)
    }

    override fun onHostFrame(host: View) {
        source?.let(::suppressChrome)
        reportNativeLabels()
        if (items.any { item ->
                (listOfNotNull(item.icon) + item.badges).any { it.visibility == View.VISIBLE && it.isDirty }
            }) {
            host.postInvalidateOnAnimation()
        }
    }

    override fun suppressNativeChrome(source: ViewGroup?) {
        val bar = source ?: this.source ?: return
        if (ShareAdapter.isCustomBottomBar(bar)) suppressChrome(bar)
    }

    override fun adjustFloatingActions(host: View, source: ViewGroup?) {
        val glassHost = host as? GlassHostLayout ?: return
        if (!glassHost.isShown || glassHost.height <= 0 ||
            !glassHost.getGlobalVisibleRect(hostRect) || hostRect.height() <= 0) return
        val coordinator = ((source ?: this.source)?.parent as? ViewGroup) ?: (glassHost.rootView as? ViewGroup) ?: return
        val fab = cachedFab?.takeIf { it.isAttachedToWindow } ?: findFab(coordinator)?.also { cachedFab = it } ?: return
        val lp = fab.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val baseMargin = baseFabMargins.getOrPut(fab) { lp.bottomMargin }
        val parent = fab.parent as? View ?: return
        parent.getLocationInWindow(coordinatorLocation)
        val parentBottom = coordinatorLocation[1] + parent.height
        val gap = max(globalGlassBottomGapPx(glassHost), (12f * glassHost.resources.displayMetrics.density).roundToInt())
        val desiredBottomMargin = max(baseMargin, parentBottom - (hostRect.top - gap))
        if (lp.bottomMargin != desiredBottomMargin) {
            lp.bottomMargin = desiredBottomMargin
            fab.layoutParams = lp
        }
    }

    override fun hostPageAllowsNavigation(host: GlassHostLayout, root: View?): Boolean {
        val bar = source ?: return true
        if (!bar.isAttachedToWindow || !bar.isShown) return false
        return !isDrawerOpen(bar, root)
    }

    override fun resolveAutoDarkMode(host: View): Boolean? {
        val bar = source
        if (bar != null) {
            sampledNativeBgDark[bar]?.let { return it }
            (bar.parent as? View)?.let(::backgroundDark)?.let { return it }
        }
        val uiNight = (host.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (uiNight) return true
        return (host as? GlassHostLayout)?.adapterContentOrSystemDark() ?: uiNight
    }

    override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int): Int? {
        val currentItems = if (items.isNotEmpty()) items else ShareAdapter.visibleTabs(source).map(::inspectTabItem)
        return currentItems.indexOfFirst { it.root.isSelected }.takeIf { it in 0 until slotCount }
    }

    override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int): Boolean {
        val visible = ShareAdapter.visibleTabs(source ?: this.source)
        val tab = visible.getOrNull(index) ?: items.getOrNull(index)?.root ?: return false
        return tab.performClick()
    }

    override fun onTapSucceeded(requestRefresh: (Long) -> Unit) {
        refreshAt = 0L
        requestRefresh(0L)
        requestRefresh(80L)
    }

    override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) {
        val unselectedColor = if (frame.darkGlass) 0xFFE6E6E6.toInt() else 0xFF5F6672.toInt()
        for ((visual, index) in frame.enabledIndices.withIndex()) {
            val item = items.getOrNull(index) ?: continue
            val centerX = 4f * frame.density + (visual + 0.5f) * frame.slotWidth
            val centerY = frame.height / 2f
            val selected = index == frame.selectedIndex
            val slotColor = if (selected) frame.accentColor else unselectedColor
            drawSlot(canvas, item, centerX, centerY, frame, slotColor)
        }
    }

    private fun drawSlot(
        canvas: Canvas,
        item: TabItem,
        centerX: Float,
        centerY: Float,
        frame: AdapterNavigationFrame,
        slotColor: Int,
    ) {
        val icon = item.icon?.takeIf { it.width > 0 && it.height > 0 }
        val text = item.label?.takeIf { it.visibility == View.VISIBLE }?.text?.toString()?.trim().orEmpty()
        val showLabel = !frame.iconOnly && text.isNotEmpty()
        val baseIconDp = if (showLabel) 23f else 25f
        val iconSize = if (icon == null) 0f else baseIconDp * frame.density * frame.iconScale
        labelPaint.textSize = frame.textSize * frame.scaledDensity
        labelPaint.typeface = item.label?.typeface
        val fm = labelPaint.fontMetrics
        val labelHeight = if (!showLabel) 0f else fm.descent - fm.ascent
        val gap = if (icon != null && showLabel) 2f * frame.density else 0f
        val top = centerY - (iconSize + gap + labelHeight) / 2f
        val labelBaseline = top + iconSize + gap - fm.ascent

        val save = canvas.save()
        try {
            canvas.scale(frame.extraScale, frame.extraScale, centerX, centerY)
            if (icon != null) {
                val scale = iconSize / max(icon.width, icon.height).coerceAtLeast(1)
                val dw = icon.width * scale
                val dh = icon.height * scale
                val x = centerX - dw / 2f
                val y = top + (iconSize - dh) / 2f
                drawTintedLeaf(canvas, icon, x, y, dw, dh, scale, slotColor)
                icon.getLocationInWindow(iconPosition)
                for (badge in item.badges) {
                    if (badge.visibility != View.VISIBLE || badge.width <= 0 || badge.height <= 0) continue
                    badge.getLocationInWindow(badgePosition)
                    drawRawLeaf(
                        canvas,
                        badge,
                        x + (badgePosition[0] - iconPosition[0]) * scale,
                        y + (badgePosition[1] - iconPosition[1]) * scale,
                        scale,
                    )
                }
            }
            if (showLabel) {
                labelPaint.color = slotColor
                canvas.drawText(text, centerX, labelBaseline, labelPaint)
            }
        } finally {
            canvas.restoreToCount(save)
        }
    }

    private fun drawTintedLeaf(
        canvas: Canvas,
        view: View,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        scale: Float,
        color: Int,
    ) {
        iconBounds.set(x - 1f, y - 1f, x + width + 1f, y + height + 1f)
        iconLayerPaint.colorFilter = BlendModeColorFilter(color, BlendMode.SRC_IN)
        val layer = canvas.saveLayer(iconBounds, iconLayerPaint)
        val savedAlpha = view.alpha
        try {
            view.alpha = 1f
            canvas.translate(x, y)
            canvas.scale(scale, scale)
            view.draw(canvas)
        } finally {
            view.alpha = savedAlpha
            iconLayerPaint.colorFilter = null
            canvas.restoreToCount(layer)
        }
    }

    private fun drawRawLeaf(canvas: Canvas, view: View, x: Float, y: Float, scale: Float) {
        val save = canvas.save()
        val savedAlpha = view.alpha
        try {
            view.alpha = 1f
            canvas.translate(x, y)
            canvas.scale(scale, scale)
            view.draw(canvas)
        } finally {
            view.alpha = savedAlpha
            canvas.restoreToCount(save)
        }
    }

    private fun inspectTabItem(tabRoot: View): TabItem {
        var icon: ImageView? = null
        var label: TextView? = null
        val badges = ArrayList<View>(2)
        if (tabRoot is ViewGroup) {
            for (i in 0 until tabRoot.childCount) {
                val child = tabRoot.getChildAt(i)
                when {
                    child is ImageView && icon == null -> icon = child
                    child is TextView && label == null -> label = child
                    else -> badges += child
                }
            }
        }
        return TabItem(tabRoot, icon, label, badges)
    }

    private fun findFab(root: ViewGroup): View? {
        val queue = ArrayDeque<Pair<View, Int>>()
        queue += root to 0
        while (queue.isNotEmpty()) {
            val (view, depth) = queue.removeFirst()
            if (view.javaClass.name == FAB_CLASS ||
                view.javaClass.superclass?.name?.endsWith("FloatingActionButton") == true) {
                return view
            }
            if (view is ViewGroup && depth < 4) {
                for (i in 0 until view.childCount) queue += view.getChildAt(i) to (depth + 1)
            }
        }
        return null
    }

    private fun isDrawerOpen(bar: ViewGroup, root: View?): Boolean {
        var parent: View? = bar.parent as? View
        var drawerLayout: ViewGroup? = null
        repeat(6) {
            val current = parent ?: return@repeat
            if (current is ViewGroup && isDrawerLayout(current)) {
                drawerLayout = current
                return@repeat
            }
            parent = current.parent as? View
        }
        val drawer = drawerLayout ?: (root as? ViewGroup)?.takeIf(::isDrawerLayout) ?: return false
        for (i in 0 until drawer.childCount) {
            val child = drawer.getChildAt(i)
            if (isNavigationView(child) && child.visibility == View.VISIBLE && child.width > 0 &&
                child.left + child.translationX + child.width > 2f) {
                return true
            }
        }
        return false
    }

    private fun isDrawerLayout(view: View): Boolean {
        var type: Class<*>? = view.javaClass
        while (type != null) {
            if (type.name.endsWith("DrawerLayout")) return true
            type = type.superclass
        }
        return false
    }

    private fun isNavigationView(view: View): Boolean {
        var type: Class<*>? = view.javaClass
        while (type != null) {
            if (type.name == "com.google.android.material.navigation.NavigationView") return true
            type = type.superclass
        }
        return false
    }

    private fun reportNativeLabels() {
        if (reportingLabels || items.size !in 2..5) return
        val now = SystemClock.uptimeMillis()
        if (now < nextLabelProbe) return
        nextLabelProbe = now + 1_000L
        val defaults = if (items.size == 2) listOf("首页", "发现") else listOf("首页", "消息", "发现")
        val labels = items.mapIndexed { index, item ->
            item.label?.takeIf { it.visibility == View.VISIBLE }?.text?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: item.root.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: defaults.getOrElse(index) { "第 ${index + 1} 个入口" }
        }
        if (labels == reportedLabels) return
        reportingLabels = true
        labelReporter.execute {
            val success = runCatching {
                host.context.contentResolver.call(
                    ConfigContract.URI,
                    ConfigContract.METHOD_REPORT_NAVIGATION,
                    host.context.packageName,
                    Bundle().apply {
                        putStringArrayList(ConfigContract.KEY_NAVIGATION_LABELS, ArrayList(labels))
                    },
                ) != null
            }.getOrDefault(false)
            mainHandler.post {
                if (success) reportedLabels = labels
                else nextLabelProbe = SystemClock.uptimeMillis() + 30_000L
                reportingLabels = false
            }
        }
    }

    override fun onHostDetached() = dispose()

    override fun dispose() {
        baseFabMargins.forEach { (fab, base) ->
            if (fab.isAttachedToWindow) {
                val lp = fab.layoutParams as? ViewGroup.MarginLayoutParams
                if (lp != null && lp.bottomMargin != base) {
                    lp.bottomMargin = base
                    fab.layoutParams = lp
                }
            }
        }
        baseFabMargins.clear()
        cachedFab = null
        source = null
        items = emptyList()
        refreshAt = 0L
    }

    companion object {
        private const val FAB_CLASS = "com.hengye.share.ui.widget.fab.AnimatedFloatingActionButton"
        private val sampledNativeBgDark = WeakHashMap<View, Boolean>()
        private val labelReporter = Executors.newSingleThreadExecutor { task ->
            Thread(task, "LiquidTab-ShareLabels").apply { isDaemon = true }
        }

        internal fun suppressChrome(bar: ViewGroup) {
            if (!sampledNativeBgDark.containsKey(bar)) {
                val detected = backgroundDark(bar)
                    ?: (bar.parent as? View)?.let(::backgroundDark)
                    ?: bar.rootView?.let(::backgroundDark)
                if (detected != null) sampledNativeBgDark[bar] = detected
            }
            if (bar.alpha != 0f) bar.alpha = 0f
            if (bar.elevation != 0f) bar.elevation = 0f
            if (bar.translationZ != 0f) bar.translationZ = 0f
            if (bar.outlineProvider != null) bar.outlineProvider = null
            if (bar.background != null) bar.background = null
            if (bar.foreground != null) bar.foreground = null
            val targetOffset = max(bar.height.toFloat(), 320f)
            if (bar.translationY < targetOffset) bar.translationY = targetOffset

            val container = (0 until bar.childCount).asSequence()
                .map(bar::getChildAt)
                .filterIsInstance<ViewGroup>()
                .firstOrNull()
            if (container != null) {
                if (container.background != null) container.background = null
                for (i in 0 until container.childCount) {
                    val child = container.getChildAt(i)
                    if (child !is LinearLayout && child.visibility != View.GONE) {
                        child.visibility = View.GONE
                    }
                }
            }

            // Keep any bottom spacer view collapsed so Share's feed renders edge-to-edge underneath.
            val coordinator = bar.parent as? ViewGroup ?: return
            for (i in 0 until coordinator.childCount) {
                val child = coordinator.getChildAt(i)
                if (child !== bar && child is ViewGroup && child.childCount >= 2) {
                    val last = child.getChildAt(child.childCount - 1)
                    if (last.javaClass == View::class.java && last.visibility != View.GONE) {
                        last.visibility = View.GONE
                    }
                }
            }
        }

        private fun backgroundDark(view: View): Boolean? {
            val bg = view.background ?: return null
            val color = when (bg) {
                is ColorDrawable -> bg.color
                else -> runCatching {
                    (XposedHelpers.callMethod(bg, "getFillColor") as? android.content.res.ColorStateList)?.defaultColor
                }.getOrNull() ?: return null
            }
            if (Color.alpha(color) < 128) return null
            val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0
            return luminance < 0.5
        }
    }
}
