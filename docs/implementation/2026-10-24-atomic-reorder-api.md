# 原子拖拽排序契约

新增用户要求所有顺序交互使用长按拖拽并在落位后即时映射主页面。取消账本、分类、项目/商家/标签中的“置顶”及面板的上下移动替代操作。此文定义领域写入边界；拖拽组件及界面消费由 UI 作者实施。

四条接口经数据作者与界面作者确认：

```kotlin
Command.ReorderLedgers(orderedIds:List<String>, expectedVersion:Long)
Command.ReorderCategories(ledgerId:String, direction:Direction, orderedIds:List<String>, expectedVersion:Long)
FinanceAction.ReorderDimensions(ledgerId:String, kind:DimensionKind, orderedIds:List<String>, expectedVersion:Long)
FinanceAction.ReorderAccounts(ledgerId:String, accountType:AccountType, orderedIds:List<String>, expectedVersion:Long)
```

每条提交的 orderedIds 必须覆盖当前完整作用域，无遗漏、重复、未知或跨账本/类型成员。完整作用域包含隐藏、归档、停用和业务单据专属账户。UI 只展示部分成员时，将可见成员的新排列合并回完整排列的原槽，隐藏成员保持槽位。所有账本视图中账户按类型与账本分别成组，不跨账本拖动。

`expectedVersion` 必须是开始拖动时的内容版本；落位时不能换成更新后的版本。现有 AppController.perform 未向 execute 传 expectedVersion，因此版本由新命令自身校验。即使集合成员未变，也不能用旧排序覆盖期间产生的新排序。一条拖拽落位对应一个 operationId、一个写事务、一条 receipt 和一次 contentVersion 增长，不拆成多个 Save 命令。重复相同 operationId 与相同命令幂等；同 operationId 不同排序冲突。

排序只修改目标成员的 sortOrder（按完整排列赋0..n-1）和 revision，各加1。其他资金、类型、所属、开账日期、归档/隐藏/启用状态及业务关联不改。引用中的虚拟账户允许改变显示顺序；原有直接记账/转账/资金编辑保护继续有效。已归档账本内的分类/维度/资金账户仍不可修改；账本自身的全体顺序包含归档账本。

存储沿用现有 sortOrder/payload/position 字段，无 schema 或备份版本变化。Room重开保持顺序数据；备份的 canonical records 按ID排序，因此恢复后的所有 UI 选择列表必须按 sortOrder/id 排列，不依赖原始 List 物理次序。

验证包括四类作用域与版本/幂等/重复/缺失/未知/跨作用域/归档约束、虚拟账户资金和引用保持、实际Room重开和备份恢复排序保持，以及UI拖起版本冻结、落位即时应用、失败回滚和跨类型/账本拖动拒绝。root 是唯一 Gradle/ADB 执行者。

序列化边界验证修正：账户类型参数命名为 accountType，避免与现有 sealed JSON discriminator `type` 冲突；不修改全局 discriminator，因此旧操作摘要与兼容性保持原约定。真实 ReorderTest 已复现并覆盖此序列化路径。

## 交叉审阅记录

数据作者只读审阅了 UI 消费：共享 `LongPressReorderableList` 在拖起时冻结 onCommit 和 sourceVersion；内容变化/忙状态会取消拖拽；落位只发一次提交；失败后恢复已提交排列。账本/分类提交完整成员；维度将未显示归档成员合并回原槽；资金账户仅同账本同类型内拖动，隐藏成员按原槽合并。原有“置顶”入口已由 UI 作者移除。实际拖动动画、自动滚动和落位触感仍需 root 设备验证。

另对 root 变更作只读审阅：首页统计详情保存 sourceStatisticsScope，并在 root 构造monthScope时优先使用来源范围；下钻返回frame保留来源详情，原统计Tab范围不被覆盖。原版资源记录包含39逻辑映射、34唯一PNG，读取本地源文件和打包目标后SHA均与JSON一致；Android actual仅走本地painterResource，单色图按内容色着色、彩图保持原色，没有增加联网路径。以上是源码/文件一致性结论，不替代设备视觉验收。
