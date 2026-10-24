# 2026-10-24 纠正版本独立验收

审阅者：`correction_acceptance`。前期是 UI、路由与拖拽的非作者审阅者；最新普通二级页/搜索范围已转为作者，详见文末职责变更，此部分必须由 visual/root 独立验收。全程未运行 Gradle 或 ADB。本文随修复证据更新，不以审阅人数代替交互验收。

当前追加范围：用户最新要求分类管理等可排序页与主页面板共用同一套行和开关 UI。下文12项设备通过及资源/动画闭环属于这一追加改动之前的验证基线；新增共享 `ReorderSettingRow` 及 Category/Ledger/Dimension 迁移必须再次完成源码、截图和构建验证，不能沿用旧签名声明最新产物完成。

最新状态：用户再次追加面板开关闪动、点击金额打开计算键盘、首页全月曲线与自定义日期、自动记账三种方式/通知栏截图入口，已转入新一轮方案交叉审阅。此前共享行管理定向通过，Entry+动画定向2项通过（36.853秒）；首页雷达旧作用域已在旧APK确实复现，修复后专项及最终全量尚未运行即被新增要求转向。此文不把历史失败或定向通过混称最新完整通过。

## 验收依据和证据版本

- 用户最新要求：不出现登录、会员等空入口；真实二级页；图表与密度贴近薄荷；排序使用长按拖动、其他项让位与落位吸附；主页面板顺序返回首页立即生效；直接使用可恢复的原版图片/XML 依据。
- 方案：`docs/superpowers/specs/2026-10-24-ui-correction.md`；排序契约：`docs/implementation/2026-10-24-atomic-reorder-api.md`。方案中“至少上移/下移、长按若实现”的旧措辞已被用户后续要求覆盖，不能作为按钮排序的验收依据。
- 原版：`.local/research/bohe-20261024/round2/`，以及 `jadx/resources/`、`jadx/sources/`。
- 当前实图：`.local/correction-device-round2/01-首页.png`、`03-统计.png`、`04-更多.png`、`05-我的.png`、`06-账户详情.png`、`08-存钱详情.png`、`17-借入详情.png`、`18-周期详情.png`，已逐张实际查看。此批图早于下述 P2 修复和最新紧凑开关，不能用于证明这些修复最终生效。
- 修复闭环实图：`.local/correction-device-round3/02-账户.png`、`04-更多.png`、`05-我的.png`、`06-账户详情.png`、`08-存钱详情.png`、`17-借入详情.png`、`15-主页面板.png`、`16-统计面板.png`，已逐张实际查看。IA-01/02/04/05 在此批图关闭；IA-03 浅色尺寸与颜色关闭，深色我的尚待补图。
- 深色补证：已实际查看 `.local/correction-ui-final/19-深色我的.png`；自动、组件、备份、货币、安全的单色原图均使用浅色前景，管理宫格保留原彩色，图标不再陷入深色底。IA-03 关闭。

## 独立发现与修复闭环

| 编号 | 严重性 | 有证据的问题 | 依据 | 当前状态 |
|---|---|---|---|---|
| IA-01 | P2 | “往来”误用白色透明双箭头 `ic_advance_contact`，白底下图标不可见；`more_blender` 握手心形误配给工具箱。 | Round 2 `04-更多.png` 可见往来图标空白、工具箱显示握手；原图 `p292-more-home.png`；原 `i4.java` 的 `BLENDER` case 4 → `o01.Q0` → `more_blender`。 | 已修复；Round 3 `04-更多.png` 闭环。 |
| IA-02 | P2 | 资产摘要仍使用自行绘制的渐变圆形背景，而原版海面游艇图片已在 APK 内可直接使用；原卡的历史线也未出现在摘要卡。 | 原 `p394-account-new-data.png`；`assets/widget_images/account_theme/image_account_header.jpg` 已实际查看；`o7.java:532–533` 默认 `account_theme_fill` 明确引用该文件；旧 `AssetHero` 使用渐变 Canvas。 | 已修复；Round 3 `02-账户.png` 与请求绑定源码闭环。 |
| IA-03 | P2 | 我的列表单色图未使用前景 tint，无法随深色主题改变；图标实际绘制尺寸也比原布局大。 | 原 `main_me_fragment_list_item.xml` 为 28dp 盒、4dp padding（图20dp），tint=`skin_content_foreground`、右箭头16dp；旧 `ProfileAction` 两侧默认24dp且单色映射 `tinted=false`。宫格原 `main_me_fragment_icon_item.xml` 30dp盒、4dp padding（图22dp），旧 `ManagementGrid` 图28dp。 | 已修复；源码、Round 3浅色图、final/19深色图闭环。 |
| IA-04 | P2 | 存钱详情摘要采用原版没有的巨大环图、重复对象标题及独立双按钮行，首卡约265dp；原版约170dp，金额与存入操作同行，次要指标紧凑排列。 | Round 2 `08-存钱详情.png` 对原 `p235-savings-deposit-saved.png`。数据同为已存5、目标100，可直接比较信息布局。 | 已修复；Round 3 `08-存钱详情.png` 闭环，三项指标来自真实数据。 |
| IA-05 | P2 | 账户详情重复顶部对象名与类型，借入详情将四行元数据置于资金记录之前，关键流水位置较原版显著下移。 | Round 2 `06-账户详情.png` 对原 `p100-cash-account-detail.png`；Round 2 `17-借入详情.png` 对原 `p256-borrow-detail.png` / `p262-borrow-partial-repaid.png`。 | 已修复；Round 3 账户和借入详情实图闭环。 |

