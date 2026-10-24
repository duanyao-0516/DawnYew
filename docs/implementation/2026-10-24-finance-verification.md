# DawnYew 离线财务改版交付与验证

> 历史记录：本页对应用户已否定的首轮 UI 交付，不能作为当前视觉、空入口或交互验收依据。后续纠正以 [纠正版本验证](2026-10-24-correction-verification.md) 和独立验收记录为准。

日期：2026-10-24。分支：`p/duanyao.0516/codex/dawnyew-android-app`，起点 `abf4cc3`。实现保留在当前工作树，未提交或推送。

## 交付范围

依据薄荷记账 12.3.3 实采资料，完成首页、账户、统计、更多、我的五页及本地财务链：八类账户、收支/退款/转账、借入/借出/报销与人员、八种周期、四种存钱场景、预算与额度转移、项目/商家/标签、流水筛选和范围/日汇总、CSV、快捷记账 Widget。原有本地 OCR、JSON/加密备份、保护副本、应用锁、回收站、工具箱继续保留。

这是独立实现的离线版本。登录、会员、分期及其他受限/在线目标保留标题、返回和空内容，不模拟登录、购买或业务成功。完整范围、可达空页、与参考差异见 [功能覆盖矩阵](2026-10-24-feature-coverage.md)。不宣称全部参考面板、所有设置或逐像素复制。

- [技术方案](../superpowers/specs/2026-10-24-offline-finance-redesign.md)
- [实施计划与验收门](../superpowers/plans/2026-10-24-offline-finance-redesign.md)
- [API 与数据契约](2026-10-24-finance-api-contract.md)
- [决策和审阅关闭记录](2026-10-24-finance-review-resolution.md)

## 多 agent 审查

先完成三位 agent 的设计交叉审阅，18 个设计条目修订并复核关闭后进入开发。实现中按 core、storage/protocol、presentation/platform、UI 分区负责，再由非作者交叉检查。

| 独立复核 | 结果和记录 |
| --- | --- |
| core/protocol，由 storage 作者审 | 已关闭周期候选账户校验应使用下一期日期的问题；[记录](2026-10-24-code-review-core-protocol-by-storage.md)。 |
| storage/备份/前台周期，由 core 作者审 | 未发现未关闭的 P0/P1/P2；[记录](2026-10-24-code-review-storage-by-core.md)。 |
| presentation/platform，由 UI 作者审 | CSV prompt cancellation 暂存清理、授权 epoch 撤销清草稿两项 P2 已修，源码复核与单元/真机回归通过；[记录](2026-10-24-code-review-presentation-platform-by-ui.md)。 |
| fresh 最终 UI 审阅 | UI-R1 周期切转账隐藏字段、UI-R2 下钻来源账本、UI-R3 新建维度自动关联三项 P2 均关闭；追加独立图片复核；[记录](2026-10-24-final-ui-review.md)。 |
| fresh 最终平台审阅 | CSV、SAF、授权、备份、代际代理、Widget 和离线边界未发现新增 P0/P1/P2；[记录](2026-10-24-final-platform-review.md)。 |

最终实现审查没有未关闭的 P0/P1/P2。视觉审查另外修复存钱卡片的四字标题与场景图标、我的 Tab 选中颜色、虚拟账户展示来源名称；UI-V1/V2/V3 已由原审查者对最新图片复核关闭。没有为展示名称改写原有财务标识或历史载荷。

## 构建与主机测试

最终生产代码运行 `./dev.sh check --offline --console=plain` 成功；日志 `.local/finance-check-delivery-20261024.log`。包含全模块测试、Compose JVM 编译、Debug/AndroidTest APK、R8 Release、Release Lint 和 OCR JNI 定义校验。没有排除任务、清缓存或修改依赖版本绕过失败。最终 check 的五模块测试任务为 UP-TO-DATE，以下统计来自本轮先前实际执行后仍有效的当前 XML，不表示该次命令强制重新执行了376项；新增回归分别由对应模块测试或 `.local/finance-check-final-20261024.log` 实际执行。

| 模块 | XML suites | 测试数 | failures / errors / skipped |
| --- | ---: | ---: | --- |
| shared/core | 7 | 69 | 0 / 0 / 0 |
| shared/protocol | 6 | 30 | 0 / 0 / 0 |
| shared/storage | 7 | 54 | 0 / 0 / 0 |
| shared/presentation | 9 | 110 | 0 / 0 / 0 |
| platform/android | 13 | 113 | 0 / 0 / 0 |
| **合计** | **42** | **376** | **0 / 0 / 0** |

测试报告位于各模块 `build/test-results/jvmTest`，Android 平台模块为 `build/test-results/testDebugUnitTest`。关键覆盖包括：

- 资金金样例、未入账与预算分离、退款父项资格、虚拟账户归属、往来超额、存钱非负/愿望原子联动、分类预算转移。
- 八种周期中的月末/闰年/季度锚点、工作日/周末、暂停恢复、未来期拒绝、跳过消耗、删除不重建、100 期前台限额、失败不推进、精确回执确认。
- schema 1/2/3→4、完整新财务图持久化、SQL 筛选/分页及预算父项 JOIN、恶意候选/冗余索引与 payload 不一致拒绝。
- 旧 v1 九实体原摘要校验、v2 二十三实体流式恢复、根字段乱序、超过 20 MiB/12,001 笔的生产 JSON 链、恢复预览版本/代际失效。
- CSV RFC 引号/换行与逻辑记录号、公式文本前缀、边界、来源幂等/冲突、只创建实际选中新行引用的分类、导入预览及撤销保护。
- 精确金额计算器、月/年/预算独立查询、异步旧结果隔离、来源账本、草稿金融字段与新建维度关联、授权撤销。

额外执行：

