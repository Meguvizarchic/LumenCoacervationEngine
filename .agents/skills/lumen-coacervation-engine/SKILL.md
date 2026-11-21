---
name: lumen-coacervation-engine
description: 为 Android View 及局部 Compose 工程接入或迁移凝光视效引擎（LumenCoacervationEngine 1.6.2）。当工程需要玻璃/磨砂/折射/亚克力/云母/棱镜材质、长按弹性形变、锚点弹窗与预测式返回、动画资产会话，或与 MeguvizAndroid WindowSession 联合托管时触发；关键词包括 LumenSurfaceSession、LumenActivityDelegate、LumenElasticInteraction、LumenModalPresenter、openLumenSurfaces、LumenEngine。通用计算与视频帧调度转交 MeguvizCompute / MeguvizMedia，纯 Drawable 适配器转交 MeguvizDrawable，声明式 UiScope 节点管理转交 MeguvizUI。
---

# LumenCoacervationEngine 接入与迁移

LumenCoacervationEngine 为 Android View 应用（`minSdk 27`、`compileSdk 37+`）提供柔光/液态玻璃材质、长按弹性形变、弹窗与全屏过渡，并支持局部 Compose 原型和 MeguvizAndroid 生命周期桥接。

## 第 1 阶段：盘点工程现状

改代码前先读取目标工程以下文件，并输出一份简短评估（接入模式、SDK 基线、潜在冲突）：

1. **构建与依赖**：读取 `gradle/libs.versions.toml`、`settings.gradle.kts`、模块 `build.gradle.kts` 与 `gradle.properties`，确认 `minSdk >= 27`、`compileSdk >= 37`（注意 `1.6.0`/`1.6.1` 曾误要求 `37.2`，`1.6.2` 已改回 `37`）、Java/Kotlin 编译目标 `>= 17`。
2. **宿主结构与清单**：检查 `AndroidManifest.xml`（`enableOnBackInvokedCallback="true"`、目标页透明窗口主题）与 Activity 基类（裸 `Activity`、`AppCompatActivity`、`ComponentActivity` 或自有基类）。
3. **现有视效与冲突点**：盘点自绘模糊、`RenderEffect`、`PixelCopy`、`dispatchTouchEvent` 拦截、手工绑定缓存表、旧版 `onTrimMemory` 转发以及是否已引入 `MeguvizAndroid`。
4. **单引擎进程红线**：确认工程未通过 shade 或包名重定位（relocate）引入两份引擎。`LumenEngine`、`SkinRepository`、`SkinRenderSessionRegistry` 与 `LumenMemoryPressureHub` 为进程级单例，共存两份引擎会互相覆盖状态机。

## 第 2 阶段：选择模块与接入依赖

`1.6.2` 已发布到 Maven Central（`io.github.jichuo1:<模块>:1.6.2`，**不在 `meguviz-bom:1.2.0` 内**）与 JitPack（`com.github.jichuo1.LumenCoacervationEngine:<模块>:1.6.2`）。按实际场景只引入必需模块，禁止顺手添加未使用的可选桥或播放器运行时：

| 场景 | 坐标（Maven Central） | 边界说明 |
|---|---|---|
| 局部表面、Fluent/Mica/Prism、全页委托 | `io.github.jichuo1:lumen-engine:1.6.2` | 必需核心，仅依赖 AndroidX core |
| 长按弹性、锚点弹窗、全屏形变、翻页/滑块 | `io.github.jichuo1:lumen-motion:1.6.2` | 依赖 `lumen-engine`，无 AppCompat 依赖 |
| AppCompat 控件换装、`RecyclerView` 拖拽排序 | `io.github.jichuo1:lumen-controls:1.6.2` | 唯一引入 AppCompat/RecyclerView 的模块 |
| 局部 Compose 材质与长按弹性 | `io.github.jichuo1:lumen-compose:1.6.2` | **仅局部原型**；不含全页托管、Modal/预测返回 |
| 薄膜/纸张/能量/粒子独立装饰 | `io.github.jichuo1:lumen-effects:1.6.2` | 挂在 `ViewOverlay`，不采样背后内容 |
| 统一动画资产播放与格式后端 | `io.github.jichuo1:lumen-assets:1.6.2` + `-lottie`/`-pag`/`-rive` | 按格式单选；PAG/Rive 含原生 `.so` |
| 与 `MeguvizAndroid` 窗口会话联合托管 | `io.github.jichuo1:lumen-bridge-meguviz-android:1.6.2` | 需配合 `platform("io.github.jichuo1.meguviz:meguviz-bom:1.2.0")` |

