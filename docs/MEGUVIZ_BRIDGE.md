# Meguviz 桥（可选，未发布）

`lumen-bridge-meguviz-android` 把 Lumen 局部会话的生命周期交给 MeguvizAndroid 的 `WindowSession`。它是独立的可选模块：
`lumen-engine`、`lumen-motion`、`lumen-assets` 等核心模块不依赖任何 Meguviz 制品；不引入本模块的宿主，行为和体积都不变。

## 为什么只有一座桥

| Meguviz 仓库 | 与 Lumen 的关系 | 本仓库的做法 |
|---|---|---|
| MeguvizUI | 已有 `meguviz-ui-lumen-bridge`（背景属性占用 + 登记） | 不重复建桥；做兼容验证 |
| MeguvizDrawable | 已有 `meguviz-drawable-lumen`（绑定所有权、幂等更新、宿主替换检测） | 不重复建桥；做兼容验证 |
| MeguvizAndroid | 没有 Lumen 桥 | 本模块 |

两边功能相近的地方逐项看过（2026-11-06）：

- 生命周期：Meguviz 宿主已有 `WindowSession` + `LifecycleAdapters.bindWindow` 跟踪 Activity；`LumenSurfaceSession.forActivity`
  另外在 Application 上登记一份 Activity 回调。本桥改为把会话登记在窗口会话上，不再登记第二份。
- 内存：MeguvizAndroid 不注册 trim 回调（`MemoryBudgetObserver` 是按字节预算的快照），与 `LumenMemoryPressureHub` 没有重复。
- 帧调度：静止页面上两套运行时都不出帧（见下文"验证"），没有需要合并的重复调度。

## 最小接入

```kotlin
// build.gradle.kts：桥已经带上 meguviz-android-window 与 meguviz-android-lifecycle，版本由 BOM 对齐
// 1.6.2 尚未发布，桥坐标不是可解析的远程制品；在 Lumen 源码树内用 project() 依赖：
implementation(project(":lumen-bridge-meguviz-android"))
implementation(platform("io.github.jichuo1.meguviz:meguviz-bom:1.2.0"))

// Activity.onCreate（Activity 须是 LifecycleOwner，例如 AppCompatActivity）
val surfaces = openLumenSurfaces()
surfaces.bind(card, LumenSurfacePresets.card(), content)
```

一行就够：桥在本 Activity 的窗口上打开只读的 Meguviz 窗口会话（`Permission.OBSERVE`），交给 Activity 生命周期，再开 Lumen 会话。
不需要写 `onDestroy`，也不需要自己调用 `LifecycleAdapters.bindWindow`。Java 调用方写 `LumenMeguvizActivity.openLumenSurfaces(activity)`。

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

这条写法的窗口会话由宿主负责关闭（通常用 `LifecycleAdapters.bindWindow`）；窗口会话已关闭或不再接受登记时抛 `IllegalStateException`，
不会留下无人关闭的会话。默认配色只在创建时取一次。

不自动暂停：退到后台时 Lumen 会话本来就不工作（见下文"验证"：后台 5 s 主线程唤醒 0 次，后台 PSS 与不用桥相同），自动暂停省不下
资源，回到前台还要重建一次。需要时宿主自己调用 `pause()` / `resume()`。

多窗口：每个窗口各开一个窗口会话与一个 Lumen 会话，一一绑定。

## 构建

Meguviz 1.2.0 上 Maven Central 之前，本模块和演示页默认不进构建（`settings.gradle.kts` 只在 `-Plumen.meguviz=true` 时 include）。
CI、JitPack 与发布流水线都不受影响，本模块也不随 Lumen 发布。

本地联调用**统一的 `meguviz-bom:1.2.0` 本地候选仓库**（未签名、未发布，不带 `-joint` 后缀）。它是维护者联合工作区里的一个
本地 file Maven 仓库（字节来自各库自己的 publish 输出），连同把它注入依赖解析的 Gradle init 脚本，**都不在本仓库，也不在任何公开仓库里**。
拿到这两样东西后：

