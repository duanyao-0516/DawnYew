# DawnYew

免费、完全离线的 Android 用途记账应用。Android 首发，Kotlin Multiplatform 共享账务规则与 Compose 界面；没有服务器、账号、广告或运行时网络权限。

## 当前范围

- 四栏导航：首页、统计、更多、我的；更多提供离线小工具，截图记账、明细、管理与备份工具保留在我的。
- 长按底栏「我的」打开 Debug 二级页，按账本、日期范围和条数生成测试账单；默认日常／近 30 天／100 条。测试数据参与正常统计，可按未改动批次撤销。
- 首页提供账本选择、搜索、周期净花销曲线、独立今日收支及圆形加号记账。多账本与独立收支分类、连续记账、编辑、筛选和跨账本移动均保留；明细作为二级页承接账单操作。
- 首页、统计与明细各自保留日期范围：本月、上月、今年、近 7/30 天及自定义起止日期；整月/整年前后切换按历法，自定义按同等天数平移。
- 关联退款、成组回收站与恢复、永久清理；金额使用整数分。
- 收入/净支出趋势、分类占比、账本比较、等价数据表和下钻；独立月预算。
- 完整 JSON 与口令加密 `.dwybak` 备份、校验预览、整体恢复及一次保护副本。
- 本地应用锁、后台遮盖、系统强生物识别/锁屏凭据；受控截图选择返回无需重复解锁，普通退后台及锁屏仍上锁。安全设置不随业务备份迁移。
- 随包离线 OCR：单张 PNG/JPEG 截图 → 草稿核对 → 明确保存；不自动入账。
- 更多工具：RGB/十六进制调色盘、可校准全屏厘米尺、麦克风声级测量、随机做决定、双人石头剪刀布。噪音仅前台主动开始后采样，不存录音；未校准显示 dBFS，参考声级计校准后显示估算 dB，不是专业声压计。尺子首次使用需以实物尺校准。

**按本轮范围保留：**微信、支付宝与 DawnYew Excel 的具体模板适配器等待样本，入口明确不可导入。真实 OOXML codec、通用模板/预览及原子提交基础已实现，但不代表三类账单格式已适配。iOS 仅保留平台契约；没有 iOS 工程或兼容承诺。语音、桌面小组件、多人及资产负债不在本轮。

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
