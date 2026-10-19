# Scrcpy Android 主控端

仓库根目录就是 Android 主控端项目，以 **Android 16 / API 36** 为目标，最低 Android 11。
被控端运行 `scrcpy/` 子模块中的 scrcpy **5.0.1** 服务端，支持 Android 15/16；不需要安装主控 APK、root 或辅助无障碍服务。
每次会话通过 ADB 上传服务端，以 shell 身份启动，结束时清理。

## 开发接手

后续 agent 请先阅读 [AGENTS.md](AGENTS.md) 和 [开发接手文档](docs/development-handoff.md)。后者总结全部开发阶段、架构与源码入口、测试证据、已知问题和交付流程；完整文档导航见 [docs/README.md](docs/README.md)。

## 0.4.1 小窗微调

- 上下操作栏统一为 40 dp，整个窗口按圆角裁切，修复栏背景越出圆角的问题。
- 移除加减按钮，改为左右下角的斜线拖动手柄；点击不缩放，拖动保持比例。中间全屏及顶部电源改用 Material 图标。
- 顶部显示被控端设备名称，读取失败时回退机型/Android；名称按小窗当前可用宽度单行省略，仍可拖动顶部移动窗口。
- 局部采用 Material Components 独立主题，组件和图标许可见 [docs/ui-assets.md](docs/ui-assets.md)。

## 0.4.0 电源、小窗与界面设置

- 全屏悬浮球 → **电源与唤醒**，提供唤醒、点按/长按电源，以及一键电源 + 音量组合键。小窗顶部右侧电源图标支持点按和长按。
- 小窗底部独立操作栏：左右下角拖动缩放，中间全屏图标复用当前会话恢复；远端画面始终等比显示。0.4.1 已用手柄替换最初的加减按钮。
- 全屏默认跟随被控屏幕方向，使用 Android 原生 Activity 旋转，支持四个方向；球菜单可关闭并保存。小窗保持原有方向行为。
- 球菜单增加 **通知栏 / 快捷设置 / 收起通知**，操作被控端面板。本机使用沉浸模式，系统边缘手势仍可能唤出本机系统栏。
- 主界面右上角 ⋮ → **设置**，可分别调整悬浮球、展开菜单和整个小窗的不透明度（20–100%），自动保存并支持恢复默认；小窗实时生效。

电源长按与组合键的系统动作由被控 ROM 决定；可以唤醒熄屏设备，不能启动已经关机的设备。
详细需求、验收步骤、实体机清单和重构约束见 [docs/requirements-and-regression.md](docs/requirements-and-regression.md)。

## 0.3.0 实验麦克风转发

全屏悬浮球增加 **转发麦克风 / 停止麦克风**。选择被控应用并授予主控录音权限后，
可通过独立 ADB 通道向该应用的录音输入转发本机麦克风。默认关闭，离开全屏或切换小窗时停止。
需要被控端 Android 14+ 且系统向 ADB shell 开放音频路由能力；已在 Android 16 模拟器上验证。
不保证支持所有通话应用，Android 14/15 真机和通话音质仍需测试。

摄像头暂未实现通用输入替换。系统特权、虚拟设备与 UVC 的可行边界见 [麦克风与摄像头调查](INPUT_SHARING.md)。

## 0.2.0 界面与小窗更新

- 底部三个入口：**连接 / 设备与凭据 / 工具**。无线配对、端口扫描、Shell、文件传输和凭据详情使用独立二级页面。
- 连接后右下角显示“远程控制”快捷按钮，无需切换页面即可启动全屏远控。
- 设备页统一查看当前连接、启动全屏/小窗、断开连接；已保存设备支持命名、修改连接端口和删除本地记录。
- 凭据页可查看、复制本机 SHA-256 公钥指纹。沿用 0.1.0 的同一加密身份；升级不会自动重置密钥或清除配对。
- 全屏控制隐藏系统栏和固定工具栏，仅保留一个可拖动悬浮球。点击展开返回、主页、最近任务、音量、旋转、剪贴板、小窗与退出操作。
- 默认将全屏下音量实体键的按下、长按重复、松开转发给被控端，可在悬浮球菜单中关闭。切换窗口/离开全屏会释放尚未松开的键。主控端返回手势操作远程返回；退出请使用球菜单。
- **可触控悬浮小窗**支持在其他应用上方显示，拖动顶部移动，底栏左右缩放、中间恢复全屏。小窗内没有操作菜单，也不拦截本机音量键；提供基本远程触控及顶部电源按钮。
- 点击小窗通知，或在设备页点击“全屏控制”，可恢复全屏；通知和设备页均可关闭小窗。关闭投屏仍保留 ADB 连接。
- 已建立会话的全屏/小窗往返复用 ADB、服务端、音视频通道，只切换解码输出 Surface。