| 命令/产物 | 结果 |
| --- | --- |
| `python3 scripts/build-ocr-verify.py` | 模型/AAR 哈希、无遥测原生产物、各目标 ELF PT_LOAD 16 KiB 校验通过；`.local/finance-ocr-assets-20261024.log`。 |
| `python3 -m unittest discover -s scripts/tests -p 'test_*.py' -v`，明确 JDK17/ANDROID_HOME | 13 项发布脚本回归通过，使用临时测试密钥；`.local/finance-release-scripts-20261024.log`。 |
| Release JNI | 129 classes / 3,006 methods / 1,352 fields，1 DEX，R8 应用混淆已启用；报告 `build/reports/ocr-release-jni-verification.json`。 |
| Release Lint | 0 errors，14 warnings：HardcodedText 7、UnusedAttribute 3、UseTomlInstead 3、ChromeOsAbiSupport 1；未关闭 lint 检查。 |
| `git diff --check` | 通过。 |

第一次 UI 编译的可空类型问题、旧缓存用例口径、CSV 分类裁剪等真实失败均修复后重跑。一次 Kotlin 增量编译器内部状态错误用禁用增量的诊断构建确认，再恢复正常增量构建；没有修改业务语义绕开校验。

## 真机验证

设备：OPPO PKM110，Android API 36，arm64。原设备已有不同签名的正式 DawnYew。使用仅 Debug 生效的 `-PdawnyewIsolatedDeviceTest=true`，安装 `com.dawnyew.app.validation` 和测试包；没有卸载、覆盖或清除原 `com.dawnyew.app` 数据，也没有操作薄荷记账数据。

最终 `.local/finance-device-final-20261024.log`：**OK (10 tests)**，57.101 秒。

1. 新财务图实际 Room 写入→重开→生产 JSON 备份→候选恢复→删除/恢复交易，金额与关系一致。
2. 旧九实体 v1 备份通过当前生产代际恢复，新实体为空，源摘要/当前候选计数分开处理。
3. 五 Tab、账户/存钱详情、周期、预算、账单表单、流水与受限空页，以真实数据库合成数据渲染；保持 `FLAG_SECURE`。
4. 实际 Activity 旋转保留草稿、普通后台和显式授权撤销清草稿；CSV 导出在 0/5/25 ms 取消后，无残留 CSV 暂存和 external lease。
5. 成包无网络权限、自动备份禁用。
6. 10,000 笔真实 SQLite 数据，20 次查询：`queryEntryPage` 首批50条 p95 **20 ms**，统计 p95 **66 ms**；是本设备观察值，不是新 `queryActivityPage` 或首页3笔查询的基准，也不是全机型性能承诺。
7. 原有流式候选隔离和旧预览拒绝。
8. 真实随包 OCR 模型加载并识别合成金额。
9. 真实 generation 重开和持久操作幂等。
10. 真实 native 加密备份先认证后输出。

中间一次生命周期失败由 OPPO 的测试包启动确认使目标停在 PAUSED 导致。重跑时仅对准确的 validation.test 弹窗选择“仅本次允许”，没有开启长期允许或关闭系统安全策略。其后台冻结测试进程也通过让隔离验证应用保持前台完成数据库测量；最终十项全部通过。首轮恢复断言仅因规范备份重排实体顺序失败，改为按 entryId 比较关系内容，未改生产恢复逻辑。

## 图片与验证边界

15 张最新图片位于 `.local/finance-device-ui-verified/`，配套索引 `index.html`。追加无弹窗存钱列表和会员空页后，单独渲染回归再次通过（`.local/finance-device-visual-final-20261024.log`，OK 1 test）；没有生产代码变动。数据均带 UI测试 标记，来自隔离测试数据库；不是用户财务数据。

采图通过测试进程内可见 Activity/Dialog View 渲染，未关闭生产 `FLAG_SECURE`。旧 pass1/pass2 仅画 Activity 导致详情弹窗缺失，最终已包含独立 Dialog root，并对账户/存钱详情断言窗口存在。截图证明所采视图的内容和排版，不等于系统合成屏幕、全部点击路径或完整无障碍验收。

未宣称完成：真实签名 Release 的逐页设备交互/真实支付截图 OCR 准确率、所有 OEM 的 SAF/生物认证/Widget pin、全筛选组合、200% 字体/TalkBack/横屏、16 KiB 真实内核设备和 DNS/网络抓包。已验证最终 APK 不请求 INTERNET 或 ACCESS_NETWORK_STATE；受限入口没有网络实现。

## APK 与原工作区保护

`./dev.sh release --offline --console=plain` 成功，沿用本地既有正式密钥，未创建新身份。日志 `.local/finance-release-final-20261024.log`。

- 成包：`artifacts/releases/DawnYew-v0.1.0-arm64-v8a.apk`，52,684,926 bytes。
- SHA-256：`fe60c798a44ffc300fbd07030b96d7e5ae876269960b025380b5311be21b8719`；同目录 `.sha256`。
- 签名：APK v3，CN=DawnYew，证书 SHA-256 `6d4f9af5e41ffd8c98f75cd795cfaca3d8577d6fee01e641189ddc567c942cbb`，验签通过。
- `zipalign -c -P 16 4` 通过。实际 `aapt dump permissions` 无网络权限；合并 manifest 为 `allowBackup=false`、`usesCleartextTraffic=false`，Widget receiver 不导出。
- 发布 APK 没有安装覆盖用户原包；设备上保留隔离验证包，测试数据库按用例清理，截图与本地日志保留。
- 既有 `gradle.properties` 的 parallel 设置、未跟踪 JDK25 daemon criteria、历史 `docs/implementation/ocr-release-jni-verification.json` 的旧 SHA 修改均保留。最新 JNI 证据写入 build/reports，没有覆盖历史报告。
