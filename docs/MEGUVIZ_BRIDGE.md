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
// build.gradle.kts
implementation("com.github.jichuo1.LumenCoacervationEngine:lumen-bridge-meguviz-android:<版本>")
implementation(platform("io.github.jichuo1.meguviz:meguviz-bom:1.2.0"))
implementation("io.github.jichuo1.meguviz:meguviz-android-lifecycle")

// Activity.onCreate
val window = WindowSession.open(getWindow(), this, packageName, WindowSession.Permission.OBSERVE)
LifecycleAdapters.bindWindow(this, window)
val surfaces = window.openLumenSurfaces()          // 配色默认跟随当前深浅色
surfaces.bind(card, LumenSurfacePresets.card(), content)
```

不需要写 `onDestroy`：Activity 销毁时 Meguviz 关闭窗口会话，登记在它上面的 Lumen 会话随之关闭。Java 调用方写
`LumenMeguvizWindow.openLumenSurfaces(window)`。

规则：

- 在窗口会话的所有者线程（主线程）调用。窗口会话已关闭或不再接受登记时抛 `IllegalStateException`，不会留下无人关闭的会话。
- 暂停与恢复由宿主决定（`surfaces.pause()` / `resume()`）。`WindowSession` 没有可订阅的可见性事件，桥不猜测。
- 宿主自己处理深浅色变化、不重建 Activity（`configChanges="uiMode"`）时，在 `onConfigurationChanged` 里先
  `window.refreshConfiguration()`，再 `surfaces.updatePalette(LumenPalette.neutral(night))`；默认配色只在创建时取一次。
- 多窗口：每个窗口各开一个 `WindowSession` 与一个 Lumen 会话，桥把两者一一绑定。

## 构建

Meguviz 1.2.0 上 Maven Central 之前，本模块和演示页默认不进构建（`settings.gradle.kts` 只在 `-Plumen.meguviz=true` 时 include）。
CI、JitPack 与发布流水线都不受影响，本模块也不随 Lumen 发布。本地联调用 MeguvizFamily 生成 staging 解析脚本：

```sh
python D:/DevArea/MeguvizFamily/family.py init-script --only android --output <dir>/family-android.init.gradle.kts
python D:/DevArea/MeguvizFamily/family.py bom --output <bom-repo>/io/github/jichuo1/meguviz/meguviz-bom/1.2.0/meguviz-bom-1.2.0.pom
./gradlew -Plumen.meguviz=true -I <dir>/family-android.init.gradle.kts -I <解析 bom-repo 的 init 脚本> \
    :sample:assembleRelease :sample:assembleReleaseAndroidTest
```

开关打开时 sample 多一个桌面入口"Lumen × Meguviz"（`MeguvizBridgeActivity`）和设备测试 `MeguvizBridgeTest`。

## 验证（2026-11-06）

Xiaomi 2210132C（API 33）；`MeguvizBridgeTest`，桥接与"两套各自管理"（`forActivity` + 窗口会话）交替各 3 轮、跑 2 次：

| 指标 | 桥接 | 两套各自管理 |
|---|---|---|
| 4 张卡片的窗口像素哈希 | 逐个相同 | 逐个相同 |
| 接入耗时（打开窗口会话到全部绑定） | 0.74–0.92 ms | 0.75–2.91 ms |
| 静止 1.5 s 的出帧数 | 0 | 0 |
| 冷启动 `am start -W` TotalTime（5 次，去掉首次预热） | 323–372 ms | 321–402 ms |
| PSS / Java 堆 | 140.2–140.5 MB / 3.3 MB | 139.5–140.5 MB / 3.3 MB |

两种写法没有可测量的性能差别；本桥的收益是接入更短、关闭顺序不会写错，不宣称性能提升。Activity 销毁后窗口会话与 Lumen 会话都已关闭；
已关闭的窗口会话拒绝再开会话。

默认构建（开关关闭）的 sample APK 与 `lumen-engine` AAR 与未改动的 main 逐项比对：dex、清单与资源相同，差异只有 worktree 检出的换行
（LICENSE、proguard.txt）与 AGP 的版本控制信息。

Meguviz 侧两座既有 Lumen 桥（在临时克隆里构建，不改原仓库）：

| 桥 | LCE 1.5.5 | LCE 1.6.1 | LCE main（含 `minCompileSdk` 修正） |
|---|---|---|---|
| `meguviz-drawable-lumen` | 编译通过；设备契约 45/45（API 34 模拟器） | 编译失败：要求 compileSdk 37.2 | 编译与单测通过；设备契约 45/45；探针 17 行观测与 1.5.5 逐行相同 |
| `meguviz-ui-lumen-bridge` | 编译通过 | 编译失败：要求 compileSdk 37.2 | 编译通过 |

## 未验证与限制

- 只在 API 33 真机与 API 34 模拟器上验证；未在 API 27–32 上运行本桥。
- 本模块要等 Meguviz 1.2.0 上 Central 后才能进 CI 与发布；坐标中的 `<版本>` 届时确定。