首次使用小窗会引导授予“在其他应用上层显示”权限，并申请通知权限以提供恢复/关闭入口。通知被拒绝仍可从设备页管理小窗。
首次进入全屏时，Android 可能显示系统操作说明，确认后即可接收实体键。

## 功能

| 功能 | 当前实现 |
| --- | --- |
| 传统 TCP ADB | IP + 端口、RSA 授权、连接/重连/断开、Shell |
| Android 无线调试 | 六位配对码 + 配对端口，SPAKE2/TLS 1.3；配对成功后使用连接端口 |
| 配对记忆 | 保存同一 RSA 身份、设备 IP/连接端口和配对状态，配对码不落盘 |
| OTG/USB ADB | USB Host 枚举、ADB 接口识别、系统 USB 授权、RSA 授权、拔线处理 |
| USB 转无线 | 当前连接上执行 `tcpip:5555`，随后输入被控端 Wi-Fi 地址重连 |
| 远程桌面 | H.264 + Android MediaCodec + SurfaceView；默认最大边 1600、60 fps、6 Mbps |
| 远程控制 | 触摸、多指、返回/主页/最近任务、音量、旋转、全屏音量实体键；按视频尺寸映射坐标 |
| 悬浮小窗 | 可触控、移动、缩放；全屏往返保留同一会话；通知/设备页恢复或关闭 |
| 声音 | 48 kHz 双声道 PCM16，通过同一 ADB 连接播放；可在连接前关闭 |
| 主控麦克风 | 实验性按被控应用转发，48 kHz 单声道 PCM16；仅全屏前台，需主控录音授权 |
| 剪贴板 | 前台双向纯文本、去重、手动获取、粘贴；上限约 256 KiB |
| 端口扫描 | 用户输入单个 IPv4 和端口范围，32 并发、400 ms 超时、可取消，结果由用户选择 |
| 文件共享 | Android 系统文件选择器 + ADB sync，上传/下载任意文件；逐块传输 |
| 调试日志 | Logcat + 内部滚动文件，主界面导出；包含服务端输出、阶段和错误堆栈 |

端口扫描只表示 TCP 可达，不保证该端口为 ADB。连接端口和配对端口可能同时开放，且互不相同。
不会自动连接扫描结果。IP 变化时需手动更新；端口变化时可重新扫描，通常不需要再次配对。

## 使用

### 无线调试（Android 11 及以上）

1. 主控与被控设备连接到互通的网络。
2. 在被控设备的开发者选项中开启无线调试，点击“使用配对码配对设备”。
3. 主控填写目标 IP、该对话框内的**配对端口**及六位配对码，点击配对。
4. 回到被控端无线调试首页，取“IP 地址和端口”中的**连接端口**，在主控端填写并连接。
5. 点击右下角“远程控制”或页面中的“开始投屏和控制”。重启主控应用后仍使用已保存的身份。

被控端取消授权、忘记配对、清除主控数据或卸载主控应用后，需要重新配对。
“清除列表”只删除最近设备信息，不会撤销被控端授权，也不会删除主控身份。

### USB / OTG

1. 主控端需支持 USB Host，使用支持数据传输的 OTG 连接线连接被控端。
2. 被控端开启 USB 调试。主控点击“选择 USB 设备并连接”，授予 USB 访问权限。
3. 在被控端确认“允许 USB 调试”，可勾选始终允许。
4. 连接后启动投屏；也可以请求开启 TCP 5555，再用 Wi-Fi IP 连接。

USB 的首次系统授权和被控端的 ADB 授权是两个不同步骤。USB/TCP 共用持久身份。
部分厂商设备另有“USB 调试（安全设置）”等输入授权，无法控制时查看服务端日志中的注入错误。

