# 更多页离线小工具实施计划

> 使用 subagent-driven-development 分工实现，先规格审查再代码质量复核；用户已明确要求直接添加五个工具。

**Goal:** 在更多页提供调色盘、全屏厘米尺、噪音测量、做个决定和双人石头剪刀布，账务工具仍留在我的。

**Architecture:** 共享 UI 承担颜色、随机选择和展示；UtilityPlatform 隔离尺子像素校准、窗口和麦克风能力。Android 本机工具校准保存在独立 SharedPreferences，不进入账本备份，不修改全局配置。

**Tech Stack:** 既有 KMP、Compose Material 3/Canvas、AudioRecord、WindowInsetsControllerCompat，无新依赖或网络权限。

## 交互与边界

- 调色盘支持 #RRGGBB、#RGB、RGB 三通道 0–255，色相/饱和度点选与亮度调整。非法输入显示错误，不覆盖有效颜色。
- 尺子独占全屏，左右边缘绘制毫米小刻度及厘米数字。采用纵向物理 ydpi，而非逻辑 dp；默认标未校准，提供与实物尺比较的校准和重置。校准与显示设备/物理分辨率/方向绑定（刷新率和系统栏变化不影响比例），系统 UI 在返回、后台和销毁时恢复；零点留安全边距并明确标出。
- 噪音只在点击开始、录音权限已授予且应用前台解锁时采样；离页、后台、锁定、销毁全部停止。不存储或上传录音。权限回调不自动开始，避免权限弹窗触发的暂停造成错误授权延续。
- 音量按 PCM RMS 计算 dBFS；静音不伪造 0 dB。参照声级计校准后显示估算 dB，并持续注明未进行频率计权、不能替代专业声级计。采样源变化时旧校准不复用，削波需提示。
- 做个决定支持增加、删除 2–20 个有效选项，拒绝空白和重复项，每项最多 200 字符，均匀随机选一个。编辑后清除旧结果。
- 石头剪刀布上下各占一半，中央圆形按钮同时随机两方，显示各方手势及胜负；上半区旋转面向对坐者。
- 工具输入在当前会话导航与旋转时保留，不进入财务数据库。无 iOS 新工程，无后台录音，无账号或联网。

## 文件与分工

- [x] Root：UtilityPlatform.kt 契约；DawnYewApp.kt 更多入口/二级路由/全屏；MainActivity.kt 权限、窗口与生命周期适配；Manifest。
- [x] 共享颜色与游戏：UtilityModels.kt / UtilityModelsTest.kt；PaletteScreen.kt / DecisionScreen.kt / RockPaperScissorsScreen.kt。颜色转换、输入校验、随机边界与九种胜负组合主机测试。
- [x] 测量 UI：RulerScreen.kt / NoiseScreen.kt；清楚的校准流程、无测量状态/失败状态、无障碍与大字滚动。
- [x] Android：AndroidNoiseMeter.kt / AndroidRulerMetrics.kt 与纯信号计算测试；资源失败释放、权限/后台停止、硬件与校准身份。
- [x] 独立规格/质量 review 后运行 ./dev.sh check --offline --console=plain，修复确定缺陷。
- [x] 开发密钥签名覆盖安装，保留数据；遵循用户此前不追加手机操作验证的指示。更新文档、记录人工硬件校准清单，源码推送当前 GitHub 分支，APK 忽略。

## 依据

- Android DisplayMetrics：https://developer.android.com/reference/android/util/DisplayMetrics
- Android AudioRecord：https://developer.android.com/reference/android/media/AudioRecord
- Android 音频输入与未处理源：https://developer.android.com/media/platform/mediarecorder

## 完成结果

282 项主机测试通过，Debug/AndroidTest/优化 Release/JNI/Lint 检查通过；独立最终质量 review 无确定 P1/P2。开发签名 APK 覆盖安装成功，未操控应用或采集录音。完整证据与人工硬件校准清单见 [小工具验证记录](../../implementation/2026-10-18-more-tools-verification.md)。
