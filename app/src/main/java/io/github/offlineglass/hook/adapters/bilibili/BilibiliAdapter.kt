package io.github.offlineglass.hook.adapters.bilibili

import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.NavigationCandidate
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.TargetAdapter

/** Domestic Bilibili's app-owned bottom navigation policy and artwork. */
internal object BilibiliAdapter : TargetAdapter {
    override val key = "bilibili"
    override val homeOnlyNavigation: Boolean get() = io.github.offlineglass.BuildConfig.BETA_BUILD
    override fun installHooks(
        lpparam: io.github.offlineglass.hook.compat.callbacks.XC_LoadPackage.LoadPackageParam,
        spec: io.github.offlineglass.targets.TargetSpec,
    ) {
        // Optional comment/splash compatibility must never prevent home lifecycle hooks.
        if (io.github.offlineglass.BuildConfig.BETA_BUILD) return
        if (lpparam.processName == "tv.danmaku.bili:web") {
            BilibiliWebInsetsHooks.install()
            return
        }
        listOf<Pair<String, () -> Unit>>(
            "author space insets" to { BilibiliWebInsetsHooks.install(authorSpace = true) },
            "video ambient" to { BilibiliVideoAmbient.install(lpparam.classLoader) },
            "shop dormancy" to { BilibiliShopDormancy.install(lpparam.classLoader) },
            "splash" to { BilibiliSplashHooks.install(lpparam.classLoader) },
            "comment chrome" to { BilibiliCommentChromeHooks.install(lpparam.classLoader) },
            "detail insets" to { BilibiliDetailInsetsHooks.install(lpparam.classLoader) },
            "comment viewport" to { BilibiliCommentViewportHooks.install(lpparam.classLoader) },
            "story comments" to { BilibiliStoryCommentHooks.install(lpparam.classLoader) },
            "story video viewport" to { BilibiliStoryVideoViewport.install(lpparam.classLoader) },
            "playlist glass" to { BilibiliPlaylistGlassHooks.install() },
            "home layout" to { BilibiliHomeLayoutHooks.install(lpparam.classLoader) },
        ).forEach { (name, install) ->
            runCatching(install).onFailure { XposedBridge.log("LiquidTab Bilibili $name: $it") }
        }
    }
    override val ownsNavigationFinding = true
    override val recheckNavigationBindingOnLayout = true
    override val keepNavigationOverlayOnTop = true
    override fun acceptsNavigationSource(navigation: ViewGroup): Boolean = isHomeNavigation(navigation)
    override fun findNavigation(root: View, spec: io.github.offlineglass.targets.TargetSpec): NavigationCandidate? {
        val navigation = AdapterNavigationSearch.findFirst(root) {
            isHomeNavigation(it) && it.isAttachedToWindow && it.isShown && it.width > 0 && it.height > 0
        } ?: return null
        return NavigationCandidate(navigation, 5, 1_000)
    }
    private fun isHomeNavigation(view: ViewGroup): Boolean {
        var type: Class<*>? = view.javaClass
        while (type != null) {
            if (type.name == "com.bilibili.lib.homepage.widget.TabHost") return true
            if (type.name == "tv.danmaku.bili.home.components.bottomtab.WindowAwareComposeView" &&
                AdapterNavigationSearch.resourceEntryName(view) == "tab_host") return true
            type = type.superclass
        }
        return false
    }
    // The renderer draws each panel shadow; the host outline spans both panels.
    override val suppressPlatformElevation = true
    override val continuousIndicatorMask = true
    override val ownsNavigationTap = true
    override fun createNavigationState(context: android.content.Context): io.github.offlineglass.hook.adapters.AppNavigationState =
        BilibiliNavigationState()
    override val foregroundNavigationOverSelection = true
    override val usesNavigationOpticalMix = true
    override val persistentNavigationOpticalMix = true
    override fun barLayout(config: io.github.offlineglass.config.GlassConfig, width: Int,
        height: Int, density: Float, slots: Int): io.github.offlineglass.hook.adapters.AdapterBarLayout? {
        if (!config.detachedPublish || slots != 5 || config.hiddenMask and 4 != 0 ||
            height <= 0 || width <= height + 12f * density) return null
        val navigation = listOf(0, 1, 3, 4).filter { config.hiddenMask and (1 shl it) == 0 }
        if (navigation.isEmpty()) return null
        val actionLeft = width - height.toFloat()
        return io.github.offlineglass.hook.adapters.AdapterBarLayout(
            mainWidth = actionLeft - 12f * density, actionLeft = actionLeft,
            actionSize = height.toFloat(), actionIndex = PUBLISH_INDEX, navigationIndices = navigation,
            actionFillColor = accentColor,
        )
    }
    override val ownsNavigationDrawing = true
    override val usesContentDarkMode = true
    override val sceneVisibilityAnimation = true
    override val drawsNavigationInOptics = true
    override val accentColor: Int = 0xFFFB7299.toInt()
    override val sourceIconScale = 1.15f
    override val sourceTextTranslationDp = 4f
    override val sourceIconTranslationDp = 0f
    override val snapIndicatorToSelectionOnRebind = true
    override val refreshHostGeometryOnRebind = true
    override val usesDirectOpticalBackdropMode = true
    override val allowsSystemBackgroundBlur = false
    override val usesNativeOpticalBlur = false
    override val countsOpticalCompositorCopies = true
    override val keepsOpticalSurfaceDuringBarAnimation = true
    override val reuseOpticalBlur = true
    override val reuseSurfaceBlurDuringBarAnimation = true

    override fun opticalCaptureGeometry(
        rootWidth: Int, rootHeight: Int, left: Int, top: Int, width: Int, height: Int, padding: Int,
    ): io.github.offlineglass.hook.adapters.OpticalCaptureGeometry? {
        val rect = android.graphics.Rect(
            (left - padding).coerceAtLeast(0),
            (top - padding).coerceAtLeast(0),
            (left + width + padding).coerceAtMost(rootWidth),
            (top + height + padding).coerceAtMost(rootHeight),
        )
        if (rect.isEmpty) return null
        val targetWidth = (rect.width() / 2f).toInt().coerceAtLeast(1)
        val targetHeight = (rect.height() / 2f).toInt().coerceAtLeast(1)
        return io.github.offlineglass.hook.adapters.OpticalCaptureGeometry(
            rect, targetWidth, targetHeight, android.graphics.Rect(0, 0, targetWidth, targetHeight),
        )
    }

    override fun screenReferenceHeight(rootHeight: Int?, displayHeight: Int): Int =
        maxOf(rootHeight ?: 0, displayHeight)

    const val PUBLISH_INDEX = 2
    const val SHOP_INDEX = 3
    override fun drawNavigation(canvas: Canvas, frame: AdapterNavigationFrame) =
        BilibiliRenderer.draw(canvas, frame)
    override fun hookSignals() = io.github.offlineglass.hook.adapters.bilibili.hookSignals()
}
