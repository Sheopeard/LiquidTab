package io.github.offlineglass.hook.adapters.bilibili

import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.adapters.AppNavigationState
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

/** Native navigation owns page changes; glass only owns the gesture. */
internal class BilibiliNavigationState : AppNavigationState {
    override val persistentRestingIndicatorFill = true
    override val retainHiddenSourceFillWhileExpanded = true
    private var getterClass: Class<*>? = null
    private var selectionGetter: java.lang.reflect.Method? = null
    private var cellApiClass: Class<*>? = null
    private var cellCountGetter: java.lang.reflect.Method? = null
    private var cellGetter: java.lang.reflect.Method? = null
    private var transitionUntil = 0L
    private var selectionReadAt = 0L
    private var cachedSelection: Int? = null
    private var lastDispatchedSelection = -1
    private var gestureActive = false
    private var pendingSelection: Int? = null
    private var selectionGuardUntil = 0L
    private var activeSource: WeakReference<ViewGroup>? = null
    private var activeHost: io.github.offlineglass.hook.GlassHostLayout? = null
    private var hasCompositorContent = false
    private var lastOpticalSurfaceMode: Boolean? = null
    private var opticalFrameReady = false
    private var lastUiBarAnimating = false
    private var lastCompositorProbe = 0L
    private var compositorProbeDirty = true
    private var compositorObserver: android.view.ViewTreeObserver? = null
    private val probeRootRect = android.graphics.Rect()
    private val probeChildRect = android.graphics.Rect()
    private val compositorClassCache = java.util.WeakHashMap<Class<*>, Boolean>()
    private val compositorDirtyListener = android.view.ViewTreeObserver.OnScrollChangedListener {
        compositorProbeDirty = true
    }
    private val compositorLayoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
        compositorProbeDirty = true
    }

    override fun onHostFrame(host: View) {
        val glass = host as? io.github.offlineglass.hook.GlassHostLayout ?: return
        activeHost = glass
        BilibiliShopDormancy.bind(glass)
        updateCompositorBackdropGuard(glass)
        updateBackdropRendererMode(glass)
    }

    override val allowsSoftwareBackdropCapture: Boolean
        get() = !hasCompositorContent

    override fun skipLiveBackdrop(selectedIndex: Int): Boolean {
        activeHost?.let { updateCompositorBackdropGuard(it) }
        return hasCompositorContent
    }

    override fun requiresSampledBackdrop(): Boolean = activeHost?.config?.let {
        it.enabled && it.backdropCapture && !it.solidBarEnabled &&
            (it.liquidGlassEnabled || it.blurRadius > 0f)
    } == true

    override fun allowsOpticalSurfacePipeline(): Boolean =
        hasCompositorContent && requiresSampledBackdrop()

    override fun maintainsCompositorSamplingWithoutSurface(): Boolean =
        allowsOpticalSurfacePipeline()

    override fun opticalSurfaceVisibleDuringTransition(hostVisibility: Int, hostAlpha: Float): Boolean =
        hostVisibility == View.VISIBLE && hostAlpha > 0.001f

    override fun onOpticalSurfacePipelineEnabled(wasActive: Boolean) {
        if (!wasActive) opticalFrameReady = false
    }

    override fun onOpticalSurfaceFrameInvalidated() {
        opticalFrameReady = false
    }

    override fun onOpticalSurfaceFrameRendered(): Boolean {
        if (opticalFrameReady) return false
        opticalFrameReady = true
        return true
    }

    override fun shouldQueueOpticalRenderFromUi(animating: Boolean): Boolean {
        val queue = animating || lastUiBarAnimating
        lastUiBarAnimating = animating
        return queue
    }

    override fun onOpticalRendererConfigurationChanged() {
        lastOpticalSurfaceMode = null
        opticalFrameReady = false
        lastUiBarAnimating = false
    }

    override fun holdDirectBackdropUntilOpticalFrame(
        liveBackdropActive: Boolean, hasDisplayList: Boolean,
    ): Boolean {
        val host = activeHost ?: return true
        val surface = host.opticalSurfaceViewForAdapter
        return !hasCompositorContent || !opticalFrameReady ||
            !host.opticalSurfaceRunningForAdapter || surface?.visibility != View.VISIBLE || !surface.isShown
    }

    override fun enterDirectOpticalBackdrop(
        surface: android.view.SurfaceView, pipelineActive: Boolean,
    ): Boolean {
        if (allowsOpticalSurfacePipeline()) return false
        if (!pipelineActive && surface.visibility == View.GONE) return false
        surface.visibility = View.GONE
        opticalFrameReady = false
        return true
    }

    override fun updateBackdropRendererMode(host: io.github.offlineglass.hook.GlassHostLayout): Boolean {
        val enabled = allowsOpticalSurfacePipeline()
        if (lastOpticalSurfaceMode != enabled) {
            lastOpticalSurfaceMode = enabled
            opticalFrameReady = false
            host.liveBackdropNodeForAdapter.setRenderEffect(null)
            host.liveBackdropNodeForAdapter.discardDisplayList()
            host.clearLiveBackdropForAdapter()
            host.disableOpticalSurfaceNativeBlurForAdapter()
            if (enabled) host.enableOpticalSurfaceRendererForAdapter()
            else host.disableOpticalSurfaceRendererForAdapter()
            host.invalidate()
        }
        return true
    }

    private fun updateCompositorBackdropGuard(host: io.github.offlineglass.hook.GlassHostLayout) {
        val observer = host.viewTreeObserver
        if (observer !== compositorObserver) {
            releaseCompositorObserver()
            compositorObserver = observer
            observer.addOnScrollChangedListener(compositorDirtyListener)
            observer.addOnGlobalLayoutListener(compositorLayoutListener)
            compositorProbeDirty = true
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (!compositorProbeDirty && now - lastCompositorProbe < 160L) return
        lastCompositorProbe = now
        compositorProbeDirty = false
        val root = (host.parent as? View) ?: host.rootView
        val excludeSurface = host.opticalSurfaceViewForAdapter
        val compositorVisible = if (root.getGlobalVisibleRect(probeRootRect)) {
            containsVisibleCompositorSurface(root, host, excludeSurface, probeRootRect)
        } else {
            false
        }
        if (compositorVisible == hasCompositorContent) return
        hasCompositorContent = compositorVisible
        host.invalidateOpticalSceneForAdapter()
        if (compositorVisible) host.clearLiveBackdropForAdapter()
        host.invalidateProjectionCache()
        updateBackdropRendererMode(host)
        host.invalidate()
    }

    private fun releaseCompositorObserver() {
        compositorObserver?.takeIf { it.isAlive }?.let {
            it.removeOnScrollChangedListener(compositorDirtyListener)
            it.removeOnGlobalLayoutListener(compositorLayoutListener)
        }
        compositorObserver = null
    }

    private fun isCompositorView(view: View): Boolean {
        compositorClassCache[view.javaClass]?.let { return it }
        val result = view is android.webkit.WebView || view is android.view.SurfaceView ||
            isCompositorClassHierarchy(view.javaClass)
        compositorClassCache[view.javaClass] = result
        return result
    }

    private fun isCompositorClassHierarchy(type: Class<*>): Boolean {
        var current: Class<*>? = type
        while (current != null && current !== View::class.java && current !== Any::class.java) {
            val name = current.name.lowercase()
            if (name.contains("webview") || name.contains("chromium") ||
                name.contains("meco") || name.contains("x5")
            ) {
                return true
            }
            current = current.superclass
        }
        return false
    }

    private fun containsVisibleCompositorSurface(
        view: View,
        excludeOverlay: View,
        excludeSurface: View?,
        rootBounds: android.graphics.Rect,
    ): Boolean {
        if (view === excludeOverlay || view === excludeSurface ||
            !view.isShown || view.alpha <= 0.001f
        ) return false
        if (isCompositorView(view) && view.getGlobalVisibleRect(probeChildRect) &&
            android.graphics.Rect.intersects(rootBounds, probeChildRect)
        ) {
            return true
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (containsVisibleCompositorSurface(
                        view.getChildAt(i), excludeOverlay, excludeSurface, rootBounds,
                    )
                ) {
                    return true
                }
            }
        }
        return false
    }

    override fun includeNavigationInIndicatorScene(surfaceActive: Boolean) = true
    override fun drawForeground(canvas: android.graphics.Canvas,
        frame: io.github.offlineglass.hook.adapters.AdapterNavigationFrame,
        selectionPath: android.graphics.Path, drawSource: () -> Unit): Boolean =
        BilibiliOpticalForeground.draw(canvas, frame, selectionPath, drawSource)

    override fun draw(canvas: android.graphics.Canvas, frame: io.github.offlineglass.hook.adapters.AdapterNavigationFrame) =
        BilibiliRenderer.draw(canvas, frame)

    // Theme discovery may wait for native selection, but optical references
    // must follow the newly visible page even while its slider is animating.
    override fun deferThemeDetection(now: Long) = now < transitionUntil

    // The native row keeps its original listener/hit hierarchy. Recursively
    // marking decorative children clickable makes them swallow their tab's tap
    // after the overlay returns from a hidden page.
    override fun setNativeBarTouchable(view: View?, touchable: Boolean) = true

    override fun onBarGesture(phase: Int, host: View) {
        gestureActive = phase != 2
    }

    override fun onHostWindowFocusChanged(hasFocus: Boolean) = resetTransientState()

    private fun resetTransientState() {
        BilibiliShopDormancy.release()
        releaseCompositorObserver()
        transitionUntil = 0L
        selectionReadAt = 0L
        cachedSelection = null
        gestureActive = false
        pendingSelection = null
        selectionGuardUntil = 0L
        activeSource = null
        activeHost = null
        hasCompositorContent = false
        lastOpticalSurfaceMode = null
        opticalFrameReady = false
        lastUiBarAnimating = false
        lastCompositorProbe = 0L
        compositorProbeDirty = true
    }

    override fun onHostDetached() = resetTransientState()

    override fun acceptNativeNavigationSelection(index: Int, now: Long): Boolean? {
        if (gestureActive) return false
        val pending = pendingSelection ?: return null
        if (index == pending || now >= selectionGuardUntil) {
            pendingSelection = null
            return null
        }
        return false
    }

    override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int): Int? {
        val current = source?.takeIf { it.isAttachedToWindow }
            ?: activeSource?.get()?.takeIf { it.isAttachedToWindow } ?: return null
        // Verified on current TabHost: this getter uses the same index as its
        // click listener. Never invoke setCurrentItem: it can skip page callbacks.
        val now = SystemClock.uptimeMillis()
        if (getterClass === current.javaClass && now - selectionReadAt < 80L) return cachedSelection
        selectionReadAt = now
        if (getterClass !== current.javaClass) {
            cachedSelection = null
            getterClass = current.javaClass
            selectionGetter = runCatching { current.javaClass.getMethod("getCurrentItem") }
                .getOrNull()?.takeIf { it.returnType == Int::class.javaPrimitiveType }
        }
        val method = selectionGetter
        cachedSelection = if (method != null) {
            runCatching {
                (method.invoke(current) as? Int)?.takeIf { it in 0 until slotCount && it != 2 }
            }.getOrNull()
        } else {
            resolveComposeSelectedIndex(current, slotCount)
        }
        return cachedSelection
    }

    override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int): Boolean {
        if (index == BilibiliAdapter.SHOP_INDEX || cachedSelection == BilibiliAdapter.SHOP_INDEX ||
            lastDispatchedSelection == BilibiliAdapter.SHOP_INDEX) {
            (host as? io.github.offlineglass.hook.GlassHostLayout)?.let {
                io.github.offlineglass.hook.AnimationResourceGuard.begin(it)
            }
        }
        if (slotCount != 5 || index !in 0 until slotCount) return false
        val current = source?.takeIf { ready(it) && it.rootView === host.rootView }
            ?: BilibiliAdapter.findNavigation(host.rootView, targetSpec)?.view
            ?: return false
        activeSource = WeakReference(current)
        pendingSelection = index.takeUnless { it == BilibiliAdapter.PUBLISH_INDEX }
        selectionGuardUntil = SystemClock.uptimeMillis() + 300L
        // Defer theme probing only. Retaining the old backdrop here bypasses
        // the shared tap handoff and exposes sharp destination-page pixels.
        transitionUntil = SystemClock.uptimeMillis() + 180L
        android.os.Trace.beginSection("LiquidTab.TabBinding")
        val cell = try { originalTabCell(current, index, slotCount)
            ?: nativeCells(current, slotCount)?.getOrNull(index)
        } finally { android.os.Trace.endSection() }
        android.os.Trace.beginSection("LiquidTab.TabDispatch")
        val handled = if (cell != null) {
            // Exactly one action; do not retry after a listener was invoked.
            runCatching { cell.performClick() }.getOrDefault(false)
        } else {
            dispatchNativeTouch(current, index, slotCount)
        }
        android.os.Trace.endSection()
        // Invalidate AFTER native callbacks, which may synchronously redraw
        // while the controller still reports the old selection.
        selectionReadAt = 0L
        if (handled && index != BilibiliAdapter.PUBLISH_INDEX) lastDispatchedSelection = index
        if (!handled) {
            pendingSelection = null
            XposedBridge.log("LiquidTab Bilibili: native input rejected (${current.javaClass.name}); subsequent gestures remain enabled")
        }
        return handled
    }

    override fun onTapSucceeded(requestRefresh: (Long) -> Unit) {
        // Observe native selection after synchronous listeners and posted work.
        requestRefresh(0L)
        requestRefresh(190L)
    }

    private fun ready(view: View) = view.isAttachedToWindow && view.isShown &&
        view.isEnabled && view.width > 0 && view.height > 0

    private fun originalTabCell(source: ViewGroup, index: Int, count: Int): View? = runCatching {
        if (cellApiClass !== source.javaClass) {
            cellApiClass = source.javaClass
            cellCountGetter = runCatching { source.javaClass.getMethod("getItemCount") }.getOrNull()
                ?.takeIf { it.returnType == Int::class.javaPrimitiveType }
            cellGetter = if (cellCountGetter == null) null else source.javaClass.methods.filter {
                !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) &&
                    it.returnType == ViewGroup::class.java
            }.singleOrNull()
        }
        val countGetter = cellCountGetter ?: return@runCatching null
        if (countGetter.invoke(source) != count) return@runCatching null
        // Current 9.14.0 exposes k(int):ViewGroup. Match its public ABI instead
        // of the obfuscated name, and validate the returned cell's ownership.
        val getter = cellGetter ?: return@runCatching null
        val cell = getter.invoke(source, index) as? View ?: return@runCatching null
        var ancestor = cell.parent
        repeat(10) {
            if (ancestor === source) return@runCatching cell.takeIf { it.hasOnClickListeners() }
            ancestor = ancestor?.parent
        }
        null
    }.getOrNull()


    private fun nativeCells(source: ViewGroup, count: Int): List<View>? {
        val stack = ArrayDeque<Pair<ViewGroup, Int>>()
        stack.add(source to 0)
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 128) {
            val (group, depth) = stack.removeFirst()
            if (group.childCount == count) {
                val cells = (0 until count).map { group.getChildAt(it) }
                if (cells.all { ready(it) && it.hasOnClickListeners() }) {
                    val bounds = cells.map { child ->
                        Rect(0, 0, child.width, child.height).also {
                            source.offsetDescendantRectToMyCoords(child, it)
                        }
                    }
                    // A child count alone can select a decorative wrapper. Require
                    // five distinct cells ordered across the native navigation row.
                    val unit = source.width.toFloat() / count
                    if (bounds.withIndex().all { (i, rect) ->
                            rect.width() in (unit * 0.5f).toInt()..(unit * 1.5f).toInt() &&
                                kotlin.math.abs(rect.exactCenterX() - unit * (i + 0.5f)) < unit * 0.4f
                        }) return cells
                }
            }
            if (depth < 8) for (i in 0 until group.childCount) {
                (group.getChildAt(i) as? ViewGroup)?.let { stack.add(it to depth + 1) }
            }
        }
        return null
    }

    private data class ComposeTabTarget(val bounds: RectF, val selected: Boolean)

    private val composeMethods = HashMap<Pair<Class<*>, String>, Method?>()
    private val composeConfigFields = HashMap<Class<*>, Field?>()
    private val composeKeyNames = IdentityHashMap<Any, String?>()
    private val composePropertyNames = setOf("OnClick", "Selected")
    @Volatile private var pagerRef: WeakReference<View>? = null
    @Volatile private var pagerClass: Class<*>? = null
    @Volatile private var pagerCurrentItemMethod: Method? = null
    @Volatile private var pagerAdapterMethod: Method? = null

    private fun composeCall(value: Any, name: String): Any? {
        val key = value.javaClass to name
        if (!composeMethods.containsKey(key)) {
            composeMethods[key] = value.javaClass.methods
                .firstOrNull { it.name == name && it.parameterCount == 0 }
                ?.apply { isAccessible = true }
        }
        return composeMethods[key]?.invoke(value)
    }

    private fun composeStructural(value: Any, role: String, accept: (Method) -> Boolean): Method? {
        val key = value.javaClass to role
        if (!composeMethods.containsKey(key)) {
            composeMethods[key] = value.javaClass.methods
                .filter { !Modifier.isStatic(it.modifiers) && accept(it) }
                .singleOrNull()?.apply { isAccessible = true }
        }
        return composeMethods[key]
    }

    private fun composeConfigField(type: Class<*>): Field? {
        if (!composeConfigFields.containsKey(type)) {
            composeConfigFields[type] = type.fields.singleOrNull {
                !Modifier.isStatic(it.modifiers) && Iterable::class.java.isAssignableFrom(it.type)
            }
        }
        return composeConfigFields[type]
    }

    private fun composeTabTargets(source: ViewGroup, count: Int): List<ComposeTabTarget>? {
        fun findComposeHost(view: View): View? {
            if (view.javaClass.methods.any { it.name == "getSemanticsOwner" && it.parameterCount == 0 }) return view
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) findComposeHost(view.getChildAt(i))?.let { return it }
            }
            return null
        }
        val host = findComposeHost(source) ?: return null
        val owner = composeCall(host, "getSemanticsOwner") ?: return null
        val root = composeCall(owner, "getUnmergedRootSemanticsNode")
            ?: composeCall(owner, "getRootSemanticsNode")
            ?: composeStructural(owner, "root") { it.parameterCount == 0 && composeConfigField(it.returnType) != null }
                ?.invoke(owner)
            ?: return null
        val loader = source.javaClass.classLoader
        val coordinatesApi = runCatching {
            loader?.loadClass("androidx.compose.ui.layout.LayoutCoordinates")
        }.getOrNull()
        val boundsInRootMethod = coordinatesApi?.let { api ->
            runCatching {
                loader?.loadClass("androidx.compose.ui.layout.LayoutCoordinatesKt")?.getMethod("boundsInRoot", api)
            }.getOrNull()
        }
        val sourceScreen = IntArray(2).also(source::getLocationOnScreen)
        val hostScreen = IntArray(2).also(host::getLocationOnScreen)
        val dx = (hostScreen[0] - sourceScreen[0]).toFloat()
        val dy = (hostScreen[1] - sourceScreen[1]).toFloat()
        val density = source.resources.displayMetrics.density
        val minWidthPx = 20f * density
        val minHeightPx = source.height * 0.35f
        val found = ArrayList<ComposeTabTarget>(count)

        fun readBounds(raw: Any): RectF? {
            val rawBounds = composeCall(raw, "getBoundsInRoot") ?: coordinatesApi?.let { api ->
                composeStructural(raw, "coordinates") { it.parameterCount == 0 && api.isAssignableFrom(it.returnType) }
                    ?.invoke(raw)?.let { coords -> boundsInRootMethod?.invoke(null, coords) }
            }
            if (rawBounds != null) {
                fun edge(name: String) = (composeCall(rawBounds, name) as? Number)?.toFloat() ?: 0f
                return RectF(edge("getLeft") + dx, edge("getTop") + dy, edge("getRight") + dx, edge("getBottom") + dy)
            }
            val id = (composeCall(raw, "getId") as? Int) ?: raw.javaClass.fields.singleOrNull {
                !Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType
            }?.let { runCatching { it.getInt(raw) }.getOrNull() } ?: return null
            val info = host.accessibilityNodeProvider?.createAccessibilityNodeInfo(id) ?: return null
            val rect = Rect()
            info.getBoundsInScreen(rect)
            info.recycle()
            rect.offset(-sourceScreen[0], -sourceScreen[1])
            return RectF(rect)
        }

        fun visit(raw: Any, depth: Int) {
            if (depth > 24 || found.size > count * 4) return
            val config = composeCall(raw, "getConfig") ?: composeConfigField(raw.javaClass)?.get(raw) ?: return
            val iterator = composeCall(config, "iterator") as? Iterator<*> ?: return
            var hasClick = false
            var selected = false
            while (iterator.hasNext()) {
                val entry = iterator.next() as? Map.Entry<*, *> ?: continue
                val key = entry.key ?: continue
                if (!composeKeyNames.containsKey(key)) {
                    composeKeyNames[key] = composeCall(key, "getName") as? String
                        ?: key.javaClass.declaredFields.asSequence()
                            .filter { !Modifier.isStatic(it.modifiers) && it.type == String::class.java }
                            .mapNotNull { f -> runCatching { f.isAccessible = true; f.get(key) as? String }.getOrNull() }
                            .firstOrNull { it in composePropertyNames }
                        ?: composePropertyNames.firstOrNull { key.toString().endsWith(": $it") }
                }
                when (composeKeyNames[key]) {
                    "OnClick" -> hasClick = true
                    "Selected" -> if (entry.value == true) selected = true
                }
            }
            if (hasClick) {
                val bounds = readBounds(raw)
                if (bounds != null && !bounds.isEmpty &&
                    bounds.width() >= minWidthPx &&
                    bounds.width() < source.width * 0.7f &&
                    bounds.height() >= minHeightPx &&
                    bounds.left >= -2f && bounds.right <= source.width + 2f
                ) {
                    found += ComposeTabTarget(bounds, selected)
                    return
                }
            }
            val children = composeCall(raw, "getReplacedChildren") ?: composeCall(raw, "getChildren")
                ?: composeStructural(raw, "children") {
                    List::class.java.isAssignableFrom(it.returnType) && it.parameterCount in 2..3 &&
                        it.parameterTypes.all { type -> type == Boolean::class.javaPrimitiveType }
                }?.let { m -> m.invoke(raw, *Array<Any>(m.parameterCount) { false }) }
                ?: composeStructural(raw, "replacedChildren") {
                    List::class.java.isAssignableFrom(it.returnType) && it.parameterCount == 0
                }?.invoke(raw)
            (children as? List<*>)?.forEach { child -> if (child != null) visit(child, depth + 1) }
        }

        visit(root, 0)
        if (found.size != count) return null
        val sorted = found.sortedBy { it.bounds.left }
        for (i in 0 until sorted.lastIndex) {
            if (sorted[i].bounds.centerX() >= sorted[i + 1].bounds.left) return null
        }
        return sorted
    }

    private fun resolveComposeSelectedIndex(source: ViewGroup, slotCount: Int): Int? {
        composeTabTargets(source, slotCount)?.let { tabs ->
            tabs.forEachIndexed { index, tab ->
                if (tab.selected && index != BilibiliAdapter.PUBLISH_INDEX) return index
            }
        }
        if (slotCount != 5) return null
        val root = source.rootView ?: return null
        val pager = pagerRef?.get()?.takeIf { it.isAttachedToWindow && it.rootView === root }
            ?: root.resources.getIdentifier("pager", "id", root.context.packageName)
                .takeIf { it != 0 }
                ?.let { id -> root.findViewById<View>(id) }
                ?.also { pagerRef = WeakReference(it) }
            ?: return null
        if (pagerClass !== pager.javaClass) {
            pagerClass = pager.javaClass
            pagerCurrentItemMethod = runCatching { pager.javaClass.getMethod("getCurrentItem") }.getOrNull()
            pagerAdapterMethod = runCatching { pager.javaClass.getMethod("getAdapter") }.getOrNull()
        }
        val adapter = runCatching { pagerAdapterMethod?.invoke(pager) }.getOrNull() ?: return null
        val itemCount = runCatching { adapter.javaClass.getMethod("getItemCount").invoke(adapter) as? Int }.getOrNull()
        if (itemCount != 4) return null
        val page = runCatching { pagerCurrentItemMethod?.invoke(pager) as? Int }.getOrNull() ?: return null
        return when (page) {
            0, 1 -> page
            2, 3 -> page + 1
            else -> null
        }
    }

    private fun dispatchNativeTouch(source: ViewGroup, index: Int, count: Int): Boolean {
        val target = composeTabTargets(source, count)?.getOrNull(index)
        val (x, y) = if (target != null) {
            target.bounds.centerX() to target.bounds.centerY()
        } else {
            // Bilibili 9.13+ HomeBottomTabContainer switches to centered 76.8dp slots
            // spaced 24dp apart when WindowSizeClass is expanded (e.g. Bili Tablet Unlock).
            val density = source.resources.displayMetrics.density
            val itemWidthPx = 76.8f * density
            val spacingPx = 24f * density
            val groupWidthPx = count * itemWidthPx + (count - 1).coerceAtLeast(0) * spacingPx
            val root = source.rootView
            val winWidthDp = (root?.width?.takeIf { it > 0 } ?: source.width) / density
            val winHeightDp = (root?.height?.takeIf { it > 0 } ?: source.resources.displayMetrics.heightPixels) / density
            val centeredX = if (minOf(winWidthDp, winHeightDp) >= 600f && source.width >= groupWidthPx) {
                val groupLeft = (source.width - groupWidthPx) / 2f
                groupLeft + index * (itemWidthPx + spacingPx) + itemWidthPx / 2f
            } else {
                source.width * (index + 0.5f) / count
            }
            centeredX to (source.height / 2f)
        }
        val now = SystemClock.uptimeMillis()
        fun send(action: Int, time: Long): Boolean {
            val event = MotionEvent.obtain(now, time, action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            return try { source.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        return runCatching {
            if (!send(MotionEvent.ACTION_DOWN, now)) {
                send(MotionEvent.ACTION_CANCEL, now)
                false
            } else {
                // Dispatch directly to the source, never Activity/root: that
                // would recursively hit the glass overlay or unrelated content.
                send(MotionEvent.ACTION_UP, now + 32L)
                // DOWN claimed the gesture. UP may return false after launching
                // a page/dialog; that does not justify disabling future touches.
                true
            }
        }.getOrElse {
            runCatching { send(MotionEvent.ACTION_CANCEL, now + 32L) }
            false
        }
    }
}
