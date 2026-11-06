# 内置采集权限与上机调试

## 使用方式

首页「管理采集权限」、设置页「采集权限」以及首次设置向导进入同一页面。

| 模式 | 启动方式 | 实际采集身份 |
| --- | --- | --- |
| 默认权限 | 无需操作 | 应用 UID |
| 无线 ADB | 系统开启无线调试；填写配对端口及六位配对码；再用连接端口启动或自动发现 | shell，UID 2000 |
| 电脑 ADB | 通过 USB 或电脑无线 ADB 连接设备；执行页面中复制的完整命令 | shell，UID 2000 |
| root | 设备已有 root；点击「请求 root 并启动」，在 root 管理器允许 Raidian | root，UID 0 |

无线调试的配对端口和连接端口是两个独立端口。用分屏或小窗保留系统配对窗口，窗口关闭会使该配对端口失效。小米平板的系统设置可能不支持分屏，此时将 Raidian 放入小窗即可。连接端口可以随网络／调试开关变化。只连接本机，不提供远程设备控制。

电脑需安装 Android Platform Tools。先用 `adb devices` 确认授权；多设备时使用 `adb -s 序列号 shell …`。页面提供适用于 PowerShell、cmd、bash／zsh 的完整命令，不需要 `pm grant`、安装 Shizuku 或 `adb root`。电脑命令包含安装级凭据，不能公开分享。

服务启动后可以拔线或关闭无线调试。应用被杀后可重新连接仍存活的服务；设备重启后需重新启动。安装新 APK 后，界面会识别旧版本服务并要求停止、重新启动。root 授权不会因单纯刷新或启动应用而自动弹出。

选择默认权限会立即绕过提权通道，但不会结束已经启动的服务；要结束服务，请在对应 ADB／root 模式点击「停止所选服务」。无线和电脑 ADB 共用 shell 服务，root 服务独立。不可读数据保留缺失值，不能解释成零。

## 实现

`PrivilegeManager` 是 Application 级状态源。`CommandExecutor` 为所有采集器提供唯一入口，服务不可用时退回应用自身权限。`MonitorSampler` 按身份重置 CPU 差分与短时缓存。温度与硬件快照在提权进程中直接 JVM 读文件，保留已有低开销采样方式。

`RunnerServer.main` 从系统安装目录的只读 APK 通过 `app_process` 启动，没有 ActivityManager 隐藏 API或导出的命令执行组件。root 保持 Linux abstract Unix socket 和双向 `SO_PEERCRED` 校验。小米平板实测禁止应用连接 shell Unix socket，因此 ADB 改为仅绑定 `127.0.0.1` 的 TCP 通道，不绑定局域网接口。

ADB 通道使用应用私有目录内的 256 位安装级凭据，进行双方各 32 字节随机挑战的 HMAC-SHA256 双向认证，绑定协议版本、应用 UID、shell 身份与角色。凭据不经过 socket 发送。每条连接只有一个请求，命令与响应也分别使用本次挑战派生的会话密钥校验完整内容，防止端口抢占者转发认证后替换命令、伪造响应或重放其他连接的数据。TCP 不提供 `SO_PEERCRED`，其安全边界是安装凭据，不声称由内核验证对端 UID。实际启动入口仍只接受 UID 2000／0。凭据与 ADB 私钥均放在 `noBackupFilesDir`。

协议使用有界 UTF-8 数据帧（最多 2 MiB），请求和响应有超时；shell 命令沿用 4 秒限制和 1 MiB 输出限制。未认证连接限时 1 秒；端口候选集限于 8 个，处理端口冲突而不扫描网络。已发送的命令不会因响应失败而重试。命令超时不返回半截成功结果。服务处理坏请求后继续接收后续请求。R8 keep 规则保留 `app_process` 入口及 LibADB 反射调用的 Conscrypt Provider／TLS exporter。

无线连接使用仓库内固定版本 LibADB，补丁和许可证见 `THIRD_PARTY_NOTICES.md`。不复制完整 Shizuku 管理器；四种权限方式不依赖 Shizuku 安装状态。

## Android 17

项目保持 compileSdk／targetSdk 37。自动发现使用 NSD；Android 17 在用户点击自动发现时请求 `ACCESS_LOCAL_NETWORK`，拒绝后保留手动填写端口的入口。官方规则：https://developer.android.com/privacy-and-security/local-network-permission 。

本机服务不使用隐藏 Binder 接口，降低系统内部 API 变化的影响。但这不等于已验证 Android 17：OEM SELinux 对 app／shell Unix socket 的限制、root 管理器行为、无线调试本机回环与 NSD 均需实机测试。系统未开放的 GPU／thermal 节点仍可能不可读。

## 构建与测试

PowerShell：

```powershell
$env:GRADLE_USER_HOME = "$PWD/.gradle-home"
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease
```

设备端真实通道测试：

