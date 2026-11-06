# 内置 ADB 相关开源组件

## LibADB Android

- 来源：https://github.com/MuntashirAkon/libadb-android
- 版本：3.1.1，提交 `c849886ebc6d48e7b46d967e78a6bb65c90c3b74`。
- 本项目选择其 Apache-2.0 授权选项。各源文件的原始版权与 SPDX 声明保留，完整许可见 `third_party/libadb/COPYING` 和 `third_party/libadb/LICENSES/`。
- 原始 Java 源码纳入 `third_party/libadb`。本地补丁：TCP 连接限时 10 秒；配对及 ADB socket 读限时 15 秒；配对失败时关闭尚未完成握手的 socket，安全处理空流／空认证上下文；SSLContext 不跨已销毁的身份对象缓存；ADB OPEN 在发送前注册等待并检查条件，避免回环连接丢失唤醒，最多等待 15 秒；不记录配对对端标识。Gradle 配置适配本工程。
- 配对使用上游基于 AOSP／Shizuku 的 SPAKE2、TLS 1.3 实现，不自行替换密码协议。ADB 只用于启动本机采集服务，随后关闭连接。

## 传递及显式依赖

- SPAKE2 Android 2.2.1：https://github.com/MuntashirAkon/spake2-java 。保留依赖内许可，来源及许可同时参见 LibADB 的 LICENSES。
- Conscrypt Android 2.5.3：https://github.com/google/conscrypt ，Apache-2.0 及其原生依赖声明。使用独立 TLS 实现，避免访问系统隐藏的 Conscrypt API。
- Bouncy Castle 1.81：https://www.bouncycastle.org/ ，Bouncy Castle License。用于 RSA 身份证书与上游密码运算。

## 设计与采集参考

- Shizuku：https://github.com/RikkaApps/Shizuku ，参考 `app_process` 独立提权进程模型；运行时不再依赖 Shizuku API、Provider 或管理器。
- 采集数据源继续沿用仓库中已对照 Scene、FrameX、FPS-Meter 的实现。本次不更改 CPU／GPU／温度／电流单位契约。
