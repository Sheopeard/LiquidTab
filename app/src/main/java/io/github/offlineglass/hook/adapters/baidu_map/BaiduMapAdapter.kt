package io.github.offlineglass.hook.adapters.baidu_map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
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

/** Baidu Map bottom bar adaptation with Meituan-style detached circular AI button. */
internal object BaiduMapAdapter : TargetAdapter {
    override val key = "baidu_map"
    const val AI_INDEX = 2

    override val ownsNavigationFinding = true
    override val recheckNavigationBindingOnLayout = true
    override val keepNavigationOverlayOnTop = true
    override val suppressPlatformElevation = true
    override val continuousIndicatorMask = true

    override val ownsNavigationDrawing = true
    override val skipNavigationSnapshot = true
    override val drawsNavigationInOptics = true
    override val foregroundNavigationOverSelection = true
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

    // BaiduMap renders its main map via BaiduMapSurfaceView and embeds WebViews in sub-tabs.
    override val usesOpticalSurfacePipeline = true
    override val allowsSystemBackgroundBlur = false
    override val keepsOpticalSurfaceDuringBarAnimation = true
    override val countsOpticalCompositorCopies = true
    override val reuseOpticalBlur = true
    override val reuseSurfaceBlurDuringBarAnimation = true
    override val deferOpticalRenderDuringBarAnimation = true

    override fun isOpticalSurfaceTab(selectedIndex: Int): Boolean = true
    override fun needsContinuousOpticalSurfaceFrames(selectedIndex: Int): Boolean = true

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

    override fun findNavigation(root: View, spec: TargetSpec): NavigationCandidate? {
        val ufoRoot = AdapterNavigationSearch.findFirst(root) { view ->
            AdapterNavigationSearch.resourceEntryName(view) == "ufo_root" &&
                view.isAttachedToWindow && view.isShown &&
                view.width > 0 && view.height > 0 &&
                findNewBtns(view)?.childCount == 5
        } ?: return null
        return NavigationCandidate(ufoRoot, 5, 1_000)
    }

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
        )
    }

    override fun suppressNativeBottomChrome(navigation: ViewGroup) {
        clearUfoChrome(navigation)
        navigation.post { clearUfoChrome(navigation) }
    }

    private fun clearUfoChrome(root: ViewGroup) {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            val id = AdapterNavigationSearch.resourceEntryName(child)
            if (id == "mid" || id == "preventTouchEventUfo" || id == "new_ai_home_top_shadow") {
                runCatching { child.background = ColorDrawable(Color.TRANSPARENT) }
                runCatching { child.alpha = 0f }
            }
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
