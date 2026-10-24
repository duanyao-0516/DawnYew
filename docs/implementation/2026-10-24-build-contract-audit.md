# DawnYew 离线改版构建、平台契约与迁移审计

审计日期：2026-10-24。审计对象为当前工作树，目标参考 `.local/research/bohe-20261024/薄荷记账-完整功能与UI分析.md`。本次仅阅读源码、脚本、已存在的缓存和历史报告；**没有执行 Gradle、构建、ADB、安装或设备操作**。下列命令供主任务在集成后统一执行，不能把缓存存在或历史成功结果视为本轮验证通过。

## 1. 实际基线与已有改动

- 分支：`p/duanyao.0516/codex/dawnyew-android-app`；本地 HEAD `abf4cc3`，上游为同名 `origin` 分支。读取时 `main` 也指向 `abf4cc3`；未 fetch，不能据此声称远端仍相同。
- `git stash list` 为空；没有切分支、合并或改写 Git 配置。仓库及逐级父目录未发现磁盘 `AGENTS.md`，遵循本次会话提供的规则和指定工程 guardrails。
- 开工前已有改动须保留，不能归入本轮业务实现或回滚：
  - `gradle.properties`：新增注释及 `org.gradle.tooling.parallel=true`。
  - `docs/implementation/ocr-release-jni-verification.json`：只替换 `apk_sha256`，原 `176ec0…` 变为 `573764…`。
  - 未跟踪的 `gradle/gradle-daemon-jvm.properties`：要求 `toolchainVersion=25`，含各平台下载地址。
- `.local/research`、缓存、签名材料、旧 APK 均属于本地输入；本轮不重新生成、替换或删除。新的 JNI 校验报告写 `build/reports/ocr-release-jni-verification.json`，不覆盖上述历史证据文件。

## 2. 工具链与缓存的当前静态证据

| 输入 | 当前证据 | 使用判断 |
| --- | --- | --- |
| Gradle | Wrapper 9.5.0，校验值固定；`.local/gradle-home/wrapper/dists/gradle-9.5.0-bin/…/gradle-9.5.0` 存在 | 可先尝试 `--offline`，不需要重新下载 wrapper |
| Java | Homebrew JDK 17.0.20.1；项目缓存 Adoptium 25.0.3；Android Studio JBR 25.0.3 均存在 | `project-env.sh` 设 launcher JDK17，但未跟踪 daemon criteria 要求 JDK25；最新历史 daemon 日志确实使用项目缓存 JDK25。不能仅按 README 声称 daemon 运行在 JDK17 |
| Android SDK | `local.properties` 与环境均指向 `/Users/bytedance/Library/Android/sdk`；Platform 37.0 revision 2 的 `android.jar` 存在 | **实际目录是 `platforms/android-37.0`，不是 `platforms/android-37`**；应用配置 `compileSdk=37`、`targetSdk=37`、`minSdk=30` |
| Build Tools | 36.0.0 和 30.0.3 已安装，36.0.0 的 `apksigner`、`zipalign` 存在 | 正式打包工具输入可用，仍需实际签名/对齐命令确认 |
| NDK | `.local/android-ndk-r28c/source.properties` 为 `28.2.13676358` | 与应用 `ndkVersion`/`ndkPath` 一致 |
| Gradle 主要依赖 | 项目缓存含 AGP 9.3.3、Kotlin 2.4.21、Compose 1.12.2、KSP 2.3.12、Room compiler 2.8.5；各模块 lockfile 已在仓库 | 不新增依赖时具备离线构建基础；未逐项证明每个变体全部传递依赖已缓存 |
| 本机 crypto JNI | `.local/crypto-work/host/libdawnyew_crypto.dylib` 存在 | `platform:android` 主机测试已配置此 `java.library.path`；需实际测试确认可加载 |
| OCR/crypto Android 输入 | 本地 ORT/OpenCV AAR 和模型在仓库；两 AAR 均包含 arm64-v8a 与 x86_64，crypto `.so` 另外含两种 32 位 ABI | 默认交付 arm64-v8a；不能因 crypto 含 32 位库就启用 32 位交付，OCR 没有对应 ABI |
| Python | 当前 `python3=3.9.6` | 常规 release/静态校验脚本可用；`build-ocr-native.py` 明确要求 >=3.12，当前默认解释器不能用于该重建流程 |
| 签名输入 | 本地 Debug keystore、正式 keystore、password 文件存在；三个文件均为 0600 | 本次仅检查存在性、大小和权限，未读取密码，未验证 alias/口令。最终发布必须走现有脚本 |

