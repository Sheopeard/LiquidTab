package io.github.offlineglass.hook.adapters.eudic

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.NavigationCandidate
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.targets.TargetSpec

/** Owns Eudic's Material BottomNavigationView discovery, full-bleed expansion, and leaf projection. */
internal object EudicAdapter : TargetAdapter {
    override val key = "eudic"
    override val ownsNavigationFinding = true
    override val ownsNavigationDrawing = true
    override val ownsNavigationTap = true
    override val nativeSelectionReliable = true
    override val skipNavigationSnapshot = true
    override val managesNativeTabVisibility = true
    override val usesContentDarkMode = true

    override fun createNavigationState(context: Context, host: GlassHostLayout): AppNavigationState =
        EudicNavigationState(host)

    override fun findNavigation(root: View, spec: TargetSpec): NavigationCandidate? {
        val nav = AdapterNavigationSearch.findFirst(root) { view ->
            view is ViewGroup && isBottomNavView(view) && view.isShown && view.width > 0 && view.height > 0
        } as? ViewGroup ?: return null
        val count = visibleTabViews(nav).size
        return if (count in spec.preferredSlots) NavigationCandidate(nav, count, 160) else null
    }

    override fun acceptsNavigationSource(navigation: ViewGroup): Boolean =
        isBottomNavView(navigation) && visibleTabViews(navigation).size in 2..6

    override fun estimateSlotCount(group: ViewGroup, spec: TargetSpec): Int? {
        if (!isBottomNavView(group)) return null
        return visibleTabViews(group).size.takeIf { it in spec.preferredSlots }
    }

    override fun beforeNavigationScan(activity: Activity) {
        val id = activity.resources.getIdentifier("nav_view", "id", activity.packageName)
        if (id == 0) return
        val nav = activity.findViewById<View>(id) as? ViewGroup ?: return
        if (isBottomNavView(nav)) EudicNavigationState.suppressChrome(nav)
    }

    override fun suppressNativeBottomChrome(navigation: ViewGroup) {
        if (isBottomNavView(navigation)) EudicNavigationState.suppressChrome(navigation)
    }

    internal fun isBottomNavView(view: View): Boolean {
        var type: Class<*>? = view.javaClass
        while (type != null) {
            val name = type.name
            if (name == BOTTOM_NAV_CLASS || name == NAVIGATION_BAR_CLASS) return true
            type = type.superclass
        }
        return false
    }

    internal fun visibleTabViews(navigation: ViewGroup?): List<View> {
        navigation ?: return emptyList()
        val menuView = (0 until navigation.childCount).asSequence()
            .map(navigation::getChildAt)
            .filterIsInstance<ViewGroup>()
            .firstOrNull()
            ?: return emptyList()
        val indexed = ArrayList<Pair<Int, View>>(menuView.childCount)
        for (i in 0 until menuView.childCount) {
            val child = menuView.getChildAt(i)
            if (child.visibility == View.VISIBLE) indexed += i to child
        }
        return indexed.sortedWith(compareBy({ it.second.left }, { it.first })).map { it.second }
    }

    private const val BOTTOM_NAV_CLASS = "com.google.android.material.bottomnavigation.BottomNavigationView"
    private const val NAVIGATION_BAR_CLASS = "com.google.android.material.navigation.NavigationBarView"
}
