package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 高级材质 Activity 退到后台时只留正在显示的实时截图。守住三件事：不回收（RenderThread / 在途 PixelCopy 可能仍持有），
 * 不走会永久降级的挂起路径，回前台补齐时保留绑定、不改写正在显示的缓冲。
 */
class LiquidStopEvictionContractTest {
    private val renderer = SourceContract.read("liquid/LiquidActivityRenderer.kt")

    @Test fun stopReleasesIdleBuffersWithoutRecyclingOrSuspending() {
        val stop = renderer.after("fun onActivityStopped() {").before("override fun surface(")
        assertTrue(stop.contains("captureRequests.invalidate()"))
        assertTrue(stop.contains("releaseIdleCaptureBuffers()"))
        val release = stop.after("private fun releaseIdleCaptureBuffers() {")
        assertTrue(release.contains("sources.forEach { if (it !== bound) it.close() }"))
        assertTrue(release.contains("realtimeCaptureSources = listOf(bound)"))
        assertTrue(release.contains("releaseSuppressionUnderlay()"))
        assertFalse(release.contains("recycle()"))
        assertFalse(release.contains("suspendRealtimeCapture("))
        assertFalse(release.contains("advanceToTranslucent"))
    }

    @Test fun returningRegrowsAroundTheDisplayedBuffer() {
        val ensure = renderer.after("private fun ensureRealtimeCaptureSources(").before("private fun suspendRealtimeCapture(")
        val grow = ensure.after("if (reusable && existing.size < LiquidRealtimeCapturePolicy.BUFFER_COUNT) {")
            .before("releaseRealtimeCaptureSources(rebindStableBackdrop = true)")
        assertTrue(grow.contains("addAll(existing)"))
        assertTrue(grow.contains("realtimeCaptureNextIndex = existing.size"))
        assertFalse("regrowing must not rebind the stable backdrop", grow.contains("bindPreparedBackendsToBackdrop"))
    }
}
