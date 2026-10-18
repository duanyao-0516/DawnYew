# DawnYew 本地数据协议与安全专项设计

日期：2026-10-18。状态：技术设计建议与官方依据；未创建工程、安装依赖或执行 Android 真机测试。适用范围：Android 首版，最低 Android 11/API 30；iOS 只保留平台接口，不在本轮开发、编译或验收。具体账单模板只保留扩展协议，不实现微信、支付宝或 DawnYew 表格适配器，不预设它们的列名、状态值或文件布局。

输入：[需求设计 v0.3](../superpowers/specs/2026-10-18-dawnyew-requirements-design.md)、[技术设计准入检查](../superpowers/specs/2026-10-18-dawnyew-technical-design-readiness.md)、[离线录入研究](2026-10-18-offline-capture-and-data-research.md)。本文给出数据和安全层的确定性规则，供总体技术设计引用；模板可执行映射和设备验证仍是相应功能的验收前置条件。

## 1. 决策与保护边界

| 事项 | 本轮选择 | 理由及边界 |
| --- | --- | --- |
| 本地数据库 | SQLite 位于应用内部 credential-encrypted、no-backup 目录；不增加数据库文件加密或字段加密 | 系统沙箱和设备存储保护是首版基础。可选应用锁保护应用入口，但不能抵御 root、恶意系统、调试注入或已取得应用私有文件的攻击者。产品说明不能写“数据库已加密” |
| 应用锁 | AndroidX BiometricPrompt，允许 `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` | API 30 起采用同一认证组合，代价是不支持 Android 10 及更早设备；无服务器账号、无应用自建 PIN、无备份口令绕过本机锁 |
| 完整数据 | 独立版本的 UTF-8 JSON；整体替换恢复 | 业务格式不等于 SQLite schema，也不绑定平台类或库内部序列化格式 |
| 加密备份 | libsodium 1.0.22；Argon2id13 + secretstream XChaCha20-Poly1305 | 使用成熟流式 AEAD，支持大文件；口令派生密钥使文件可跨设备恢复，不依赖 Android Keystore |
| 内部保护副本 | 只读旧 generation，必要时额外生成已关闭连接的一致复制；与活动库相同沙箱保护 | 明确保存原因、时间、数量与清理入口；不能将内部副本称为设备外备份 |
| 表格 | 真实 OOXML `.xlsx`；结构检查与来源适配器分层 | 文件扩展名和 MIME 仅辅助识别；具体来源、列映射、数据状态与日期格式等待确认样本 |
| 系统备份 | 本机业务、安全配置、暂存文件与保护副本全部排除云备份和设备迁移 | 不借 Android 系统备份引入隐式云同步 |

