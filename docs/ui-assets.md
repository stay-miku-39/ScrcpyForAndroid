# 小窗 UI 组件与图标

0.4.1 的小窗局部采用 [Material Components for Android 1.13.0](https://github.com/material-components/material-components-android/releases/tag/1.13.0)，使用 `MaterialButton`（透明底、白色图标、触摸涟漪）与 `MaterialShapeDrawable`（圆角框）。通过 `Theme.Scrcpy.Window` 仅包装小窗 Context，主界面及全屏菜单维持原主题。

`src/main/res/drawable/ic_window_power.xml` 和 `ic_window_fullscreen.xml` 根据 Google [Material Icons](https://github.com/google/material-design-icons) 的 `power_settings_new`、`fullscreen` 24 px 路径转写为 Android VectorDrawable。图标不使用字体字符，避免缺字。

- [电源原图](https://github.com/google/material-design-icons/blob/master/src/action/power_settings_new/materialicons/24px.svg)
- [全屏原图](https://github.com/google/material-design-icons/blob/master/src/navigation/fullscreen/materialicons/24px.svg)
- [原始 Apache-2.0 许可](https://github.com/google/material-design-icons/blob/master/LICENSE)，本地副本 [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt)。

`ic_window_resize.xml` 是项目自有的三条斜线缩放标记，左侧镜像显示。标记所在区域只响应拖动，不是点击缩放按钮。电源和全屏按钮保留中文内容描述及提示，完整设备名通过标题内容描述提供。

0.5.0 将单个窗口的视图/几何代码移至 `FloatingWindow.java`，由 `FloatingWindowService` 按设备管理多个实例；组件主题、图标、许可、等高两栏、圆角裁切和拖动手柄保持相同。

0.7.0 增加标题区域向边缘甩出关闭，沿用原有栏和图标，不新增关闭图标。字体调色盘 `QualityColorPicker.java` 为项目自有 Canvas 渐变和原生对话框/滑块/色块，无新增外部图片、组件依赖或第三方图标。
