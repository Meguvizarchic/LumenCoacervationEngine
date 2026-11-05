package com.lumen.coacervation.sample

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.host.LumenSurfaceMaterial
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSampling
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.model.LumenPalette
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 软件透镜采样批次的分配量：4 个软件后端表面、内容逐帧变化约 3 秒，统计主线程与进程分配字节，
 * 除以会话记录的软件采样请求数。只用公开 API，改动前后的 APK 跑同一份测试。
 */
@RunWith(AndroidJUnit4::class)
class SamplerBatchAllocationBenchmarkTest {
    @get:org.junit.Rule val hardwareOutput = HardwareOutputRule()

    @Suppress("DEPRECATION")
    @Test fun softwareSamplingBatchAllocations() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var session: LumenSurfaceSession
            lateinit var source: View
            val bindings = ArrayList<LumenSurfaceBinding>()
            scenario.onActivity { activity ->
                activity.binding.close(); activity.session.close()
                val root = FrameLayout(activity)
                source = object : View(activity) {
                    private val paint = Paint()
                    var phase = 0
                    override fun onDraw(canvas: Canvas) {
                        for (band in 0 until 12) {
                            paint.color = Color.HSVToColor(floatArrayOf(((band * 30 + phase * 7) % 360).toFloat(), .7f, .9f))
                            canvas.drawRect(0f, band * height / 12f, width.toFloat(), (band + 1) * height / 12f, paint)
                        }
                        phase++
                    }
                }
                root.addView(source, FrameLayout.LayoutParams(360, 480))
                val targets = (0 until 4).map { index ->
                    View(activity).also { root.addView(it, FrameLayout.LayoutParams(150, 60).apply {
                        leftMargin = 20 + (index % 2) * 170; topMargin = 40 + (index / 2) * 200 }) }
                }
                activity.setContentView(root)
                session = LumenSurfaceSession(activity, LumenPalette.neutral(false))
                val options = LumenSurfaceOptions(material = LumenSurfaceMaterial.FROSTED, radiusDp = 12f,
                    sampling = LumenSurfaceSampling(backend = LumenSurfaceBackend.SOFTWARE, minIntervalMs = 0L))
                targets.forEach { bindings += session.bind(it, options, source) }
            }
            fun frames(count: Int) = repeat(count) {
                scenario.onActivity { source.invalidate(); session.notifyContentChanged() }
                SystemClock.sleep(16)
            }
            frames(60)
            var requestsBefore = 0L; var mainBefore = 0L
            val processBefore = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: -1L
            scenario.onActivity {
                requestsBefore = session.performanceDiagnostics().softwareRequests
                Debug.resetThreadAllocCount(); Debug.startAllocCounting()
                mainBefore = Debug.getThreadAllocSize().toLong()
            }
            frames(180)
            var requests = 0L; var main = 0L
            scenario.onActivity {
                main = Debug.getThreadAllocSize().toLong() - mainBefore
                Debug.stopAllocCounting()
                requests = session.performanceDiagnostics().softwareRequests - requestsBefore
                session.close()
            }
            val process = (Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: -1L) - processBefore
            assertTrue("the fixture must actually sample", requests > 50)
            Log.i("Lumen-Bench", "samplerBatch requests=$requests mainBytes=$main mainPerRequest=${if (requests > 0) main / requests else -1} " +
                "processBytes=$process processPerRequest=${if (requests > 0 && process >= 0) process / requests else -1}")
        }
    }
}
