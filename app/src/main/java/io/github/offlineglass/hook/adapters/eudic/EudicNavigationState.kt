package io.github.offlineglass.hook.adapters.eudic

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
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import de.robv.android.xposed.XposedHelpers
import io.github.offlineglass.config.ConfigContract
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AppNavigationState
import java.util.WeakHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/** Projects Eudic visible Material bottom tabs and expands the fragment container full-bleed. */
internal class EudicNavigationState(private val host: GlassHostLayout) : AppNavigationState {
    private data class TabItem(
        val root: View,
        val icon: View?,
        val largeLabel: TextView?,
        val smallLabel: TextView?,
        val fallbackLabel: String,
        val badges: List<View>,
    )

    private var source: ViewGroup? = null
    private var items: List<TabItem> = emptyList()
    private var refreshAt = 0L
    private var reportedLabels = emptyList<String>()
    private var reportingLabels = false
    private var nextLabelProbe = 0L
    private var nextClearanceAt = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val iconLayerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val iconBounds = RectF()
    private val iconPosition = IntArray(2)
    private val badgePosition = IntArray(2)
    private val scrollBasePaddings = WeakHashMap<ViewGroup, IntArray>()
    private val controlBasePaddings = WeakHashMap<View, IntArray>()
    private val controlBaseMargins = WeakHashMap<View, Int>()

    override fun prepare(source: ViewGroup?, schedule: (Long, () -> Unit) -> Unit) {
        val visible = EudicAdapter.visibleTabViews(source)
        val now = SystemClock.uptimeMillis()
        if (this.source === source && items.size == visible.size && now < refreshAt &&
            items.zip(visible).all { (item, view) -> item.root === view && item.root.isAttachedToWindow }) {
            return
        }
        this.source = source
        refreshAt = now + 250L
        nextClearanceAt = 0L
        items = visible.map(::inspectTabItem)
    }

    override fun onHostFrame(host: View) {
        source?.let { nav ->
            suppressChrome(nav)
            syncContentClearance(host, nav)
        }
        reportNativeLabels()
        if (items.any { item ->
                (listOfNotNull(item.icon) + item.badges).any { it.visibility == View.VISIBLE && it.isDirty }
            }) {
            host.postInvalidateOnAnimation()
        }
    }

    override fun suppressNativeChrome(source: ViewGroup?) {
        val nav = source ?: this.source ?: return
        if (EudicAdapter.isBottomNavView(nav)) suppressChrome(nav)
    }

    override fun hostPageAllowsNavigation(host: GlassHostLayout, root: View?): Boolean {
        val nav = source ?: return true
        return nav.isAttachedToWindow && nav.isShown
    }

    override fun resolveAutoDarkMode(host: View): Boolean? {
        val nav = source
        if (nav != null) {
            sampledNativeBgDark[nav]?.let { return it }
            themeBackgroundDark(nav)?.let { return it }
            (nav.parent as? View)?.let(::backgroundDark)?.let { return it }
        }
        val uiNight = (host.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return uiNight || ((host as? GlassHostLayout)?.deviceNightMode() == true)
    }

    override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int): Int? {
        val currentItems = if (items.isNotEmpty()) items else EudicAdapter.visibleTabViews(source).map(::inspectTabItem)
        currentItems.indexOfFirst { isItemChecked(it.root) }
            .takeIf { it in 0 until slotCount }
            ?.let { return it }
        val nav = source ?: return null
        val selectedId = runCatching { (XposedHelpers.callMethod(nav, "getSelectedItemId") as? Number)?.toInt() }
            .getOrNull() ?: return null
        return currentItems.indexOfFirst { itemId(it.root) == selectedId }
            .takeIf { it in 0 until slotCount }
    }

