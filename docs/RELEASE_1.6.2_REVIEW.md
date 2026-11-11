# 1.6.2 发布复核

## 版本范围

- Java 新增无参的 `LumenSurfacePresets.floating()`。局部会话新增生命周期接入和幂等绑定入口，包括 `forActivity`、`ensure` 和 `ensureSource`。
- 局部增强和悬浮栏复用进程内的着色程序。系统内存压力回调会释放图形资源，热状态监听在停止时断开对页面的间接引用。
- 库内部使用 compileSdk 37.2 构建，发布 AAR 的消费者最低编译 SDK 为 37。原生 backdrop 按运行时 SDK 完整版本决定是否启用。运行时基线仍为 minSdk 27，宿主契约版本仍为 1。
- GitHub 常规发行包含九个库模块。`lumen-bridge-meguviz-android` 是独立的 Central 制品，其运行依赖包括 `lumen-engine:1.6.2` 和由 Meguviz BOM `1.2.0` 对齐的 Android 模块。

## 本地验证

以下记录基于主分支 `aef3c4b3f8a83a906e03c356fa8f0d83fd92e844` 的隔离快照。

- `assembleDebug`、`testDebugUnitTest`、`:sample:testReleaseUnitTest`、`lintDebug`、Release AAR 和样例 R8 通过。共 1,183 项宿主测试，无失败、错误或跳过。其中 19 个与 `1.6.1..HEAD` 变更相关的 engine 测试类独立复跑了 78 项。
- 用实际 Central 暂存引擎 AAR 编译并运行 Java 17 消费者，覆盖四个无参预设、原显式 `floating` 重载和 `withTint`。`LumenSurfacePresets` 与公开的 `1.6.1` AAR 对比，保留了该类原有的八个公开方法。
- 独立 Java Android 消费者以 compileSdk 37.0 分别使用 Gradle `.module` 和 Maven POM 构建。两条路线均通过 Java 编译、AAR 元数据检查、R8 和 Release lint，生成逐字节一致的 APK。消费者使用了本轮暂存的 LCE engine、桥和三个冻结的 MeguvizAndroid AAR。
- 对样例 Debug APK 做了 minSdk 27 的 DEX 审计，分类 75 处平台类型引用。预测返回的两个 API 34 回调签名在调用处有 API 34 SDK 检查。
- Maven Central 九模块常规包与独立桥包分别完成本地签名，共验签 50 个载荷。桥的上游依赖可用性按实际 Central 读回判断。

## GitHub 发布门禁

主分支 `aef3c4b` 的 GitHub CI 构建成功。该提交的 Demo APK 冒烟中，API 27 和 31 通过，API 33 和 34 各有一条失败。两条失败均出现在 `NativeComposeIntegrationTest.nativeEnhancedPressChangesPixelsAndUniformUpdatesKeepTheShaderCache` 的着色输入断言。 [GitHub CI](https://github.com/Meguvizarchic/LumenCoacervationEngine/actions/runs/42979938074) · [Demo 冒烟记录](https://github.com/Meguvizarchic/LumenCoacervationEngine/actions/runs/42979938107)

原测试等待当前会话至少编译三次着色程序。现在同一进程内的直接着色程序可被多个表面复用，缓存已由前序测试建立时，当前会话的编译计数可以为零。发布准备提交将就绪条件改为启用增强后出现新的 GPU 绘制，且三个表面均已在 GPU 上绘出可见帧。按压前后的像素差异与更新 uniform 后的着色程序、效果链计数断言照常执行。独立的 `DirectProgramSharingTest` 还覆盖八个不同参数表面的像素隔离及关闭后重绑的缓存合同。

正式标签的 GitHub 附件、四系统演示矩阵和 JitPack 下载可用性以发布流水线生成的 `VERIFICATION_1.6.2.json` 和 `SHA256SUMS.txt` 为准。上述本地验证未包含本轮真机复测，也未测量设备功耗或长期 GPU 内存占用。
