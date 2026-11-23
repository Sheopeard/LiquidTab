package io.github.offlineglass.hook.adapters.netease_mail

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.NavigationCandidate
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.OpticalCaptureGeometry
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.targets.TargetSpec

/** NetEase Mail Master bottom bar adaptation with edge-to-edge layout and hybrid WebView backdrop. */
internal object NeteaseMailAdapter : TargetAdapter {
    override val key = "netease_mail"

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
    override val sourceSwapGraceMs = 0L
    override val accentColor: Int = 0xFFDF362F.toInt()

    // Unified GPU Surface compositor pipeline across all 5 tabs eliminates flash between
    // native tabs (邮件/待办/我) and WebView tabs (文件) and supplies a real backdrop on all tabs.
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
        ((host as? GlassHostLayout)?.appNavigationState as? NeteaseMailNavigationState)?.onWindowTouch(event)
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
        val targetWidth = (rect.width() / 2f).toInt().coerceAtLeast(1)
        val targetHeight = (rect.height() / 2f).toInt().coerceAtLeast(1)
        return OpticalCaptureGeometry(
            rect, targetWidth, targetHeight, Rect(0, 0, targetWidth, targetHeight),
        )
    }

    override fun acceptsNavigationSource(navigation: ViewGroup): Boolean =
        AdapterNavigationSearch.resourceEntryName(navigation) == "tab_layout" &&
            navigation.childCount == 5 &&
            (0 until navigation.childCount).all {
                navigation.getChildAt(it).javaClass.simpleName.endsWith("TabItemView")
            }

    override fun findNavigation(root: View, spec: TargetSpec): NavigationCandidate? {
        val tabLayout = AdapterNavigationSearch.findFirst(root) { view ->
            acceptsNavigationSource(view) && view.isAttachedToWindow && view.isShown &&
                view.width > 0 && view.height > 0
        } ?: return null
        return NavigationCandidate(tabLayout, 5, 1_000)
    }

    override fun suppressNativeBottomChrome(navigation: ViewGroup) {
        clearTabChrome(navigation)
        navigation.post { clearTabChrome(navigation) }
    }

    private fun clearTabChrome(navigation: ViewGroup) {
        runCatching { navigation.background = ColorDrawable(Color.TRANSPARENT) }
        if (navigation.alpha != 0f) navigation.alpha = 0f
        navigation.elevation = 0f
        expandTabContentFullBleed(navigation)
    }

    internal fun expandTabContentFullBleed(navigation: ViewGroup) {
        val container = navigation.parent as? ViewGroup ?: return
        val pkg = navigation.context.packageName
        val res = navigation.resources
        val fragmentId = res.getIdentifier("tab_fragment_container", "id", pkg)
        val fragmentContainer = (if (fragmentId != 0) container.findViewById<View>(fragmentId) else null)
            ?: (0 until container.childCount).map { container.getChildAt(it) }.firstOrNull {
                AdapterNavigationSearch.resourceEntryName(it) == "tab_fragment_container"
            } ?: return
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

        val navId = navigation.id
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

    override fun createNavigationState(context: Context): AppNavigationState = NeteaseMailNavigationState()
    override fun createNavigationState(context: Context, host: GlassHostLayout): AppNavigationState =
        NeteaseMailNavigationState(host)

    override fun drawNavigation(canvas: Canvas, frame: AdapterNavigationFrame) = Unit

    override fun hookSignals() = io.github.offlineglass.hook.adapters.netease_mail.hookSignals()
}
