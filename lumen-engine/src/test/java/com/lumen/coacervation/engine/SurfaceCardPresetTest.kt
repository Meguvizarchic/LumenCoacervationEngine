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
}
