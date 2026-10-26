# 整体架构审查：Android 平台、安全与生命周期

> 历史记录：小游戏接入决定已于 2026-10-26 被独立产品方案取代。DawnYew 已移除 2048 运行依赖、保留猜拳；本文当时的代码路径和游戏验收结果不代表当前工程状态。见[拆除记录](2026-10-26-games-detachment.md)。

日期：2026-10-25。分支：`p/duanyao.0516/codex/dawnyew-android-app`。审查对象是包含本轮悬浮记账改动的当前工作区，不仅是 HEAD。本阶段仅阅读源码、写此报告，未修改产品源码、运行 Gradle 或 ADB、切换分支、提交代码。

## 结论与验证边界

当前分层可以继续承接独立的离线小游戏：Android 层负责系统授权、窗口、硬件和文件句柄；共享层负责账务规则、命令和提交回执；代际存储层负责数据库切换、原子提交和故障恢复。无需为了新增小游戏重写这些层，也不应让游戏进入账本命令、备份协议或截图服务。

本次源码审阅未发现新的 P0/P1 平台阻塞项。发现两项具体 P2 债务：旧截图入口的通知寿命未与内存 token 一起交给系统管理；部分私有暂存文件删除失败未被报告。另有几项有界的职责与资源策略改进，见后文。该结论只覆盖下列已追踪链路，不等于全应用安全审计或所有 OEM/API 版本验收。

