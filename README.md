<p align="center">
  <img src="docs/assets/raidian-icon.png" width="144" height="144" alt="Raidian 应用图标">
</p>

# Raidian

Raidian 是一款 Android 性能与电池监控工具，使用 Kotlin、Jetpack Compose 和 Miuix 构建，提供实时监控、游戏悬浮窗、后台会话记录、续航与充电曲线。界面采用接近 HyperOS 的样式，适配手机和平板。

名称来自《明日方舟》角色「电弧」（Raidian）。应用图标为以该角色为灵感、经 AI 生成并由维护者选定的插画。本项目是独立开发的工具，与《明日方舟》官方无隶属关系。

当前版本：**0.5.6**（versionCode：9）。最低系统：**Android 13／API 33**。

## 功能

- **实时监控**：帧率、CPU 频率与占用、GPU、温度、电池电流与功率、内存等；CPU 详情按设备实际核心和频率簇展示，运行状态页提供可读取的温度传感器明细。
- **悬浮窗**：跨应用查看主要指标，可配置显示项、字号、位置与触摸穿透。
- **会话记录**：记录整个监控过程，或只在指定应用位于前台时采样；提供历史记录、统计与曲线详情，支持点选读数和多选删除。
- **采样与导出**：记录间隔支持 0.25、0.5、1、2、5 秒预设及 250～10000 毫秒自定义；报告可导出为离线 HTML 或完整 CSV 表格。
- **电池曲线**：查看续航周期、应用估算耗电、充电过程与历史曲线。
- **四种采集权限**：默认权限、内置无线 ADB、电脑 ADB、root。自 0.5.0 起无需安装 Shizuku。
- **曲线配置**：调整实时曲线的显示项与配色；手机底部导航可左右滑动切页，平板使用侧边导航。

不同设备、系统和权限下的数据完整程度不同。无法读取的指标显示为缺失，不使用零代替。

## 使用方法

### 初次设置

1. 安装与设备架构匹配的 APK。大多数现代手机和平板使用 `arm64-v8a`；模拟器请按实际架构选择。
2. 按首次设置向导开启需要的权限。使用悬浮窗需允许「显示在其他应用上层」，后台记录时允许通知，以便查看运行状态。
3. 在首页「管理采集权限」或设置页「采集权限」选择采集方式。基础电池信息可直接查看；跨应用帧率及更多硬件指标通常需要 ADB 或 root。
4. 需要后台持续记录时，从应用提供的后台设置入口调整 Raidian 的省电策略。HyperOS 实测需要设为「无限制」，否则系统可能暂停采样。

### 选择采集权限

| 模式 | 操作 | 适用情况 |
| --- | --- | --- |
| 默认权限 | 选择后直接使用 | 基础电池、内存及系统允许读取的信息 |
| 无线 ADB | 在系统无线调试中配对，再连接并启动 | 无需电脑，以 shell 身份采集 |
| 电脑 ADB | 电脑连接设备，执行应用生成的启动命令 | USB 或电脑无线 ADB，以 shell 身份采集 |
| root | 设备已有 root，在管理器允许 Raidian 后启动 | 以 root 身份采集，仍受设备实现影响 |

**无线 ADB：**

1. 在开发者选项中开启「无线调试」，让 Raidian 与系统设置通过分屏或小窗同时可见。
2. 在系统选择「使用配对码配对设备」，把该窗口中的配对端口、六位配对码填入 Raidian，点击「配对」。配对期间保留系统窗口；关闭窗口后端口可能立即失效。
3. 使用无线调试主页面的**连接端口**，点击「连接并启动」；留空时尝试自动发现。配对端口与连接端口不同，不能混用。
4. 确认页面显示已连接。配对身份会保存在本机，后续通常只需重新连接；调试开关或网络变化后端口可能改变。

小米平板的系统设置可能不支持分屏，此时可把 Raidian 放入小窗。Android 17 的自动发现可能需要局域网权限，界面也提供手动端口入口；当前内置通道尚未完成 Android 17 实机验证。

