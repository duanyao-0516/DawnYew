# DawnYew Android 开发与设备验收报告

日期：2026-10-18。**本轮 Android 实现、构建、安装和可执行设备验证完成**。真机发现的业务拒绝提示及优化 Release OCR 崩溃均已修复、交叉复核并重测。手机保留可运行的最终优化包，测试数据已清空。正式发布签名、多厂商/真实 OCR 语料和人体认证等门槛仍见人工清单，不作为已通过结论。

说明：文中 `artifacts/`、`.local/` 和 `build/` 链接是本机验证证据，按 `.gitignore` 排除，不随源码上传 GitHub；可公开的产物摘要保存在 `android-delivery-artifact.json`。

最终安装包：[DawnYew 0.1.0 arm64 开发签名 Release](../../artifacts/releases/DawnYew-0.1.0-arm64-dev-release.apk)，51,619,797 bytes（约 49.2 MiB）。SHA-256：`204a972125e7dc8b6dc27968b5de8fa39c87e62a7825bf7381a117a43a21ace4`。已读取手机实际安装的 base.apk 校验，哈希与交付文件一致。

本报告汇总主线程实际设备操作、已有测试输出及独立代码复核；整理报告的 agent 未另行操作设备或运行构建。所有账务与截图测试均使用本轮合成数据，不使用用户真实账单或个人认证凭据。早期工作日志中的 207 项主机测试、22.393 秒设备测试和 35 ms 分页结果属于先前批次；下文采用最新证据，保留不同批次的边界。

## 范围与测试环境

| 项目 | 本轮范围或环境 |
| --- | --- |
| 产品 | Android 离线记账；KMP 领域、存储、协议、presentation 与 Compose UI |
| 设备 | PKM110，Android API 36，arm64-v8a；单台连接真机 |
| 数据 | 合成账本、支出、退款和收入；设备性能用隔离的 10,000 条合成流水 |
| 安装 | 已执行 Debug 安装、覆盖升级与开发密钥签名的优化 Release 覆盖安装；最终包覆盖安装成功，数据保留 |
| iOS | 仅接口契约；未开发、未编译、未进行设备验收 |
| 模板 | 微信、支付宝、DawnYew 三类具体适配器按授权延期；生产入口明确等待模板 |
| 签名 | 本项目本地开发密钥用于设备测试，不能视为正式分发签名 |

需求与技术基线见[需求设计](../superpowers/specs/2026-10-18-dawnyew-requirements-design.md)、[技术设计](../superpowers/specs/2026-10-18-dawnyew-technical-design.md)及 [UI 规范](../superpowers/specs/2026-10-18-dawnyew-ui-interaction-standard.md)。

## 自动化与构建证据

| 验证 | 最新已确认结果 | 证据与边界 |
| --- | --- | --- |
| 主机测试 | **211 项通过，0 失败、0 错误、0 跳过** | core 30、protocol 16、storage 39、presentation 38、platform 88；读取各模块现有测试 XML 汇总 |
| 整包构建 | **BUILD SUCCESSFUL in 34s** | [final-delivery-build.log](../../.local/final-delivery-build.log)，包含 Debug、AndroidTest、优化 Release 与 lint；完整构建后仅补 OCR consumer rules 与产物检查；受影响 Release 再次构建并通过 `verifyReleaseOcrJni` 和 `lintRelease`，详见 [R8 修复记录](ocr-release-r8-fix.md) |
| Release lint | **0 error、7 warning** | [lint-results-release.xml](../../androidApp/build/reports/lint-results-release.xml)；不表述为零告警 |
| Android instrumentation | **6 tests，20.656s，通过** | [delivery-instrumentation.txt](../../artifacts/device/delivery-instrumentation.txt)；Debug/instrumentation，不能替代优化 Release OCR 验证 |
| Release 权限检查 | 最终包未含 INTERNET / ACCESS_NETWORK_STATE | [final-release-permissions.txt](../../artifacts/device/final-release-permissions.txt)；已对最终签名包重新检查 |
| Release 签名与 ZIP 对齐 | 最终签名包校验及 APK 16KB ZIP 对齐通过；6 个 arm64 ELF 的 PT_LOAD 对齐均 ≥16KB | [signature](../../artifacts/device/final-release-signature.txt)、[zipalign](../../artifacts/device/final-release-zipalign.txt)；不是实际 16KB 页设备运行证明 |