```sh
./gradlew -Plumen.meguviz=true \
    -I <该 init 脚本的本机路径> \
    :sample:assembleRelease :sample:assembleReleaseAndroidTest
```

`1.0.0` 是 Central 上已发布且不可变的版本，仍从 Central 解析；本地仓库只提供未发布的 `1.2.0`。
若消费方启用了依赖校验，请为 1.2.0 候选**追加** pins，不要删改任何已有 SHA。

开关打开时 sample 多一个桌面入口"Lumen × Meguviz"（`MeguvizBridgeActivity`，可滚动的条纹内容上四张玻璃卡片），以及设备测试
`MeguvizBridgeTest`（生命周期与一行接入的契约）和 `MeguvizBridgeBenchmarkTest`（三种写法的实测对照）。

## 验证（2026-11-07）

Xiaomi 2210132C（API 33）；`MeguvizBridgeBenchmarkTest`，三种写法交替各 4 轮：只用 Lumen（`forActivity`，不引入 Meguviz）、
Meguviz 与 Lumen 各自管理生命周期、桥接（一行接入）。

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

- FrameScheduler 与 Choreographer 没有重复调度：静止时三种写法都不出帧，主线程唤醒在同一量级（来自系统与测试本身）；后台都是 0。
  Meguviz 的帧调度器只在有人申请帧通道时才挂 Choreographer 回调，本页面没有。
- 引入 Meguviz 的固定开销是每个窗口约 7 KB 分配（窗口会话自己的调度器、资源作用域等），桥接与各自管理相同；这部分属于 Meguviz。
- 三种写法在帧耗时、内存、冷启动上没有可分辨的差别；桥的收益是接入更短、不容易用错，不宣称性能提升。

Activity 销毁后窗口会话与 Lumen 会话都已关闭；已关闭的窗口会话拒绝再开会话；一行接入的幂等、参数冲突、深浅色跟随与非主线程报错
由 `MeguvizBridgeTest` 守住。

默认构建（开关关闭）的 sample APK 与 `lumen-engine` AAR 与未改动的 main 逐项比对：dex、清单与资源相同，差异只有 worktree 检出的换行
（LICENSE、proguard.txt）与 AGP 的版本控制信息。

Meguviz 侧两座既有 Lumen 桥（在临时克隆里构建，不改原仓库）：

| 桥 | LCE 1.5.5 | LCE 1.6.1 | LCE main（含 `minCompileSdk` 修正） |
|---|---|---|---|
| `meguviz-drawable-lumen` | 编译通过；设备契约 45/45（API 34 模拟器） | 编译失败：要求 compileSdk 37.2 | 编译与单测通过；设备契约 45/45；探针 17 行观测与 1.5.5 逐行相同 |
| `meguviz-ui-lumen-bridge` | 编译通过 | 编译失败：要求 compileSdk 37.2 | 编译通过 |

## 未验证与限制

- 只在 API 33 真机与 API 34 模拟器上验证；未在 API 27–32 上运行本桥。
- 本模块要等 Meguviz 1.2.0 上 Central 后才能进 CI 与发布；在此之前用上面的本地 BOM 1.2.0 候选联调，Lumen 侧坐标固定为 `1.6.2`。
- **关闭顺序**：`openLumenSurfaces()` 交给 Activity 生命周期托管，不需要手写 `onDestroy()`；但这只适用于该入口。若直接使用 Meguviz 的 `UiScope`/`WindowSession`，必须按 MeguvizUI 文档先关 binding、再关窗口会话（MeguvizUI 仓库的 `docs/quickstart/03-lifecycle-and-close.md` 与
`docs/guides/bridges-android.md`），并显式 `surfaces.close()` 释放 Lumen 表面。两种入口不要混用。
