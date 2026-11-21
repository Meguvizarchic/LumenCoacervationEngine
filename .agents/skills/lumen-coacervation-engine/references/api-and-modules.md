# API 与模块速查

本速查对应 `LumenCoacervationEngine` 版本 `1.6.2`（契约版本 `LumenEngine.CONTRACT_VERSION = 1`）。

## 1. 仓库坐标与工具链基线

- **Maven Central**（已发布，**不在 `meguviz-bom:1.2.0` 内**）：
  - `io.github.jichuo1:<模块>:1.6.2`
- **JitPack**（已发布）：
  - `com.github.jichuo1.LumenCoacervationEngine:<模块>:1.6.2`（仓库 `https://jitpack.io`）
- **本地仓库 / 复合构建**：
  - 本地出版：`com.lumen.coacervation.engine:<模块>:1.6.2`（运行 `publishAllPublicationsToProjectLocalRepository` 输出至 `build/repo`）
- **SDK 与编译基线**：
  - `minSdk`: `27`（Android 8.1）
  - `compileSdk`: `37` 及以上（`1.6.0`/`1.6.1` 曾要求 `37.2`，`1.6.2` 已改回 `37`；仅库自身编译内部原生 backdrop 时用 `37.2`）
  - `targetSdk`: `37`（示例基线）
  - `Java / Kotlin JVM Target`: `17`（本库基线 AGP `9.4.1`、Kotlin `2.4.21`）
  - 混淆：无需额外 `-keep` 规则（不使用反射，AGSL 着色器以字符串常量内联）

## 2. 全部模块矩阵

| 模块 | Maven Central 坐标（`1.6.2`） | 核心传递依赖 | 职责与适用范围 |
|---|---|---|---|
| `lumen-engine` | `io.github.jichuo1:lumen-engine:1.6.2` | `androidx.core:core-ktx` | 柔光与高级材质、局部会话、悬浮栏可读性、回弹视口、触摸光晕、Fluent/Mica/Prism、控件软阴影、材质浓度 |
| `lumen-motion` | `io.github.jichuo1:lumen-motion:1.6.2` | `lumen-engine` | 长按弹性、可打断动画、锚点弹窗、全屏形变、翻页/文字链、手风琴、定位高亮、滑块/底栏/液态磁贴、传感器光源 |
| `lumen-controls` | `io.github.jichuo1:lumen-controls:1.6.2` | `lumen-engine`、AppCompat、RecyclerView | `SwitchCompat` / `CheckBox` / `EditText` 换装、`switchParts` 识别、`stopScroll`、长按拖拽排序 |
| `lumen-compose` | `io.github.jichuo1:lumen-compose:1.6.2` | `lumen-engine`、`lumen-motion`、Compose BOM `2026.09.00` | **局部原型**：Compose 局部材质、背景采样、Lazy 生命周期、长按弹性；不含全页托管/Modal/预测返回 |
| `lumen-effects` | `io.github.jichuo1:lumen-effects:1.6.2` | `lumen-motion` | `LumenEffectLayer`（薄膜、纸张、有限能量与粒子，`ViewOverlay` 独立装饰） |
| `lumen-assets` | `io.github.jichuo1:lumen-assets:1.6.2` | `lumen-motion` | `LumenAssetSession` 统一播放会话、预算门控与后台解码线程管理 |
| `lumen-assets-lottie` | `io.github.jichuo1:lumen-assets-lottie:1.6.2` | `lumen-assets`、`com.airbnb.android:lottie:6.7.1` | `LumenLottieFactory`（JSON 与内嵌 Base64 位图，不解析网络/字体） |
| `lumen-assets-pag` | `io.github.jichuo1:lumen-assets-pag:1.6.2` | `lumen-assets`、`com.tencent.tav:libpag:4.5.98-noffavc` | `LumenPagFactory`（自带原生 `.so`，禁用磁盘缓存） |
| `lumen-assets-rive` | `io.github.jichuo1:lumen-assets-rive:1.6.2` | `lumen-assets`、`app.rive:rive-android:11.14.0` | `LumenRiveFactory`（保留 `11.14.0` 以兼容 `minSdk 27`；默认 `CANVAS` 后端） |
| `lumen-bridge-meguviz-android` | `io.github.jichuo1:lumen-bridge-meguviz-android:1.6.2` | `lumen-engine`、`meguviz-bom:1.2.0`、`meguviz-android-window`、`meguviz-android-lifecycle` | 把 `LumenSurfaceSession` 绑定到 `MeguvizAndroid` 的 `WindowSession`；源码内默认需 `-Plumen.meguviz=true` 才参与构建 |

