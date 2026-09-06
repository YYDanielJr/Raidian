# Raidian — 阶段性总结与后续开发指引

> 本文件是给后续 AI 智能体(agent)的阶段交接文档。请先完整阅读,再继续开发。
> 最近更新:2026-09-06(阶段八:设置页重构为通用「设置」两页导航,见 3.11)

## 1. 项目目标

对标安卓工具 **Scene**(helloklf 开发,包名 `com.omarea.vtools`)的**性能监控**部分,做一个替代应用:

- Jetpack Compose UI,使用 **Miuix**(HyperOS 风格组件库)
- **后台记录**:记录某个程序运行全过程,输出温度/帧率/功耗曲线,平均帧、1% Low 帧等统计
- **前台悬浮窗**:小巧不挡游戏视线,显示主要参数,可自由配置显示内容
- **Shizuku 提权**(不做 root;用户当前无 root 设备)
- 文案不用 AI 腔、无 emoji、标点全角

设备:小米 13 Ultra(`2304FPN6DC`, ishtar),Android 16 (SDK 36),HyperOS V816。

## 2. 重要铁律:后端数据采集必须参考成熟开源实现

**不要让 agent 自己"想当然"设计采集逻辑或单位换算。** 本目录下已克隆多个开源参考,采集实现必须对照它们:

```
references/scene-alternatives/
├── vtools/                 # Scene 本体开源(最权威,但 2022 停更)
│     app/src/main/java/com/omarea/
│       library/shell/      # 数据读取: CpuFrequencyUtils / CpuLoadUtils / GpuUtils /
│                           #   BatteryUtils / ThermalControlUtils / FpsUtils / MemoryUtils / NetworkUtils
│       model/              # CpuStatus / BatteryStatus / BatteryAvgStatus 等数据模型
│       data/publisher|customer/  # 数据流(发布订阅)
│       vtools/popup/       # FloatMonitor(悬浮窗参考)
│       ChargeCurve.kt / ElectricityUnit 等(单位换算参考)
├── FrameX-Android/         # 2026 活跃,Shizuku 性能浮窗(最贴近我们的技术路线)
│     app/src/main/java/com/framex/app/
│       shizuku/            # ShizukuManager / CommandRunnerService(UserService 模式)
│       metrics/            # MetricsEngine / CpuInfoTopParser / ThermalServiceParser / SessionLogger
│       device/             # DeviceDiagnosticManager
├── FPS-Meter-Android/      # 2026 活跃,PerfZ 风格 FPS 浮窗
│     app/src/main/java/com/rdevzph/fpsmeter/overlay/
│       SurfaceFlingerFpsMonitor.kt   # 跨应用真实 FPS 读取(层名解析 + --latency 帧时间戳)
├── DebugOverlay-Android/   # 诊断浮窗库(架构参考)
├── atop/                   # 极简悬浮 FPS/功耗(FPS 百分位算法参考)
└── mkm/                    # 内核管理+监控(可选参考)

README.md 是各仓库的索引说明。后台数据采集的写法、路径、单位换算,一律先查 vtools / FrameX 怎么实现,再动手。
```

### 为什么特别强调这条
- 温度/功耗/电流的**单位换算**极易出错(不同厂商 sysfs 返回 nA / µA / mA,温度有 0.1°C 与 m°C 两种)。Scene 用 `ElectricityUnit` 动态换算电流单位;温度读 `dumpsys battery` 的 `temperature` 字段(0.1°C 单位)。
- 悬浮窗、GPU、FPS 都有成熟的设备适配写法,自行发明会踩 SELinux / 厂商差异的坑。
- **小米 13 Ultra 实测约束(必须记住)**:
  - CPU 频率:`/sys/devices/system/cpu/cpuX/cpufreq/scaling_cur_freq`(Hz,需 /1000 → MHz),shell(shizuku)身份**可读**
  - thermal zones:`/sys/class/thermal/thermal_zone*`(m°C,需 /1000 → °C),shell 身份**可读**
  - GPU 占用(高通 Adreno):`/sys/class/kgsl/kgsl-3d0/gpubusy` → **shell 身份可读**,输出 `busy total` 两个累计值(每读一次即复位,是"当前窗口"计数),占用率 = busy/total × 100。注意 cat 输出带前导空格(实测 ` 652988 1022170`),正则必须用 `B=\s*(\d+)\s+(\d+)` 之类容错写法。GPU 游戏运行实测 ~15-66%,桌面 0-2%
  - GPU 频率:kgsl/gpu 频率节点对 shell 身份全被 SELinux 拒(`/sys/kernel/gpu/*`、`/sys/class/kgsl/kgsl-3d0/gpuclk`、`/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq`、`/sys/class/devfreq/*` 连 ls 都拒),无 root 读不到 → gpuFreq 恒 -1(UI 显示 "GPU --MHz" 但占用 % 可出数,悬浮窗格式为 `GPU 57%` 或 `GPU 900MHz 57%`(有 root 时))
  - thermal zones 里 `gpuss-N`(N=0..7)是 GPU 温度(实测游戏 43-45°C,归类时注意当前代码只分 cpu/soc,GPU zone 落 maxOther)
  - 电池 sysfs `/sys/class/power_supply/battery/current_now` → **shell 身份被拒**;改用 `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW`(应用可读,µA 单位)或 `dumpsys battery`
  - SurfaceFlinger:`service call SurfaceFlinger 1013` 被拒;`dumpsys SurfaceFlinger --latency <layer>` / `--list` shell 身份**可读**(FPS 用这个)
  - `dumpsys battery`、`dumpsys gfxinfo` shell 身份可读

## 3. 已完成(实机验证)

### 3.1 架构总览

```
yy.daniel.raidian
├── RaidianApp.kt              # Application:全局 DI + 装配 + Shizuku 初始化
├── MainActivity.kt            # Miuix 主题入口
├── monitor/                   # 后端采集与记录
│   ├── ShizukuShell.kt        # Shizuku 通道(绑定 UserService)
│   ├── CommandExecutor.kt     # 命令执行:优先 Shizuku,降级 app 内 sh
│   ├── MonitorSampler.kt      # 采样器:CPU/GPU/温度/功耗/内存/网络/前台
│   ├── AppStateReader.kt      # 前台应用识别 + FPS 状态
│   ├── SurfaceFlingerFpsReader.kt # FPS 读取(层名 + --latency)
│   ├── SessionRecorder.kt     # 会话记录(CSV + 平均帧/1%Low 统计)
│   ├── Sample.kt              # 采样数据结构(CSV 序列化)
│   └── MonitorService.kt      # 前台服务(承载后台)
├── shizuku/
│   ├── CommandRunnerService.kt # Shizuku UserService(提权进程内跑 shell / 直接读文件)
│   └── CommandResult.java      # AIDL 结果类型
├── aidl/yy/daniel/raidian/shizuku/
│   ├── ICommandRunner.aidl     # 含 executeCommand / readThermalZones / destroy
│   └── CommandResult.aidl
└── overlay/
    ├── OverlayController.kt   # Application 级悬浮窗控制 + 全局轮询(关键!)
    └── OverlayWindow.kt       # WindowManager 悬浮窗(可拖动/点按穿透切换)
```

