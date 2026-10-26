# 整体架构审查：界面、导航与功能接入

> 历史记录：小游戏接入决定已于 2026-10-26 被独立产品方案取代。DawnYew 已移除 2048 运行依赖、保留猜拳；本文当时的代码路径和游戏验收结果不代表当前工程状态。见[拆除记录](2026-10-26-games-detachment.md)。

审查日期：2026-10-25。范围为当前工作区，而非仅 HEAD；此报告为源码审阅，不代表全部历史页面重新进行设备验收。

## 现状与保留的边界

- `shared/ui` 通过 `AppController`、`FinanceController` 和平台接口交互，未直接获取 DAO 或 Android Context。`PlatformActions` / `CapturePlatform` / `UtilityPlatform` 将文件、授权、截图和硬件功能留在 Android 层。
- `FinanceController.openRoute/back` 保存完整导航帧，包含范围与来源；设置、我的、更多使用 `SaveableStateProvider` 保存页面状态。`EntryComposerState`、独立输入布局和重排组件已经存在，不应为接新功能重新写另一套输入/开关/拖动组件。
- `FinancialRead`、分页与统计协调器使用查询范围和数据来源检查，避免旧异步结果覆盖新范围；`DawnYewApp` 还核对 datasetId/contentVersion 后展示派生统计。
- 本次全局悬浮 UI 属于 AccessibilityService 的真实系统窗口，不能并入 Compose 页面树。普通功能页面仍应复用现有 Material 主题及平面设置行。

## 有证据的后续改进

| 级别 | 位置与证据 | 影响 | 建议 |
|---|---|---|---|
| P2 | `shared/ui/.../DawnYewApp.kt`：顶部同时订阅 editor、多个统计、finance、recurring、dashboard；`when(page)` 维护全部页面装配 | 应用外壳承担了页面查询、派生值、导航、弹窗和呈现，新增页面更容易牵动无关状态。尚无性能跟踪证据证明已经掉帧 | 按页面族提取状态订阅与装配组件，保留现有返回栈及查询协调器；先测量再决定是否优化，避免整体重写 |
| P2 | `shared/presentation/.../FinanceController.kt` 的 `FinanceNavigation.page:String`、`open(page:String)`，以及 `DawnYewApp.kt` 的中文字符串路由 | 路由拼写或重命名不受编译器检查，未知入口只能在运行时落到“此页面已不可用” | 后续将导航标识与显示标题分离；当前小游戏使用稳定插件 ID 和统一宿主路由，不为每个游戏继续扩展财务控制器 |
| P2 | `shared/presentation/.../UtilityModels.kt` 同时包含颜色、随机选择、猜拳规则，`MoreScreen.kt` 静态维护工具入口 | 多个游戏若继续塞入此文件，会把账务 presentation 变成所有玩法的集中依赖 | 新建无账务依赖的游戏模块，插件只拿独立存档接口；既有猜拳可后续单独迁移，不在此轮无关改写 |
| P3 | `androidApp/.../capture/FloatingCaptureOverlay.kt` 原生 View 定义颜色/尺寸，`shared/ui/.../Theme.kt` 单独定义 Compose tokens | 两套呈现方式可能逐步产生颜色和暗色模式差异，当前悬浮面板使用固定浅色 | 后续把跨原生/Compose 的必要 token 放入小型共享契约或 Android 主题资源；不要让全局服务依赖整棵财务 UI |

以上属于演进成本与一致性风险，未将其描述为已经复现的财务错误。悬浮流程本轮发现的构造期 Context 访问、回执命名、代际写入与取消问题，另见悬浮验收及总报告中的修复记录。

## 小游戏平台接入约束

1. 更多页添加“小游戏”入口，游戏大厅和所有游戏共用一个宿主页面；2048 是首个插件，其他游戏不显示空入口。
2. 游戏插件描述、规则、状态编码与呈现注册保持明确边界。插件不能访问 LedgerRepository、Command、账本备份或 OCR 权限。
3. 存档按稳定游戏 ID 与 schema version 隔离，退出/进程重启恢复；损坏存档可重置，不能导致财务数据初始化或覆盖。
4. 首版使用 Kotlin/Compose 已有运行环境，提供真正可玩的 2048 和可重复的规则测试。Python/WebView 等运行时只在有实际插件需要时增加适配器，不预装未使用的运行时。
5. 触摸滑动、方向按钮、重开确认、胜利后继续、失败重开及存档应形成闭环。游戏页面的返回与 App 锁继续服从应用宿主生命周期。