## 3. 关键 API 签名速查

> 除 `LumenMicaImage.prepare`（`@WorkerThread`）及 `LiquidBackgroundStore.importFromUri` / `restoreAutomatic` / `decodePreview`（后台线程）外，所有 UI 与渲染 API 必须在主线程调用。

### 3.1 进程级入口（`com.lumen.coacervation.engine`）

```kotlin
object LumenEngine {
    const val VERSION: String
    const val CONTRACT_VERSION: Int = 1

    @MainThread @JvmStatic fun configure(config: LumenEngineConfig)
    @JvmStatic val config: LumenEngineConfig
    @JvmStatic fun requestedMaterial(context: Context): SkinId
    @MainThread @JvmStatic @JvmOverloads
    fun selectMaterial(context: Context, material: SkinId, realtimeCapture: Boolean? = null): Boolean
    @JvmStatic fun isRealtimeCaptureEnabled(context: Context): Boolean
    @JvmStatic fun setRealtimeCaptureEnabled(context: Context, enabled: Boolean): Boolean
    @MainThread @JvmStatic fun releaseGraphics()
}
```

### 3.2 局部视效会话与扩展材质（`com.lumen.coacervation.engine.host`）

- **会话构造与工厂**：
  - `LumenSurfaceSession(context: Context, palette: LumenPalette, options: LumenSurfaceSessionOptions = LumenSurfaceSessionOptions())`
  - `LumenSurfaceSession.forActivity(activity: Activity, palette: LumenPalette = LumenPalette.neutral(dark), options: LumenSurfaceSessionOptions = LumenSurfaceSessionOptions()): LumenSurfaceSession`（`@JvmStatic @JvmOverloads`，Activity 销毁时在 `super.onDestroy()` 内自动关闭）
- **绑定与更新**：
  - `bind(view: View, surface: LumenSurfaceOptions = LumenSurfaceOptions(), source: View? = null): LumenSurfaceBinding`
  - `bind(view: View, surface: LumenSurfaceOptions, source: View?, enhancements: LumenSurfaceEnhancements)`
  - `bindSource(view: View, surface: LumenSurfaceOptions, source: LumenContentSource?, enhancements: LumenSurfaceEnhancements = LumenSurfaceEnhancements.DEFAULT)`
  - `ensure(view: View, surface: LumenSurfaceOptions = LumenSurfaceOptions(), source: View? = null): LumenSurfaceBinding`（幂等绑定）
  - `ensureSource(view: View, surface: LumenSurfaceOptions, source: LumenContentSource?, enhancements: LumenSurfaceEnhancements = LumenSurfaceEnhancements.DEFAULT)`
  - `bindTarget(target: LumenSurfaceTarget, surface: LumenSurfaceOptions = LumenSurfaceOptions(), source: LumenContentSource? = null, enhancements: LumenSurfaceEnhancements = LumenSurfaceEnhancements.DEFAULT)`
  - `notifyScrollPositionChanged(scrollHost: View)`、`notifyPositionChanged()`、`notifyContentChanged()`
  - `updatePalette(value: LumenPalette)`、`updateOptions(value: LumenSurfaceSessionOptions)`、`pause()`、`resume()`、`releaseGraphics()`、`close()`
