package com.lumen.coacervation.engine.host

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import androidx.core.graphics.ColorUtils

/**
 * 把一块前景装饰固定在所属 View 的视口上，而不是跟着内容滚动（未发布，新增公开 API）。
 *
 * View 绘制前把画布平移了 `(-scrollX, -scrollY)`；背景由框架自己补偿，前景没有。单行输入框输入长文本后
 * `scrollX > 0`，未补偿的描边会整体左移、与背景错位。包装后只在它（可经 `RippleDrawable` 等容器间接持有）
 * 真正作为 `View.foreground` 时补偿有符号的 `scrollX/scrollY`；挂在背景上时原样绘制，不会重复平移。
 * 不挂监听、不分配，逐帧只做一次画布平移。
 */
public object LumenViewportForeground {
    /** 返回包装后的 Drawable；已经包装过的原样返回。 */
    @JvmStatic
    public fun wrap(drawable: Drawable): Drawable =
        if (drawable is ViewportForegroundDrawable) drawable else ViewportForegroundDrawable(drawable)
}

/**
 * 控件描边：聚焦或按下时加粗加深（未发布，新增公开 API）。与 [LumenActivityDelegate.controlOutline] 同一结构与取值，
 * 但不需要委托：宿主自己的主题或配色（例如自有 Activity 基类）直接传入强调色。
 * 结果已用 [LumenViewportForeground] 包装，作为前景安装时随控件视口固定。
 */
public object LumenControlOutline {
    /**
     * @param accentColor 描边颜色（通常是 `LumenPalette.primary`）；alpha 由引擎按状态覆盖
     * @param radiusDp 圆角，dp
     * @param emphasized 常态也使用强调透明度（例如已选中的条目）
     */
    @JvmStatic
    @JvmOverloads
    public fun create(context: Context, accentColor: Int, radiusDp: Float, emphasized: Boolean = false): Drawable =
        create(context.resources.displayMetrics.density, accentColor, radiusDp, emphasized)

    internal fun create(density: Float, accentColor: Int, radiusDp: Float, emphasized: Boolean): Drawable {
        fun border(active: Boolean) = GradientDrawable().apply {
            cornerRadius = radiusDp * density
            setColor(Color.TRANSPARENT)
            setStroke(((if (active) 2f else 1f) * density).toInt().coerceAtLeast(1),
                ColorUtils.setAlphaComponent(accentColor, if (active || emphasized) 0x72 else 0x22))
        }
        return ViewportForegroundDrawable(StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), border(true))
            addState(intArrayOf(android.R.attr.state_pressed), border(true))
            addState(intArrayOf(), border(false))
        })
    }
}
