package com.lumen.coacervation.engine

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumen.coacervation.engine.material.LensRefractionPolicy
import com.lumen.coacervation.engine.material.LensRemapTables
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * 透镜重映射：每次新建映射表（此前每个作业都这样）与按表面缓存的映射表。底栏典型尺寸（含外沿的采样图
 * 136×44、输出 120×28），各 2000 次，记录单次耗时分位与分配字节，并核对两条路径输出逐位相同。
 */
@RunWith(AndroidJUnit4::class)
class LensRemapTablesBenchmarkInstrumentedTest {
    private val sw = 136; private val sh = 44; private val margin = 8
    private val dw = 120; private val dh = 28

    private fun allocated(): Long = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: -1L

    @Test fun cachedTablesRemoveThePerJobAllocationAndLensWork() {
        val random = Random(3)
        val source = IntArray(sw * sh) { (0xFF shl 24) or random.nextInt(0x1000000) }
        val fresh = IntArray(dw * dh)
        val cached = IntArray(dw * dh)
        val tables = LensRemapTables()
        LensRefractionPolicy.remap(source, sw, sh, margin, fresh, dw, dh, 1f)
        LensRefractionPolicy.remap(source, sw, sh, margin, cached, dw, dh, 1f, tables)
        assertArrayEquals(fresh, cached)

        fun measure(block: () -> Unit): Pair<LongArray, Long> {
            repeat(300) { block() }
            val samples = LongArray(2000)
            val before = allocated()
            for (index in samples.indices) {
                val started = SystemClock.elapsedRealtimeNanos()
                block()
                samples[index] = SystemClock.elapsedRealtimeNanos() - started
            }
            val bytes = allocated() - before
            samples.sort()
            return samples to bytes
        }
        val (freshTimes, freshBytes) = measure { LensRefractionPolicy.remap(source, sw, sh, margin, fresh, dw, dh, 1f) }
        val (cachedTimes, cachedBytes) = measure { LensRefractionPolicy.remap(source, sw, sh, margin, cached, dw, dh, 1f, tables) }
        assertArrayEquals(fresh, cached)
        assertEquals("unchanged geometry never rebuilds", 1, tables.builds)
        fun p(values: LongArray, q: Double) = values[((values.size - 1) * q).toInt()]
        Log.i("Lumen-Bench", "lensRemap fresh p50=${p(freshTimes, .5)}ns p90=${p(freshTimes, .9)}ns bytes/2000=$freshBytes " +
            "cached p50=${p(cachedTimes, .5)}ns p90=${p(cachedTimes, .9)}ns bytes/2000=$cachedBytes")
        if (freshBytes >= 0 && cachedBytes >= 0) assertTrue(cachedBytes < freshBytes)
    }
}
