# Agent 开发约定

本文件适用于本仓库。用户本次任务的明确要求优先；版本、历史和完整验收项见下列文档，不要依赖聊天记忆。

## 开始工作

1. 查看 `git status --short`，保留用户尚未提交的修改。
2. 阅读 [开发接手文档](docs/development-handoff.md) 和与任务相关的源码。
3. 行为需求查 [需求与回归设计](docs/requirements-and-regression.md)；实际验证范围查 [TESTING.md](TESTING.md)。修改麦克风/摄像头前读 [INPUT_SHARING.md](INPUT_SHARING.md)；修改小窗组件前读 [UI 资源说明](docs/ui-assets.md)。
4. 用中文简明沟通，先说明将修改的范围。避免把小功能任务扩展为整体重构。

## 项目边界

- 根目录就是 Java Android 主控项目，应用包名 `org.scrcpy.android`。旧 `android-client/` 模块路径已失效，不要重新建立迁移子目录。
- 目标平台是 Android 16：`targetSdk 36`，主控 `minSdk 30`。本机用 `-PandroidCompileSdk=37` 适配已安装 SDK，这不等于将 targetSdk 升到 37。
- `scrcpy/` 是上游 Git 子模块，锁定 5.0.1。主控专用构建接入放在 `gradle/`；不要为普通 UI 修改改写上游或直接替换服务端版本。
- `vendor/adb/` 包含本地修复，不能当作可无损覆盖的原始依赖。修改时核对 [UPSTREAM.md](vendor/adb/UPSTREAM.md)，按影响运行流/USB 等对应测试并更新改动记录。
- 沿用现有 Java、程序化 View 与资源组织。Material Components 主题目前只包装小窗 Context，不要顺手替换全应用主题。
- 纯文档修改不升级应用版本、不重打 APK、不操作模拟器；检查文档链接、路径和 Git diff 即可。

## 必须保留的行为

1. **身份与记录**：覆盖安装保持同一 RSA 身份。不要为了连通测试清除应用数据、卸载应用、重置 Keystore 或删除配对记录。`devices` 与 `remote-ui` 分离；UI 修改不能改身份和设备历史。
2. **会话所有权**：全屏和小窗交接通过 `ScrcpySession.attachOutput()` 切换 Surface/监听者，复用服务端及音视频连接。旧视图销毁只能关闭仍由自己拥有的会话。关闭投屏保留 ADB，明确断开操作才断 ADB。
3. **线程与状态**：网络、Shell、文件与解码不能阻塞 UI。`MainUi.connected()` 在操作期间使用缓存状态是为了避免 ADB manager 锁卡住导航，不要改回主线程直接查询连接锁。
4. **输入释放**：保持 pointer ID 与实际视频坐标映射；离开、旋转和交接释放触点/音量键/电源键。组合键在一次写入内发送 DOWN，逆序 UP；按住动作必须有界。
5. **前台边界**：全屏失去前台停止普通远控；显式小窗由前台服务维持。小窗不读剪贴板、不录主控麦克风、不转发本机音量实体键。麦克风默认关闭，停止后不自动重启。
6. **方向**：全屏通过 Android Activity 原生方向请求跟随远端，默认开启且可关闭；不要仅旋转视频 View。小窗不强制改变主控方向。普通应用的沉浸模式仍允许系统边缘手势唤出本机栏。
7. **小窗最新设计**：两栏均 40 dp，2 dp 边框、12 dp 圆角统一裁切；左右下角是仅拖动的斜线手柄，不是加减按钮。电源/恢复全屏用矢量图标；标题显示 ADB 设备名，随剩余宽度末尾省略，保留顶部拖动。
8. **协议**：服务端与主控固定配套，修改协议前对照子模块源码。scrcpy 控制/媒体头与 ADB sync 的端序不同；不要凭旧版本经验改数字或报文长度。
9. **隐私与日志**：保留阶段、错误、帧/字节计数和必要设备诊断；不要记录 PIN、私钥、剪贴板正文或录音内容，不把测试控制入口加入正式 APK。

## 本机构建

Windows PowerShell，仓库根目录：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_USER_HOME = "$PWD\.gradle-local"
.\gradlew.bat -PandroidCompileSdk=37 :assembleDebug
# 需要设备测试 APK 时：
.\gradlew.bat -PandroidCompileSdk=37 :assembleDebugAndroidTest
```

- 使用 Gradle Wrapper，SDK 路径从本地 `local.properties` 读取，不提交机器配置和缓存。
- 当前 SDK XML / compileSdk 37 兼容性及 Java 8 编译目标提示已有成功构建证据，不要仅为消除提示升级整个工具链。
- 用户要求：若确有 JDK/SDK/Android Studio/模拟器环境问题，暂停并清楚说明，让用户手动解决。普通 Maven 依赖下载不是环境故障；沙箱权限不足也应先与真实环境故障区分。

## 测试与设备

- 按修改选择验证：局部 UI 用相关专项和必要的编译/静态检查；协议、持久化或生命周期变化再扩大回归。用户要求逐项实现时，每项测试通过后再做下一项，最后按要求回归。不要把旧记录写成本次重测。
- 可选本地检查：`:testDebugUnitTest`、`:adb-library:testDebugUnitTest`、`:lintDebug`；这些是根项目任务，执行时同样带 SDK 覆盖参数。
- 本机 ADB 为 `C:\Software\adb\adb.exe`。**每条设备操作明确指定 `-s`，先核对设备角色。** 历史上模拟器编号发生过变化，也出现过额外真实设备；不能对列表首项或所有在线设备运行测试。
- 最近验证角色为主控 `emulator-5554` / Medium_Phone、被控 `emulator-5556` / Medium_Phone_1；更早文档中的 5556 / 5558 是历史编号。IP、TLS 端口和 PIN 必须读取当次值。
- 设备测试入口为测试 APK 的 `org.scrcpy.android.test/org.scrcpy.android.SmokeInstrumentation`。专项参数：`-e uiStage window|power|rotation|settings`（每次一个值）；其余参数与完整命令见接手文档。
- 自动化会修改专用目标的临时设置、运行探针/录音测试、生成截图或清空日志。只用于明确指定的测试设备，结束恢复设置、设备名与偏好并停止测试组件。用户真机按其当次授权和复现步骤操作。
- 判断 `result=PASS` 与最终 `INSTRUMENTATION_CODE: -1`，不要仅依据主机 adb 进程退出码。区分 PASS、FAIL、SKIP、未测试；上游 USB 说明不等于本项目 Android OTG 实测。
- 已知非 ASCII ADB OPEN 编码问题尚未修复；小窗中文名称测试的 Base64 设置方式只是测试数据准备，不代表产品 Shell 通道已修复。详情见接手文档。

## 交付与文档维护

- 行为变化同步 `docs/requirements-and-regression.md`；实际运行结果更新 `TESTING.md`，架构/已知问题变化更新 `docs/development-handoff.md`。不要删除历史验证边界。
- 需要交付 APK 时更新 `build.gradle` 版本，将产物复制到 `dist/ScrcpyForAndroid-<版本>-debug.apk` 并更新 `dist/SHA256SUMS.txt`；保留旧产物。`dist/`、`build/`、`test-results-local/` 均被忽略，不强制加入 Git。
- 不覆盖已有本地日志/截图目录，使用本任务独立目录。保留图标/第三方代码来源与许可。
- 最后检查 diff、工作区和子模块状态，报告改动、实际测试范围、剩余限制以及相关文件/产物。不要自动推送、发布或改写历史。