- **预设与选项简写**：
  - `LumenSurfacePresets.floating(material: LumenSurfaceMaterial = LumenSurfaceMaterial.FROSTED)`（`@JvmStatic @JvmOverloads`）
  - `LumenSurfacePresets.card(radiusDp: Float = 16f, material: LumenSurfaceMaterial = LumenSurfaceMaterial.FROSTED)`（`@JvmStatic @JvmOverloads`）
  - `LumenSurfacePresets.fadingBand(hold: Float = 0.25f, end: Float = 1f, intervalMs: Long = 0L)`（`@JvmStatic @JvmOverloads`）
  - `LumenSurfacePresets.staticPanel()`（`@JvmStatic`）
  - `LumenSurfaceOptions.withTint(opacity: Float): LumenSurfaceOptions`
- **视口裁剪与共享录制**：
  - `LumenViewportContentSource(source/content, regions)` 与简写 `LumenViewportContentSource(source/content, vararg surfaces: View, paddingPx: Float = 0f)`、`sync(): Boolean`、`close()`
  - `LumenDrawableScene.create(hardware: Boolean, scale: Float = 1f)`、`record(drawable, width, height)`、`draw(canvas)`、`close()`
- **独立控件与扩展材质**：
  - `LumenControlOutline.create(context: Context, accentColor: Int, radiusDp: Float, emphasized: Boolean = false): Drawable`
  - `LumenViewportForeground.wrap(drawable: Drawable): Drawable`
  - `LumenControlShadow.wrap(...)` / `shadowOnly(...)` / `outsetPx(...)`
  - `LumenMaterialOpacity.set(drawable, value, legibility = AUTO)` / `get(drawable)`
  - `LumenFluentControl(view, palette, options = LumenFluentControlOptions(), session = null, source = null)`
  - `LumenMicaBackdrop(view, palette, options = LumenMicaOptions(), image = null)`、`LumenMicaImage.prepare(source)`、`LumenMicaPreparer()`
  - `LumenPrismGlass(view, palette, options = LumenPrismOptions(), session = null, source = null)`
  - `LumenSurfaceBinding.setRenderTargetBackdropEnabled(enabled: Boolean)` / `renderTargetBackdropState(): LumenRenderTargetBackdropState?`（仅 API 37.2+ 基础 `FROSTED` View 背景可用，低版本与不满足条件时自动回退）

### 3.3 全页宿主托管与悬浮栏（`host` 与 `glow`）

- **`LumenActivityDelegate`**：
  - `LumenActivityDelegate(activity, paletteProvider, controlShadowProvider = { DISABLED }, effectTuningProvider = { DEFAULT })`
  - 会话：`prepare()`、`bindRoot(root: View, onFailure: (() -> Unit)? = null): Boolean`、`val engine: GlowEngine?`
  - 回调转发：`onDispatchTouchEvent(event)`、`onStart()`、`onStop()`、`onTrimMemory(level)`、`onLowMemory()`、`onDestroy()`
  - 表面与控件：`surface(...)`、`cardBackground(...)`、`floatingBackground(...)`、`topBarBackground(...)`、`selectionBackground(...)`、`modalBackground(...)`、`motionSurfaceBackground(...)`、`liquidMotionSurfaceBackgroundOrNull(...)`、`chromeOverlayBackground(...)`、`neutralWindowBackground()`、`styleActionButton(...)`、`styleSelectionControl(...)`、`styleStatusChip(...)`、`controlOutline(...)`、`installStretch(...)`、`finishStretch(...)`、`setMaterialOpacity(...)`
- **`LumenActivity`**：继承 `android.app.Activity`，已绑定上述六个转发，覆盖 `resolvePalette()` / `resolveControlShadow()` / `resolveEffectTuning()` 即可。
- **`GlowFloatingChrome`**：`attach(host, edge, foregroundColor, companions, thickenHost, onForeground)`、`onContentMoved()`、`dispose()`；`engine` 参数必须传入 `{ lumen.engine }`。

