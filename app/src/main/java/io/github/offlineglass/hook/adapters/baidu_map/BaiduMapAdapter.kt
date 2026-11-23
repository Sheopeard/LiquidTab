package io.github.offlineglass.hook.adapters.baidu_map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.NavigationCandidate
import io.github.offlineglass.hook.adapters.AdapterBarLayout
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.OpticalCaptureGeometry
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.targets.TargetSpec
import java.util.WeakHashMap

/** Baidu Map bottom bar adaptation with Meituan-style detached circular AI button. */
internal object BaiduMapAdapter : TargetAdapter {
    override val key = "baidu_map"
    const val AI_INDEX = 2

    override val ownsNavigationFinding = true
    override val recheckNavigationBindingOnLayout = true
    override val keepNavigationOverlayOnTop = true
    override val ownsHostNativeChrome = true
    override val keepsBackdropDuringTapTransition = true
    override val suppressPlatformElevation = true
    override val continuousIndicatorMask = true

    override val ownsNavigationDrawing = true
    override val skipNavigationSnapshot = true
    override val drawsNavigationInOptics = true
    override val foregroundNavigationOverSelection = true
    override val foregroundUsesRenderedSelectionOutline = true
    override val foregroundRequiresSubmittedIndicatorArtwork = true
    override val foregroundMaskInsetDp = 0f
    override val usesNavigationOpticalMix = true
    override val persistentNavigationOpticalMix = true
    override val usesContentDarkMode = true
    override val ownsNavigationTap = true
    override val commitSelectionAfterTap = true
    override val sceneVisibilityAnimation = true
    override val hasTransientPageVisibility = true
    override val sourceSwapGraceMs = 800L
    override val accentColor: Int = 0xFF3385FF.toInt()

    // Tighter rim depth + balanced refraction keeps top-edge cards (e.g. 去公司/回家)
    // refracting through the pill and AI orb instead of being skipped into blank padding.
    override val outerRefractionHeightDp: Float = 10f
    override val outerRefractionScale: Float = 0.75f

    override fun glassSurfaceFillColor(dark: Boolean, selectedIndex: Int, defaultColor: Int): Int =
        if (dark) defaultColor
        else Color.argb(
            (Color.alpha(defaultColor) * 0.45f).toInt().coerceIn(0, 255),
            Color.red(defaultColor),
            Color.green(defaultColor),
            Color.blue(defaultColor),
        )

    // BaiduMap renders its main map via BaiduMapSurfaceView and embeds WebViews/Talos in sub-tabs.
    override val usesOpticalSurfacePipeline = true
    override val usesDirectOpticalBackdropMode = true
    override val allowsSystemBackgroundBlur = false
    override val keepsOpticalSurfaceDuringBarAnimation = true
    override val countsOpticalCompositorCopies = true
    override val reuseOpticalBlur = true
    override val reuseSurfaceBlurDuringBarAnimation = true
    override val deferOpticalRenderDuringBarAnimation = true
    override val observeWindowTouches = true

    override fun isOpticalSurfaceTab(selectedIndex: Int): Boolean = true
    override fun needsContinuousOpticalSurfaceFrames(selectedIndex: Int): Boolean = false

    override fun onWindowTouch(host: View, event: MotionEvent) {
        ((host as? GlassHostLayout)?.appNavigationState as? BaiduMapNavigationState)?.onWindowTouch(event)
    }

    override fun opticalCaptureGeometry(
        rootWidth: Int, rootHeight: Int, left: Int, top: Int, width: Int, height: Int, padding: Int,
    ): OpticalCaptureGeometry? {
        val rect = Rect(
            (left - padding).coerceAtLeast(0),
            (top - padding).coerceAtLeast(0),
            (left + width + padding).coerceAtMost(rootWidth),
            (top + height + padding).coerceAtMost(rootHeight),
        )
        if (rect.isEmpty) return null
        val targetWidth = (rect.width() / 2.5f).toInt().coerceAtLeast(1)
        val targetHeight = (rect.height() / 2.5f).toInt().coerceAtLeast(1)
        return OpticalCaptureGeometry(
            rect, targetWidth, targetHeight, Rect(0, 0, targetWidth, targetHeight),
        )
    }

    override fun acceptsNavigationSource(navigation: ViewGroup): Boolean =
        AdapterNavigationSearch.resourceEntryName(navigation) == "ufo_root" &&
            findNewBtns(navigation)?.childCount == 5

    internal data class ActiveUfoState(
        val ufo: ViewGroup?,
        val allowed: Boolean,
        val isOverlay: Boolean,
    )

