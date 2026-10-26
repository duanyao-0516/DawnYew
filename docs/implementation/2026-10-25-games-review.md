# 小游戏平台交叉审阅

> 历史记录：小游戏接入决定已于 2026-10-26 被独立产品方案取代。DawnYew 已移除 2048 运行依赖、保留猜拳；本文当时的代码路径和游戏验收结果不代表当前工程状态。见[拆除记录](2026-10-26-games-detachment.md)。

日期：2026-10-25。对象为当前工作区。此记录是源码交叉审阅；构建、设备手势和实际存档验收由主流程另行记录。

## 结论

当前审阅范围没有待修阻塞项。规则/API 作者与 Android 存档作者分别接受非作者审阅；不将“作者自测通过”替代交叉审阅。

- **边界成立：**插件只接收 GameSaveStore；GameCatalog 复制列表并校验稳定 ID、唯一性。游戏不依赖 LedgerRepository、财务命令、账本备份、Android Context 或额外运行时，见 GameApi（`games/api/src/commonMain/kotlin/com/dawnyew/games/api/GameApi.kt:6`，历史路径）。
- **规则与恢复成立：**四向映射、单次合并、无效移动不生成方块、胜利继续、一步撤销及最高分保留符合计划。存档严格检查字段、schema、整数、棋盘和大小；未知/损坏存档保持原样，仅显式重开使用 reset。
- **已发现并修复：**原实现允许极端 tile/score 进入有效存档，合并溢出可从规则层抛到 UI。现已限制棋盘总量与分数范围、预留运算余量，超限存档作为 INVALID_SAVE 保留；Session.busy（`games/game2048/src/commonMain/kotlin/com/dawnyew/games/game2048/Game2048Session.kt:103`，历史路径） 将规则参数异常转为可见错误，保留旧棋盘、退出 busy，允许重载/重开。
- **解析深度已收紧：**Game2048Codec（`games/game2048/src/commonMain/kotlin/com/dawnyew/games/game2048/Game2048Codec.kt:21`，历史路径） 在 JSON 解析前检查深度不超过 8；扫描正确处理字符串及转义，格式结构仍由解析器与字段校验确认。
- **取消语义已闭合：**提交前取消不发布新棋盘；原子写已完成但返回取消时保留旧 revision，下一次写必须冲突并重载，不能绕过 CAS。相应 session 回归测试已覆盖。

## Android 存档的非作者复核

主代理已独立审阅 SerializedGameSaveStore（`androidApp/src/main/kotlin/com/dawnyew/app/games/SerializedGameSaveStore.kt:24`，历史路径） 与 AtomicGameStoreProvider（`androidApp/src/main/kotlin/com/dawnyew/app/games/AtomicGameStoreProvider.kt:15`，历史路径），结论通过：Application 持有唯一 provider，每 ID 共用 slot/mutex；IO 内核对 revision；等待取消不写，原子提交段 NonCancellable；成功后推进 revision，失败保留版本以重试。存档位于独立 noBackup games 目录，严格 UTF-8、最多 16 KiB，读取失败不伪装首次启动，插件拿不到 Context 或路径。

## 已读到的验证证据与限制

- API、engine、codec、session：26 项 JVM 测试，0 失败；证据 `.local/game-final-green.log` 及四份 JUnit XML。
- Android 存档：11 项 JVM 测试，0 失败；证据 `.local/game-store-green.log`。首次尝试被依赖校验拦住；随后缺 provider 的编译失败可归因于尚未实现，但不把它描述为 11 项行为断言的 RED。
- AndroidTest Kotlin 已编译；5 项 AtomicFile 设备测试已准备，覆盖中断恢复、旧备份恢复、失败重试、坏 UTF-8/超限保留与 provider 隔离。本记录生成时未将其宣称为设备已通过，最终以统一验收为准。

最终设备更新：真实 AtomicFile 5 项和游戏操作 1 项已通过；同包悬浮回归也通过。详见 [最终验收](2026-10-25-games-verification.md)，源码审查和设备证据保持分开。
