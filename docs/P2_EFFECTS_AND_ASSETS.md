# 传感器、效果包与动画资产

> 自 1.2.1 起提供；本文描述当前版本的行为，随版本更新。

本组能力对应传感器光源（`lumen-motion`）、独立效果与粒子（`lumen-effects`）及统一动画资产播放（`lumen-assets*`）。所有功能可单独接入；所选模块须使用同一版本。

## 模块与依赖

| 模块 | 用途 | 额外播放依赖 |
|---|---|---|
| `lumen-motion` | 可选传感器光源、时钟 | 无 |
| `lumen-effects` | 薄膜、纸张、有限能量、粒子 | 无；依赖 `lumen-motion` / `lumen-engine` |
| `lumen-assets` | 资产来源、预算、生命周期、后台解析与播放会话 | 无；依赖 `lumen-motion` / `lumen-engine` |
| `lumen-assets-lottie` | JSON 与嵌入位图的 Lottie | `com.airbnb.android:lottie:6.7.1` |
| `lumen-assets-pag` | PAG 时间线 | `com.tencent.tav:libpag:4.5.98-noffavc` |
| `lumen-assets-rive` | Rive 画板、时间线、状态机与输入 | `app.rive:rive-android:11.14.0` |

核心模块不反向依赖上述扩展模块。播放库是各自模块的 `implementation` 依赖，公开签名不含播放库类型；引入 Rive 时会带有其 Compose 与原生传递依赖。PAG、Rive 带原生运行时（PAG AAR 含 `arm64-v8a`、`armeabi-v7a`、`armeabi`、`x86_64`，不含 `x86`）。业务应用只选择需要的格式模块即可。

## D14 传感器光源

`LumenSensorLightController(view, listener, options, input?)` 是窗口局部生产者。默认关闭，只有显式 `start()` 且启用、可见、有焦点时注册；`stop()`、隐藏、失焦、detach、`close` 解除监听。宿主将原始类型光向量回调转发给自己选择的 `LumenSurfaceBinding.setLightDirection()`，并自行管理传感器与手动光源的优先级。

| 参数 | 默认／范围 |
|---|---|
| `enabled` / `allowAccelerometerFallback` / `invertX` / `invertY` | false / true / false / false |
| `sampleRateHz` / `minimumUpdateIntervalMs` | 30（1–60）／32（16–1000 ms） |
| `smoothingTimeMs` / `influence` | 150（0–2000 ms）／0.5（0–1） |
| `maximumTiltDegrees` / `maximumAngularSpeedDegrees` | 45（0–60°）／180（10–360°/s） |
| `changeThresholdDegrees` / `flatThreshold` | 0.3（0–10°）／0.025（0–0.2） |
| `baseAngleDegrees` / `baseAltitude` | 145（0–360°）／0.65（0.05–1） |
| `fixedRotation` | null 自动主显示器，或 0/1/2/3 |

优先重力传感器，可关闭加速度计回退。时间滤波使用事件时间戳；首次有效样本初始化方向，后续样本以球面插值约束角速度，单次时间差最多 0.25 秒。方向旋转映射、平放死区和发布节流独立。自动映射拒绝非主显示器，宿主可显式提供旋转。无传感器、注册失败、回调失败均有独立状态；失败不会每次 `preDraw` 重试，显式 `start`/`update` 后可恢复。诊断 `rejected` 包括无效与被死区/间隔抑制的事件。

## D20/D21 独立装饰

`LumenEffectLayer` 挂在 `ViewOverlay`，保留业务背景、层级和输入监听。默认生成器和粒子均关闭；生成器不需要背景来源，捕获计数恒为 0。API 33+ 硬件绘制采用已编译的固定 `RuntimeShader` 程序；失败在该 `Drawable` 内粘性回退 Canvas。薄膜为风格化虹彩，未宣称物理光学模拟；Canvas 使用近似梯度、点粒或环形波，外观与 GPU 不保证逐像素相同。

| 组 | 可调内容 |
|---|---|
| `layer` | `seed`、`framesPerSecond` 1–60、`maximumRenderPixels` 16K–1M、`reduceMotion`、`pauseWhenUnfocused`、`clearOnPause`、`clipToBounds` |
| `procedural` | `enabled`、`kind` (`FILM`/`PAPER`/`ENERGY`)、`opacity`、`color`、`radiusDp` 0–128、`gpuEnabled` |
| `film` | `thicknessNm` 100–2000、`iridescence`、`roughness` 0.05–1、`angleDegrees`、`intensity` 0–2 |
| `paper` | `grainEnabled`、`grainCount` 16–512、`grainSizeDp` 0.5–12、`contrast`、`relief` |
| `energy` | `durationMs` 100–4000、`speed` 0.1–3、`bands` 1–8、`wavelengthDp` 8–96、`intensity` 0–2 |
| `particles` | `enabled`、`shape` (`ORB`/`STREAK`)、`maximumParticles` 4–128、`burstCount` 1–64、`lifetimeMs` 100–4000、`speedDpPerSecond` 0–800、`gravityDpPerSecondSquared` −300–300、`radiusDp` 1–12、`spreadDegrees`、`directionDegrees`、`trailLengthDp` 0–24、`opacity`、`color`、`maximumParticlePixels` 1K–256K |

