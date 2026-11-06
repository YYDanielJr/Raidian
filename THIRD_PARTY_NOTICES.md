# 第三方组件与参考项目

完整的项目致谢与用途说明见 [README 的致谢章节](README.md#致谢)。下文保留随附 LibADB 源码的固定版本和补丁记录，并补充其他依赖索引。第三方源码自身的版权、SPDX、COPYING 和 LICENSE 文件仍是对应代码的许可依据。

## LibADB Android

- 来源：https://github.com/MuntashirAkon/libadb-android
- 版本：3.1.1，提交 `c849886ebc6d48e7b46d967e78a6bb65c90c3b74`。
- 本项目选择其 Apache-2.0 授权选项。各源文件的原始版权与 SPDX 声明保留，完整许可见 `third_party/libadb/COPYING` 和 `third_party/libadb/LICENSES/`。
- 原始 Java 源码纳入 `third_party/libadb`。本地补丁：TCP 连接限时 10 秒；配对及 ADB socket 读限时 15 秒；配对失败时关闭尚未完成握手的 socket，安全处理空流／空认证上下文；SSLContext 不跨已销毁的身份对象缓存；ADB OPEN 在发送前注册等待并检查条件，避免回环连接丢失唤醒，最多等待 15 秒；不记录配对对端标识。Gradle 配置适配本工程。
- 配对使用上游基于 AOSP／Shizuku 的 SPAKE2、TLS 1.3 实现，不自行替换密码协议。ADB 只用于启动本机采集服务，随后关闭连接。

## 传递及显式依赖

- SPAKE2 Android 2.2.1：https://github.com/MuntashirAkon/spake2-java 。该版本 POM 标注 LGPL-3.0，依赖的许可独立于 LibADB 的 Apache-2.0 选项；完整许可及原生来源请同时查看 SPAKE2 上游和发行物声明。
- Conscrypt Android 2.5.3：https://github.com/google/conscrypt ，Apache-2.0 及其原生依赖声明。使用独立 TLS 实现，避免访问系统隐藏的 Conscrypt API。
- Bouncy Castle 1.81：https://www.bouncycastle.org/ ，Bouncy Castle License。用于 RSA 身份证书与上游密码运算。

LibADB 随附源文件还保留 Cameron Gutman、Sam Palmer、南宫雪珊、Google 等原始版权声明；部分文件同时适用 BSD-3-Clause 或 MIT，不能只看模块的双重许可选项。AndroidUtils 的模拟器检测标注了 Firebase Android SDK 的参考来源（https://github.com/firebase/firebase-android-sdk）；本应用不集成 Firebase 服务。

## 设计与采集参考

- Shizuku：https://github.com/RikkaApps/Shizuku ，参考 `app_process` 独立提权进程模型；运行时不再依赖 Shizuku API、Provider 或管理器。
- 采集数据源继续沿用仓库中已对照 Scene、FrameX、FPS-Meter 的实现。本次不更改 CPU／GPU／温度／电流单位契约。

## 界面与基础设施

- Miuix 0.9.4（UI、Core、Icons、Blur）：https://github.com/compose-miuix-ui/miuix ，Apache-2.0。
- AndroidX／Jetpack Compose（含 Material 3、Activity、Lifecycle、Core、Navigation Event、Annotation）：https://android.googlesource.com/platform/frameworks/support/ ，许可见各模块，主要为 Apache-2.0。
- Kotlin：https://github.com/JetBrains/kotlin ，Apache-2.0。
- kotlinx.coroutines：https://github.com/Kotlin/kotlinx.coroutines ，Apache-2.0。
- kotlinx.serialization：https://github.com/Kotlin/kotlinx.serialization ，Apache-2.0。
- Gradle：https://github.com/gradle/gradle ，Apache-2.0。
- Android Gradle Plugin：https://android.googlesource.com/platform/tools/base/ ，Apache-2.0；R8 及其他工具各自保留其声明。
- Foojay Toolchains Resolver：https://github.com/gradle/foojay-toolchains 。
- JUnit 4：https://github.com/junit-team/junit4 ，EPL-1.0。
- AndroidX Test／Espresso：https://github.com/android/android-test ，Apache-2.0；Compose UI Test 属 AndroidX。

## 开发参考索引

以下为对照数据源、算法或功能组织的参考工程，不作为完整应用依赖打包：

- Scene／vtools：https://github.com/helloklf/vtools ，GPL-3.0。
- FrameX-Android：https://github.com/MaheshSharan/FrameX-Android ，MIT。
- FPS-Meter-Android：https://github.com/rdevz-ph/FPS-Meter-Android ，MIT。
- Minimal Kernel Manager：https://github.com/abhay-byte/mkm ，GPL-3.0-or-later。
- atop：https://github.com/chandradeep24/atop ，MIT。
- DebugOverlay-Android：https://github.com/Manabu-GT/DebugOverlay-Android ，Apache-2.0。
- Cumulus-APP：https://github.com/chenzyadb/Cumulus-APP ，GPL-3.0。
- Android Open Source Project：https://android.googlesource.com/ ，各源码模块遵循各自许可。

本文件不是对 Raidian 自有代码的统一许可声明，也不替代上游完整许可。角色与图标来源另见 [图标说明](docs/assets/README.md)。