    override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int): Boolean {
        val visible = EudicAdapter.visibleTabViews(source ?: this.source)
        val tab = visible.getOrNull(index) ?: items.getOrNull(index)?.root ?: return false
        return tab.performClick()
    }

    override fun onTapSucceeded(requestRefresh: (Long) -> Unit) {
        refreshAt = 0L
        nextClearanceAt = 0L
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
        val text = resolveItemLabel(item)
        val showLabel = !frame.iconOnly && text.isNotEmpty()
        val baseIconDp = if (showLabel) 23f else 25f
        val iconSize = if (icon == null) 0f else baseIconDp * frame.density * frame.iconScale
        labelPaint.textSize = frame.textSize * frame.scaledDensity
        labelPaint.typeface = (item.largeLabel ?: item.smallLabel)?.typeface
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
        val res = tabRoot.resources
        val pkg = tabRoot.context.packageName
        val iconId = res.getIdentifier("navigation_bar_item_icon_view", "id", pkg)
        val largeId = res.getIdentifier("navigation_bar_item_large_label_view", "id", pkg)
        val smallId = res.getIdentifier("navigation_bar_item_small_label_view", "id", pkg)
        val indicatorId = res.getIdentifier("navigation_bar_item_active_indicator_view", "id", pkg)
        val iconContainerId = res.getIdentifier("navigation_bar_item_icon_container", "id", pkg)
        val labelsGroupId = res.getIdentifier("navigation_bar_item_labels_group", "id", pkg)

        val icon = (if (iconId != 0) tabRoot.findViewById<View>(iconId) else null) ?: findFirstImage(tabRoot)
        val large = (if (largeId != 0) tabRoot.findViewById<TextView>(largeId) else null)
        val small = (if (smallId != 0) tabRoot.findViewById<TextView>(smallId) else null)
        val badges = ArrayList<View>(2)
        val queue = ArrayDeque<View>()
        if (tabRoot is ViewGroup) {
            for (i in 0 until tabRoot.childCount) queue += tabRoot.getChildAt(i)
        }
        while (queue.isNotEmpty()) {
            val child = queue.removeFirst()
            val id = child.id
            if (child === icon || child === large || child === small || (indicatorId != 0 && id == indicatorId)) {
                continue
            }
            if ((iconContainerId != 0 && id == iconContainerId) || (labelsGroupId != 0 && id == labelsGroupId)) {
                if (child is ViewGroup) for (i in 0 until child.childCount) queue += child.getChildAt(i)
                continue
            }
            if (child is ViewGroup && child.childCount > 0) {
                for (i in 0 until child.childCount) queue += child.getChildAt(i)
            } else {
                badges += child
            }
        }
        val title = runCatching {
            val itemData = XposedHelpers.callMethod(tabRoot, "getItemData")
            itemData?.let { XposedHelpers.callMethod(it, "getTitle")?.toString()?.trim() }
        }.getOrNull().orEmpty()
        return TabItem(tabRoot, icon, large, small, title, badges)
    }

    private fun resolveItemLabel(item: TabItem): String {
        val visibleLarge = item.largeLabel?.takeIf { it.visibility == View.VISIBLE }?.text?.toString()?.trim()
        if (!visibleLarge.isNullOrEmpty()) return visibleLarge
        val visibleSmall = item.smallLabel?.takeIf { it.visibility == View.VISIBLE }?.text?.toString()?.trim()
        if (!visibleSmall.isNullOrEmpty()) return visibleSmall
        val anyLabel = item.largeLabel?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: item.smallLabel?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        if (!anyLabel.isNullOrEmpty()) return anyLabel
        if (item.fallbackLabel.isNotEmpty()) return item.fallbackLabel
        return item.root.contentDescription?.toString()?.trim().orEmpty()
    }

    private fun reportNativeLabels() {
        if (reportingLabels || items.size !in 2..6) return
        val now = SystemClock.uptimeMillis()
        if (now < nextLabelProbe) return
        nextLabelProbe = now + 1_000L
        val labels = items.mapIndexed { index, item ->
            resolveItemLabel(item).ifBlank { "第 ${index + 1} 个入口" }
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

    /** Pads scrollable content and lifts bottom-anchored AI controls above the floating glass pill. */
    private fun syncContentClearance(host: View, navigation: ViewGroup) {
        val now = SystemClock.uptimeMillis()
        if (now < nextClearanceAt) return
        nextClearanceAt = now + 120L

        if (!host.isShown || host.height <= 0) return
        val container = navigation.parent as? ViewGroup ?: return
        val pkg = navigation.context.packageName
        val res = navigation.resources
        val fragmentId = res.getIdentifier("fragment_container", "id", pkg)
        if (fragmentId == 0) return
        val fragmentContainer = container.findViewById<ViewGroup>(fragmentId) ?: return

        val hostRect = Rect()
        val containerRect = Rect()
        if (!host.getGlobalVisibleRect(hostRect) || !fragmentContainer.getGlobalVisibleRect(containerRect)) return
        if (hostRect.height() < host.height / 2 || hostRect.top <= containerRect.top || containerRect.bottom <= hostRect.top) {
            return
        }

        val density = host.resources.displayMetrics.density
        val wantedContentBottom = hostRect.top - (8f * density).roundToInt()
        val fullClearance = (containerRect.bottom - wantedContentBottom).coerceAtLeast(0)
        val pillLift = (containerRect.bottom - hostRect.top).coerceAtLeast(0)

        applyScrollClearance(fragmentContainer, host, navigation, wantedContentBottom)
        applyAiBottomControlClearance(fragmentContainer, pkg, fullClearance, pillLift)
    }

    private fun applyScrollClearance(
        fragmentContainer: ViewGroup,
        host: View,
        navigation: ViewGroup,
        wantedContentBottom: Int,
    ) {
        val candidates = ArrayList<ViewGroup>()
        val stack = ArrayDeque<Pair<View, Int>>()
        stack.addLast(fragmentContainer to 0)
        val rect = Rect()

        while (stack.isNotEmpty()) {
            val (view, depth) = stack.removeLast()
            if (view === host || view === navigation || !view.isShown) continue
            if (view !is ViewGroup) continue

            val className = view.javaClass.name
            val parentName = view.parent?.javaClass?.name.orEmpty()
            val isPager = className.contains("ViewPager") || parentName.contains("ViewPager2")
            val scrollLike = !isPager && (
                className.contains("ScrollView") ||
                    className.contains("NestedScroll") ||
                    className.contains("RecyclerView") ||
                    className.contains("ListView")
                )
            if (scrollLike && !isHorizontalOnlyRecycler(view, className)) {
                if (view.getGlobalVisibleRect(rect) &&
                    rect.height() >= host.height * 2 &&
                    rect.top < wantedContentBottom &&
                    rect.bottom > wantedContentBottom &&
                    candidates.none { isDescendantOf(view, it) }
                ) {
                    candidates += view
                    continue
                }
            }
            if (depth < 24) {
                for (i in view.childCount - 1 downTo 0) {
                    stack.addLast(view.getChildAt(i) to depth + 1)
                }
            }
        }

        for (view in candidates) {
            if (!view.getGlobalVisibleRect(rect)) continue
            val base = scrollBasePaddings.getOrPut(view) {
                intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
            }
            val requiredBottom = base[3] + (rect.bottom - wantedContentBottom).coerceAtLeast(0)
            if (view.paddingBottom != requiredBottom || view.clipToPadding) {
                view.setPadding(base[0], base[1], base[2], requiredBottom)
                view.clipToPadding = false
            }
        }
    }

    private fun applyAiBottomControlClearance(
        fragmentContainer: ViewGroup,
        pkg: String,
        fullClearance: Int,
        pillLift: Int,
    ) {
        val res = fragmentContainer.resources
        // Translation tab ("翻译"): preserve existing 20dp margin above the glass pill.
        listOf("voice_button", "liv_voice_anim").forEach { entry ->
            val id = res.getIdentifier(entry, "id", pkg)
            if (id != 0) findAllViewsById(fragmentContainer, id).forEach { adjustBottomMargin(it, pillLift) }
        }
        // Simultaneous interpretation ("同传"): pad record_container so bottom mic clears pill.
        val recordId = res.getIdentifier("record_container", "id", pkg)
        val bottomContainerId = res.getIdentifier("bottom_container", "id", pkg)
        if (recordId != 0 && bottomContainerId != 0) {
            findAllViewsById(fragmentContainer, recordId).forEach { recordView ->
                if (recordView.findViewById<View>(bottomContainerId) != null) {
                    adjustBottomPadding(recordView, pillLift, clearClip = false)
                }
            }
        }
        // AI writing / correction ("批改"): pad root ConstraintLayout (keeps its full-bleed background).
        val webEditorId = res.getIdentifier("web_editor", "id", pkg)
        val toolbarId = res.getIdentifier("editor_toolbar", "id", pkg)
        if (webEditorId != 0 && toolbarId != 0) {
            findAllViewsById(fragmentContainer, webEditorId).forEach { editor ->
                val writerRoot = editor.parent as? ViewGroup ?: return@forEach
                if (writerRoot !== fragmentContainer && writerRoot.findViewById<View>(toolbarId) != null) {
                    adjustBottomPadding(writerRoot, fullClearance, clearClip = true)
                }
            }
        }
        // AI chat / speaking ("口语"): lift bottom input bar above the pill.
        val inputLayoutId = res.getIdentifier("input_layout", "id", pkg)
        val chatContainerId = res.getIdentifier("chatContainer", "id", pkg)
        if (inputLayoutId != 0 && chatContainerId != 0) {
            findAllViewsById(fragmentContainer, inputLayoutId).forEach { input ->
                val chatParent = input.parent as? View
                if (chatParent?.id == chatContainerId) {
                    adjustBottomMargin(input, fullClearance)
                }
            }
        }
    }

    private fun adjustBottomMargin(view: View, extraMargin: Int) {
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val base = controlBaseMargins.getOrPut(view) { lp.bottomMargin }
        val target = base + extraMargin
        if (lp.bottomMargin != target) {
            lp.bottomMargin = target
            view.layoutParams = lp
        }
    }

    private fun adjustBottomPadding(view: View, extraPadding: Int, clearClip: Boolean) {
        val base = controlBasePaddings.getOrPut(view) {
            intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
        }
        val target = base[3] + extraPadding
        val needsClip = clearClip && view is ViewGroup && view.clipToPadding
        if (view.paddingBottom != target || needsClip) {
            view.setPadding(base[0], base[1], base[2], target)
            if (needsClip) view.clipToPadding = false
        }
    }

    private fun isHorizontalOnlyRecycler(view: ViewGroup, className: String): Boolean {
        if (!className.contains("RecyclerView")) return false
        val lm = runCatching { XposedHelpers.callMethod(view, "getLayoutManager") }.getOrNull() ?: return false
        val horiz = runCatching { XposedHelpers.callMethod(lm, "canScrollHorizontally") as? Boolean == true }.getOrDefault(false)
        val vert = runCatching { XposedHelpers.callMethod(lm, "canScrollVertically") as? Boolean == true }.getOrDefault(false)
        return horiz && !vert
    }

    private fun findAllViewsById(root: ViewGroup, id: Int): List<View> {
        val result = ArrayList<View>()
        val stack = ArrayDeque<View>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val view = stack.removeLast()
            if (view.id == id) result += view
            if (view is ViewGroup) {
                for (i in view.childCount - 1 downTo 0) stack.addLast(view.getChildAt(i))
            }
        }
        return result
    }

    private fun isDescendantOf(view: View, ancestor: ViewGroup): Boolean {
        var current = view.parent
        while (current is View) {
            if (current === ancestor) return true
            current = current.parent
        }
        return false
    }

    private fun findFirstImage(root: View): ImageView? {
        if (root is ImageView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findFirstImage(root.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun isItemChecked(tabRoot: View): Boolean {
        if (tabRoot.isSelected || tabRoot.isActivated) return true
        return runCatching {
            val itemData = XposedHelpers.callMethod(tabRoot, "getItemData") ?: return@runCatching false
            XposedHelpers.callMethod(itemData, "isChecked") as? Boolean == true
        }.getOrDefault(false)
    }

    private fun itemId(tabRoot: View): Int =
        runCatching {
            val itemData = XposedHelpers.callMethod(tabRoot, "getItemData")
            (XposedHelpers.callMethod(itemData, "getItemId") as? Number)?.toInt() ?: tabRoot.id
        }.getOrDefault(tabRoot.id)

    override fun onHostDetached() = dispose()

    override fun dispose() {
        source = null
        items = emptyList()
        refreshAt = 0L
    }

    companion object {
        private val sampledNativeBgDark = WeakHashMap<View, Boolean>()
        private val labelReporter = Executors.newSingleThreadExecutor { task ->
            Thread(task, "LiquidTab-EudicLabels").apply { isDaemon = true }
        }

        internal fun suppressChrome(navigation: ViewGroup) {
            if (!sampledNativeBgDark.containsKey(navigation)) {
                val detected = backgroundDark(navigation)
                    ?: themeBackgroundDark(navigation)
                    ?: (navigation.parent as? View)?.let(::backgroundDark)
                if (detected != null) sampledNativeBgDark[navigation] = detected
            }
            if (navigation.alpha != 0f) navigation.alpha = 0f
            if (navigation.elevation != 0f) navigation.elevation = 0f
            if (navigation.translationZ != 0f) navigation.translationZ = 0f
            if (navigation.outlineProvider != null) navigation.outlineProvider = null
            if (navigation.background != null) navigation.background = null
            if (navigation.foreground != null) navigation.foreground = null
            val targetOffset = max(navigation.height.toFloat(), 320f)
            if (navigation.translationY < targetOffset) navigation.translationY = targetOffset

            val pkg = navigation.context.packageName
            val res = navigation.resources
            val indicatorId = res.getIdentifier("navigation_bar_item_active_indicator_view", "id", pkg)
            val menuView = (0 until navigation.childCount).asSequence()
                .map(navigation::getChildAt)
                .filterIsInstance<ViewGroup>()
                .firstOrNull()
            if (menuView != null) {
                if (menuView.background != null) menuView.background = null
                if (menuView.foreground != null) menuView.foreground = null
                for (i in 0 until menuView.childCount) {
                    val item = menuView.getChildAt(i)
                    if (item.background != null) item.background = null
                    if (indicatorId != 0) {
                        item.findViewById<View>(indicatorId)?.let { indicator ->
                            if (indicator.alpha != 0f) indicator.alpha = 0f
                        }
                    }
                }
            }

            val container = navigation.parent as? ViewGroup ?: return
            val dividerId = res.getIdentifier("divider_line", "id", pkg)
            if (dividerId != 0) {
                container.findViewById<View>(dividerId)?.let { divider ->
                    if (divider.visibility != View.GONE) divider.visibility = View.GONE
                }
            }
            val fragmentId = res.getIdentifier("fragment_container", "id", pkg)
            if (fragmentId != 0) {
                container.findViewById<View>(fragmentId)?.let { fragmentContainer ->
                    expandFragmentFullBleed(fragmentContainer, navigation.id)
                }
            }
        }

        private fun expandFragmentFullBleed(fragmentContainer: View, navId: Int) {
            val lp = fragmentContainer.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            val lpClass = lp.javaClass
            val topField = runCatching { lpClass.getField("bottomToTop") }.getOrNull()
            val bottomField = runCatching { lpClass.getField("bottomToBottom") }.getOrNull()
            if (topField != null && bottomField != null) {
                val bottomToTop = runCatching { topField.getInt(lp) }.getOrNull() ?: return
                val bottomToBottom = runCatching { bottomField.getInt(lp) }.getOrNull() ?: return
                if (bottomToTop != -1 || bottomToBottom != 0 || lp.bottomMargin != 0) {
                    runCatching {
                        topField.setInt(lp, -1)
                        bottomField.setInt(lp, 0)
                        lp.bottomMargin = 0
                        fragmentContainer.layoutParams = lp
                    }
                }
                return
            }

            // R8 obfuscates ConstraintLayout$LayoutParams fields; match bottomToTop by nav_view id and fill height.
            val navRefFields = if (navId != 0) {
                lpClass.fields.filter { field ->
                    !java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                        field.type == Int::class.javaPrimitiveType &&
                        runCatching { field.getInt(lp) == navId }.getOrDefault(false)
                }
            } else {
                emptyList()
            }
            if (navRefFields.isNotEmpty() || lp.bottomMargin != 0 || lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                runCatching {
                    navRefFields.forEach { it.setInt(lp, -1) }
                    lp.bottomMargin = 0
                    lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                    fragmentContainer.layoutParams = lp
                }
            }
        }

        private fun themeBackgroundDark(view: View): Boolean? {
            val context = view.context
            val res = view.resources
            val attrIds = intArrayOf(
                res.getIdentifier("bottom_nav_tab_background", "attr", context.packageName),
                android.R.attr.colorBackground,
            )
            val tv = TypedValue()
            for (attrId in attrIds) {
                if (attrId == 0 || !context.theme.resolveAttribute(attrId, tv, true)) continue
                val color = when {
                    tv.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT -> tv.data
                    tv.resourceId != 0 -> runCatching { res.getColor(tv.resourceId, context.theme) }.getOrNull()
                    else -> null
                } ?: continue
                if (Color.alpha(color) < 128) continue
                return colorLuminanceDark(color)
            }
            return null
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
            return colorLuminanceDark(color)
        }

        private fun colorLuminanceDark(color: Int): Boolean {
            val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0
            return luminance < 0.5
        }
    }
}