    override fun findNavigation(root: View, spec: TargetSpec): NavigationCandidate? {
        val pkg = root.context.packageName
        val persistId = root.resources.getIdentifier("persist_container", "id", pkg)
        val persist = if (persistId != 0) root.findViewById<View>(persistId) else null
        val ufoRoot = (persist?.let(::findVisibleUfoInSubtree))
            ?: AdapterNavigationSearch.findFirst(root) { view ->
                isUsableUfoRoot(view)
            }
            ?: return null
        return NavigationCandidate(ufoRoot, 5, 1_000)
    }

    internal fun resolveActiveUfo(root: ViewGroup?, homeUfo: ViewGroup?): ActiveUfoState {
        if (root == null) {
            val homeOk = homeUfo == null || isUfoActive(homeUfo)
            return ActiveUfoState(if (homeOk) homeUfo else null, allowed = homeOk, isOverlay = false)
        }
        val pkg = root.context.packageName
        val res = root.resources
        val replaceId = res.getIdentifier("replace_container", "id", pkg)
        val luiId = res.getIdentifier("base_stack_lui", "id", pkg)
        val replaceContainer = (if (replaceId != 0) root.findViewById<View>(replaceId) else null) as? ViewGroup
        val luiContainer = (if (luiId != 0) root.findViewById<View>(luiId) else null) as? ViewGroup
        val replaceTop = topmostVisibleChild(replaceContainer)
        val luiTop = topmostVisibleChild(luiContainer)

        if (replaceTop != null) {
            val overlayUfo = findVisibleUfoInSubtree(replaceTop)
                ?: return ActiveUfoState(null, allowed = false, isOverlay = true)
            if (luiTop != null && findVisibleUfoInSubtree(luiTop) == null) {
                return ActiveUfoState(null, allowed = false, isOverlay = true)
            }
            clearUfoChrome(overlayUfo, expandHome = false)
            return ActiveUfoState(overlayUfo, allowed = true, isOverlay = true)
        }
        if (luiTop != null) {
            val overlayUfo = findVisibleUfoInSubtree(luiTop)
                ?: return ActiveUfoState(null, allowed = false, isOverlay = true)
            clearUfoChrome(overlayUfo, expandHome = false)
            return ActiveUfoState(overlayUfo, allowed = true, isOverlay = true)
        }
        if (homeUfo != null && !isUfoActive(homeUfo)) {
            return ActiveUfoState(null, allowed = false, isOverlay = false)
        }
        return ActiveUfoState(homeUfo, allowed = true, isOverlay = false)
    }

    private fun topmostVisibleChild(container: ViewGroup?): View? {
        container ?: return null
        for (i in container.childCount - 1 downTo 0) {
            val child = container.getChildAt(i)
            if (child.isShown && child.alpha > 0.01f && child.width > 0 && child.height > 0) {
                return child
            }
        }
        return null
    }