### 3.2 关键决策与已验证事实

1. **Shizuku 接入方式**(踩坑后修正):
   - 必须在 manifest 注册 `rikka.shizuku.ShizukuProvider`(authorities=`${applicationId}.shizuku`),否则 app 检测不到 Shizuku 服务
   - Shizuku v13 的 `Shizuku.newProcess` 是 **private**,不可用;正确做法是 **UserService** 模式:
     - `CommandRunnerService` 实现 AIDL `ICommandRunner.Stub`,提供 `()` 与 `(Context)` 构造函数
     - `Shizuku.bindUserService(UserServiceArgs(ComponentName(...)).daemon(true).tag(...).processNameSuffix("runner"))`
     - Shizuku 会 fork 出 `yy.daniel.raidian:runner` 独立进程,以 shell 身份运行
   - 授权流程:用户点"请求授权" → Shizuku 系统对话框 → 授权后 `checkSelfPermission()==GRANTED`
   - 实机日志已确认:`binderAlive=true ver=13 hasPerm=true`,`runner` 进程存活

2. **温度读取**(踩坑后修正):不要在 app 进程里 cat sysfs(被拒且慢)。
   - 在 `ICommandRunner` 增加 `readThermalZones()`,`CommandRunnerService` 里**直接 JVM File 遍历** `/sys/class/thermal/thermal_zone*`(提权进程内,无 shell 开销,毫秒级)
   - 解析 "type temp"(m°C),分类:cpu*/cpuss* → cpu;soc/apc/aoss*/tsens → soc
   - **不要**用 shell for 循环逐 zone cat —— 实测 106 个 zone 要 9 秒,会拖垮采样

3. **功耗/电流换算**(踩坑后修正):
   - 温度/电量/电压/充电状态:`dumpsys battery`(shell 可读,稳定,Scene 同款)
   - 电流:`BatteryManager.BATTERY_PROPERTY_CURRENT_NOW`(返回 µA,需 /1000 → mA;小米是 µA)
   - 功耗 mW = mA × mV / 1000
   - 已实机验证:充电时 ~4W、待机 ~1.5W 合理值;核心温度 42~72°C 正常(曾出现 4000W 的换算 bug,已修)

4. **FPS 读取**:FPS-Meter 式 — 前台包名 → `dumpsys SurfaceFlinger --list`/grep 找 layer → `dumpsys SurfaceFlinger --latency <layer>` 解析帧时间戳算 FPS。
   已实现但**未在游戏/高帧场景实测**(桌面静止无渲染层时返回 -1 正常)。见待办。

5. **悬浮窗常驻**(架构关键):数据轮询放在 **Application 级 OverlayController**(CoroutineScope 独立于 Activity),不在 Activity/ViewModel。
   - 主界面 Activity 销毁(切去玩游戏)后,悬浮窗仍持续更新
   - 实机验证:显示悬浮窗后切桌面,悬浮窗数据持续变化(用户确认)

6. **会话记录**:SessionRecorder 独立协程循环,500ms 采样写 CSV(files/raidian/sessions/<ts>/{samples.csv, session.json}),每次采样 flush 防丢失。已实机验证 CSV 落盘、CPU 频率三簇/占用率/温度/内存/前台包名都正常记录。

### 3.3 阶段二(2026-09-05):UI 完善 + 悬浮窗配置 + 曲线 + 采样性能修复(全部实机验证)

新增/修改文件:
```
├── overlay/OverlayPrefs.kt        # 新增:悬浮窗配置持久化(SharedPreferences)
├── overlay/OverlayController.kt   # 加 prefs / setEnabledItems / setTouchThrough / setTextSize / 历史缓冲
├── overlay/OverlayWindow.kt       # 读 prefs 记忆位置/字号/穿透;View 操作统一切主线程(修黑块)
├── monitor/SessionRecorder.kt     # SessionMeta 增 avgPowerMw/avgBatteryTemp/avgCpuTemp 均值
├── monitor/SessionStore.kt        # 新增:会话目录枚举 + session.json/CSV 解析(手写轻量 JSON/CSV)
├── monitor/MonitorSampler.kt      # 合并采样:CPU频率/负载/内存/网络一次 shell(原 ~9 次 binder → ~4 次)
├── monitor/SurfaceFlingerFpsReader.kt # layer 解析失败退避 3s;新增 readFpsForPkg(免重复查前台)
├── monitor/AppStateReader.kt      # 新增 readFpsFor(pkg) 复用已知前台
├── monitor/CommandExecutor.kt     # thermal 降级 for 循环加 timeout 2 限时(防 3.7s 拖垮)
├── shizuku/CommandRunnerService.kt # executeCommand 加 4s 超时+daemon 读线程;readThermalZones 去掉 canRead 预检
├── ui/MainScreen.kt               # 重写:三页(TabRow: 实时/记录/悬浮窗) + 详情页导航
├── ui/MainViewModel.kt            # 会话列表/详情加载、UI 级采样(悬浮窗显示时自停)
└── ui/Chart.kt                    # 新增:Compose Canvas 折线图 LineChart
```

功能(全部实机验证):
1. **主界面三页导航**(Miuix `TabRow`):实时(数据卡+曲线+记录控制+悬浮窗开关+Shizuku 状态)/ 记录(历史列表+会话入口)/ 悬浮窗(配置页)。
2. **悬浮窗配置页**:8 个显示项开关(Miuix `Switch`,即时生效)、触摸穿透开关、字号 ±(8~18sp),全部写 SharedPreferences,悬浮窗拖动位置也记忆。
3. **会话历史 + 详情**:记录页列出 files/raidian/sessions 全部会话(目标应用/时长/均值功耗/时间),点入详情:返回图标(Miuix `IconButton`+`MiuixIcons.Back`,0.9.3 里图标用 `MiuixIcons.Back` 或 `MiuixIcons.Light.X`,import `top.yukonga.miuix.kmp.icon.extended.X`)、统计卡(帧率/均功耗/均电池温/均核心温)、Canvas 曲线(帧率/功耗/电池温度/核心温度)。系统返回键 BackHandler 关详情。
4. **session.json 统计**:stop 时写入 endTime/durationMs/sampleCount + avgPowerMw/avgBatteryTemp/avgCpuTemp(实机 12s 会话:43mW / 33.4°C / 44.9°C)。
5. **实时曲线**:OverlayController 维护最近 240 点历史(≈2 分钟),实时页画帧率/温度/功耗三条线。
6. **Miuix API 踩坑记录(0.9.3)**:
   - 本地源码在 `references/miuix-0.9.3/`(0.9.3 精确版,Kotlin 2.4.0),docs 与 example 可查;**main 分支(references/miuix-main)API 已变,勿照抄**
   - TopAppBar 0.9.3 参数为 `navigationIcon`/`actions`/`subtitle`(subtitle 是 String 非可空,别传 null)
   - Divider 组件名是 `HorizontalDivider`/`VerticalDivider`(无 Divider);配色用 MiuixTheme.colorScheme,无 dividerColor 字段
   - Switch: `Switch(checked, onCheckedChange)`;TabRow: `TabRow(tabs=List<String>, selectedTabIndex, onTabSelected)`
   - 图标取值 `MiuixIcons.Back`(extended 包里 `val MiuixIcons.Back`),IconButton 在 0.9.3 basic 已有