`scripts/build-crypto.sh` 会在源文件缺失时 `curl`，OCR 原生重建会下载源码并触发上游构建；它们不是本轮 UI/业务改版的日常构建前置条件。应用运行时全离线与构建机获取工具链是两个边界；本轮已有输入足够时应使用 `--offline`，不要为 UI 改版重建原生依赖或更新 lockfile。

## 3. 构建入口的真实范围

| 命令 | 实际任务/副作用 |
| --- | --- |
| `./dev.sh debug` | `:androidApp:assembleDebug` |
| `./dev.sh test` | core/protocol/storage/presentation 的 `jvmTest`，加 `:platform:android:testDebugUnitTest` |
| `./dev.sh check` | 上述主机测试 + `:shared:ui:compileKotlinJvm` + Debug APK + AndroidTest APK + 优化 Release OCR JNI DEX 校验 + Release Lint；**不运行设备测试** |
| `./dev.sh release` | 先校验既有正式密钥，再构建优化 Release、执行 OCR JNI/Lint，最后对齐、签名、验签、验证 16KB ZIP 对齐并导出 APK 与 SHA256 |
| `./dev.sh gradle …` | 透传自定义 Gradle 任务 |

- `scripts/project-gradle.sh` 固定切回项目根目录，使用 `.local/gradle-home`；若 Debug key 不存在会复制旧 key 或生成，因此即使只问 Gradle 信息，也不完全等于无副作用。本次没有调用。
- Release 开启 R8 和资源收缩。`verifyReleaseOcrJni` 依赖 `assembleRelease`，以固定 AAR 中的类/字段/方法签名对照实际 APK DEX 定义，还要求存在应用混淆证据；不能用 Debug 可运行替代。
- `release` 禁止 `-x/--exclude-task` 和切工程参数；信息选项不会导出旧 APK。保留这个防止“旧包被当成本次结果”的顺序。
- `check` 只生成未签名 Release 中间包；不等于正式签名交付。正式 key 已存在，不应再调用 `init-release-key`。
- 原生资产哈希/ELF/遥测标记校验 `scripts/build-ocr-verify.py` **没有被 `dev.sh check` 自动包含**，最终需要单独运行一次。
- `scripts/tests` 下 13 个 Python 发布脚本测试也不在 `dev.sh test/check` 内；脚本未改时可作为最终回归补充，脚本有改时必须执行。测试使用临时签名目录；不会替换项目正式 key。

## 4. 全离线、免登录边界