**电脑 ADB：**

1. 安装 [Android SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools)，开启 USB 调试并允许电脑连接，或先在电脑完成无线 ADB 配对。
2. 执行 `adb devices`，确认设备状态为 `device`。
3. 在 Raidian 选择「电脑 ADB」，点击「复制启动命令」，将完整命令在电脑终端执行。多设备连接时，在命令中的 `adb` 后加 `-s 设备序列号`。
4. 返回应用点击「刷新连接」，确认已连接且 UID 为 2000。

命令由当前安装生成，含私有连接凭据，请勿贴进 Issue、公开截图或 README。无需执行 `adb root` 或另行安装 Shizuku。

**root：**

选择 root 模式，点击「请求 root 并启动」，并在 Magisk／KernelSU 等管理器中允许 Raidian。部分管理器需要先手动授权，不一定弹出对话框。此选项不会为设备安装 root。

ADB 服务启动后可拔线或关闭无线调试。**设备重启后需要重新启动服务；更新 APK 后也需停止旧服务，再按新版本页面重新启动。** 选择默认权限会绕过提权通道；要结束已启动的服务，请在对应模式点击「停止所选服务」。

### 记录与查看曲线

1. 在实时页选择「记录目标」。选择「跟随当前前台应用」时不限制目标；指定应用后，切出该应用会暂停写入，返回后继续。
2. 点击「开始记录」，按需打开悬浮窗，然后进入待测应用。
3. 返回 Raidian 停止记录，在「记录」页进入会话详情。
4. 点击曲线进入独立详情页，点按或横向拖动查看读数；暂停段以断线展示。
5. 在会话详情中选择并排的「导出 HTML」或「导出 CSV」，通过系统文件选择器保存。HTML 含统计、图表和图例，可离线打开；CSV 使用 UTF-8 编码并带 BOM，保留全部原始采样列，可用 Excel 等表格软件打开。

设置页「通用」→「会话记录」→「采样间隔」可调整记录间隔，默认 500 毫秒，从下一次记录生效。点击后弹出预设菜单，也可选择「自定义」输入毫秒数。较短间隔增加采集开销和文件体积；帧率、电池及温度仍按各自刷新周期更新。长报告按显示宽度保留峰谷并缓存绘图路径，滚动时只加载可见图表，原始数据与点选读数不做删减。

记录保存在应用私有目录中，会话使用 CSV 与 JSON 保存。实时曲线详情是打开时的快照。卸载或清除应用数据会删除本地记录。

在「续航」页打开「后台记录续航曲线」，随后可在「续航」「充电」页查看对应历史详情；充电过程在插电后自动记录。「设置」分为「通用」「实时曲线」「悬浮窗」三类，采集权限与后台设置集中在「通用」，实时曲线按性能、温度、电池与内存分组。

### 如何理解读数

- 帧率来自 SurfaceFlinger 的可用呈现时间戳。有效图层停止出帧时可以显示 0；权限不足或无法获得图层时显示缺失。
- 会话 Low 帧统计是**采样窗口估算**，不能当作逐帧采集得到的严格 1% Low。设置的间隔是目标采样周期，实际间隔会受命令耗时和系统调度影响。
- 电池功率来自电池电流与电压。连接电源时反映电池端净功率，不能当作充电器输出功率、整机功耗或单个应用的直接功耗。
- 应用耗电来自系统 BatteryStats 的估算，受系统统计周期与功耗模型影响。
- CPU／GPU 温度、频率、占用是否可读由厂商接口与 SELinux 策略决定。例如小米平板 7S Pro 的玄戒 O1 可读取十核 CPU 和实时 HAL 温区，但 ADB 下 GPU 频率与占用仍不可读。

## 编译方法

### 环境

以下版本以仓库配置为准，不需要为了编译手动升级依赖：

