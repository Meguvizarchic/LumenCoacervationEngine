# 迁移对照与排障指南

本文覆盖存量代码向 `LumenCoacervationEngine 1.6.2` 迁移的前后对照、联合生命周期顺序、高频问题排查及安全回退方法。

## 1. 存量代码迁移前后对照

### 1.1 自管绑定表与手动 `onDestroy` → `forActivity` + `ensure`

旧代码通常在 Activity 维护 `bindings`、`ownedBackgrounds` 和 `padding` 快照，并在 `onDestroy` 手动 `close()`：

```kotlin
// 迁移前：手写对账与释放
private val bindings = HashMap<View, LumenSurfaceBinding>()
private lateinit var session: LumenSurfaceSession

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    session = LumenSurfaceSession(this, palette)
    refreshSurfaces()
}

private fun refreshSurfaces() {
    val existing = bindings[card]
    if (existing?.isBound != true) {
        existing?.close()
        val pad = intArrayOf(card.paddingLeft, card.paddingTop, card.paddingRight, card.paddingBottom)
        bindings[card] = session.bind(card, LumenSurfacePresets.card(), content)
        card.setPadding(pad[0], pad[1], pad[2], pad[3])
    } else {
        existing.update(LumenSurfacePresets.card())
    }
}

override fun onDestroy() {
    session.close()
    super.onDestroy()
}
```

迁移后由引擎自动保存与恢复 `padding`、校验背景归属并在 `super.onDestroy()` 内关闭会话：

```kotlin
// 迁移后：自动关闭 + 幂等 ensure
private lateinit var session: LumenSurfaceSession

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    session = LumenSurfaceSession.forActivity(this) // 或传入自定义 palette
    refreshSurfaces()
}

private fun refreshSurfaces() {
    session.ensure(card, LumenSurfacePresets.card(), content)
}
```

### 1.2 自有 Activity 基类拷贝描边 → `LumenControlOutline` / `LumenViewportForeground`

若宿主使用自己的 Activity 基类与主题色（不走 `LumenActivityDelegate`），直接调用公开入口替代复制内部描边或前景滚动包装类：

```kotlin
// 迁移前：宿主各自复制 FocusOutlineDrawable 与 ViewportForegroundDrawable
// 迁移后：
input.foreground = LumenControlOutline.create(this, monetPrimary, radiusDp = 14f)
button.foreground = RippleDrawable(
    rippleColor,
    LumenControlOutline.create(this, accent, radiusDp = 20f, emphasized = true),
    mask
)
customView.foreground = LumenViewportForeground.wrap(customFocusDrawable)
```

### 1.3 Java 调用无参预设与小视口简写

`1.6.2` 起 `LumenSurfacePresets.floating` 与 `card` 已提供 `@JvmOverloads`，`LumenViewportContentSource` 提供固定表面简写构造，`LumenSurfaceOptions` 提供 `withTint`：

```java
// Java 可直接无参调用 floating()，与 card() / fadingBand() / staticPanel() 一致
LumenSurfaceOptions barOpt = LumenSurfacePresets.floating().withTint(0.24f);
LumenSurfaceOptions cardOpt = LumenSurfacePresets.card();
LumenViewportContentSource cropped = new LumenViewportContentSource(contentRoot, new View[]{topBar, bottomBar}, 8f);
// 无额外余量时可用可变参数重载：new LumenViewportContentSource(contentRoot, topBar, bottomBar);
// Kotlin 写法：LumenViewportContentSource(contentRoot, topBar, bottomBar, paddingPx = 8f)
```

### 1.4 各自管理 `WindowSession` → `openLumenSurfaces()`

引入 `io.github.jichuo1:lumen-bridge-meguviz-android:1.6.2` 与 `platform("io.github.jichuo1.meguviz:meguviz-bom:1.2.0")` 后：

```kotlin
// 迁移前：手动打开 WindowSession + LifecycleAdapters.bindWindow + LumenSurfaceSession.forActivity
// 迁移后（Activity 须实现 LifecycleOwner）：
val surfaces = openLumenSurfaces()
surfaces.ensure(card, LumenSurfacePresets.card(radiusDp = 18f), content)
```

### 1.5 旧版 `Application.onTrimMemory` 手动转发

引擎第一次拿到 `Context`（调用 `LumenActivityDelegate.prepare()`、`LumenSurfaceSession` 或 `LumenMicaBackdrop` 创建）时，已自动在 `Application` 上登记同一门槛的系统内存回调。宿主可移除冗余的 `Application.onTrimMemory` 转发；若因自定义内存协调器仍需手动调用 `LumenEngine.releaseGraphics()`，只允许在 `TRIM_MEMORY_RUNNING_CRITICAL`（15）或 `>= TRIM_MEMORY_COMPLETE`（80）时触发，**严禁**在 `TRIM_MEMORY_UI_HIDDEN`（20）释放。

## 2. 生命周期、线程与联合关闭顺序

1. **全页宿主托管销毁顺序（`onDestroy`）**：
   先释放依赖会话或控件的组件（`GlowFloatingChrome.dispose()`、`PageTextChain.dispose()`、`LumenNavigationBar.dispose()`、`LumenSlider.dispose()`、`LumenModalPresenter.onDestroy()`、`ContainerMorphLauncher.clear(...)`、`LumenElasticInteraction.dispose()`），再调用 `lumen.onDestroy()`，最后在 `try / finally` 的 `finally` 中调用 `super.onDestroy()`。
