# 最终 UI 独立源码审查

日期：2026-10-24。审查人：final_ui_review（独立于 UI 实施者）。

## 范围与证据等级

审查当前工作树 `p/duanyao.0516/codex/dawnyew-android-app` 全部未提交的 `shared/ui/**`，并追踪 `shared/presentation` 的导航、草稿、保存回调与查询接口及必要的 core 参数契约。依据 `docs/superpowers/specs/2026-10-24-offline-finance-redesign.md`，读取用户全局 AGENTS 规则和工程变更审查约束。

本报告为实际源码与 diff 审查，并包含对 root 采集图片的独立视觉复核；未运行 Gradle、ADB、安装、UI 自动化或设备操作。编译与设备结果由 root 单独提供。没有把历史审查报告作为代码正确性的证明。

## 问题列表

### UI-R1 / P2 / 已关闭：周期类型切换留下转账不允许的隐藏预算标记

- 位置：`shared/ui/src/commonMain/kotlin/com/dawnyew/ui/RecurringScreens.kt:78`、`:83`、`:98`。
- 触发：新增或编辑支出周期，打开“不计入预算”，将类型改成“转账”，选好两个账户后保存。
- 证据：切类型只清 `category`；`excluded` 仍为 true。转账界面隐藏此开关，构造 `RecurringPlan` 仍传 `excluded`。`FinanceValidation.kt:20` 明确要求转账模板 `!p.excludedFromBudget`，因此保存失败。
- 影响：完整填写的转账周期不能保存，用户无法在当前类型下看见或修正失败字段。
- 修复方向：切换类型时归一化仅支出适用的字段，构造转账模板时也强制清除该标记；回归支出开启排除后切转账的路径。
- 复核：最终 `RecurringScreens.kt:78-84` 切到非支出时清除 `excluded`；命令构造只在支出时传预算排除，转账同时清除 category/account/project/merchant。原触发链不再产生非法模板，源码复核关闭。

### UI-R2 / P2 / 已关闭：下钻流水新增默认账本丢失来源范围

- 位置：`shared/ui/src/commonMain/kotlin/com/dawnyew/ui/DawnYewApp.kt:39`、`:146`；`shared/presentation/src/commonMain/kotlin/com/dawnyew/presentation/StatisticsNavigation.kt:73`。
- 触发：主页选账本 A，统计独立选账本 B，从分类或月份进入 B 的流水，再点击记一笔，输入金额并继续。
- 证据：`FinanceController.showActivities` 只保存 `finance.activityScope`；流水、金额的 `UiNavigation.scope` 回退 `homeScope`。UI 据此得到 `selectedLedger=A`，金额下一步将 A 传给 `beginEditor`，转账也以相同账本默认值打开。
- 影响：在 B 的查询结果中录入新交易却默认落到 A，且保存后回到 B 筛选时新记录不可见。
- 修复方向：新增入口捕获来源 `ActivityScope` 的单账本并贯穿金额到普通账单/转账；多账本来源保留显式选账本语义。至少覆盖统计与账户/维度下钻后的新增。
- 复核：`FinanceNavigation.sourceLedgerIds` 由 `showActivities` 捕获完整来源账本集合，`open` 继承、返回栈恢复、切 tab 清理。最终 `DawnYewApp.kt:52-53` 统一派生 `financeLedgerIds/selectedLedger`，同一集合用于 snapshot/profile 请求、结果核对及重试，避免资金来源页混入主页账本投影。金额下一步继续使用捕获的账本；`TransferEditor` 在非唯一来源时保持未选账本，要求显式选择。`FinanceControllerTest.activityCreationRetainsSourceLedgerAcrossAmountAndTransferRoutes` 覆盖跨主页/统计账本的金额、转账、草稿和 tab 清理。原触发链源码及现有测试结果复核关闭。

### UI-R3 / P2 / 已关闭：就地新建维度后草稿自动选择被保存锁丢弃

- 位置：`shared/ui/src/commonMain/kotlin/com/dawnyew/ui/EntryScreens.kt:91`；`shared/ui/src/commonMain/kotlin/com/dawnyew/ui/DimensionScreens.kt:36`；`shared/presentation/src/commonMain/kotlin/com/dawnyew/presentation/AppController.kt:148`、`:313`、`:328`。
- 触发：记账表单点击“新增项目”“新增商家”或“新增标签”，填名称并保存，再直接保存账单。
- 证据：DimensionEditor 的保存成功回调先执行 `onCreated(id)`，UI 再调用 `changeEditor` 选择新对象；此时 `performOperation` 尚未进入 finally，`busy=true`，`changeEditor` 在第148行直接返回。
- 影响：维度实体创建成功，但账单保留原关联或空关联，违背该回调意图，后续账单会漏掉新维度。
- 修复方向：提供成功提交后安全关联草稿的时序，兼顾最新数据集、operationId 和授权代际；不要全局取消 busy 输入门。
- 复核：`EntryForm` 现在仅将 `SaveDimension` 交给 `AppController.createEditorDimension`。该入口仍走 `perform` 单写门，提交成功后使用 repository 最新 state，检查 transient generation、dataset 和精确 draft operationId/ledger，再直接更新相应维度与 dirty/OCR 确认状态；没有放开普通 busy 输入。`EditorDimensionTest` 覆盖项目/商家/标签全部关联、保存中普通输入仍阻断、失败不关联和草稿撤销不回填。原触发链源码及现有测试结果复核关闭。