### 剪贴板和文件

点击全屏悬浮球，可开关文本同步、获取远程剪贴板和粘贴本地文本。Android 限制后台剪贴板读取，因此仅在全屏投屏界面处于前台时同步；小窗模式不进行剪贴板同步。
图片/视频等剪贴板 MIME 数据尚未实现自动同步；可通过主界面的文件上传/下载传输媒体文件。

文件路径是**被控设备**的绝对路径，需包含文件名，例如 `/sdcard/Download/photo.jpg`。
上传会写入指定文件，下载使用系统保存对话框选择本地目的地；不需要主控端申请全盘存储权限。
ADB shell 无权访问的目录仍不可访问。单次传输最长 10 分钟，取消按钮会断开连接；中断后可能留下不完整的目标文件，需重新传输。

## 仓库结构

```text
ScrcpyForAndroid/
├── src/                         # 主控应用、单元测试及设备测试源码
├── build.gradle                 # 根项目直接构建主控 APK
├── settings.gradle
├── gradle/scrcpy-server.gradle  # 服务端构建接入，不修改上游源码
├── gradle/input-server.gradle   # 可选麦克风接收进程构建
├── src/inputServer/             # 独立的 ADB shell 输入接收源码
├── vendor/adb/                  # 固定版本的 LibADB 及本地适配
├── scrcpy/                      # 独立 Git 子模块，锁定上游 5.0.1 提交
├── docs/                        # 详细需求、回归验收与重构约束
├── README.md
└── TESTING.md
```

主仓库管理安卓迁移代码，`scrcpy/` 保留自己的 Git 历史及原始源码。
服务端原始源码、资源和 manifest 直接取自子模块，生成文件写入根项目的 `build/scrcpy-server/`。
LibADB 的来源、版本及本地改动见 [vendor/adb/UPSTREAM.md](vendor/adb/UPSTREAM.md)。
`dist/`、构建缓存、本机 SDK 路径、日志和本地测试结果不进入 Git。

克隆主仓库时使用 `git clone --recurse-submodules <主仓库地址>`；已有检出执行：

```powershell
git submodule update --init --recursive
```

根项目的 `gradle/scrcpy-server.gradle` 与固定的 scrcpy 版本配套。升级子模块时需同时核对服务端版本和协议。

## 构建

使用仓库根目录的 Gradle Wrapper、JDK 21 或以上，以及 Android SDK。默认 compileSdk 36、targetSdk 36。
若本地仅安装 API 37 SDK，可使用 `-PandroidCompileSdk=37` 编译，targetSdk 仍为 36。
当前 AGP 9.1 对 compileSdk 37 会给出兼容性提示，已完成该配置的实际构建与模拟器测试。

Windows PowerShell 示例（在仓库根目录执行）：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_USER_HOME = "$PWD\.gradle-local"
.\gradlew.bat -PandroidCompileSdk=37 :assembleDebug
```

SDK 路径通过本地、已忽略的 `local.properties` 中 `sdk.dir` 配置，或使用自己的 `ANDROID_HOME`。
输出：`build/outputs/apk/debug/ScrcpyForAndroid-debug.apk`。
`bundleScrcpyServer` 自动从当前源码构建并打包同版本服务端，不依赖电脑安装的 scrcpy。
Debug APK 支持实体机排查；正式分发时请自行配置发布签名。

## 测试

单元测试和 Android 静态检查：

```powershell
.\gradlew.bat -PandroidCompileSdk=37 :testDebugUnitTest :adb-library:testDebugUnitTest :lintDebug
```

测试 APK 包含 `TargetProbeActivity`：持续动画、440 Hz 音频、真实多点触控日志及双向文本剪贴板观察。
这些组件**仅存在于测试 APK**，主控 APK 没有供外部调用的测试控制入口。

两个专用模拟器中先安装主控 APK 和测试 APK，开启被控端无线调试，然后执行：

```powershell
.\gradlew.bat -PandroidCompileSdk=37 :assembleDebug :assembleDebugAndroidTest
& C:\Software\adb\adb.exe -s emulator-5554 install -r build/outputs/apk/debug/ScrcpyForAndroid-debug.apk
& C:\Software\adb\adb.exe -s emulator-5554 install -r build/outputs/apk/androidTest/debug/ScrcpyForAndroid-debug-androidTest.apk
& C:\Software\adb\adb.exe -s emulator-5556 install -r build/outputs/apk/debug/ScrcpyForAndroid-debug.apk
& C:\Software\adb\adb.exe -s emulator-5556 install -r build/outputs/apk/androidTest/debug/ScrcpyForAndroid-debug-androidTest.apk

