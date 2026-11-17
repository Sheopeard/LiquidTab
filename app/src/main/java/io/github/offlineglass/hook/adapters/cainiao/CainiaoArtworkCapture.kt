package io.github.offlineglass.hook.adapters.cainiao

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.offlineglass.hook.adapters.NavigationLabelLocalization

/** Draw leaves directly: hidden native ancestors and their clipping cannot erase artwork. */
internal object CainiaoArtworkCapture {
    private const val ENLARGEMENT = 1.2f
    private val labelIds = setOf("home_page_nav_tv_name", "tv_name")
    // Home's selected artwork is a 52dp two-state sprite clipped by a 25dp
    // container with a separate blue background, or a 38dp rocket DraweeView
    // inside tabbar_container_biz (while home_page_nav_tv_name is hidden).
    private val iconIds = listOf(
        "home_page_nav_nav_tab_icon_biz",
        "tabbar_container_biz",
        "tabbar_container",
        "nav_tab_icon_static",
        "home_page_nav_nav_tab_icon_animation_v81012",
        "home_page_nav_nav_tab_icon_animation",
        "home_page_nav_nav_tab_icon",
        "nav_tab_icon",
    )
    private val badgeIds = setOf("number_red_dots_textView", "redpoint_imageView")

    internal data class Tab(
        val slot: ViewGroup,
        val icon: View,
        val label: TextView?,
        val semanticName: String,
        val badges: List<View>,
    )

