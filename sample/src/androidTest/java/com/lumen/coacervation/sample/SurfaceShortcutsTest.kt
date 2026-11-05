package com.lumen.coacervation.sample

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.host.LumenSurfaceMaterial
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.model.LumenPalette
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 第 7 轮的简写入口与原写法逐像素相同：`forActivity`（含默认配色）对比显式构造，`withTint` 对比两参数 copy；
 * 并核对 `forActivity` 随 Activity 销毁自动关闭、注销回调。
 */
@RunWith(AndroidJUnit4::class)
class SurfaceShortcutsTest {
    @get:org.junit.Rule val hardwareOutput = HardwareOutputRule()

    private class Stage(val source: View, val target: View)

    private fun install(activity: SurfaceSandboxActivity): Stage {
        activity.binding.close(); activity.session.close()
        val root = FrameLayout(activity)
        val source = object : View(activity) {
            private val paint = Paint()
            override fun onDraw(canvas: Canvas) {
                paint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
                    intArrayOf(Color.RED, Color.YELLOW, Color.BLUE), null, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                paint.shader = null; paint.color = Color.WHITE
                for (i in 0 until 8) canvas.drawRect(i * 40f, 30f, i * 40f + 14f, 170f, paint)
            }
        }
        val target = View(activity)
        root.addView(source, FrameLayout.LayoutParams(320, 220))
        root.addView(target, FrameLayout.LayoutParams(220, 90).apply { leftMargin = 40; topMargin = 60 })
        activity.setContentView(root)
        return Stage(source, target)
    }

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    /** 在同一位置先后用两种写法绑定同一个表面，各截一次窗口像素。 */
    private fun render(scenario: ActivityScenario<SurfaceSandboxActivity>, bind: (SurfaceSandboxActivity, Stage) -> Pair<LumenSurfaceSession, LumenSurfaceBinding>): IntArray {
        lateinit var activity: SurfaceSandboxActivity
        lateinit var stage: Stage
        lateinit var session: LumenSurfaceSession
        scenario.onActivity { activity = it; stage = install(it); session = bind(it, stage).first }
        var result = IntArray(0)
        try {
            // 软件采样有节流与收尾补采：等两轮截图稳定后再取。
            var previous = IntArray(0)
            for (attempt in 0 until 6) {
                WindowRegressionCapture.copy(activity, stage.target) { image -> result = pixels(image) }
                if (result.contentEquals(previous)) break
                previous = result
            }
            assertTrue("the capture must settle", result.contentEquals(previous))
        } finally { WindowRegressionCapture.main { session.close() } }
        return result
    }

    @Test fun shortcutsRenderExactlyLikeTheExplicitCalls() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            val frosted = LumenSurfaceOptions(material = LumenSurfaceMaterial.FROSTED, radiusDp = 12f, color = 0xFFF8F9FC.toInt())
            val explicitTint = frosted.copy(tintOpacity = .42f, fallbackTintOpacity = .42f)
            assertEquals("withTint is field-for-field the two-argument copy", explicitTint, frosted.withTint(.42f))
            var dark = false
            scenario.onActivity { dark = it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES }
            val palette = LumenPalette.neutral(dark)

            val reference = render(scenario) { activity, stage ->
                LumenSurfaceSession(activity, palette).let { it to it.bind(stage.target, explicitTint, stage.source) }
            }
            val shortcut = render(scenario) { activity, stage ->
                LumenSurfaceSession.forActivity(activity).let { it to it.bind(stage.target, frosted.withTint(.42f), stage.source) }
            }
            assertTrue("the fixture must actually show the sampled source", reference.toSet().size > 16)
            assertArrayEquals("forActivity (default palette) + withTint must render the same pixels", reference, shortcut)
        }
    }

    @Test fun forActivitySessionClosesWithTheActivity() {
        lateinit var session: LumenSurfaceSession
        lateinit var binding: LumenSurfaceBinding
        lateinit var target: View
        var original: Any? = null
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val stage = install(activity)
                target = stage.target
                original = target.background
                session = LumenSurfaceSession.forActivity(activity)
                binding = session.bind(target, LumenSurfaceOptions(), stage.source)
                assertTrue(binding.isBound)
                assertNotSame(original, target.background)
                assertNotNull(closer(session))
            }
        }
        // ActivityScenario.close() 已销毁 Activity：会话随之关闭、背景还原、回调注销。
        WindowRegressionCapture.main {
            assertTrue(session.diagnostics().closed)
            assertFalse(binding.isBound)
            assertSame(original, target.background)
            assertNull("the lifecycle callback must be unregistered", closer(session))
            session.close()
        }
    }

    private fun closer(session: LumenSurfaceSession): Any? =
        LumenSurfaceSession::class.java.getDeclaredField("activityCloser").apply { isAccessible = true }.get(session)
}
