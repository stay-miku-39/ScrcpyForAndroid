# Android 14+ 主控麦克风与摄像头共享调查

调查与原型验证日期：2026-10-18。当前交付版本：0.3.0。

## 结论

| 能力 | 当前结论 |
| --- | --- |
| 主控麦克风 → 被控应用的录音输入 | 已实现实验功能：借助已有 ADB shell 权限，按应用 UID 建立录音注入路由；Android 16 模拟器通过跨应用实际录音验证 |
| 主控摄像头 → 任意被控应用的原前/后摄像头 | 当前普通 APK + ADB 模式下没有通用实现；本版本未增加摄像头采集或虚假的“已共享”入口 |
| 仅把声音/视频传到被控设备播放 | 不能代表输入替代；本次麦克风测试验证的是目标应用实际调用 AudioRecord 获取到转发数据 |

Android 14 是本功能支持的最低被控版本，不代表每个 Android 14/15/16 ROM 都开放相同的系统路由能力。
本机只有 Android 16 模拟器完成了运行验证；Android 14/15 真机及厂商系统仍需实测。

## 麦克风方案

Android 14 的 AOSP 已包含 `AudioMixingRule.MIX_ROLE_INJECTOR`、按 UID 匹配规则和
`AudioPolicy.createAudioTrackSource()`。该组合可把 PCM 数据送入匹配应用的录音流。
它需要 `MODIFY_AUDIO_ROUTING`，不是普通应用的运行时录音授权。
本次 Android 16 `user` 构建的 ADB shell 确实拥有该权限，因此接收端通过 ADB 启动，
不要求在被控端安装主控 APK，也未使用 root 或更改系统权限策略。

数据路径：

```text
主控 AudioRecord（需用户授予 RECORD_AUDIO）
    → 48 kHz / 单声道 / PCM16
    → 独立的 ADB localabstract 通道
    → 被控端 shell 接收进程
    → 按目标 UID 注册的 AudioPolicy / AudioTrack
    → 被控应用自己的 MIC / VOICE_COMMUNICATION 录音流
```

接收代码在 `src/inputServer/`，构建接入在 `gradle/input-server.gradle`。
它复用固定版本 scrcpy 服务端的 shell Context 初始化；scrcpy 子模块源码保持原样。
接收 APK 作为资产打包，只作为 dex 容器经 `app_process` 运行，不安装到被控设备。
退出时关闭通道、撤销 AudioPolicy 并清理临时接收文件；进程异常退出也由 Binder 生命周期释放路由。

PCM 长度头与数据合并为一次 ADB 写入，避免分开写入时每帧多一次等待确认而产生欠载。
主控录音与网络读取不占 UI 线程。发送停滞超过约 5 秒时停止采集，接收端也有读取超时。
日志只记录目标、阶段、字节计数和异常，不保存录音内容。

### 使用

1. 建立 ADB 连接并进入全屏远控，在被控端打开需要使用麦克风的应用。
2. 点击悬浮球 → **转发麦克风**。
3. 确认被控应用包名，默认填写当前前台应用；点击“开启”，在主控端授予麦克风权限。
4. 看到顶部“麦克风正在转发至…”后，再让被控应用开始录音或网络通话。
5. 点击悬浮球 → **停止麦克风**可恢复原输入。离开全屏、退出远控或切换小窗也会停止；不会自动重启录音。

转发默认关闭，每次需主动开启。拒绝主控权限时不采集音频。
被控应用仍需正常取得自己的录音权限，系统隐私开关也必须允许录音。
切换到另一个被控应用时，需要停止并为新应用重新开启。

### 边界

- 目前只在主控全屏远控保持前台时工作，小窗和后台不采集麦克风。
- 按应用 UID 路由；使用共享 UID 的应用可能共享该输入。未实现跨工作资料/多用户的应用选择。
- 不保证支持 SIM 电话、运营商通话、指定物理输入设备或厂商专有音频路径。
- 应用已开始录音后再切换路由可能需要重新开始录音/通话。不能承诺所有即时通讯应用均兼容。
- PCM 增加约 96 KB/s 上传数据；网络质量影响延迟和连续性。按设备能力尝试系统回声消除与降噪，未完成真实双向通话的音质和回声评估。

## 摄像头为何没有直接增加