## 已核实的改善与边界

修复源码复核补记：IA-01 已改为往来 `more_blender`、工具箱通用 `ic_advance`；IA-02 `AssetHero` 已加载原 JPG，并由账户列表请求最近30天真实历史，绑定 request/dataset/contentVersion 后才绘图，隐藏金额时不泄露曲线；IA-03 主图20dp/右箭头16dp和前景 tint 已落地，宫格图22dp；IA-04 已移除环图，改金额/目标/存入同行及三项真实指标，存入、取出、编辑、删除/恢复、愿望、资金流水入口仍保留；IA-05 账户摘要简化且借入/还款记录前置。新增 `Alignment` 导入遗漏在非作者复查发现后已由作者补齐。以上关闭源码层问题，最终 APK 视觉状态仍以下一批截图为准。

1. 资源确为原始字节复用：对 `2026-10-24-reference-artwork.json` 的当时40项用途、34个唯一目的文件逐个计算源文件/目的文件 SHA-256，全部相同且匹配清单。哈希相同不代表语义映射正确，IA-01 正是反例。
2. 五个导航图已能由 `MainActivity.java` 的实际调用映射至 `ic_home`、`ic_account`、`ic_stat`、`ic_advance`、`ic_me`。本轮实图显示这些图标生效。
3. 首页当前首屏能看到支出面积图、未入账、三条最近交易以及预算开头；统计首屏能看到分类环、日趋势、月度柱图和配置入口。相较上一轮过大卡片，密度已有实质变化。没有从数据不同导致的曲线形状差异推断 UI 错误。
4. 查看过的账户、存钱、借入、周期详情均为实际内容页，有真实金额、历史/记录和适用操作；不能再说这些图对应的是空 Box。此结论只覆盖已查看的页面与状态，不自动等于所有按钮的行为通过。
5. `FinanceHomePage` 与 `PanelManagementPage` 都从 `PanelCatalog.resolve(s.preferences, HOME)` 获取同一稳定 ID 布局；首页按 `layout.order.filter { it in enabled }` 渲染，保存走 `Command.SetPreference`。源码没有另存一份首页顺序。是否拖动后立即刷新并重启保持，仍须使用真实交互记录证明。
6. `LongPressReorderableList` 使用 `detectDragGesturesAfterLongPress`、中点跨越重排、`animateItem` 弹簧让位、拖动抬升、松手 `Animatable` 归位、边缘滚动。拖动前冻结版本与回调，取消/忙碌/版本变化丢弃预览；松手一次提交。源码存在这些机制，不等于已看到吸附动画或跨屏拖动成功。

## 首轮待补证（后续闭环见文末）

- 上述修复后的对应实图；对于深色图标须在深色我的页面查看，不以浅色图确认。
- 主页面板真实长按拖动后，配置顺序 → 返回首页顺序 → 重启后的顺序；以及开关显隐。取消不写入、异步提交失败恢复、不可跨分组的约束，应由对应测试或复现记录覆盖。
- 可排序管理页的实际触摸行为不能只由面板页一个测试外推；账本、分类、项目/商家/标签、同账本同类型账户分别记录覆盖与未覆盖。
- 最终构建/测试由 root 统一运行；本审阅不以旧截图、旧 APK 或源码存在推断最终编译与行为通过。

## Round 3 闭环与交互测试审阅

- IA-01：`04-更多.png` 中往来已恢复握手心形，工具箱为通用四格图标，无白底失踪图标，关闭。
- IA-02：`02-账户.png` 已实际显示原版海面游艇照片、真实白色历史曲线、资产/负债与趋势入口，关闭本项背景和摘要图遗漏。没有据此声称整页逐像素相同。
- IA-04：`08-存钱详情.png` 已无巨大环图，金额/目标与存入同行，3项真实指标下置，存取记录提前，关闭。
- IA-05：`06-账户详情.png` 摘要已精简为当前余额；`17-借入详情.png` 本金/还款分组记录先于元数据，关闭。
- IA-03：`05-我的.png` 宫格/列表尺寸和单色前景色已符合修复；后续 `correction-ui-final/19-深色我的.png` 完成深色实图闭环。

只读审阅了当前 `FinanceDeviceTest.kt` 的触摸测试：`drag` 注入真实 DOWN、等待长按阈值、18段 MOVE 与 UP，随后检查持久状态；首页另外比较“最近交易”与“10月支出”的实际节点 top，能证明返回首页后顺序立即生效。最新测试还为账本、分类、项目、商家、标签、同类型账户各注入一次拖动。此链路没有通过直接命令排序冒充触摸排序；测试数据准备使用生产命令是合理的隔离 fixture。

已向 root 指出两项测试证据问题：统计详情等待条件 `panels[id]?.loading != true` 会让 null/error 通过，而仅断言没有“不支持页面”不能证明详情加载完成；渲染 fixture 等路由后固定350ms，未针对资产/账户历史等待匹配结果，可能截到加载态。应加强非空结果、无错误与版本匹配断言。Round 3 实图中的资产和账户历史恰有完整曲线，因此这两张实际图自身不受“抓到加载态”的问题影响。