采样性能修复(重要,实机验证):
- **症状**:每次采样 binder 3.7~4s(CSV 间隔 ~4.2s),logcat `BpBinder PerfMonitor binderTransact time=3800ms interface=ICommandRunner`。
- **根因一**:CommandExecutor.readThermalZones 的 Shizuku 降级分支用 shell for 循环逐 zone cat —— 实测 106 zone **3.7s**(AGENTS.md 原警告的 9s 场景,runner 的 JVM File 遍历一旦返回空就会触发)。为何 JVM 遍历返回空:CommandRunnerService 里 `File.canRead()` 在 app_process(shell 身份)域下恒 false → 整段被跳过 → fallback。
- **根因二**:SurfaceFlingerFpsReader 桌面无 layer 时每次 sample 全量 dumpsys(慢)。
- **修复**:CommandRunnerService 去掉 canRead 预检,直接 try readText;executeCommand 加 4s 超时 + daemon 读线程;fallback for 循环套 `timeout 2`;FPS layer 解析失败退避 3s;采样合并为 4 次 binder。
- **效果**:CSV 间隔回到 ~550ms。**注意:重装 APK 后 daemon(true) 的旧 runner 进程不会自动退出**,旧代码会继续被新 app 绑定,必须 `pkill -f 'yy.daniel.raidian:runner'` 或重启再验。

悬浮窗黑块修复:
- OverlayWindow 的 update/show/hide 原本在轮询协程(Dispatchers.Default)直接操作 View → CalledFromWrongThreadException 被吞,只剩黑底。改为 mainHandler.post 切主线程。

已删:ui/theme/Color.kt、Type.kt(旧 Material3 模板残留,无引用)。

### 3.4 实机验证数据样例(小米 13 Ultra)

CSV 行片段(单位已正确):
```
2016,1920,864,19.7,... 34.4,57.0,46.5,-1,979,4161,4073,76,1,7364,11190,"com.miui.home","系统桌面"
# cpu_l/cpu_m/cpu_b MHz, cpu_load%, 电池温, max_cpu_temp, max_soc_temp, 电流mA, 电压mV, 功耗mW, 电量%, 内存, 前台包名
```
核心温度随负载 42~72°C 波动、功耗 1.5~4W,均符合物理实际。

### 3.5 阶段三(2026-09-06):UI 重构为"主页只放实时 + 子页独立 Activity" + HyperOS 列表规范 + 多选删除(大部分实机验证;**多选删除确认对话框崩溃已在本次会话修复并实机验证,见 5.x #13**)

本轮起因是用户三条 UI 要求,随后多轮迭代:
1. 列表项 style:主文本 17sp Medium / 副文本 14sp Normal;
2. 列表项与 HyperOS 一致:内容贴容器、无上下 padding、无分割线、按压反馈贴合实际行区域;
3. 主页只放"实时"内容,记录与悬浮窗改顶栏右侧入口,进入时**新建 Activity**。