Android 官方将强生物认证定义为 Class 3，支持系统 PIN/图案/密码后备，同时说明 Android 10 及以下不支持上述组合。本文的 API 30 选择来自这一兼容边界。[Android 生物认证指南](https://developer.android.com/identity/sign-in/biometric-auth)

Keystore 能使密钥不可导出并约束使用条件，但不会自动加密 SQLite。首版没有需要设备绑定保存的账务加密密钥，也不为“使用了 Keystore”而增加无实际数据保护作用的密钥。后续若增加数据库加密，必须单独设计密钥丢失、认证配置变化、备份恢复和锁定时数据库连接关闭；不得悄悄改变现有协议。[Android Keystore](https://developer.android.com/privacy-and-security/keystore)

## 2. 安全状态与业务状态分开保存

内部根目录采用 `noBackupFilesDir/dawnyew/`，它属于 credential-encrypted 存储；不使用 device-protected storage，设备首次解锁前不接收业务任务。

| 区域 | 内容 | 恢复/清空行为 |
| --- | --- | --- |
| `control/` | active 指针、切换 journal、操作 epoch、清理任务清单 | 不进入业务备份；恢复只按切换协议更新 |
| `security/` | `lockEnabled`、安全配置版本、启用状态记录 | 不进入 JSON、XLSX 或普通偏好对象；同设备恢复及清空账务数据均保留；关闭锁为独立认证操作 |
| `generations/<opaque-id>/` | 账务库及可备份偏好；同一 generation 中 SQLite/WAL/SHM 均由数据库驱动管理 | 恢复创建新代，成功切换后旧代作为保护副本；不得改名正在使用的 SQLite 文件 |
| `staging/<job-id>/` | 输入隔离副本、解析暂存库、未提交新代、临时导出 | 不备份；取消及下次安全启动清理；作业 ID 为应用生成，不由文件名提供 |
| `recovery/` | 不可变保护代的描述、生成时间、原因、待清理标记 | 数据管理可见；清空全部数据必须删除，不可日后自动复活 |

应用锁会话、认证通过标记、敏感操作 permit、口令、派生密钥只在内存中存在。Activity 重建可以复用同一进程的安全协调器，进程重建绝不从 SavedStateHandle、Bundle 或数据库恢复“已解锁”。持久安全配置损坏，或发现既有数据但安全配置意外丢失时，进入安全配置错误态，不能默认关闭锁；修复必须成功系统认证并重建为 `lockEnabled=true`，随后才提供数据操作。无可用认证能力则保持受保护错误态。新安装的空目录才可初始化为未启用。

## 3. 应用锁状态机

唯一 `AppSecurityCoordinator` 负责认证与入口授权。所有外部 Activity、导航入口和敏感用例经过它；UI 遮盖不是唯一检查。受保护 Repository/UseCase 收到的授权对象包含 `processNonce + sessionEpoch`，提交前验证仍有效，禁止只检查页面是否曾显示过解锁成功。

| 状态 | 允许行为 | 转移 |
| --- | --- | --- |
| `Unprotected` | 普通业务；敏感操作仍有范围确认 | 启用锁时先认证，成功后持久化启用并进入 Unlocked；取消不变 |
| `Locked(reason)` | 锁页、重新认证、退出；可暂存不含内容的待处理入口描述 | 仅用户触发或一次待处理入口触发 Authenticating |
| `Authenticating(attemptId, purpose)` | 系统认证；应用窗口仍遮盖 | 当前 attempt 的成功回调才可建立会话/permit；失败继续等待系统，取消/错误不改变 lockEnabled，回到发起前未保护/锁定状态或敏感操作取消态 |
| `Unlocked(sessionEpoch)` | 当前前台会话中的普通查看、记账 | 普通离开、锁屏、进程失去会话或明确锁定撤销 epoch；敏感操作另行认证 |
| `ExternalFlow(opId, kind, deadline, suspendedEpoch)` | 仅应用主动发起的系统认证或本地文件选择；内容遮盖 | 匹配的一次结果回到原作业，校验存活、期限和 epoch；超时先锁定，成功重新认证后继续原作业，不再次打开选择器 |
| `SecurityUnavailable` | 解释设备认证不可用、重试、退出 | 认证能力恢复后仍需成功系统认证；不能降级为未保护 |

### 3.1 生命周期与外部系统流程

1. 应用窗口在首次绘制前按安全状态决定内容；已启用锁或正在显示口令/敏感导出确认时启用 `FLAG_SECURE`。Activity `onPause` 立即启用无敏感文字/缩略图的遮盖，重返且安全校验通过后才移除；不等待进程级 `ON_STOP` 才遮盖。
2. 真正离开应用即撤销普通会话，无普通后台宽限期。配置变化只允许同进程、受控重建 continuation，期间仍遮盖，不把旋转当成新解锁，也不持久化会话。
3. 只有在调用平台 launcher **之前**，由协调器创建一次性外部流程 token，才能豁免本次 `onPause/onStop` 的会话撤销。种类仅 `SystemAuthentication`、`LocalOpenDocument`、`LocalCreateDocument`、`LocalImagePicker`；token 绑定作业、ActivityResult launcher、发起 epoch 和单调时钟时间，不能被外部 Intent 自称是同一流程。
4. 文件选择 continuation 有效期为 120 秒。超过期限、锁屏、普通 Home 离开后无法确认仍是同一授权流程、收到新的外部入口或进程死亡，均使它失效。返回结果先收为待处理引用，显示锁页；重新认证成功后处理这个结果，不能自动重启 picker。系统认证 token 使用相同 attemptId，成功/取消回调只消费一次；超时后到达的旧成功回调不能解锁。
5. 文件选择器内部切换路径属于它自己的流程；应用无法把每次焦点变化可靠判定为用户主动切到其他应用。因此外部流程只给予有界 continuation，期间始终遮盖，收到结果时重新检查设备锁屏和 token；平台无法确认的情形按失效处理。这一判定必须覆盖多窗口、通知栏、Home、熄屏和厂商选择器真机用例。
6. ProcessLifecycleOwner 的 `ON_PAUSE/ON_STOP` 有意延迟，用于避开配置变化，因此只能辅助统计前后台，不能作为及时遮盖的唯一信号。[ProcessLifecycleOwner](https://developer.android.com/reference/androidx/lifecycle/ProcessLifecycleOwner) `FLAG_SECURE` 限制窗口截屏及非安全显示，不承诺阻止相机拍摄或被攻陷系统。[WindowManager 标志](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE)

### 3.2 认证结果与敏感操作

每次认证前以同一个 `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` 组合查询能力。无强生物但有系统凭据允许凭据后备；临时锁定、未录入、硬件不可用分别呈现，不把错误或 `onAuthenticationFailed` 当成功。没有系统后备时保持锁定。启用/关闭锁均须成功认证；在系统设置修改认证配置返回后撤销旧会话再认证，不试图读取或保存生物模板。

已启用锁时，明文 JSON/XLSX 导出、加密备份、完整替换、恢复保护副本、清空数据、关闭锁均使用一次性 `SensitivePermit`。它绑定 `opId + purpose + dataGeneration + revision + previewDigest`，认证完成后 60 秒内进入该操作的受控执行阶段；执行期间不能转给另一用途。外部文件流程可使用上述有界 continuation；超时返回重新认证，已取得的 URI 不重复选择。替换、清空和向外部文件复制之前均再次核验 permit、generation 与用户确认的影响范围；过期则暂停，不丢失已校验输入。

长时间生成备份可在已授权作业内计算，但不能因持有 permit 而解锁新页面；普通后台离开时暂停到安全检查点并遮盖。SQLite 中已经开始的短事务允许完成或回滚，外部写入停止并记录可能的残留文件；返回认证后决定继续同一任务还是清理。任何“取消后自动弹认证”只能由尚未消费的用户动作触发一次，避免认证取消循环。

## 4. 文件入口与离线边界

打开/保存使用 SAF 的 `ACTION_OPEN_DOCUMENT` / `ACTION_CREATE_DOCUMENT`，设置 `CATEGORY_OPENABLE` 与 `EXTRA_LOCAL_ONLY=true`，只选择本机文件；不申请 `MANAGE_EXTERNAL_STORAGE`，不把文件 URI 转换成任意磁盘路径。Android 的本地 root 标志定义为内容获取不发起网络请求，但 provider 自身属于另一个应用；不能凭 DawnYew 没有 INTERNET 权限就声称任意 provider 不会联网。[SAF 文件访问](https://developer.android.com/training/data-storage/shared/documents-files)、[本地 root 定义](https://developer.android.com/reference/android/provider/DocumentsContract.Root#FLAG_LOCAL_ONLY)

本机验收采取以下可执行边界：

- 文件选择只接受经过目标设备验证的系统本地 DocumentsProvider/MediaStore 来源；能力登记按 authority、实际 provider 包身份及本地 root 能力匹配，不把任意声称“本地”的第三方 provider 当可信来源。未知或云来源提示先存到系统本地位置再选。不能直接读取 `http(s)`、`file://`、自定义网络协议或虚拟文档。
- 截图分享仅接收单项 `content://` PNG/JPEG、实际读授权与已验证的本地图片 provider；其他来源不打开 URI 内容，转到本机选择入口。接收页面仅登记 URI、类型和入口 ID，解锁前不解析图片、显示名称/缩略图或执行 OCR。URI 失效要求重选，不扩大权限。
- provider 的声明、MIME、显示名称、大小都不可信；读取后检查魔数，累计真实字节，按用途限额隔离到随机命名的内部 staging 文件。拒绝多项分享、超长 URI/Intent、目录、符号链接式路径输入、路径穿越和解包后的越界成员。
- 内容流在 I/O 执行器读取，支持取消信号、关闭句柄和无进展超时；未受信任 provider 不进入数据通路。未知大小边读边计数；超过限额立即关闭。复制成功后释放无需保留的 URI grant，处理输入用内部副本，避免预览后 provider 调包。
- 导出先在内部完整生成、校验，再向用户创建的新外部文件复制。不能承诺 SAF 跨 provider 的 rename/replace 原子性；失败只报失败，尝试删除本次创建的不完整目标，删除不了须列出位置提醒清理，不能显示备份成功。外部文件被用户再交给其他应用后的行为不在本地保存保证中。

首版不额外增加导出后任意应用分享功能。若未来增加，只能通过非导出的 FileProvider、最小可分享路径、单 URI 临时读授权，并在授权结束后清理；不能公开 `generations/` 或 `security/` 根目录。[Android 安全文件分享](https://developer.android.com/training/secure-file-sharing/share-file)

## 5. 完整 JSON 协议

扩展名 `.json`，业务信封以[主技术方案的完整数据协议](../superpowers/specs/2026-10-18-dawnyew-technical-design.md)为唯一字段定义：`format/formatVersion/datasetId/createdAt`、`entities` 各数组与 `manifest`；`counts` 位于 manifest 内。本文不再定义第二套字段布局。金额统一为整数分的十进制字符串，必须匹配 `[1-9][0-9]*` 且不超过业务上限；没有浮点指数、加号、空白、前导零或隐式四舍五入。累计值用有检查的 64 位整数；发生时间用明确本地日期时间字符串，时区元数据只作来源说明，不能重新解释为另一自然日。

| 协议方面 | 安全约定 |
| --- | --- |
| 标识与版本 | 固定标识和协议版本；未来版本先拒绝，不能尝试“尽量恢复”；数据库 schemaVersion 独立 |
| 记录规范化 | 必须使用显式版本的确定性规则；不能哈希任意库默认序列化结果，不能把 Long 转成 IEEE 754 后规范化 |
| manifest | SHA-256 绑定规范化记录、实体类型/稳定 ID 和经验证的集合数量；元数据的完整性规则在主协议同处定义，不用自身摘要循环包含自己 |

业务数据包含账本、分类、所有有效和回收站流水、退款关联、删除组、月预算、来源身份及来源快照、导入批次及成员状态、必要显示偏好。幂等相关持久身份保留 `Entry.creationOperationId`、`ImportBatch.submissionId` 与批次 ID/成员关系，批次的 `previewDigest/mappingDigest` 也随完整实体保留；设备本地 `OperationReceipt` 不导出，恢复后以新的本机 epoch 拒绝旧任务回调。全部实体使用稳定 ID；批次已撤销、成员已永久清理等历史状态必须仍可表达。每个数组对象使用明确定义字段，拒绝未知字段，后续新增字段需要协议版本迁移；同一数据版本不随运行库升级改变意义。数组顺序不定义实体身份，导出使用固定顺序便于测试与比较，导入可接收任意数组/记录顺序并在 staging 完成引用校验。

不导出：应用锁和会话、Keystore alias、口令/派生密钥、URI 授权、原始账单文件/截图/OCR 全文、临时草稿、作业缓存、内部保护副本、SQLite 内部表和派生统计缓存。源平台身份保存必要的规范化来源范围，不保存支付登录凭据或无关个人信息。

摘要检测无意损坏，不是明文真实性证明；能改文件的人也能重算 SHA-256。预览数量、日期范围和汇总从已验证的实体数据推导，不相信文件的描述性计数。加密容器在此之外提供口令 AEAD 完整性，也不证明该备份来自某个真实支付平台。

读取器使用严格流式 JSON：UTF-8 错误、重复对象键、重复实体 ID、尾部额外 JSON/字节、未知枚举、非法金额或日期结构、坏业务引用、删除组不一致、有效退款跨账本/分类、有效退款早于原支出或有效退款累计超额都拒绝。回收站退款可以保留已移动原支出的旧账本/分类，其原支出 ID 必须存在，但归属、有效父项、日期先后与额度在用户恢复该删除组时才按当前规则校验；例如先删除退款、再把原支出改为更晚日期，回收站退款仍可存在于完整备份中，不能因这一合法中间状态拒绝整包。已移除 `ImportMember.entryId`、`DeletionGroup.rootEntryId` 等审计标识允许有规定状态的历史悬挂引用，不等同于退款父项等强业务引用。先写隔离 staging 库；建立索引后逐项校验外键和领域不变量，再形成预览。未知版本在创建正式新代前拒绝，已支持旧版经过确定的 `vN → vN+1` 纯迁移，并对迁移结果执行当前全部校验。跨平台共同使用同一份协议样例，不能直接导出 Room schema 当协议。

## 6. 口令加密容器 v1

### 6.1 算法、依赖与口令

扩展名 `.dwybak`，内容是上节完整 JSON 的原始 UTF-8 字节流，不做压缩，避免完整备份引入 ZIP 解压风险。选用 libsodium **1.0.22** 官方发布源码，构建时校验官方签名与锁定摘要；Android 使用官方 `android-aar.sh` 产物及项目内最小 JNI 包装，仅暴露初始化、随机数、KDF、stream init/push/pull、销毁。不得使用浮动版本或来历不明的预编译 JNI 包。官方发布及 Android 构建路径均已查到，但本次未构建、未证明 ABI 或性能兼容。[libsodium 发布](https://github.com/jedisct1/libsodium/releases/tag/1.0.22-RELEASE)、[官方安装说明](https://doc.libsodium.org/installation)

KDF 固定调用 `crypto_pwhash`，输出 32 字节，算法显式 `crypto_pwhash_ALG_ARGON2ID13`，`opslimit=3`，`memlimit=67,108,864` 字节，salt 为 CSPRNG 每文件新生成的 16 字节。显式保存算法与参数，不能使用未来可能变化的 `ALG_DEFAULT` 或 `*_INTERACTIVE` 数值隐式解释旧文件。64 MiB 与 3 次计算是本方案的固定 v1 参数，必须在最低性能设备验证；内存不足就失败，不静默降低强度。[libsodium 口令派生 API](https://doc.libsodium.org/password_hashing/default_phf)

创建口令要求 12–128 个 Unicode 标量、UTF-8 编码不超过 512 字节，二次输入完全相等；推荐长口令，不强制特殊符号。输入按原始 Unicode 编码为 UTF-8，不 trim、不大小写转换、不做 Unicode 规范化；拒绝 NUL、孤立代理项和无效编码。恢复接受相同范围内的准确字节。应用不保存口令、不设口令找回或额外口令校验哈希；错误口令与内容篡改统一为“口令不正确或备份已损坏”。密码输入禁自动填充/学习/保存，日志不记录输入；内存数组及时覆盖并释放 native state，但不宣称 JVM/输入法中所有历史副本均可保证清零。

### 6.2 精确帧格式

所有整数为无符号 big-endian。v1 文件头固定 **80 字节**，必须完整读取并通过格式/参数白名单检查后才执行 KDF。

| 偏移 | 长度 | 字段与 v1 值 |
| --- | --- | --- |
| 0 | 8 | magic：十六进制 `44 57 59 42 41 4B 0D 0A`（`DWYBAK` 后跟 CR LF） |
| 8 | 2 | containerVersion = 1 |
| 10 | 2 | headerLength = 80 |
| 12 | 2 | kdfId = 1（本协议 Argon2id13 编号，不使用库枚举裸值） |
| 14 | 2 | streamId = 1（secretstream_xchacha20poly1305） |
| 16 | 4 | opslimit = 3 |
| 20 | 8 | memlimitBytes = 67,108,864 |
| 28 | 4 | maxPlainChunkBytes = 65,536 |
| 32 | 2 | saltLength = 16 |
| 34 | 2 | streamHeaderLength = 24 |
| 36 | 4 | flags = 0；v1 不接受压缩或其他 flag |
| 40 | 16 | 本文件 salt |
| 56 | 24 | `secretstream_init_push` 生成的 stream header |

头之后是顺序帧：`ciphertextLength:u32 | ciphertext:ciphertextLength bytes`。每帧额外认证数据为 `完整80字节文件头 || frameIndex:u64 || ciphertextLength:u32`，frameIndex 从 0 递增，任何溢出拒绝。密钥传给 `crypto_secretstream_xchacha20poly1305_init_push/pull`，块的 nonce、状态演进与认证标签全部由 libsodium 管理；业务层不自行构造随机 nonce 或实现 AEAD。

普通帧 plaintext 恰为 65,536 字节、长度字段为 65,553，tag 必须 `TAG_MESSAGE`；最后一帧 plaintext 为 0–65,535 字节，长度 17–65,552，tag 必须 `TAG_FINAL`。因此当 JSON 大小恰好为块长整数倍时仍追加零明文的 FINAL 帧。读取器在分配缓冲区前验证长度，最多分配固定大小；不接受其他 tag。FINAL 后必须立即 EOF；EOF 前缺 FINAL、帧乱序/重复/删段、额外尾部、任一认证失败均使整个文件失败。每段解密成功只允许写隔离 staging，完整 FINAL、EOF、JSON 摘要和所有领域校验通过前禁止切换正式数据。

libsodium 的 secretstream 提供状态化认证加密、额外认证数据和 FINAL 结束标签；本节的固定帧长度、头部绑定和严格 EOF 是 DawnYew 对文件容器的约束。采用官方算法及 API，不自行复刻密码原语。[secretstream 文档](https://doc.libsodium.org/secret-key_cryptography/secretstream)、[1.0.22 头文件](https://github.com/jedisct1/libsodium/blob/1.0.22-RELEASE/src/libsodium/include/sodium/crypto_secretstream_xchacha20poly1305.h)

### 6.3 拒绝滥用与版本演进

- v1 只接受上表的完整参数元组，不提供“按文件任意内存/迭代次数”的 KDF；新强度建立新受支持 profile/版本和显式资源评审。每进程最多一个 KDF 作业，手动重试不能积累并发任务。
- KDF 在专用后台线程执行；取消后废弃结果、清理 state。一次 native KDF 不承诺可中途抢占，取消延迟最多包括本次派生耗时，须计入真机验收。
- 每次导出都使用新 salt 和新 stream header。恢复过程不在旧 state 上继续写、不复用失败作业的随机材料。测试固定随机向量只能存在测试代码。
- 版本演进不能把旧文件按新默认值派生，也不能在同一个 ID 下换算法。加密容器版本、JSON 协议版本和数据库 schemaVersion 三者独立。

## 7. 整体恢复、保护副本与崩溃恢复

`DataMaintenanceCoordinator` 串行协调导入提交、完整恢复、迁移、清空、保护副本回退、永久清理与导出快照。普通写入以 generation/epoch 验证加入同一写屏障。预览可以耗时，但不占用活动库写锁；提交时再次检查预览基线，变化则刷新并重新确认。

### 7.1 准备阶段

1. 读取本机文件到受限 staging；加密文件按上述流式解密进入隔离解析库，不必生成一份额外完整明文 JSON。用户取消时仅删除本作业文件。
2. 建立新 generation，完整迁移、验证关系、金额/时间、唯一性和摘要。计算预览。空间检查同时考虑现有库、输入副本、staging、新库、保护副本与 SQLite 临时/WAL 增长；保留至少 128 MiB 安全余量，过程中持续检查实际增长，空间不足停止且旧库可用。未知大小先受工作区配额限制，不能信任文件声明长度。
3. 用户确认整体替换；应用锁开启时取得绑定新文件摘要及当前 generation/revision 的 SensitivePermit。获取独占维护屏障，等待活跃短事务结束，停止流式查询、快照和写入任务；基线变化则退回预览。
4. 生成当前库保护副本并验证可打开。可将已关闭且检查一致的当前 generation 保留为不可变保护代，同时用一致数据库复制形成需要的独立副本；不能只复制正在使用的 `.db` 而漏掉 WAL。保护副本未就绪、空间不足或关闭失败不得继续。[SQLite WAL](https://www.sqlite.org/wal.html)、[SQLite 备份接口](https://www.sqlite.org/backup.html)

### 7.2 原子切换

目录名使用内部随机 UUID，路径不可来自 JSON。新代完成数据库提交、关闭连接、fsync 后，写入 `PREPARED` journal，包含 opId、old/new generation、旧 revision、候选校验摘要与保护代 ID。journal 本身以临时文件写入、fsync、同目录原子 rename、目录 fsync 更新。

平台 `AtomicGenerationStore` 在同一内部文件系统写 `active.next`、fsync，然后原子替换 `active` 并 fsync 父目录。**active 指针替换是切换的唯一线性化点**。不得将三个 `.db/.wal/.shm` 分别 rename 后宣称整体原子；也不得先清旧库再填新库。新指针至少包含格式版本、generation、操作 epoch 和指针自身字段的校验值；不能把会随正常记账变化的整个 DB hash 当永久指针校验值。它与安全配置没有共同反序列化入口。

切换后以新代打开数据库并做轻量完整性/版本检查，发布新的 repository epoch，使旧屏幕和后台回调全部失效；确认可打开后写 `COMMITTED` journal，返回成功。检查失败而未向用户确认成功时，可在仍持维护屏障下原子回退旧 active，保留错误候选供用户选择清理；不能在新代已有成功写入后静默回退。

Android 实现以内部同卷原子 rename 与文件/目录同步组成该协议，平台层负责结果核验；这是待故障注入验证的设计保证，不能仅依据 API 名称就声称断电已测。SQLite 候选库的复制/压缩可使用经过选定驱动验证的 backup API 或 `VACUUM INTO`；后者中断输出可能不完整，只有完成并检查后的文件可被选为保护代。[SQLite VACUUM](https://www.sqlite.org/lang_vacuum.html)

| 中断位置 | 下次启动的确定行为 |
| --- | --- |
| 无 PREPARED，active 指旧代 | 旧代继续；删除无 owner 的 staging/new generation |
| PREPARED 存在，active 指旧代 | 未切换；保持旧代，取消候选或重新走用户确认，不后台自动替换 |
| PREPARED 存在，active 指新代 | 指针切换已发生；验证新代后完成 journal，显示已恢复结果；不再次导入 |
| COMMITTED 存在，active 指新代 | 新代继续；按保护副本策略清理，不重复执行操作 |
| active 损坏、指向不存在/不一致代，或新代验证失败 | 保持锁和遮盖，进入恢复维护页；依据已验证 journal/保护副本让用户确认恢复，不能按“修改时间最新”猜一个库 |

保护副本策略：保留最近一次成功替换或迁移前的完整保护代，数据管理显示日期、原因和占用；下一次替换准备成功前不能先删掉唯一有效保护代。新保护代和新活动代均可用后，旧保护代标记待清理；清理失败保留清单并重试，不因此宣称副本已删除。用户可认证后主动清除。恢复保护副本也走相同替换/确认流程，不允许后台静默回滚账务。

## 8. 删除、清空及残留

普通删除只改变有效状态和删除组，回收站仍存在于备份；永久清理才删除正式实体，同时保留不带原金额/备注的最小批次成员“已移除”状态。解除来源去重占位必须是明确规则：永久清理或成功批次撤销后，相同来源再次导入可以重新新增，不能因不可见 tombstone 永久禁止重新记账。外部来源身份已删除后，不再承诺能辨认“此前已移除”；只有自身稳定 ID 仍可匹配已有最小批次历史时，才可提示该历史事实，不为提示额外保留来源隐形墓碑。

永久清理事务执行 DELETE **之前**在写连接启用 `secure_delete=ON`，随后在独占维护窗口执行检查点及截断 WAL，并按需要生成压缩后的新 generation；禁止遗留自动查询缓存、原始导入文件或明文导出临时副本。既有不可变保护副本可能仍包含删除前的账目，清理确认须说明并提供单独清除内部保护副本的入口；只有“清空全部数据”必须一并移除。SQLite 的安全删除/压缩可以减少数据库文件可恢复内容，不能保证闪存磨损均衡、文件系统快照、系统取证或外部文件中不存在旧字节；UI 用“从当前账务库永久移除”，不承诺“物理不可恢复”。[SQLite secure_delete](https://www.sqlite.org/pragma.html#pragma_secure_delete)、[SQLite VACUUM](https://www.sqlite.org/lang_vacuum.html)

清空全部数据按以下顺序串行执行：

1. 用户二次范围确认及敏感认证；取得维护屏障，撤销所有作业 epoch，取消并等待导入/OCR/导出/恢复作业，阻止旧回调落盘。
2. 持久化 `CLEARING` 清单，列出全部活动/保护/候选 generation、staging、内部导出、草稿及 URI grants。先记录不可恢复的清空意图再逐项删除，进程死亡后从清单继续，禁止把剩余旧代当“恢复保护副本”。
3. 清理所有业务代和内部副本，释放授权；创建全新默认业务代，保持本机 `lockEnabled`，并在新 epoch 下发布初始化完成。首次清理后如有文件删除失败，保持维护错误页并允许重试，不能显示“全部清空”。
4. 不删除用户保存到外部目录的 JSON、加密备份、XLSX 或相册原图。此前外部写失败但删除不了的目标仍在清单里提醒用户，不能假装收回了其他应用已有内容。

系统设置中的“清除应用数据”与卸载由 Android 管理，应用不能保证拦截、认证或恢复；它与应用内清空不是同一种保证。

## 9. 导入身份、冲突与批次撤销

来源身份与本地账务身份分离，否则用户移动账本/改分类后重导会重复入账。以下是标准化对象契约，并非任何支付平台文件列名。实体名与持久字段以主技术方案 3.2 为准；本文的快照/基线是对应字段的职责，不要求重复建第二套实体表。

| 对象 | 必须保留 | 用途 |
| --- | --- | --- |
| `LocalRecord` | 稳定本地 ID、当前账务字段、revision、删除状态 | 用户编辑与退款不变量 |
| `SourceIdentity` | provider、scope、eventKind、externalId；关联本地 entryId | 只有适配器证实该范围内唯一才可成为确定身份；平台不同或范围不同均不碰撞；eventKind 是不可变身份分量，不能来自可变交易状态 |
| 来源快照字段 | SourceIdentity 内的 canonicalSource、normalizationVersion、sourceDigest；adapterVersion 在批次中可追溯 | 与本地金额/分类/备注独立且不因用户编辑改写；用于判断来源本身是否变化 |
| `ImportedBaseline` | 新增时 localRecord revision、业务规范化摘要、来源关系摘要 | 判断整批撤销是否仍安全；任意后续编辑即使改回原值也通过 revision 检出 |
| `ImportBatch` | id、submissionId、adapterId/version、fileDigest、previewDigest、mappingDigest、counts、impact、createdAt、status；成员关系在 ImportMember | submissionId 为持久唯一幂等键并随备份保留；物理 generation/epoch 属本机协调器，不作为可迁移批次字段；不保存原始文件全文 |

sourceDigest 使用版本化规范化后的语义字段，不哈希 Excel 文件原始字节。adapterVersion 可追溯解析规则，但不能简单加入稳定 identity 导致每次升级产生一批新身份；规范化版本变化须提供已测试的对照规则，否则转人工冲突，不重新新增。未知 source scope 不得用空字符串拼接后当唯一；只能作为弱重复信号。

| 匹配情况 | 处理 |
| --- | --- |
| DawnYew ID 相同、规范化本地内容相同 | 确定重复；跳过 |
| DawnYew ID 相同、本地内容不同 | 冲突；首版不覆盖 |
| 来源身份相同、来源快照语义相同，本地已移动/改分类 | 确定重复；保留本地修改 |
| 来源身份相同、来源金额/状态/退款语义变化 | 冲突；不得用“现在不同”新建第二条，也不覆盖第一条 |
| 来源相同但库中记录在回收站 | 已存在但已删除；不自动复活、不复制；提示通过回收站处理 |
| 身份指向本地一条记录，而另一个提供的 ID 指向另一条 | 身份冲突，整行阻止；不能选其中一个覆盖 |
| 无稳定身份、跨截图/手动/文件同日同额 | 仅疑似重复，由用户选择；文件 hash 不能作为整文件永久跳过规则 |

文件摘要用于审计与同一提交重试，不代表所有相同字节永远不能再次导入。`submissionId` 来自用户确认的那一次预览；提交事务写入唯一约束 `submissionId`、批次、映射显式创建的容器、正式流水及来源关系。重试读取同一成功结果。去重和引用校验必须在获取写屏障后的事务内重跑，防止两个并发预览均判断“没有”后双写。

批次撤销先验证每个当前存活的新增成员：ID、revision、归属、删除状态与基线完全符合；无批次外退款或其他依赖；本批成员未被单独永久清理。任一后续编辑、移动、删除、永久清理或外部引用都拒绝整批撤销并列出影响项。全部满足后一个事务移除本批流水及批内退款、释放相应有效来源关系、标记批次已撤销。原有流水和本次显式创建的账本/分类均保留；再次撤销只返回已撤销。批次自身及成员最小历史状态进入完整备份，恢复后规则不变。

模板扩展接口仅接受 `inspect → identifySupportedFormat → streamRows → normalize`，输出标准候选/排除/错误/待处理；它不能直接写数据库、认证或联网。具体适配器注册表当前为空，未知文件显示不支持，不根据列相似度猜出“微信/支付宝”。未来接入须提供脱敏样本、唯一范围证明、时间金额/退款状态语义、预期行数与合计、兼容版本和坏例；直接历史导入仍是产品 P0，但不因本轮只设计接口而标记功能已交付。

## 10. 不可信输入的资源预算

限额在隔离复制、解压、解析、规范化和预览各层执行；不能只检查 provider 声明的文件大小。后台执行与取消贯穿管线，UI 不在主线程读 ZIP、执行 KDF 或扫描数据库。

| 类型 | 本轮默认限额/规则 |
| --- | --- |
| 历史文件/表格交换 | 压缩/原始输入最多 20 MiB；最多 10,000 个候选业务记录；压缩结构实际解压累计最多 100 MiB。最终具体适配器可申请更低限制，提高须评审真实样本 |
| XLSX/ZIP 结构 | 最多 256 个成员；单个 XML 成员最多 32 MiB；累计仍受 100 MiB 限制；不递归展开内嵌归档；ZIP 每项压缩比最多 1000:1 并持续按真实解压字节计数 |
| XML/表格 | 禁止 DTD、外部实体、外部关系、公式和宏；不读取公式缓存当金额；最大 XML 深度 32、单文本节点最多 32 Ki 字符且 UTF-8 最多 64 KiB、单表 128 列；拒绝稀疏超大坐标、重复单元格或行号、超量 sharedStrings，不能按单元格坐标直接申请大数组 |
| JSON token | 最大嵌套 32、对象成员数 128、属性名最多 128 字符、单字符串最多 32 Ki 字符且 UTF-8 最多 64 KiB、数字 token 最多 32 字符；业务字段另用更严格长度。拒绝重复键、NaN/Infinity、指数型金额、尾随内容及无限嵌套 |
| 完整 JSON/加密备份 | 不套用 20 MiB/10,000 条交换限额，不设额外空间比例或总记录数硬上限；按流式块与暂存库处理，使用有检查的 64 位计数。根据输入隔离副本、候选新代、保护副本、索引及 SQLite 临时/WAL 的实际占用与当前可用空间动态预算，始终保留 128 MiB 安全余量。预估不确定时分阶段持续预检，捕获 ENOSPC 后取消候选并保持旧库，不以启动时固定比例拒绝实际空间充足的完整备份 |
| 图片 | 只允许一张 PNG/JPEG；输入最多 20 MiB、解码前检查最长边 12,000 像素且总像素不超过 24MP；按目标采样解码，OcrPort 另有峰值内存预算；拒绝多帧/动图、异常尺寸或不符魔数 |

ZIP 成员名拒绝绝对路径、`..`、反斜线、NUL、规范化后碰撞和重复名称；只解析必要 OPC 成员，不把任意 ZIP 写入真实目录。XML relationships 解析为包内规范路径，拒绝外部目标与越界 URI；`.xlsx` 必须有正确 Content Types、工作簿和关系结构，不能把 CSV 改后缀、普通 ZIP、`.xls`、`.xlsm` 或加密工作簿当受支持文件。输出金额为真实数值单元格，备注/名称/日期为明确文本，不写公式节点；以 `= + - @` 开头的文本也仍是文本。

完整恢复的内存不随全部记录数增长；ID 唯一性、引用、来源查重、退款汇总均通过 staging 数据库索引/聚合校验。流式解析不意味着可无视磁盘和 CPU：长作业持续展示进度/已读字节，可取消；每个 token、行和块之间检查取消，I/O 无进展超时关闭句柄。支持大备份的证明包括大于 20 MiB 且超过 10,000 条的往返，不宣称无限容量。

## 11. 系统备份、日志与平台接口

Android Manifest 设置 `allowBackup=false`，并配置旧版 `fullBackupContent` 及 Android 12+ `dataExtractionRules`，对 cloud-backup 和 device-transfer 均排除 root/file/database/sharedpref/external 以及对应 device-protected 数据域。账务/保护/安全数据实际位于 noBackupFilesDir，形成额外边界；不依赖部分厂商对 allowBackup 的解释。官方明确部分 Android 12+ 厂商设备仍可能允许 D2D 迁移，且 noBackupFilesDir 不参与自动备份，因此需要配置与设备验证共同证明。[Android Auto Backup](https://developer.android.com/identity/data/autobackup)

合并清单不含 INTERNET、无需读取全部文件、非必要 exported 组件一律关闭。外部接收 Activity 仅接受窄的 share/import Intent，所有操作仍受锁保护。日志只记录事件码、耗时、计数与随机作业 ID，不含 URI 路径、账本名称、金额、备注、原始来源 ID、图片文字或口令；崩溃不上传。系统任务预览、通知、剪贴板和错误弹窗不能泄露敏感内容。

| 共享接口 | Android 实现职责 | iOS 本轮范围 |
| --- | --- | --- |
| `AuthenticationPort` | 能力检查、BiometricPrompt、attemptId、取消 | 仅契约，无 LocalAuthentication 实现 |
| `SecureWindowPort` | 遮盖、FLAG_SECURE、前后台/锁屏事件 | 仅事件与保护请求契约 |
| `LocalFilePort` | 本地 SAF/picker、provider 检查、授权生命周期、限流读取/写入 | 仅流式输入/输出及取消契约 |
| `PasswordBackupCryptoPort` | libsodium 初始化/KDF/secretstream，固定协议测试向量 | 仅字节协议与接口，不编译 Apple 产物 |
| `AtomicGenerationStore` | 内部文件、同步、原子指针、journal 与清理 | 仅原子操作语义，无 NSFileManager 实现 |
| `PlatformBackupPolicy` | Manifest/XML 排除与安装后核验 | 仅“不进入系统云备份”的能力要求 |

协议层只接收字节流、时钟、随机数、安全能力与标准错误，不引用 Android Uri、Context、BiometricPrompt、Java File 或 UIKit。平台端必须明确 `Unsupported/Cancelled/Unavailable/PermissionLost/InvalidInput/InsufficientSpace/Corrupted/VersionTooNew`，不能一律吞错转成空数据。

## 12. 交叉评审反例与验证门槛

| 高危反例 | 必须阻止它的设计点 | 验证 |
| --- | --- | --- |
| 从未启用锁的旧备份恢复，把当前设备锁关掉 | SecurityState 与业务协议/偏好分离 | AC-28、AC-30 |
| 打开系统认证触发 onPause，返回又锁，再弹认证无限循环 | attemptId + 有界 ExternalFlow + 一次结果消费 | AC-29～AC-31，旋转/Home/熄屏真机矩阵 |
| 文件 picker 超过两分钟，旧认证继续执行清空/替换 | 单调时钟期限、绑定 permit、返回只重认证不重开 picker | 增补锁超时测试 |
| 有 SHA-256 就把明文备份视为可信来源 | 摘要不等于真实性、全部领域校验 | 篡改金额并重算摘要仍不能绕过领域约束 |
| 验证了前几个加密块就替换库，截断尾部被接受 | FINAL+EOF、暂存隔离、切换点 | 每帧截断/重排/重复/尾部注入/头篡改 |
| 恶意头写 1 TB KDF 内存或巨大块长 | 80 字节头与固定参数白名单 | KDF 调用前拒绝，内存平稳 |
| 把运行中 SQLite 的 `.db` 复制为保护副本，遗漏 WAL | 驱动一致快照或关闭后的完整代 | 写入中断与保护副本校验 |
| active 已切到新代但 journal 未 COMMITTED，重启再次追加导入 | active 为唯一切换点、整体替换 | 每个持久步骤前后杀进程与故障注入 |
| 清空时后台 OCR/导入完成，把旧数据写回空库 | epoch 撤销、维护屏障、CLEARING journal | 竞争测试、重启继续清理 |
| 改分类后 sourceDigest 跟着改变，重新导入同一笔 | 不可变来源快照与可编辑本地字段分离 | AC-39 |
| 两个来源同流水号碰撞；未知来源范围写空串作为唯一键 | 来源命名空间/范围经样本证明 | AC-39～AC-41 |
| 批次记录编辑后又改回，内容 hash 一样就允许撤销 | revision 与摘要双验证，批外依赖检查 | AC-27、AC-37 |
| JSON 恢复或 XLSX 往返丢失来源快照，次日导入重复 | 完整协议/模板携带来源身份与规范化版本 | AC-15、AC-39；模板未冻结时不得宣称通过 |
| 压缩文件 1 MiB，但 sharedStrings 展开数 GB | 真实字节、token、成员数量、稀疏坐标限额 | AC-35 |
| 没有 INTERNET 权限，却打开任意云 ContentProvider 触发联网 | 本地 provider 边界及跨 UID 流量验证 | AC-01、AC-02，断网/联网均测 |
| 永久删除只删活动表，旧保护库仍保留且 UI 声称物理擦除 | 保护副本显式生命周期、清空清单、准确说明 | AC-19、AC-23 |

实现准入前须确定 Android libsodium ABI 打包/许可/SBOM及 16 KiB 页兼容，最低设备 KDF 时间与内存，SAF 系统 provider 列表，代际指针 fsync/断电行为，备份排除和应用锁厂商生命周期测试方案。实现后须以真实执行记录关闭这些项；本文无任何“构建通过”“加密审计通过”或“真机验证通过”的暗示。模板缺口保持显式，不能用伪适配器、猜测字段或仅有文件选择按钮替代历史导入验收。
