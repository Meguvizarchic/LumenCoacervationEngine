import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

// 可选桥：把 Lumen 局部会话的生命周期交给 MeguvizAndroid 的 WindowSession。核心模块不依赖本模块，
// 也不依赖任何 Meguviz 制品；只有显式引入本模块的宿主才会解析 Meguviz。
plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

group = providers.gradleProperty("lumen.group").orElse("com.lumen.coacervation.engine").get()
version = providers.gradleProperty("lumen.version").get()

android {
    namespace = "com.lumen.coacervation.engine.bridge.meguviz.android"
    compileSdk = providers.gradleProperty("lumen.compileSdk").get().toInt()
    buildToolsVersion = providers.gradleProperty("lumen.buildTools").get()
    defaultConfig {
        minSdk = providers.gradleProperty("lumen.minSdk").get().toInt()
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.all { it.workingDir = projectDir }
    }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    api(project(":lumen-engine"))
    // 家族版本只在 BOM 里写一次：window 与它传递的 runtime 都由 BOM 对齐。
    api(platform(libs.meguviz.bom))
    api(libs.meguviz.android.window)
    // 一行接入（Activity.openLumenSurfaces）把窗口会话交给 Activity 生命周期。
    api(libs.meguviz.android.lifecycle)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "lumen-bridge-meguviz-android"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("lumen-bridge-meguviz-android")
                description.set("Optional bridge that ties Lumen surface sessions to MeguvizAndroid window sessions.")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
    repositories {
        maven {
            name = "projectLocal"
            url = uri(rootProject.layout.buildDirectory.dir("repo"))
        }
    }
}
