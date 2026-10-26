# 离线小游戏平台与 2048 验收

> 历史记录：小游戏接入决定已于 2026-10-26 被独立产品方案取代。DawnYew 已移除 2048 运行依赖、保留猜拳；本文当时的代码路径和游戏验收结果不代表当前工程状态。见[拆除记录](2026-10-26-games-detachment.md)。

日期：2026-10-25。入口为“更多 → 小游戏 → 2048”。当前只有已完成的 2048 插件，不展示未实现的游戏入口。

## 交付内容

- 新增 `games:api` 与 `games:game2048` 独立模块；GameCatalog 统一注册，宿主根据稳定 gameId 呈现。
- 完整 2048：滑动/四向按钮、分数/最高分、一次合并规则、一步撤销、重开确认、达成 2048 后继续、无合法移动结束、退出再进恢复。
- Android 独立 AtomicFile 存档，限制 16 KiB、严格 UTF-8，IO mutex 与 revision CAS 防旧页面覆盖；取消等待不写，已开始提交完成或回滚。
- schema version、棋盘/分数/字段/重复键/结构深度校验。损坏或未知版本保留原文，显式确认重开才覆盖。
- 不增加登录、网络权限、Python/WebView 运行时，不读写账务 Command、数据库、preferences 或备份结构。
- 接入方式见 [小游戏接入说明](../games/README.md)，实现前决定见 [计划](../superpowers/plans/2026-10-25-mini-game-platform.md)，非作者审阅见 [交叉审阅记录](2026-10-25-games-review.md)。

## 测试结果

| 范围 | 结果 |
|---|---|
| 插件目录 API | 2 项 JVM 测试通过 |
| 2048 规则、codec、session | 24 项 JVM 测试通过 |
| Android 游戏存档 | 11 项 JVM 测试通过，包含于 App 33 项中 |
| 现有 UI / App 回归 | UI 17、App 33 项通过 |
| 本轮其它模块 | core 88、presentation 140、平台 130 项通过 |
| 本轮单元测试合计 | 434 项，0 failures/errors |
| 集成构建 | Debug、AndroidTest、Release（含 R8 / lintVital）通过 |
| 游戏设备 | GameDeviceTest 1 项 + AtomicGameStoreDeviceTest 5 项，`OK (6 tests)`，4.924 秒 |
| 同包悬浮回归 | FloatingCaptureDeviceTest，`OK (1 test)`，24.046 秒 |
| 最终文案调整 | GameDeviceTest 再次通过，5.512 秒；已更新实际截图 |

测试使用 API 36 ARM64 模拟器与 `com.dawnyew.app.validation` 隔离包。游戏设备测试真实操作更多页入口、方向按钮与触屏滑动，核对盘面和磁盘存档；覆盖撤销、返回重进、Activity 重建、重开取消/确认、胜利继续、失败、未知版本恢复。结束前比较完整账务快照，游戏操作未改变账务数据。

AtomicFile 设备测试验证真实文件中断恢复、旧 `.bak` 恢复、写失败重试、坏 UTF-8/超限字节保留、ID 隔离和重新读取。磁盘重开与 Activity 重建不冒称真实杀进程重启或所有 OEM 兼容验证。

## 实际视觉核验

主代理检查了测试设备截图，棋盘、方向按钮、撤销与重开同屏，无裁切或溢出。游戏使用宿主主题。结束状态无撤销历史时不再误提示“可以撤销”。测试仅对隔离包的合成游戏棋盘临时关闭 FLAG_SECURE 取图，并立即恢复；生产安全窗口没有改动。

截图与设备日志保存在：

- `.local/dawnyew-games-device-evidence/games-validation/game-2048.png`
- `.local/dawnyew-games-device-evidence/games-validation/2048-game-over.png`
- `.local/dawnyew-games-device-test.log`
- `.local/dawnyew-games-final-ui-test.log`
- `.local/dawnyew-floating-final-regression.log`

## 审阅发现与闭合

原始数值范围可能允许极端 tile/score 触发合并溢出。现已限制可接受数值并在会话边界处理规则异常；补测证明失败保留盘面与存档且可明确重开。JSON 在解析前限制深度为 8，拒绝 1000 层嵌套。提交完成后收到取消时，旧会话仍持旧 revision，继续写会冲突，重载读取实际已提交状态。

已独立复核插件 API/规则和 Android 存档；当前范围无未处理阻塞。架构审查列出的历史通知 TTL、部分暂存删除反馈等后续债务仍然单独保留，没有以小游戏完成为由宣称全部历史问题已修复。

## 依赖与安装包

新模块依赖沿用项目已有 Kotlin / Compose 体系。保留依赖验证；相对 HEAD 新增的 33 个校验条目（含前序 OCR BOM 条目）全部从 Google Maven 或 Maven Central 重新获取并逐项核对 SHA-256，33/33 匹配。公开坐标、来源与摘要见 [依赖核验](2026-10-25-game-dependency-verification.json)。

正式包名 Debug 安装包：`.local/artifacts/DawnYew-2026-10-25-debug.apk`，包名 `com.dawnyew.app`，版本 `0.1.0`，88,267,661 字节。

SHA-256：`6e30c03dd7c7d67150ddb932a7e349cd7107acf727e3e29277c227090d667f42`。

它由同一最终源代码单独构建，仅生成文件，没有覆盖用户手机的正式应用。设备运行验证采用上述隔离包；Release 完成构建及混淆，未进行发布版安装验证。本轮没有 Git 提交或推送，原有三份本机配置/报告变更的 SHA-256 保持一致。
