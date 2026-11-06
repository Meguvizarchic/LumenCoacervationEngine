import com.android.build.api.dsl.CommonExtension
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // 只把 KGP 放上 buildscript classpath，**不 apply**：AGP 9 的内置 Kotlin 读它的版本决定编译器，
    // 不声明就退回 AGP 自带的旧编译器。任何模块真的 apply 它，AGP 会让出内置 Kotlin。
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

subprojects {
    plugins.withId("com.android.base") {
        // Source-contract tests read text directly. Bytecode inputs alone cannot invalidate a cached
        // test after a comment/whitespace-only change, or a cross-module contract source change.
        val contractSources = files(fileTree("src/main"))
        if (name == "lumen-engine") contractSources.from(rootProject.fileTree("lumen-controls/src/main"))
        if (name == "lumen-motion") contractSources.from(rootProject.fileTree("lumen-engine/src/main"))
        tasks.withType<Test>().configureEach {
            inputs.files(contractSources).withPropertyName("sourceContractInputs")
                .withPathSensitivity(PathSensitivity.RELATIVE)
        }
        // Module compileSdk setters clear the minor level, so set it after each module's DSL.
        extensions.configure<AndroidComponentsExtension<*, *, *>>("androidComponents") {
            finalizeDsl { dsl -> (dsl as CommonExtension).compileSdkMinor = providers.gradleProperty("lumen.compileSdkMinor").get().toInt() }
        }
        // 只有本仓库自己编译需要 37.2（原生 backdrop 在内部按完整版本门控，公开签名不含 37.2 类型）。AGP 默认把
        // compileSdkMinor 写进 AAR 元数据，迫使每个消费者也用 37.2 编译；这里显式只要求 37。
        plugins.withId("com.android.library") {
            extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
                defaultConfig.aarMetadata.minCompileSdk = providers.gradleProperty("lumen.compileSdk").get().toInt()
            }
        }
        extensions.configure<CommonExtension>("android") {
            lint.apply {
                checkReleaseBuilds = false
                checkTestSources = false
                ignoreTestSources = true
            }
        }
        tasks.matching {
            it.name.contains("Lint") && (it.name.contains("UnitTest") || it.name.contains("AndroidTest"))
        }.configureEach {
            enabled = false
        }
    }
}
