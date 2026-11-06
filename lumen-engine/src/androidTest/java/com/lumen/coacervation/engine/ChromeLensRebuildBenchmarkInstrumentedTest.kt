package com.lumen.coacervation.engine

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.glow.GlowChromeBlurApi31
import com.lumen.coacervation.engine.liquid.LiquidChromeLensApi33
import com.lumen.coacervation.engine.liquid.LiquidEffectProfile
import com.lumen.coacervation.engine.liquid.LiquidTokenResolver
import com.lumen.coacervation.engine.liquid.LiquidVisualTuningPolicy
import com.lumen.coacervation.engine.model.LumenEffectTuning
import com.lumen.coacervation.engine.model.LumenEffectTuningPolicy
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 悬浮栏透镜的效果重建（回弹动画里光学强度逐帧变化时每帧一次）：只写几何的 effect，
 * 与共享程序要求的"先 configure 写回光学参数、再 effect"（参数不变时 configure 跳过重写；
 * alternating 是两组参数交替的最坏情况）。主线程各 3000 次，记录单次耗时分位。
 */
@SdkSuppress(minSdkVersion = 33)
@RunWith(AndroidJUnit4::class)
class ChromeLensRebuildBenchmarkInstrumentedTest {
    @Test fun configureBeforeEffectCost() {
        val parameters = LumenEffectTuningPolicy.applyTo(
            LiquidTokenResolver.resolve(LiquidVisualTuningPolicy.resolve(dark = false), LiquidEffectProfile.entries.first(), dark = false),
            LumenEffectTuning.DEFAULT)
        val other = parameters.copy(refractionAmountDp = parameters.refractionAmountDp + 1f)
        val density = 2.75f
        var report = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val lens = LiquidChromeLensApi33.shared(LiquidChromeLensApi33.CHROME)
            val blur = GlowChromeBlurApi31.create(4f * density)
            fun sample(configure: Boolean, alternate: Boolean = false): LongArray {
                val times = LongArray(3000)
                for (i in times.indices) {
                    val intensity = 1f + (i % 85) / 100f
                    val start = SystemClock.elapsedRealtimeNanos()
                    if (configure) LiquidChromeLensApi33.configure(lens, if (alternate && i % 2 == 1) other else parameters, density)
                    LiquidChromeLensApi33.effect(lens, blur, 900, 160, 60, 40f, intensity, 0f)
                    times[i] = SystemClock.elapsedRealtimeNanos() - start
                }
                return times.apply { sort() }
            }
            sample(true); sample(false) // 预热
            val rounds = (0 until 4).map { round -> if (round % 2 == 0) "plain" to sample(false) else "configured" to sample(true) } +
                ("alternating" to sample(true, alternate = true))
            report = rounds.joinToString(" ") { (name, t) -> "$name:p50=${t[1500] / 1000.0}us,p90=${t[2700] / 1000.0}us" }
        }
        Log.i("Lumen-Diag", "chromeLensRebuild $report")
    }
}
