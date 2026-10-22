# 项目文档索引

新 agent 从根目录 [AGENTS.md](../AGENTS.md) 开始，再阅读 [开发接手文档](development-handoff.md)。

| 文档 | 内容 |
| --- | --- |
| [开发接手文档](development-handoff.md) | 全部开发阶段总结、架构/源码索引、数据和协议约束、构建测试、踩坑与已知问题 |
| [需求与回归设计](requirements-and-regression.md) | 当前行为、需求编号、验收矩阵和实体机补充清单 |
| [码率控制](bitrate-control.md) | 0.8.0 目标策略、服务端软上限、配套构建和验证边界 |
| [多指队列分析](touch-queue-analysis.md) | 修改前复现、MOVE/UP 寿命与批写策略、真实延迟验证边界 |
| [本轮开发清单](current-development-checklist.md) | 六项功能的顺序开发和验证状态 |
| [测试记录](../TESTING.md) | 各版本实际测试结果、跳过项及证据边界 |
| [输入共享调查](../INPUT_SHARING.md) | Android 14+ 麦克风方案、摄像头限制、权限和验证 |
| [UI 组件与资源](ui-assets.md) | Material Components、小窗矢量图标来源和许可 |
| [第三方 UI 许可副本](licenses/Apache-2.0.txt) | 图标等使用的 Apache-2.0 许可文本 |
| [LibADB 来源与补丁](../vendor/adb/UPSTREAM.md) | 固定版本、来源提交及本地传输修复 |
| [USB 设计说明](../vendor/adb/USB_TRANSPORT.md) | vendored USB 通道实现；其中硬件记录不代替本项目 Android OTG 实测 |

功能需求变化更新需求文档，实际运行更新测试记录，架构/已知问题变化更新接手文档。历史结果保留版本范围；新工作不自动继承为已经重测。
