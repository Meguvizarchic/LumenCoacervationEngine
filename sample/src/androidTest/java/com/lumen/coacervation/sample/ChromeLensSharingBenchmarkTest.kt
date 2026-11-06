package com.lumen.coacervation.sample

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.LumenEngine
import com.lumen.coacervation.engine.glow.GlowBackdropTarget
import com.lumen.coacervation.engine.glow.GlowFloatingChrome
import com.lumen.coacervation.engine.glow.GlowScrollEdge
import com.lumen.coacervation.engine.host.LumenActivityDelegate
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.model.SkinId
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Activity 级悬浮栏的内容节点玻璃（Liquid 与柔光）：三条栏挂在同一个内容层上，
 * 记录每条栏的窗口像素哈希，以及主线程上重新登记一条栏（撤下再挂上）的耗时。
 * 改动前后的 APK 跑同一份测试，像素哈希必须逐个相同。
 */
@SdkSuppress(minSdkVersion = 33)
@RunWith(AndroidJUnit4::class)
class ChromeLensSharingBenchmarkTest {
    @get:org.junit.Rule val hardwareOutput = HardwareOutputRule()

    @Test fun liquidChromeLens() = run(SkinId.LIQUID)
    @Test fun frostedChromeLens() = run(SkinId.MATERIAL_YOU)

    private fun run(skin: SkinId) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val oldMaterial = LumenEngine.requestedMaterial(context)
        val oldRealtime = LumenEngine.isRealtimeCaptureEnabled(context)
        WindowRegressionCapture.main { assertTrue(LumenEngine.selectMaterial(context, skin, false)) }
        try {
            ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
                lateinit var activity: SurfaceSandboxActivity
                lateinit var material: LumenActivityDelegate
                lateinit var target: GlowBackdropTarget
                lateinit var bars: List<View>
                var chrome: GlowFloatingChrome? = null
                scenario.onActivity { a ->
                    activity = a; a.binding.close(); a.session.close()
                    material = LumenActivityDelegate(a, { LumenPalette.neutral(false) })
                    material.prepare()
                    val root = FrameLayout(a).apply { keepScreenOn = true }
                    target = GlowBackdropTarget(a)
                    target.addView(object : View(a) {
                        private val paint = Paint()
                        override fun onDraw(canvas: Canvas) {
                            for (band in 0 until 12) {
                                paint.color = Color.HSVToColor(floatArrayOf(band * 30f, .7f, .95f))
                                canvas.drawRect(0f, band * height / 12f, width.toFloat(), (band + 1) * height / 12f, paint)
                            }
                            paint.color = Color.WHITE
                            for (i in 0 until 12) canvas.drawRect(i * 40f, 0f, i * 40f + 8f, height.toFloat(), paint)
                        }
                    }, FrameLayout.LayoutParams(-1, -1))
                    root.addView(target, FrameLayout.LayoutParams(-1, -1))
                    bars = listOf(
                        View(a).apply { background = material.topBarBackground(Color.WHITE, 20f) }
                            .also { root.addView(it, FrameLayout.LayoutParams(-1, 150, Gravity.TOP).apply { setMargins(30, 60, 30, 0) }) },
                        View(a).apply { background = material.floatingBackground(Color.WHITE, 28f) }
                            .also { root.addView(it, FrameLayout.LayoutParams(-1, 170, Gravity.BOTTOM).apply { setMargins(40, 0, 40, 60) }) },
                        View(a).apply { background = material.floatingBackground(Color.WHITE, 40f) }
                            .also { root.addView(it, FrameLayout.LayoutParams(160, 160, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 50, 280) }) })
                    a.setContentView(root); assertTrue(material.bindRoot(root)); material.onStart()
                    chrome = GlowFloatingChrome(target, { material.engine }, { 0f }).apply {
                        attach(bars[0], GlowScrollEdge.TOP, Color.BLACK) {}
                        attach(bars[1], GlowScrollEdge.BOTTOM, Color.BLACK) {}
                        attach(bars[2], null, Color.BLACK) {}
                    }
                }
                try {
                    WindowRegressionCapture.frame(target)
                    SystemClock.sleep(400)
                    val before = hashes(activity, bars)
                    // 撤下再挂上：每次挂上都会新建一条栏的玻璃（改动前每次都编译一段 AGSL）。
                    val rebinds = LongArray(REBINDS)
                    WindowRegressionCapture.main {
                        val engine = material.engine!!
                        assertTrue("node backdrop", engine.supportsSurfaceBackdrop)
                        for (i in 0 until REBINDS) {
                            val bar = bars[i % bars.size]
                            val start = SystemClock.elapsedRealtimeNanos()
                            engine.setSurfaceBackdrop(bar, null)
                            engine.setSurfaceBackdrop(bar, target)
                            rebinds[i] = SystemClock.elapsedRealtimeNanos() - start
                        }
                    }
                    WindowRegressionCapture.frame(target)
                    SystemClock.sleep(400)
                    val after = hashes(activity, bars)
                    assertTrue("rebinding must not change the bars: $before vs $after", before == after)
                    val sorted = rebinds.sorted()
                    Log.i("Lumen-Diag", "chromeLens skin=$skin hashes=${before.joinToString(",") { Integer.toHexString(it) }} " +
                        "rebindMedianUs=${sorted[REBINDS / 2] / 1000} rebindP90Us=${sorted[REBINDS * 9 / 10] / 1000} " +
                        "rebindSumMs=${rebinds.sum() / 1_000_000.0} firstUs=${rebinds[0] / 1000}")
                } finally {
                    WindowRegressionCapture.main { chrome?.dispose(); material.onDestroy() }
                }
            }
        } finally {
            WindowRegressionCapture.main { assertTrue(LumenEngine.selectMaterial(context, oldMaterial, oldRealtime)) }
        }
    }

    private fun hashes(activity: SurfaceSandboxActivity, bars: List<View>): List<Int> = bars.map { view ->
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

    private companion object { const val REBINDS = 30 }
}
