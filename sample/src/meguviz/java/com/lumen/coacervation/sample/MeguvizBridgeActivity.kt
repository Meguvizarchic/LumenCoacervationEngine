package com.lumen.coacervation.sample

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.lumen.coacervation.engine.bridge.meguviz.android.openLumenSurfaces
import com.lumen.coacervation.engine.host.LumenSurfacePresets
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import io.github.jichuo1.meguviz.android.lifecycle.LifecycleAdapters
import io.github.jichuo1.meguviz.android.window.WindowSession
import kotlin.math.roundToInt

/**
 * Lumen × MeguvizAndroid 演示页（只在 -Plumen.meguviz=true 的构建里存在）。
 *
 * 默认（[MODE_BRIDGE]）演示桥接写法：`openLumenSurfaces()` 一行接入。基准对照另有两种写法，画面必须逐像素相同：
 * - [MODE_LUMEN]：只用 Lumen（`LumenSurfaceSession.forActivity`），不引入 Meguviz 运行时；
 * - [MODE_SEPARATE]：Meguviz 窗口会话与 Lumen 会话各自管理生命周期。
 *
 * 页面是一段可滚动的条纹内容，四张玻璃卡片随内容滚动，采样它们下方的条纹。
 */
class MeguvizBridgeActivity : AppCompatActivity() {
    var windowSession: WindowSession? = null
        private set
    lateinit var surfaces: LumenSurfaceSession
        private set
    lateinit var content: View
        private set
    lateinit var scroll: ScrollView
        private set
    val cards = ArrayList<View>()
    lateinit var mode: String
        private set
    /** 主线程 tid：测量静止期与后台的主线程唤醒（/proc/self/task/<tid>/status）。 */
    @Volatile var mainTid = 0
        private set
    var createStartNanos = 0L
        private set
    @Volatile var firstFrameNanos = 0L
        private set

    private var pausedByUser = false

    override fun onCreate(savedInstanceState: Bundle?) {
        createStartNanos = SystemClock.elapsedRealtimeNanos()
        super.onCreate(savedInstanceState)
        mainTid = Process.myTid()
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_BRIDGE
        val root = FrameLayout(this)
        scroll = ScrollView(this)
        val page = FrameLayout(this)
        content = PatternView(this)
        page.addView(content, FrameLayout.LayoutParams(-1, dp(2400)))
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)) }
        column.addView(TextView(this).apply {
            text = when (mode) { MODE_LUMEN -> "只用 Lumen"; MODE_SEPARATE -> "Lumen × Meguviz（各自管理）"; else -> "Lumen × Meguviz（桥接）" }
            textSize = 20f; setTextColor(Color.BLACK)
        })
        repeat(CARD_COUNT) { index ->
            val card = TextView(this).apply {
                text = "卡片 ${index + 1}"
                textSize = 16f; setTextColor(Color.BLACK); gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
            }
            cards += card
            column.addView(card, LinearLayout.LayoutParams(-1, dp(72)).apply { topMargin = dp(12) })
        }
        // 暂停与恢复：触控目标不小于 48dp。
        column.addView(Button(this).apply {
            text = "暂停材质"; minHeight = dp(48); minimumHeight = dp(48)
            setOnClickListener {
                pausedByUser = !pausedByUser
                if (pausedByUser) surfaces.pause() else surfaces.resume()
                text = if (pausedByUser) "恢复材质" else "暂停材质"
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        page.addView(column, FrameLayout.LayoutParams(-1, -2))
        scroll.addView(page)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        setContentView(root)

        surfaces = when (mode) {
            MODE_LUMEN -> LumenSurfaceSession.forActivity(this)
            MODE_SEPARATE -> {
                windowSession = WindowSession.open(window, this, packageName, WindowSession.Permission.OBSERVE)
                    .also { LifecycleAdapters.bindWindow(this, it) }
                LumenSurfaceSession.forActivity(this)
            }
            // 桥接：一行接入，窗口会话、生命周期与深浅色跟随都由桥负责。
            else -> openLumenSurfaces()
        }
        for (card in cards) surfaces.bind(card, LumenSurfacePresets.card(radiusDp = 18f), content)
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> surfaces.notifyScrollPositionChanged(scroll) }

        if (Build.VERSION.SDK_INT >= 29) {
            root.viewTreeObserver.registerFrameCommitCallback { firstFrameNanos = SystemClock.elapsedRealtimeNanos() }
        }
        Log.i(TAG, "mode=$mode")
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private class PatternView(context: Context) : View(context) {
        private val paint = Paint()
        override fun onDraw(canvas: Canvas) {
            val bands = 40
            for (band in 0 until bands) {
                paint.color = Color.HSVToColor(floatArrayOf(band * 27f % 360f, .55f, .98f))
                canvas.drawRect(0f, band * height / bands.toFloat(), width.toFloat(), (band + 1) * height / bands.toFloat(), paint)
            }
            paint.color = Color.WHITE
            for (i in 0 until 14) canvas.drawRect(i * 80f, 0f, i * 80f + 14f, height.toFloat(), paint)
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_LUMEN = "lumen"
        const val MODE_BRIDGE = "bridge"
        const val MODE_SEPARATE = "separate"
        const val CARD_COUNT = 4
        private const val TAG = "Lumen-Meguviz"
    }
}
