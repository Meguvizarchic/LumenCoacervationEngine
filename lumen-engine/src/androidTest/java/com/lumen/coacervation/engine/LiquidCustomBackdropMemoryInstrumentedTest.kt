package com.lumen.coacervation.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumen.coacervation.engine.liquid.LiquidBackdropSizingPolicy
import com.lumen.coacervation.engine.liquid.LiquidBackdropSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors

/**
 * 实时档（crispRefraction）自定义背景不再生成光学副本：折射与根绘制本来就读清晰底图，副本从不绘制。
 * 核对：实时档准备字节只有底图本身、绘制像素与底图逐位相同；标准档仍带软化副本。
 * 同时记录两种准备的耗时与字节（标准档的工作量等于此前实时档的工作量）。
 */
@RunWith(AndroidJUnit4::class)
class LiquidCustomBackdropMemoryInstrumentedTest {
    private val fullWidth = 1440
    private val fullHeight = 3200
    private val density = 4f

    private fun customBitmap(): Bitmap {
        val size = LiquidBackdropSizingPolicy.resolve(fullWidth, fullHeight)
        return Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            val paint = Paint()
            for (band in 0 until 16) {
                paint.color = Color.HSVToColor(floatArrayOf(band * 22.5f, .7f, .9f))
                canvas.drawRect(0f, band * bitmap.height / 16f, bitmap.width.toFloat(), (band + 1) * bitmap.height / 16f, paint)
            }
        }
    }

    private fun prepare(crisp: Boolean): Pair<LiquidBackdropSource, Long> {
        val worker = Executors.newSingleThreadExecutor()
        try {
            return worker.submit<Pair<LiquidBackdropSource, Long>> {
                val bitmap = customBitmap()
                val started = SystemClock.elapsedRealtimeNanos()
                val source = LiquidBackdropSource.fromCustomBitmap(bitmap, "asset", fullWidth, fullHeight, density, crisp)
                source to SystemClock.elapsedRealtimeNanos() - started
            }.get()
        } finally { worker.shutdown() }
    }

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    @Test fun realtimeCustomBackdropHoldsOnlyTheCrispImageAndDrawsIdentically() {
        val crispTimes = LongArray(7); val softTimes = LongArray(7)
        var crispBytes = 0L; var softBytes = 0L
        for (round in crispTimes.indices) {
            val (crisp, crispNanos) = prepare(crisp = true)
            val (soft, softNanos) = prepare(crisp = false)
            crispTimes[round] = crispNanos; softTimes[round] = softNanos
            val bitmapBytes = crisp.bitmap.allocationByteCount.toLong()
            crispBytes = crisp.preparedByteCount
            softBytes = soft.preparedByteCount
            // preparedByteCount = 底图 + 光学副本（不同于底图时）：实时档只有底图一张，标准档两张。
            assertEquals(bitmapBytes, crisp.preparedByteCount)
            assertEquals(2 * bitmapBytes, soft.preparedByteCount)
            assertEquals(crisp.bitmap.width, crisp.refractionWidth)
            assertEquals(crisp.bitmap.height, crisp.refractionHeight)
            if (round == 0) {
                // 折射输入（后端 RuntimeShader 的 content）：实时档就是清晰底图本身；根绘制同理。
                val out = Bitmap.createBitmap(crisp.bitmap.width, crisp.bitmap.height, Bitmap.Config.ARGB_8888)
                Canvas(out).drawRect(0f, 0f, out.width.toFloat(), out.height.toFloat(), Paint().apply { shader = crisp.bitmapShader })
                assertArrayEquals(pixels(crisp.bitmap), pixels(out))
                crisp.drawRoot(Canvas(out), Rect(0, 0, out.width, out.height), 255)
                assertArrayEquals(pixels(crisp.bitmap), pixels(out))
                // 标准档仍读软化副本（与清晰底图不同）。
                Canvas(out).drawRect(0f, 0f, out.width.toFloat(), out.height.toFloat(), Paint().apply { shader = soft.bitmapShader })
                assertTrue(!pixels(soft.bitmap).contentEquals(pixels(out)))
                out.recycle()
            }
            crisp.markPublished(); soft.markPublished()
            crisp.close(); soft.close()
        }
        crispTimes.sort(); softTimes.sort()
        Log.i("Lumen-Bench", "customBackdrop crisp p50=${crispTimes[3] / 1000}us bytes=$crispBytes " +
            "softened p50=${softTimes[3] / 1000}us bytes=$softBytes")
        assertTrue(crispBytes < softBytes)
    }
}