    private fun findVisibleUfoInSubtree(root: View): ViewGroup? {
        if (root is ViewGroup && isUsableUfoRoot(root)) return root
        val group = root as? ViewGroup ?: return null
        for (i in group.childCount - 1 downTo 0) {
            findVisibleUfoInSubtree(group.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun isUfoActive(view: View): Boolean =
        view.isAttachedToWindow && view.isShown && view.height > 0 && view.translationY <= 24f

    private fun isUsableUfoRoot(view: View): Boolean =
        AdapterNavigationSearch.resourceEntryName(view) == "ufo_root" &&
            isUfoActive(view) && view.width > 0 &&
            (view as? ViewGroup)?.let(::findNewBtns)?.childCount == 5

    override fun barLayout(
        config: GlassConfig, width: Int, height: Int, density: Float, slots: Int,
    ): AdapterBarLayout? {
        if (!config.detachedPublish || slots != 5 || config.hiddenMask and (1 shl AI_INDEX) != 0 ||
            height <= 0 || width <= height + 12f * density
        ) return null
        val navigation = listOf(0, 1, 3, 4).filter { config.hiddenMask and (1 shl it) == 0 }
        if (navigation.isEmpty()) return null
        val actionLeft = width - height.toFloat()
        return AdapterBarLayout(
            mainWidth = actionLeft - 12f * density,
            actionLeft = actionLeft,
            actionSize = height.toFloat(),
            actionIndex = AI_INDEX,
            navigationIndices = navigation,
            actionFillColor = null,
            localActionOptics = true,
            localMainOptics = true,
        )
    }

    override fun suppressNativeBottomChrome(navigation: ViewGroup) {
        clearUfoChrome(navigation)
        navigation.post { clearUfoChrome(navigation) }
    }

    internal fun clearUfoChrome(root: ViewGroup, expandHome: Boolean = true) {
        if (root.alpha != 0f) root.alpha = 0f
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            val id = AdapterNavigationSearch.resourceEntryName(child)
            if (id == "mid" || id == "preventTouchEventUfo" || id == "new_ai_home_top_shadow") {
                runCatching { child.background = ColorDrawable(Color.TRANSPARENT) }
                runCatching { child.alpha = 0f }
            }
        }
        if (expandHome) expandTabPagesFullBleed(root, isNonHomeSelected = false)
    }

    internal fun expandTabPagesFullBleed(
        navigation: ViewGroup,
        isNonHomeSelected: Boolean,
        savedMargins: WeakHashMap<View, Int>? = null,
        savedPaddings: WeakHashMap<View, Int>? = null,
    ) {
        findTabPageContainer(navigation)?.let { tabContainer ->
            if (isNonHomeSelected && tabContainer.paddingBottom > 0) {
                savedPaddings?.putIfAbsent(tabContainer, tabContainer.paddingBottom)
                tabContainer.setPadding(
                    tabContainer.paddingLeft,
                    tabContainer.paddingTop,
                    tabContainer.paddingRight,
                    0,
                )
            }
            for (i in 0 until tabContainer.childCount) {
                val child = tabContainer.getChildAt(i)
                clearBottomInset(child, savedMargins, savedPaddings)
                findSurroundMap(child)?.let { surroundMap ->
                    clearBottomInset(surroundMap, savedMargins, savedPaddings)
                }
            }
        }
        findHomePanelContainer(navigation)?.let { homePanelContainer ->
            for (i in 0 until homePanelContainer.childCount) {
                val child = homePanelContainer.getChildAt(i)
                val lp = child.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
                if (lp.height == ViewGroup.LayoutParams.MATCH_PARENT && lp.bottomMargin > 0) {
                    savedMargins?.putIfAbsent(child, lp.bottomMargin)
                    lp.bottomMargin = 0
                    child.layoutParams = lp
                }
            }
        }
    }

    private fun findTabPageContainer(navigation: ViewGroup): ViewGroup? {
        val pkg = navigation.context.packageName
        val res = navigation.resources
        val targetId = res.getIdentifier("aihome_tab_page_container", "id", pkg)
        val ufoContainer = navigation.parent as? ViewGroup
        val persistContainer = ufoContainer?.parent as? ViewGroup
        if (persistContainer != null) {
            if (targetId != 0) {
                (persistContainer.findViewById<View>(targetId) as? ViewGroup)?.let { return it }
            }
            for (i in 0 until persistContainer.childCount) {
                val child = persistContainer.getChildAt(i) as? ViewGroup ?: continue
                if (AdapterNavigationSearch.resourceEntryName(child) == "aihome_tab_page_container") {
                    return child
                }
            }
        }
        val root = navigation.rootView ?: return null
        return if (targetId != 0) root.findViewById<View>(targetId) as? ViewGroup else null
    }

    private fun findHomePanelContainer(navigation: ViewGroup): ViewGroup? {
        val pkg = navigation.context.packageName
        val res = navigation.resources
        val targetId = res.getIdentifier("home_panel_container", "id", pkg)
        val root = navigation.rootView ?: return null
        return if (targetId != 0) root.findViewById<View>(targetId) as? ViewGroup else null
    }

    private fun findSurroundMap(root: View): View? {
        if (AdapterNavigationSearch.resourceEntryName(root) == "surround_map" ||
            root.javaClass.simpleName == "SurroundMapLayout"
        ) return root
        val group = root as? ViewGroup ?: return null
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (AdapterNavigationSearch.resourceEntryName(child) == "surround_map" ||
                child.javaClass.simpleName == "SurroundMapLayout"
            ) return child
        }
        return null
    }

    private fun clearBottomInset(
        view: View,
        savedMargins: WeakHashMap<View, Int>?,
        savedPaddings: WeakHashMap<View, Int>?,
    ) {
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams
        if (lp != null && lp.bottomMargin != 0) {
            savedMargins?.putIfAbsent(view, lp.bottomMargin)
            lp.bottomMargin = 0
            view.layoutParams = lp
        }
        if (view.paddingBottom != 0) {
            savedPaddings?.putIfAbsent(view, view.paddingBottom)
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, 0)
        }
    }

    override fun createNavigationState(context: Context): AppNavigationState = BaiduMapNavigationState()
    override fun createNavigationState(context: Context, host: GlassHostLayout): AppNavigationState =
        BaiduMapNavigationState(host)

    override fun drawNavigation(canvas: Canvas, frame: AdapterNavigationFrame) = Unit

    internal fun findNewBtns(ufoRoot: ViewGroup): ViewGroup? {
        val directId = ufoRoot.resources.getIdentifier("new_btns", "id", ufoRoot.context.packageName)
        if (directId != 0) (ufoRoot.findViewById<View>(directId) as? ViewGroup)?.let { return it }
        for (i in 0 until ufoRoot.childCount) {
            val child = ufoRoot.getChildAt(i) as? ViewGroup ?: continue
            if (AdapterNavigationSearch.resourceEntryName(child) == "new_btns") return child
        }
        return null
    }
}
