package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.runtime.LumenMemoryPressureHub
import com.lumen.coacervation.engine.runtime.LumenMemoryPressureListener
import org.junit.Assert.assertEquals
import org.junit.Test

class LumenMemoryPressureHubTest {

    @Test
    fun `release walks current listeners and unregister stops further calls`() {
        val counts = mutableListOf(0, 0)
        val first = LumenMemoryPressureListener { counts[0] += 1 }
        val second = LumenMemoryPressureListener { counts[1] += 1 }
        LumenMemoryPressureHub.addListener(first)
        LumenMemoryPressureHub.addListener(second)
        try {
            LumenMemoryPressureHub.releaseGraphics()
            assertEquals(listOf(1, 1), counts)
            LumenMemoryPressureHub.removeListener(second)
            LumenMemoryPressureHub.releaseGraphics()
            assertEquals(listOf(2, 1), counts)
        } finally {
            LumenMemoryPressureHub.removeListener(first)
            LumenMemoryPressureHub.removeListener(second)
        }
    }

    /** 系统 trim 自动释放的门槛与 Activity、局部会话一致：只有 15 与 >= 80，20（UI_HIDDEN）等普通切后台不释放。 */
    @Test
    fun `system trim releases only at running critical and complete`() {
        val released = listOf(5, 10, 15, 20, 40, 60, 80).filter(LumenMemoryPressureHub::shouldRelease)
        assertEquals(listOf(15, 80), released)
    }

    /** 每个拿得到 Context 的入口都登记系统回调，宿主不必在 Application.onTrimMemory 里转发。 */
    @Test
    fun `every context entry installs the process callbacks once`() {
        val hub = com.lumen.coacervation.engine.contract.SourceContract.read("runtime/LumenMemoryPressureHub.kt")
        assertEquals(true, hub.contains("application.registerComponentCallbacks(systemCallbacks)"))
        assertEquals(true, hub.contains("if (systemInstalled) return"))
        for ((file, call) in listOf(
            "host/LumenSurfaceSession.kt" to "LumenMemoryPressureHub.installSystemCallbacks(appContext)",
            "host/LumenMicaBackdrop.kt" to "LumenMemoryPressureHub.installSystemCallbacks(view.context)",
            "runtime/ActivitySkinSession.kt" to "LumenMemoryPressureHub.installSystemCallbacks(activity)")) {
            assertEquals(file, true, com.lumen.coacervation.engine.contract.SourceContract.read(file).contains(call))
        }
    }
}
