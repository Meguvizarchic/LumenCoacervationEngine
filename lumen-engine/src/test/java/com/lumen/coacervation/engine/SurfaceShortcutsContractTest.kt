package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 简写入口只是组合既有调用：forActivity = 原构造 + 随销毁关闭；withTint = 两参数 copy。 */
class SurfaceShortcutsContractTest {
    @Test fun withTintIsTheTwoArgumentCopy() {
        val base = LumenSurfaceOptions(radiusDp = 0f, color = 0xFF181A20.toInt())
        for (value in floatArrayOf(0f, .2f, .42f, .94f, 1f)) {
            assertEquals(base.copy(tintOpacity = value, fallbackTintOpacity = value), base.withTint(value))
        }
    }

    @Test fun forActivityDelegatesToTheConstructorAndOnlyAddsTheLifecycleClose() {
        val session = SourceContract.read("host/LumenSurfaceSession.kt")
        val factory = session.after("public fun forActivity(").before("private inner class Group")
        assertTrue(factory.contains("LumenSurfaceSession(activity, palette, options).also"))
        assertTrue(factory.contains("if (activity.isDestroyed) session.close() else session.closeWith(activity)"))
        val closer = session.after("private fun closeWith(activity: Activity) {").before("public companion object")
        assertTrue("never retain the Activity strongly", closer.contains("val owner = WeakReference(activity)"))
        assertTrue(closer.contains("if (destroyed === owner.get()) close()"))
        val close = session.after("override fun close() {").before("private fun closeWith(")
        assertTrue(close.contains("unregisterActivityLifecycleCallbacks(it)"))
    }
}
