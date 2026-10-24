# DawnYew 财务改版决策与交叉审阅关闭记录

日期：2026-10-24。用户授权自主决策，不逐项提问；先多 agent 方案审阅，再开发与编译，最终再次交叉审阅。设计复核已全部通过，当前进入实施；代码审查和运行验证尚未完成。

## 设计初审处理

三位独立审查者分别提交 domain（8项）、UI（7项）、platform（3项）报告，共18个问题条目，若干指向相同根因；不是18个已运行代码缺陷。以下采用规则均已写入 spec 与 plan，三位原审查者已逐项复核，结论见各自设计审阅文档。

| ID | 采用决策与验证要求 | 当前状态 |
| --- | --- | --- |
| D1 | 单据虚拟账户一对一专属且零初始，普通账单/转账/周期/存钱不可绑定，只有所属单据命令可写；未入账本金/结算不增加可结算余额。候选校验和所有入口同规则 | CLOSED-DESIGN（原审复核通过） |
| D2 | 退款预算资格动态读父支出，退款自身排除字段固定false；按退款日期计入月份，budgetUsed最后钳制0；账户/recorded独立，不随预算改写 | CLOSED-DESIGN（原审复核通过） |
| D3 / UI-R3 | 自主选择支持单端存钱转账：缺失侧明确是未纳管外部资金；from扣/to加，净资产按计入资产两端差额，存取进度独立；双空不建转账。此为离线实现决策，不能称参考实测结果 | CLOSED-DESIGN（原审复核通过） |
| D4 | 愿望完成唯一事实为fulfilledMovementId，进度只计movement；完成/取消与转账原子；完成后先取消才能改金额/删除；无目标随手攒钱用null，其余正目标，计划余额不得负 | CLOSED-DESIGN（原审复核通过） |
| D5 | allocation为当前额度，reallocation为不可变历史不重复累加；撤回用反向转移；SetBudget(null)明确删除整组子预算/历史，不动流水；分类/子额度引用保护 | CLOSED-DESIGN（原审复核通过） |
| D6 | 已有期次后冻结锚点/频率，修改只影响未来模板；跳过消耗期次、失败不推进、降低总次不得小于已处理；批内账单创建身份各自唯一 | CLOSED-DESIGN（原审复核通过） |
| D7 / PLATFORM-01 | 统一前台ready含无锁用户+repo ready，单飞/单写门，用户busy/pending/picker期间暂停；每笔复核session/database epoch；系统只确认自己的回执；失败走原恢复链，100期后显式继续 | CLOSED-DESIGN（原审复核通过） |
| D8 / PLATFORM-03 | 私有磁盘逐记录暂存，禁止全根DOM；来源version/counts/digest保留，expectedCandidateCounts单独补0；大文件/乱序/空间/取消/加密生产链测试；DWYBAK封装v1不变 | CLOSED-DESIGN（原审复核通过） |
| UI-R1 | ActivityScope补categoryIds和完整QueryScope映射，筛选在数据库层；identity包含ENTRY/TRANSFER family，固定三段游标排序 | CLOSED-DESIGN（原审复核通过） |
| UI-R2 | Account加入openingAt/archived，隐藏与归档分离；早于期初绑定拒绝，有引用后期初时间不可改，初始金额修改明确重算；虚拟账户禁改 | CLOSED-DESIGN（原审复核通过） |
| UI-R4 | 月分类/日趋势与年月度收支独立请求与下钻来源；环图原始支出非负份额，退款/净额另列；不同月份/仅退款月测试 | CLOSED-DESIGN（原审复核通过） |
| UI-R5 | 受限目标只有标题/返回，具体内容留空；原因只记开发矩阵，不出现登录/会员权益表单或假成功 | CLOSED-DESIGN（原审复核通过） |
| UI-R6 | Widget和预览固定静态动作入口，无金额/账本名/摘要；显式本地Intent仍须认证，不存敏感财务数据 | CLOSED-DESIGN（原审复核通过） |
| UI-R7 | Obligation可空dueDate；设日期才判断到期，未设只显示未结清；无后台通知 | CLOSED-DESIGN（原审复核通过） |
| PLATFORM-02 | CSV v1固定版本/表头/双引号及可逆文本前缀、来源身份；逻辑记录与物理错误行分离；一次10000条/32MiB，10001提前拒绝、绝不截断；既有XLSX事实字段不偷换 | CLOSED-DESIGN（原审复核通过） |

## 跨问题自主决策