本轮悬浮功能的构建、单测和 API 36 ARM64 模拟器证据由主流程独立完成，见[悬浮记账实现与验证](2026-10-25-floating-capture-verification.md#L24)。其设备测试验证了另一个 UID 的 App 上实际截图、生产 OCR、保存、确认修改、通知撤销和磁盘重开；没有覆盖 API 30、OEM 真机、真实生物识别接续或 Release 安装运行，不能把这些范围算作本报告已验证。

## 责任边界与调用链

| 责任 | 当前所有者与证据 | 应保留的边界 |
|---|---|---|
| 进程级会话与恢复入口 | [DawnYewApplication](../../androidApp/src/main/kotlin/com/dawnyew/app/DawnYewApplication.kt#L29) 持有 SessionCoordinator、GenerationRepository、待返回外部操作与短时悬浮动作 | 不持有 Activity；进程重启不恢复授权、密码、截图或未确认候选 |
| 页面授权与外部结果消费 | [MainActivity](../../androidApp/src/main/kotlin/com/dawnyew/app/MainActivity.kt#L89) 注册 ActivityResult，协调前后台、认证、文件选择器、恢复预览和截图接续 | 平台负责授权时序；账务验证仍交给共享命令与仓库 |
| 真实全局悬浮窗口 | [FloatingCaptureAccessibilityService](../../androidApp/src/main/kotlin/com/dawnyew/app/capture/FloatingCaptureAccessibilityService.kt#L90)、[FloatingCaptureOverlay](../../androidApp/src/main/kotlin/com/dawnyew/app/capture/FloatingCaptureOverlay.kt#L69) | 使用系统 accessibility overlay，服务窗口不塞进本 App Compose 页面树 |
| 截图业务接续 | [FloatingCaptureWorkflow](../../androidApp/src/main/kotlin/com/dawnyew/app/capture/FloatingCaptureWorkflow.kt#L52)、[CaptureBookingCoordinator](../../shared/presentation/src/commonMain/kotlin/com/dawnyew/presentation/CaptureBookingCoordinator.kt#L1) | 平台管理像素、短时租约和通知；共享协调器核对提交回执；领域事务决定去重、分类补建和撤销 |
| 数据集与持久化原子边界 | [GenerationRepository](../../platform/android/src/main/kotlin/com/dawnyew/platform/storage/GenerationRepository.kt#L225) | 认证、物理代际、pending marker 和执行在同一个仓库 mutex 内检查；UI 检查不能代替仓库检查 |
| 其他硬件工具 | [AndroidUtilityPlatform](../../androidApp/src/main/kotlin/com/dawnyew/app/AndroidUtilityPlatform.kt#L25)、[UtilityPlatform](../../shared/presentation/src/commonMain/kotlin/com/dawnyew/presentation/UtilityPlatform.kt#L17) | 共享接口不泄露 Android 资源；Activity 平台适配器负责前后台、权限和释放 |

## 安全与会话契约

1. **离线与组件入口。** [Manifest](../../androidApp/src/main/AndroidManifest.xml#L13) 显式移除 INTERNET 和 ACCESS_NETWORK_STATE，关闭系统备份及明文流量；截图服务与撤销接收器不导出，AccessibilityService、Quick Settings Tile 使用相应系统绑定权限。无障碍配置只声明截图能力，不请求读取窗口内容或手势能力，见[配置](../../androidApp/src/main/res/xml/floating_capture_accessibility.xml#L2)。本次全局球不申请 SYSTEM_ALERT_WINDOW，也不读取无障碍节点。系统受保护窗口截图失败作为失败处理，不能据此绕过系统保护。
2. **应用锁是访问门，不是账本文件加密。** [AppLockStore](../../platform/android/src/main/kotlin/com/dawnyew/platform/security/AppLockStore.kt#L8) 使用私有 AtomicFile 保存锁状态；已有账本而锁文件缺失、状态损坏时按需要修复的已上锁状态处理。校验和用于发现损坏，并非抗设备完全失陷的加密措施。手动加密备份是另一项能力，不应把它描述为本地数据库已加密。
3. **授权和离开应用有明确时限。** [SessionCoordinator](../../platform/android/src/main/kotlin/com/dawnyew/platform/security/SessionCoordinator.kt#L17) 将认证、文件选择器返回绑定到进程、epoch、租约及两分钟期限；普通后台离开撤销会话，只有注册过的外部选择器首次离开走暂停契约。Activity 的 [onPause/onDestroy](../../androidApp/src/main/kotlin/com/dawnyew/app/MainActivity.kt#L181) 遮盖页面并停止页面能力；[FLAG_SECURE](../../androidApp/src/main/kotlin/com/dawnyew/app/MainActivity.kt#L318) 始终开启。数据库查询/写入还会检查授权，不仅依赖可见 UI。
4. **息屏和物理数据集切换是独立失效条件。** [Application 的息屏处理](../../androidApp/src/main/kotlin/com/dawnyew/app/DawnYewApplication.kt#L65) 清空旧截图交接、悬浮待处理请求、服务 UI，并撤销会话。新悬浮工作流同时检查屏幕、功能设置、服务连接、请求 generation、数据库 epoch 和 deadline；[保存及撤销进入 commitGate 后](../../androidApp/src/main/kotlin/com/dawnyew/app/capture/FloatingCaptureWorkflow.kt#L85)再次检查会话，避免排队期间授权变化。
5. **外部 URI 延迟到授权后打开。** MainActivity 先接收分享或选择器结果的标识，只有当前前台会话才消费；[LocalDocumentAccess](../../platform/android/src/main/kotlin/com/dawnyew/platform/files/LocalDocumentAccess.kt#L15) 在每次打开前检查 content scheme、provider 包身份、系统标记和本地卷。`EXTRA_LOCAL_ONLY` 只是选择器提示，真正限制在读取边界执行。此白名单有意不支持任意第三方/OEM 云提供方；属于兼容范围，不应因某个 provider 被拒而放宽成任意 URI。

关闭应用锁时，后台账务访问不再要求本 App 的生物识别会话，这是配置语义；截图和硬件仍须遵循各自的息屏/设备锁检查。锁定时旧账本快照可能仍在进程内，界面遮盖与仓库拒绝访问不等同于内存清零；产品描述应保持这一范围。

## OCR 与系统资源生命周期

| 入口或资源 | 当前契约 | 关键源码 |
|---|---|---|
| 分享/选择本地截图 | URI 输入写私有暂存，限制 20 MiB；文件头只接受 PNG/JPEG，限制像素规模，解码为最长边不超过 1920 的软件 Bitmap；识别完成回收 Bitmap | [ImageInputService](../../platform/android/src/main/kotlin/com/dawnyew/platform/files/ImageInputService.kt#L19)、[ScreenshotImageDecoder](../../platform/android/src/main/kotlin/com/dawnyew/platform/files/ScreenshotImageDecoder.kt#L17)、[CaptureRecognizer](../../androidApp/src/main/kotlin/com/dawnyew/app/capture/CaptureRecognizer.kt#L1) |
| 单次 MediaProjection | 用户授权产生一次 projection/display；ImageReader、VirtualDisplay、projection 和工作线程由服务成对清理；不持久化授权结果复用 | [ScreenCaptureService](../../androidApp/src/main/kotlin/com/dawnyew/app/capture/ScreenCaptureService.kt#L34)、[释放路径](../../androidApp/src/main/kotlin/com/dawnyew/app/capture/ScreenCaptureService.kt#L162) |
| 系统截图监听 | 建立当前 MediaStore 基线，只处理新截图；有界卷数、查询行数与识别次数；查询 CancellationSignal、cursor.use、权限/会话/代际复查；停止时注销观察器与接收器 | [ScreenshotWatchService](../../androidApp/src/main/kotlin/com/dawnyew/app/capture/ScreenshotWatchService.kt#L106)、[查询与访问核对](../../androidApp/src/main/kotlin/com/dawnyew/app/capture/ScreenshotWatchService.kt#L199)、[清理](../../androidApp/src/main/kotlin/com/dawnyew/app/capture/ScreenshotWatchService.kt#L251) |
| 悬浮截图 | 隐藏球后等待显示帧，调用默认显示屏 takeScreenshot；hardwareBuffer.use → 软件 Bitmap → 有界缩放；过期回调仍关闭 buffer、回收像素；请求状态门只接受当前一次交接 | [等待帧与截图](../../androidApp/src/main/kotlin/com/dawnyew/app/capture/FloatingCaptureAccessibilityService.kt#L113)、[像素转移](../../androidApp/src/main/kotlin/com/dawnyew/app/capture/FloatingCaptureAccessibilityService.kt#L145)、[解绑取消](../../androidApp/src/main/kotlin/com/dawnyew/app/capture/FloatingCaptureAccessibilityService.kt#L290) |
| 原生 OCR 会话 | 进程单例只持有 Application Context；首次加载前校验资产；SingleFlightGate 串行化推理，JNI 返回之前保持所有权，取消不提前释放仍被推理使用的 Bitmap/原生会话 | [AndroidOfflineOcrEngine](../../platform/android/src/main/kotlin/com/dawnyew/platform/ocr/AndroidOfflineOcrEngine.kt#L33)、[SingleFlightGate](../../platform/android/src/main/kotlin/com/dawnyew/platform/ocr/SingleFlightGate.kt#L13) |
| 麦克风噪声工具 | 只有当前前台页面主动启动；权限回调不自动录音。停止递增 generation、终止录音以解除阻塞读取；最后清空 PCM，注销回调，释放 effect/AudioRecord | [AndroidNoiseMeter](../../platform/android/src/main/kotlin/com/dawnyew/platform/tools/AndroidNoiseMeter.kt#L81)、[finally 清理](../../platform/android/src/main/kotlin/com/dawnyew/platform/tools/AndroidNoiseMeter.kt#L205) |

几个截图入口有不同的系统授权来源，不能仅因都输出 Bitmap 就合并成同一个服务父类。已经共用的识别器、输入限制和短时 Inbox 是合理复用边界；后续如收敛，应优先统一短时交接/通知结果契约，保留各自系统生命周期。

## 备份、文件与恢复

备份没有把整个 JSON 文档作为生产路径的内存对象。 [JsonStreamCodec.prepare](../../platform/android/src/main/kotlin/com/dawnyew/platform/files/JsonStreamCodec.kt#L45) 检查重复字段、嵌套深度、单字段长度、记录合法性、计数和摘要；[CanonicalRecordStore](../../platform/android/src/main/kotlin/com/dawnyew/platform/files/CanonicalRecordStore.kt#L7) 使用磁盘排序与有界归并。PreparedBackup.visit 分批交给候选库，close 检查私有暂存目录是否删除成功。兼容测试 helper `read()` 仍会构造全部记录，新增生产调用应继续使用 prepare/streaming，不能因 API 更短而退回 helper。

加密备份由 [BackupCrypto](../../platform/android/src/main/kotlin/com/dawnyew/platform/crypto/BackupCrypto.kt#L73) 先在私有暂存完成认证、验证最终标记及 EOF，才把明文交给调用者；密码数组、派生密钥和原生状态在 finally 清理。加密认证与账务关系检查是两层契约：认证成功仍须经过 JSON 协议与候选数据库验证。

[MainActivity 导出](../../androidApp/src/main/kotlin/com/dawnyew/app/MainActivity.kt#L382) 先生成并验证私有文件，再打开目标，写完重新读取核对摘要；文件已成功导出但本地“最近备份时间”更新失败时，不把有效外部文件误报为未导出。目标写入失败则尝试删除新建文档并报告无法删除的残留位置。[恢复预览](../../androidApp/src/main/kotlin/com/dawnyew/app/MainActivity.kt#L446) 在 provider 读取前绑定 DatasetToken，解析到隔离候选，确认时再次敏感授权与版本核对，取消时丢弃候选。

[GenerationRepository.replaceCandidate](../../platform/android/src/main/kotlin/com/dawnyew/platform/storage/GenerationRepository.kt#L427) 在 mutex 内验证候选 owner、使用状态与数据集基线；通过 PREPARED/COMMITTED journal、原子指针和保护副本切换代际。持久提交关键段不因协程取消停在半个指针写入；打开/校验失败走 failClosed，避免自动拿空库掩盖已有数据。读取与恢复代码需维持“验证失败保留可恢复证据、停止访问”的现有策略。

## 待处理动作分为三种寿命

| 类型 | 持有与恢复方式 | 不可混淆的边界 |
|---|---|---|
| 截图/解锁候选 | [CaptureRuntime](../../androidApp/src/main/kotlin/com/dawnyew/app/capture/CaptureRuntime.kt#L22) 与 [FloatingCaptureWorkflow](../../androidApp/src/main/kotlin/com/dawnyew/app/capture/FloatingCaptureWorkflow.kt#L40) 的进程内 Inbox；短时、单次 token；悬浮只保留一个待处理动作，通知两分钟超时 | 进程退出候选消失，通知不得隐含“可以永久继续”；解锁只续接原动作，不刷新其原始 deadline 或数据集绑定 |
| 系统文件选择器操作 | Application 持有 PendingExternal，租约与数据库 epoch 绑定；[MainActivity 动态注册 key](../../androidApp/src/main/kotlin/com/dawnyew/app/MainActivity.kt#L89) 拒绝旧回调、重复结果；配置变化可接续同一进程动作 | 不把密码、URI 内容或授权放进 SavedState；进程死亡后不能伪造此前批准 |
| 可能已提交的账务命令 | [GenerationRepository.executeInGeneration](../../platform/android/src/main/kotlin/com/dawnyew/platform/storage/GenerationRepository.kt#L225) 写 pending-operation 标识并通过持久回执核查；确认结果后 acknowledge | UI 消失或取消不证明事务未提交，不能自动重复业务命令或清除不确定结果；旧代际不得 lookup/ack 新代际 marker |

新 [inGeneration 视图](../../platform/android/src/main/kotlin/com/dawnyew/platform/storage/GenerationRepository.kt#L283) 对 execute、snapshot、查询、lookup 和 acknowledge 均在所有者 mutex 内检查 expectedEpoch；视图不拥有底层仓库，close 不关闭数据库。相同 datasetId 恢复为另一物理代际仍会失效，避免仅比较逻辑 ID 的竞态。相关测试位于 [GenerationRepositoryTest](../../platform/android/src/test/kotlin/com/dawnyew/platform/storage/GenerationRepositoryTest.kt#L148)。

## 本轮已修问题，不计入未修债务

| 问题 | 当前源码中的修复 |
|---|---|
| R8 重命名导致截图提交回执名称不匹配 | [Domain.stableCommandType](../../shared/core/src/commonMain/kotlin/com/dawnyew/core/Domain.kt#L238) 对 RecordCapture/UndoCapture 使用固定名称 |
| 同 datasetId 的物理代际恢复绕过旧截图绑定 | inGeneration 将代际检查放进真实操作 mutex；工作流在提交时使用绑定视图 |
| 排队保存/撤销期间会话变化 | [commitGate 内检查](../../androidApp/src/main/kotlin/com/dawnyew/app/capture/FloatingCaptureWorkflow.kt#L150) 覆盖屏幕、session epoch 与授权 |
| 系统解绑后仍可续接旧候选 | [detach](../../androidApp/src/main/kotlin/com/dawnyew/app/capture/FloatingCaptureAccessibilityService.kt#L290) 取消工作流待处理项，available 检查真实连接 |
| 待解锁通知比候选活得久、投递失败仍假装排队成功 | [unlock](../../androidApp/src/main/kotlin/com/dawnyew/app/capture/FloatingCaptureNotifications.kt#L115) 设置系统 TTL、返回投递结果；queue 失败回收候选 |
| 通知权限竞态把已提交账单误报未保存 | [publish 与 saved/undone](../../androidApp/src/main/kotlin/com/dawnyew/app/capture/FloatingCaptureNotifications.kt#L50) 捕获通知发送异常并提供 Toast 反馈，不逆转提交结论 |
| 确认面板无分类时拒绝保存、默认来源丢失 | [分类选项](../../androidApp/src/main/kotlin/com/dawnyew/app/capture/FloatingCaptureOverlay.kt#L203) 允许 null 交领域原子补建；[noteWithDefaults](../../androidApp/src/main/kotlin/com/dawnyew/app/capture/FloatingCaptureWorkflow.kt#L108) 保留默认账本/分类/时间来源 |

## 仍存在的有界债务

| 优先级 | 证据、触发与影响 | 建议范围及验证 |
|---|---|---|
| P2：旧截图通知可能残留 | [CaptureNotifications.showResult](../../androidApp/src/main/kotlin/com/dawnyew/app/capture/CaptureNotifications.kt#L45) 没有 setTimeoutAfter；[CaptureRuntime](../../androidApp/src/main/kotlin/com/dawnyew/app/capture/CaptureRuntime.kt#L48) 依赖进程 Handler 两分钟后取消。进程被杀会清空 token，却不能执行 Handler 清理，系统通知可能仍显示“截图已准备好”。点击应失败关闭，不会恢复像素或绕过授权，但会给用户过期入口 | 为旧结果通知设置与 Inbox 一致的系统 TTL；让 showResult 返回投递是否成功，避免权限消失时仍保留无入口候选。复用新悬浮的小型通知结果模式，不合并各服务。测试通知投递失败回收，并设备验证进程退出后的过期行为 |
| P2：部分私有暂存清理失败静默 | [ImageInputService.finally](../../platform/android/src/main/kotlin/com/dawnyew/platform/files/ImageInputService.kt#L48) 忽略 delete 返回值；[导出 finally](../../androidApp/src/main/kotlin/com/dawnyew/app/MainActivity.kt#L440)、[解密恢复 finally](../../androidApp/src/main/kotlin/com/dawnyew/app/MainActivity.kt#L467) 也有同类路径。删除失败时明文截图/备份可能留在私有 noBackup 暂存直到下次启动清理，不是外部目录或联网泄露，但与及时清理契约不一致 | 在最窄文件层收敛“删除或报告”的暂存所有者，异常处理保留原异常并附清理失败；已成功提交不能被清理错误改称未提交。用注入文件操作测试删除失败、取消和成功后清理，不全局泛化所有文件 API |
| P3：Activity 同时编排多个独立流程 | [MainActivity](../../androidApp/src/main/kotlin/com/dawnyew/app/MainActivity.kt#L373) 承担备份、恢复、CSV、图像、认证、导航接续与 busy 状态；这些流程共享 fileJob、external、预览和 callback 注册，后续修改容易触及无关生命周期 | 后续改文件流程时提取 Activity 所有的 FileOperationCoordinator，显式借用会话/仓库/展示接口；Application 只保存进程级短时记录。游戏使用独立宿主/控制器，不继续向 Activity 添加玩法状态；不要求本轮整体重构 |
| P3：OCR 原生模型的内存回收策略未接入宿主 | [AndroidOfflineOcrEngine.release](../../platform/android/src/main/kotlin/com/dawnyew/platform/ocr/AndroidOfflineOcrEngine.kt#L68) 有串行释放入口，但当前生产路径只调用 getInstance/recognize，未发现 release 调用者。会话缓存至进程结束有利于重复识别，也形成稳定原生内存占用；未发现 Activity 引用泄漏证据 | 先测量低内存设备后台驻留，再决定 idle/trim 时由进程级策略调用 release；必须与 SingleFlightGate 协作，不在 Activity.onPause 直接释放推理资源 |
| P3：启动时暂存清理在主线程 | [Application.onCreate](../../androidApp/src/main/kotlin/com/dawnyew/app/DawnYewApplication.kt#L61) 同步 deleteRecursively；大备份中断后暂存数量/体积可能放大冷启动 I/O。当前没有耗时跟踪证明已发生 ANR | 如冷启动测量确认成本，将清理合入现有异步 initialize/awaitReady 启动门；保留清理失败停止访问的语义，不为提速跳过清理 |
| P3：其他命令回执仍使用类名 | [stableCommandType 的 else](../../shared/core/src/commonMain/kotlin/com/dawnyew/core/Domain.kt#L242) 仍来自 command::class.simpleName。新截图协议已修复，不能据此声称所有历史命令跨 R8/版本都有稳定 kind | 下次改变回执持久化协议时定义稳定命令 kind 与兼容映射，补旧回执/升级测试；不在本轮无版本方案地改写所有历史 receipt |

以上两项 P2 是可独立修补的通知与文件收尾问题，不构成让游戏接入账本存储的理由；P3 也不要求在开发首个小游戏前完成全部重构。

## 后续离线小游戏的接口与存储建议

1. **规则独立。** 用无 Android/账务依赖的 Kotlin 模块承载规则、状态和状态校验，2048 通过输入方向和注入的随机源产生下一状态。不能把游戏记录加入 LedgerState、Command、finance preferences 或账本备份实体。
2. **插件边界是显式的编译期注册。** GameDefinition 采用稳定 gameId、标题、存档 schemaVersion；玩法状态编码、规则和 UI 适配器分别注册。只暴露游戏所需的窄 GameStore 接口，不把 Application、LedgerRepository、Context、任意路径或服务权限作为插件上下文。首版无需下载代码、反射发现、WebView、Python 或其他运行时。
3. **存档物理隔离。** Android 适配器在私有 `noBackupFilesDir/games/` 下按白名单 gameId 使用 AtomicFile 或等价原子替换；写入前限制长度与校验 schema，读取验证棋盘尺寸、格值、分数和状态一致性。损坏/未知版本只影响本游戏，返回可解释的重置状态，不触发账本恢复、清库或仓库 failClosed。清空账务、恢复账本备份不能删除游戏存档，游戏重开也不能碰账本目录。
4. **并发与恢复明确。** 同一游戏的读写串行化，使用不可变快照；旧异步保存不能覆盖较新进度，重开不能被旧保存复活。当前棋盘与最高分的原子边界需在设计中选定；如果放在一个文档，重开仍保留最高分。保存失败向宿主暴露状态，不能只改 UI 后宣称下次可恢复。
5. **服从宿主生命周期。** 页面状态可以跨配置变化保留，进程恢复依赖已验证的独立存档。应用锁仍遮盖并禁止操作；后台暂停计时/动画/输入，不申请无障碍、媒体、麦克风权限，也不创建前台服务。纯 2048 不需要后台任务。游戏宿主应使用现有导航与 Material 主题，不复用截图 overlay 窗口。
6. **首版验证聚焦实际合同。** 规则测相邻合并一次、移动无效不生成新块、胜利继续、无路可走、随机边界；存档测损坏、旧 schema、原子写失败、快速连续保存/重开、进程重读。集成测旋转、返回、后台/锁屏、再进入及财务备份/清空对游戏存档的隔离。纯逻辑/文件测试与设备手势验证分别报告。

小游戏可以借鉴现有 UtilityPlatform 的窄接口和独立配置方式，但不向该硬件工具接口塞入全部游戏方法；新的责任应由独立游戏模块和宿主承担。