后续测试源码已补强：统计详情要求 `!loading && error == null && snapshot != null`，并校验 snapshot.request / datasetId / contentVersion；资产和账户渲染增加历史加载完成与非空判断；补入 `19-深色我的` 捕获。纠正方案第2条也已更新为强制长按拖动、让位、吸附、持久化和主页同序，旧的可选长按措辞已移除。

历史拖动采样证据：已读取 `.local/correction-drag-animation-red.log`（文件名带 red，但实际结果为 `OK (1 test)`），并实际查看 `.local/correction-drag-before/move-12.png`、`move-18.png`、`drop-0.png`、`drop-11.png`。可见拖动项浮起、跨中点后邻项让位、松手后稳定落入第二槽位。`pointer-samples.txt` 的24点最大中心偏差为5.8125px，小于该测试12px阈值。

**该批是修复前 APK，不能作为交换动画修复通过证据。** root 指出逐帧 PNG 编码和 accessibility 等待会跳过跨中心瞬间，实际 Compose placementDelta 可能与手动 translation 叠加；本审阅者接受此证据边界，撤回以该组慢采样关闭动画缺口的判断。它仅证明慢采样时点的跟手和最终顺序，不能否定瞬时额外交换。最终应核对跨中心后停指、连续内存采帧、结束再编码的修复前后结果。

最新交互脚本也加入 ACTION_CANCEL 后 contentVersion 与面板布局均不变的断言，待该方法运行完成确认。跨屏边缘滚动与写失败回滚尚未由这组短距离手势实测；不能从一次落位排序通过外推这些分支均已覆盖。底层完整作用域、幂等、旧版本拒绝与数据库重开/备份顺序有独立 core/storage 测试源码，但本审阅者未运行它们。

## 共享行追加前设备证据复核（历史基线）

已只读核对 root 运行后保存的 `.local/correction-device-final.log`：实际结束为 `OK (12 tests)`，175.053秒。`.local/correction-interactions-final.txt` 记录取消拖动、首页即时同序、16项统计详情、8个更多入口、17个我的入口、六种管理拖动，以及生产备份恢复布局保持。取消分支同时断言版本与配置未变化，首页顺序使用真实节点位置比较，管理排序使用持久状态断言；没有用直接排序命令替代手势。

最终拖动证据来自 `.local/correction-drag-final/`，区别于前述 before 目录。已实际查看 `hold-2`、`hold-3`、`hold-29`、`drop-0`、`drop-5`、`drop-15`、`drop-29`：停指期间被拖卡保持位置，邻项已让位；松手后向新槽位收敛，最终间距正常。30个记录点的最大像素中心偏差为5px；修复前连续采样为最大9px，二者都低于16px断言阈值，因此不把这项测试描述成“修复前必失败”的红绿回归，也不夸大为通用性能指标。

新源码也已非作者复核：active/landing 项关闭 placementSpec，只由手动 translation 控制；邻项保留 animateItem；落位保存 viewportTop 并在 graphicsLayer 读取实时 slot；独立 ReorderLanding 与身份检查防止旧动画清除新状态；失败回退从当前可见位置重新落位。该职责修正、最终连续帧和手势落位断言共同构成本次排序验收。

截至最终版：IA-01至IA-05 均完成源码和新 APK 静态实图闭环，设备12项套件有明确通过日志，无当前有证据的未修复 P1/P2。本审阅者未运行构建/设备命令，以上为对 root 产物的独立复核。尚未实测跨屏边缘滚动与故意写失败后的动画；不能扩展为这些分支或原版全部功能的完全等价声明。

## 追加：分类等管理页共享排序行

用户随后追加“分类管理等拖动和开关与面板共用一套 UI”，本段是上文通过基线之后的增量验收。

已完成非作者源码审阅：`ReorderSettingRow.kt`、`PanelManagement.kt`、`ManagementScreens.kt`、`DimensionScreens.kt`。

- 共用16dp圆角、16×12dp内边距、bodyLarge标题/bodySmall说明、36×22dp开关视觉槽和16dp列表间距；说明和长标题自然增长，22dp只约束菜单视觉槽，没有固定整行高度。
- 长按手势仍统一由外层 `LongPressReorderableList` 承担，共享行没有引入第二套拖动手势。
- `checked != null` 必须提供 `onToggle`，`checked == null` 不显示开关。分类对应真实 `Category.enabled` 与 `SaveCategory`，账本和项目/商家/标签没有被虚构启用状态。
- 整行显式 `onClick` 优先；没有显式点击时才执行开关切换。`toggleEnabled` 与整行 `enabled` 分离，归档账本分类仍可打开带 ledger/category 范围的流水，而开关、菜单、拖动禁止写入。开关独立点击不需要触发整行的流水动作。
- 原有菜单、稳定ID、完整成员、隐藏/归档成员合并、作用域和拖起版本冻结保持；没有把排序改回逐条保存。

当前源码无新增有证据的 P1/P2。等待 root 对增量版本重新构建，并提供分类/面板新截图与手势、开关结果；此前签名和12项设备通过不能代替这一追加改动的验证。