主机测试 XML 位置：`shared/core/build/test-results/jvmTest/`、`shared/protocol/build/test-results/jvmTest/`、`shared/storage/build/test-results/jvmTest/`、`shared/presentation/build/test-results/jvmTest/`、`platform/android/build/test-results/testDebugUnitTest/`。最后的 OCR 修复未修改业务代码、模型和 native 库；其专属验证是实际 Release DEX 门禁、Release 构建/lint 及最终签名包真机 OCR 全流程。

6 项设备 instrumentation 覆盖：安装包网络权限及自动备份配置；10,000 条真实 SQLite 查询；流式候选隔离与过期预览拒绝；内置 native OCR 合成金额识别；代际替换、重开与持久操作重放；真实 libsodium 加密往返及篡改拒绝。

## 真实设备功能结果

| 场景 | 已执行操作与观察结果 | 验证批次或限制 |
| --- | --- | --- |
| 首次启动与持久化 | 首次安装后打开成功；保存支出 32.50，退出/覆盖安装后记录保留 | 早期 Debug 与后续覆盖安装；没有以首次启动成功代替飞行模式完整验收 |
| 退款金额规则 | 对 32.50 支出尝试退款 50.00 被拒绝；退款 5.00 成功，净支出 27.50 | 早期设备闭环；确定业务拒绝的提示缺陷见下文 |
| 账本管理 | 最后一个可用账本归档被拒绝；创建、归档、取消归档、重命名和删除空账本成功 | 设备实测 |
| 移动与删除恢复 | 支出移动到 `Travel-Verified` 后关联 5.00 退款同行；删除后形成两笔删除组，整组恢复后金额回到 27.50 | 设备实测；未把此项扩展为全部故障注入场景通过 |
| 收入与连续记账 | 优化 Release 新增收入 15.00；保存并继续保留“收入 / 奖金 / Travel-Verified”；再保存收入 2.00 | 主线程最新 Release 操作记录 |
| 编辑 | 第一笔收入从 15.00 改为 18.00，保留第二笔收入 2.00 | 主线程最新 Release 操作记录 |
| 统计五指标 | 收入 **20.00**、原始支出 **32.50**、退款 **5.00**、净支出 **27.50**、结余 **-7.50** | 与本轮四条有效流水一致 |
| 月预算 | 预算 **100.00**，已用 **27.50**，剩余 **72.50** | 设备实测；前期 JSON/加密恢复后也核对过预算保留 |
| 搜索和筛选 | 备注搜索、类型筛选与无结果状态已操作；分类下钻显示对应 **1 笔**记录 | 主线程最新 Release 操作记录 |
| 分类管理 | 重命名、置顶、停用及删除未引用分类均成功；删除被引用分类保持数据并准确提示停用 | 最终 Release 对业务拒绝重测通过 |
| 永久清理 | 将 2.00 收入移入回收站，再确认永久删除，回收站为空 | 修复后的设备构建实测 |
| 外观与清空 | 深色选中、恢复跟随系统；清理保护副本，清空取消后金额不变，确认后默认空账本 | 最终 Release 清空成功，冷启动保持空账本 |
| JSON 导出/恢复 | 系统文件选择器导出并回读校验；最终 Release 清空后选择该文件，预览 2 账本/4 流水，确认后净支出 27.50、4 笔 | [DawnYew-Final-Device.json](../../artifacts/device/DawnYew-Final-Device.json) 为本轮合成数据证据 |
| 加密导出与恢复 | 加密备份导出并回读成功；正确口令恢复后 **4 条有效流水** | 最新设备闭环；早期批次另验证错误口令被拒绝、原数据保留 |
| 旧保护副本 | schema 2 保护副本可打开预览，显示 **2 条有效流水**；用户流程取消，未执行替换 | 不把预览取消记作已恢复旧副本 |
| pending 保存恢复 | 在月份选择提交窗口强杀，重启实际显示“已提交”pending 提示；明确确认后恢复 **2026-09** | 真机验证了跨进程结果提示与确认，不只是内存模拟 |
| 模板入口 | 微信、支付宝、DawnYew 三类均显示等待模板 | 不宣称具体历史账单导入或业务 XLSX 导出已可用 |

