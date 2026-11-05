package com.lumen.coacervation.sample

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.host.LumenSurfaceMaterial
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `LumenSurfaceSession.ensure`：宿主每次刷新都调用的幂等绑定。语义与宿主手写的
 * “isBound + 背景归属 + update/重新绑定” 对账相同；基准比较两种写法在 8 个表面上的主线程耗时。
 */
@RunWith(AndroidJUnit4::class)
class SurfaceEnsureTest {
    @get:org.junit.Rule val hardwareOutput = HardwareOutputRule()

    private class Stage(val root: FrameLayout, val source: View, val other: View, val views: List<View>)

    private fun install(activity: SurfaceSandboxActivity): Stage {
        activity.binding.close(); activity.session.close()
        val root = FrameLayout(activity)
        val source = View(activity).apply { setBackgroundColor(Color.rgb(40, 90, 160)) }
        root.addView(source, FrameLayout.LayoutParams(320, 400))
        val other = View(activity).apply { setBackgroundColor(Color.rgb(160, 90, 40)) }
        root.addView(other, FrameLayout.LayoutParams(320, 400))
        val views = (0 until 8).map { index ->
            View(activity).apply { setPadding(3, 5, 7, 11) }.also {
                root.addView(it, FrameLayout.LayoutParams(140, 40).apply { topMargin = index * 48 })
            }
        }
        activity.setContentView(root)
        return Stage(root, source, other, views)
    }

    private fun options(dark: Boolean) = LumenSurfaceOptions(material = LumenSurfaceMaterial.STATIC, radiusDp = 8f,
        color = if (dark) 0xFF181A20.toInt() else 0xFFF8F9FC.toInt(), tintOpacity = .9f, fallbackTintOpacity = .9f)

    @Test fun ensureReusesUpdatesAndRebindsExactlyWhenTheHostReconcileWould() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val stage = install(activity)
                val session = LumenSurfaceSession(activity, LumenPalette.neutral(false))
                try {
                    val view = stage.views[0]
                    val first = session.ensure(view, options(false), stage.source)
                    val installed = view.background
                    assertTrue(first.isBound)
                    assertEquals(listOf(3, 5, 7, 11), listOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom))
                    // 相同选项：同一个绑定、同一块背景。
                    assertSame(first, session.ensure(view, options(false), stage.source))
                    assertSame(installed, view.background)
                    // 选项变化：就地更新，不重新绑定。
                    val updated = session.ensure(view, options(true), stage.source)
                    assertSame(first, updated)
                    assertSame(installed, view.background)
                    // 宿主换掉了背景：重新包裹宿主的新背景，旧绑定失效。
                    val hostBackground = ColorDrawable(Color.RED)
                    view.background = hostBackground
                    val rebound = session.ensure(view, options(true), stage.source)
                    assertNotSame(first, rebound)
                    assertFalse(first.isBound)
                    assertTrue(rebound.isBound)
                    assertNotSame(hostBackground, view.background)
                    rebound.close()
                    assertSame("closing restores the host's replacement, not the first original", hostBackground, view.background)
                    // 关闭后再次 ensure 等于重新绑定。
                    val again = session.ensure(view, options(true), stage.source)
                    assertTrue(again.isBound)
                    assertNotSame(rebound, again)
                    // 来源变化：重新绑定。
                    val otherSource = session.ensure(view, options(true), stage.other)
                    assertNotSame(again, otherSource)
                    assertFalse(again.isBound)
                    // 无来源 ⇄ 有来源也要区分。
                    val noSource = session.ensure(view, options(true))
                    assertNotSame(otherSource, noSource)
                    assertSame(noSource, session.ensure(view, options(true)))
                } finally { session.close() }
                assertFalse("a closed session hands back an unbound handle", session.ensure(stage.views[1], options(false)).isBound)
            }
        }
    }

    @Test fun ensureVersusHandWrittenReconcileOnEightSurfaces() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val stage = install(activity)
                val session = LumenSurfaceSession(activity, LumenPalette.neutral(false))
                try {
                    val opts = options(false)
                    // 宿主现有写法（BIL HostOpusChrome / HostPageChrome / HostReadingSurfaces 同形）。
                    val bindings = HashMap<View, LumenSurfaceBinding>()
                    val owned = HashMap<View, android.graphics.drawable.Drawable>()
                    fun handWritten() {
                        for (view in stage.views) {
                            val binding = bindings[view]
                            if (binding?.isBound != true || owned[view] !== view.background) {
                                binding?.close()
                                val padding = intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
                                bindings[view] = session.bind(view, opts, stage.source)
                                view.background?.let { owned[view] = it }
                                view.setPadding(padding[0], padding[1], padding[2], padding[3])
                            } else binding.update(opts)
                        }
                    }
                    fun ensured() { for (view in stage.views) session.ensure(view, opts, stage.source) }
                    fun measure(block: () -> Unit): LongArray {
                        repeat(300) { block() }
                        return LongArray(400) {
                            val started = SystemClock.elapsedRealtimeNanos(); block(); SystemClock.elapsedRealtimeNanos() - started
                        }.also { it.sort() }
                    }
                    val hand = measure(::handWritten)
                    // ensure 接管同一批已绑定的表面：首轮全部沿用（不重新绑定）。
                    val before = stage.views.map { it.background }
                    ensured()
                    assertEquals(before, stage.views.map { it.background })
                    val short = measure(::ensured)
                    fun p(values: LongArray, q: Double) = values[((values.size - 1) * q).toInt()]
                    Log.i("Lumen-Bench", "reconcile8 hand p50=${p(hand, .5)}ns p90=${p(hand, .9)}ns " +
                        "ensure p50=${p(short, .5)}ns p90=${p(short, .9)}ns")
                } finally { session.close() }
            }
        }
    }
}