追加实图复核：已实际查看新 `.local/correction-ui-final/15-主页面板.png`、`20-分类管理.png`、`21-账本管理.png`、`22-项目.png`。分类与 Panel 的卡片行、说明和开关尺寸一致，分类22dp菜单没有撑高；账本保留有效账单数，账本和项目均无假开关。项目页“显示归档”在该批截图仍为较大开关，root 已做窄修提取 `CompactFinanceSwitch` 供共享行及 `FinanceToggle` 同用。非作者源码核对确认原36×22dp视觉槽、52×32dp/0.70缩放、enabled和回调完整保留，FinanceToggle 的52dp整行高度不变，Panel的开关语义描述仍在。此最后样式提取待重新编译和项目页新截图，不沿用旧图声称已生效。

## 共享行增量测试的失败与定向复测

`.local/correction-device-final.log` 在共享行版本被新结果覆盖，实际曾出现分类拖动后的持久顺序等待超时（失败时仍在“分类管理”），另一个动画测试报告 Activity 已销毁。因此上文175.053秒的12项通过对应的是已归档的 `.local/correction-device-before-shared-row.log`，不能视为当前全量通过。失败页面仍是分类管理，只能排除该次已经导航到流水的情形，不能单凭此断言组件手势正常或异常。

源码对照发现 Panel 与 Category 共用同一 Surface 和外层长按检测，分类主要增加独立菜单与显式下钻点击；没有证据可直接归因“Surface 吞手势”。前序账本拖动等待仅校验新顺序，而后续拖动受 `busy` / `pendingOperation` 禁用；DOWN 前处于禁用态是候选原因，旧失败日志没有记录该时刻状态，未确证根因。

root 随后仅为测试每次 DOWN 前增加 `!busy && pendingOperation == null` 等待与状态/边界记录，未修改产品手势实现、未放宽持久排序断言。已独立读取 `.local/correction-management-focused.log`，结束为 `OK (1 test)`、39.439秒。该定向方法覆盖分类真实长按 → 停用 → 启用 → 菜单重命名取消 → 范围流水下钻 → 返回，以及账本、项目、商家、标签、账户的原顺序断言。结论仅是可交互状态下该管理链通过；不把一次定向通过写成上一轮失败原因已确认，也不替代最新全套运行和动画用例重跑。

## 追加：记账单页独立源码审阅

已核对 `EntryScreens.kt` / `EntryComposerControls.kt` 与 `EditorDraft.command()`、`AppController.changeEditor()` / `createEditorDimension()` 真实链路；根路由的 FAB 直接调用 `beginEditor`，不再以金额页为必经步骤。

- 主次保存共用 `canSave`，都要求非 busy、重复核对完成且无错误、OCR 字段确认及必要的重复确认；最终仍走 `draft.command()` 的领域校验，没有在 UI 中构造旁路命令。
- 编辑和退款继续使用相同草稿；退款的类型、账本、分类不可修改，预算资格仍继承原支出。账户选择使用真实 `spendableAccounts`，项目/商家/标签带账本作用域；切换账本由控制器清理跨域选择。
- 快捷分类区会把当前选中项补回可见候选；全部分类仍可选。标签快捷多选、全部选择与新增操作均更新同一草稿；维度新增仍经生产写入完成后关联当前 operationId 的 surviving draft。
- 金额键盘保留文本选区、删除、算式、小数和括号；空串/运算符后的小数点补 `0.`。金额计算器 import 缺失在预读时指出，作者已补，不把源码审查当作编译通过。
- 固定保存区位于唯一正文滚动容器外；自定义数字键盘与系统金额键盘有互斥状态，大字号/小窗口按实际可用高度关闭默认计算键盘，正文继续可滚动。截图识别警告与错误重试、备注/日期、更多选项都有真实内容和操作。

当前源码未发现有证据的新增 P1/P2。仍须以当前产物的普通首屏、IME、较小窗口/大字体及保存后账单验证布局和行为；尤其根 Scaffold padding 与 `imePadding` 的组合须检查实际底部留白，不能仅凭源码存在固定栏声明全部尺寸通过。新增设备测试使用真正点击键盘/分类/标签，断言12.34保存精确分值及3+4保存继续生成新草稿，待运行结果。

冻结后根集成复查发现 IA-06（P2）：同页键盘输入 `3+4` 后直接切“转账”，`EntryForm` 将 `draft.amount` 原样传给 `TransferEditor`，但后者保存仍使用 `Money.parse(amount)`，必然拒绝该算式；旧前置金额页的下一步会先 `AmountCalculator.evaluate` 再 `Money.format`。已通知 root/route，需要在清空草稿之前完成非空金额规范化，非法算式应留在表单报错，空金额仍可打开转账；或使用等价统一计算口径。该项属于新增同页路径的实际数据交接缺口，先前单独草稿保存检查不能关闭它。

IA-06 源码修复复核：route 已新增 `transfer()`，非空输入先 evaluate+format，空/全空白传空串；DomainException 仅更新本地 error，不调用 onTransfer、不改变/清空草稿。转账 chip 改走该函数，root 原有切换确认保留。源码缺口关闭，等待最新设备算式传递及非法输入不离页结果。

其余根集成检查：`requestBack` 在 busy 时阻止返回，dirty/capture 草稿离开仍弹确认，确认后才 `clearEditor`；未修改 `beginEditor` 的来源返回页和 continuation 规则。root 已在实际 Scaffold padding 处补 `consumeWindowInsets(padding)`，与 Entry 的 `imePadding` 配合，源码层重复 inset 风险已处理，仍等 IME 实图。

已实际查看 Entry 第一张设备图 `.local/correction-entry-final/01-amount-category-tag-same-screen.png`：金额、六格分类、账户、标签、备注/日期、更多、计算键盘和保存同屏，无前置“下一步”页。但该次真实输入断言失败，截图外观不能代替功能通过。

