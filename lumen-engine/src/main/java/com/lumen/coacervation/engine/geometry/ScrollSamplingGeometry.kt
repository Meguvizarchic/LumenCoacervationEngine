package com.lumen.coacervation.engine.geometry

/** Only a completed hardware draw records this relative source-to-target footprint. */
internal class ScrollSamplingGeometry {
    private val recorded = FloatArray(9)
    private var initialized = false

    /** 是否已有成功绘制登记过相对矩阵；未登记时 [needsRefresh] 恒为真，调用方不能跳过重算。 */
    val isInitialized: Boolean get() = initialized

    fun needsRefresh(current: FloatArray): Boolean =
        current.size >= 9 && SamplingMatrixMath.isFinite(current) &&
            (!initialized || !SamplingMatrixMath.equal(recorded, current))

    fun record(current: FloatArray) {
        if (current.size < 9 || !SamplingMatrixMath.isFinite(current)) return
        current.copyInto(recorded, endIndex = 9)
        initialized = true
    }
}
