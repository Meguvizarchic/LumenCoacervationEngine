package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 局部会话与 Activity 悬浮栏的透镜程序在进程内共享（每段 AGSL 只编译一次）。共享成立的前提由这里守住：
 * 每次建效果前写全本表面的强度与光学参数，Prism 在 effect 里写全自己的 uniform。
 */
class SharedLensProgramContractTest {
    private val capture = SourceContract.read("host/SurfaceCaptureApi31.kt")

    private val liquidChrome = SourceContract.read("liquid/LiquidChromeBackdropApi31.kt")
    private val frostedChrome = SourceContract.read("material/FrostedChromeGlassApi31.kt")

    @Test fun eachProgramIsCompiledOncePerProcess() {
        val cache = capture.after("class LensCache {").before("class Glass(")
        assertEquals(1, cache.split("SharedPrograms.prism = it; counters?.shaderCompiled()").size - 1)
        assertTrue(cache.contains("private object SharedPrograms {"))
        // LIQUID 与 FROSTED 的透镜程序由透镜对象自己持有：局部会话与 Activity 悬浮栏各一份，避免两类参数交替重写。
        assertTrue(cache.contains("LiquidChromeLensApi33.shared(LiquidChromeLensApi33.SURFACES) { counters?.shaderCompiled() }"))
        assertTrue(cache.contains("FrostedChromeLensApi33.shared(FrostedChromeLensApi33.SURFACES) { counters?.shaderCompiled() }"))
        val liquidShared = liquidChrome.after("object LiquidChromeLensApi33 {").before("fun configure(")
        assertEquals(1, liquidShared.split("RuntimeShader(ROUNDED_RECT_REFRACTION_SHADER)").size - 1)
        assertTrue(liquidShared.contains("programs[user] ?: RuntimeShader(ROUNDED_RECT_REFRACTION_SHADER).also { programs[user] = it; onCompiled?.invoke() }"))
        assertTrue(frostedChrome.contains("programs[user] ?: create().also { programs[user] = it; onCompiled?.invoke() }"))
    }

    @Test fun activityChromeGlassUsesTheSharedProgramsAndRewritesItsParameters() {
        val liquid = liquidChrome.after("fun create(parameters: LiquidParameters, density: Float)").before("object LiquidChromeLensApi33")
        assertTrue(liquid.contains("LiquidChromeLensApi33.shared(LiquidChromeLensApi33.CHROME)"))
        val liquidConfigure = liquid.indexOf("LiquidChromeLensApi33.configure(lens, parameters, density)")
        assertTrue(liquidConfigure >= 0 && liquidConfigure < liquid.indexOf("LiquidChromeLensApi33.effect(lens"))
        val frosted = frostedChrome.after("fun create(density: Float)").before("object FrostedChromeLensApi33")
        assertTrue(frosted.contains("FrostedChromeLensApi33.shared(FrostedChromeLensApi33.CHROME)"))
        val frostedConfigure = frosted.indexOf("FrostedChromeLensApi33.configure(lens, 1f)")
        assertTrue(frostedConfigure >= 0 && frostedConfigure < frosted.indexOf("FrostedChromeLensApi33.effect(lens"))
        // configure 只写强度相关的 uniform：其余 uniform 在共享程序创建时写一次，任何使用方都不改。
        val configure = frostedChrome.after("fun configure(lens: Any, strength: Float)").before("fun effect(")
        for (name in listOf("rimStart", "luminanceGain", "luminanceBias")) assertTrue(name, !configure.contains("\"$name\""))
        // 光学 uniform 只由 configure 写：effect 不碰它们，参数没变时跳过重写才成立。
        val liquidEffect = liquidChrome.after("fun effect(").before("RenderEffect.createChainEffect")
        for (name in listOf("refractionHeight", "refractionAmount", "chromaticShift", "scatteringRadius", "specularStrength", "stretchSampling"))
            assertTrue(name, !liquidEffect.contains("\"$name\""))
    }

    @Test fun chainRewritesThisSurfacesParametersBeforeEveryEffect() {
        val chain = capture.after("private fun chain(").before("override fun close()")
        val liquid = chain.indexOf("LiquidChromeLensApi33.configure(lens, liquidParameters, density)")
        assertTrue(liquid >= 0 && liquid < chain.indexOf("LiquidChromeLensApi33.effect(lens"))
        val frosted = chain.indexOf("FrostedChromeLensApi33.configure(lens, lensStrength)")
        assertTrue(frosted >= 0 && frosted < chain.indexOf("FrostedChromeLensApi33.effect(lens"))
        // Prism 的 effect 自己写全形状与配方相关的 uniform。
        val prism = SourceContract.read("prism/PrismLensApi33.kt").after("fun effect(").before("RenderEffect.createChainEffect")
        for (name in listOf("size", "offset", "radius", "refractionHeight", "refractionAmount", "depthEffect", "dispersion"))
            assertTrue(name, prism.contains("setFloatUniform(\"$name\""))
    }
}