IA-07（P2，设备发现、非作者字节码复核）：root 增加逐键日志后观察到 `1→1、2→2、.→0.、3→3、4→4`，分类和标签正常。只读 `javap` 验证失败编译产物：`::amountKey` 生成 `FunctionReferenceImpl`，额外字段捕获 `$draft/$busy/$selection`；EntryForm 随 draft 变更新建引用，EntryAmountKeyboard 入口使用 changedInstance，但每个键 Surface 的 onClick 缓存使用 `Composer.changed(onKey)`（equals）。本地 Kotlin 2.4.21 `FunctionReference.equals` 仅比较 name/signature/boundReceiver/owner，不包含该局部引用额外的 draft 字段，键回调因此可继续使用首次空草稿。若只是选区为0且正文已更新，第二键应为21而非2，不能用选区归零解释整段覆盖。已通知作者将局部函数引用改为显式回调并核 `::transfer` 同类风险；等待修复重编译与原严格输入断言通过。

该次 javap 证据的精确位置（均是只读现有产物，未由本审阅者编译）：

- `shared/ui/build/classes/kotlin/android/main/com/dawnyew/ui/EntryScreensKt$EntryForm$3$1$1$2$1$1.class`：类继承 `kotlin.jvm.internal.FunctionReferenceImpl`；构造函数保存 `$draft`、`$busy`、`$selection$delegate`、`$onChange`、`$error$delegate`，`invoke(String)` 再把这些捕获字段传给 `EntryScreensKt.access$EntryForm$amountKey`。
- 同目录 `EntryScreensKt.class`：调用 `EntryAmountKeyboard` 之前的生成 lambda 在字节码偏移1955对 draft 调用 changedInstance；2029–2042创建上述 FunctionReference，2092调用键盘。父层不是完全没看到草稿变化。
- 同目录 `EntryComposerControlsKt.class`：`EntryAmountKeyboard` 入口偏移65对 onKey 调用 changedInstance；但其生成的每个键 onClick 缓存段偏移1383调用 `Composer.changed(Object)` 比较 onKey，1434–1448才在改变时重新捕获 onKey/key 并缓存。这个下游 equals 比较是旧捕获存活点。
- `.local/gradle-home/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.4.21/c343e135bd1e7dd94b7ed76b4159068d888d09db/kotlin-stdlib-2.4.21.jar` 中 `kotlin.jvm.internal.FunctionReference.equals(Object)`：比较 getName、getSignature、getBoundReceiver、getOwner；未访问生成子类的 `$draft` 字段。

修复源码复核：作者已用 `rememberUpdatedState` 包装 draft、busy、onChange、onSave、onTransfer；`amountKey` / `save` / `transfer` 在动作发生时读取 current state，键盘与转账改显式 lambda。每次打开系统键盘另递增 keyboardRequest，LaunchedEffect 同时以 systemKeyboard/keyboardRequest 为键，解决保持同一 true 状态时“输入”按钮无法重新触发 show 的风险。确认未改业务命令及完整保存 guard；待17→退格1→12.34、算式、转账和 IME 重开的设备回归。

IA-08（P2，同根因的具体首页作用域链）：根 `navigatePage` 捕获 homeScope，经局部 `::navigatePage` 传给首页，分类雷达的 `onOpen = { onOpen("分类统计") }` 可按函数引用 equals 缓存旧捕获。只读现有 `DawnYewAppKt$DawnYewApp$8$2$15$2$3$1.class` 确认该 FunctionReferenceImpl 的 `$page/$homeScope` 字段；`HomeScreenKt.class` 对应分类雷达调用 `FinanceStatisticCard` 之前，偏移895以 `Composer.changed(Object)` 比较 onOpen，952创建包装回调。首页月份/账本改变而雷达卡仍在原组合位置时，图表 request 可刷新而详情仍收到旧 homeScope。已通知 root 窄修点击时的 scope/page 读取，并补启用雷达后换月份/账本下钻断言；不把此证据外推所有函数引用都有问题，不做无范围重构。

IA-08 源码复核：root 已给 page/homeScope 增加 `rememberUpdatedState`，`navigatePage` 内在点击时读取 `.value`；保留原 statistics route 判定、来源状态清理和 openStatistics 链路。该具体旧捕获缺口源码关闭，独立 HomeScope 设备回归仍待结果。

## 最新记账单页与动画定向闭环

已直接读取 `.local/correction-entry-drag-focused.log`，结束 `OK (2 tests)`，36.853秒。当前测试源码保留17连输、退格回1、12.34精确保存1234分、分类/标签同草稿、3+4继续保存700分并生成新operationId、IME返回收起后再次打开、切回自定义键盘、取消转账保留表达式及确认后保存700分转账的断言。该结果关闭 IA-06 和 IA-07 的对应交互缺口，也单独重跑通过了此前 Activity 已销毁的动画测试；不据此把历史生命周期异常原因改写成已确定。

IME切回计算键盘曾真实失败：imePadding缩小 maxHeight 使 canExpandKeyboard 为false，原按钮分支重新打开系统键盘。root 改为 `systemKeyboard || canExpandKeyboard` 时先退出IME并请求展开，下一帧仍按完整可用高度判断是否能显示计算键盘。上述2项通过已覆盖此分支，未删除等待IME消失的断言。

