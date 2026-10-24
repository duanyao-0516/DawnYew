# 普通二级列表与搜索筛选实现计划

> **For agentic workers:** 使用当前 subagent-driven-development 分工；本文件作者负责实现，visual/root 负责非作者交叉审查，root 独占编译与设备验证。

**Goal:** 让普通二级配置页采用薄荷备份/设置式分组列表，搜索筛选与真实结果同页，同时保留所有离线能力和确认链。

**Architecture:** SettingsSection/SettingItem 统一普通配置密度；ActivityQueryDraft 是唯一待应用筛选对象，ActivityQueryControls 只有一个当前编辑种类。已应用 ActivityScope 始终来自 FinanceController，分页/汇总请求与版本闸门不变。

**Tech Stack:** Kotlin Multiplatform / Jetpack Compose / 既有离线 FinanceController。

## 参考与取舍

已逐图及 XML 核对 `.local/research/bohe-20261024/round2/p460-backup-secondary`、`p357-settings`、`p083-home-search`、`p067-transaction-filter-more`：备份全宽白底平直双行列表，左右16dp，约59dp行步；设置按内容分组、组内分隔；搜索输入和多行胶囊保留在结果页上方。

考虑过保留大型 AlertDialog 只改圆角（缺少结果上下文）和每页重新写定制列表（重复密度规则）。采用共享分组列表+结果内胶囊，每次仅打开一项筛选器。模型无排序/图片谓词，不制造相应空入口。图表、可拖管理、金融明细和业务表单不全局替换。

## 文件边界

- 新增 `shared/ui/src/commonMain/kotlin/com/dawnyew/ui/GroupedSettings.kt`：组容器、平直行、说明块及窄选项弹层。
- 修改 `ProfileScreen.kt` 的 Backup/Security/LocalSettings/LocalHelp/Reminder；主 Profile 度量与图标不变。
- 修改 `FileAndRecycleScreens.kt` 的普通导入导出/组件说明，回收站金融业务不动；`MoreScreen.kt` 仅 Toolbox。
- 新增 `ActivityQueryControls.kt` 与 presentation `ActivityQueryDraft.kt`；Transactions 的结果页接控件，删除旧巨型 ActivityFilterDialog 和无调用 AmountEntryPage。
- root 单独修改首页搜索直接打开结果，清理旧 FilterDialog，并以 updateActivities 替代筛选 push 回栈。

## 实施步骤

- [x] 定义 `ActivityQueryDraft(scope, start, end, minimum, maximum)`，`validatedScope()` 只校验并返回复制对象，重置保留 deleted/includeArchived 查询域；添加空日期、零/非零金额范围、取消不改原 scope、非可见维度保留及清理依赖筛选的单元测试，由 root 统一运行（本步骤仅表示用例已编写）。
- [x] 实现 `SettingsSection` 和 `SettingItem`；单行最小52dp、双行自然增高、组内分隔、箭头仅可导航行；开关仅真实配置。
- [x] 替换普通页：备份五条原安全回调及口令流程逐项保留，设置主题/金额/默认账本/默认账户通过小选择器，继续记账开关原 preference 命令不变。
- [x] 实现单份 pending draft+单 editor enum：输入/日期/金额/类型/账本/分类/账户/项目/商家/标签/周几/入账/预算/对账；Apply 验证成功才回调，Cancel 回到 applied，Reset 只改变 pending 并需 Apply；选择账本清除依赖维度和未分配标志，避免跨账本脏条件。
- [x] 结果页原 summary、分页、请求/数据集/版本闸门保留；搜索输入提交才应用，过滤菜单在同一页展开。清除所有已证实无调用旧 UI。
- [ ] 交 root/visual 非作者源码复审；root 编译/单元测试/实机覆盖搜索→过滤→取消→应用→清空→返回、设置选项、备份安全确认取消及深色/大字体。

## 验收约束

本作者不运行 Gradle/ADB、不以源码审通过宣称设备一致。最终需真实截图对照上述原版，危险操作只核确认/取消，测试包隔离，不清除用户数据。新控件采用一个稳定 owner，不新增业务数据副本、登录/会员/网络能力。
