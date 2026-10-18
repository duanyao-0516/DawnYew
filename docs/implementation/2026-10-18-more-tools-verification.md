# 更多工具实现与验证记录

日期：2026-10-18。新增五个独立工具：调色盘、尺子、噪音测量、做个决定、石头剪刀布；原账务和备份功能保留在我的。

## 使用与口径

- 调色盘：RGB 三通道和 #RGB/#RRGGBB 输入、色盘点选/拖动、亮度调节、颜色预览与色值复制。
- 尺子：全屏双侧刻度，1 mm 小刻度、1 cm 数字，零点位于屏幕上方留白后的标记线。以平台物理像素密度初始化，实物校准后保存当前显示配置的比例。
- 噪音测量：前台点击开始并获得麦克风权限后采样，退出、锁定或后台即停止。PCM 样本仅留内存，不录制文件、不上传。未经校准显示相对满量程 dBFS，静音不显示虚假的 0 dB；参照声级计校准后显示估算 dB。未实现 A/C 计权，不替代专业声级计。
- 做个决定：2–20 个有效且不重复的选项，每项最多 200 字符，等概率随机选一个，编辑清空旧结果。
- 石头剪刀布：上下双人布局，中央圆形按钮同时随机揭晓双方手势与胜负。

## 人工硬件与交互清单

沿用用户此前不追加手机操作验证的要求，本轮不自动启动应用、授权麦克风或操作页面。以下项目需要设备持有人实际校验：

1. 调色盘输入 `255 / 0 / 0` 与 `#F00` 均应为红色；非法数值不覆盖有效颜色；点选/拖动与亮度、复制结果一致。
2. 尺子用真实物理尺测量标记 0 到 5 cm 的实际距离，在校准面板输入该距离；保存后重新核对 1、5、10 cm（屏幕能容纳时）。不得以手机截图像素验证物理长度。横竖屏/系统分辨率改变后核对校准状态，返回与后台恢复系统栏。
3. 噪音首次申请权限，允许后再点开始；拒绝、永久拒绝和系统麦克风关闭时有明确提示。测试返回、Home、熄屏、应用锁、旋转后麦克风占用停止且不会自动重启；测试耳机/蓝牙接入、录音被其他应用占用。
4. 噪音与参考声级计处于同一稳定声场，输入参考 dB 校准；比较不同音量和频率。削波时不允许校准。单点校准只提供估算，手机麦克风响应和处理链可能引入误差。
5. 做决定增删、空白、重复、全部20项及长文本；每次结果必须属于有效选项。
6. 石头剪刀布上下同时变化，结果遵循石头胜剪刀、剪刀胜布、布胜石头和平局；上方内容面向对坐者。
7. 工具页切换、返回、旋转、大字号和深浅色；今日/统计/记账/导入导出等原功能入口可达。

## 技术依据

[Android DisplayMetrics](https://developer.android.com/reference/android/util/DisplayMetrics) 区分逻辑密度与物理 xdpi/ydpi；厘米尺采用物理密度并允许实物修正。[Android 音频指南](https://developer.android.com/media/platform/mediarecorder) 说明运行时录音权限、未处理输入源及 VOICE_RECOGNITION 回退；本实现使用 [AudioRecord](https://developer.android.com/reference/android/media/AudioRecord) 的 PCM 内存采样而非音频文件录制。

## 自动化与交叉 review

最终 `./dev.sh check --offline --console=plain` **BUILD SUCCESSFUL in 11s**，包含主机测试、UI JVM、Debug/AndroidTest APK、优化 Release、OCR JNI 与 Release Lint。日志保留于 `.local/more-tools-check.log`（忽略上传）。

| 模块 | 测试通过 |
| --- | ---: |
| core | 41 |
| protocol | 16 |
| storage | 39 |
| presentation | 82 |
| platform Android | 104 |
| 合计 | **282** |

0 失败、0 错误、0 跳过。新增 18 项测试覆盖 RGB/HEX、HSV 往返、随机选择边界、九种猜拳组合、选项长度，以及 PCM RMS、静音、饱和、分块采样和物理尺比例校验。没有把主机信号测试描述为真实麦克风精度验证。

Release Lint 0 error、7 个既有 warning。OCR JNI ABI 129 classes / 3006 methods / 1352 fields 验证通过，应用混淆有效。最终 APK 权限回读确认新增 RECORD_AUDIO，没有 INTERNET/ACCESS_NETWORK_STATE；麦克风硬件为可选。

三个 Agent 分工实现颜色/游戏、测量界面、Android 硬件模块。交叉规格审查修复了小横屏猜拳滚动区域为零、显示配置变化未刷新尺子、无效噪音校准静默失败；主线程补充色轮亮度一致性和输入长度约束，并修正音频释放后的回调隔离。独立最终质量 reviewer 未发现确定 P1/P2。代码空白检查通过。

尺子校准绑定设备、物理分辨率、方向与 DPI，刷新率和系统栏变化不使比例失效。DisplayListener 与前台恢复都会重新检查配置，Activity 代次与配置 revision 防止在新配置上误用旧实测输入。

## 本地产物

开发密钥签名优化 Release：`artifacts/releases/DawnYew-0.1.0-more-tools.apk`，52,008,918 bytes；签名及 16KB ZIP 对齐检查通过。

SHA-256：`d9f724d0abe9ab3860bde4e80b0d7aaa252ae83f315e4d73bfaba9ce5491e591`。

已以 `adb install -r` 覆盖安装到已连接 PKM110，返回 Success；未卸载或清理数据，未启动/操控应用或申请麦克风权限。

APK、签名附属文件、本地测试日志继续由 `.gitignore` 排除；代码、必要构建输入和文档上传 GitHub。