### 3.4 交互、动效、效果、资产、Compose 与 Meguviz 桥

- **`lumen-motion`**：
  - `LumenElasticInteraction(activity, lumen, isExcluded, effectTuning, springSpec, rubberCurve)`：`dispatch(event, superDispatch)`、`clear()`、`installDialog(dialog)`、`dispose()`、`travelPolicy`
  - `LumenModalPresenter(activity, lumen, style = LumenModalStyle(), elastic = null, styleContent = {})`：`createContainer()`、`present(...)`、`dismiss(...)`、`captureSubPanel(...)`、`presentSubPanel(...)`、`dismissParent(...)`、`onDestroy()`
  - `ContainerMorphLauncher.launch(...)` / `clear(...)`、`ContainerMorphHost`（含 `followScreenCorners`）、`ContainerMorphController`
  - `LumenPagePager`、`PageTextChain`、`LumenPageScrollView`、`SectionExpansionController`、`LumenReveal`
  - `LumenNavigationBar`（含 `setPrismSelection`）、`LumenSegmentScrubBar`、`LumenSlider`、`LumenSlidingSelection`、`LumenMorphTile`、`LumenMaterialOpacityScope`、`LumenSensorLightController`
- **`lumen-controls`**：`LumenControls.style(root, lumen)`、`LumenControls.switchParts(view)`、`LumenControls.stopScroll(host)`、`LumenReorderCallback`
- **`lumen-effects` & `lumen-assets*`**：`LumenEffectLayer`、`LumenAssetSession`、`LumenLottieFactory`、`LumenPagFactory`、`LumenRiveFactory(context, renderer = CANVAS)`
- **`lumen-compose`**：`rememberLumenComposeState(palette, options)`、`LumenComposeHost(state, modifier, backdrop, content)`、`Modifier.lumenSurface(...)`、`Modifier.lumenElastic(enabled)`
- **`lumen-bridge-meguviz-android`**（包 `com.lumen.coacervation.engine.bridge.meguviz.android`）：
  - `Activity.openLumenSurfaces(palette: LumenPalette? = null, options: LumenSurfaceSessionOptions = LumenSurfaceSessionOptions()): LumenSurfaceSession`（`@file:JvmName("LumenMeguvizActivity")`，Activity 须为 `LifecycleOwner`）
  - `WindowSession.openLumenSurfaces(context: Context? = null, palette: LumenPalette? = null, options: LumenSurfaceSessionOptions = LumenSurfaceSessionOptions()): LumenSurfaceSession`（`@file:JvmName("LumenMeguvizWindow")`）

## 4. 与 Meguviz 家族其他库的配合边界

- **`MeguvizAndroid` + `lumen-bridge-meguviz-android`**：`lumen-bridge-meguviz-android:1.6.2` 已发布到 Maven Central，依赖 `io.github.jichuo1.meguviz:meguviz-bom:1.2.0` 下的 `meguviz-android-window` 与 `meguviz-android-lifecycle`。使用 `openLumenSurfaces()` 时由 `WindowSession` 统一关闭 `LumenSurfaceSession`，不重复注册 Activity 生命周期回调。
- **`MeguvizUI`（`meguviz-ui-lumen-bridge`）与 `MeguvizDrawable`（`meguviz-drawable-lumen`）**：这两座桥属于 Meguviz 侧仓库，**尚未发布到 Maven Central，仅可源码构建**。联合使用时须遵守 Meguviz 侧关闭顺序：先关闭各 Drawable / UI binding，再关闭 `WindowSession`，若未由 `openLumenSurfaces()` 托管则显式关闭 `LumenSurfaceSession`。
- **`MeguvizCompute` / `MeguvizMedia`**：负责通用计算、离屏调度与视频帧管线，不提供 Android View 材质、弹性手势或 Modal 形变，与 Lumen 职责互不重叠。
