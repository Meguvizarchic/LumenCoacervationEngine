package com.lumen.coacervation.sample

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import android.view.animation.LinearInterpolator
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.bridge.meguviz.android.openLumenSurfaces
import com.lumen.coacervation.engine.host.LumenSurfacePresets
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import io.github.jichuo1.meguviz.android.lifecycle.LifecycleAdapters
import io.github.jichuo1.meguviz.android.window.WindowSession
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 三种写法（只用 Lumen / Meguviz 与 Lumen 各自管理 / 桥接）交替各 [ROUNDS] 轮，真机实测：
 * 首帧、滚动帧耗时 p50/p90、静止期与后台的主线程唤醒与出帧、前台/后台 PSS、接入一次的平均分配字节；
 * 卡片窗口像素在三种写法之间、以及退到后台再回来之后都必须逐个相同。
 */
@RunWith(AndroidJUnit4::class)
class MeguvizBridgeBenchmarkTest {
    @get:org.junit.Rule val hardwareOutput = HardwareOutputRule()

    private class Round(val mode: String, val hashes: List<Int>, val resumedHashes: List<Int>, val firstFrameMs: Double,
        val scrollP50: Double, val scrollP90: Double, val idleWakeups: Long, val idleFrames: Int, val bgWakeups: Long,
        val pssKb: Long, val bgPssKb: Long, val setupAllocB: Long, val setupUs: Long)

    private fun wakeups(tid: Int): Long = File("/proc/self/task/$tid/status").readLines()
        .filter { it.startsWith("voluntary_ctxt_switches") || it.startsWith("nonvoluntary_ctxt_switches") }
        .sumOf { it.substringAfter(':').trim().toLong() }

    private fun hashes(activity: MeguvizBridgeActivity): List<Int> = activity.cards.map { card ->
        var hash = 0
        var previous = -1
        for (attempt in 0 until 8) {
            WindowRegressionCapture.copy(activity, card) { image ->
                val pixels = IntArray(image.width * image.height)
                image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                hash = pixels.contentHashCode()
            }
            if (hash == previous) break
            previous = hash
            SystemClock.sleep(150)
        }
        hash
    }

    private fun percentile(values: List<Long>, p: Double): Double =
        values.sorted().let { if (it.isEmpty()) -1.0 else it[((it.size - 1) * p).toInt()] / 1e6 }

    /** 一次接入（打开、绑定四张卡片）再关闭，主线程上重复 [n] 次，返回每次的平均分配字节与耗时。 */
    private fun setupCost(activity: MeguvizBridgeActivity, mode: String, n: Int): Pair<Long, Long> {
        fun once() {
            var window: WindowSession? = null
            val session = when (mode) {
                MeguvizBridgeActivity.MODE_LUMEN -> LumenSurfaceSession(activity, com.lumen.coacervation.engine.model.LumenPalette.neutral(false))
                MeguvizBridgeActivity.MODE_SEPARATE -> {
                    window = WindowSession.open(activity.window, activity, "bench", WindowSession.Permission.OBSERVE)
                    LifecycleAdapters.bindWindow(activity, window)
                    LumenSurfaceSession(activity, com.lumen.coacervation.engine.model.LumenPalette.neutral(false))
                }
                else -> {
                    window = WindowSession.open(activity.window, activity, "bench", WindowSession.Permission.OBSERVE)
                    LifecycleAdapters.bindWindow(activity, window)
                    window.openLumenSurfaces()
                }
            }
            val probes = activity.cards.map { android.view.View(activity) }
            for (probe in probes) session.bind(probe, LumenSurfacePresets.card(radiusDp = 18f), activity.content)
            session.close(); window?.close()
        }
        repeat(3) { once() }
        val allocated = { Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: 0L }
        val before = allocated(); val start = SystemClock.elapsedRealtimeNanos()
        repeat(n) { once() }
        return (allocated() - before) / n to (SystemClock.elapsedRealtimeNanos() - start) / n / 1000
    }