随后迭代:标题上滑回缩(collapsible TopAppBar)、会话详情也独立 Activity、记录页多选删除(长按选中 + 底部红色删除 FAB + 顶栏 SelectAll 全选)。**最后一步删除确认对话框(改用 Miuix OverlayDialog)曾崩溃,已在本次会话修复并实机验证(见 5.x #13)。**

新增/修改文件:
```
├── ui/HyperList.kt               # 新增(阶段三基础):HyperOS 分组列表组件
│                                  #   ListType(ListMain 17sp/Medium, ListSecondary 14sp/Normal)
│                                  #   HyperListGroup(圆角容器 clip 行内容,无内部 padding)
│                                  #   HyperListRow(整行可点,按压遮罩按行矩形,弹簧渐入渐出)
│                                  #   ListRowContent(标题+副标题+尾部;行高 单行56dp/双行68dp)
│                                  #   onLongClick 支持(combinedClickable,供多选)
├── ui/UiKit.kt                   # SubPageScaffold(子页骨架:TopAppBar 可折叠 + 返回 + actions)
│                                  #   SectionHeader / HyperCard(共享)
├── ui/Widgets.kt                 # 数值展示块:SampleGrid/DataCell/ChartRow/DetailChart/InfoRow
├── ui/MainScreen.kt              # 主页:仅实时页(LivePage),顶栏右侧 记录/悬浮窗 两入口按钮
├── ui/RecordsActivity.kt         # 记录列表 Activity(历史记录数据)
│                                  #   多选:长按进入选择 → Checkbox 行 → 底部红色 FAB →
│                                  #   OverlayDialog 删除确认(⚠ 崩溃未决)→ deleteSessions
├── ui/SessionDetailActivity.kt   # 会话详情独立 Activity(Intent extra 传会话目录)
├── ui/OverlaySettingsActivity.kt # 悬浮窗设置独立 Activity(HyperList 开关行)
├── monitor/SessionStore.kt       # 新增 deleteDirs(递归删除,返回成功数)
├── ui/MainViewModel.kt           # 新增 deleteSessions(IO 删除后刷新)
└── AndroidManifest.xml           # 注册 RecordsActivity / OverlaySettingsActivity / SessionDetailActivity
```

功能与已验证要点:
1. **主页只放实时页**:MainActivity → MainScreen(实时数据网格+曲线+记录控制+悬浮窗开关+Shizuku 状态),顶栏右侧两个 IconButton(ListView=记录 / Settings=悬浮窗)分别 startActivity 打开独立 Activity。
2. **子页都是独立 Activity**:RecordsActivity(会话列表)、SessionDetailActivity(详情,extra 传目录绝对路径)、OverlaySettingsActivity(悬浮窗配置)。
3. **标题回缩**:Miuix TopAppBar scrollBehavior + 内容区 `Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)`(SubPageScaffold 内部已包好;主页 MainScreen 手动包 Box)。实机验证主页/记录页上滑大标题回缩为小标题正常。
4. **HyperList 组件**(HyperOS 列表观感核心):
   - 行内按压反馈 `drawBehind` 画 `onSurface` 半透明矩形,alpha 由 Animatable + folmeSpring 驱动(按下 damping=1.0/response=0.2,松开 0.95/0.35 —— 与 MiuixIndication 一致),渐入渐出不闪烁;
   - 行高:仅标题 56dp,带副标题 68dp(`heightIn` 抬升);
   - 分组容器 clip 圆角 → 首/末行按压矩形自动被裁出上圆/下直角,与 HyperOS 一致;
   - 行尾默认 ChevronForward;多选态改 Checkbox。
5. **多选删除(除对话框外均可用,实机验证)**:长按进入多选并选中该行;选中态下标题不变、副标题变「已选 N 项」;顶栏 SelectAll 图标(全选时 primary 蓝,否则 onSurface 黑);底部中央红色圆形 FAB(Miuix FloatingActionButton,containerColor #E53935 + 白色 Delete 图标);点击后弹确认。确认后 `vm.deleteSessions(dirs)` → SessionStore.deleteDirs 递归删 → 刷新列表。
6. **Miuix dialog 踩坑**(崩溃详情见 #13):OverlayDialog 文档要求必须在 Scaffold 内(requiresScaffoldHost: true,Scaffold 自带 MiuixPopupHost)。

### 3.6 实机验证补充(阶段三,截图在 E:\SourceFiles\androidstudio\Raidian\_shots\)
- 主页实时:顶栏大标题「Raidian 性能监控器」+ 右上记录/悬浮窗入口按钮;滚动后标题回缩,均正常。
- 记录页「历史记录数据」:历史会话列表(HyperList 双行:标题+副标题摘要,行高正常,无分割线)、点击进详情(独立 Activity)、长按进入多选正常。
- 会话详情独立 Activity:信息卡 + 曲线,正常。
- 悬浮窗设置独立 Activity:显示项开关行等正常。

### 3.7 阶段四(2026-09-06):FPS 与 GPU 占用修复 —— 明日方舟:终末地实机验证通过

起因:用户实机反馈"读取不到 FPS 和 GPU 占用",连接小米 13 Ultra 并前台运行明日方舟:终末地(`com.hypergryph.endfield`,Unity + U8 SDK,SurfaceView BLAST 渲染)供采集。

**对照的开源实现**:
- `references/scene-alternatives/mkm/`(最新内核监控)对 `--latency` 列语义研究最透:`--latency` 每行三列是 `frameNum desired actual`(或 desired/actual/ready),**要信第 2 列 actual present**;行数 <3(退化输出 SoloX#303)要判"无数据"而非 0fps;还有 `--latency-frameinfo` / gfxinfo 兜底链。
- `FPS-Meter-Android` 的 SurfaceFlingerFpsMonitor:候选层排序(SurfaceView+BLAST 优先)、每候选逐个 `--latency` 试、层名过滤黑名单。

**FPS 根因(原代码 3 处错误)**:
1. **层名形态错**:`resolveLayer` 解析 `--list`/`Output Layer` 时把带地址前缀的整串当层名(实测 `--latency` 对 `0xb4...(...)` 形态只回 1 行 refresh period → 永远判"无数据")。正确层名必须从 `RequestedLayerState{<完整名> parentId=...}` / `Output Layer 0xb4...(b85e336 SurfaceView[pkg/act](BLAST)#19010)` 里**抽括号内完整名**(保留 `b85e336 ` hash 前缀与 `#19010` 序号)。
2. **取错列**:原来取每行第 3 列;实测列 3 是 ready 不可靠,要取列 2 actual present。
3. **只解析一个候选**:游戏/普通 app 有多个同包层,应收集全部可渲染候选并逐个试 `--latency`,取第一个有数据的。

**GPU 根因(关键新发现,修正 AGENTS.md 旧结论)**:
- 旧文称 kgsl 全部被拒 —— **不准确**。`/sys/class/kgsl/kgsl-3d0/gpubusy` **shell 身份可读**!(adbd shell 与 Shizuku runner 同为 uid=2000 `u:r:shell:s0`,权限一致,实测都能读)。它输出 `busy total`,每读一次计数器复位(当前窗口),占用率 = busy/total × 100。游戏实机 15-66%,桌面 0-2%。
- **频率仍全被拒**(gpu_clock/gpuclk/cur_freq 等),无 root 读不到 → gpuFreq 恒 -1。
- 隐藏 bug:解析正则 `B=(\d+)` 匹配不到实际输出 `B= 652988 1022170`(cat 带前导空格)→ 占用读到了却解析失败。修正则 `B=\s*(\d+)\s+(\d+)`。

改动文件:
```
├── monitor/SurfaceFlingerFpsReader.kt  # 重写:多候选层 resolve + 逐个试 latency;取 actual(列2);
│                                       #   --list 优先、Output Layer 兜底;cleanLayerName 抽完整层名;
│                                       #   时间戳新鲜度 guard(>1.5s 判停);失效自愈退避
├── monitor/MonitorSampler.kt           # readGpu 重写:gpubusy busy/total 比值算占用(容错空格正则);
│                                       #   频率多源探测(全被拒则 -1)
├── overlay/OverlayWindow.kt            # GPU 行:有占用即显示 "GPU xx%",频率非负才拼 MHz(不再因无频率整行 --)
└── monitor/Sample.kt                   # 注释更新(gpuLoad 现可读)
```

实机验证(用户确认):悬浮窗五行全部出数且合理 —— FPS 60(16.3ms)、CPU 1459/864MHz 57%、**GPU 7%**(游戏待机)/游戏内 15-66%、电温 39.5°C 芯温 50.7°C、功耗 904mW。桌面静止无渲染层时 FPS=-1 属正常。

**踩坑备忘**:
- Kotlin 块注释**可嵌套**:在注释里写 `/sys/kernel/gpu/*` 这种含 `/*` 的字面量会把后续代码全吞成注释 → 编译报 "Missing }/Unclosed comment" 且报错行号错乱。注释里别写路径通配 `/*`。
- `--latency` 时间戳是 CLOCK_MONOTONIC,与 `System.nanoTime()` 同源(别用 `elapsedRealtimeNanos`,那是 BOOTTIME)。
- 层名 `#19010` 序号会随 Surface 重建变化,失效要重解析(现有退避+自愈逻辑已处理)。

### 3.8 阶段五(2026-09-06):每核频率/GPU占用曲线 + 指定应用记录 + 空闲显示 0 —— 实机验证通过

本轮完成三块:1) 记录每个逻辑核频率(8 核)并画曲线;2) GPU 占用曲线 + 空闲显示 0%;3) 指定应用记录(应用选择页 + 门控暂停续采)。全部实机验证。

**1. 每核频率采集与曲线**
- 拓扑实测(小米 13 Ultra / 8 核):cpu0-2 小(307-2016MHz)、cpu3-6 中(499-2323MHz)、cpu7 大(595-2476MHz)。**注意 `Runtime.availableProcessors()` 实机返回 6(有离线核),不可用于核数探测** → 改列 `/sys/devices/system/cpu/cpu[0-9]*` 目录取最大核号(probeCoreCount)。
- `Sample` 增加 `perCoreFreqMhz: List<Int>`(固定 MAX_CORES=8,下标=核号,读不到 -1);CSV 在 cpu_load_pct 后展开 `cpu0_freq_mhz..cpu7_freq_mhz` 8 列(toCsvHeader/toCsvRow 用 buildList 逐项拼)。
- `MonitorSampler.readMerged` 一条 shell 里先 cat 各簇代表核(`@@C<簇>`),再逐核 cat(`@@P<核>`),解析进 perCore 数组。
- UI:`Chart.kt` 新增 `MultiLineChart`(多序列叠加 + 各序列独立断点);详情页新增 `DetailMultiChart`「CPU 核心频率(MHz)」(8 条线,同簇同色系:小核绿/中核蓝/大核橙,下方核号图例);实时页曲线卡顶部加近 2 分钟每核频率迷你多线图;详情页信息卡新增「记录范围」(门控会话标注)。
- **CSV 解析重构**:SessionStore 新增 `readSamplesWithHeader`(表头+行);MainViewModel.openSession 改**按表头列名定位**(不再硬编码列号),兼容旧会话;elapsed 跳变 >2×interval 视为门控暂停段,该行各序列插 -1(曲线留白)。

**2. GPU 占用曲线 + 空闲 0%(用户提出:GPU 空闲确实没压力,应显示 0% 而非 --)**
- 实机:`/sys/class/kgsl/kgsl-3d0/gpubusy` 空闲输出 `0 0`(busy=0,total=0)。修正 readGpu:total>0 按比值,else(busy=0,total=0)= 0%(不再留 -1)。
- 详情页加「GPU 占用(%)」曲线(0-100,紫);实时页加 GPU 占用行与曲线。

**3. FPS"无帧显示 0" + 活跃层 bug(用户提出,深挖出的真 bug)**
- 语义:层存在但画面静止(无新帧)→ FPS 应显示 0,不是 --。AppStateReader.readFpsFor 让 fps>=0 透传(Sample.fps 可为 0);悬浮窗/实时页 FPS 行 `>=0` 显示 0。
- measureByLatency 返回语义:>0 有效帧率;0f=层有效(有帧记录行)但缓冲无真实时间戳/停更>1.5s(静止);null=层无效(SF 只回 1 行 refresh,层名不匹配/退化)。
- **活跃层 bug(关键)**:同包有多个层(实测桌面 launcher 有 `890932e ...Launcher#74` 旧层 latency 恒全 0,与 Output Layer 指向的活跃层 `...Launcher#21751` 有 125 个真实时间戳)。旧逻辑先试到全 0 旧层 → 判"静止 0"卡死,即使画面变化也保持 0。**修复:resolveLayers 必须优先从全量 dumpsys 的 "Output Layer <name>" 行取活跃层,再补 --list 候选去重**。`--list` 里的层很多是已不活跃的旧层,latency 缓冲恒全 0。
- 系统/桌面滑动场景实测:Output Layer 优先后可测 ~120(120Hz 屏),静止显示 0,恢复滑动能重新出数。

**4. 指定应用记录**
- 主页「会话记录」卡新增「记录目标」行:点击进新 `AppPickActivity`(应用选择页);顶部「跟随当前前台应用」= 清除门控;下方全部可启动应用(名称/包名搜索过滤 + 应用图标,MIUI 需 `<queries>` + `QUERY_ALL_PACKAGES` 才能列全)。
- 选择写入 `SessionRecorder.watchTarget`(WatchTarget data class + SharedPreferences 持久化,"record_target",跨 Activity 共享 —— **不要放 MainViewModel**,那是 Activity 级会丢)。start() 带 watchPkg;loop 每 500ms 查前台,**目标不在前台 → 暂停采样(_paused=true,不写 CSV),回来自动续采**;SessionMeta 增 `watchPkg` 字段落盘。
- 详情页信息卡显示「记录范围:仅 <应用> 在前台时」;主页记录卡显示目标 + 暂停提示 +「清除指定」。
- UI 组件:应用列表复用 HyperList;`ListRowContent` 增加可选 `leading` 槽(应用图标);AppPickActivity 需 NavigationEventDispatcherOwner(SearchBar 内部 NavigationBackHandler,与 RecordsActivity #13 同款,见下)。
- **Miuix SearchBar 也要 NavigationEventDispatcherOwner**:SearchBar.kt:121 内部调用 NavigationBackHandler,普通 ComponentActivity 不注入 owner 直接崩(IllegalStateException)。AppPickActivity onCreate 与 RecordsActivity 相同的 CompositionLocalProvider 包裹。

实机验证:游戏会话 2 分 56 秒(session.json:durationMs 175837、sampleCount 117、avgFps 45.5、avgPower 823mW、avgCpuTemp 61°C),CSV 8 核频率列全程有效、GPU 占用 70-80%(游戏战斗)、front 全程游戏(门控生效);悬浮窗 GPU 空闲 0%、FPS 桌面静止 0 恢复 ~120。

### 3.9 阶段六(2026-09-06):统计图 Material 色板 + 全图坐标轴 —— 实机验证通过

用户要求:1) 统计图颜色尽量选 Material 色系;2) 所有统计图加坐标轴。