实际逐张查看 `.local/correction-entry-final/entry-composer/02-ready-to-save-without-scroll.png`、`03-continue-keeps-selection-clears-amount.png`、`04-system-keyboard-save-visible.png`：12.34、购物及选中标签同屏，继续保存后金额清空且标签保留，系统输入态没有自定义计算键盘且固定保存栏上移。第04图是 Activity View 绘制，不含独立IME窗口像素，不能把下半空白误读为系统键盘未显示；IME显示和保存栏不被遮挡另有实际 WindowInsets.isVisible / IME bottom 与节点 bounds 的断言。正常字体的本机首屏通过，不将其外推所有窗口或大字体已验。

最新动画另实际查看 `.local/correction-drag-keyboard-final/hold-3.png`、`hold-29.png`、`drop-29.png`：停指两时点的抬升卡相同位置，邻项让位已完成，松手后卡片落入第二槽位且间距正常。pointer-samples末段偏差5px，与当前用例通过一致；仍保留前述“旧阈值也曾通过，不能描述稳定红绿性能回归”的限制。

后续视觉审阅提出继续记账的 Snackbar 遮住固定保存按钮（03图可见）。root 改为标题暂显“记一笔 · 已保存”，按新草稿 operationId 匹配并1800ms清除。独立复核补充：`AppController.performOperation` 在成功回调前还会先 `notify("已保存")`，仅删除原 continue 回调的第二条通知不够，仍会出现通用成功 Snackbar。已要求成功回调定向处理本次通用成功消息或使用明确通知策略，保留其他错误/写入消息，最终03图再闭环。

该补充已源码修复：keep成功回调先 `clearMessage()` 再设置 `entrySavedFor`，与 `performOperation` 的通用成功通知在同一同步成功链完成；其他写入与错误分支未改。设备回归新增继续保存后不存在通用“已保存”/旧 continue Snackbar 文案的断言，并捕获03图。仍待新图和运行结果，不沿用旧03图关闭。

IA-08旧APK专项补证：已直接读取 `.local/correction-home-scope-old-apk.log`，实际失败为 `Expected <2026-09-01>, actual <2026-10-01>`，已到源作用域断言，确认旧月下钻的真实行为。此前名带red的 `.local/correction-home-scope-red.log` 仅卡可编辑节点定位，不能作为这个RED；两者不能混用。root已安装修复后debug，但在最终14项运行前用户新增范围，尚不记录GREEN。

## 新增四项的独立方案审阅

本阶段尚为方案与数据契约审查，不提前声明实现或设备完成。

- 面板闪动：当前 Panel 将 busy 传播为所有行 enabled=false，Material Switch 同时切 disabled 配色，再在提交后切回。认可新增 transientBusy 的局部方案：业务 enabled/toggleEnabled 保持独立，交互按 `enabled && !transientBusy` 禁止，只有本来可用但暂时写入的开关使用对应正常配色；Panel 启用，真实只读行及其他 consumer 默认不改。向作者明确 helper 应收到业务 enabled 与 transientBusy 两个参数，不能先合成false导致保色条件失效；排序的busy守卫与已提交checked来源保留。
- 点击金额：认可普通触摸 PressInteraction.Release 与语义点击共用最新动作，点击金额总是请求自定义计算键盘，只有“输入”显式打开系统IME。完整 body 容量在 imePadding 外测量，唯一 imePadding 放内部，等IME inset归零再显示自定义键盘；保留选区与焦点，小窗口真实容量不足才系统fallback。不加覆盖层或消费原文本选择手势，不撤销已验证的 currentDraft/callback 修复。
- 首页日期：认可保留实际自定义range、<=91日显示完整每日点（包括自然月剩余日期的真实0/已有记录）、长范围沿用已有月聚合，只稀疏轴标签而不裁数据。要求预算/对比固定范围结束月，日历独立calendarScope；lastHomeRange单值进入共享validatePreference，只首页显式选择写入，新键缺失兼容lastPeriod，不能将统计tab选择覆盖首页或为旧备份注入新字段改变源摘要。按月点的标签和drill须使用实际粒度，交集范围不得越出用户选区。等作者最终契约确认及实现后复审。
- 截图快捷入口：已读现有 MainActivity 的 onNewIntent/session撤销与 recognize(epoch/unlocked检查、bitmap回收) 链；新通道必须明确一次性结果消费、会话授权/取消/锁屏、超时和后台资源释放，沿用OCR人工核对与重复检查，不截图即记账。入口原版三种方式的事实识别及实现方案由root提供后再审。

