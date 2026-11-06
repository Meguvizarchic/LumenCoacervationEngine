package com.lumen.coacervation.sample

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
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
 * 最小接入就是 onCreate 里的三行：打开窗口会话、交给 Activity 生命周期、在窗口会话上开 Lumen 会话。
 * 不需要 onDestroy：Activity 销毁时 Meguviz 关闭窗口会话，登记在它上面的 Lumen 会话随之关闭。
 *
 * [EXTRA_MODE] = [MODE_SEPARATE] 时改用两套各自管理生命周期的写法（`LumenSurfaceSession.forActivity`），
 * 供基准对照：两种写法的画面必须逐像素相同。
 */
class MeguvizBridgeActivity : AppCompatActivity() {
    lateinit var windowSession: WindowSession
        private set
    lateinit var surfaces: LumenSurfaceSession
        private set
    lateinit var content: View
        private set
    val cards = ArrayList<View>()
    /** onCreate 开始到两套会话与全部绑定就绪的主线程耗时、期间的分配字节；首帧提交时刻。 */
    var setupNanos = 0L
        private set
    var setupAllocatedBytes = 0L
        private set
    var createStartNanos = 0L
        private set
    @Volatile var firstFrameNanos = 0L
        private set

    private var pausedByUser = false

    override fun onCreate(savedInstanceState: Bundle?) {
        createStartNanos = SystemClock.elapsedRealtimeNanos()
        super.onCreate(savedInstanceState)
        val separate = intent.getStringExtra(EXTRA_MODE) == MODE_SEPARATE
        val root = FrameLayout(this)
        content = PatternView(this)
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)) }
        val title = TextView(this).apply {
            text = if (separate) "Lumen × Meguviz（两套各自管理）" else "Lumen × Meguviz（桥接）"
            textSize = 20f; setTextColor(Color.BLACK)
        }
        column.addView(title)
        repeat(CARD_COUNT) { index ->
            val card = TextView(this).apply {
                text = "卡片 ${index + 1}"
                textSize = 16f; setTextColor(Color.BLACK); gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
                // 卡片只是材质展示，文字已可朗读；玻璃本身不额外占用无障碍焦点。
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }
            cards += card
            column.addView(card, LinearLayout.LayoutParams(-1, dp(72)).apply { topMargin = dp(12) })
        }
        // 暂停与恢复由宿主决定（窗口会话没有可订阅的可见性事件）；触控目标不小于 48dp。
        val toggle = Button(this).apply {
            text = "暂停材质"; minHeight = dp(48); minimumHeight = dp(48)
            setOnClickListener {
                pausedByUser = !pausedByUser
                if (pausedByUser) surfaces.pause() else surfaces.resume()
                text = if (pausedByUser) "恢复材质" else "暂停材质"
            }
        }
        column.addView(toggle, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        root.addView(column, FrameLayout.LayoutParams(-1, -2))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        setContentView(root)

        val allocatedBefore = allocated()
        val start = SystemClock.elapsedRealtimeNanos()
        windowSession = WindowSession.open(window, this, packageName, WindowSession.Permission.OBSERVE)
        LifecycleAdapters.bindWindow(this, windowSession)
        surfaces = if (separate) LumenSurfaceSession.forActivity(this) else windowSession.openLumenSurfaces()
        for (card in cards) surfaces.bind(card, LumenSurfacePresets.card(radiusDp = 18f), content)
        setupNanos = SystemClock.elapsedRealtimeNanos() - start
        setupAllocatedBytes = allocated() - allocatedBefore

        if (Build.VERSION.SDK_INT >= 29) {
            root.viewTreeObserver.registerFrameCommitCallback { firstFrameNanos = SystemClock.elapsedRealtimeNanos() }
        }
        Log.i(TAG, "mode=${if (separate) MODE_SEPARATE else MODE_BRIDGE} setupUs=${setupNanos / 1000} setupAllocB=$setupAllocatedBytes")
    }

    private fun allocated(): Long = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: -1L
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private class PatternView(context: android.content.Context) : View(context) {
        private val paint = Paint()
        override fun onDraw(canvas: Canvas) {
            for (band in 0 until 12) {
                paint.color = Color.HSVToColor(floatArrayOf(band * 30f, .55f, .98f))
                canvas.drawRect(0f, band * height / 12f, width.toFloat(), (band + 1) * height / 12f, paint)
            }
            paint.color = Color.WHITE
            for (i in 0 until 14) canvas.drawRect(i * 80f, 0f, i * 80f + 14f, height.toFloat(), paint)
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_BRIDGE = "bridge"
        const val MODE_SEPARATE = "separate"
        const val CARD_COUNT = 4
        private const val TAG = "Lumen-Meguviz"
    }
}
