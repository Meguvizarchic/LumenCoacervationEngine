# Meguviz 桥（可选，自 1.6.2 起提供）

`lumen-bridge-meguviz-android` 把 Lumen 局部会话的生命周期交给 MeguvizAndroid 的 `WindowSession`。它是独立的可选模块：`lumen-engine`、`lumen-motion`、`lumen-assets` 等核心模块不依赖任何 Meguviz 制品；不引入本模块的宿主，行为和体积都不变。

## 为什么只有一座桥

| Meguviz 仓库 | 与 Lumen 的关系 | 本仓库的做法 | 发布与构建状态 |
|---|---|---|---|
| MeguvizUI | 已有 `meguviz-ui-lumen-bridge`（背景属性占用 + 登记） | 不重复建桥；做兼容验证 | 尚未发布到 Maven Central，仅可源码构建 |
| MeguvizDrawable | 已有 `meguviz-drawable-lumen`（绑定所有权、幂等更新、宿主替换检测） | 不重复建桥；做兼容验证 | 尚未发布到 Maven Central，仅可源码构建 |
| MeguvizAndroid | 宿主未自带 Lumen 桥 | 提供 `lumen-bridge-meguviz-android` | `1.6.2` 已发布到 Maven Central（同时存在 JitPack `1.6.2`） |

两边功能相近的部分核对情况（2026-11-06）：

- **生命周期**：Meguviz 宿主已有 `WindowSession` + `LifecycleAdapters.bindWindow` 跟踪 Activity；`LumenSurfaceSession.forActivity` 会另在 Application 上登记一份 Activity 回调。本桥把会话直接登记在 `WindowSession` 上，不再登记第二份。
- **内存**：MeguvizAndroid 不注册 trim 回调（`MemoryBudgetObserver` 是按字节预算的快照），与 `LumenMemoryPressureHub` 没有重复。
- **帧调度**：静止页面上两套运行时都不出帧（见下文“验证与适用范围”），没有需要合并的重复调度。

## 最小接入

`lumen-bridge-meguviz-android:1.6.2` 与 `io.github.jichuo1.meguviz:meguviz-bom:1.2.0` 均已发布到 Maven Central（`lumen-*` 制品不在 `meguviz-bom` 管理范围内）。消费方直接引用制品坐标即可；在 Lumen 源码树内本地联调则使用 `project(":lumen-bridge-meguviz-android")`：

```kotlin
// build.gradle.kts：桥模块已传递引入 meguviz-android-window 与 meguviz-android-lifecycle
dependencies {
    implementation("io.github.jichuo1:lumen-bridge-meguviz-android:1.6.2")
    implementation(platform("io.github.jichuo1.meguviz:meguviz-bom:1.2.0"))
}

// Activity.onCreate（Activity 须是 LifecycleOwner，例如 AppCompatActivity）
val surfaces = openLumenSurfaces()
surfaces.bind(card, LumenSurfacePresets.card(), content)
```

一行就够：桥在本 Activity 的窗口上打开只读的 Meguviz 窗口会话（`Permission.OBSERVE`），交给 Activity 生命周期，再开 Lumen 会话。不需要写 `onDestroy`，也不需要自己调用 `LifecycleAdapters.bindWindow`。Java 调用方写 `LumenMeguvizActivity.openLumenSurfaces(activity)`。

| 情况 | 行为 |
|---|---|
| 同一 Activity 再次调用（例如写在 `onResume`） | 返回同一个未关闭的会话，不会叠出第二个 |
| 再次调用但参数不同 | 抛 `IllegalStateException`，不会悄悄忽略新参数 |
| 宿主先 `close()` 了会话 | 下一次调用重新打开 |
| 不传 `palette` | 取当前深浅色的 `LumenPalette.neutral`；Activity 自己处理 `uiMode` 变化（不重建）时自动跟随 |
| 传了 `palette` | 原样使用，不被改写 |
| 非主线程、Activity 已销毁 | 抛 `IllegalStateException` |
| Activity 不是 `LifecycleOwner`（裸 `android.app.Activity`） | 抛 `IllegalArgumentException`，提示改用 ComponentActivity 或下面的窗口会话写法 |
| Activity 销毁 | Meguviz 关闭窗口会话，Lumen 会话随之关闭 |

宿主已经自己管理 `WindowSession` 时，在它上面开会话：

```kotlin
val surfaces = window.openLumenSurfaces()   // Java：LumenMeguvizWindow.openLumenSurfaces(window)
```

这条写法的窗口会话由宿主负责关闭（通常用 `LifecycleAdapters.bindWindow`）；窗口会话已关闭或不再接受登记时抛 `IllegalStateException`，不会留下无人关闭的会话。默认配色只在创建时取一次。

不自动暂停：退到后台时 Lumen 会话本来就不工作（后台 5 秒主线程唤醒 0 次，后台 PSS 与不用桥相同），自动暂停省不下资源，回到前台还要重建一次。需要时宿主自己调用 `pause()` / `resume()`。

多窗口：每个窗口各开一个窗口会话与一个 Lumen 会话，一一绑定。

## 源码构建与本地联调

