package com.lumen.coacervation.sample

import android.app.Activity
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.ref.WeakReference

/**
 * 诊断：每个演示页启动、关闭若干次，强制 GC 后统计仍存活的 Activity 实例与内存压力中心的登记数。
 * 存活的 Activity 意味着某个进程级登记（Application 回调、压力中心、静态缓存）仍可达它。
 */
@RunWith(AndroidJUnit4::class)
class LifecycleLeakDiagnosticTest {
    private val pages: List<Class<out Activity>> = listOf(
        SampleActivity::class.java, DetailActivity::class.java, SurfaceSandboxActivity::class.java,
        FluentSandboxActivity::class.java, MicaSandboxActivity::class.java, PrismSandboxActivity::class.java,
        SliderSandboxActivity::class.java, MaterialOpacitySandboxActivity::class.java, ComposeSandboxActivity::class.java)

    private fun hubMap(): Map<*, *> {
        val type = Class.forName("com.lumen.coacervation.engine.runtime.LumenMemoryPressureHub")
        val hub = type.getDeclaredField("INSTANCE").get(null)
        return type.getDeclaredField("listeners").apply { isAccessible = true }.get(hub) as Map<*, *>
    }
    private fun hubListeners(): Int = hubMap().size
    private fun hubOwners(): List<String> = hubMap().keys.mapNotNull { it?.javaClass?.name }.sorted()

    private fun collect() {
        repeat(6) {
            Runtime.getRuntime().gc(); System.runFinalization()
            SystemClock.sleep(300)
        }
    }

    @Test fun repeatedlyOpenedPagesAreCollectedAfterClose() {
        collect()
        val baseline = hubListeners()
        val counts = ArrayList<Int>()
        val references = LinkedHashMap<String, MutableList<WeakReference<Activity>>>()
        repeat(2) { pass ->
            for (page in pages) {
                repeat(4) {
                    ActivityScenario.launch(page).use { scenario ->
                        scenario.onActivity { references.getOrPut(page.simpleName) { ArrayList() } += WeakReference(it) }
                        SystemClock.sleep(600)
                    }
                }
            }
            collect()
            counts += hubListeners()
        }
        val alive = references.mapValues { (_, list) -> list.count { it.get() != null } }
        Log.i("Lumen-Leak", "alive=$alive opened=${references.values.sumOf { it.size }} hubListeners before=$baseline " +
            "afterPass=$counts owners=${hubOwners()}")
        assertEquals("every closed page must be collectable", 0, alive.values.sum())
        assertEquals("process-level registrations must not grow with the number of pages opened", counts[0], counts[1])
    }
}
