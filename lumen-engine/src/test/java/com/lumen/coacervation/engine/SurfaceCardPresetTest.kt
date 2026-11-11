package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.host.LumenSurfaceMaterial
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.host.LumenSurfacePresets
import com.lumen.coacervation.engine.model.SurfaceRole
import org.junit.Assert.assertEquals
import org.junit.Test

/** card 预设逐字段等于手写选项：渲染走完全相同的路径。 */
class SurfaceCardPresetTest {
    @Test fun cardPresetEqualsTheHandWrittenOptions() {
        assertEquals(LumenSurfaceOptions(material = LumenSurfaceMaterial.FROSTED, role = SurfaceRole.CARD, radiusDp = 20f),
            LumenSurfacePresets.card(20f))
        assertEquals(LumenSurfaceOptions(material = LumenSurfaceMaterial.LIQUID, role = SurfaceRole.CARD, radiusDp = 20f),
            LumenSurfacePresets.card(20f, LumenSurfaceMaterial.LIQUID))
        assertEquals(LumenSurfaceOptions(role = SurfaceRole.CARD, radiusDp = 16f), LumenSurfacePresets.card())
    }

    /**
     * 每个预设的全默认形式都必须能从 Java 写成无参静态调用（Kotlin 的默认参数在 Java 里不可见，要靠 `@JvmOverloads`）。
     * 反射拿到的就是 Java 调用方链接的那个方法；结果与 Kotlin 的默认调用逐字段相等。
     */
    @Test fun everyPresetHasAZeroArgumentStaticFormForJava() {
        val kotlinDefaults = mapOf(
            "floating" to LumenSurfacePresets.floating(),
            "fadingBand" to LumenSurfacePresets.fadingBand(),
            "card" to LumenSurfacePresets.card(),
            "staticPanel" to LumenSurfacePresets.staticPanel(),
        )
        for ((name, expected) in kotlinDefaults) {
            val method = LumenSurfacePresets::class.java.getMethod(name)
            assertEquals("$name() must be static", true, java.lang.reflect.Modifier.isStatic(method.modifiers))
            assertEquals(name, expected, method.invoke(null))
        }
        assertEquals(LumenSurfaceOptions(), LumenSurfacePresets.floating())
        assertEquals(LumenSurfaceOptions(material = LumenSurfaceMaterial.LIQUID), LumenSurfacePresets.floating(LumenSurfaceMaterial.LIQUID))
    }
}