> **兄弟桥发布状态边界**：`lumen-bridge-meguviz-android:1.6.2` 已发布到 Maven Central；Meguviz 侧的 `meguviz-ui-lumen-bridge` 与 `meguviz-drawable-lumen` 尚未发布到 Maven Central，仅可源码构建。

## 第 3 阶段：执行最小迁移与生命周期对齐

根据盘点结果选择以下三种主模式之一（不要混搭重复托管）：

### 模式 A：局部表面装饰（`LumenSurfaceSession`，推荐起点）

适用于只装饰顶栏、底栏、悬浮卡片或独立弹窗，不接管 Activity 根背景的场景：

```kotlin
// Activity 内推荐（自动随深浅色取中性配色，并在 super.onDestroy() 内关闭会话）
val session = LumenSurfaceSession.forActivity(this)
// 刷新主题或状态时幂等调用 ensure，取代手写 bindings map 与 padding 保存
session.ensure(topBar, LumenSurfacePresets.floating(), content)
// 滚动容器在实际位移后传实际滚动的 View
scrollView.setOnScrollChangeListener { v, _, _, _, _ -> session.notifyScrollPositionChanged(v) }
```

Java 调用方可直接写无参 `LumenSurfacePresets.floating()` 或 `LumenSurfacePresets.card()`。

### 模式 B：全页宿主托管（`LumenActivityDelegate`）

适用于统一管理整页材质、悬浮栏可读性、弹性手势与锚点弹窗：

1. 在 Activity 基类组合持有 `LumenActivityDelegate`（裸 `android.app.Activity` 可直接继承 `LumenActivity`）。
2. 在授权门与早退分支**之后**调用 `lumen.prepare()`，在 `setContentView` **之后**调用 `lumen.bindRoot(root) { recreate() }`。
3. 严格按时机转发六个回调：`dispatchTouchEvent`（`super` 前）→ `onStart`（`super` 后）→ `onStop`（`super` 前）→ `onTrimMemory`/`onLowMemory`（`super` 前）→ `onDestroy`（`super` 前放入 `try/finally`）。
4. 接入动效与弹窗时：用 `LumenElasticInteraction.dispatch` 包住原 `super.dispatchTouchEvent`，所有弹窗经 `LumenModalPresenter` 构造与关闭（多级面板在点击瞬间 `captureSubPanel`），弹性与阴影容器设 `clipChildren = false`、`clipToPadding = false`。
5. 销毁顺序：先释放业务监听器与 `GlowFloatingChrome`、`PageTextChain`、`LumenNavigationBar`、`LumenSlider`、`LumenModalPresenter`、`ContainerMorphLauncher.clear`、`LumenElasticInteraction.dispose()`，再调用 `lumen.onDestroy()`，最后 `super.onDestroy()`。

### 模式 C：与 Meguviz 联合托管（`openLumenSurfaces`）

`LifecycleOwner` Activity 引入 `lumen-bridge-meguviz-android` 后，可一行绑定到只读 `WindowSession`：

```kotlin
val surfaces = openLumenSurfaces() // 已有 WindowSession 时改为 windowSession.openLumenSurfaces()
surfaces.ensure(card, LumenSurfacePresets.card(radiusDp = 18f), content)
```

