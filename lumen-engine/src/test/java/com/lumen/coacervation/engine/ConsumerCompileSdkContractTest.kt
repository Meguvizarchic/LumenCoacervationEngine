package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 库的 AAR 元数据只要求消费者用 compileSdk 37 编译，不带小版本。
 * 本仓库自己用 37.2 编译（原生 backdrop），AGP 默认把 compileSdkMinor 写进 AAR 元数据，1.6.0/1.6.1 因此要求每个
 * 消费者也用 37.2：2026-11-06 联调时，Meguviz 的两座 Lumen 桥（compileSdk 37.0）在 1.6.1 上 checkAarMetadata 失败。
 */
class ConsumerCompileSdkContractTest {
    private val root = listOf("../build.gradle.kts", "build.gradle.kts").map(::File)
        .first { it.isFile && it.readText().contains("subprojects {") }

    @Test fun librariesDeclareTheMajorCompileSdkOnly() {
        val build = SourceContract.normalize(root.readText()).after("subprojects {").before("lint.apply {")
        val library = build.after("plugins.withId(\"com.android.library\") {")
        assertTrue(library.contains("defaultConfig.aarMetadata.minCompileSdk = providers.gradleProperty(\"lumen.compileSdk\").get().toInt()"))
        // 公开签名不得出现 37.2 才有的类型：原生 backdrop 只在内部隔离类里使用。
        val publicState = SourceContract.read("host/LumenRenderTargetBackdropState.kt")
        assertTrue(!publicState.contains("android.graphics.RenderNode"))
    }
}
