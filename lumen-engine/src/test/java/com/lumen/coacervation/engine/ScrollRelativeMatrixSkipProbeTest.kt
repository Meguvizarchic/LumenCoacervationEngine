package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.geometry.ScrollSamplingGeometry
import com.lumen.coacervation.engine.geometry.ScrollSurfaceScope
import com.lumen.coacervation.engine.geometry.SamplingMatrixMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滚动通知热路径的 host 探针（纯 JVM，不含 android.graphics）。
 *
 * 口径：用与 `ViewSamplingMatrix.localToScreen` 逐句同序的整型夹具（同一套 [SamplingMatrixMath]、
 * 同一个 [ScrollSamplingGeometry]、同一个 [ScrollSurfaceScope]）复算"源→目标相对矩阵"，
 * 统计一次 `notifyScrollPositionChanged` 中真正发生的相对矩阵重算次数与耗时。
 * 旧口径 = 源或目标任一在滚动容器内就重算；新口径 = 源与目标都在容器内且已登记过几何时跳过。
 *
 * 断言两条：
 * 1. 两种口径对每个表面给出的"是否需要刷新"结论完全一致（等价性）。
 * 2. 新口径在"源与表面同在一个滚动容器"这一常见情形下把重算次数降为 0（收益）。
 */
class ScrollRelativeMatrixSkipProbeTest {

    /** 夹具节点：只有整数布局与滚动，没有变换矩阵，与 ViewSamplingMatrix 的整型滚动路径一致。 */
    private class Node(
        val name: String,
        var parent: Node? = null,
        var left: Int = 0,
        var top: Int = 0,
        var scrollX: Int = 0,
        var scrollY: Int = 0
    )

    /** 与 ViewSamplingMatrix 一样复用全部缓冲，避免把分配算进被测成本。 */
    private class MatrixProbe {
        private val sourceGlobal = FloatArray(9)
        private val targetGlobal = FloatArray(9)
        private val inverse = FloatArray(9)
        private val step = FloatArray(9)
        private val out = FloatArray(9)
        var calls = 0L
        var nanos = 0L
        /** 最近一次 sourceToTarget 的结果，供 needsRefresh 读取。 */
        val lastMatrix: FloatArray get() = out

        /** 逐句对应 ViewSamplingMatrix.localToScreen：自下而上累乘，根对齐到屏幕原点。 */
        private fun localToScreen(view: Node): Boolean {
            SamplingMatrixMath.identity(out)
            var current: Node? = view
            var depth = 0
            while (depth++ < 128) {
                val node = current ?: return false
                SamplingMatrixMath.identity(step) // 夹具没有变换矩阵
                val parent = node.parent
                SamplingMatrixMath.translateAfter(step,
                    (node.left - (parent?.scrollX ?: 0)).toFloat(),
                    (node.top - (parent?.scrollY ?: 0)).toFloat())
                SamplingMatrixMath.multiply(step, out, out)
                if (parent == null) return SamplingMatrixMath.alignRootToScreen(out, step, 0, 0)
                current = parent
            }
            return false
        }

        /** 对应 Entry.sourceToTarget → ViewSamplingMatrix.sourceToTarget(source, target, out)。 */
        fun sourceToTarget(source: Node, target: Node): Boolean {
            calls++
            val start = System.nanoTime()
            var ok = false
            if (localToScreen(source)) {
                out.copyInto(sourceGlobal)
                if (localToScreen(target)) {
                    out.copyInto(targetGlobal)
                    ok = SamplingMatrixMath.bitmapToTarget(sourceGlobal, targetGlobal, 1f, 1f, out, inverse)
                }
            }
            nanos += System.nanoTime() - start
            return ok
        }
    }

    private fun contains(surface: Node, host: Node): Boolean =
        ScrollSurfaceScope.contains(surface, host) { it.parent }

    /** 旧口径：源或目标任一在滚动容器内就重算相对矩阵。 */
    private fun oldDecision(
        probe: MatrixProbe, geometry: ScrollSamplingGeometry, source: Node, target: Node,
        scrollHost: Node, sourceMoved: Boolean
    ): Boolean {
        if (!sourceMoved && !contains(target, scrollHost)) return false
        if (!probe.sourceToTarget(source, target)) return false
        return geometry.needsRefresh(probe.lastMatrix)
    }