**新增 `ui/ChartTheme.kt`**(统一色板 + 轴样式 + 刻度辅助):
- `ChartColors` 对象:单指标主色取 Material3 500/600 档 —— Fps 绿 #43A047、Power 蓝 #1E88E5、BatteryTemp 橙 #FB8C00、CpuTemp 紫 #AB47BC、Gpu 深紫 #673AB7(区分于 CpuTemp 紫)、Network 青、Memory 蓝灰。颜色统一从这里取,**不要再在调用点硬编码**。
- `corePalette(coreCount)` 生成每核频率多线图色板:小核(0-2)M3 绿系、中核(3-6)M3 蓝系、大核(7+)M3 橙系明暗梯度;超过 8 核回退 M3 彩虹。
- `ChartAxisDefaults`:Grid 淡灰、AxisLine 中灰、Label 60% 黑、y 轴宽 40dp / x 轴高 18dp。
- `axisNum(v)` 刻度数字取整;`timeAxisLabels(elapsedMs)` 从会话 elapsed 生成 5 档 mm:ss x 标签。

**改 `ui/Chart.kt`**:LineChart / MultiLineChart 加坐标轴 —— 左 y 轴(4 档刻度值 + 轴线)、下 x 轴(刻度短线 + 可选 [xLabels] 文字标签),绘图区自动扣除轴占位(axisOffsets);原生 Canvas(nativeCanvas + Paint)画刻度文字;**注意文字画笔色用 `color.toArgb()`(成员扩展),别自拼 ARGB**。断点/面积填充/多序列逻辑不变。`showAxis=true` 默认开。

