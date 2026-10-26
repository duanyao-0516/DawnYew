# 离线小游戏平台与 2048 实施计划

> 历史记录：小游戏接入决定已于 2026-10-26 被独立产品方案取代。DawnYew 已移除 2048 运行依赖、保留猜拳；本文当时的代码路径和游戏验收结果不代表当前工程状态。见[拆除记录](../../implementation/2026-10-26-games-detachment.md)。

> 使用 subagent-driven-development；用户授权按悬浮完成→架构审查→游戏开发的顺序执行，不增加确认问题。游戏功能单独验证、交叉审阅。

**目标：**在“更多”中提供小游戏大厅，完整实现离线 2048，并让新增玩法通过独立插件注册。

**架构：**`games:api` 定义不依赖财务的插件描述、Compose 呈现、独立存档接口和注册表；`games:game2048` 实现纯规则、版本化存档、会话与呈现。Android 组合根注入已编译插件及按 gameId 隔离的 AtomicFile 存档。shared/ui 只接大厅和统一宿主，不根据玩法新增财务命令。

**技术：**沿用 Kotlin Multiplatform / Compose / kotlinx.serialization 与 Android AtomicFile；不新增网络、登录、Python、WebView 或动态代码下载。

## 约定

- 插件是编译期模块，不是动态加载未知二进制的安全沙箱。新增一个游戏实现 GamePlugin、独立状态编码与规则，再在组合根注册。
- GamePlugin 仅接收该游戏的 GameSaveStore；不能通过 API 获得 LedgerRepository、Command 或账务备份。
- gameId 使用稳定 ASCII 小写标识并校验唯一；页面标题独立。统一“小游戏”路由的对象 ID 标识当前游戏。
- 每游戏独立存档，大小上限 16 KiB；严格 schema version、棋盘/分数校验。损坏或未来版本显示恢复提示，由用户主动重开后覆盖，不静默丢失存档。
- 存档 IO 离开主线程，用串行、原子替换提交。有效操作保存完成后发布新状态；写失败留在前一可恢复状态并显示重试提示。
- 2048 支持四向滑动、方向按钮、分数/最高分、一步撤销、重开确认、2048 达成后继续、无步可走提示以及退出重进恢复；无效移动不生成新方块。
- 纯规则与随机生成分离，测试使用确定性随机源，验证一次合并限制、四向映射、无效步、结束/胜利与存档防损坏。
- 游戏 UI 沿用宿主 Material 主题，使用紧凑布局；手机上棋盘和主要操作无需反复滑动。方向按钮提供触摸滑动以外的可访问操作方式。
- 多语言玩法未来通过 GamePlugin 适配器接入；有实际插件需要时再评估具体运行时体积、ABI、生命周期和离线资源，不预装未使用环境。

## 文件与责任

- API/规则 agent：`games/api`、`games/game2048` 的规则/codec/session与测试，模块构建配置。
- UI agent：2048 插件 Compose 呈现、shared/ui 游戏大厅/宿主、更多入口及页面路由，必要的设备测试。
- root：Android 存档与组合根、构建接线、报告和验收；与 reviewer 对交付独立复核。

## 验收清单

- [x] 先完成小游戏 API/规则契约评审。
- [x] 规则/存档/会话测试先失败再实现并通过；新增游戏无需改财务核心。
- [x] 独立本地存档，重复输入串行，失败可见，不改账务数据。
- [x] 更多→小游戏→2048；滑动与按钮可玩，返回/重进恢复。
- [x] 确认重开、撤销、胜利继续和失败结束形成完整操作闭环。
- [x] 构建、单元测试、设备交互和独立 review。
- [x] 输出插件接入说明、实际验证范围及遗留债务。

## 冻结接口（实现前交叉审阅已通过）

包 `com.dawnyew.games.api`：

```kotlin
const val MAX_GAME_SAVE_BYTES = 16_384

data class GameDescriptor(val id: String, val title: String, val description: String)
data class GameSaveSnapshot(val payload: String?, val revision: Long)
class GameSaveConflictException : IllegalStateException
interface GameSaveStore {
    suspend fun read(): GameSaveSnapshot // null payload = 首次；IO 失败抛异常
    suspend fun write(payload: String, expectedRevision: Long): GameSaveSnapshot
    suspend fun reset(payload: String): GameSaveSnapshot // 仅显式重开/丢弃操作使用
}
interface GameStoreProvider { fun forGame(gameId: String): GameSaveStore }
interface GamePlugin {
    val descriptor: GameDescriptor
    @Composable fun Content(store: GameSaveStore, modifier: Modifier)
}
class GameCatalog(plugins: List<GamePlugin>) {
    val plugins: List<GamePlugin>
    fun find(id: String): GamePlugin?
}
```

ID 正则 `[a-z][a-z0-9-]{0,47}`。store 的 revision 是同一进程 provider 中的单调写入版本，非游戏 schema。组合根持有唯一 provider，每 ID 共享一个 slot / mutex；旧页面写入用旧 revision 时必须失败。取消等待中的操作不落盘；进入原子文件提交后完成或回滚，不能留下半文件。

2048 `id="2048"` 与首字符字母规则不一致，因此采用稳定 ID `game-2048`，显示标题为 `2048`。