2. **局部会话与复合控件顺序**：
   先 `input.close()` / `fluentControl.close()` / `prismGlass.close()` / `binding.close()`，最后 `session.close()`。使用 `LumenSurfaceSession.forActivity` 时，会话关闭发生在 `super.onDestroy()` 内部，晚于宿主 `onDestroy()` 中的控件清理代码。
3. **Meguviz 联合使用关闭顺序**：
   - 仅使用 `openLumenSurfaces()`：无需手写 `onDestroy`，`WindowSession` 关闭时自动关闭 `LumenSurfaceSession`。
   - 混用 `MeguvizUI` 的 `UiScope` 或 `MeguvizDrawable` 适配器：必须先关闭所有 UI/Drawable binding，再关闭 `WindowSession`；如还有未交给 `openLumenSurfaces()` 托管的 `LumenSurfaceSession`，显式调用 `surfaces.close()`。
4. **线程规则**：
   - `LumenMicaImage.prepare`（`@WorkerThread`）与 `LiquidBackgroundStore.importFromUri` / `restoreAutomatic` / `decodePreview` 在后台线程执行。
   - 其余渲染、会话、动效与桥接入口必须在主线程调用。

## 3. 高频错误排查表

| 现象或异常 | 根因 | 处理方式 |
|---|---|---|
| `checkAarMetadata` 报错要求 `compileSdk 37.2` | 消费方误用了 `1.6.0` 或 `1.6.1` | 升级所有 `lumen-*` 坐标到 `1.6.2`（仅要求 `compileSdk 37`） |
| 同一进程出现材质开关反复失效或状态错乱 | 通过 shade / relocate 引入了两份引擎，两个单例读写同一偏好文件 | 全进程只保留一份引擎依赖，禁止对引擎做包名重定位 |
| `openLumenSurfaces()` 抛 `IllegalArgumentException` | 在裸 `android.app.Activity`（非 `LifecycleOwner`）上调用 | 改用 `AppCompatActivity`/`ComponentActivity`，或自行打开 `WindowSession` 并调用 `windowSession.openLumenSurfaces()` |
| `openLumenSurfaces()` 抛 `IllegalStateException` | 在非主线程、已销毁 Activity 上调用，或同一 Activity 重复调用传入了不同参数 | 保持同一参数并在主线程调用；需改参数时先 `close()` 原会话 |
| `EditText` 文本横向滚动时焦点/按压描边跟着左移 | 自行构造的前景未补偿 `scrollX/scrollY` | 使用 `lumen.controlOutline(...)` 或 `LumenControlOutline.create(...)` / `LumenViewportForeground.wrap(...)` |
| 悬浮栏内容节点玻璃闪烁或递归模糊 | 悬浮栏被放进了 `GlowBackdropTarget` 内部，或 `source` 包含了表面自身 | 把悬浮栏设为 `GlowBackdropTarget` 的兄弟节点，确保 `source` 不包含目标表面 |
| 长按弹性或控件阴影被切出直角边界 | 直接宿主容器未关闭裁剪 | 在直接宿主容器设置 `clipChildren = false`、`clipToPadding = false` 并预留外扩空间 |
| 切换 `SkinId.LIQUID` 或设置 `effectTuning` 后无变化 | 写入偏好后未重建 Activity，或 `LumenEngine.selectMaterial` 返回 `false` | 仅当返回 `true` 时调用 `recreate()`；返回 `false` 时还原界面开关 |
| `RecyclerView` 拖拽排序时条目弹回或行闪动 | 拖动中途提交了外部列表更新，或未关闭 change 动画 | 仅在 `LumenReorderCallback` 的 `onDrop` 提交数据；设 `supportsChangeAnimations = false` |
| Rive 在 API 27–28 设备加载失败（`UNSUPPORTED_ABI`） | 强行升级 `rive-android` 到含 ELF TLS 的版本 | 使用 `lumen-assets-rive:1.6.2` 绑定的 `11.14.0`（默认 `CANVAS` 后端） |

## 4. 安全回退方案

1. **单页回退（不影响其他页面）**：
   - 使用 `LumenSurfaceSession` 时，直接调用 `session.close()`（会恢复仍由引擎持有的原背景并保留 `padding`），或将配置设为 `LumenSurfacePresets.staticPanel()` / `enabled = false`。
2. **全页材质紧急关闭或回退**：
   - 将所有可见页通过 `LumenEngine.selectMaterial(context, SkinId.MATERIAL_YOU)` 切回柔光并 `recreate()`；`SkinRepository` 在 `LIQUID` 未确认健康前如进程异常退出也会在下次启动自动回退 `MATERIAL_YOU`。
3. **移除 Meguviz 桥回退独立宿主**：
   - 若消费方暂时不需要联合 `WindowSession`，移除 `lumen-bridge-meguviz-android` 依赖，把 `openLumenSurfaces()` 原位替换为 `LumenSurfaceSession.forActivity(this)` 即可保持相同参数与自动关闭语义。