**改调用点(全部换 ChartColors + 传 x 标签)**:
- 实时页 MainScreen:每核迷你多线图(高 96dp,corePalette + 相对首点 mm:ss x 标签)、帧率/GPU/电池温/功耗 ChartRow 全部 ChartColors。
- 详情页 SessionDetailActivity:每核多线图、GPU/帧率/功耗/电池温/核心温 DetailChart 全部 ChartColors,共用 `timeAxisLabels(detail.elapsed)` x 轴时间标签。
- Widgets.kt:DetailChart/DetailMultiChart 加 `xLabels` 参数并 showAxis;ChartRow 高度 42→60dp 容纳 y 轴。

实机验证(用户确认):主页实时与详情页图表 Material 配色协调、坐标轴齐全,效果良好。

### 3.10 阶段七(2026-09-06):实时曲线可配置(九宫格全指标) + 悬浮窗设置页三页导航 —— 实机验证通过

用户要求:1) 首页实时曲线**去掉 8 核频率迷你曲线**;2) 其余实时曲线可在设置页配置显示哪些、用什么颜色;3) 九宫格内**所有指标均可画曲线**,自由开/关,默认只开少量;4) 悬浮窗设置页用底部导航隔成多页。

**新增 `ui/LiveChartPrefs.kt`**(主页实时曲线配置,独立 SharedPreferences `live_chart_prefs`):
- `ChartItem`(key/显示名/默认开/Series 列表);`ALL` 共 9 项:帧率、CPU 占用、GPU 占用、**CPU 频率(3 系列:小核/中核/大核)**,GPU 频率(默认关,无 root 无数据)、功耗、电池温度、核心温度、内存。
- 每系列独立颜色持久化(`color_{key}_{si}`),开关 `on_{key}`;默认开 fps/cpu_load/gpu_load/power 四项。
- `MATERIAL_COLORS`:18 个 Material 色块供选色。

**主页 MainScreen 实时曲线区重构**:删除每核 8 线迷你 MultiLineChart;按 `LiveChartPrefs.ALL` 逐项画——单值项走 ChartRow,CPU 频率走新增 `MultiSeriesRow`(左标签列 + L/M/B 三当前值,右三线 MultiLineChart,色来自 3 系列配置),GPU 频率等无数据项显示 `NoDataRow` 占位。全部读取当前 SharedPreferences(改配置回主页即生效)。

**Widgets.kt**:新增 `MultiSeriesRow`、`NoDataRow`。

**悬浮窗设置页 OverlaySettingsActivity 重构为底部导航三页**:
- 底部 `NavigationBar`(普通整条贴底,非 FloatingNavigationBar —— 用户嫌悬浮样式差):Tab0「显示项」(悬浮窗开关 + 8 显示项开关,原内容)、Tab1「实时曲线」(LiveChartPrefs.ALL 逐项开关行 + 色点展开色板)、Tab2「外观」(穿透/字号/说明)。
- `chartItemRow`:单线项点色点展开一排色块选色;多线项(CPU 频率)展开后**每系列一行**(系列名 + 该簇色点 + 色板),实现 L/M/B 各自配色。
- 内容底部留白 ~96dp 给导航栏,SubPageScaffold content 内 Box align bottom 放 NavigationBar。
- **踩坑**:OverlaySettingsActivity `exported=false`,adb `am start` 无法拉起(仅 app 内导航进入);NavigationBarItem icon 是 `ImageVector`(非 composable)。

实机验证(用户确认):底部三页导航正常;实时曲线页 9 项开关与选色可用;主页只画启用项、每核迷你图已移除。

### 3.11 阶段八(2026-09-06):设置页重构为通用「设置」两页导航 —— 实机验证通过

用户要求:设置已不止悬浮窗,把设置页逻辑收敛为通用「设置」——底部导航只留 **实时曲线设置 / 悬浮窗设置** 两页(悬浮窗相关全部合并到悬浮窗设置页),标题与 Activity 统一改为「设置 / Settings」。

改动:
- `OverlaySettingsActivity.kt` → **删除**;新建 `ui/SettingsActivity.kt`,类名 `SettingsActivity`、Composable `SettingsScreen`。
- 标题统一「设置」(副标题显示当前分页);主页顶栏入口文案由「悬浮窗」改「设置」(图标 Settings),跳 `SettingsActivity`;manifest 的 activity 改 `.ui.SettingsActivity` + label「设置」。
- 底部 NavigationBar 两页:Tab0「实时曲线设置」(原实时曲线 9 项开关+选色);Tab1「悬浮窗设置」(悬浮窗开关 + 显示项 8 项 + 触摸穿透 + 字号 + GPU 说明 —— 原显示项页与外观页合并)。
- OverlayPrefs 注释引用同步更新。

踩坑备忘:无(纯重命名+重组);重命名后旧 OverlaySettingsActivity 文件已删,勿再引用。

实机验证(用户确认):主页顶栏「设置」入口进入,标题「设置」,两页导航正常,悬浮窗相关设置齐全。

## 4. 构建 / 设备环境(必须按此操作)

### 4.1 Gradle 沙箱配置(重要!)
- **沙箱禁止写 `C:\Users\YYDaniel\.gradle` 和 `E:\DeveloperTools\AndroidSDK\.android`**
- 必须设置:`$env:GRADLE_USER_HOME = 'E:\SourceFiles\androidstudio\Raidian\.gradle-home'`(工作区内,依赖缓存已就绪)
- debug 签名用项目内 keystore(`app/keystore/debug.keystore`,密码 raidian123),已配在 build.gradle.kts 的 `signingConfigs.debugLocal`,否则 AGP 会尝试写 SDK 目录
- Kotlin daemon 会报 `AccessDeniedException ... kotlin-daemon-client-*.tmp`(写用户 AppData 被拒),**无害**,忽略即可;偶发回退"Compile without daemon"

### 4.2 版本
- AGP 9.4.0 / Kotlin **2.4.0**(必须,miuix 0.9.3 用 Kotlin 2.4 编译,低了读不了 metadata)/ compileSdk 37 / minSdk 26 / targetSdk 37
- compose BOM 2026.02.01;Miuix 0.9.3(坐标 `top.yukonga.miuix.kmp:miuix-ui|core|icons`);Shizuku 13.1.5;kotlinx-serialization 1.8.0
- 本地 Gradle 发行版已复制到 `.gradle-dists`,wrapper 会自适配

