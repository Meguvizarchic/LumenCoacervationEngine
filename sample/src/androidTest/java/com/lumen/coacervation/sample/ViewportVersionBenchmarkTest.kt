package com.lumen.coacervation.sample

import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.host.LumenViewportContentSource
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * `LumenViewportContentSource.contentVersion` 在每次会话 pre-draw 都被读取，并遍历来源的整棵可见子树。
 * 这里在真实窗口里搭一棵 16 × 15 = 240 个 View 的列表（含平移、缩放、旋转与滚动），
 * 测量单次读取的主线程耗时，并核对版本语义：静止时稳定，深层子 View 平移、旋转、滚动时各自改变。
 * 原始样本写到 `lumen-viewport-version-benchmark.json`，只描述当前设备。
 */
@RunWith(AndroidJUnit4::class)
class ViewportVersionBenchmarkTest {
    @get:org.junit.Rule val hardwareOutput = HardwareOutputRule()

    @Test fun contentVersionTraversalCostAndSemantics() {
        val output = JSONObject().put("schema", 1).put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("model", android.os.Build.MODEL)
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.session.close()
                val root = FrameLayout(activity)
                val owner = FrameLayout(activity)
                val rows = ArrayList<FrameLayout>()
                for (row in 0 until 16) {
                    val group = FrameLayout(activity)
                    for (cell in 0 until 14) {
                        group.addView(View(activity).apply {
                            translationX = cell * 0.5f
                            if (cell % 5 == 0) { scaleX = 1.02f; scaleY = 0.98f }
                            if (cell % 7 == 0) rotation = 1.5f
                        }, FrameLayout.LayoutParams(20, 20))
                    }
                    rows += group
                    owner.addView(group, FrameLayout.LayoutParams(320, 24))
                }
                val surface = View(activity)
                root.addView(owner, FrameLayout.LayoutParams(320, 400))
                root.addView(surface, FrameLayout.LayoutParams(320, 400))
                activity.setContentView(root)
                root.layout(0, 0, 360, 420)
                owner.layout(0, 0, 320, 400)
                rows.forEachIndexed { index, group ->
                    group.layout(0, index * 24, 320, index * 24 + 24)
                    for (cell in 0 until group.childCount) group.getChildAt(cell).layout(cell * 22, 2, cell * 22 + 20, 22)
                }
                // 表面覆盖整个来源：最重的情形，每个 View 都要走完矩阵与相交判断。
                surface.layout(0, 0, 320, 400)
                val source = LumenViewportContentSource(owner) { use -> use(surface, 0f, 0f, 8f) }
                try {
                    val first = source.contentVersion
                    assertTrue(first >= 0L)
                    assertEquals("a static tree must keep its version", first, source.contentVersion)
                    val deep = rows[9].getChildAt(6)
                    deep.translationY = 0.25f
                    val moved = source.contentVersion
                    assertNotEquals("a deep sub-pixel move must change the version", first, moved)
                    deep.rotation = 2f
                    val rotated = source.contentVersion
                    assertNotEquals(moved, rotated)
                    rows[3].scrollTo(3, 0)
                    val scrolled = source.contentVersion
                    assertNotEquals(rotated, scrolled)
                    rows[3].scrollTo(0, 0); deep.rotation = 0f; deep.translationY = 0f
                    assertEquals("restoring every pose must restore the version", first, source.contentVersion)

                    repeat(200) { source.contentVersion }
                    val samples = LongArray(400)
                    for (index in samples.indices) {
                        val started = SystemClock.elapsedRealtimeNanos()
                        source.contentVersion
                        samples[index] = SystemClock.elapsedRealtimeNanos() - started
                    }
                    val sorted = samples.sorted()
                    fun percentile(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
                    val raw = JSONArray(); samples.forEach { raw.put(it) }
                    output.put("views", 1 + rows.size * 15).put("rawNanos", raw)
                        .put("p50Nanos", percentile(.5)).put("p90Nanos", percentile(.9)).put("p99Nanos", percentile(.99))
                    Log.i("Lumen-Bench", "contentVersion views=${1 + rows.size * 15} p50=${percentile(.5)}ns " +
                        "p90=${percentile(.9)}ns p99=${percentile(.99)}ns")
                } finally { source.close() }
            }
        }
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(target.getExternalFilesDir(null), "lumen-viewport-version-benchmark.json")
        file.writeText(output.toString(2))
        assertTrue(file.length() > 100)
    }
}