| 项目 | 配置 |
| --- | --- |
| Android Studio | 使用支持本项目 AGP 9.4.0 的版本 |
| Gradle | 9.6.0，使用仓库自带 Wrapper |
| Gradle Daemon JDK | 25，由 `gradle/gradle-daemon-jvm.properties` 指定 |
| Java／Kotlin 字节码目标 | 17，与运行 Gradle 的 JDK 版本不同 |
| Android SDK | compileSdk 37、targetSdk 37、minSdk 33 |
| Kotlin／Compose 插件 | 2.4.0 |
| Compose BOM | 2026.02.01，最终解析还受 Miuix 传递依赖约束 |
| Miuix | 0.9.4 |

SDK Manager 需安装 Android SDK Platform 37、对应 Build Tools 与 Platform Tools。Gradle 同步需要访问 Google Maven、Maven Central、Gradle 分发服务和 JitPack；JitPack 用于 SPAKE2 依赖。

克隆或下载完整仓库后，在 Android Studio 打开根目录。配置本机 SDK 路径（由 IDE 生成 `local.properties`），等待 Gradle 同步完成。JDK 自动下载不可用时，安装 JDK 25 并在 IDE 的 Gradle 设置中配置。

`third_party/libadb` 是已纳入仓库的源码模块，必须保留。`references/` 仅为开发参考，不参与构建，也不需要下载这些参考仓库才能编译。

### Debug 与测试

Windows PowerShell：

```powershell
$env:GRADLE_USER_HOME = "$PWD/.gradle-home"
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

macOS／Linux：

```bash
chmod +x gradlew
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

项目包含专用 debug keystore，只用于调试，**不要用于发布签名**。Debug 包与正式签名包不能直接互相覆盖；不要为了调试随意卸载有历史记录的应用。

### 在 Android Studio 构建 Release APK