# IP/端口须替换成当前被控模拟器显示的值；首次可增加 -e pairPort 端口 -e pin 六位配对码。
& C:\Software\adb\adb.exe -s emulator-5554 shell am instrument -w -e host 10.0.2.17 -e port 5555 org.scrcpy.android.test/org.scrcpy.android.SmokeInstrumentation
```

集成测试会在指定目标清空 Logcat、写入并删除 `/sdcard/Download/scrcpy-smoke.bin`、启动测试页面、临时锁定屏幕方向并恢复。
仅对专用测试设备运行。较新模拟器支持在其共享网络内直接连接；如本地模拟器网络隔离，需要使用模拟器网络转发。
加 `-e connectOnly true` 可只验证连接、身份、Shell、文件和扫描。
加 `-e mainUiOnly true` 可验证连接、分类导航、凭据详情和设备记录保存。
新增专项使用 `-e uiStage power`、`window`、`rotation` 或 `settings`，每次只运行一组；统一回归使用 `-e windowUi true`，麦克风使用 `-e microphoneOnly true`。详见 docs 中的验收矩阵。

测试小窗和实体键前，仅对专用主控模拟器授予测试权限并确认全屏说明，然后运行：

```powershell
& C:\Software\adb\adb.exe -s emulator-5554 shell appops set org.scrcpy.android SYSTEM_ALERT_WINDOW allow
& C:\Software\adb\adb.exe -s emulator-5554 shell pm grant org.scrcpy.android android.permission.POST_NOTIFICATIONS
& C:\Software\adb\adb.exe -s emulator-5554 shell settings put secure immersive_mode_confirmations confirmed
& C:\Software\adb\adb.exe -s emulator-5554 shell am instrument -w -e host 10.0.2.17 -e port 5555 -e windowUi true org.scrcpy.android.test/org.scrcpy.android.SmokeInstrumentation
```

实体机请通过应用内权限引导操作，无需执行这些测试权限命令。

## 日志和限制

在右上角更多菜单或“工具 → 连接与诊断”点击“导出调试日志”。也可采集主控模拟器：

```powershell
& C:\Software\adb\adb.exe -s emulator-5554 logcat -v threadtime -s ScrcpyAndroid AdbConnection PairingConnectionCtx AndroidRuntime
& C:\Software\adb\adb.exe -s emulator-5554 shell run-as org.scrcpy.android cat files/debug.log
```

反馈时附：主控/被控型号和 Android 版本、连接方式、问题出现步骤、日志、画面或声音表现。
日志记录连接 IP/端口和设备信息，不记录配对码、私钥、Shell 输入或剪贴板正文。
身份文件使用 Android Keystore AES-GCM 加密，置于不备份目录。所有网络、传输和解码在后台线程运行。

普通全屏投屏退出前台后停止；明确切换至小窗时，由前台服务维持可见悬浮窗。未实现自动重连。音频采用低延迟 PCM 连续播放，尚无独立时钟漂移补偿或精确音画校准。
DRM 受保护画面及应用/系统禁止采集的声音受被控系统限制。
OTG 授权、线材供电、厂商 ROM 权限和 Android 15 实体机兼容性仍需实体机验证。

## 来源

- scrcpy 服务端及协议：本仓库 5.0.1，Apache-2.0。
- `vendor/adb`：[LibADB Android BC 3.4.0](https://github.com/osservatorionessuno/libadb-android-bc/tree/3.4.0)，固定提交见 `UPSTREAM.md`；选用 Apache-2.0 许可，保留原始各文件许可声明和 LICENSES。
- BouncyCastle 1.84：SPAKE2 底层运算、配对 TLS、证书生成。
- AndroidX Core 1.15.0：兼容的私有 USB 广播注册。
- AndroidX Activity 1.9.3：兼容 Android 16 的返回手势分发。
