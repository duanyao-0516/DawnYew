# 最终独立平台、协议与离线边界审查

日期：2026-10-24。对象：分支 `p/duanyao.0516/codex/dawnyew-android-app` 当前未提交改动。审查者未实现被审模块；只读源码和已有构建产物，仅新增本文，未运行 Gradle、ADB 或操作设备。

依据：`docs/superpowers/specs/2026-10-24-offline-finance-redesign.md`、`docs/implementation/2026-10-24-finance-api-contract.md`，以及用户指定的 2026-10-20 工程归属、外部数据契约和生命周期规则。

## 结论

本次独立审查未发现新的、可证实的 P0/P1/P2 问题。此前 `PLATFORM-UI-01`、`PLATFORM-UI-02` 的实际修正已独立核对，源码层面关闭；编译、自动化与设备验证结论由主任务对应证据提供，本文不替代这些验证。

## 生产调用链核对

| 范围 | 核对内容与结果 | 代码位置 |
| --- | --- | --- |
| 前台与会话 | 前台周期条件包含 resumed、遮盖、授权、设备锁、仓库就绪、外部 picker、认证、文件任务和原生预览；协调器继续检查表单、单写门和待确认回执。SessionCoordinator 的真实 revoke 统一触发 Application 清理，合法 picker/auth/rotation 不改变 epoch。 | `MainActivity.kt:159–191,235–239`；`DawnYewApplication.kt:44–59`；`SessionCoordinator.kt:45–60`；`RecurringCoordinator.kt:64–95,133–169` |
| 失效草稿 | onRevoked 先撤销周期权限，再 resetTransientState、discardExternal、发送 revocation；reset 清草稿/筛选，不清除未确认写入回执。Activity 取消文件任务、OCR 草稿与原生预览。 | `DawnYewApplication.kt:46–50`；`MainActivity.kt:130–135`；`AppController.kt:232–243` |
| CSV 文件所有权 | CSV 临时文件在 IO 内创建时即交给外层 scratch；dialog 成功接管后才置 handedToDialog。跨 dispatcher 取消由外层 NonCancellable finally 清理；预览取消、认证取消和 picker 失效分别有文件拥有者清理。导出目标写完重读校验，失败尝试删除创建目标。 | `MainActivity.kt:335–365,447–480`；`DawnYewApplication.kt:61–70` |
| CSV 预览提交 | provider 读取前保存 session/database epoch；预览和提交再核对 dataset/contentVersion。确认提交走 sealed ImportPlan → AppController.perform → GenerationRepository → 原有事务域；重复/冲突、疑似重复人工选择与批次撤销沿用真实入口。 | `MainActivity.kt:483–528`；`ImportPreview.kt:54–119`；`GenerationRepository.kt:215–244` |
| 外部文件边界 | 每次内容打开前校验 content scheme、已确认本地 provider、系统包身份和本地卷；EXTRA_LOCAL_ONLY 不被当作唯一保障。picker payload 仅留进程内，注册 key 绑定 lease；晚到结果需匹配当前操作与数据库 epoch。 | `LocalDocumentAccess.kt:8–30`；`MainActivity.kt:83–123`；`DawnYewApplication.kt:20–38` |
| Widget | RemoteViews 只有应用名和静态动作名；显式 Intent 仅请求 EXPENSE/INCOME/OCR 导航，PendingIntent immutable。实际消费入口检查可用前台会话与仓库，Intent 不提交交易。 | `QuickEntryWidget.kt:10–26`；`MainActivity.kt:211–225`；`res/layout/widget_quick_entry.xml` |

## 协议与存储边界核对

- CSV 输入限定原始字节 32 MiB、10,000 条逻辑交易和解码字段 UTF-8 8 KiB；严格 framing/header、UTF-8、列数、空记录和引号状态。物理行仅用于定位，连续逻辑编号用于预览选择；输出在创建 SAF 目标前执行相同数量与字节边界。ledger/category/note 统一一个前导单引号，导入只去掉一个，保留原文本中的单引号和公式字符。证据：`DawnYewCsvCodec.kt:15–86,89–112`、`DawnYewCsv.kt:24–35,37–49,61–83`。
- 来源身份采用 provider/scope/type/sourceEntry 与规范内容摘要。相同来源跳过，变化来源冲突；本机同 ID 复用必须业务相同且现有匹配来源相同，不改原 Entry 或来源。仅选中 NEW 行引用的新增分类进入 CSV commit plan。UndoImport 检查成员状态、revision、完整内容摘要和外部引用，再一次性删除成员及附加关系。证据：`TemplateRegistry.kt:19–35`、`ImportPreview.kt:103–117`、`Import.kt:99–111,136–151`。
- JSON v1/v2 的源版本、源 counts 和源 digest 保持独立。根字段可乱序，边读边执行旧/新实体共有的严格标量 schema 校验，读完再检查版本对应的精确实体与 counts 集合；v1 的九类摘要不被候选 23 类计数污染。`expectedCandidateCounts` 仅为不存在的新实体补零，GenerationRepository 用它校验实际候选。证据：`JsonStreamCodec.kt:53–75,140–224`、`BackupProtocol.kt:16–22,98–115`、`GenerationRepository.kt:351–368`。
- JSON 生产路径逐记录私有暂存并外部排序，不建立完整根 JsonObject；每批最多 128 条或约 4 MiB，合并 fan-in 16，读取/排序/摘要/候选阶段均检查取消，失败清理私有目录。流对象由 caller 所有。证据：`JsonStreamCodec.kt:19–41,53–76,140–153`、`CanonicalRecordStore.kt:17–57,61–107`。
- 新 queryActivityPage/activitySummary/financeSnapshot 都在 GenerationRepository IO mutex 内检查授权后调用真实 delegate，无绕过生产 SQL 层的默认内存实现。预览 commit 比较 epoch，恢复候选比较 token，并在验证候选后再次检查授权再发布。证据：`GenerationRepository.kt:215–244,270–288,351–418`。