上述统计数值只说明测试账务一致，不代表账户余额：`32.50 - 5.00 = 27.50`，`20.00 - 27.50 = -7.50`，`100.00 - 27.50 = 72.50`。

## schema 2 → 3 升级与保护副本

真机覆盖安装通过候选数据代完成 schema 2 → 3 升级，没有原地覆盖旧代。升级前活动代为 schema 2、2 条记录；升级后新的活动代为 schema 3、2 条记录，升级前活动代成为 schema 2 保护副本，仍含相同数量的记录。

| 时点 | 活动代 | 保护副本 |
| --- | --- | --- |
| 升级前 | schema 2，2 条 | schema 2，2 条 |
| 升级后 | schema 3，2 条，新 generation | schema 2，2 条，等于升级前活动 generation |

证据：[pre-candidate-upgrade.json](../../artifacts/device/pre-candidate-upgrade.json)、[post-candidate-upgrade.json](../../artifacts/device/post-candidate-upgrade.json)。随后新增和编辑收入形成四条有效流水，因此“升级保留 2 条”与“最终备份恢复 4 条”是不同操作时点，不矛盾。

## 性能结果与适用边界

最新设备 instrumentation 在 PKM110 / API 36 的真实 SQLite 上运行 **10,000 条合成流水、20 次**，得到：

| 测量 | P95 |
| --- | --- |
| 50 条分页查询 | **27 ms** |
| 月度统计查询 | **86 ms** |

原始汇总位于 [delivery-instrumentation.txt](../../artifacts/device/delivery-instrumentation.txt)。这是 **Debug / instrumentation 的查询耗时**，不包含完整冷启动、用户保存或界面月份切换，也不是技术方案要求的固定 Android 12 / 6GB / 骁龙 778G 级设备、Release 包、各 100 次的正式性能验收。不能据此宣称冷启动 P95 ≤2s、保存 ≤300ms、切月 ≤500ms 已通过。

## OCR、应用锁与交互的已验证部分

- Debug 设备 instrumentation 已通过真实 native 模型的合成 `12.34` 金额识别。早期真实系统文件选择器 → 合成支付截图 → 待确认草稿流程识别出金额、日期时间及商户，没有自动保存。
- 早期 OCR 草稿经历竖屏 → 横屏 → 竖屏后，金额、账本、日期时间和未确认状态保留；系统返回触发丢弃确认，取消保留草稿。字体倍率 2.0 时相关字段和保存按钮可滚动到达；设备原字体与旋转设置已恢复并回读。
- 本机系统强指纹提示和系统密码后备按钮已出现；取消认证后应用锁开关保持关闭。未输入设备持有人的凭据，未修改生物识别设置，因此成功认证、已启用锁的全部生命周期仍待人工验证。
- UI/presentation 已经独立复核字段级草稿更新、连续记账保留选择、零支出分类下钻、pending 明确确认及偏好写入队列；该轮未发现新的 P1/P2。38 项 presentation 主机测试通过，不替代所有屏幕和辅助技术验收。

最终签名优化 Release 已真实选择同一合成 PNG，识别金额 **12.34**、日期时间和商户“测试咖啡店”。未勾选核对时不能保存；勾选后净支出 **27.50 → 39.84**。再次选择同图，同时提示相同图片摘要和同日同额，两个确认项默认未选；明确双确认后才保存第二笔，净支出 **52.18**。这证明真实原生模型、R8/JNI、草稿、查重和明确提交链路可运行，不证明真实支付截图准确率。证据：[识别草稿](../../artifacts/device/final-release-ocr-draft.xml)、[重复提示](../../artifacts/device/final-release-ocr-duplicate.xml)。崩溃日志复查仅保留修复前 16:48 的一条 native 崩溃，无本轮修复后新增记录。

## 本轮真机发现与处理状态

