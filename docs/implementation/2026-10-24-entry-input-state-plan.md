# 记账输入状态与面板写入反馈收敛

本轮由 root 授权，视觉实现负责 Entry 与共享面板行；root 负责设备验证和导航集成，acceptance 负责非作者方案及源码审查。财务模型和 Controller 写入协议保持原职责。

## 具体问题与边界

- 面板开关不是整表位置重建：稳定 ID 和 order 不变。`PanelManagementPage` 将全局 `busy` 映射到每行 `enabled`，而 Material3 `Switch` 根据 enabled 选择不同颜色，导致全部开关一起闪灰再恢复。
- 金额字段是只读的自定义键盘输入视图，已有点按只移动选区，没有主动请求自定义键盘。17覆盖问题已通过最新 draft 引用修复，不能倒退为捕获旧草稿的回调。
- 用户新要求：移除金额旁“键盘/输入”按钮，改为日期、时间两个独立点击；备注直接页内输入，最多三行，更多内容内部滚动。金额点击请求计算键盘，备注点击请求系统文本键盘。

## 状态与组件职责

1. `EntryComposerState` 只保存 UI 状态：明确的输入模式 `NONE / CALCULATOR / NOTE / SYSTEM_AMOUNT`（最后一项仅真实小窗口的金额输入降级）、金额与备注选区、focus 请求序号、单一对话框目标和错误展示。没有金额、备注或其他财务草稿副本。
2. `EditorDraft` 继续由 Controller 唯一持有。UI 的最新草稿与动作回调汇总为一个只读 bindings 引用，避免多个分散 `rememberUpdatedState` 和局部函数引用捕获过期草稿。
3. 金额按键处理提取纯函数：输入当前文本、选区和按键，输出新文本、选区或可显示的错误；复用 `AmountCalculator`。UI 再将变化提交为 `EditorChange.Amount`。
4. `EntryForm` 保留编排，拆出金额/分类区、备注/元数据区、计算键盘/保存区与对话框区。原有 OCR、退款、编辑、维度新增、保存继续和转账合同不改变。
5. 外层 `BoxWithConstraints` 读取 Scaffold 已扣除顶底栏后的完整 body，内部内容仅消费一次 IME padding。自定义键盘需要容量足够且 IME inset 已归零，避免以被系统键盘缩短的高度误判窗口。
6. 金额真实点击沿用 BasicTextField 的 interactionSource release，不消费原始指针事件；无障碍点击走同一入口。备注使用 BasicTextField 的正常文本输入和三行高度限制。模式切换不重置字段选区。
7. 临时写入状态成为共享 `ReorderSettingRow` / `CompactFinanceSwitch` 的 `transientBusy` 契约：交互与无障碍保持 disabled；业务本来可用的控件在短暂 busy 时不改变颜色。真实归档/只读/不可用仍使用标准 disabled 颜色，checked 继续读取已提交状态，不制造乐观成功。

## 验证边界

- 保留已通过的设备回归证据：17、退格、小数12.34、算式3+4、保存继续、转账700最小货币单位。
- 纯函数回归覆盖连续输入、选区替换与删除、小数起始、运算符、精确金额算式边界；不写只重复实现分支的测试。
- 新设备动作：金额点击打开计算键盘；备注点击切到文本 IME；备注多行内部滚动；金额再次点击回计算键盘；日期和时间独立打开且显示保存值。
- 面板写入期间逐帧比较未操作行的开关外观，确认保持不变；仍拒绝并发写入，失败后 checked 仍来自实际提交状态。
- 最终由非作者检查整体 Entry 数据与 UI 状态归属、所有功能入口、旧入口清理、真实尺寸和设备截图。

本方案不把编译成功等同于交互验收，也不以默认首屏截图覆盖大字体、OCR、退款和输入法场景。

## 本轮实现与交叉审查

- 已落地 `EntryComposerState`、金额编辑纯函数、`EntryComposerSections` 和 `EntryComposerDialogs`。输入状态、焦点请求、两个选区及备注的中文组合输入范围归 UI；金额和备注文本继续仅从 `EditorDraft` 读取。UI 状态、焦点和内容滚动均按 `operationId` 隔离。
- 自定义金额按键仅在文本确实改变时提交 `EditorChange.Amount`；退格空值、选区移动、计算错误、超过 96 字符拒绝均不会重置 OCR 的确认状态。日期和时间独立编辑，原时间包含秒时保留秒。
- `PanelManagementPage` 仅给共享行传 `transientBusy`。共享开关同时维持 disabled 交互语义与正常颜色，真实业务 disabled 仍使用禁用颜色；未引入乐观 checked 或放开全局写门。
- route 与 acceptance 两位非作者已复核实现，未发现新的业务 P1/P2；共同发现 EntryForm 死参数 `onRestricted` 已移除而根调用仍需同步删除，交由 root 处理。
- 新增 `EntryComposerStateTest` 覆盖连续数字、退格、小数、反向选区替换、精确计算、拒绝输入保持、草稿间 UI 状态隔离。本 agent 未运行 Gradle 或 ADB；统一编译和实机由 root 执行。
- 真机增量验收仍需覆盖真实点按与无障碍点击、中文拼音 composition 未提交时切金额再回备注、多行粘贴后的三行内部滚动、系统返回隐藏 IME 后再点击备注、小窗口和大字体、日期时间独立修改，以及面板忙态未操作行逐帧颜色。此前通过的旧输入路径不能替代这轮新状态机验收。