## 离线配置与验证限制

源 Manifest 显式移除 INTERNET / ACCESS_NETWORK_STATE，关闭平台备份与明文流量；本次读取的 debug/release 合并 Manifest 均未声明这两项网络权限。Widget 不注册周期更新（updatePeriodMillis=0），不包含金额、账本或交易摘要；入口无需账号，未发现新增网络 SDK 调用。

本文检查了现有 CSV/JSON/session/generation 单元测试与 MainActivity 生命周期回归用例源码，用于确认预期边界有对应断言，但未自行运行。合并 Manifest 是当前磁盘上的构建产物，最终 APK 权限、最新源码是否全部进入构建、SAF/认证/旋转/设备运行结果仍以主任务最终产物及日志为准。

## 最终交付证据独立复核

在主任务生成 `2026-10-24-finance-verification.md` 后，追加只读核对对应日志、XML 和本地 APK。本阶段仍未运行 Gradle、ADB 或操作设备；只对磁盘 APK 执行 aapt、apksigner verify 和 zipalign check。

- 逐个汇总当前 XML，得到 core 7 suites / 69 tests，protocol 6 / 30，storage 7 / 54，presentation 9 / 110，platform 13 / 113：合计 42 suites / 376 tests，failures/errors/skipped 均为 0。`finance-check-delivery-20261024.log` 为 BUILD SUCCESSFUL，并包含五个测试任务、UI JVM 编译、Debug/AndroidTest、Release/R8/JNI/Lint。五模块测试任务在这次最终命令中均为 UP-TO-DATE；376 是当前有效测试 XML 的汇总，不表示该次命令重新执行了全部测试。
- `finance-release-scripts-20261024.log` 明确为 13 tests / OK；测试源中的签名根使用 TemporaryDirectory，未把这些测试身份当作正式发布身份。`finance-ocr-assets-20261024.log` 通过模型与 AAR 哈希、各目标 ELF PT_LOAD=16384 检查。当前 lint XML 恰为 14 warnings、0 errors，类别数与报告一致。
- `finance-device-final-20261024.log` 有完整十项开始/成功结果、57.101 秒及 OK (10 tests)，性能状态为 PKM110 / API 36 / page50 p95=20 ms / statistics p95=66 ms。测试源证明性能样本是 10,000 条合成 Entry，实际测量 `queryEntryPage(query, null, 50)` 与 `statistics(query)`；它不证明新的统一 `queryActivityPage`、首页渲染或交互时延。建议报告将“50 行首页分页”表述为“queryEntryPage 首批 50 条”，避免混淆。
- 真机报告已区分合成数据渲染、真实 Activity 生命周期、实际 Room/OCR/native 加密与未覆盖的 SAF/认证/Widget OEM 行为。渲染测试以隔离仓库及 PreviewActions 驱动生产 Composable，不能当作所有用户点击链路通过；报告当前的图片边界说明与此一致。最新磁盘 Debug 和 AndroidTest APK 包名分别为 `com.dawnyew.app.validation`、`com.dawnyew.app.validation.test`；正式 APK 为 `com.dawnyew.app`。临时财务与性能仓库的 finally 均清理各自目录，生产 Activity 用例限制在 validation 包。
- 正式 APK 大小 52,684,926 bytes，SHA-256 `fe60c798a44ffc300fbd07030b96d7e5ae876269960b025380b5311be21b8719`，与报告及 sidecar 一致。apksigner 独立验签通过，v3、CN=DawnYew，证书 SHA-256 为 `6d4f9af5e41ffd8c98f75cd795cfaca3d8577d6fee01e641189ddc567c942cbb`，也与历史 `release-signing-build.log` 一致。zipalign `-c -P 16 4` 成功；aapt 读取正式 APK 确认无 INTERNET/ACCESS_NETWORK_STATE、allowBackup=false、usesCleartextTraffic=false、Widget receiver exported=false；native ABI 仅 arm64-v8a。
- 当前 unsigned Release SHA-256 `b67b83c5ebc8d650ffc0920bae2144da98711b4d0bc9384f681ea5528b58370b` 与最终 JNI 报告一致。正式与 unsigned ZIP 的全部共有条目逐字节哈希相同，仅正式包多三个 META-INF 签名条目；正式包使用的 DEX/资源/native 内容与接受 JNI 校验的 unsigned 包一致。JNI 报告为 129 classes / 3,006 methods / 1,352 fields、1 DEX、app obfuscation active、passed=true。
- 现存三项既有修改与开工审计记录一致：parallel 设置仍在；JDK25 daemon criteria 仍未跟踪；历史 JNI JSON 仍只将旧 apk_sha256 改为 `573764…`，本轮最新 JNI 证据位于 build/reports。HEAD 仍为 `abf4cc3`。

未发现测试计数、成包身份、权限或签名与报告不一致。已向主任务指出上述 UP-TO-DATE 和性能接口两处精度说明，由主任务统一修改交付报告；本审查者未修改该报告。完整十项设备日志与后续补采图运行分别计数，后续截图运行不替代十项结果。