### 4.3 设备调试
- adb:设备序列号 `c9e352e3`(小米 13 Ultra)。命令形如 `adb -s c9e352e3 shell ...`
- 常用:
  - 安装:`adb -s c9e352e3 install -r app/build/outputs/apk/debug/app-debug.apk`
  - 启动:`adb -s c9e352e3 shell am start -n yy.daniel.raidian/.MainActivity`
  - 日志:`adb -s c9e352e3 logcat -d | Select-String 'ShizukuShell|CmdExec|raidian'`
  - 拉记录:`adb -s c9e352e3 shell run-as yy.daniel.raidian ls -t files/raidian/sessions/`,cat samples.csv
  - 截图:`adb -s c9e352e3 shell screencap -p /sdcard/x.png` 再 pull(勿用 PowerShell 重定向,会损坏 PNG)
- 手机可能锁屏 → `wm dismiss-keyguard` 或请用户解锁;`input keyevent` 在小米被 INJECT_EVENTS 限制
- **Shizuku 状态**:服务由 Shizuku App 启动(adb 方式),已授权 Raidian。若重装 APK 后授权保留;force-stop 后重启 app 会自动重连(粘性监听)

### 4.4 测试节奏
- 改后端后:`compileDebugKotlin` → `assembleDebug` → 安装 → 启动
- 请用户操作 UI 点按钮(记录/悬浮窗),agent 用截图+OCR+CSV+logcat 验证
- 截图经 `adb pull` 后用 image_ocr / image_scan 看(模型不支持直接读图)

## 5. 遗留问题 / 下一步(按优先级)

### P0 后端(继续对照开源实现做)
1. ~~**GPU 数据显示**~~ ✅ 阶段四已修(见 3.7):占用走 kgsl `gpubusy` busy/total 比值,shell 身份可读,实机通;频率仍无 root 读不到(恒 --,占用 % 照常显示,悬浮窗/实时页/CSV 均正常)。
2. ~~**FPS 实机验证**~~ ✅ 阶段四已修(见 3.7):重写 SurfaceFlingerFpsReader(层名抽括号内完整名 + 多候选逐个试 + 取 actual 列),明日方舟:终末地实机 60fps 出数正常。
3. **GPU/CPU 温度的更多指标**:Scene 有 CPU 每簇温度/最高、GPU 温度等。当前只取 maxCpu/maxSoc。参考 vtools GlobalStatus 扩展:各 thermal zone 明细可进 CSV。另注意 thermal zone 里已有 `gpuss-N`(GPU 温度,实测 43-45°C)未被归类,可考虑加 gpuTemp 字段。
4. **功耗曲线细化**:Scene 有 ChargeCurve(充电电流曲线)、BatteryAvgStatus。当前每 500ms 的电流/功率已进 CSV;可加 BatteryManager 的 CHARGE_COUNTER 长时平均。
5. ~~采样性能~~ ✅ 阶段二已修:合并命令 + thermal 走 JVM File(去掉 canRead 预检)+ FPS 退避 + 命令超时;CSV 间隔回 ~550ms(见 3.3)。

### P1 记录与悬浮窗增强
6. ~~1% Low 帧统计落盘~~ ✅ 阶段二已补:session.json 现写 endTime/durationMs/sampleCount + avgPowerMw/avgBatteryTemp/avgCpuTemp(实测 12s 会话 43mW/33.4°C/44.9°C;sampleCount 仍为 0 因桌面无 FPS,游戏场景才有值)。
7. ~~曲线可视化~~ ✅ 阶段二已做:ui/Chart.kt LineChart(Compose Canvas),实时页近 2 分钟 + 详情页 帧率/功耗/温度/核心温。
8. ~~悬浮窗配置~~ ✅ 阶段二已做:悬浮窗页(显示项开关/触摸穿透/字号±)+ SharedPreferences 持久化 + 位置记忆。
9. **悬浮窗样式**:当前是自绘 TextView 行,可考虑 Miuix/Compose 化的精致样式(半透明圆角卡片、单行紧凑),但注意悬浮窗只能 View 体系(WindowManager),可用 ComposeView 包。

### P2 工程化
10. **MonitorService 完善**:已在 manifest 声明(specialUse),但 MainActivity/OverlayController 目前用 Application 级常驻而非前台服务。评估:长时间后台记录应起前台服务保活(Scene 有常驻通知)。当前 OverlayController 在进程存活时工作,进程被杀则停 —— 若要"后台记录"可靠,需接 MonitorService + START_STICKY。
11. **前台应用识别精度**:已用 runningAppProcesses 过滤自身 + dumpsys activity 兜底。可加 UsageStatsManager(PACKAGE_USAGE_STATS 权限)提高精度(参考 FrameX)。
12. ~~代码清理~~ ✅ 阶段二已删 Color.kt/Type.kt;MonitorSampler 已去掉重复 readMem/readNet/readCpu(并入 readMerged)。仍待办:若干注释乱码(GDK 编码)文件(MonitorService.kt 等)整理。

