package io.github.offlineglass.hook.adapters.share

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.NavigationCandidate
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.targets.TargetSpec

/** Owns Share's CustomBottomBar discovery, FAB margin lifting, drawer guard, and leaf projection. */
internal object ShareAdapter : TargetAdapter {
    override val key = "share"
    override val ownsNavigationFinding = true
    override val ownsNavigationDrawing = true
    override val ownsNavigationTap = true
    override val nativeSelectionReliable = true
    override val skipNavigationSnapshot = true
    override val managesNativeTabVisibility = true
    override val usesContentDarkMode = true

    override fun createNavigationState(context: Context, host: GlassHostLayout): AppNavigationState =
        ShareNavigationState(host)

    override fun findNavigation(root: View, spec: TargetSpec): NavigationCandidate? {
        val bar = AdapterNavigationSearch.findFirst(root) { view ->
            view is ViewGroup && isCustomBottomBar(view) && view.isShown && view.width > 0 && view.height > 0
        } as? ViewGroup ?: return null
        val count = visibleTabs(bar).size
        return if (count in spec.preferredSlots) NavigationCandidate(bar, count, 160) else null
    }

    override fun acceptsNavigationSource(navigation: ViewGroup): Boolean =
        isCustomBottomBar(navigation) && visibleTabs(navigation).size in 2..5

    override fun estimateSlotCount(group: ViewGroup, spec: TargetSpec): Int? {
        if (!isCustomBottomBar(group)) return null
        return visibleTabs(group).size.takeIf { it in spec.preferredSlots }
    }

    override fun beforeNavigationScan(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        val bar = AdapterNavigationSearch.findFirst(decor) { view ->
            view is ViewGroup && isCustomBottomBar(view)
        } as? ViewGroup ?: return
        ShareNavigationState.suppressChrome(bar)
    }

    override fun suppressNativeBottomChrome(navigation: ViewGroup) {
        if (isCustomBottomBar(navigation)) ShareNavigationState.suppressChrome(navigation)
    }

    internal fun isCustomBottomBar(view: View): Boolean {
        var type: Class<*>? = view.javaClass
        while (type != null) {
            val name = type.name
            if (name == CUSTOM_BOTTOM_BAR_CLASS || name == ROUGHIKE_BOTTOM_BAR_CLASS) return true
            type = type.superclass
        }
        return false
    }

    internal fun findTabRow(bar: ViewGroup?): LinearLayout? {
        bar ?: return null
        val queue = ArrayDeque<Pair<View, Int>>()
        queue += bar to 0
        while (queue.isNotEmpty()) {
            val (view, depth) = queue.removeFirst()
            if (view !== bar && view is LinearLayout && view.orientation == LinearLayout.HORIZONTAL &&
                view.childCount in 2..5 && (0 until view.childCount).all { view.getChildAt(it) is ViewGroup }) {
                return view
            }
            if (view is ViewGroup && depth < 3) {
                for (i in 0 until view.childCount) queue += view.getChildAt(i) to (depth + 1)
            }
        }
        return null
    }

    internal fun visibleTabs(bar: ViewGroup?): List<View> {
        val row = findTabRow(bar) ?: return emptyList()
        val indexed = ArrayList<Pair<Int, View>>(row.childCount)
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            if (child.visibility == View.VISIBLE) indexed += i to child
        }
        return indexed.sortedWith(compareBy({ it.second.left }, { it.first })).map { it.second }
    }

    private const val CUSTOM_BOTTOM_BAR_CLASS = "com.hengye.share.ui.widget.third.CustomBottomBar"
    private const val ROUGHIKE_BOTTOM_BAR_CLASS = "com.roughike.bottombar.BottomBar"
}
