# 首页完整区间、月业务与日历纠正

## 问题证据与决定

旧 `FinanceHomePage` 已收到完整 `StatisticsSnapshot.trend`，却调用 `chartDayWindow` 截为今天附近七天；2026-10-24 只显示 10.21–10.27，10.1 的金额仍计入标题但不在图中。根页面读取首页和统计来源时又强制 `monthRange(startDate)`，且首页只有月份输入，任意自定义区间都会被扩大为起始月。

本轮不在图表内重算财务数据。沿用实际 `Statistics.calculate` / Room `readStatistics` 的精确查询和桶：含首尾的 91 天以内按日，其余按月。完整自然月保留全部 28–31 个日期，包括没有记录的零值日和已录入的未来记录；仅稀疏绘制轴文字。按月桶的点击通过原范围交集下钻，首末不完整月份不会越界。

## 单一所有权和范围契约

| 内容 | 范围来源 | 点击/返回 |
| --- | --- | --- |
| 首页金额、支出趋势、收支趋势 | `homeStatisticsScope(navigation.homeScope)`；不改起止日 | `homePeriodDrillScope` 保留日期/账本，限制普通收支退款，排除资金转账 |
| 最近交易、未入账 | 所选首页区间 | 同区间的完整资金流水；未入账另限定 recorded=false |
| 月预算概览、分类预算追踪、分类月对比 | `homeMonthlyScope`，所选区间结束日所在自然月 | 卡上明确年月与“按月”；独立预算统计结果 |
| 日历 | `homeCalendarScope`，独立当前日历月 | 初始跟随区间结束月；切日历月不写首页区间、不移动预算月；日期下钻使用日历账本与该日 |
| 从首页进入统计详情 | `FinanceNavigation.sourceStatisticsScope` 精确来源 | 非年度详情使用通用日期区间选择器，返回保留原来源；年度业务仍明确全年 |

根页面持有首页区间和独立日历月份；presentation 派生月范围。Home 不拥有第二套隐式月份状态。首页的两张趋势图共用 `homeTrendPoints`，旧七日裁剪函数与无调用的旧 `HomePage`/旧图表已移除。

各展示槽仍校验 request/scope、datasetId、contentVersion、loading、error，改变区间时不显示上一范围的数据。`FinanceController.loadHome` 接收独立 calendarQuery，最近交易/未入账不被日历翻月影响。预算统计在授权恢复时仅恢复已经请求的槽；失败或仍在读取时替换旧授权请求，成功缓存继续复用。

## 日期操作和持久化

首页标题/独立日期入口保留紧凑月份输入，并提供本月、上月、今年、近7天、近30天及自定义起止日期。自定义复用既有 `DateRangeDialog`；只有显式确认才更新首页区间。

`lastHomeRange` 是唯一首页范围偏好，使用 `start/end` 单值，避免两次保存产生混合起止日。`encodeDateRangePreference`/`decodeDateRangePreference` 和 `validatePreference` 共用严格日期校验，命令、整库、备份校验保持一致。仅首页显式日期选择写此键；统计/明细选择继续使用既有 `lastPeriod`，不覆盖首页区间。首次加载：首页优先有效新键，缺失/无效时回退旧月份；统计/明细保留旧月份规则。旧备份读取时不注入新键，不改变原摘要。

## 日历布局及金额

参考实际 `round2/p063-home-calendar-month-picker-return.png` 与 `home_calendar_day_item.xml`：日期行固定，下面金额位置不挤日期。本轮所有日期都保留相同的第二行高度；无记录日期留空，有记录时显示 `income + refunds - expense`。transferAmount 不加入净收支。正负分别用收入/支出颜色，零净额照实显示 0.00；完整日期和净额也进入可访问描述。超大净额求和失败显示明确状态，不伪造零。

## 验证清单与实际状态

新增/调整自动测试（本 agent 未执行 Gradle 或 ADB，待 root 统一运行）：

- `DateRangeTest`：单值编码、反向/非法/多段拒绝、旧月份兼容。
- `ControllerStateTest`：首页区间原子持久、统计选择不覆盖、重建恢复、无效新键回退、下钻返回保留来源。
- `FinanceControllerTest`：全局 09.15–10.10、结束月10月、独立8月日历查询隔离；月点只下钻10.01–10.10，排除转账。
- Room `EntryQueryTest`：实际数据库读取10.1/10.31两端、全31日、跨月09.15–10.10内外边界、长区间月聚合与领域参考一致。
- `FinancePanelPresentationTest`：全31点不裁掉首尾，月点完整标记与交集下钻，日历正/负/零净额及转账排除。
- 既有 `HomeScopeDeviceTest` 的月份输入与“确定”路径保留；旧 APK 已由 root 证明范围错误 RED，当前整合版设备结果待 root。

源码搜索确认不再有 `chartDayWindow` 或无调用旧首页。`git diff --check` 已通过。本文件不把源码审查当作编译或真机通过。

## 整合回归与主页面导航

整合时三条 `QueryLoadingTest` 发现授权恢复多读一次（原预期 4 次，实际 5 次）。原因是新的预算槽在没有任何 UI 请求时仍被 `resumeVisibleQueries` 无条件创建且强刷。修复收敛到 `resumeBudgetQuery`：只恢复已有槽，只有 pending/error 需要 force。原三条调用次数断言保留；新增跨月预算失败/在途恢复、成功缓存不重复读取及旧错误不得覆盖新结果的测试。

「更多」预算摘要使用 `homeMonthlyScope` 对应的预算结果，匹配 scope/dataset/version/loading/error，并明确显示结束月。不能再拿 09.15–10.10 区间支出减去 9 月或 10 月预算。

五个主 tab 的导航统一由 `FinanceController.selectTab` 接管，清除上一二级页来源与返回栈。「账户追踪」进入主账户页后，账本选择使用当前主页面范围；带 objectId 的账户详情仍保留来源账本与原返回页面。首页分类统计等二级统计仍携带精确首页范围，进入主统计 tab 则恢复主统计自己的范围。

新增独立 `HomeRangeDeviceTest`，不通过 Controller 代替受测点击：

- 全月图可访问绘图数据必须有 31 个日桶和 10.1/10.31 金额，触摸图两端验证实际流水范围。
- 原日期弹窗切换文字输入，应用 09.15–10.10，验证 26 个图点与下钻账单 ID 集合。
- 日历同周日期覆盖负净额、正净额、零与空白；加入真实转账验证不计净额。对测试进程自身绘图读取日期数字像素位置，另保留 PNG 供视觉复核。
- 真实账户追踪入口、账户页账本弹窗切换，验证请求账本、返回快照及可见账户。

测试图片输出到验证包 external files 的 `home-range-verification`。本 agent 未运行 Gradle/ADB；源码交叉审查和 `git diff --check` 与设备执行结果分开记录，最终执行由 root 统一。
