package com.lumen.coacervation.engine

import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.liquid.LiquidThermalMonitorApi29
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.ref.Reference
import java.lang.ref.WeakReference

/**
 * 热状态监听注销后，系统进程仍可能持有 PowerManager 包出的 Binder 存根，直到它自己 GC（本进程里是 JNI 全局引用）。
 * 2026-11-06 Demo APK API 31 的 hprof：存根 → 监听 → 监视器 → 性能协调器 → 渲染器 → Activity。
 * 这里在 stop 之后继续强引用登记出去的监听（模拟远端尚未释放），回调链上的对象必须仍可回收。
 */
@SdkSuppress(minSdkVersion = 29)
@RunWith(AndroidJUnit4::class)
class ThermalListenerReleaseInstrumentedTest {
    @Test fun aStoppedMonitorsRegisteredListenerNoLongerReachesItsCallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var registered: Any? = null
        lateinit var callbackOwner: WeakReference<Any>
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val owner = Any()
            callbackOwner = WeakReference(owner)
            val monitor = LiquidThermalMonitorApi29(context) { owner.hashCode() }
            monitor.start()
            // 登记给 PowerManager 的那个对象：监视器里唯一实现了 OnThermalStatusChangedListener 的字段。
            registered = LiquidThermalMonitorApi29::class.java.declaredFields.firstNotNullOfOrNull { field ->
                field.isAccessible = true
                field.get(monitor)?.takeIf { it is PowerManager.OnThermalStatusChangedListener }
            }
            monitor.stop()
        }
        assertNotNull("monitor must register a listener", registered)
        for (attempt in 0 until 20) {
            Runtime.getRuntime().gc(); System.runFinalization()
            if (callbackOwner.get() == null) break
            SystemClock.sleep(100)
        }
        assertNull("the registered listener must not keep the callback chain alive after stop", callbackOwner.get())
        Reference.reachabilityFence(registered)
    }
}