## 已检查的链路

- 五页均有实际路由；账户、借还、周期、存钱、预算、维度、回收站、文件页可达。现有受限路由统一只有标题、返回及空正文；UI 未加入 HTTP/登录/支付副作用。
- 普通转账 from/to、借还 settlement、存钱明确端点与预算反向转移的参数方向，与 core 消费方匹配；未发现同等级参数反转问题。
- 新财务表单 `remember` 固定 operationId，AppController 同步设置 busy 后执行写入，普通重复点击会被单写门拒绝；表单支持脏数据放弃确认，保存中不能关闭。
- 月统计、年统计使用独立结果槽；分类以原始支出形成非负环图，退款独立展示；下钻直接使用实际 snapshot.scope。
- 列表 family+id 身份、分类下钻不混入转账、回收站关联业务命令、归档历史保留与新增校验均追踪到实现。
- 列表与表单基本具备滚动/IME 避让；200% 字体、横屏和实际触控可达性仍需设备验证，不能由源码替代。

## 覆盖核对项

方案第19行及先前 UI gap audit 指定的拆分、手续费入口已经补充在非退款记账表单。两者经 `finance.open` 进入既有空页分支，标题、返回、空正文符合约束；返回栈保存记账页导航，controller 中的草稿保留，回到表单仍由原 operationId 管理。

## 修复后新增投影复核

- 流水范围合计和每天合计由独立 `activitySummary` 槽读取，列表分页、首页日历不会互相覆盖；源查询与 `readActivityPage` 共用 `activityFilter`，SQL 对完整筛选范围计算四类金额和笔数，再按日期聚合，没有把当前50条分页相加冒充全量。
- `ActivitiesPage` 将汇总绑定 scope/datasetId/contentVersion；加载或错误时金额为未知状态并提供重试，不显示伪造零值。旧范围的行被过滤，旧内容版本行不可操作。收入、原始支出、退款、转账分别显示，未将转账算入普通收入/支出。
- 新汇总在修改后刷新、reset 时清理；`FinanceControllerTest.activitySummaryIsAnIndependentFullScopeProjection` 覆盖完整汇总与单页条数不同及首页查询独立性。
- 原审查人只读取 root 已生成的 `.local/finance-review-ui-green-20261024.log` 和 `shared/presentation/build/test-results/jvmTest/TEST-*.xml`：日志为 `BUILD SUCCESSFUL`，9个 suite 合计110项，失败0、错误0、跳过0。此为已有运行证据复核，未由本审查人重新执行 Gradle。

## 采集图片的视觉复核

逐张实看 `.local/finance-device-ui-pass2` 全部10张浅色图，以及 `.local/finance-device-ui-pass1` 的01至05五页、08存钱、09预算、10账单表单八张暗色图；参考 `.local/research/bohe-20261024/薄荷记账-完整功能与UI分析.md` 的页面骨架、金额层级、颜色与样例口径。以下仅判断截图所呈现的内容，不从静态图片推导触控、滚动、提交、持久化或返回路径已经验证。

- 浅色五页：标题、浅色圆角卡片、收入/支出颜色、主要金额及底部选中态清晰，未见主要金额/标题的硬截断。账单表单当前可见部分的金额、类型、分类、账户、备注排列正常；日期及保存动作在画面外，未以该图确认可触达性。
- 暗色首页、账户、我的、预算与账单表单可见文字和卡片对比正常。暗色旧图03统计仍选中账户、04更多仍选中统计；浅色第二批对应页正确，因此记录为旧采集状态差异，不能仅凭旧图片认定最终代码仍有选中态缺陷。
- 图示收入100.00、支出16.34、结余83.66、净资产1084.66、资产1092.66、负债8.00、预算300.00/剩余283.66、存钱5.00/目标100.00之间未见可见算术矛盾。统计分类100%与单一餐饮分类一致。数据绑定真实查询的结论来自前述源码追踪；这些样例图片本身不能证明任意数据集均正确。
- 两批名为06账户详情和08存钱详情的图片只呈现 Activity 根列表，没有 AlertDialog。root 已确认旧采集使用 Activity.decorView，遗漏独立 Dialog Window。因此不能将它们标作详情/弹窗已视觉通过，也不把漏窗误报为运行时没有详情。

### UI-V1 / P3 / 已关闭：存钱场景卡片断字及通用减号图标

