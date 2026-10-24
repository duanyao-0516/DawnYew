# DawnYew

免费、完全离线的 Android 用途记账应用。Android 首发，Kotlin Multiplatform 共享账务规则与 Compose 界面；没有服务器、账号、广告或运行时网络权限。

## 当前范围

本轮开发、审查、历史验证与未完成项见[2026-10-24 开发交付说明](docs/implementation/2026-10-24-development-delivery.md)。用户要求在最后设备验收完成前停止，本提交是开发代码快照，不能视为已经全面验收的正式版本。

- 五栏导航：首页、账户、统计、更多、我的；首页10类和统计16类面板支持开关、长按拖动与持久化顺序，主页立即按配置显示。
- 完整月份与自定义日期区间、独立月份日历和每日净收支；分类、趋势、账本、账户历史等统计与流水下钻。
- 单页记账：金额、分类、账户、标签、内联备注和保存；点击金额切计算键盘，备注切系统文本键盘，金额右侧编辑日期/时间。
- 八类本地资金账户、转账、项目/商家/标签、借入/借出/报销、八种频率周期交易、分类预算与额度调配、四类存钱计划及愿望记录。
- 完整JSON与口令加密 `.dwybak` 备份、旧v1兼容、预览/原子恢复与保护副本；DawnYew CSV交换普通收入/支出，保留导入预览、重复/冲突处理与批次撤销。
- 三种离线截图入口：系统新截图监听、状态栏Quick Settings截屏按钮、分享图片/本地选图；统一OCR后进入人工核对草稿，不自动写账。此新链路的最终设备验证仍未完成。
- 本地应用锁、后台遮盖、系统强生物识别/锁屏凭据与静态桌面快捷组件；没有登录、会员或云同步入口。
- 离线小工具和长按「我的」进入Debug测试数据工具继续保留。调色盘、尺子、声级测量等仍遵循原校准/权限要求，不代表专业测量仪器。

**未实现范围：**分期、多人协作与信用卡账单周期不展示空壳入口；微信、支付宝与DawnYew Excel具体模板仍待样本；提醒尚无每日后台通知，周期处理在授权前台进行。iOS/Harmony没有可交付工程或兼容性承诺。更多边界与验证状态以本轮交付说明为准。

## 工程

| 模块 | 职责 |
| --- | --- |
| `shared:core` | 金额、领域命令、退款/回收站、查询与平台契约 |
| `shared:storage` | Room KMP、Bundled SQLite、事务、迁移、分页与聚合 |
| `shared:protocol` | 版本化备份、规范摘要、模板扩展和导入预览 |
| `shared:presentation` | 完整草稿、导航、幂等提交及查询状态 |
| `shared:ui` | Compose Material 3 界面与图表 |
| `platform:android` | SAF、OCR、文件代际、加密、应用锁与 OOXML |
| `androidApp` | Android 生命周期与能力组合 |

最低 Android 11 / API 30；默认 arm64-v8a。iOS/Harmony 不参与构建。

## 构建

需要 JDK 17、Python 3.9+、Android SDK 37、Build Tools 35.0.0 或更新稳定版与项目内 NDK r28c。已固定依赖和 Gradle wrapper 校验值。可通过 `DAWNYEW_JAVA_HOME`、`ANDROID_HOME` 指向现有工具链；缓存和签名材料均放 `.local/`，不修改 Git 全局配置。

```sh
./dev.sh help                         # 查看全部命令
./dev.sh debug                        # Debug APK
./dev.sh init-release-key             # 首次创建本机正式签名密钥，已有密钥保持不变
./dev.sh release                      # 优化 Release + OCR JNI/Lint + 签名、校验、版本命名
./dev.sh test                         # 共享层和 Android 平台主机测试
./dev.sh check                        # 完整构建与自动化检查
./dev.sh clean                        # 清理构建目录，保留本地缓存和密钥
./dev.sh gradle tasks                 # 自定义 Gradle 任务
./dev.sh debug --offline --console=plain  # 透传 Gradle 参数
```