1. `androidApp/src/main/AndroidManifest.xml` 显式通过 merge remove 删除 `INTERNET` 和 `ACCESS_NETWORK_STATE`；`usesCleartextTraffic=false`。旧 Debug/Release merged manifest 也不包含这两项网络权限，但本轮成包必须重新核对。
2. 应用禁用 `allowBackup`，`backup_rules.xml` 和 `data_extraction_rules.xml` 排除云备份及 device transfer 的全部数据域。安全状态、代际数据库和暂存文件均在 `noBackupFilesDir`。
3. 仅有生物识别、声级测量麦克风等现有权限；`RECORD_AUDIO` 的调用方为前台主动声级测量，不是语音记账。增加薄荷参考中的“语音/AI”入口不能直接接系统可能联网的 `SpeechRecognizer` 或服务商 SDK。
4. 本次源码扫描未发现业务 HTTP client、WebView、`ACTION_VIEW` 外链、Firebase、支付/会员 SDK 或云服务调用；本地 OCR 为自建无遥测 ORT CPU + 随包模型。lockfile 中的 `httpclient`/Netty/analytics 命中属于 lint/UTP/构建配置，不能只按依赖名字判定运行包联网。
5. `LocalDocumentAccess.validate` 在读字节前检查 `content://`、authority、系统 provider 包身份，以及存储卷 ID；`EXTRA_LOCAL_ONLY` 只是 picker hint。SAF/分享图片继续复用该入口，不能为“兼容分享”直接对任意 URI 调 `ContentResolver`。部分厂商文件提供方可能被拒绝；现有提示引导先保存到本机下载目录。
6. 参考中的登录、会员权益、共享、云同步、WebDAV、外部客服/社交、在线更新和订阅支付只做无副作用占位；不采集账号/密码/支付资料，不产生成功状态，不启动浏览器/外部 App，不做后台重试。
7. 参考数据中未验证的行为不能自动升级为实现事实。原有本地 JSON/加密备份、OCR、系统设备认证是 DawnYew 已有能力；本轮是否保留/如何呈现应在方案中明确，不能仅因参考某入口收费就误删本地数据保护链。

静态 permission/源码扫描只能证明当前可见构建与调用边界，不能证明系统文件提供方等受托组件绝不联网；最终应明确是否实际做了设备网络/DNS 观测。

## 5. Android 接线与生命周期责任

| 责任 | 所属代码 | 改版必须保留的契约 |
| --- | --- | --- |
| 生命周期、系统认证、SAF/分享、OCR 组合 | `androidApp/MainActivity.kt` | Compose UI 通过 `PlatformActions` 调能力，不绕过锁状态和 `sensitive()`；后台立即遮盖，`FLAG_SECURE` 始终开启 |
| 进程级 controller/repository/授权 | `DawnYewApplication.kt` | 保持唯一进程实例；Application scope 不持有 Activity/View；启动失败不创建空账本覆盖旧库 |
| 会话时序 | `SessionCoordinator.kt` | process + epoch + lease id + 120 秒 deadline；普通退后台/锁屏撤销；只允许匹配 launcher 的首次 picker 往返续用 |
| UI 草稿、导航、幂等保存 | `shared:presentation/AppController.kt`、`EditorDraft.kt` | 改版应继续走 Command 与稳定 operationId；不由 UI 写 DAO；提交回执与用户确认分开 |
| 持久数据和发布 | `GenerationRepository.kt`、`shared:storage` | mutex 内再次检查授权/epoch；成功发布后递增 epoch；未确认提交先阻止新的不同 operation |

必须逐项复核：

- `MainActivity` 在 `onPause` 遮盖、停止工具、取消文件任务和恢复预览；`onDestroy` 释放 launcher/认证并丢弃候选。自定义全屏表单/新导航不能吞掉现有 Back handler 或让覆层后控件继续接受触摸与无障碍操作。
- 文件 picker 的 `PendingExternal` 只存在进程内，含 lease 与 databaseEpoch；注册键绑定操作身份，旋转可以继续，进程死亡不能复活授权。禁止把 lease、口令、URI 权限或已认证状态放入 `Bundle`/`rememberSaveable`。
- 恢复和清空成功会调用 `controller.resetTransientState()` 并清理 OCR capture；新账户/标签/周期页面的缓存、编辑草稿、后台偏好写入也必须失效。单纯 `remember`/一次初始化列表可能显示或覆盖旧代际数据。
- 当前应用锁是立即后台锁定、系统强生物识别/设备凭据，安全设置不随业务备份迁移。参考的 3 秒～5 分钟锁定选项、数字/手势锁不能只复制 UI 并偷偷改变安全语义；会员/登录限定项按目标占位。
- OCR 是 `decode → 单飞 native engine → parser → 待核对 capture → editor → 明确保存`；旧 session epoch 结果不可回填，取消时 bitmap 生命周期须包住实际 native 调用。金额/日期修改后必须重做重复检查并重置确认。
- 麦克风权限返回只提示重新主动开始，退后台/换工具停止采样；新“记账语音”入口不能借用这个权限回调自动开始录制。

