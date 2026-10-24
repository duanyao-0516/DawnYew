# DawnYew 纠正版本验证（2026-10-24）

> 最新追加反馈与当前验收进度见 [当日反馈收敛与整体审查](2026-10-24-convergence-verification.md)。下文为上一阶段记录，不证明最新截图能力、备注输入或完整日期范围已验收。

此文取代首轮 finance-verification 中关于视觉和空入口的验收结论。工作树分支 `p/duanyao.0516/codex/dawnyew-android-app`，保留原未提交改动；没有提交或推送。

## 用户反馈与修正

- 移除登录、会员、消息、AI、同步等在线/空入口；资金账户保留真实离线业务。没有分期/多人模型的入口不展示，不以空白页面冒充实现。
- 首页10种和统计16种面板采用开关+长按排序；首页按同一个持久化 PanelLayout 立即渲染，无额外保存动作。未入账提示独立于用户面板开关。
- 账本、分类、项目、商家、标签、同账本同类型资金账户统一长按排序；隐藏/归档成员保留原槽，完整组一次原子提交。
- 主页面板、统计面板、分类管理、账本与项目/商家/标签管理共用 ReorderSettingRow；统一行内边距、字体、圆角、紧凑开关和菜单，账本/维度使用无开关变体，不新增虚假启用业务。
- 当前拖动项和落位项关闭 Lazy item 的 placement 动画，只让邻项播放让位动画。松手同步保留当前可见位置并向空槽落位，取消/写失败从当前显示位置恢复；避免跟手位移和列表位移动画叠加。
- 设置、我的、更多使用各自 SaveableStateProvider 保存列表 index+offset；进入二级页再返回保持离开前位置。状态仍由数据集与授权 generation 隔离。
- 记一笔取消独立金额前置页：金额、分类、账户、标签与固定计算键盘/保存同页；日期、备注及低频选项按需展开。普通尺寸首屏无滚动；小窗口、大字、OCR 警告保留正文滚动及系统键盘降级。
- 账户/借还/周期/存钱详情为真实二级内容页，保留原写入与编辑动作；存钱去除不属于原版的大环，账户和借还摘要去重复，关键记录前置。
- 直接复制原 APK 的34个唯一图片文件（40项用途），包括底部导航、更多、我的、资产游艇背景；全部源文件/目的文件 SHA-256 与清单一致。原 XML 中图标盒/padding、颜色、间距作为 Compose 实现依据，未声称把依赖原 Activity/ViewModel 的 XML 直接当完整可运行页面。
- 两个可换月的首页面板都关闭时补紧凑公共月份入口；统计详情的 home/tab 保存状态分离，首页新访从卡片默认收支类型开始，下钻返回仍保留当前选择。

## 构建和主机测试

单页记账首轮 `./dev.sh check -PdawnyewIsolatedDeviceTest=true --offline --console=plain`：`.local/correction-check-entry-final.log`，BUILD SUCCESSFUL（28秒）；包含 Debug、AndroidTest、R8 Release、Lint、OCR JNI 和六模块主机测试。随后实机发现逐键覆盖问题，正在修复和重新验证；此构建通过不能代替最终设备结果。

| 模块 | 测试数 | failures / errors / skipped |
|---|---:|---|
| core | 78 | 0 / 0 / 0 |
| protocol | 30 | 0 / 0 / 0 |
| storage | 57 | 0 / 0 / 0 |
| presentation | 115 | 0 / 0 / 0 |
| UI | 9 | 0 / 0 / 0 |
| Android platform | 114 | 0 / 0 / 0 |
| 合计 | 403 | 0 / 0 / 0 |

数量来自当前 test-results XML；Gradle 会复用未变更任务，不声称每次 check 强制重跑403项。新行为覆盖完整面板查询、旧scope结果隔离、预算/退款口径、历史余额、原子重排与隐藏成员合并等。新增 ReorderAccounts 的 `type` 与序列化 discriminator 冲突曾在真实测试失败，改为 `accountType` 后通过；没有改全局旧协议 discriminator。

JNI：129 classes / 3006 methods / 1352 fields，1 DEX，R8 混淆启用。历史 docs/implementation/ocr-release-jni-verification.json 保留初始改动，报告文件 SHA-256 `82cb39a02549e59d7de695465693e0c608b1844443d73e2b61975343b4325727`；最新构建证据写 build/reports。

## 设备验证与边界

仅安装 `com.dawnyew.app.validation` 与测试包，OPPO PKM110 / API36 / 1080×2373。未覆盖用户正式包、清除用户数据或操作薄荷记账数据。测试中只对准确的测试包弹窗选“仅本次允许”。

21张页面图在 `.local/correction-ui-final/`；全部由合成财务数据的测试 Activity View 在进程内绘制，保留 FLAG_SECURE，不是绕过系统安全截图。包括5个主Tab、真实详情、表单、流水、面板配置、深色我的及共享管理行。单页记账图另放 `.local/correction-entry-final/`，等待修复后重新采集。

连续动画采集在测试进程内缓冲帧，松手后才PNG编码，避免编码耗时掩盖瞬间位置变化。修复前的慢采样和停指采样也曾通过最终顺序/宽松位置断言，因此不能声称该断言已稳定复现用户报告的所有多余动画；修复依据是用户实测、Compose 本地源码的 placementDelta 路径及独立审阅，修复后的帧需单独验收。

组件统一前基线：`.local/correction-device-before-shared-row.log`，OK (12 tests)，175.053秒。已通过16统计详情、8更多页、17我的入口，六类管理真实拖动、取消不写入、主页立即同步顺序、设置→备份→返回y=1869保持、备份恢复面板配置、60帧拖动/落位、旧备份、OCR、native加密及离线manifest。新共享行调整后需重跑涉及UI的验证，以下最终结果仍待补入。

中途失败也保留：共享行调整后的12项整套曾在分类拖动等待和Activity销毁处失败；增加手势DOWN前等待busy/pending空闲后，`.local/correction-management-focused.log`单项39.439秒通过全部管理拖动、分类开关/菜单/下钻及设置返回。该结果不证明原失败根因。记账初轮 `.local/correction-entry-diagnostic.log` 实证依次点1、2、点、3、4得到1、2、0.、3、4；独立javap审阅确认局部函数引用的equals忽略捕获draft，键Surface记忆了首次回调，修复不能仅改变游标。

## 独立审查

方案与接口先交叉审；实现由 UI、路由、数据、root 分工，再由非作者审查。独立验收发现并关闭原图映射错误、资产背景、图标尺寸/深色着色、存钱详情密度、账户/借还详情密度；后续关闭月份入口、统计来源状态、拖动双placement、失败回退及设置返回位置的源码问题。具体记录见 correction-independent-acceptance、dashboard-delivery-review、correction-review-route-cross。

未宣称所有原版功能/所有筛选组合/全部OEM或逐像素一致。分期、多人以及在线能力没有本地模型即不提供空入口；真实签名Release逐页交互、所有SAF/Widget/生物认证设备、200%字体/TalkBack/横屏等不由本次单设备截图代替验证。