- 本轮继续在已有个人功能分支/当前工作树开发：保留本地研究和已配置的离线工具链以及三个既有变更，避免另开工作树丢失输入。目录分区、独立review和统一构建减少相互覆盖；不推送远端。
- 参考收费项/在线项留空；原 DawnYew 本地备份/应用锁/OCR继续保留。CSV只交换普通支出收入，不假称完整财务备份；快捷Widget不展示财务数据。
- 不改变旧 Entry/Category/九类线格式；资金实体有类型并进入相同事务/代际/备份。禁止独立preferences账本或UI假余额。
- 日期“不能未来”约束用于新手工写入，不把当前时钟用于拒绝合法旧备份；周期未来期只显示待处理。
- 领域/备份/资金测试先写失败用例再实现；无意义的视觉镜像测试不添加。主agent唯一运行构建与设备，避免多Gradle并发写输出。

## 下一门槛

原设计审查者已复核全部对应条目，无未解决设计P0/P1/P2，Gate0通过；按计划固定API并实施。最终代码review和编译/设备证据另列，不把设计通过当实现通过。

## 实施阶段追加决定与证据

- 待入账结算不预占额度；余额只扣已入账结算。多笔待入账可并存，转入账时重新检查剩余本金，超额整体拒绝。对应 core 回归测试通过。
- 预算限额/分类额度调整本身不改变活动或统计金额，因此不使查询缓存失效；Details、资金事实和所属对象变化仍失效。保留既有缓存回归约定。
- Kotlin 2.4.21 增量编译出现 classpath diff 内部 ActivityKind DirtyData 异常；单次 `-Pkotlin.incremental=false` 可正常编译并跑到真实失败用例，后续普通增量构建正常。未清缓存或跳过测试；日志 `.local/finance-platform-backup-red-20261024.log` 与 `.local/finance-storage-schedule-red-20261024.log`。
- 手机现有 DawnYew 为正式证书，与 Debug 不同。使用 `-PdawnyewIsolatedDeviceTest=true` 给 Debug 添加 `.validation` 应用标识后做设备测试，避免卸载、覆盖或迁移用户现有账本。Release 应用标识/签名约定不变。
- ROOT-UI-01：账户在同页面名下显示对象详情时须退出 primary 状态，显示返回；UI agent 已修 `primary && financeNavigation.id == null`，待设备验证。


## 最终实现审阅与验证关闭（2026-10-24）

| 条目 | 修复与复核 |
| --- | --- |
| 周期候选账户日期 | 使用下一未处理期日期验证模板引用，保留原始锚点算法；core/storage交叉复核关闭。 |
| CSV临时文件取消清理 | 在IO内部取得scratch所有权，未交给预览前由NonCancellable finally回收；Dialog取消/后台另释放已接管文件。真实Activity在0/5/25ms取消验证通过。 |
| 授权epoch撤销草稿 | SessionCoordinator真实revoke回调统一清理controller/外部lease；旋转和有效picker不撤销；13项session测试与真机生命周期测试通过。 |
| UI-R1周期类型切换 | 转账清除预算排除/project/merchant，UI隐藏字段不再阻断领域校验。 |
| UI-R2来源账本 | FinanceNavigation携带sourceLedgerIds贯穿流水→金额→收支/转账及snapshot查询；多来源转账显式选择账本。 |
| UI-R3新建维度关联 | createEditorDimension走同一单写门，仅在本命令成功且draft id/ledger/generation仍匹配时关联；普通busy输入继续阻止，失败/草稿撤销不回填。 |
| 流水聚合覆盖 | 独立activitySummary SQL请求提供范围/日金额，校验scope/dataset/version，不能对分页内存求和代替全量。 |
| 受限入口覆盖 | 拆分/手续费可达空页，往返保留草稿；分期、会员、登录及在线配置仍为空，不产生假保存。 |
| 视觉P3 | 存钱卡片标题/图标、我的选中底色、虚拟账户展示名修正；持久化标识不变。 |

最终 [验证报告](2026-10-24-finance-verification.md) 记录376项主机测试、13项发布脚本、10项真机测试、签名APK和具体边界。方案、实现、独立代码复核与真机证据分开记录。所有P0/P1/P2已由原审查者复核关闭。

设备决策：既有正式包与Debug签名不同，采用debug-only `.validation` applicationId后缀，保护原包数据；生产Release身份不变。OEM测试启动确认只允许本次，未关闭系统保护。测试截图由带UI测试标记的隔离数据产生，未关闭FLAG_SECURE。首轮恢复实体顺序断言按ID修正，未放宽实际内容验证。
