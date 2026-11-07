package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滚动通知的"同源同目标在滚动容器内不重算相对矩阵"护栏。
 *
 * 这条跳过必须满足三个条件才成立：源与目标都在容器内、几何已登记、且仍排在 failure 复查之前。
 * 少任何一条都会让相对矩阵变化被漏掉（残影），或多算一条祖先链（回到优化前）。
 */
class ScrollRelativeMatrixSkipContractTest {
    @Test fun scrollSkipNeedsBothSidesInsideAndAnRecordedFootprintBeforeFailure() {
        val session = SourceContract.read("host/LumenSurfaceSession.kt")
        val scroll = session.after("public fun notifyScrollPositionChanged(scrollHost: View)").before("public fun pause()")
        val scopeFilter = scroll.indexOf("if (!sourceMoved && !ScrollSurfaceScope.contains(host, scrollHost)")
        val skip = scroll.indexOf("sourceMoved && entry.scrollGeometry.isInitialized")
        val skipTail = scroll.indexOf("ScrollSurfaceScope.contains(host, scrollHost) { it.parent as? View }) continue", skip)
        val feedbackCheck = scroll.indexOf("if (failure(host, entry) != LumenSurfaceFailure.NONE)")
        assertTrue("既有作用域过滤必须仍在", scopeFilter >= 0)
        assertTrue("跳过必须要求源与目标都在容器内", skip >= 0)
        assertTrue("跳过必须要求已登记过几何", skip >= 0 && scroll.substring(skip).contains("isInitialized"))
        assertTrue("跳过必须完整到 continue", skipTail > skip)
        assertTrue("跳过仍排在 failure 之前", skip < feedbackCheck)
        assertTrue("原有作用域过滤仍排在跳过之前", scopeFilter < skip)
    }

    @Test fun geometryExposesInitializationWithoutChangingRefreshSemantics() {
        val geometry = SourceContract.read("geometry/ScrollSamplingGeometry.kt")
        assertTrue(geometry.contains("val isInitialized: Boolean get() = initialized"))
        assertTrue(geometry.contains("!initialized || !SamplingMatrixMath.equal(recorded, current)"))
    }
}