## 6. 数据、备份与迁移风险

当前 Room schema 为 **3**，显式迁移 `1→2→3`。数据库升级先 probe/checkpoint，再复制到候选 generation，在副本上迁移验证后发布；旧 generation 留作保护副本。不能用 destructive migration、直接改 active 数据库或删除 datasets 修复升级失败。

当前业务记录存为 Room 索引列 + Kotlin serialization JSON payload；`RoomLedgerRepository` 使用 `Json { encodeDefaults=true }`。备份则有独立且更严格的协议：`formatVersion=1`、`canonicalizationVersion=1`、固定 9 种实体、精确字段集合、宽整数十进制字符串、计数与规范摘要。`RecordSchema.validate` 明确要求默认值字段也必须在记录中，拒绝多余字段；**给 Kotlin 字段加默认值并不能保持旧备份兼容**。

新增资金账户、转账、商家/项目/标签、借还报销、周期或存钱计划前，方案必须明确以下纵向闭环：

1. 领域模型/命令/校验与跨实体约束；转账、借还、退款、资产统计的计入语义，以及幂等 operationId。
2. Room entity/DAO、payload 读写、用于查询的冗余索引列和迁移；旧 v1/v2/v3 数据升级结果必须确定。
3. `LedgerEntityBatch`、`Streaming.kt` 的实体集合/分页/导入，`CandidateVerification` 的 SQL 与 payload 一致性和关系校验。
4. 备份 entityTypes、RecordSchema、encode/decode、manifest/counts、canonical digest；如需新协议版本，提供旧 v1 显式转换而不是静默放宽字段校验。
5. 整体替换、保护副本读取、恢复预览统计、candidate 发布、回滚/失败封闭、代际失效。
6. 页面/统计/OCR/清空/回收站/导入模板的所有受影响调用方；不能在 UI 另存一套余额充当真实资产。

现有偏好也不是任意 key-value：`Command.SetPreference`、`Validation.kt`、`LedgerEntityBatch.kt` 当前白名单只有 `theme/lastLedgerId/lastPeriod/lastBackupAt`。新增主题、面板排序、记账设置等持久项必须同步命令、全量校验、批量恢复校验和测试；安全凭据仍不得进入业务偏好/备份。

备份恢复在读取 provider 前取得 `DatasetToken(epoch,generation,datasetId,contentVersion)`，候选写入不改变 active；确认发布前再次验证 token、owner、候选内容、授权。新功能不得通过自己缓存的 `LedgerState` 直接 `replace` 覆盖其间编辑，也不能省略 candidate 的失效/清理。

## 7. 现有测试覆盖与缺口

本地已有 2026-10-18 主机测试 XML：core 50、protocol 16、storage 43、presentation 82、platform 104，合计 **295**，均记录 0 failures/errors。此为磁盘历史产物，不是本轮运行；旧验收文档中的 211 不是当前测试基线。

- 现有主机覆盖：金额/日期/领域边界、备份严格类型/字段/摘要、真实 SQLite 迁移与分页、import 事务、JSON 流式与规范存储、crypto 金向量/破坏拒绝、OOXML、OCR parser/resource limit/single-flight、SessionCoordinator、generation fault injection、controller 草稿/导航/查询加载。
- 现有 Android instrumentation 是 6 个测试：最终权限/自动备份标志、真实 libsodium、真实 generation 重开/幂等、真实 OCR 合成金额、流式候选隔离与旧预览拒绝、10,000 条 SQLite 查询测量。`assembleDebugAndroidTest` 只证明测试 APK 可编译。
- 未见 Compose UI 自动化依赖/页面测试。薄荷参考的视觉接近、全导航可达、键盘/小屏、Back/旋转、200% 字号/TalkBack 仍需要实际 UI 验收。
- 历史设备结果覆盖有限设备、合成 OCR；不能替代新交付包、真支付截图准确率、危险负样本、多厂商 SAF、系统备份排除、16KB 页大小真实设备、全系统网络观察。