- 证据：两批08图中四个场景均呈现三字加一字的换行，图标都是减号圆圈；文字完整，未造成核心功能阻断。
- 修复复核：`SavingsScreens.kt:21` 使用竖排卡片，`FinanceComponents.kt:73-104` 让标题使用完整卡宽，保留大字体单列且无强制截断；`AppGlyph.kt:76-101` 为四个场景提供独立旗帜、星形、靶心、攒钱绘制。
- 最终补图复核：已实看 `.local/finance-device-ui-verified/14-存钱列表.png`；四个场景均为图标上置、四字标题单行、说明完整，四种图形可区分，未见截断或遮挡。源码与最终视觉均关闭。

### UI-V2 / P3 / 已关闭：我的选中底色与其他四页不一致

- 证据：两批05图的我的选中背景为灰蓝，其他四页为浅青/深青。
- 修复复核：`DebugNavigationItem.kt:33` 显式采用 `primaryContainer`，与 `DawnYewApp.kt:117` 的其他四页一致；点击与长按手势所有者未改变。
- 最终图片复核：`.local/finance-device-ui-final/05-我的.png` 的选中背景已为浅青色，与同批01至04一致。源码与最终视觉均关闭。

### UI-V3 / P3 / 已关闭：虚拟应收账户显示UUID尾段

- 证据：浅色06底层账户列表显示“应收·8f-97ab-26b7bffb7acc”，无法从名称辨识来源单据。
- 修复复核：`FinanceForms.kt:55-56` 只在展示层按 virtualAccountId 查找 Obligation.title；`AccountsScreen.kt:37/49` 列表与详情、`TransactionsScreen.kt:27/105/164` 流水方向/账户筛选/详情均使用该映射。普通账户名仍直接展示；持久化名称和标识未变更。普通 AccountChoice 排除系统虚拟账户，不会在选择器继续暴露该系统名称。
- 最终图片复核：同批06图底层应收账户显示“UI测试·LEND”，11流水中借出/还款方向显示“UI测试·现金 → UI测试·LEND/BORROW”，未再露出UUID尾段。源码与最终视觉均关闭。

三项均为可见的低优先级外观问题，没有升级为P2。最终08图的弹窗遮挡场景卡片，后补14图提供无遮挡列表，现UI-V1、UI-V2、UI-V3均已完成源码与视觉复核关闭。

### 最终合成窗口图片复核

已逐张实看 `.local/finance-device-ui-final` 的01至13全部图片。

- 01至05五页选中态与页面一致；浅色卡片、标题、金额、导航未见新增硬截断。07周期、09预算、10账单表单可见区域延续前批的正常排版。
- 06账户详情完整显示名称、余额1062.66、期初1000.00及日期、查看账户流水、编辑/归档/删除与关闭。08存钱详情完整显示计划名、已存5.00/目标100.00、资金影响说明、存入/取出/新增愿望、编辑/归档、存取记录与关闭。两张图已经补足原来遗漏 Dialog Window 的证据缺口；不据静态按钮绘制判断实际可点击行为。
- 11流水显示9笔、收入100.00、支出16.34、退款0.00、转账42.00，四类金额区分清楚；2026-10-24的日汇总与该样例同日的范围汇总一致，方向名称可识别。未从仅显示前几行的图片推导分页或所有数据均已验证。
- 12受限空页的实际标题为“拆分”，只显示返回、标题和空正文，符合该入口约束。这不是会员截图，不能记录为会员页面视觉通过；会员等其他入口仍以源码共同空页分支审查为证据。
- 13深色首页文字、卡片、图表和选中态可读；画面包含“已保存”提示覆盖图表下方，属于该次捕获的瞬时状态。未将静态截图用于判断提示出现/消失时序。
- 最终13图未发现新增P0/P1/P2。200%字体、横屏、触控、页面往返、业务提交及设备生命周期不属于本组图片能够证明的范围。

### 最终补图与视觉结论

独立实看 `.local/finance-device-ui-verified/14-存钱列表.png` 和 `15-会员空页.png`。前者补齐UI-V1的无遮挡排版证据；后者明确显示“会员”标题、返回及空正文，未出现登录、支付、推荐或说明等正文。会员空页的视觉证据现已补足。截图无法证明没有网络副作用，该结论仍来自前述源码链路审查。

截至此轮，三项P3视觉问题均已关闭；已审图片中没有新增可证实的P0/P1/P2或待修视觉缺陷。本审查人没有执行Gradle、ADB或重新运行设备测试，完整测试、签名和生命周期回归证据由root的交付报告负责。

## 结论

首轮发现的 UI-R1、UI-R2、UI-R3 及视觉复核的 UI-V1、UI-V2、UI-V3 已逐项修复并由原审查人复核关闭。最终实际 UI/presentation 源码与上述图片复核未发现未解决的 P0/P1/P2。源码与所列图片的视觉审查通过；完整编译、设备交互、200%字体与横屏验证仍以 root 的独立报告为准。