`emitBurst(x, y)` 是显式有限发射，未实现无限自动发射器。固定 128 槽数组池用解析年龄积分，不每帧扩容；种子和固定时钟使结果可复现。`triggerEnergy()` 只产生有限瞬态，未触发和结束时透明。只在活粒子或能量存在时安排 tick；隐藏、后台、关闭和减少动画停止动态工作。`clearOnPause = false` 保留状态并冻结寿命，`true` 清空瞬态。

`LumenEffectPreset` 是 schema 1 参数 JSON；16 KiB、深度、字段类型与范围校验沿用既有有界导入规范，已知错误字段拒绝整份配置。它不包含任意 Shader、资产文件、传感器状态或播放器状态。

## D22 资产会话

```kotlin
val session = LumenAssetSession(container, LumenAssetOptions(autoplay = false))
session.setListener { state, failure -> /* 异步主线程状态通知 */ }
session.load(LumenAssetSource { context.assets.open("animation.json") }, LumenLottieFactory())
// 用户开始播放时 session.play()；onStop 时 pause()；onDestroy 时 close()
```

会话每次只拥有一个子容器，不修改宿主 `container` 的裁剪与原有子 View。它是资产展示层：阻止子播放器自动接收 pointer 事件，自己的子容器返回 `false` 把触摸交还宿主；Rive 交互使用显式状态输入，不接管业务触摸。`clipToBounds` 作用于自己的子容器，不能取消祖先已有的裁剪。`enabled = false` 隐藏自己并停止提交。每会话单线程加最多一个待处理替换；取消旧任务、代次隔离、关闭迟到句柄，后台解析后只在主线程挂载。固定时钟下须显式 `advanceFrame()`；Rive 原生时钟拒绝此模式。

| 预算／选项 | 默认／范围 |
|---|---|
| `enabled` / `autoplay` / `reduceMotion` / `pauseWhenUnfocused` / `mute` / `videoEnabled` / `clipToBounds` | true / false / false / true / true / false / true |
| `speed` / `repeatCount` / `framesPerSecond` | 1（0.1–4）／1（1–10）／30（1–60） |
| `maximumFileBytes` | 4 MiB（1 KiB–16 MiB），读取中即拒绝超限 |
| `maximumRenderPixels` | 1M（16K–4M），分别检查元数据画布和宿主绘制面积 |
| `maximumDurationMs` | 60s（100 ms–600s），限制元数据时长及一次可见播放会话的总时长（含重复） |
| `maximumDecodedImagePixels` | 1M（1K–4M），Lottie 检查嵌入位图真实头部尺寸的总和 |
| `opacity` / `fit` | 1（0–1）／`CONTAIN`、`COVER`、`FILL` |

Lottie 支持 JSON 时间线、绝对寻帧和有限重播；不自动解析网络图片、字体或 dotLottie ZIP。PAG 采用自有 `TextureView`、`PAGPlayer` 及 `PAGSurface`，手动时间线提交，关闭时明确释放 `player`/`surface` 并禁用磁盘缓存；`PAGFile` 本身无公开 `release` 接口，结束后丢弃引用由运行时 GC 回收。

Rive `11.14.0` 的类型化 View 兼容层负责画板、命名时间线、状态机和 number/boolean/trigger 输入，不把新版 Compose API 接进核心。解析时枚举至多 1024 个输入，`metadata.inputs` 给出名称及类型；提交前校验，错误名称或类型返回 `false`。只有可见、正在播放且未降低动态的会话才允许提交输入，暂停时返回 `false`。Rive 适配器使用私有生命周期 owner、默认不加载 CDN 资产、关闭时销毁 renderer 并释放 File。公开 `metadata` 标明 `nativeClock = true`、`seekable = false`、`speedControl = false`、`repeatControl = false`、`frameRateControl = false`（固定速度 1、单次播放），任意寻帧、速度或重复设置给出 `UNSUPPORTED_OPERATION`。`framesPerSecond` 只用于可控时间线提交，不约束 Rive 原生时钟；Rive 通过单次剩余时长截止任务收尾（受主线程调度影响），状态机无已知时长时由 `maximumDurationMs` 停止会话。

文件字节与已知画布预算是接入门禁，不等于供应商内部原生解码内存的硬上限（PAG/Rive 内嵌图像像素数不可提前穷举，`decodedImagePixels = 0` 表示未计量）。仅加载宿主信任的资产；宿主提供的网络 `InputStream` 须自行设置连接与读取超时。

## Demo 与适用范围

Rive 工厂默认使用 `LumenRiveRenderer.CANVAS`（SDK Canvas 兼容后端）；`GPU` 模式需显式选择并自行在目标硬件验证像素输出（SwiftShader 环境下四版本曾出现 GPU depth/stencil 回退无像素问题，不可仅凭解析成功判断可见绘制）。SDK 的 Canvas 与 View 兼容 API 在上游已标注 deprecated，业务采用前须确认对应硬件渲染行为。

Demo 入口位于“表面沙盒 → P2 效果 / 资产”。

- **适用范围与验证说明**：验证细节见 [P2_REVIEW.md](P2_REVIEW.md)。模拟器上的重力事件、解析与生命周期测试仅用于同环境对比，真实传感器轴向、多显示器、手机功耗、长时间原生内存占用及 Rive GPU 模式均需目标真机验收；`lumen-compose` 与原生 backdrop 均不接管本模块能力。
