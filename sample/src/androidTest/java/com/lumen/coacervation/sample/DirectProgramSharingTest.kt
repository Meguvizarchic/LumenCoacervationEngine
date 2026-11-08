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
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.host.LumenSurfaceCorners
import com.lumen.coacervation.engine.host.LumenSurfaceEnhancements
import com.lumen.coacervation.engine.host.LumenSurfaceGeometryOptions
import com.lumen.coacervation.engine.host.LumenSurfaceLightOptions
import com.lumen.coacervation.engine.host.LumenSurfaceMaterial
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSampling
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.model.LumenPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 局部增强的直接着色（API 33+ 的 DIRECT_BITMAP 路径）：8 个参数各不相同的增强表面在同一会话里，
 * 记录每个表面的窗口像素哈希、绑定后各帧绘制耗时与着色器构建次数；再全部关闭、重新绑定一遍，像素必须与第一遍逐个相同。
 * 改动前后的 APK 跑同一份测试，像素哈希必须逐个相同（基线 APK 传 `-e lumenBaseline true` 跳过构建次数断言）。
 */
@SdkSuppress(minSdkVersion = 33)
@RunWith(AndroidJUnit4::class)
class DirectProgramSharingTest {
    @get:org.junit.Rule val hardwareOutput = HardwareOutputRule()

    private fun enhancements(index: Int) = LumenSurfaceEnhancements(
        geometry = LumenSurfaceGeometryOptions(cornersEnabled = true,
            corners = LumenSurfaceCorners(4f + index * 3f, 24f - index * 2f, 6f + index, 18f),
            shadowEnabled = index % 3 == 0),
        light = LumenSurfaceLightOptions(enabled = index % 2 == 0, angleDegrees = 30f * index))

    @Test fun enhancedSurfacesPixelsAcrossRebind() {
        val baseline = InstrumentationRegistry.getArguments().getString("lumenBaseline") == "true"
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var session: LumenSurfaceSession
            lateinit var targets: List<View>
            lateinit var source: View
            lateinit var activity: SurfaceSandboxActivity
            var bindings = emptyList<LumenSurfaceBinding>()
            val draws = ArrayList<Long>()
            var listener: Window.OnFrameMetricsAvailableListener? = null
            fun bindAll() {
                bindings = targets.mapIndexed { index, view ->
                    val material = if (index < 4) LumenSurfaceMaterial.LIQUID else LumenSurfaceMaterial.FROSTED
                    session.bind(view, LumenSurfaceOptions(material = material, radiusDp = 18f,
                        sampling = LumenSurfaceSampling(refractionStrength = if (index % 2 == 0) 1f else .55f)), source, enhancements(index))
                }
            }
            scenario.onActivity { current ->
                activity = current
                current.binding.close(); current.session.close()
                val root = FrameLayout(current)
                source = object : View(current) {
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
                bindAll()
            }
            fun stableHashes(): List<Int> = targets.map { view ->
                var hash = 0
                var previous = -1
                for (attempt in 0 until 8) {
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
            val first = stableHashes()
            var backends = emptyList<LumenSurfaceBackend?>()
            var firstBuilds = 0L
            val firstDraws = synchronized(draws) { ArrayList(draws) }
            scenario.onActivity {
                backends = bindings.map { it.diagnostics()?.backend }
                firstBuilds = session.performanceDiagnostics().runtimeShaderBuilds
                bindings.forEach(LumenSurfaceBinding::close)
                bindAll()
            }
            val second = stableHashes()
            var totalBuilds = 0L
            scenario.onActivity {
                it.window.removeOnFrameMetricsAvailableListener(listener!!)
                totalBuilds = session.performanceDiagnostics().runtimeShaderBuilds
                session.close()
            }
            SystemClock.sleep(50)
            Log.i("Lumen-Diag", "directSharing baseline=$baseline hashes=${first.joinToString(",") { Integer.toHexString(it) }} " +
                "rebind=${second.joinToString(",") { Integer.toHexString(it) }} backends=$backends builds=$firstBuilds/$totalBuilds " +
                "firstFrames=${firstDraws.size} firstMaxDrawMs=${(firstDraws.maxOrNull() ?: 0L) / 1_000_000.0} " +
                "firstSumDrawMs=${firstDraws.sum() / 1_000_000.0} top3=${firstDraws.sortedDescending().take(3).map { it / 1_000 }}us")
            assertTrue("frame metrics must arrive", firstDraws.isNotEmpty())
            assertTrue("every surface must take the GPU direct path: $backends", backends.all { it == LumenSurfaceBackend.GPU })
            // 每个表面的参数不会串到别的表面：同材质、不同角与强度的像素必须不同。
            assertEquals(8, first.toSet().size)
            // 关闭后重绑：共享程序的输入被换成占位再写回，像素必须与第一遍逐个相同。
            assertEquals(first, second)
            if (!baseline) {
                // 进程内只编译一次：本会话至多补编一次（同进程先前的测试可能已编译过），重绑不再编译。
                assertTrue("builds=$firstBuilds", firstBuilds <= 1)
                assertEquals(firstBuilds, totalBuilds)
            }
        }
    }
}
