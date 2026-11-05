package com.lumen.coacervation.sample

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.lumen.coacervation.engine.host.LumenSurfaceMaterial
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSampling
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.model.LumenPalette
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 局部会话的 GPU 透镜：8 个表面（LIQUID 与 FROSTED、各两档折射强度）在同一会话里，
 * 记录每个表面的窗口像素哈希、绑定后各帧的绘制耗时与着色器构建次数。改动前后的 APK 跑同一份测试，
 * 像素哈希必须逐个相同（2026-11-05：进程内共享透镜程序前后 12 轮逐个相同）。
 */
@SdkSuppress(minSdkVersion = 33)
@RunWith(AndroidJUnit4::class)
class LensProgramSharingTest {
    @get:org.junit.Rule val hardwareOutput = HardwareOutputRule()

    @Test fun mixedLensSurfacesPixelsAndFirstFrames() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var session: LumenSurfaceSession
            lateinit var targets: List<View>
            lateinit var activity: SurfaceSandboxActivity
            val draws = ArrayList<Long>()
            var listener: Window.OnFrameMetricsAvailableListener? = null
            scenario.onActivity { current ->
                activity = current
                current.binding.close(); current.session.close()
                val root = FrameLayout(current)
                val source = object : View(current) {
                    private val paint = Paint()
                    override fun onDraw(canvas: Canvas) {
                        for (band in 0 until 10) {
                            paint.color = Color.HSVToColor(floatArrayOf(band * 36f, .75f, .95f))
                            canvas.drawRect(0f, band * height / 10f, width.toFloat(), (band + 1) * height / 10f, paint)
                        }
                        paint.color = Color.WHITE
                        for (i in 0 until 9) canvas.drawRect(i * 48f, 0f, i * 48f + 10f, height.toFloat(), paint)
                    }
                }
                root.addView(source, FrameLayout.LayoutParams(420, 720))
                targets = (0 until 8).map { index -> View(current).also { root.addView(it, FrameLayout.LayoutParams(170, 70).apply {
                    leftMargin = 24 + (index % 2) * 200; topMargin = 24 + (index / 2) * 170 }) } }
                current.setContentView(root)
                listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ -> synchronized(draws) { draws += metrics.getMetric(FrameMetrics.DRAW_DURATION) } }
                current.window.addOnFrameMetricsAvailableListener(listener!!, Handler(Looper.getMainLooper()))
                session = LumenSurfaceSession(current, LumenPalette.neutral(false))
                targets.forEachIndexed { index, view ->
                    val material = if (index < 4) LumenSurfaceMaterial.LIQUID else LumenSurfaceMaterial.FROSTED
                    val strength = if (index % 2 == 0) 1f else .55f
                    session.bind(view, LumenSurfaceOptions(material = material, radiusDp = 18f,
                        sampling = LumenSurfaceSampling(refractionStrength = strength)), source)
                }
            }
            val hashes = targets.map { view ->
                var hash = 0
                var previous = -1
                for (attempt in 0 until 6) {
                    WindowRegressionCapture.copy(activity, view) { image ->
                        val pixels = IntArray(image.width * image.height)
                        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                        hash = pixels.contentHashCode()
                    }
                    if (hash == previous) break
                    previous = hash
                }
                hash
            }
            var builds = 0L
            scenario.onActivity {
                it.window.removeOnFrameMetricsAvailableListener(listener!!)
                builds = session.performanceDiagnostics().runtimeShaderBuilds
                session.close()
            }
            SystemClock.sleep(50)
            assertTrue("frame metrics must arrive", draws.isNotEmpty())
            // 同一段透镜程序在进程内只编译一次：本会话至多补编 LIQUID 与 FROSTED 各一段。
            assertTrue("builds=$builds", builds <= 2)
            // 每个表面的强度不会串到别的表面：同材质两档强度的像素必须不同。
            assertTrue(hashes[0] != hashes[1] && hashes[4] != hashes[5])
            Log.i("Lumen-Diag", "lensSharing hashes=${hashes.joinToString(",") { Integer.toHexString(it) }} " +
                "builds=$builds frames=${draws.size} maxDrawMs=${draws.maxOrNull()!! / 1_000_000.0} maxAt=${draws.indexOf(draws.maxOrNull()!!)} " +
                "sumDrawMs=${draws.sum() / 1_000_000.0} top3=${draws.sortedDescending().take(3).map { it / 1_000_000 }}")
        }
    }
}
