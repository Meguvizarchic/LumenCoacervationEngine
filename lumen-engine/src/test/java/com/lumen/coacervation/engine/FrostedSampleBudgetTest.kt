package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.material.LensRefractionPolicy
import com.lumen.coacervation.engine.material.ModernMaterialPolicy
import org.junit.Assert.assertTrue
import org.junit.Test

/** 柔光渲染器实时透镜缓冲的总预算：远大于真实工作集（不会在正常页面上触发），又给出明确的上限。 */
class FrostedSampleBudgetTest {
    @Test fun budgetHoldsTheRealWorkingSetWithHeadroom() {
        // 单表面最坏：采样 MAX_SAMPLE_PIXELS × 12 B，输出不大于采样 × 8 B（LiveBackdropSampler 的记账口径）。
        val worstSurface = LensRefractionPolicy.MAX_SAMPLE_PIXELS.toLong() * (12L + 8L)
        // 顶栏 + 底栏胶囊 + 三个圆按钮 + 两个浮层，按全部最坏尺寸计。
        val workingSet = worstSurface * 7
        assertTrue(workingSet <= ModernMaterialPolicy.LIVE_SAMPLE_BYTES)
        assertTrue(ModernMaterialPolicy.LIVE_SAMPLE_BYTES / worstSurface >= 17)
        assertTrue(ModernMaterialPolicy.LIVE_SAMPLE_BYTES <= 8 * 1024 * 1024)
    }
}