### P3 阶段三 UI 遗留
13. **✅ 记录页多选删除确认对话框崩溃(已修复,2026-09-06 实机验证)**
    - **位置**:`ui/RecordsActivity.kt` `RecordsScreen` → 多选态点底部红色 FAB → `showDeleteDialog=true` → `OverlayDialog`。
    - **症状演进**:(a) 最初用 Miuix `WindowDialog`(真 Window 对话框)→ 点删除按钮闪退回主页;(b) 改自绘遮罩 → 不崩但用户要求必须用 Miuix 自带 dialog;(c) 改 `OverlayDialog`(先放 SubPageScaffold 外兄弟位 → 不显示也不崩;后移入 SubPageScaffold content 槽 → **FATAL 崩溃**)。
    - **崩溃栈**(已抓到,logcat `FATAL EXCEPTION: main`):
      ```
      java.lang.IllegalStateException: No NavigationEventDispatcher was provided via LocalNavigationEventDispatcherOwner
        at androidx.navigationevent.compose.NavigationEventHandlerKt.NavigationEventHandler(NavigationEventHandler.kt:82)
        at ... NavigationBackHandler(NavigationEventHandler.kt:148)
        at top.yukonga.miuix.kmp.layout.DialogContentLayoutKt.DialogContentLayout(DialogContentLayout.kt:184)
        at top.yukonga.miuix.kmp.overlay.OverlayDialogKt.OverlayDialog(OverlayDialog.kt:75)
        at top.yukonga.miuix.kmp.utils.MiuixPopupUtils.DialogEntry(MiuixPopupUtils.kt:408)
      ```
    - **根因**:Miuix 0.9.3 的 `OverlayDialog`(经 `DialogContentLayout`)内部**无条件**调用 `NavigationBackHandler`,它需要组合树里有 **`LocalNavigationEventDispatcherOwner`**。miuix 自带 `Scaffold` 只提供 `LocalDialogStates` + `MiuixPopupHost`(渲染宿主),**不提供该 owner**;文档只写了"必须放 Scaffold 内",没提这个隐藏依赖。example 不崩是因为页面被 **`NavDisplay`(androidx.navigation3)** 包裹,`NavDisplay` 内部提供 owner。
    - **修复方案(已实机验证,与标题栏 scaffold 无关——换 Miuix 自带 Scaffold 也不能规避,必须在组合树注入 owner)**:
      - `gradle/libs.versions.toml` + `app/build.gradle.kts`:显式 `implementation(libs.androidx.navigationevent.compose)`(`androidx.navigationevent:navigationevent-compose:1.1.2`;miuix-ui 只把它当 runtime 传递依赖,编译期不可见,不显式声明无法 import)。
      - `ui/RecordsActivity.kt` `onCreate` 的 setContent 根(包住 `MiuixTheme`):
        ```kotlin
        val owner = rememberNavigationEventDispatcherOwner(enabled = true, parent = null)
        CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
            MiuixTheme { RecordsScreen(...) }
        }
        ```
        **注意 `parent = null` 必须显式传**:默认参数会去读当前 Local 值,组合根没有时抛同样的 IllegalStateException("If you intended to create a root dispatcher, explicitly pass null as the parent")。API 语义见反编译(owner 内部创建根 `NavigationEventDispatcher`,`enabled=false` 时不注册回调)。
      - 另补 `BackHandler(enabled = showDeleteDialog) { showDeleteDialog = false }`:普通 ComponentActivity 没有 navigation3 把**系统返回键事件**桥接进该 dispatcher,对话框内建的返回处理收不到事件;显式拦截让对话框打开时按返回只关对话框、不退多选。
    - **用户已认可的多选交互(勿回退)**:长按进入多选;多选态标题不变、副标题「已选 N 项」;顶栏 SelectAll 图标(全选=primary 蓝/否则 onSurface 黑,IconButton);行尾 Checkbox(行点击=切换,点击行不打开详情);底部中央红色圆形 FAB(FloatingActionButton,容器 #E53935 + 白色 MiuixIcons.Delete);确认删除后 `vm.deleteSessions`(SessionStore.deleteDirs)→ 刷新 → 退出多选。
    - **后续样式调整(已实机验证)**:删除确认按钮由红字 `TextButton` 改为蓝色实心 `Button`(`ButtonDefaults.buttonColorsPrimary()` 主题蓝底 + 白字,与主页「开始记录」一致),取消仍是文字按钮。
14. **会话摘要副文本截断**:列表行副文本(maxLines=1)会截断「无帧率数据 · 均功耗 4.…」等长摘要(实机可见)。可接受(HyperOS 列表惯例),若想展示全量需进详情看。

## 6. 里程碑状态

| 项 | 状态 |
|---|---|
| Gradle 工程 + Miuix/Shizuku 依赖 | ✅ |
| Shizuku UserService 提权通道 | ✅ 实机通 |
| CPU 频率(3簇)/占用 | ✅ 实机通 |
| 温度(电池/核心/SoC) | ✅ 实机通 |
| 电流/电压/功耗(mW) | ✅ 实机通(单位已校准) |
| 内存 / 网络计数 | ✅ 实机通 |
| 前台应用识别 | ✅ 实机通(桌面/自身正确) |
| 会话记录 CSV 落盘 | ✅ 实机通 |
| 悬浮窗显示 + 常驻更新 | ✅ 实机通 |
| FPS(跨应用 SurfaceFlinger) | ✅ 阶段四/五实机通(游戏 60fps;桌面静止显示 0、恢复 ~120;Output Layer 活跃层优先,见 3.7/3.8) |
| GPU 占用 | ✅ 阶段四/五实机通(kgsl gpubusy 比值;空闲显示 0%,见 3.7/3.8) |
| GPU 频率 | 🔶 无 root 系统限制(恒 --,占用 % 照常;见 3.7) |
| 每核频率记录 + 曲线 | ✅ 阶段五实机通(8 核 CSV + 详情/实时多线图,见 3.8) |
| 指定应用记录(门控暂停续采) | ✅ 阶段五实机通(AppPickActivity 选应用;不在前台自动暂停,见 3.8) |
| 悬浮窗显示项配置 UI | ✅ 阶段二实机通(8 项开关/穿透/字号/位置记忆) |
| 曲线可视化 | ✅ 阶段二/六实机通(Material 色板 + 全图坐标轴,见 3.9) |
| 实时曲线可配置(9 项开关/颜色/CPU 三簇) | ✅ 阶段七/八实机通(LiveChartPrefs;主页按配置绘制,见 3.10/3.11) |
| 通用「设置」页 NavigationBar 两页 | ✅ 阶段八实机通(实时曲线设置/悬浮窗设置,SettingsActivity,见 3.11) |
| 主界面三页 TabRow(阶段二形态) | ✅ 已被阶段三取代(主页仅实时 + 子页 Activity) |
| 会话统计落盘(均值功耗/温度) | ✅ 阶段二实机通 |
| 采样性能(500ms 稳定节奏) | ✅ 阶段二实机通(~550ms/点) |
| 悬浮窗样式美化(Miuix 卡片化) | 🔶 未做(遗留 #9) |
| 阶段三:主页仅实时 + 顶栏入口 | ✅ 实机通(记录/悬浮窗图标进独立 Activity) |
| 阶段三:HyperOS 列表(HyperList 组件) | ✅ 实机通(行高/无分隔/按压渐入渐出/贴容器) |
| 阶段三:标题上滑回缩 | ✅ 实机通(Miuix scrollBehavior + nestedScroll) |
| 阶段三:会话详情独立 Activity | ✅ 实机通 |
| 阶段三:记录多选删除(含确认对话框) | ✅ 实机通(长按多选/FAB/全选/OverlayDialog 确认删除,崩溃已修复见 #13) |

## 7. 协作与沟通约定
- 后端改动后按 4.4 节奏验证;改单位换算、新数据源前**先查 references/scene-alternatives 对应实现**
- **UI 组件改动(尤其 Miuix 组件用法)前,先查 `references/miuix-0.9.3/docs/zh_CN/components/` 对应文档 + `docs/zh_CN/guide/` + `example/shared/src/commonMain/kotlin/` 里该组件的实际用法**,不要只凭猜(教训见 #13:OverlayDialog 除了"放 Scaffold 内"还有隐藏的 NavigationEventDispatcherOwner 依赖,example 是通过 NavDisplay 间接提供的)
- 界面截图/数据验证:adb 截图 + image_ocr(中文 OCR 用默认引擎即可)
- 需要用户操作手机(点按钮/授权)时,用 ask_user_question 明确引导
- 每次重要里程碑可追加本文件更新记录(保持"最近更新"日期)
