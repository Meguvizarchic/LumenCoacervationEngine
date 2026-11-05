package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宿主（BIL 六处）曾各自手写“isBound + 背景归属 + update/重新绑定”的对账；`ensure` 把它收进引擎。
 * 守住：沿用只在来源相同且背景仍归引擎时发生，沿用走与 update 相同的就地更新，句柄每条目只有一个。
 */
class SurfaceEnsureContractTest {
    private val session = SourceContract.read("host/LumenSurfaceSession.kt")

    @Test fun reuseRequiresTheSameSourceAndAnEngineOwnedBackground() {
        val reuse = session.after("private fun reuse(").before("/** A draw target has its own identity")
        assertTrue(reuse.contains("entries.firstOrNull { it.installsBackground && it.host.get() === view } ?: return null"))
        assertTrue(reuse.contains("if (!sameSource || installed && view.background !== entry.drawable) return null"))
        assertTrue(reuse.contains("updateEntry(entry, surface)"))
        assertTrue(reuse.contains("updateEntryEnhancements(entry, enhancements)"))
        // 来源分组规则与 bindSource 一致：同一 View 来源每次新建的 ViewContentSource 视为同一组。
        assertTrue(reuse.contains("group.source.coordinateView === source.coordinateView &&\n                group.source is ViewContentSource && source is ViewContentSource"))
    }

    @Test fun publicUpdateAndEnsureShareOneInPlacePathAndHandlesAreCached() {
        val update = session.after("internal fun update(id: Long, value: LumenSurfaceOptions) {").before("internal fun detach(")
        assertTrue(update.contains("entries.firstOrNull { it.id == id }?.let { updateEntry(it, value) }"))
        assertTrue(update.after("private fun updateEntry(").contains("if (entry.config == value) return"))
        assertTrue(session.contains("val binding = LumenSurfaceBinding(this@LumenSurfaceSession, id)"))
        assertEquals("bindSource, bindTarget, draw-only bind and reuse hand back the entry's cached handle", 4, Regex("return entry.binding", RegexOption.LITERAL).findAll(session).count())
        assertFalse(session.contains("return LumenSurfaceBinding(this, entry.id)"))
    }
}