在 `LumenCoacervationEngine` 源码仓库内，为保持默认构建与不用 Meguviz 的宿主互不干扰，`settings.gradle.kts` 只在传入 `-Plumen.meguviz=true` 时才 include `:lumen-bridge-meguviz-android`。本地构建依赖 `io.github.jichuo1.meguviz:meguviz-bom:1.2.0`（从 Maven Central 直接解析）；若需与本地尚未发布的 Meguviz 改动联调，可在各成员库与 [MeguvizBom](https://github.com/Meguvizarchic/MeguvizBom) 执行 `publishToMavenLocal`，并通过 Gradle init 脚本注入 `mavenLocal()`：

```kotlin
// meguviz-local.init.gradle.kts
settingsEvaluated { dependencyResolutionManagement.repositories.mavenLocal() }
```

```sh
./gradlew -Plumen.meguviz=true \
    -I meguviz-local.init.gradle.kts \
    :sample:assembleRelease :sample:assembleReleaseAndroidTest
```

开关打开时 sample 多一个桌面入口“Lumen × Meguviz”（`MeguvizBridgeActivity`，可滚动的条纹内容上四张玻璃卡片），以及设备测试 `MeguvizBridgeTest`（生命周期与一行接入契约）和 `MeguvizBridgeBenchmarkTest`（三种写法的实测对照）。

## 验证与适用范围（2026-11-07）

Xiaomi 2210132C（API 33）；`MeguvizBridgeBenchmarkTest`，三种写法交替各 4 轮：只用 Lumen（`forActivity`，不引入 Meguviz）、Meguviz 与 Lumen 各自管理生命周期、桥接（一行接入）。

| 指标 | 只用 Lumen | 各自管理 | 桥接 |
|---|---|---|---|
| 4 张卡片的窗口像素哈希（含退到后台再回来） | 逐个相同 | 逐个相同 | 逐个相同 |
| 首帧（onCreate 到首帧提交，去掉首轮预热） | 30.7–70.5 ms | 29.0–41.6 ms | 38.8–56.2 ms |
| 滚动帧 TOTAL p50 | 8.53–11.15 ms | 10.64–11.75 ms | 10.56–11.49 ms |
| 滚动帧 TOTAL p90 | 10.43–17.46 ms | 16.95–25.68 ms | 17.33–25.44 ms |
| 静止 5 s 主线程唤醒 / 出帧 | 56–61 / 0 | 35–63 / 0 | 38–68 / 0 |
| 后台 5 s 主线程唤醒 | 0 | 0 | 0 |
| 前台 PSS / 后台 PSS | 156.3–159.2 / 81.3–82.6 MB | 156.0–158.2 / 80.9–83.6 MB | 156.0–157.0 / 80.6–82.0 MB |
| 一次接入的平均分配（40 次取均值） | 25.4 KB | 31.9–32.4 KB | 31.9–32.4 KB |
| 冷启动 `am start -W -S`（8 次） | 334–423 ms | 342–392 ms | 336–400 ms |

结论：

- `FrameScheduler` 与 `Choreographer` 没有重复调度：静止时三种写法都不出帧，主线程唤醒在同一量级（来自系统与测试本身）；后台都是 0。Meguviz 的帧调度器只在有人申请帧通道时才挂 `Choreographer` 回调，本页面没有。
- 引入 Meguviz 的固定开销是每个窗口约 7 KB 分配（窗口会话自己的调度器、资源作用域等），桥接与各自管理相同；这部分属于 Meguviz。
- 三种写法在帧耗时、内存、冷启动上没有可分辨的差别；桥的收益是接入更短、不容易用错，不提供额外的性能加速。

Activity 销毁后窗口会话与 Lumen 会话都已关闭；已关闭的窗口会话拒绝再开会话；一行接入的幂等、参数冲突、深浅色跟随与非主线程报错由 `MeguvizBridgeTest` 覆盖。

Meguviz 侧两座既有 Lumen 桥（仅源码构建，尚未发布到 Maven Central）兼容构建情况如下：

| 桥 | LCE 1.5.5 | LCE 1.6.1 | LCE 1.6.2（含 `minCompileSdk 37` 修正） |
|---|---|---|---|
| `meguviz-drawable-lumen` | 编译通过；设备契约 45/45（API 34 模拟器） | 编译失败：要求 compileSdk 37.2 | 编译与单测通过；设备契约 45/45；探针 17 行观测与 1.5.5 逐行相同 |
| `meguviz-ui-lumen-bridge` | 编译通过 | 编译失败：要求 compileSdk 37.2 | 编译通过 |

**适用范围与限制**：

- 上述基准和设备验证仅覆盖 API 33 真机与 API 34 模拟器，未覆盖 API 27–32。
- **关闭顺序**：`openLumenSurfaces()` 交给 Activity 生命周期托管，不需要手写 `onDestroy()`；若宿主直接使用 Meguviz 的 `UiScope` / `WindowSession` 或 `meguviz-drawable-lumen`，必须先关 binding、再关窗口会话，并显式 `surfaces.close()` 释放未托管的 Lumen 会话。两种入口不要混用。
