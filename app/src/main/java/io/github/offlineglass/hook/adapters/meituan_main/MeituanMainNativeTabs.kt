package io.github.offlineglass.hook.adapters.meituan_main

import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/** Stable native artwork API + platform selection + a unique logical cell binding. */
internal object MeituanMainNativeTabs {
    data class Cell(val slot: View, val glyph: View, val button: View, val extraTabs: List<View> = emptyList())
    data class Binding(
        val row: ViewGroup,
        val cells: List<Cell>,
        val actionIndex: Int,
        val cartIndex: Int,
        val profileIndex: Int,
    ) {
        fun valid() = row.childCount == cells.size && cells.withIndex().all { (i, cell) ->
            row.getChildAt(i) === cell.slot && cell.glyph.isAttachedToWindow &&
                cell.button.isAttachedToWindow && below(cell.glyph, cell.slot) && below(cell.button, cell.slot)
        }
    }
    private val contracts = ConcurrentHashMap<Class<*>, Boolean>()
    private val bindings = WeakHashMap<ViewGroup, Binding>()
    private val observers = WeakHashMap<ViewGroup, Triple<android.view.ViewTreeObserver, android.view.ViewTreeObserver.OnGlobalLayoutListener, View.OnAttachStateChangeListener>>()
    private val active = WeakHashMap<ViewGroup, Boolean>()
    private val restorers = WeakHashMap<ViewGroup, () -> Unit>()
    private val failures = WeakHashMap<ViewGroup, String>()
    private fun below(child: View, parent: View): Boolean {
        var current: View? = child
        while (current != null) { if (current === parent) return true; current = current.parent as? View }
        return false
    }
    fun isTab(view: View): Boolean = contracts.getOrPut(view.javaClass) {
        MeituanMainTabContract.matches(view.javaClass, View::class.java, Drawable::class.java)
    }
    private fun visibleTree(root: View): List<View> {
        val result = ArrayList<View>(); val queue = ArrayDeque<View>(); queue += root
        while (queue.isNotEmpty() && result.size < 64) {
            val view = queue.removeFirst()
            // Native scroll animations may hide a slot without changing its capability.
            result += view
            if (view is ViewGroup) for (i in 0 until view.childCount) queue += view.getChildAt(i)
        }
        return if (queue.isEmpty()) result else emptyList()
    }
    fun actionIndex(row: ViewGroup?, slotCount: Int): Int =
        bind(row)?.actionIndex ?: if (slotCount == 4) 1 else 2

    fun cartIndex(row: ViewGroup?, slotCount: Int): Int =
        bind(row)?.cartIndex ?: if (slotCount == 4) 2 else 3

    fun profileIndex(row: ViewGroup?, slotCount: Int): Int =
        bind(row)?.profileIndex ?: if (slotCount == 4) 3 else 4