scrcpy 现有 `--video-source=camera` 是捕获**被控设备自己的摄像头**，方向与本需求相反；
Linux 的 V4L2 输出也不能直接变成 Android 被控应用的 Camera2 输入。

AOSP 的 Companion App Streaming / VirtualDevice 框架确实设计了远端麦克风、摄像头能力。
但这是具有系统特权角色的应用串流方案，涉及独立虚拟设备/显示上下文，
不是给普通 APK 的任意主显示应用替换前后摄像头的 API。
官方文档说明 `COMPANION_DEVICE_APP_STREAMING` 角色只允许预装 priv-app 持有。

另外，Android 15 的 `CAMERA_INJECT_EXTERNAL_CAMERA` 权限为 `signature`。
本次测试设备的 ADB shell 没有该权限，服务列表也没有可用的 `virtual_camera` 服务。
因此“有 Android 14+、有 ADB”不足以推出能替换任意应用相机输入。
摄像头若继续实现，需要明确的 OEM/系统集成环境或其他受控运行环境，并单独验证目标应用兼容性。

Android 14 QPR1+ 的“用作 USB 摄像头”是另一个硬件路径：主控设备以 UVC 外设身份连接 USB 主机。
它要求主控厂商实现 UVC gadget、被控设备支持外接 USB 摄像头，且目标应用能选用该摄像头。
这既不是无线 ADB 摄像头注入，也不保证替换原有前/后摄像头；本次没有改变设备 USB 功能或角色。

## 验证方法和证据

专用设备：主控 `emulator-5556`、被控 `emulator-5558`，均为 Android 16 / API 36。
`-e microphoneOnly true` 只运行新增麦克风专项，绕开文件、扫描、小窗等完整测试。

```powershell
.\gradlew.bat -PandroidCompileSdk=37 :assembleDebug :assembleDebugAndroidTest
.\gradlew.bat -PandroidCompileSdk=37 :testDebugUnitTest --tests org.scrcpy.android.MicrophoneTargetTest

# 按根 README 安装主控与测试 APK；仅对专用目标测试包授予录音权限。
& C:\Software\adb\adb.exe -s emulator-5558 shell pm grant org.scrcpy.android.test android.permission.RECORD_AUDIO
& C:\Software\adb\adb.exe -s emulator-5556 shell am instrument -w -e host 10.0.2.17 -e port 41929 -e microphoneOnly true org.scrcpy.android.test/org.scrcpy.android.SmokeInstrumentation
```

测试经生产转发通道发送确定的 1 kHz PCM，另一个 UID 的独立测试 Activity 使用正常 AudioRecord 录音。
测试音不是从被控扬声器播放再被麦克风收音；录音设备类型为 `TYPE_REMOTE_SUBMIX`（25），
`MIC` 和 `VOICE_COMMUNICATION` 分别检测到约 9999 的测试音幅度。
关闭后恢复 `TYPE_BUILTIN_MIC`（15），测试音幅度降至约 0.09。
测试同时保持 scrcpy 视频解码，用合成源测试时不采集真实主控麦克风。

本地原始日志保存在忽略目录 `test-results-local/input-sharing/`。
这不能替代 Android 14/15 真机和真实通话应用测试，详细本轮结果见 `TESTING.md`。

## 官方资料

- [AOSP Companion app streaming](https://source.android.com/docs/core/permissions/app-streaming)：角色、权限、虚拟显示及音视频重定向范围。
- [Android 14 AudioMixingRule](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/media/java/android/media/audiopolicy/AudioMixingRule.java)：输入混合角色及 UID 规则。
- [Android 15 VirtualAudioSession](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/core/java/android/companion/virtual/audio/VirtualAudioSession.java)：系统的按 UID 注入实现。
- [Android 15 AudioPolicy](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/media/java/android/media/audiopolicy/AudioPolicy.java)：权限检查、输入 Track 创建。
- [Android 15 权限定义](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/core/res/AndroidManifest.xml)：摄像头注入的签名权限。
- [AOSP USB webcam](https://source.android.com/docs/core/camera/webcam)：Android 14 QPR1+ UVC 的硬件和系统集成要求。
- [Android 14 前台服务类型](https://developer.android.com/about/versions/14/changes/fgs-types-required#microphone)：未来若支持后台麦克风还需满足相应前台服务与录音权限限制；本版本未增加后台采集。
