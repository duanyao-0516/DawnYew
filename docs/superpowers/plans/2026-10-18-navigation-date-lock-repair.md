# 页面切换、日期选择与截图返回修复计划

> 执行：subagent-driven-development；按独立文件分工，根线程集成及设备操作，最终交叉 review。

**Goal:** 页面切换和前后期切换不整页闪烁；受控截图选择返回不重复解锁；明细与统计职责清晰，支持任意起止日期。

**Architecture:** 查询状态由 presentation 协调，缓存仅在账务输入一致时复用；新查询保留上次完整内容，展示范围与数据绑定，取消和序号隔离迟到结果。日期逻辑共享于 core，两页复用同一个日期入口。系统选择器仅通过进程内、限时、单次 lease 恢复会话。

**Tech Stack:** Kotlin Multiplatform、Compose Material 3、kotlinx.datetime、既有 Android ActivityResult 和 SessionCoordinator。

## 设计约定

- 明细专注查找、筛选和处理账单，保留截图记账与记一笔；移除和统计重复的大汇总卡。
- 统计专注汇总、图表和月预算；通过图表下钻明细，各页面保持独立查看范围。
- 日期入口始终可见：点击日期弹出本月、上月、今年、近 7 天、近 30 天和自定义选项；自定义使用 Material 日期范围弹窗，支持日历和键盘输入，取消不应用。
- 整月前后跳自然月，整年前后跳自然年，自定义范围前后平移同样的闭区间天数。日期边界为 0001-01-01 至 9999-12-31；采用 UTC 日期转换，避免设备时区导致偏移。
- 取消整页统计 Loading 替换和页面/日期切换转圈。已有数据刷新时保留内容与原范围提示，临时禁止旧内容下钻/修改；新结果原子替换。首次读取和真实错误仍有文字状态及重试。
- 受控文件选择期间隐藏账本。有效的截图 ActivityResult（包括取消）可恢复原会话；在应用界面直接退后台、设备熄屏/锁定、过期 lease、新入口或进程丢失不能获得此豁免。系统 picker 覆盖期间的 Home → 回到 picker 路径不能被本应用可靠观察，匹配结果仍可能在 120 秒会话窗口内恢复；不为观察其他应用引入使用情况或无障碍权限。备份敏感操作继续原有认证要求。

## 实施与验证

- [x] `shared/core/.../DateRanges.kt` 及 `DateRangeTest.kt`：先写闰年、跨年、闭区间、上下界、UTC 转换及预设测试，再实现纯日期函数。
- [x] `shared/presentation/.../EntryPagingCoordinator.kt`、`StatisticsQueryCoordinator.kt`、`AppController.kt` 及查询回归测试：先重现清空内容/偏好失效，再实现稳定状态、缓存和竞态隔离；偏好写入保持串行但不显示用户保存进度和成功提示。
- [x] `platform/android/.../SessionCoordinator.kt`、`androidApp/.../MainActivity.kt` 及 session 测试：先重现 picker 回来丢授权，再实现受控返回与撤销边界。
- [x] `shared/ui/.../DateRangeSelector.kt`、`DawnYewApp.kt`、`StatisticsScreen.kt`：复用日期选择，拆清页面职责，接入稳定查询状态，保留滚动与错误状态。
- [x] 执行 `./dev.sh test`、`./dev.sh debug`、`./dev.sh release`；交叉审查数据范围一致性、缓存失效和应用锁安全边界。
- [x] 覆盖安装到已连接 PKM110，保留用户数据；用户完成系统认证并自行验证后明确要求不再继续验证，按其指示停止追加真机验证。已执行与未执行范围见验收记录。

日期控件依据：[Android Compose date pickers](https://developer.android.com/develop/ui/compose/components/datepickers)。与既有 UI 规范冲突的部分以本轮用户反馈及本设计为准。

## 执行结果

最终主机测试 254 项通过，Debug/Release/JNI/Lint 构建通过。独立交叉 review 发现的旧范围提示、空态表述、数据行禁用和 picker 返回查询恢复已修复。用户于本轮明确表示已自行验证，无需继续验证，因此停止追加真机验证；最终补丁覆盖安装保留数据。详见 `docs/implementation/2026-10-18-navigation-date-lock-verification.md`。