## 8. 集成后精确验证命令

所有命令从 `/Users/bytedance/Desktop/code/DawnYew` 执行。先由主任务检查最终 diff 和基线保留，再统一运行；不要多 agent 同时写同一 Gradle 输出或操作设备。

```sh
# 1. 一次完整主机/编译/Release gate；不会运行设备测试或创建正式 key
./dev.sh check --offline --console=plain

# 2. 未包含在 check 内的随包 OCR 模型/AAR/ELF/遥测静态校验
python3 scripts/build-ocr-verify.py

# 3. 发布脚本回归，使用临时根目录与临时 key；常规构建入口未覆盖此组
env JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
    ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
    python3 -m unittest discover -s scripts/tests -p 'test_*.py' -v

# 4. 重新检查本轮 merged manifest，预期没有 INTERNET/ACCESS_NETWORK_STATE
rg -n 'uses-permission|allowBackup|usesCleartextTraffic' \
    androidApp/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml

# 5. 如本轮需可分发 APK，使用已有正式 key，完成真实签名和包对齐校验
./dev.sh release --offline --console=plain
```

应记录本轮测试 XML 各 suite 数量、失败/skip、Lint 结果、`build/reports/ocr-release-jni-verification.json`、最终 APK SHA256。若 `--offline` 因缺依赖失败，先定位具体缺项；不要直接 `--refresh-dependencies`、改版本、清缓存或覆写 lockfile。

设备执行由主任务统一选择目标。以下为受控设备阶段，**本审计未执行**：

```sh
# 在确认目标设备与现有签名/数据兼容后，Gradle 设备集成测试：
ANDROID_SERIAL='<目标设备 serial>' ./dev.sh gradle \
    :androidApp:connectedDebugAndroidTest --offline --console=plain
```

该任务会安装 Debug 主包和测试包；若设备已有不同签名的正式包，不能以卸载绕过而丢失数据，应选择测试设备或先按已验证备份迁移流程处理。现有 instrumented 测试把业务数据置于临时 Context，但包安装和 Application 启动仍是实际设备动作。最终 R8 Release 必须另做 UI/OCR 回归；Debug instrumentation 不证明 Release native 反射链可运行。

## 9. 开发前与交付前验收门槛

| 门槛 | 必须可审查的证据 |
| --- | --- |
| 方案经交叉 review | UI/功能矩阵列出可落地、登录/会员占位、未覆盖；审查者分别核对领域/存储、UI/导航、Android/安全，不以审查人数替代具体问题关闭 |
| 无数据损失 | 旧 schema/旧备份 fixture 的显式迁移、故障注入、保存幂等、并发编辑使恢复预览失效；恢复后新旧功能都可读取 |
| 真实离线 | 新 merged manifest/最终包权限不含网络；没有新增云 SDK/外链执行/后台重试；本地 OCR 资源随包；登录/VIP入口无副作用 |
| 平台保真 | 锁定入口不泄露页面；picker 旋转/超时/锁屏/进程死亡正确；OCR 只草稿；后台麦克风停止；恢复/清空之后所有新页面状态同步失效 |
| 构建与原生 | `dev.sh check --offline`、独立资产校验；发布时 Release/JNI/Lint/签名/16KB ZIP 对齐；说明真实 16KB 设备是否验证 |
| UI 与业务 | 核心手动链路含增改删/退款/回收站/统计，新账户/转账等实际命令闭环；逐主要页面与参考截图核对；字号、横屏、键盘、返回、空态和错误态 |
| 最终独立 review | 检查集成后的实际 diff 和最新产物；问题已修复或明确阻断，不把未完成数据功能伪装成可点击成功页 |

当前最重要的实施风险依次为：新领域功能绕过既有严格持久化/备份契约；UI 重写破坏进程授权与代际失效；占位入口意外引入外部动作；只验证 Debug 而漏掉 Release JNI；视觉验收受 `FLAG_SECURE` 限制后草率取消数据保护。截图困难应由受控验证方法解决，不应在交付包移除安全窗口。
