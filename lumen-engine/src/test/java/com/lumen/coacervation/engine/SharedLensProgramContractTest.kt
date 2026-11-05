package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 局部会话的透镜程序在进程内共享（每段 AGSL 只编译一次）。共享成立的前提由这里守住：
 * 每次建效果前写全本表面的强度与光学参数，Prism 在 effect 里写全自己的 uniform。
 */
class SharedLensProgramContractTest {
    private val capture = SourceContract.read("host/SurfaceCaptureApi31.kt")

    @Test fun eachProgramIsCompiledOncePerProcess() {
        val cache = capture.after("class LensCache {").before("class Glass(")
        for (name in listOf("liquid", "frosted", "prism"))
            assertEquals(name, 1, cache.split("SharedPrograms.$name = it; counters?.shaderCompiled()").size - 1)
        assertTrue(cache.contains("private object SharedPrograms {"))
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