```text
adb -P 15037 -s 设备序列号 install -r app/build/outputs/apk/debug/Raidian_0.5.2_x86_64_debug.apk
adb -P 15037 -s 设备序列号 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -P 15037 -s 设备序列号 shell am instrument -w -e class yy.daniel.raidian.PrivilegeIntegrationTest yy.daniel.raidian.test/androidx.test.runner.AndroidJUnitRunner
```

测试设备必须为正常 shell ADB（不要先执行 `adb root`）。测试会切换权限模式、启动／停止测试服务；请勿在正在进行的重要记录中运行。root 测试默认跳过，追加 `-e root true` 启用，提前在 root 管理器中允许本应用。ARM 真机改用 arm64-v8a APK；已有 release 签名安装不可直接被 debug 签名覆盖，不要为测试卸载或清数据。

真机回归还需覆盖：错误配对码／过期端口／拒绝 root、关闭无线调试后持续记录、强制停止应用后重连、设备重启、APK 更新旧服务检测、横竖屏、首次向导、后台游戏＋浮窗＋CSV 连续采集。MuMu 的虚拟 CPU／GPU 不代表小米真机 sysfs／thermal 支持。

## 2026-11-06 验证记录

- 24 项 JVM 单测通过，包含 5 项协议长度边界、截断、Unicode、大响应及身份隔离测试。
- MuMu Android 15／x86_64：默认权限、电脑 ADB（UID 2000）、KernelSU root（UID 0）测试通过。root 由用户在 KernelSU 中手动允许，未修改系统 SELinux 策略。
- 真实系统无线配对成功；手动连接端口、NSD 自动发现、保存密钥后重新启动均通过。修复本机 PTY 退出过快引起的启动竞态：服务通过 `nohup setsid` 独立运行，无线启动 shell 保留短暂宽限，成功与否以本机认证握手为准。
- 4 项设备回归通过：三项通道测试及一项 Compose UI／记录测试。覆盖错误安装凭据拒绝、命令超时后继续采集、服务停止后的默认降级和重连、四模式切换、root 按钮启动。8.361 秒会话实际记录 14 点，CSV 15 行含表头，session.json 的 recordedSamples 为 14。
- ARM64 的 Conscrypt／SPAKE2 原生库 LOAD 对齐均为 0x4000（16 KiB）。不代表已在 16 KiB 页设备上运行。
- release 的 R8／lintVital 构建通过；另将优化后 APK 作为只读文件交给 app_process，实测入口保留、root UID 0 和硬件快照读取通过，未替换模拟器的 debug 签名安装。
- MuMu 测试期间发生过原生窗口／系统服务无响应，曾重启并临时使用 DirectX。验证后将配置恢复为原 Vulkan，下次启动生效。本次未清除模拟器或应用数据。
- 上述为 MuMu 阶段结果；实体设备回归见下节，Android 17 仍未验证，不能解释成所有 OEM 兼容保证。

## 同日：小米平板 7S Pro Release 回归

设备 Android 16／玄戒 O1，0.4.1 Release 同签名覆盖升级为 0.5.0，保留历史记录与电池曲线服务。测试未使用 root，也未安装 debug。

初版在 MuMu 可用的 app→shell Unix socket，在此设备明确返回 Permission denied。已改用上述双向认证且逐消息校验的回环 TCP；root 继续原有 Unix socket 协议。新增 6 项认证／消息完整性单测，合计 30 项通过，Release／R8／lintVital 构建通过。

| 项目 | 实测结果 |
| --- | --- |
| 默认权限 | 基础电池／内存／允许读取的频率正常，权限不足指标保持缺失 |
| 电脑 ADB | 页面命令启动，实际 shell UID 2000，停止／恢复正常 |
| 无线 ADB | Release 自身真实配对、手动端口、NSD、持久密钥重连通过 |
| 生命周期 | 应用强停后重连、关闭无线调试后持续采集通过 |
| APK 更新 | 识别旧 runner 并提示重启，停止后执行新命令恢复 |
| 失败恢复 | 关闭的无线端口连接失败可继续操作；错误安装凭据与篡改请求被拒绝；超时约 4.022 秒后继续正常读取 |
| 数据 | 十核、四频率簇、38 个实时 HAL 温区；O1 GPU 频率／占用仍不开放 |
| 后台记录 | 1 分 08 秒／124 点；CPU 8 频率 124 有效、0 缺失，帧率 120 有效窗口 |
| 浮窗＋记录 | 1 分 01 秒／106 点，帧率 106 有效窗口；启动动画约 60 FPS |

两段记录通过 Release 历史列表、详情和曲线重开验证持久化；没有直接导出应用私有 CSV。游戏测试停留在《明日方舟》启动动画，没有操作游戏进度。这是短时回归，Android 17、设备重启及长时间压力测试仍待验证。

收尾关闭了测试浮窗／录制／无线调试，保留电脑 ADB 采集服务。原指定目标“鸣潮”已卸载，记录目标改为“跟随当前前台应用”。原有两条历史和两段有效测试记录均保留，空测试记录已清理。
