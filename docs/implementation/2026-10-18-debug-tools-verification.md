# Debug 测试数据工具验证记录

日期：2026-10-18。长按底栏「我的」进入 Debug 二级页，普通点击仍打开我的，从 Debug 返回时回到我的。

## 使用

- 默认目标为未归档的「日常」，日期为含今天的近 30 天，数量为 100；日常不存在时显示首个可写账本。
- 可切换账本，选择近 7 天、本月至今或任意起止日期，输入 1–10,000 条。结束日期不能晚于本机今天；没有启用分类的账本拒绝生成。
- 生成数据按日期覆盖范围首尾，数量为 1 时取开始日期。使用目标账本启用的收支分类；两类齐全时约 4:1 的支出/收入，仅有一种方向时全部使用该方向。金额、时刻与分类有变化，当前日期不会写入晚于当前时刻的记录。
- 备注标注 `[DEBUG 测试]`，参与正常明细、首页、统计及备份。数据为可重复的合成分布，不代表真实收支或真实支付来源。
- 页面提供本地数据概览和测试批次列表，可撤销未改动的整批测试数据。批次记录被修改、删除、移动或新增关联退款后拒绝整批撤销；其余正常账单不受影响。

## 写入与恢复

`GenerateDebugEntries` 使用现有仓库写入事务，内部复用导入计划校验和批次成员模型；无需新数据库 schema 或平台能力。批次 adapter 为 `dawnyew.debug.seed`，没有伪造文件或支付来源；origin 复用 IMPORT，备注及 adapter 区分测试数据。

每次生成是一次命令、一次版本递增、一次原子提交。回执与批次 submissionId 支持重复请求幂等；回执缺失时仍可从测试批次查询结果，参数冲突会拒绝。撤销复用既有 `UndoImport` 成员版本、内容摘要和关联完整性校验。

## 自动化与交叉 review

最终执行 `./dev.sh check --offline --console=plain`：**BUILD SUCCESSFUL in 36s**。涵盖主机测试、UI JVM、Debug/AndroidTest APK、优化 Release、OCR JNI、Release Lint。日志 `.local/debug-tools-check.log` 不提交。

| 模块 | 测试通过 |
| --- | ---: |
| core | 50 |
| protocol | 16 |
| storage | 43 |
| presentation | 82 |
| platform Android | 104 |
| 合计 | **295** |

0 失败、0 错误、0 跳过。新增 9 个核心和 4 个 Room 测试，先观察失败后实现；覆盖：

- 精确条数含 10,000 条、首尾日期、闰年、午夜、单方向分类、目标账本隔离与参数拒绝。
- 相同操作重复提交不重复生成，回执缺失后的幂等及 lookup 恢复，普通导入不能被误判为测试批次。
- 真实 Room 并发同操作提交、关闭重开后的持久化、写入失败完整回滚，以及撤销保留正常账单、被修改批次拒绝且原子不变。

Release Lint 0 error、7 个既有 warning；OCR JNI 129 classes / 3006 methods / 1352 fields 通过，应用混淆有效。签名与 16KB ZIP 对齐校验通过。

领域实施 Agent 与主线程分工，手势审查发现并修正了 visual NavigationBarItem 的重复键盘焦点。手势采用完整 tab 覆盖层的 combinedClickable，内部 Material 控件只负责视觉和反馈，由 combinedClickable 统一区分长按与单击。独立最终 reviewer 重读稳定变更，未发现确定 P1/P2；静态审查不替代实际设备交互测试。

## 安装与人工边界

已以 `adb install -r` 覆盖安装至已连接 PKM110，返回 Success。未卸载或清空应用数据，未在手机中实际生成测试账单，未操作应用或追加设备 instrumentation，沿用用户此前无需继续真机验证的指示。

用户可自行验证：长按图标/标签打开 Debug，短按仍打开我的；检查默认值、日期选择、生成后首页/统计刷新，以及撤销后恢复原数据。大字号、键盘/D-pad 焦点和无障碍长按入口留作人工交互检查。

本地开发签名 APK：`artifacts/releases/DawnYew-0.1.0-debug-tools.apk`，52,041,686 bytes。

SHA-256：`c43f180dd8ea914c3b49853243cc801d261e48894f791fe8cf8e1b800eb866d4`。

APK、签名附属文件、测试日志继续忽略上传。工作过程中另外出现的 `gradle.properties` 与 `gradle/gradle-daemon-jvm.properties` 配置改动原样保留，未纳入本功能提交；完整检查在当前工作区执行。