| 问题 | 已确认现象与处理 | 当前状态 |
| --- | --- | --- |
| 确定业务拒绝误显示 pending 提示 | 原本可以明确拒绝的业务操作触发待确认弹窗；已修复，相关主机测试与只读复核通过 | 最终签名 Release 原路径通过，显示“已有账单引用该分类，可改为停用”，无错误 pending 弹窗；[证据](../../artifacts/device/final-release-business-rejection.xml) |
| 优化 Release OCR 崩溃 | 自建 ONNX Runtime 产物缺少 consumer rules，优化后执行 `OrtSession.run` 出现 JNI `java_class == null` 并 SIGABRT；证据见 [release-crash-buffer.txt](../../artifacts/device/release-crash-buffer.txt) | 已补 ORT/OpenCV consumer keep 规则，旧 APK 被 DEX 检查拦截，新 APK 的 129 类/3006 方法/1352 字段全部保留；最终签名 Release 两次识别和显式入账通过；[修复记录](ocr-release-r8-fix.md) |

其他跨模块审查与修复记录见[交叉审查闭环](cross-review-resolution.md)、[OCR/UI 状态复核](ocr-ui-state-review.md)。代码审查关闭不等于最终 APK 已完成运行验收。

## 授权延期及人工验收

详细步骤见[人工验收清单](manual-acceptance-checklist.md)。以下继续保留，未勾选项不得报告为通过：

- 设备持有人完成强生物识别/设备凭据；已启用锁的冷启动、后台返回、分享、文件流程及认证配置变化。
- 至少 240 张获得授权且脱敏的真实 OCR 样本、危险负样本、三家厂商且包含无 GMS，首次断网、准确率、拒绝率与完整录入耗时。
- 实际 16KB 内存页设备加载全部 JNI；支持版本和多厂商 SAF、系统级故障、网络/DNS 观察及云备份/设备迁移排除。
- 规定基准机上的 Release、20 账本/10,000 流水、100 次冷启动/保存/切月性能；完整 TalkBack、字号、布局与首次用户可用性评测。
- 正式分发签名配置和保管；本轮开发密钥不代替正式签名。
- 具体模板适配器等待用户提供样本、字段语义和预期结果；之后再验收业务导入/导出完整界面以及 Excel、LibreOffice 互操作。

iOS 本轮仅保留接口。模板延期与上述人工门槛均为明确边界；仍可实现且已承诺的 Android 功能问题不得借此转入人工清单。

## 交付文件与设备清理

| 项目 | 最终结果 |
| --- | --- |
| APK | `artifacts/releases/DawnYew-0.1.0-arm64-dev-release.apk`，版本 0.1.0 / code 1，Android 11+、arm64 |
| 包 SHA-256 | `204a972125e7dc8b6dc27968b5de8fa39c87e62a7825bf7381a117a43a21ace4`，与手机实际安装包一致 |
| 签名 | 项目 `.local/signing/` 开发密钥，v3 签名通过；证书 SHA-256 `c199e00ba667927769ace89ff76e257849f7ec2b7ed94792bb8b01da7672d178` |
| 验证清单 | [产物信息](android-delivery-artifact.json)、[设备终态](../../artifacts/device/final-device-state.json)、[崩溃复查](../../artifacts/device/final-crash-audit.txt) |
| 最终恢复回归 | 从空账本恢复 JSON 后 4 笔、净支出 27.50；[设备证据](../../artifacts/device/final-release-json-restored.xml) |
| 手机最终数据 | 已通过应用“清空全部数据”删除本轮合成账务/回收站/保护副本；冷启动验证默认“日常”、0 笔；[证据](../../artifacts/device/final-empty-app.xml) |
| 外部测试文件 | 仅删除本轮创建的备份、合成 PNG 与 UI dump，逐一检查不存在；未操作设备原有备份文件 |
| 辅助包与设备设置 | 已卸载 `com.dawnyew.app.test`；保留主应用；font_scale=1.0、旋转 lock 0；未改生物识别配置 |
| 工程隔离 | Git 身份为项目 local 配置 duanyao / yaoduanink@163.com；缓存/开发签名在 `.local/`，未修改全局 Git/SSH 配置 |

构建日志、设备层级及合成数据证据保存在忽略提交的 `.local/`、`artifacts/device/`；主应用源码、依赖锁和验证脚本留在当前项目。未创建正式发布签名、未上传商店、未推送远端。本轮开发交付完成，人工清单保留为后续验收入口。
