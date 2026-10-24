# 当日反馈收敛与整体审查（用户要求停止）

用户最后明确要求“不验证了，2分钟之内总结结果，并结束任务”。已停止后续开发与验证，未将未完成的设备验收标为通过。本文记录截至停止时的状态，覆盖当天追加的所有交互反馈。旧 correction-verification、feature-coverage 和截图结论保留为历史证据，不能证明本轮已通过。

分支仍为 `p/duanyao.0516/codex/dawnyew-android-app`，HEAD `abf4cc3c3e8cf8d53609e2c2c6c4bb29c87fccb0`。保留既有未提交改动，没有提交、推送或替换用户正式安装。

## 结构与实现

| 反馈 | 收敛后的责任与实现 |
|---|---|
| 面板开关时其他行闪动 | 共用 ReorderSettingRow 区分业务可用性与暂时写入锁，保留点击/拖动禁用门，不再用全局 busy 将整组切成灰色。只提交真实偏好，不以乐观假状态掩盖失败。 |
| 金额连输覆盖、点击金额唤起键盘 | EditorDraft 唯一拥有金额；EntryComposerState 统一焦点、选区、输入模式，稳定回调读取最新 bindings，修复局部函数引用缓存旧草稿。金额点击转计算键盘，最多只有一种键盘。 |
| 日期时间位置、备注输入 | 金额右侧分别编辑日期/时间；备注内联最多三行、内部滚动，真实 TextFieldValue 保留 selection/composition，焦点转文本 IME，不再弹备注 Dialog。 |
| 完整月份和自定义区间 | presentation 推导全局精确范围、自然月预算范围和独立日历范围；曲线不再截取最近七天，只稀疏坐标标签。自定义范围保持到下钻和恢复。 |
| 日历数字上移、显示笔数 | 日期有固定高度与基线，保留第二行净收支金额空间；净额是收入 + 退款 - 支出，不混入转账。 |
| 二级页、设置与搜索样式 | SettingsSection / SettingItem 提供平面分组列表；筛选拥有单一待应用草稿，应用、取消、清空与已应用查询分离；流水列表统一两行约61dp，不再叠加卡片内边距成108dp。 |
| 返回设置滚动位置 | 真实页面保存列表位置，返回恢复原 index/offset；新回归在450dp窗口先证明实际滚动，避免页面全部可见时的假通过。 |
| 长按排序与主页实时同步 | 可拖管理行共用组件，当前拖动项不同时播放placement位移动画；邻项先让位、放手落槽。首页直接消费同一持久化顺序。 |
| 自动记账三种方式 | CapturePlatform 只向common暴露能力；Android提供系统截图监听、QS主动截屏、图片分享/本地选择，统一 CaptureRecognizer 离线解析和现有人工确认草稿。 |

删除不可达的旧金额前置页、旧首页、旧流水/统计/回收页及旧筛选Dialog，保持一条实际路由。金额/备注没有复制第二份业务值；图表范围、过滤查询和媒体识别分别归属对应层。

截图能力不直接写账：Image/URI → 离线识别 → 新应用会话授权 → 人工核对草稿 → 既有 SaveEntry / 回执链。没有网络权限、登录/会员页面或云服务。

## 多 agent 交叉审查

方案先审查：`docs/superpowers/specs/2026-10-24-capture-and-ui-convergence.md`。实现按 Entry/管理行、日期/路由、普通列表/筛选、Android平台拆分，再由非作者交叉审查。整体审查额外发现并修复：

1. 更多预算摘要使用了自定义范围和起始月，与预算详情的结束月不一致。
2. 首页进入账户主Tab后仍保留来源账本，导致账本选择失效；主Tab统一归属，详情保留来源。
3. 流水卡重复padding导致密度仍远高于参考。
4. 主动截屏像素复制期间的锁屏/取消竞争：统一请求终态gate；锁屏失效epoch与结果投递共用原子边界；取消可撤回已投递结果。
5. 第二次并发截屏被静默忽略：按request保存终态，并明确拒绝忙时新请求。
6. 通知渠道单独关闭仍启动监听：同时检查全局权限及运行/结果渠道。
7. exported MainActivity的自动启用布尔extra可伪造：改为进程内短TTL、用途绑定、一次性动作token，解锁和数据就绪后核销。
8. 结果发布异常使请求永不终结：成功发布后才提交终态，通知异常回滚并释放结果；失败仍能报告明确终态。

审查发现的P1/P2须关闭后复审；“未发现其它问题”只针对已审范围，不等于所有OEM、所有权限组合均已实测。

## 当前已完成的验证

`.local/convergence-check-fourth.log` 与 `.local/convergence-check-fifth.log` 均 `BUILD SUCCESSFUL`，包含 Debug、AndroidTest、R8 Release、Lint 和 OCR JNI。dev.sh 已将新增 androidApp 纯状态机测试纳入日常check，避免测试写了但标准命令不执行。

| 模块 | 测试数 | failures / errors / skipped |
|---|---:|---|
| core | 79 | 0 / 0 / 0 |
| protocol | 30 | 0 / 0 / 0 |
| storage | 58 | 0 / 0 / 0 |
| presentation | 126 | 0 / 0 / 0 |
| UI | 17 | 0 / 0 / 0 |
| Android platform | 114 | 0 / 0 / 0 |
| Android app | 18 | 0 / 0 / 0 |
| 合计 | 442 | 0 / 0 / 0 |

计数来自test-results XML，Gradle正常复用未变更任务，未声称每次强制重跑。覆盖输入纯函数、日期边界/范围隔离、真实Room数据与迁移、查询恢复、待应用筛选、媒体新图边界、token代际/TTL/单用、取消竞争和发布失败。

JNI检查：129 classes / 3006 methods / 1352 fields，1 DEX，混淆开启。初始受保护文件SHA保持：

- `docs/implementation/ocr-release-jni-verification.json`：`82cb39a02549e59d7de695465693e0c608b1844443d73e2b61975343b4325727`
- `gradle.properties`：`0e175e5aec4bb16d23362bbf92c7d645fd3055b825e9033e688589e67f6128c6`
- `gradle/gradle-daemon-jvm.properties`：`6c13aa5ba36690ccfbbb25a8807d01d98c15833e446d7ae82ddc726472cb8ef7`

## 停止时尚未完成的设备验收

仅使用validation包和隔离财务数据，正式包与薄荷数据未改。OPPO PKM110/API36在本轮进入系统密码锁屏，`.local/convergence-entry-focused.log`、`.local/convergence-entry-panels-settings.log` 因无法获得窗口焦点失败；未绕过密码，也未将这些失败算通过。

已准备独立API36模拟器并安装隔离验证包，尚未运行新增交互套件即按用户要求停止并关闭模拟器。以下仍没有本轮实测通过证据：逐帧开关稳定、真实键盘切换/CJK组合输入/备注滚动、完整范围/日历、设置滚动返回、搜索草稿语义、真实QS系统授权和目标画面、截图监听及分享、最终整套回归和签名Release。

debug-only CaptureFixtureActivity 只显示合成支付样例，供MediaProjection实测目标帧；Release不包含它。正常财务页保持FLAG_SECURE，页面验收图只绘制本测试进程拥有的合成View。

## 停止状态

代码与审查记录均留在当前工作树。最新完整check通过，442项主机测试XML均无失败；随后仅补充测试隔离和API版本guard，AndroidTest编译通过。最新设备测试未完成，未再生成或验收本轮最终签名交付包，不以旧APK冒充本轮成品。未提交、推送或覆盖用户正式安装。Goal停止执行，不能把原完整验收目标宣称为达成。
