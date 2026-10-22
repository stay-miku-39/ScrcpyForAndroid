# 视频目标码率与软上限（0.8.0）

目标码率影响编码器的质量/压缩决策，是希望编码器达到的平均输出值；不能把 100 Mbps 目标理解成“允许最高 100 Mbps、平时自动省带宽”。用户在原设备上反馈，100 Mbps 目标改回 8 Mbps 后动画恢复正常。本次模拟器对照和硬件边界分别记录于 [TESTING.md](../TESTING.md)。

## 设置

常用的视频码率上限默认 20 Mbps，范围 0.1–100 Mbps。高级设置提供：

| 目标方式 | 生效值 |
| --- | --- |
| 默认 | `min(8 Mbps, 上限)` |
| 自动比例 | `max(0.1 Mbps, min(上限 × 80%, 8 Mbps))` |
| 自定义 | 输入 0.1–100 Mbps，不能高于上限 |

例如自动方式在 1/8/20/100 Mbps 上限时，目标为 0.8/6.4/8/8 Mbps，对应 80%/80%/40%/8%。这是优先控制编码负担、为突发留余量的保守初值，**不是用模拟器测出的各厂商最优百分比**。如果压缩细节不够，可在高级设置提高目标并实测帧率与解码排队。

旧版 `videoBitRate` 数值保留，新增 `videoTargetMode` 缺省为 `default`，不继续把旧高目标作为生效值。只有选自定义时才启用该字段。整页保存、离开丢弃草稿和仅新会话生效的规则保持；不修改身份、设备历史或运行中的会话。

## 软上限实现与边界

Android 公共接口允许配置目标码率以及运行中更新目标，但没有通用的独立峰值限额。AOSP 的 Codec2 映射中，`max-bitrate` 是从同一个 bitrate 参数读取的输出元数据，并非独立可写上限。因此本项目不以隐藏的 `max-bitrate` 字段冒充实际限制。[MediaCodec 动态目标](https://developer.android.com/reference/android/media/MediaCodec#PARAMETER_KEY_VIDEO_BITRATE)、[AOSP Codec2 映射](https://android.googlesource.com/platform/frameworks/av/+/master/media/codec2/sfplugin/CCodecConfig.cpp)。

服务端在现有视频编码线程统计压缩有效载荷（含关键帧/配置，不含 ADB/IP/音频）。十个 100 ms 桶近似最近一秒；首秒只观察，之后最多每秒调节一次。超限时按 `当前目标 × 上限 / 实测 × 85%` 降低，最低 0.1 Mbps；输出连续低于上限的 70% 达约 3 秒后，每秒最多恢复 10%，且永不超过用户初始目标。静止时的恢复也只回到初始目标，不会追逐更高的上限。85% 留出桶边界、测量及编码器响应余量。

调用 `MediaCodec.setParameters(PARAMETER_KEY_VIDEO_BITRATE)` 改变后续编码；不额外排队、不等待额度、不丢弃压缩参考帧。首帧、关键帧或复杂画面可短时超过上限，也不能保证任意网络窗口、音视频总流量或线速硬上限。编码器拒绝更新，或最低目标下连续约 5 秒仍超限，会记录并提示“软上限未能执行”，停止继续调节，画面保持运行；用户应降低尺寸/帧率或更换编码器。该探测不能证明驱动完全服从每次目标更新。

## 配套构建

`scrcpy/` 仍锁定原始 5.0.1，不修改子模块。`gradle/scrcpy-server.gradle` 将原 Java 源复制到构建目录，在 `SurfaceEncoder.encode()` 的初始化和成功写包后各插入一个显式调用，匹配位置不唯一时构建失败。扩展位于 `src/serverExtensions/`，通过 `AndroidServer` 启动包装将数值上限传给限额器，再调用原 `Server.main()`。上游控制消息、会话头、媒体包和版本校验没有变化；直接运行原入口不会开启本项目的软上限。普通 UI 修改仍不应增加服务端补丁。

限额器按编码器实例重建，旋转/重试会重新观察首秒；设置按会话快照固定。CBR 仍是可选编码器控制方式，兼容回退只移除 CBR 请求，软上限继续保留。动态调整记录的目标可能低于高级设置的初始目标；质量窗口的“目标”明确表示初始请求，实际输出另列。

## 验证入口

- `:testDebugUnitTest`：三种策略、低上限、无效值、旧目标留存和自定义约束。
- `:server:testDebugUnitTest`：超限降目标、更新频率、静态恢复/初始目标封顶、旧桶失效及大字节数运算。
- `-e featureCase bitratePolicies`：真实设置控件、升级解释、校验、保存及离开丢弃。
- `-e mediaCase rateLimit`：三种模式的小上限编码、测试 APK 的纹理动画及桌面手势，读取生效/调节日志和有效载荷 CSV。
- `-e mediaCase performance`：同一桌面手势下 1080×2400、H.264、120 fps 上限、8/100 Mbps 自定义目标；仅专用目标允许执行。软上限设 100 Mbps，结果需同时注明它可能触发调节。

专项的通道参数与基础回归命令见 [接手文档](development-handoff.md)。通过判据同时包括 `result=PASS` 和 `INSTRUMENTATION_CODE: -1`；历史测试不算本次运行。
