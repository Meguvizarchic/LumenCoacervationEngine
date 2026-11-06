package com.lumen.coacervation.sample

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Window
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.bridge.meguviz.android.openLumenSurfaces
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import io.github.jichuo1.meguviz.android.window.WindowSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lumen × MeguvizAndroid 桥（只在 -Plumen.meguviz=true 的构建里存在）。
 * 两种写法（桥接 / 两套各自管理）交替各 [ROUNDS] 轮：卡片窗口像素逐个相同；记录接入耗时、分配、首帧与静止期出帧数。
 * 生命周期：Activity 销毁后窗口会话与 Lumen 会话都已关闭；已关闭的窗口会话拒绝再开会话。
 */
@RunWith(AndroidJUnit4::class)
class MeguvizBridgeTest {
    @get:org.junit.Rule val hardwareOutput = HardwareOutputRule()

    private class Round(val mode: String, val hashes: List<Int>, val setupUs: Long, val allocB: Long, val firstFrameMs: Double, val idleFrames: Int)

    private fun run(mode: String): Round {
        val intent = Intent(InstrumentationRegistry.getInstrumentation().targetContext, MeguvizBridgeActivity::class.java)
            .putExtra(MeguvizBridgeActivity.EXTRA_MODE, mode)
        lateinit var surfaces: LumenSurfaceSession
        lateinit var window: WindowSession
        val round = ActivityScenario.launch<MeguvizBridgeActivity>(intent).use { scenario ->
            lateinit var activity: MeguvizBridgeActivity
            scenario.onActivity { activity = it; surfaces = it.surfaces; window = it.windowSession }
            val hashes = activity.cards.map { card ->
                var hash = 0
                var previous = -1
                for (attempt in 0 until 6) {
                    WindowRegressionCapture.copy(activity, card) { image ->
                        val pixels = IntArray(image.width * image.height)
                        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                        hash = pixels.contentHashCode()
                    }
                    if (hash == previous) break
                    previous = hash
                }
                hash
            }
            // 静止页面：两套运行时都不应在没有内容变化时持续出帧。
            var frames = 0
            val listener = Window.OnFrameMetricsAvailableListener { _, _, _ -> frames++ }
            WindowRegressionCapture.main { activity.window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper())) }
            SystemClock.sleep(IDLE_MS)
            WindowRegressionCapture.main { activity.window.removeOnFrameMetricsAvailableListener(listener) }
            val firstFrameMs = if (activity.firstFrameNanos > 0) (activity.firstFrameNanos - activity.createStartNanos) / 1e6 else -1.0
            Round(mode, hashes, activity.setupNanos / 1000, activity.setupAllocatedBytes, firstFrameMs, frames)
        }
        WindowRegressionCapture.main {
            assertTrue("$mode: Lumen session must close with the Activity", surfaces.isClosed)
            assertTrue("$mode: window session must close with the Activity", window.lifecycle().isClosed)
        }
        return round
    }

    @Test fun bridgedAndSeparateWiringDrawTheSamePixelsAndCloseTogether() {
        val rounds = ArrayList<Round>()
        repeat(ROUNDS) {
            rounds += run(MeguvizBridgeActivity.MODE_BRIDGE)
            rounds += run(MeguvizBridgeActivity.MODE_SEPARATE)
        }
        val reference = rounds.first().hashes
        for (round in rounds) assertEquals("${round.mode} pixels", reference, round.hashes)
        for (mode in listOf(MeguvizBridgeActivity.MODE_BRIDGE, MeguvizBridgeActivity.MODE_SEPARATE)) {
            val mine = rounds.filter { it.mode == mode }
            Log.i("Lumen-Meguviz", "mode=$mode setupUs=${mine.map { it.setupUs }} allocB=${mine.map { it.allocB }} " +
                "firstFrameMs=${mine.map { "%.1f".format(it.firstFrameMs) }} idleFrames=${mine.map { it.idleFrames }} " +
                "hashes=${reference.joinToString(",") { Integer.toHexString(it) }}")
        }
    }

    @Test fun aClosedWindowSessionRefusesANewLumenSession() {
        val intent = Intent(InstrumentationRegistry.getInstrumentation().targetContext, MeguvizBridgeActivity::class.java)
        ActivityScenario.launch<MeguvizBridgeActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val window = WindowSession.open(activity.window, activity, "lumen-test", WindowSession.Permission.OBSERVE)
                val session = window.openLumenSurfaces()
                assertFalse(session.isClosed)
                window.close()
                assertTrue("closing the window session closes its Lumen session", session.isClosed)
                try {
                    window.openLumenSurfaces()
                    fail("a closed window session must not hand out a session nobody would close")
                } catch (expected: IllegalStateException) { }
            }
        }
    }

    private companion object {
        const val ROUNDS = 3
        const val IDLE_MS = 1500L
    }
}
