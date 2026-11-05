package com.lumen.coacervation.sample

import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.host.LumenSurfaceMaterial
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.model.LumenPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 绑定上的逐帧调用（光源、按压、材质浓度查询）在满载会话（16 个表面）里的主线程耗时。
 * 只用公开 API，改动前后的 APK 跑同一份测试；查询结果在两种情况下必须一致。
 */
@RunWith(AndroidJUnit4::class)
class BindingCallBenchmarkTest {
    @get:org.junit.Rule val hardwareOutput = HardwareOutputRule()

    @Test fun perFrameBindingCallsOnAFullSession() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.binding.close(); activity.session.close()
                val root = FrameLayout(activity)
                val views = (0 until 16).map { index ->
                    View(activity).also { root.addView(it, FrameLayout.LayoutParams(60, 40).apply { topMargin = index * 44 }) }
                }
                activity.setContentView(root)
                val session = LumenSurfaceSession(activity, LumenPalette.neutral(false))
                try {
                    val bindings: List<LumenSurfaceBinding> = views.map {
                        session.bind(it, LumenSurfaceOptions(material = LumenSurfaceMaterial.STATIC, radiusDp = 8f))
                    }
                    val last = bindings.last()
                    last.setMaterialOpacity(.5f)
                    fun measure(block: () -> Unit): Pair<Long, Long> {
                        repeat(2000) { block() }
                        val samples = LongArray(400)
                        for (index in samples.indices) {
                            val started = SystemClock.elapsedRealtimeNanos()
                            repeat(50) { block() }
                            samples[index] = (SystemClock.elapsedRealtimeNanos() - started) / 50
                        }
                        samples.sort()
                        return samples[200] to samples[360]
                    }
                    var sum = 0f
                    val query = measure { sum += last.materialOpacity() }
                    val light = measure { last.setLightDirection(.3f, -.4f, 1f) }
                    val press = measure { last.setPressPixels(10f, 12f, .5f) }
                    // 交替访问两个绑定：单槽缓存不命中时仍要正确。
                    val first = bindings.first()
                    val alternate = measure { first.setLightDirection(.1f, .2f, 1f); last.setLightDirection(.3f, -.4f, 1f) }
                    val queryAlternate = measure { sum += first.materialOpacity(); sum += last.materialOpacity() }
                    assertEquals(.5f, last.materialOpacity(), 0f)
                    assertEquals(1f, first.materialOpacity(), 0f)
                    assertTrue(sum > 0f)
                    assertTrue(bindings.all { it.isBound })
                    Log.i("Lumen-Bench", "bindingCalls16 query p50=${query.first}ns p90=${query.second}ns " +
                        "light p50=${light.first}ns p90=${light.second}ns press p50=${press.first}ns p90=${press.second}ns " +
                        "alternate p50=${alternate.first}ns p90=${alternate.second}ns " +
                        "queryAlternate p50=${queryAlternate.first}ns p90=${queryAlternate.second}ns")
                } finally { session.close() }
            }
        }
    }
}
