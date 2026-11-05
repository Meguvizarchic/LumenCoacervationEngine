package com.lumen.coacervation.sample

import android.app.Activity
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.ref.WeakReference

/**
 * 诊断：每个演示页启动、关闭若干次，强制 GC 后统计仍存活的 Activity 实例与内存压力中心的登记数。
 * 存活的 Activity 意味着某个进程级登记（Application 回调、压力中心、静态缓存）仍可达它。
 *
 * 判定前按弱引用轮询回收（最多 [RELEASE_TIMEOUT_MS]）：系统在页面关闭后短暂持有 Activity 属正常，
 * 例如滚动条淡出消息在主线程队列里最多停留约 1 秒。超时仍存活就导出堆到 [HPROF_NAME]，
 * CI 的 run-smoke.sh 在失败时拉取它作为证据上传，然后断言失败。
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

    /** 每轮强制回收后检查一次，全部回收即返回；超时返回仍存活的数量。 */
    private fun awaitCollected(references: Map<String, List<WeakReference<Activity>>>): Map<String, Int> {
        val deadline = SystemClock.uptimeMillis() + RELEASE_TIMEOUT_MS
        while (true) {
            Runtime.getRuntime().gc(); System.runFinalization()
            val alive = references.mapValues { (_, list) -> list.count { it.get() != null } }
            if (alive.values.sum() == 0 || SystemClock.uptimeMillis() >= deadline) return alive
            SystemClock.sleep(300)
        }
    }

    private fun evidenceFile(): File? =
        InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)?.let { File(it, HPROF_NAME) }

    @Test fun repeatedlyOpenedPagesAreCollectedAfterClose() {
        evidenceFile()?.delete()
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
        val alive = awaitCollected(references)
        if (alive.values.sum() != 0) {
            val file = evidenceFile()
            if (file != null) {
                Debug.dumpHprofData(file.absolutePath)
                Log.i("Lumen-Leak", "hprof=${file.absolutePath} bytes=${file.length()}")
            }
        }
        Log.i("Lumen-Leak", "alive=$alive opened=${references.values.sumOf { it.size }} hubListeners before=$baseline " +
            "afterPass=$counts owners=${hubOwners()}")
        assertEquals("every closed page must be collectable", 0, alive.values.sum())
        assertEquals("process-level registrations must not grow with the number of pages opened", counts[0], counts[1])
    }

    private companion object {
        const val RELEASE_TIMEOUT_MS = 10_000L
        const val HPROF_NAME = "lumen-leak.hprof"
    }
}