1. 完成 Gradle 同步。
2. 打开 **Build → Generate Signed App Bundle or APK**，选择 **APK** 与 `app` 模块。
3. 选择自己的发布 keystore、别名与密码，选择 `release` 并生成。已有安装需要沿用原签名，才能保留数据覆盖更新。详细流程见 [Android 官方签名文档](https://developer.android.com/studio/publish/app-signing)。

项目也支持根目录本地文件 `release-signing.properties`，供 Gradle 直接签名。该文件已忽略，以下仅为填写格式：

```properties
storeFile=E:/private-keys/raidian-release.jks
storePassword=REPLACE_WITH_YOUR_STORE_PASSWORD
keyAlias=REPLACE_WITH_YOUR_KEY_ALIAS
keyPassword=REPLACE_WITH_YOUR_KEY_PASSWORD
```

`storeFile` 建议使用绝对路径；Windows 可使用正斜杠。相对路径按 `app/` 目录解析。请保管好自己的密钥与密码，不要上传到 GitHub；已有本地签名配置无需重建。

配置完成后也可在 Android Studio 的 Gradle 面板运行 `:app:assembleRelease`，或使用：

```powershell
./gradlew.bat :app:assembleRelease
```

没有本地签名配置、也未通过 IDE 签名向导构建时，不能把普通 `assembleRelease` 输出当作已签名安装包。

命令行构建产物位于 `app/build/outputs/apk/release/`，按 ABI 分包，不生成 universal APK：

```text
Raidian_0.5.3_arm64-v8a_release.apk
Raidian_0.5.3_armeabi-v7a_release.apk
Raidian_0.5.3_x86_64_release.apk
Raidian_0.5.3_x86_release.apk
```

IDE 签名向导可使用另选的输出目录，以构建完成提示为准。Release 开启 R8 优化与资源压缩；如果修改权限通道，还应验证 Release 下的服务启动和无线配对，不能只测 Debug。

## 致谢

Raidian 的实现离不开以下项目。这里分别列出直接集成的组件和开发时对照的实现，感谢所有作者、维护者和贡献者开放代码、记录兼容问题并持续维护工具。

### 界面、运行时与构建

| 项目 | 在 Raidian 中的用途 |
| --- | --- |
| [Miuix](https://github.com/compose-miuix-ui/miuix) | 核心 UI 组件库。使用 0.9.4 的 UI、Core、Icons、Blur 模块；主题、列表、导航、对话框、图标与模糊效果均受益于其实现及示例。Apache-2.0。 |
| [AndroidX／Jetpack Compose](https://android.googlesource.com/platform/frameworks/support/) | Compose UI、Graphics、Material 3、Activity、Lifecycle、ViewModel、Core、Navigation Event 和 Annotation，提供声明式界面、生命周期与返回事件等基础。主要采用 Apache-2.0，以各组件声明为准。 |
| [Kotlin](https://github.com/JetBrains/kotlin) | 应用语言、标准库及 Compose／Serialization 编译插件。Apache-2.0。 |
| [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | 协程、Flow、任务调度与采样并发控制；通过依赖图引入。Apache-2.0。 |
| [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | JSON 序列化支持。Apache-2.0。 |
| [Gradle](https://github.com/gradle/gradle) 与 [Android Gradle Plugin](https://android.googlesource.com/platform/tools/base/) | 构建、变体、资源处理、测试与 APK 打包；Release 使用 Android 工具链的 R8 优化。 |
| [Foojay Toolchains Resolver](https://github.com/gradle/foojay-toolchains) | Gradle JVM 工具链发现与下载支持。 |
| [JUnit 4](https://github.com/junit-team/junit4)、[AndroidX Test／Espresso](https://github.com/android/android-test) 与 Compose UI Test | JVM 解析与协议测试、Android 设备测试、界面回归。JUnit 4 使用 EPL-1.0，AndroidX 测试组件主要采用 Apache-2.0。 |

### 内置 ADB 与密码组件

| 项目 | 在 Raidian 中的用途 |
| --- | --- |
| [LibADB Android](https://github.com/MuntashirAkon/libadb-android)／MuntashirAkon | 无线 ADB 的核心实现。固定 3.1.1 源码随仓库集成，采用其 Apache-2.0 授权选项；保留原始声明，补充超时、失败清理、身份缓存与连接竞态修复。 |
| [SPAKE2 Java／Android](https://github.com/MuntashirAkon/spake2-java) | 2.2.1 配对密码协议组件，由 LibADB 引入，支持 Android 无线调试配对；该版本发布元数据标注 LGPL-3.0，不能因 LibADB 选择 Apache-2.0 而忽略这一依赖的独立许可。 |
| [Conscrypt](https://github.com/google/conscrypt) | 2.5.3 Android TLS 实现，供无线 ADB 握手使用，避免访问系统隐藏 Provider API。Apache-2.0 及原生依赖声明。 |
| [Bouncy Castle](https://github.com/bcgit/bc-java) | 1.81 的 bcprov／bcpkix 及其传递模块，用于密钥、证书与密码基础设施。Bouncy Castle License。 |
| [Android Open Source Project](https://android.googlesource.com/) | ADB、SurfaceFlinger、BatteryStats 和系统接口的基础；无线配对实现沿用上游 AOSP 协议。感谢其文档与代码提供可核对的数据语义。 |
| [Shizuku](https://github.com/RikkaApps/Shizuku)／RikkaApps | 早期版本的提权基础，也是独立 `app_process` 服务模型的重要参考。当前应用不再依赖 Shizuku API、Provider 或管理器；LibADB 的上游实现仍包含相关来源声明。 |

随附源码的准确版本、提交号、补丁与许可文件见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 和 [LibADB 许可说明](third_party/libadb/COPYING)。

也感谢 LibADB 源文件中保留署名的 Cameron Gutman、Sam Palmer、南宫雪珊与 Google 等原始贡献者。其 ADB 协议、流、认证、mDNS 和随机数兼容代码使上层集成成为可能。LibADB 的模拟器识别辅助代码还标注了 [Firebase Android SDK](https://github.com/firebase/firebase-android-sdk) 的来源；Raidian 未集成 Firebase 服务或 Crashlytics。

### 性能采集、悬浮窗与电池功能参考

这些项目帮助 Raidian 核对设备数据源、单位、帧时间戳及交互设计，不作为完整应用打包进 APK。许可名称描述各参考项目自身，具体以其对应版本声明为准。

| 项目／作者 | 参考内容 | 上游许可 |
| --- | --- | --- |
| [Scene／vtools](https://github.com/helloklf/vtools)／helloklf | 本项目性能监控方向的主要启发。对照 CPU 频率与负载、GPU、温度、电池电流与功率、内存、网络、浮窗和会话记录实现，尤其重视设备路径与单位换算。Raidian 仅聚焦监控，不实现 Scene 的全部系统调节功能。 | GPL-3.0 |
| [FrameX-Android](https://github.com/MaheshSharan/FrameX-Android)／MaheshSharan | 参考 shell／Shizuku 身份下的命令执行、CPU 数据解析、thermal service 温度解析、诊断与采样组织。 | MIT |
| [FPS-Meter-Android](https://github.com/rdevz-ph/FPS-Meter-Android)／rdevz-ph、Romel Brosas | 参考 SurfaceFlinger 跨应用帧率测量、渲染图层筛选、多候选尝试与层名兼容处理。 | MIT |
| [Minimal Kernel Manager（mkm）](https://github.com/abhay-byte/mkm)／Abhay Raj | 对照 SurfaceFlinger latency 时间戳语义、无数据判断和帧率读取的兼容思路，也作为硬件监控组织方式的参考。 | GPL-3.0-or-later |
| [atop](https://github.com/chandradeep24/atop)／chandradeep24、theNullCrown | 参考轻量浮窗、采样线程、功率展示及低帧统计思路。Raidian 当前 Low 帧指标仍是采样窗口估算，不等同于严格逐帧统计。 | MIT |
| [DebugOverlay-Android](https://github.com/Manabu-GT/DebugOverlay-Android)／Manabu-GT | 参考诊断浮层和监控数据聚合的架构。该项目面向嵌入式调试，与 Raidian 的跨应用监控定位不同。 | Apache-2.0 |
| [Cumulus-APP](https://github.com/chenzyadb/Cumulus-APP)／chenzyadb | 参考续航周期、充电过程与电池曲线的功能组织。Raidian 的系统续航／亮屏及每 UID 估算耗电解析依据本机 BatteryStats 输出实现，数据口径与 Cumulus 自采样统计不同。 | GPL-3.0 |

也感谢 Linux CPUFreq 文档、Android 开发者文档，以及提供真实设备、日志和问题反馈的测试者。厂商接口差异很大，实机反馈对确认读数和修复兼容问题尤其重要。

### 名称与图标

感谢《明日方舟》及鹰角网络的角色创作。名称与图标灵感来自电弧；图标为 AI 辅助生成的同人风格插画，不是官方素材或官方授权标识。感谢 [PRTS](https://prts.wiki/w/Raidian) 提供角色资料。

图标原稿及导出说明位于 [docs/assets](docs/assets/README.md)。各开源组件的代码许可不代表对角色、美术或商标的授权。

## 开发与反馈

权限通道设计、测试命令和已验证范围见 [docs/PRIVILEGE.md](docs/PRIVILEGE.md)。当前内置通道已在 MuMu Android 15 和小米平板 7S Pro Android 16 做过回归；实体设备 root、Android 17 新通道及长时间稳定性仍需更多验证。

提交问题时请附设备型号、系统版本、应用版本、所选权限模式、复现步骤和缺失的指标。日志与截图请移除无线配对码、电脑启动命令中的凭据等私有信息。

本仓库目前尚未为 Raidian 自有代码声明统一许可证；第三方代码继续遵循各自许可。致谢不替代随附源码的版权和许可证声明。
