# 截图记账与交互结构收敛

本规范追加于 UI correction，按用户当天的新反馈执行；不询问用户，设计决定留档。交付前须自查、真实设备回归，再由非作者整体审阅架构、路由、功能完整性和参考对齐。不是只审局部补丁。

## 用户可见目标

1. 面板配置切换时无关行不闪灰，保留原子写入与失败反馈。
2. 记账页是同一屏的金额、分类、标签、备注和保存；点击金额显示计算键盘，金额右侧放日期和时间；备注页内最多三行，获得焦点时换系统文本键盘，超过三行内部滚动。
3. 首页金额和曲线覆盖整个选定区间，整月包含所有日期；支持自定义起止日期。预算等月度业务明确显示其所属自然月；日历月份独立，日期固定基线，下方显示当日净收支。
4. 普通二级页面按原版备份的分组平面列表组织；可拖管理行、图表和记账专用布局保持各自语义。搜索/筛选/设置重新对照参考，全部已显示操作有真实离线行为。
5. 三种截图入口均落地：系统截图监听、通知栏截屏快捷按钮、图片分享/本地选图。所有识别离线、核对后保存，不新增登录、云服务或网络权限。

## 证据与系统契约

- 原版 `.local/research/bohe-20261024/round2/p002-home-auto-accounting.xml` 与 `p466-auto-from-profile.xml` 明确三种方式；`ScreenTileService.java` 使用系统 Quick Settings Tile，非普通桌面小组件。
- [Android Quick Settings](https://developer.android.com/develop/ui/views/quicksettings-tiles)：系统管理 Tile；API 33+提供添加请求，API 34+使用 PendingIntent 启动并收起面板。
- [Android MediaProjection](https://developer.android.com/media/grow/media-projection)：每次授权只用于一次 projection session；注册停止回调后创建 VirtualDisplay，mediaProjection 前台服务和通知，完成后立即释放。
- [前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)：持续截图监听使用用户显式启停的 specialUse 服务；说明 subtype。仅监听新截图，不扫描历史相册、不设置开机自启。

## 结构与责任

### UI 与数据

- EntryComposer 只拥有输入模式、焦点、选区等 UI 状态；EditorDraft 仍是财务事实唯一来源。抽取清晰的输入状态与编辑函数，替换散落的布尔分支和捕获旧草稿的局部函数引用。
- 全局首页范围、自然月业务范围、独立日历范围由 presentation 的具名函数推导。UI 不自行重新扩成整月；图表只稀疏轴标签，不裁掉数据。
- 平面二级页使用共同 Section/Row；搜索筛选使用待应用草稿，应用/取消/重置与当前查询范围分离，不能边编辑边污染已应用范围。

### 截图入口

- shared/presentation 定义 CapturePlatform 的能力状态与动作接口，AndroidCaptureController 负责 Activity 权限、Tile 添加、设置跳转；不把 Android 类型放到 commonMain。
- CaptureRecognizer 统一 URI/Bitmap 到离线 OCR 结果的映射，选图、分享、Tile、系统截图监听共用；MainActivity 只处理授权后投递与既有草稿确认，不复制识别解析。
- CaptureInbox 为进程内有界的一次性结果通道：不可预测 token，短 TTL，消费后移除，原图在识别结束或取消时释放。Intent 只传 token，不传 Bitmap、原图文本或任意文件路径。锁屏/取消/过期/进程消失不复用旧结果。
- MainActivity 接收后先执行现有应用锁/设备锁与 repository 就绪检查，再消费结果。后台识别不读写账本；最终重复检查、OCR 确认、SaveEntry/操作回执仍经现有链路。
- token 绑定截图用途、创建时 repository generation、TTL，消费单次；入口接收后绑定新授权 session epoch，异步识别结束再核 epoch。新 Intent 的正常会话撤销不代表可以绕过解锁；generation 已变更的旧结果拒绝消费。

### 主动截屏

- ScreenCaptureTileService → 专用授权 Activity → 一次性 ScreenCaptureService。拒绝授权立即结束；不缓存授权 Intent，不绕过系统授权和 FLAG_SECURE。
- 捕获授权框与通知面板退出后的目标内容。Capture Activity 不展示私有账本；释放 ImageReader/VirtualDisplay/MediaProjection 成对执行，超时、锁屏、用户停止全部清理。
- 捕获完成通过一次性 token 回到 MainActivity；后台启动若被 OEM 限制，保留可点击的结果通知，不能丢结果或谎称已记账。
- 主动 Tile 的系统同意只授权本次屏幕读取；应用锁仍保护账本读取和草稿投递。系统锁屏不允许抓取；不会复用先前授权。

### 系统截图监听

- 用户在自动记账页主动启用并授予所需媒体/通知权限。前台服务通知提供停止动作，权限撤销或显式关闭即注销 ContentObserver。
- API 33+通知被拒、仅授予部分照片或媒体读取权限未满足时不启动后台监听，页面给出可再次请求的实际状态，不运行一个无法交付结果的后台服务。
- 只接纳启用后新增、已提交、文件名或路径明确为截图的 PNG/JPEG；查询有界、去重有界，不识别历史照片。
- 设备解锁且应用授权有效时可后台调用同一离线识别器；应用锁未授权时只保留有界的图片库变更信号，在用户进入并解锁后重新校验并读取。
- 未授权时连截图名/路径匹配的 MediaStore 查询也不执行，只记录有界失效信号；解锁后再校验启用时间、待提交标记与来源，超过窗口的事件不补扫历史。锁屏清理已解析待领结果。
- 后台行为遵守 Android 启动限制：结果以本地通知供用户打开核对；不新增覆盖其他应用的悬浮窗权限，不假装所有 OEM 都允许无交互弹出页面。页面明确说明此行为。
- 原系统截图不由应用擅自删除；Tile 捕获不写相册。删除媒体需要额外用户意图，不纳入本次默认开启。

## 验证门槛

- 主机：输入纯函数（17、小数、退格、选区）、完整日期边界、跨月查询与下钻、偏好恢复/旧备份、截图识别和生命周期状态。
- 设备：非目标面板行像素稳定；金额点击/备注切键盘/日期时间与三行滚动；整月10.1/10.31和跨月09.15–10.10；普通页面真实操作与返回位置。
- 截图：系统 Tile 添加；在受控外部支付样例屏点 Tile→系统同意→识别真实内容→草稿人工确认保存；授权取消/再次截图重新授权/锁屏清理；系统截图监听启停与通知打开；分享入口回归。
- 数据与发布：无网络权限；保留 FLAG_SECURE；不得覆盖用户正式包/薄荷数据。只安装 validation 包；最终统一 check、设备套件、签名/JNI/对齐并留档所有失败和验证边界。
- 质量：清除被替代的死路径，禁止每页复制同一条件；整体审查必须识别入口缺失、模型/UI不一致及协议/缓存/会话风险。所有有证据 P1/P2 修复后复审。