    /** 新口径：源与目标都在容器内且已登记过几何时跳过；其余与旧口径逐句相同。 */
    private fun newDecision(
        probe: MatrixProbe, geometry: ScrollSamplingGeometry, source: Node, target: Node,
        scrollHost: Node, sourceMoved: Boolean
    ): Boolean {
        if (!sourceMoved && !contains(target, scrollHost)) return false
        if (sourceMoved && geometry.isInitialized && contains(target, scrollHost)) return false
        if (!probe.sourceToTarget(source, target)) return false
        return geometry.needsRefresh(probe.lastMatrix)
    }

    /** 建一棵与桥演示页同形的树：window → scroll → page → {content, column → cards}。 */
    private class Fixture(val scroll: Node, val content: Node, val cards: List<Node>, val sourceMoved: Boolean)

    private fun fixture(cardCount: Int): Fixture {
        val window = Node("window")
        val scroll = Node("scroll", window)
        val page = Node("page", scroll)
        val content = Node("content", page)
        val column = Node("column", page)
        val cards = (0 until cardCount).map { Node("card $it", column, top = 120 + it * 72) }
        return Fixture(scroll, content, cards, contains(content, scroll))
    }

    /** 滚动前为每个表面登记一次相对矩阵（对应首次成功硬件绘制）。 */
    private fun recorded(card: Node, content: Node): ScrollSamplingGeometry {
        val probe = MatrixProbe()
        check(probe.sourceToTarget(content, card)) { "fixture must resolve" }
        return ScrollSamplingGeometry().also { it.record(probe.lastMatrix) }
    }

    private class ProbeResult(
        val oldDecisions: List<Boolean>,
        val newDecisions: List<Boolean>,
        val oldProbe: MatrixProbe,
        val newProbe: MatrixProbe
    )

    private fun bench(cardCount: Int, scrollDelta: Int, uninitialized: Boolean = false): ProbeResult {
        val fix = fixture(cardCount)
        val geometries = fix.cards.map { recorded(it, fix.content) }
        fix.scroll.scrollY = scrollDelta
        val sourceMoved = fix.sourceMoved
        val oldProbe = MatrixProbe()
        val newProbe = MatrixProbe()
        val oldDecisions = ArrayList<Boolean>(cardCount)
        val newDecisions = ArrayList<Boolean>(cardCount)
        for (index in fix.cards.indices) {
            val geometry = if (uninitialized) ScrollSamplingGeometry() else geometries[index]
            oldDecisions += oldDecision(oldProbe, geometry, fix.content, fix.cards[index], fix.scroll, sourceMoved)
            newDecisions += newDecision(newProbe, geometry, fix.content, fix.cards[index], fix.scroll, sourceMoved)
        }
        return ProbeResult(oldDecisions, newDecisions, oldProbe, newProbe)
    }

    @Test
    fun scrollWithSourceAndCardsInsideTheSameContainerSkipsTheWholeChainRecompute() {
        val rounds = 50
        var oldCalls = 0L
        var newCalls = 0L
        var oldNanos = 0L
        var newNanos = 0L
        repeat(rounds) {
            val result = bench(cardCount = 16, scrollDelta = 400)
            assertEquals("两种口径必须给出相同的刷新结论", result.oldDecisions, result.newDecisions)
            assertFalse("同容器滚动不应判定为需要刷新", result.oldDecisions.any { it })
            oldCalls += result.oldProbe.calls
            newCalls += result.newProbe.calls
            oldNanos += result.oldProbe.nanos
            newNanos += result.newProbe.nanos
        }
        println("LumenScrollSkipProbe cards=16 rounds=$rounds " +
            "oldCalls=$oldCalls newCalls=$newCalls oldNanos=$oldNanos newNanos=$newNanos " +
            "oldMicrosPerNotify=${oldNanos / rounds / 1_000} newMicrosPerNotify=${newNanos / rounds / 1_000}")
        assertEquals("旧口径每个表面都要重算一次", 16L * rounds, oldCalls)
        assertEquals("新口径同容器滚动一次都不重算", 0L, newCalls)
        assertTrue("新口径必须更快", newNanos < oldNanos)

        // 桥演示页同形：4 张卡片。
        var old4Calls = 0L
        var old4Nanos = 0L
        repeat(rounds) {
            val result = bench(cardCount = 4, scrollDelta = 400)
            assertEquals(result.oldDecisions, result.newDecisions)
            old4Calls += result.oldProbe.calls
            old4Nanos += result.oldProbe.nanos
        }
        println("LumenScrollSkipProbe cards=4 rounds=$rounds " +
            "oldCalls=$old4Calls newCalls=0 oldNanos=$old4Nanos newNanos=0 " +
            "oldMicrosPerNotify=${old4Nanos / rounds / 1_000} newMicrosPerNotify=0")
        assertEquals(4L * rounds, old4Calls)
    }