脚本可以从其他工作目录调用，所有构建仍定位到本项目。Debug 输出为 `androidApp/build/outputs/apk/debug/androidApp-debug.apk`。`./dev.sh release` 在 OCR JNI 和 Release Lint 通过后，用独立正式密钥签名，输出 `artifacts/releases/DawnYew-v<versionName>-<ABI>.apk` 和同名 `.sha256` 校验文件，例如 `DawnYew-v0.1.0-arm64-v8a.apk`。版本号读取本次 Gradle 构建元数据，架构读取实际 APK 中的原生库；多架构以 `+` 连接。签名前对齐、签名后验证签名及 16KB ZIP 对齐，任一步失败都不替换最终 APK。

正式签名仅用于 `./dev.sh release` 的交付步骤；Gradle 的 `assembleRelease` 和 `./dev.sh check` 保留未签名中间包 `androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk`，供现有检查使用。本次 OCR JNI 报告写入 `build/reports/ocr-release-jni-verification.json`，不覆盖仓库中的历史验收证据。首次运行平台加密主机测试前，需要按[加密构建记录](docs/implementation/crypto-native-build.md)准备 `.local/crypto-work/host` 下的本机 JNI 库。

`release` 支持 `--offline`、`--console=plain` 等 Gradle 构建参数；`--help`、`--version`、`--dry-run`、`--task-graph` 只展示信息，不导出 APK；`--status`、`--stop` 也只执行 Gradle 管理操作。为确保检查的是本项目，禁止在 `release` 中切换 Gradle 工程或用 `-x/--exclude-task` 排除任务；这类自定义调用请使用 `./dev.sh gradle`。

正式密钥及随机密码保存在 `.local/signing/release/release.keystore` 与 `.local/signing/release/password.txt`，目录权限 0700、文件权限 0600，alias 为 `dawnyew-release`。**首次初始化后，务必将整个目录私密备份到安全位置。** 后续版本和换机器时恢复同一份材料；日常构建不会创建新密钥，也不会回退到 Debug 签名。文件部分缺失、权限不正确或密码错误会停止发布。密码通过文件传给签名工具，不写入源码、命令参数或日志。

发布新版本前更新 `androidApp/build.gradle.kts` 的 `versionName` 并递增 `versionCode`。同版本同架构再次运行会替换本地导出文件；已分享的正式版本应使用新的版本号。此前设备测试包使用开发签名，无法被新的正式签名直接覆盖；请先导出并验证数据备份，再自行安排迁移，构建脚本不会卸载应用或操作设备。

测试交付包的签名、SHA-256 和实际验证结果见设备验证报告。APK/AAB、APK 签名附属文件、`artifacts/releases/`、签名密钥和密码、缓存和设备数据均由 `.gitignore` 排除，不提交到 Git。手动发布 GitHub Release 时只上传最终 APK 及 `.sha256`，不要上传签名目录。OCR AAR/模型、JNI `.so`、Gradle wrapper、Room schema 和测试向量属于必要构建输入，随源码保留。

OCR 模型和自建无遥测 ORT/OpenCV AAR 随工程提供。来源、哈希、许可和重建命令见 [OCR 构建记录](docs/implementation/ocr-native-build.md)；libsodium JNI 见 [加密构建记录](docs/implementation/crypto-native-build.md)。所有本地原生库及最终 APK 均需检查 16KB 对齐；静态检查不等于在 16KB 真机完成加载验收。

## 验证与使用边界

[实现计划](docs/superpowers/plans/2026-10-18-dawnyew-android-implementation.md)记录范围；[技术方案](docs/superpowers/specs/2026-10-18-dawnyew-technical-design.md)记录账务和协议。Debug 工具与造数验证见 [测试数据工具记录](docs/implementation/2026-10-18-debug-tools-verification.md)。更多工具及人工校准流程见 [小工具验证记录](docs/implementation/2026-10-18-more-tools-verification.md)。首页与导航调整见 [构建与交叉检查记录](docs/implementation/2026-10-18-home-dashboard-verification.md)。此前实际操作证据见 [最终设备验收报告](docs/implementation/2026-10-18-device-acceptance.md)与[人工验收清单](docs/implementation/manual-acceptance-checklist.md)。

应用锁保护入口，不等于数据库本身加密。卸载会删除本机数据；请自行保存并验证导出的备份。加密备份口令不保存，遗失无法恢复。OCR 输出必须核对，设备上的合成样例通过不代表真实支付截图准确率已经验收。