    private fun run(mode: String): Round {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(instrumentation.targetContext, MeguvizBridgeActivity::class.java).putExtra(MeguvizBridgeActivity.EXTRA_MODE, mode)
        return ActivityScenario.launch<MeguvizBridgeActivity>(intent).use { scenario ->
            lateinit var activity: MeguvizBridgeActivity
            scenario.onActivity { activity = it }
            WindowRegressionCapture.frame(activity.cards.last())
            SystemClock.sleep(1000)
            val firstFrameMs = (activity.firstFrameNanos - activity.createStartNanos) / 1e6
            val hashes = hashes(activity)

            // 静止期：主线程唤醒与出帧。
            var frames = 0
            val counter = Window.OnFrameMetricsAvailableListener { _, _, _ -> frames++ }
            WindowRegressionCapture.main { activity.window.addOnFrameMetricsAvailableListener(counter, Handler(Looper.getMainLooper())) }
            val w0 = wakeups(activity.mainTid)
            SystemClock.sleep(IDLE_MS)
            val idleWakeups = wakeups(activity.mainTid) - w0
            WindowRegressionCapture.main { activity.window.removeOnFrameMetricsAvailableListener(counter) }
            val idleFrames = frames

            // 滚动：固定 1.6 s 线性滚动 1600dp 再滚回，逐帧 TOTAL_DURATION。
            val totals = ArrayList<Long>()
            val metrics = Window.OnFrameMetricsAvailableListener { _, m, _ -> synchronized(totals) { totals += m.getMetric(FrameMetrics.TOTAL_DURATION) } }
            val done = CountDownLatch(1)
            WindowRegressionCapture.main {
                activity.window.addOnFrameMetricsAvailableListener(metrics, Handler(Looper.getMainLooper()))
                val distance = (1600 * activity.resources.displayMetrics.density).toInt()
                ValueAnimator.ofInt(0, distance, 0).apply {
                    duration = SCROLL_MS; interpolator = LinearInterpolator()
                    addUpdateListener { activity.scroll.scrollTo(0, it.animatedValue as Int) }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) { done.countDown() }
                    })
                    start()
                }
            }
            done.await(10, TimeUnit.SECONDS)
            SystemClock.sleep(500)
            WindowRegressionCapture.main { activity.window.removeOnFrameMetricsAvailableListener(metrics) }
            val scrollFrames = synchronized(totals) { totals.toList() }

            val memory = { Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss.toLong() }
            Runtime.getRuntime().gc(); SystemClock.sleep(300)
            val pss = memory()

            // 后台：停止 3 s 后测唤醒与 PSS，再回到前台，像素必须与之前相同。
            scenario.moveToState(Lifecycle.State.CREATED)
            SystemClock.sleep(3000)
            val b0 = wakeups(activity.mainTid)
            SystemClock.sleep(IDLE_MS)
            val bgWakeups = wakeups(activity.mainTid) - b0
            Runtime.getRuntime().gc(); SystemClock.sleep(300)
            val bgPss = memory()
            scenario.moveToState(Lifecycle.State.RESUMED)
            WindowRegressionCapture.frame(activity.cards.last())
            SystemClock.sleep(800)
            val resumed = hashes(activity)

            var cost = 0L to 0L
            WindowRegressionCapture.main { cost = setupCost(activity, mode, SETUP_REPEATS) }
            Round(mode, hashes, resumed, firstFrameMs, percentile(scrollFrames, .5), percentile(scrollFrames, .9),
                idleWakeups, idleFrames, bgWakeups, pss, bgPss, cost.first, cost.second)
        }
    }

    @Test fun measureLumenOnlySeparateAndBridged() {
        val modes = listOf(MeguvizBridgeActivity.MODE_LUMEN, MeguvizBridgeActivity.MODE_SEPARATE, MeguvizBridgeActivity.MODE_BRIDGE)
        val rounds = ArrayList<Round>()
        repeat(ROUNDS) { round -> for (mode in if (round % 2 == 0) modes else modes.reversed()) rounds += run(mode) }
        val reference = rounds.first().hashes
        for (round in rounds) {
            assertEquals("${round.mode} pixels", reference, round.hashes)
            assertEquals("${round.mode} pixels after returning from the background", reference, round.resumedHashes)
        }
        for (mode in modes) {
            val mine = rounds.filter { it.mode == mode }
            fun <T> col(f: (Round) -> T) = mine.map(f)
            Log.i("Lumen-Meguviz", "bench mode=$mode firstFrameMs=${col { "%.1f".format(it.firstFrameMs) }} " +
                "scrollP50=${col { "%.2f".format(it.scrollP50) }} scrollP90=${col { "%.2f".format(it.scrollP90) }} " +
                "idleWakeups=${col { it.idleWakeups }} idleFrames=${col { it.idleFrames }} bgWakeups=${col { it.bgWakeups }} " +
                "pssKb=${col { it.pssKb }} bgPssKb=${col { it.bgPssKb }} setupAllocB=${col { it.setupAllocB }} setupUs=${col { it.setupUs }}")
        }
        Log.i("Lumen-Meguviz", "bench hashes=${reference.joinToString(",") { Integer.toHexString(it) }}")
    }

    private companion object {
        const val ROUNDS = 4
        const val IDLE_MS = 5000L
        const val SCROLL_MS = 1600L
        const val SETUP_REPEATS = 40
    }
}