    @Test
    fun cardsOutsideTheScrollContainerStillRecomputeAndAgreeWithTheOldDecision() {
        val window = Node("window")
        val scroll = Node("scroll", window)
        val page = Node("page", scroll)
        val content = Node("content", page)
        val column = Node("column", page)
        val inside = Node("inside", column, top = 120)
        val outside = Node("outside", window, top = 200)
        val insideGeometry = recorded(inside, content)
        val outsideGeometry = recorded(outside, content)
        scroll.scrollY = 400
        val sourceMoved = contains(content, scroll)
        assertTrue(sourceMoved)
        val oldProbe = MatrixProbe()
        val newProbe = MatrixProbe()
        val oldInside = oldDecision(oldProbe, insideGeometry, content, inside, scroll, sourceMoved)
        val newInside = newDecision(newProbe, insideGeometry, content, inside, scroll, sourceMoved)
        val oldOutside = oldDecision(oldProbe, outsideGeometry, content, outside, scroll, sourceMoved)
        val newOutside = newDecision(newProbe, outsideGeometry, content, outside, scroll, sourceMoved)
        assertFalse(oldInside)
        assertFalse(newInside)
        assertTrue("容器外的表面相对矩阵确实变了", oldOutside)
        assertTrue("容器外的表面仍被两种口径判定为需要刷新", newOutside)
        assertEquals("容器内结论一致", oldInside, newInside)
        assertEquals("容器外结论一致", oldOutside, newOutside)
        assertEquals("只有容器外那一个表面重算", 1L, newProbe.calls)
        assertEquals("旧口径两个表面都重算", 2L, oldProbe.calls)
    }

    @Test
    fun uninitializedGeometryFallsBackToTheOldPath() {
        val result = bench(cardCount = 8, scrollDelta = 400, uninitialized = true)
        assertEquals("未登记几何时两种口径结论仍一致", result.oldDecisions, result.newDecisions)
        assertTrue("未登记几何必须判定为需要刷新", result.oldDecisions.all { it })
        assertEquals("未登记几何时新口径不跳过", result.oldProbe.calls, result.newProbe.calls)
    }

    @Test
    fun scrollHostItselfAsSourceStillRecomputes() {
        val window = Node("window")
        val scroll = Node("scroll", window)
        val column = Node("column", scroll)
        val card = Node("card", column, top = 120)
        val geometry = recorded(card, scroll)
        scroll.scrollY = 400
        val sourceMoved = contains(scroll, scroll)
        assertFalse(sourceMoved)
        val oldProbe = MatrixProbe()
        val newProbe = MatrixProbe()
        val oldDecision = oldDecision(oldProbe, geometry, scroll, card, scroll, sourceMoved)
        val newDecision = newDecision(newProbe, geometry, scroll, card, scroll, sourceMoved)
        assertEquals(oldDecision, newDecision)
        assertEquals("源就是滚动容器时不能跳过", 1L, newProbe.calls)
    }

    @Test
    fun recordedFootprintStaysBitIdenticalUnderCommonAncestorScroll() {
        val fix = fixture(cardCount = 1)
        val geometry = recorded(fix.cards.first(), fix.content)
        val probe = MatrixProbe()
        repeat(120) { delta ->
            fix.scroll.scrollY = delta * 7
            probe.sourceToTarget(fix.content, fix.cards.first())
            assertFalse("第 $delta 次滚动后相对矩阵必须逐位不变", geometry.needsRefresh(probe.lastMatrix))
        }
    }
}