    fun bind(row: ViewGroup?): Binding? {
        row ?: return null
        bindings[row]?.takeIf { it.valid() }?.let { return it }
        bindings.remove(row)
        val slotCount = row.childCount
        val cells = if (slotCount in 4..5) (0 until slotCount).mapNotNull { index ->
            val slot = row.getChildAt(index)
            val nodes = visibleTree(slot)
            val visibleTabs = nodes.filter { it.width > 0 && it.height > 0 && isTab(it) }
            val leafTabs = visibleTabs.filter { it !is ViewGroup }
            val clickableTabs = visibleTabs.filter {
                it.javaClass.name.startsWith("com.meituan.android.pt.homepage.tab.") && it.hasOnClickListeners()
            }
            val singleGlyph = MeituanMainTabContract.unique(leafTabs)
            if (singleGlyph != null) {
                val targets = nodes.filter {
                    it.javaClass.name.startsWith("com.meituan.android.pt.homepage.tab.") &&
                        it.hasOnClickListeners() && below(singleGlyph, it)
                }
                val target = MeituanMainTabContract.unique(targets) ?: return@mapNotNull null
                Cell(slot, singleGlyph, target, visibleTabs.filter { it !== singleGlyph })
            } else {
                // Newer Meituan builds (e.g. 12.69+) can stack an auxiliary overlay
                // tab view alongside the clickable tab view inside the same slot.
                val target = MeituanMainTabContract.unique(clickableTabs) ?: return@mapNotNull null
                Cell(slot, target, target, visibleTabs.filter { it !== target })
            }
        } else emptyList()
        val labels = cells.map { runCatching { it.glyph.javaClass.getMethod("getTitle").invoke(it.glyph) as String }.getOrDefault("") }
        val cartIdx = labels.indexOf("购物车")
        val profileIdx = labels.indexOf("我的")
        val actionIdx = labels.indexOf("小团").takeIf { it >= 0 } ?: if (slotCount == 4) 1 else 2
        val semantic = labels.size == slotCount && slotCount in 4..5 &&
            labels[0] in setOf("首页", "推荐") &&
            cartIdx == slotCount - 2 && profileIdx == slotCount - 1 &&
            (slotCount == 4 || actionIdx == 2)
        if (!semantic) {
            val reason = "unique navigation cells/roles missing: count=$slotCount cells=${cells.size}, labels=$labels"
            if (failures[row] != reason) Log.w("MeituanBinding", reason)
            if (failures[row] != reason) runCatching {
                java.io.File(row.context.getExternalFilesDir(null), "liquidtab-meituan-binding-v2.txt").writeText(
                    "${System.currentTimeMillis()} $reason\n" + (0 until row.childCount).joinToString("\n") { i ->
                        visibleTree(row.getChildAt(i)).joinToString("\n") { v ->
                            "${v.javaClass.name} ${v.width}x${v.height} listener=${v.hasOnClickListeners()} tab=${isTab(v)} vg=${v is ViewGroup} sel=${v.isSelected} title=" +
                                runCatching { v.javaClass.getMethod("getTitle").invoke(v) }.getOrNull()
                        }
                    })
            }
            failures[row] = reason
            return null
        }
        if (observers[row]?.first?.isAlive != true) {
            observers.remove(row)?.let { (_, _, detach) -> row.removeOnAttachStateChangeListener(detach) }
            val tree = row.viewTreeObserver
            val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
                bindings[row]?.let { if (!it.valid()) bindings.remove(row) }
            }
            val detach = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit
                override fun onViewDetachedFromWindow(view: View) { release(row) }
            }
            tree.addOnGlobalLayoutListener(listener); row.addOnAttachStateChangeListener(detach)
            observers[row] = Triple(tree, listener, detach)
        }
        return Binding(row, cells, actionIdx, cartIdx, profileIdx).also { bindings[row] = it; failures.remove(row) }
    }
    fun row(block: ViewGroup): ViewGroup? {
        if (block.javaClass.name != "com.meituan.android.pt.homepage.tab.TabBlockV2") return null
        return (0 until block.childCount).mapNotNull { block.getChildAt(it) as? ViewGroup }
            .filter { bind(it) != null }.singleOrNull()
    }
    fun activated(row: ViewGroup?) = row != null && active[row] == true
    /** The content sibling of the same verified navigation block, excluding its artwork. */
    fun content(row: ViewGroup?): View? {
        if (bind(row) == null) return null
        val block = row?.parent as? ViewGroup ?: return null
        if (block.javaClass.name != "com.meituan.android.pt.homepage.tab.TabBlockV2") return null
        val shell = block.parent as? ViewGroup ?: return null
        return (0 until shell.childCount).map(shell::getChildAt).filter {
            it !== block && runCatching { it.resources.getResourceEntryName(it.id) }.getOrNull() == "main"
        }.singleOrNull()
    }
    fun onRestore(row: ViewGroup, restore: () -> Unit) { restorers[row] = restore }
    fun activate(row: ViewGroup, enabled: Boolean) {
        active[row] = enabled
        if (!enabled) restorers.remove(row)?.invoke()
    }
    fun release(row: ViewGroup?, restoreNative: Boolean = false) {
        row ?: return
        if (restoreNative) activate(row, false)
        else restorers.remove(row)
        bindings.remove(row); active.remove(row); failures.remove(row)
        observers.remove(row)?.let { (tree, listener, detach) ->
            if (tree.isAlive) tree.removeOnGlobalLayoutListener(listener)
            row.removeOnAttachStateChangeListener(detach)
        }
    }
}
