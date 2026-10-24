# DawnYew UI Correction Implementation Plan

> 本轮按subagent-driven-development分区实施。用户已授权自主决策，不再询问；root唯一执行Gradle、设备和签名。

**Goal:** 删除账号/会员与无实现空入口，补真实本地面板开关和二级页，以实采截图纠正图表与过大排版。

**Architecture:** 两个严格UserPreference保存布局；core/SQL统一聚合与精确下钻；独立DashboardController查询状态；UI消费结果，不另存财务事实。

**Tech Stack:** 现有KMP/Compose/Room/Bundled SQLite/Android，无新联网依赖。

## Gate 0：纠正审计

- [x] 读取规则与实际工作区，保留当前分支和已有变更。
- [x] 三位agent真实截图/布局审计、全路由审计、数据架构审计；root查看首页/统计/更多/我的/配置原始截图。
- [x] 三方交叉审阅纠正方案。新增星期与未分配过滤、历史余额契约、图高比例、精确下钻、删除失效对象恢复已纳入；无未关闭阻止开发的P0/P1/P2。
- [x] 冻结 `docs/implementation/2026-10-24-dashboard-api-contract.md` 的字段/default/目录ID。

## Task 1：面板配置与真实查询（data agent）

文件：core新增PanelModels/PanelQueries/PanelLayout；FinanceModels/FinanceQueries/FinanceValidation/Models/Validation；storage财务SQL投影及RoomLedgerRepository；presentation新增DashboardController。不要修改AppController/GenerationRepository，root负责。

- [ ] 先写布局、聚合和过滤测试交root执行，确认针对新行为失败。
- [ ] 实现10首页/16统计catalog与严格解析、SetPreference/full/batch统一验证，旧备份缺键不改摘要；测试新布局真实Room重开/备份往返。
- [ ] 扩展ActivityScope weekday/missing字段，参考过滤/SQL/游标一致；实现范围受限投影与core安全聚合、完整下钻；不读范围外payload。
- [ ] 实现openingAt+已入账资金历史余额请求，测试期初/转账/退款/未入账/开户日/范围边界。
- [ ] DashboardController独立请求/有限cache/过期隔离/reset/refresh；测试多个slot、版本变化和预算变更。

## Task 2：图表与密度（visual agent）

文件：Theme/FinanceComponents/HomeScreen/StatisticsScreen、新图表和PanelManagement UI。依API消费，不修改根App或业务表单。

- [ ] 用实采dp/sp和宽高比例重做默认三图与首页7日图，默认首页/统计主卡不堆明细表和额外选月高行。
- [ ] 10/16布局顺序驱动真实卡，Switch直接保存，排序真实保存，全关仍可进入管理。
- [ ] 可离线面板完整渲染、图例/轴/单位正确，各卡真实详情与下钻；标签交叠/退款不误导饼图。
- [ ] 公共卡/行密度更紧凑，大字号自然增高，不硬裁；数字格式分UI显示和精确业务值。

## Task 3：入口与完整二级页（route agent）

文件：Profile/Accounts/Obligation/Recurring/Savings/FileAndRecycle/Entry/More以及必要新详情文件；公共组件签名与visual作者沟通。

- [ ] 删除21个空目标的可见入口（线上账号/会员、AI、分享同步等以及本轮未实现项目），移除配置空页实现并转新管理页。
- [ ] 账户、借还报销、周期、存钱详情改完整页，摘要+记录+操作；非仅把对话框放大。
- [ ] 其余保留二级页逐项真实访问与命令核对；删除对象后的路由反馈/返回正确，不静默空白。
- [ ] 分类/金额表单/我的/更多对照参考压缩层次与行距，不把业务删除伪装为实现。

## Task 4：集成与设备（root）

文件：AppController/GenerationRepository/DawnYewApp/DebugNavigationItem、Android instrumentation与本轮文档。

- [ ] 平台新查询授权mutex显式代理，AppController暴露dashboard/reset/refresh；根UI传完整源scope与object id。
- [ ] 移除无内容通用fallback；主Tab顶栏/底栏按参考组织，统计主屏不重复大标题；线上入口不可达。
- [ ] 统一执行针对性core/storage/presentation测试与UI编译，修真实失败；已有业务/备份/会话契约回归。
- [ ] 真机实际点击Switch、排序、返回/重进、数据详情下钻；逐保留入口访问，截图不能代替交互断言。
- [ ] 同分辨率新图与参考逐页核对，包含主屏密度/图表及二级详情，独立非作者视觉复核。

## Gate 1：交付

- [ ] 独立交叉代码review（各作者看他人边界），修复复核关闭全部P0/P1/P2；不得重用上轮“看得到入口”结论。
- [ ] `./dev.sh check --offline --console=plain`；必要原生校验/发布脚本；签名Release/JNI/权限/对齐/SHA。
- [ ] 只安装 `.validation` 隔离测试包，不覆盖用户手机正式包，不操作薄荷数据。
- [ ] 更新实际功能/差异和本轮验证报告；旧报告标注已被用户否定UI验收。
- [ ] 确认无本轮引入的死入口、配置开关假保存、空白详情、明显布局膨胀后再完成goal。

## 追加验收项

- [x] 审查全部排序来源并统一实现长按拖动。
- [x] 四类版本化原子重排；复用Room/备份字段；测试已捕获并修复sealed type字段冲突。
- [x] Android打包原版图标与资产照片，记录源文件SHA，按原XML调整颜色与尺寸。
- [ ] 真机长按、让位、吸附、边缘滚动、取消/失败恢复及主页即时映射。
- [ ] 原资源同屏截图非作者验收，修复白图/错误映射/重复背景/比例问题。