    private fun descendants(root: View, includeInvisible: Boolean = false): List<View> {
        val result = ArrayList<View>()
        fun visit(view: View) {
            if (!includeInvisible && view.visibility != View.VISIBLE) return
            result += view
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(root)
        return result
    }

    /** Visible bottom tabs sorted left-to-right (tolerates FuckCainiao hiding tabs and Home hiding its label). */
    fun findVisibleTabs(source: ViewGroup): List<Tab> {
        val tabRow = (source.findNamedView("ll_navigation_tab_layout") as? ViewGroup) ?: source
        val directSlots = (0 until tabRow.childCount)
            .mapNotNull { tabRow.getChildAt(it) as? ViewGroup }
            .filter { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }
        val tabs = ArrayList<Tab>(directSlots.size)
        for (slot in directSlots) {
            val visible = descendants(slot)
            val icon = iconIds.firstNotNullOfOrNull { id ->
                visible.firstOrNull { it.resourceName() == id && it.width > 0 && it.height > 0 }
            } ?: continue
            val visibleLabel = visible.filterIsInstance<TextView>()
                .firstOrNull { it.resourceName() in labelIds && it.width > 0 && it.height > 0 && it.text.isNotBlank() }
            val semanticName = visibleLabel?.text?.toString()
                ?: descendants(slot, includeInvisible = true)
                    .filterIsInstance<TextView>()
                    .firstOrNull { it.resourceName() in labelIds && !it.text.isNullOrBlank() }
                    ?.text?.toString()
                ?: ""
            tabs += Tab(slot, icon, visibleLabel, semanticName, visible.filter { it.resourceName() in badgeIds })
        }
        if (tabs.size !in 2..7) return emptyList()
        val sorted = tabs.sortedBy { it.slot.screenPosition()[0] }
        CainiaoAdapter.updateVisibleLabels(sorted.map { it.semanticName })
        return sorted
    }

    /** Rejects snapshots mid 3D-flip or before Fresco/icon drawables finish populating. */
    fun needsCaptureSettling(tabs: List<Tab>): Boolean {
        for (tab in tabs) {
            if (isViewUnstable(tab.slot) || isViewUnstable(tab.icon) || (tab.label != null && isViewUnstable(tab.label))) {
                return true
            }
            val visibleChildren = descendants(tab.icon)
            if (visibleChildren.any(::isViewUnstable)) return true
            if (tab.icon is android.widget.ImageView && tab.icon.drawable == null) return true
            val imageLeaves = visibleChildren.filterIsInstance<android.widget.ImageView>()
            if (imageLeaves.isNotEmpty() && imageLeaves.none { it.drawable != null }) return true
        }
        return false
    }

    private fun isViewUnstable(view: View): Boolean =
        view.alpha < 0.85f ||
            view.scaleX < 0.85f ||
            view.scaleY < 0.85f ||
            kotlin.math.abs(view.rotationY) > 5f ||
            kotlin.math.abs(view.rotationX) > 5f

    private fun sliceHasVisiblePixels(bitmap: Bitmap, leftPx: Int, rightPx: Int): Boolean {
        val x0 = leftPx.coerceIn(0, bitmap.width)
        val x1 = rightPx.coerceIn(x0, bitmap.width)
        if (x1 <= x0 || bitmap.height <= 0) return false
        val row = IntArray(x1 - x0)
        val stepY = (bitmap.height / 24).coerceAtLeast(1)
        var y = 0
        while (y < bitmap.height) {
            bitmap.getPixels(row, 0, x1 - x0, x0, y, x1 - x0, 1)
            for (pixel in row) if ((pixel ushr 24) > 12) return true
            y += stepY
        }
        return false
    }

    fun capture(source: ViewGroup, target: Bitmap, managerEnglishLabels: Boolean = false): Boolean {
        val tabs = findVisibleTabs(source)
        if (tabs.size !in 2..7 || needsCaptureSettling(tabs)) return false
        val canvas = Canvas(target)
        canvas.drawColor(android.graphics.Color.TRANSPARENT, BlendMode.CLEAR)
        val slotWidth = target.width / tabs.size.toFloat()
        val origin = source.screenPosition()
        val sourceTop = origin[1]
        tabs.forEachIndexed { index, tab ->
            val icon = tab.icon
            val label = tab.label
            val oldIcon = icon.screenPosition()
            val iconScale = if (label == null) {
                val maxFit = (target.height * 0.72f) / (icon.height * icon.scaleY).coerceAtLeast(1f)
                maxFit.coerceIn(0.85f, ENLARGEMENT)
            } else {
                ENLARGEMENT
            }
            val baseIconHeight = icon.height * icon.scaleY
            val baseLabelHeight = if (label != null) label.height * label.scaleY else 0f
            val oldLabel = label?.screenPosition()
            val gap = if (label != null && oldLabel != null) {
                (oldLabel[1] - oldIcon[1] - baseIconHeight).coerceAtLeast(0f) * iconScale
            } else 0f
            val iconHeight = baseIconHeight * iconScale
            val labelHeight = baseLabelHeight * iconScale
            val center = (index + 0.5f) * slotWidth
            val top = (target.height - iconHeight - gap - labelHeight) / 2f
            canvas.save()
            val clipLeft = (index * slotWidth).toInt()
            val clipRight = ((index + 1) * slotWidth).toInt()
            canvas.clipRect(index * slotWidth, 0f, (index + 1) * slotWidth, target.height.toFloat())
            drawLeaf(canvas, icon, center, top, iconScale)
            if (label != null && label.width > 0 && label.height > 0 && label.text.isNotEmpty()) {
                drawLabel(canvas, label, center, top + iconHeight + gap, iconScale,
                    if (managerEnglishLabels) NavigationLabelLocalization.toEnglish("cainiao", label.text.toString())
                    else label.text.toString())
            }
            // Badge size and colour stay native; anchor follows the enlarged icon's top/right.
            val newIconRight = center + icon.width * icon.scaleX * iconScale / 2f
            val dx = newIconRight - (oldIcon[0] - origin[0] + icon.width * icon.scaleX)
            val dy = top - (oldIcon[1] - sourceTop)
            tab.badges.forEach { badge ->
                val pos = badge.screenPosition()
                drawLeaf(canvas, badge, pos[0] - origin[0] + dx + badge.width * badge.scaleX / 2f,
                    pos[1] - sourceTop + dy, 1f)
            }
            canvas.restore()
            if (!sliceHasVisiblePixels(target, clipLeft, clipRight)) return false
        }
        return true
    }

    private fun View.screenPosition(): IntArray = IntArray(2).also(::getLocationOnScreen)

    private fun drawLeaf(canvas: Canvas, view: View, centerX: Float, top: Float, scale: Float) {
        if (view.alpha <= 0f || view.width <= 0 || view.height <= 0) return
        canvas.save()
        canvas.translate(centerX - view.width * view.scaleX * scale / 2f, top)
        canvas.scale(view.scaleX * scale, view.scaleY * scale)
        val layer = canvas.saveLayerAlpha(0f, 0f, view.width.toFloat(), view.height.toFloat(),
            (view.alpha * 255).toInt().coerceIn(0, 255))
        view.draw(canvas)
        canvas.restoreToCount(layer)
        canvas.restore()
    }

    /** Draw only the displayed text variant; the native TextView and its click/accessibility owner stay untouched. */
    private fun drawLabel(canvas: Canvas, view: TextView, centerX: Float, top: Float, scale: Float, text: String) {
        if (view.alpha <= 0f || view.width <= 0 || view.height <= 0 || text.isEmpty()) return
        val paint = android.text.TextPaint(view.paint).apply {
            color = view.currentTextColor
            textAlign = android.graphics.Paint.Align.CENTER
        }
        canvas.save()
        canvas.translate(centerX - view.width * view.scaleX * scale / 2f, top)
        canvas.scale(view.scaleX * scale, view.scaleY * scale)
        val layer = canvas.saveLayerAlpha(0f, 0f, view.width.toFloat(), view.height.toFloat(),
            (view.alpha * 255).toInt().coerceIn(0, 255))
        canvas.drawText(text, view.width / 2f, view.baseline.toFloat(), paint)
        canvas.restoreToCount(layer)
        canvas.restore()
    }
}
