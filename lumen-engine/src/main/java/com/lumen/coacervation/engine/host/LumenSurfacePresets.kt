package com.lumen.coacervation.engine.host

import com.lumen.coacervation.engine.model.SurfaceRole

/** Starting points, not persisted choices. Every field can still be changed with copy(). */
public object LumenSurfacePresets {
    /** `@JvmOverloads`（未发布）：Java 也能写无参的 `LumenSurfacePresets.floating()`，与 `fadingBand()` / `card()` 一致。 */
    @JvmStatic @JvmOverloads
    public fun floating(material: LumenSurfaceMaterial = LumenSurfaceMaterial.FROSTED): LumenSurfaceOptions =
        LumenSurfaceOptions(material = material)

    @JvmStatic @JvmOverloads
    public fun fadingBand(hold: Float = 0.25f, end: Float = 1f, intervalMs: Long = 0L): LumenSurfaceOptions =
        LumenSurfaceOptions(role = SurfaceRole.TOP_BAR, radiusDp = 0f, edgeEnabled = false,
            sampling = LumenSurfaceSampling(refractionEnabled = false, minIntervalMs = intervalMs,
                fadeEnabled = true, fadeHold = hold, fadeEnd = end))

    /** 卡片角色的采样表面（未发布）：等价于 `LumenSurfaceOptions(material = material, role = SurfaceRole.CARD, radiusDp = radiusDp)`。 */
    @JvmStatic @JvmOverloads
    public fun card(radiusDp: Float = 16f, material: LumenSurfaceMaterial = LumenSurfaceMaterial.FROSTED): LumenSurfaceOptions =
        LumenSurfaceOptions(material = material, role = SurfaceRole.CARD, radiusDp = radiusDp)

    @JvmStatic public fun staticPanel(): LumenSurfaceOptions =
        LumenSurfaceOptions(material = LumenSurfaceMaterial.STATIC, role = SurfaceRole.MODAL,
            radiusDp = 16f, sampling = LumenSurfaceSampling(enabled = false))
}
