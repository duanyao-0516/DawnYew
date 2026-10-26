# 全局悬浮识图记账实施计划

> 使用 subagent-driven-development 执行，平台、账务、设置通知分别归属独立文件，完成后交叉审阅。用户已批准方案，并明确必须在其他 App 上方显示。

**目标：**在其他普通 App 中点击全局悬浮球，读取真实屏幕并离线识别，直接记账或按设置弹一次确认浮层，成功后发含查看/修改/撤销操作的通知。

**架构：**Android AccessibilityService 创建 TYPE_ACCESSIBILITY_OVERLAY 并按需调用 takeScreenshot；截图和确认 UI 不依赖 MainActivity。领域命令在既有 Room 事务中完成记账、查重和回执；设置页负责授权检查与接续。现有选图/分享/快捷设置按钮保留原有人工确认语义。

**技术：**Android API30+、原生全局 View、现有 PP-OCRv5/ONNX/OpenCV、Kotlin coroutines、shared/core + presentation + Room。

## 明确决策

- 不使用 App 页面内的 Compose 悬浮组件冒充全局浮球；不增加 SYSTEM_ALERT_WINDOW，使用无障碍服务本身的 overlay token。
- 设置开关先检查通知权限，随后说明用途并打开系统无障碍授权页。设置保存开启意图，用户授权并返回后接续启用；显示“待授权”和“已连接”真实状态。
- 默认直接记账，提供“入账前确认”开关；球可拖动吸边、闲置半透明，点击临时隐藏后截图。确认浮层可编辑金额、类型、账本、分类、时间、备注。
- 金额无法确定时进入补全确认，不以支付渠道、退款/收入等文案拒绝。缺日期采用点击时本机时间；默认最近账本和对应方向的“其他”分类。无可用分类时由领域事务补建截图分类。
- 每次点击唯一 operationId；同截图指纹不重复入账，回收站中的同图不复活。同日同额不同截图不被强制拒绝。
- 撤销核对 OCR 来源、创建操作、revision 和关联退款，旧通知不能撤销已经编辑的账单。
- 保留现有应用锁：不因无障碍授权解锁整个账本。锁定时只保留短时内存请求并通过通知正常解锁接续。锁屏、关闭功能或切数据集终止未提交请求。
- 截图/识别文本不写日志、不上传网络；设备样本仅在隔离测试环境使用。用户原始图片、已有本机配置和历史改动保持不变；本轮不提交推送。

## 文件归属与接口

平台 agent：`FloatingCaptureAccessibilityService`、`FloatingCaptureOverlay`、`FloatingCaptureRuntime` 与截图请求/坐标测试。负责 View 生命周期、硬件 buffer→软件 Bitmap、≤1920 缩放、回调取消和释放。

账务 agent：`Command.RecordCapture`、`Command.UndoCapture`、`CaptureDomain`、`CaptureBookingCoordinator` 及 core/presentation 测试。提交必须完成 execute→lookupOperation 核验→acknowledgeOperation。

root：`FloatingCapturePreferences`、`FloatingCaptureReview`、`FloatingCaptureWorkflow`、设置 UI/Controller、Application 接入、结果通知及 MainActivity 正常解锁后的路由。

```kotlin
// 跨平台账务接口。金额为正整数分，Direction 由被选金额的符号推导。
CaptureBookingRequest(operationId, amountMinor, direction, capturedAt,
    fingerprint, engineVersion, occurrence, note, ledgerId, categoryId)
CaptureBookingCoordinator.record(request, expectedDatasetId)
CaptureBookingCoordinator.undo(datasetId, entryId, creationOperationId, expectedRevision, operationId)

// Android 服务接口。recognize 接管 Bitmap；null 表示通知已提示正常解锁。
FloatingCaptureWorkflow.recognize(bitmap, requestId, capturedAt): FloatingCaptureReview?
FloatingCaptureWorkflow.save(review): CaptureBookingOutcome?
FloatingCaptureAccessibilityService.showConfirmation(review): Boolean
```

## 实施与验收

- [x] 领域与协调器失败测试→实现：默认选择、原子补分类、同图去重、回执重试、撤销防误删。
- [x] 独立服务与全局 UI：启停、授权失效、拖动/点击区分、截图取消和资源释放。
- [x] 设置检查权限并打开系统授权页，返回接续；模式开关实际驱动服务。
- [x] 识别→直接保存/一次确认→成功通知；查看、修改、撤销动作指向正确账单。
- [x] 非作者审查平台生命周期与领域事务；处理实质问题。
- [x] 运行相关主机测试、Android 编译和隔离设备测试。
- [x] 在不同 UID 的外部 App 以及系统设置上证明悬浮球仍显示；真实屏幕→OCR→两种保存模式→通知→数据库/独立磁盘重开持久化验证（不冒称杀进程重启）。
- [x] 记录实际通过范围、未覆盖设备差异与系统返回失败；不以编译替代运行证据。

测试命令集中由 root 协调，避免同时执行 Gradle。使用 `-PdawnyewIsolatedDeviceTest=true`，不覆盖正式 App 数据。普通 App 之外的系统锁屏/安全界面和 FLAG_SECURE 内容按系统结果处理，不在产品中承诺绕过。