若同时使用 `MeguvizUI` 的 `UiScope` 或 `MeguvizDrawable` 适配器，销毁时必须先关 binding，再关 `WindowSession`；未使用 `openLumenSurfaces()` 托管的会话须显式 `surfaces.close()`。

### 模式 D：局部 Compose 原型（`lumen-compose`）

仅用于单个窗口内的局部材质和长按弹性（不支持全页托管、Modal 形变、预测返回或原生回弹反采样）：

- 每个 `LumenComposeHost` 在同层创建独立的 `rememberLumenComposeState(palette)`，Dialog 内需单独创建。
- Modifier 顺序固定为 `.lumenElastic().lumenSurface(state, options, enhancements, key)`；输入框、滑块与业务长按控件设 `lumenElastic(enabled = false)`。

### 核心工程铁律

- **线程与对象归属**：除 `LumenMicaImage.prepare`（`@WorkerThread`）与 `LiquidBackgroundStore` 写入/预览解码在后台执行外，其余公开 API 必须在主线程调用；表面 `Drawable` 只能作为单个 `View.background`；`source` 不得包含表面自身。
- **自有基类描边与材质互斥**：不经 `LumenActivityDelegate` 时直接调用 `LumenControlOutline.create` 与 `LumenViewportForeground.wrap`，不得复制内部描边类；`LumenFluentControl`、`LumenPrismGlass` 与 `LumenControlShadow` 不叠加在同一 View 上；`LumenMicaBackdrop` 仅装在窗口底层，不与 `bindRoot()` 共管同一 View。
- **不缓存实时引擎**：使用 `GlowFloatingChrome` 时传 `{ lumen.engine }`，不得缓存实例；`LumenEngine.CONTRACT_VERSION` 当前为 `1`，接入层可断言校验。
- **内存压力**：引擎在首次拿到 `Context` 时已在 `Application` 自动登记系统 trim 回调；若宿主自行调用 `LumenEngine.releaseGraphics()`，只允许在 `TRIM_MEMORY_RUNNING_CRITICAL`（15）或 `>= TRIM_MEMORY_COMPLETE`（80）时触发，严禁在 `TRIM_MEMORY_UI_HIDDEN`（20）释放。

> **按需深入阅读**：完整模块坐标、全部 API 签名与参数范围见 [API 与模块速查](references/api-and-modules.md)；存量代码迁移对照、联合关闭顺序、高频报错排查与安全回退见 [迁移对照与排障](references/migration-patterns.md)。

## 第 4 阶段：验证与交付清单

1. **消费方验证**：
   - 执行 `./gradlew :app:assembleDebug :app:testDebugUnitTest` 验证 `checkAarMetadata` 与编译通过。
   - 若修改了宿主调用点，在目标设备/模拟器核对静态与滚动画面、横竖屏与深浅色切换、开发者选项“不保留活动”进出无会话泄漏。
2. **本库源码自检**（修改本仓库或复合构建联调时执行）：
   - 全量构建与单元/契约检查：`.\gradlew.bat assembleDebug testDebugUnitTest :sample:testReleaseUnitTest lintDebug --console=plain --no-daemon`
   - 工具脚本测试：`python -m unittest discover -s tools -p 'test_*.py' -v`
   - 带 Meguviz 桥源码联调：在命令中添加 `-Plumen.meguviz=true` 并配置 BOM 仓库。
3. **失败与回退核对**：
   - `LumenEngine.selectMaterial` 返回 `false` 时，确认界面开关已还原且提示失败；`true` 时已触发 `recreate()`。
   - `bindRoot` 的 `onFailure` 只在整套高级材质不可用且回退持久化后触发；`LumenSurfaceSession` 在预算或平台受限时自动降级为 `SOFTWARE` / `STATIC`。
4. **交付摘要**：总结变更文件、采用的托管模式（局部 / 全页 / Meguviz 桥）、实际执行的验证命令与通过情况，并标明尚未实机覆盖的环境。
