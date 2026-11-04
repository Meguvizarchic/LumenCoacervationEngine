package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宿主曾因 `ViewportForegroundDrawable` 不公开、`controlOutline` 绑定委托配色而复制引擎代码（BIL PR #41）。
 * 公开入口与委托必须是同一份实现，不能再分叉出两套描边。
 */
class ControlOutlineContractTest {
    @Test fun delegateOutlineIsTheSharedPublicImplementation() {
        val delegate = SourceContract.read("host/LumenActivityDelegate.kt")
        val outline = delegate.after("public fun controlOutline(").before("// ---------------- 回弹")
        assertTrue(outline.contains("LumenControlOutline.create(density, palette.primary, radiusDp, emphasized)"))
        assertFalse(outline.contains("GradientDrawable"))
    }

    @Test fun publicOutlineIsPinnedToTheViewportAndWrapIsIdempotent() {
        val source = SourceContract.read("host/LumenControlOutline.kt")
        val create = source.after("internal fun create(density: Float")
        assertTrue(create.contains("return ViewportForegroundDrawable(StateListDrawable()"))
        assertTrue(create.contains("state_focused") && create.contains("state_pressed"))
        assertTrue(create.contains("if (active || emphasized) 0x72 else 0x22"))
        val wrap = source.after("public fun wrap(drawable: Drawable)").before("public object LumenControlOutline")
        assertTrue(wrap.contains("if (drawable is ViewportForegroundDrawable) drawable else ViewportForegroundDrawable(drawable)"))
        // 补偿本身只在一处：包装类按真实前景所有者平移。
        val wrapper = SourceContract.read("host/ViewportForegroundDrawable.kt")
        assertTrue(wrapper.contains("view?.foreground === outer) view.scrollX"))
    }
}