截图平台约束已通过官方资料核实：API34+ Tile 使用 startActivityAndCollapse(PendingIntent)，旧 Intent 重载不适用于该版本；MediaProjection 每次会话需要系统同意、token单次使用、创建显示前注册onStop，并声明 mediaProjection 前台服务类型与对应权限。拒绝/撤销/锁屏/超时应释放全部显示/图像资源，不复用旧授权Intent，不移除现有FLAG_SECURE。实际验收须证明捕获的是授权框收起后的目标画面，单纯打开系统授权页或创建VirtualDisplay不算完成。[TileService官方API](https://developer.android.com/reference/android/service/quicksettings/TileService)、[MediaProjection官方说明](https://developer.android.com/media/grow/media-projection)。

## 职责变更：普通二级页与搜索实现

用户追加普通二级页、设置与搜索筛选对照后，root 将该子范围交由本审阅者实施。因此本节之后对这些文件的自审不再称为独立验收，须由 visual/root 非作者交叉审查；此前 Entry、Home、拖拽和截图平台仍是独立审阅。

已对照原 PNG 与 UI XML：p460 备份为白底整页平直行，16dp水平边距、双行约59dp；p357 设置按组排列；p083/p067 搜索输入与胶囊筛选保留在结果页。方案经过 root 与 visual 审批，记录在 `docs/superpowers/plans/2026-10-24-secondary-lists-search.md`。

已实现 GroupedSettings 普通组/行、Backup/Security/Settings/Help/Reminder/FileAndWidget/Toolbox 迁移。备份五条真实操作、口令、明文确认、恢复预览、保护副本和危险操作确认回调保持；未改资产/往来业务表单、图表或可拖管理行。新增 ActivityQueryDraft + ActivityQueryControls，scope 仍由 FinanceController 唯一持有，待应用条件为单份草稿，每次仅一个字段编辑器；Apply 校验后提交，Cancel 恢复已应用条件，Reset 只改待应用条件。多选维度及未分配条件可见，选择账本清除跨域依赖；清空保留 deleted/includeArchived 路由域。金融查询允许零金额边界，未沿用交易金额必须正数的错误限制。单元用例由 root 统一执行。

搜索结果保留原 dataset/request/version 门禁，原 2×2 大汇总收敛为支出/收入/结余三列，退款和转账保留辅助行，明确结余含退款且不含转账；大字体转为纵排。已确认 AmountEntryPage 全仓没有调用，随旧大 ActivityFilterDialog 删除；根首页直达结果与 replace 查询路由由 root 单独集成。当前仅源码自审和 diff check，不声明编译、设备或像素验收通过。

## 新 Entry 结构与平台计划交叉复核

对 visual 新增 EntryComposerState/Sections/Dialogs 与 EntryForm 再作非作者源码审查：UI 状态按 operationId 隔离，金额/备注各自 selection，备注 composition 不做文本业务副本；单 InputMode 控制金额计算键盘、备注 IME 与小窗口系统金额输入，单 sealed dialog 取代并行布尔。latest Bindings 每次事件读取 Controller 当前 draft/busy/callback；金额 evaluate+format 转账、draft.command 保存、OCR 重复核对及字段确认、退款禁修改类型/账本/分类保持。真实 body 在 imePadding 外测量，内层只有一次 imePadding。尚须设备覆盖中文组合输入切换、三行以上备注内部滚动与 Back 后重新点备注，不以 ASCII 设置文本替代输入法验证。

截图平台方案补审已要求并由 root 确认：未授权 observer 只保存有界 opaque URI/失效信号，连 MediaStore metadata 也在授权后才读取，并按启用时间窗口过滤；通知和全照片读取能力不足不得启动后台监听；inbox purpose/generation/TTL/single-use 与 Main 新 sessionEpoch 的消费边界分离。Tile 系统同意授权一次屏幕读取，不自动授权账本访问或保存。root 曾提出冷启动 0→1 generation 特例，随后逐行查 GenerationRepository 证明普通初始化并不加 epoch，因此未引入多余 InitialOpenOnly 模型，继续严格 generation 等值；schema 升级真实换代拒绝旧 token。此为源码契约复核，不声明截图端到端已运行。

普通页非作者复核由 visual 完成：备份五回调、清空确认、筛选 draft 取消/清空/应用、dataset/version/request 守卫保持，未发现业务 P1/P2。发现视觉 P2：胶囊 clickable Material Surface 的最小交互尺寸会把约30dp内容撑到48dp。作者已仅在 QueryPill 内提供 LocalMinimumInteractiveComponentSize=Dp.Unspecified 并设32dp最小高度，长标签仍可自然增长，不影响整页其他触点。待新实图关闭视觉缺口；未把源码修复当像素通过。

首轮统一检查 `.local/convergence-check-first.log`：UI JVM/Android 编译阶段完成，但总体失败；ActivityQueryDraftTest 2条 fixture 使用非UUID短ID触发真实领域校验，作者只将测试ID改为合法规范UUID，未放宽生产校验/断言，其余3条筛选用例已通过。首页 QueryLoadingTest 另3条失败交 route 诊断，不能记为全部构建通过。

平台实现非作者审阅新增发现：自动Tile只判断权限而忽略应用session会无声停止；root已补应用授权判断与解锁导航，源码问题已修。继续发现并通知root两条P2：1）全局通知授权不能代表具体通知渠道可见，screenshot-review关闭时原能力判断仍启动后台识别；2）exported Main 的 ACTION_SETTINGS + boolean enable extra 可被其他App显式伪造，使已授权时无本次用户开启动作也启动观察服务，需要内部一次性令牌或仅导航不自动开启。此两项待根实现复核，不以“Service非exported”替代入口授权。Android官方文档明确选中照片仅授权选中媒体，且用户可单独关闭通知渠道：[部分照片访问](https://developer.android.com/about/versions/14/changes/partial-photo-video-access)、[通知渠道](https://developer.android.com/develop/ui/compose/notifications/channels)。Manifest当前声明并请求READ_MEDIA_VISUAL_USER_SELECTED，同时以READ_MEDIA_IMAGES完整授权判断观察能力，源码不会把partial当full。

上述两项平台P2由root授权本代理实施，故其实现需root/route再作非作者审阅：CapturePermissions能力判断同时检查全局、runtime和RESULT/SERVICE渠道；runtime权限请求与渠道可见性分离，避免反复请求已授予的POST权限。新增CaptureActionTokens复用通用单次inbox但独立存放无图片动作，目的为ENABLE_AUTOMATIC、120秒、容量3；Tile发随机令牌，Main只保留opaque值直到正常解锁和仓库就绪后核销，伪造/过期/重放只打开配置页，不启动监听。Application锁屏清除动作令牌由root集成。

只读核对 root 第三轮产物：ActivityQueryDraftTest XML为5 tests/0 failures；CaptureActionTokensTest 4、CapturePermissionsTest 3、CaptureInboxTest 2、ScreenshotDetectionPolicyTest 6、CaptureRequestGateTest 2均0 failures。`.local/convergence-check-third.log` 整体仍失败于唯一Lint error：API<34兼容分支的startActivityAndCollapse(Intent)。已按官方API（PendingIntent重载API34新增，旧Intent在34+抛错）仅在带SDK分支的helper加窄SuppressLint和注释，未关闭项目Lint；待root再跑，不将单测通过等同最终检查通过。

对root的新CaptureRuntime/RequestGate/Service继续独立审阅：deliveryEpoch与offer共享monitor，取消Gate覆盖像素复制期间与刚发布结果，Image先于reader关闭，completion按requestId有界存储，第二个并发请求有显式失败。另发现publish异常收敛缺口：Gate先标finished再调用publish，publish抛出时后续fail和watchdog均跳过，会留透明Activity等待；已报告root，要求异常可报告且inbox offer失败回收。尚待修复复核，不以通路源码正常覆盖平台异常。

对visual紧凑ActivityRow再次独立审阅：61dp自然最小行高，常规两行、长备注/账户省略，大字体自然扩展；金额符号、借还/报销业务标题、未入账/已对账状态及点击禁用gate保持。Activities去除额外DashboardCard内边距，账户详情复用默认0水平内边距不重复缩窄。对route新增恢复/导航补修亦未发现P1/P2：仅已有monthly slot恢复，成功cache不force；无objectId的主tab切换清source/back，账户详情不变；更多预算使用结束月严格scope/dataset/version快照。以上仍待当前设备实图。

## 当前整体验证边界（源码终审）

root已修Gate异常：`finish`在publish成功后才标记finished，通知发布异常回滚inbox/token，新增纯测试证明publish失败后仍可报告终态。非作者源码缺口关闭，运行层仍按最新日志记录。

架构复核覆盖当前完整路由与本轮跨层变化：

- UI普通配置收敛于GroupedSettings，可拖设置仍共用ReorderSettingRow/LongPressReorderableList；Entry以一个UI state和Controller单份金融draft协作，搜索以单份pending query与Controller applied scope分离，未混成并行业务副本。
- 金融写入继续由AppController→Command/FinanceAction→GenerationRepository与领域校验承担，操作ID、待确认结果、恢复/清空的原认证确认链保持。截图只提供待核对候选，最终保存仍受金额/字段/疑似重复确认与领域命令校验约束，没有自动截图即自动记账。
- 金融读取保留request/scope/dataset/contentVersion守卫；首页全范围、结束月预算、独立日历scope分开，成功缓存不因外部返回无故重读；主tab切换清旧source与返回栈，详情继续保留来源。
- 新首页范围使用一条经共享validatePreference校验的lastHomeRange，旧lastPeriod作为兼容恢复；本轮没有用UI配置增加账单字段或破坏旧备份摘要。原财务数据迁移、图关系和恢复测试依然由root全套验证。
- Android明确移除INTERNET/ACCESS_NETWORK_STATE；系统截屏是每次MediaProjection授权，observer仅显式开启且全照片与两类通知能力可用。锁屏清结果/动作token，未授权observer不读媒体metadata，Main正常session和仓库就绪后才核销令牌。无需登录/会员的实际入口没有新增身份账号或网络占位实现，rg未发现产品UI TODO/NotImplemented/登录会员空页。

尚未被本代理声明通过的具体运行面：最新全部设备套件；真实QS按钮到外部页面截图/OCR/人工保存、拒绝与再次授权；部分照片授权/全局或单渠道通知关闭后禁用与运行中停止；捕获中锁屏、取消、并发第二请求、process death/TTL；真实中文composition切换和三行备注滚动；最新紧凑页面浅深色/大小字体截图；最新完整release产物与离线权限/JNI验证。已有源码或纯测试覆盖不能替代这些设备条件。此前跨屏边缘拖动与故意写失败后的动画仍不在已看短距离图证据范围。

设备测试源审补充：ConvergenceDeviceTest的busy行像素测试通过真实Switch进入被延迟的生产事务、检查未提交与禁用语义，再逐帧比较两个非目标行并核一次写入；设置用450dp真实viewport并要求实际scroll，搜索取消/清空/应用检查真实Controller范围和结果。HomeRangeDeviceTest核实际31/26趋势桶、Canvas两端触摸下钻、真实DateDialog输入、同周正/负/零/空日历与账户账本切换。日历像素中性阈值原<160会把#999零金额误当日期，已由作者修为<100并保持同周字形底线差≤2px；这是测试假失败修正，未改产品或放宽日期对齐容差。均待root运行，不以测试源码存在写成PASS。
