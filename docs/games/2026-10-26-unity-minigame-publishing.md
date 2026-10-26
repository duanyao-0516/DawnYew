# Unity + C#：独立 APP 与小游戏渠道发布

日期：2026-10-26。状态：技术栈已由用户确定为 Unity + C#。用户随后明确当前只做好 Android APP，暂不接入微信、抖音等渠道；本文的渠道路线、版本与架构内容保留为后续研究，不是当前开发门槛。尚无编辑器、转换 SDK 或渠道开发者工具的运行验证。

## 决定

采用 Unity + C#。用户不需要维护游戏引擎，优先考虑广泛的学习资料、开发工具和后续扩展。原创游戏代码可以开源，引擎及第三方资源按各自许可使用。DawnYew 继续保留猜拳；新小游戏不重新接入 DawnYew。

独立 APP 在 [XanaduYonder](https://github.com/duanyao-0516/XanaduYonder) 中做离线合集，本阶段只实现 Android。以下微信、抖音独立构建配置及按单款游戏出包的设想均为暂缓方案；若后续启动渠道工作，再验证共享玩法代码、资源与平台适配的具体边界。

## 1. 官方发布路线

| 目标 | 路线 | 可复用与额外工作 |
| --- | --- | --- |
| Android APP | Unity Android 构建，输出 APK/AAB | 大厅和全部游戏、本地资源与存档，安装后离线运行 |
| 微信小游戏 | Unity/团结项目 + 微信转换 SDK → 小游戏工程 → 微信开发者工具 → 真机测试与上传 | 复用玩法、场景和资源；适配存档、宿主生命周期、资源加载与平台能力 |
| 抖音小游戏 | Unity WebGL + TTSDK；另有 Android 专用 Native 路线 | 初期以 WebGL 兼容能力组织共享代码；抖音有独立配置和 SDK，不能复用微信导出物直接上线 |

微信当前可访问的官方 [Unity/团结 SDK 仓库](https://github.com/wechat-miniprogram/minigame-tuanjie-transform-sdk/blob/main/README.md)明确支持在 Unity 或团结中通过 Package Manager 接入。[团结手册](https://docs.unity.cn/cn/tuanjiemanual/Manual/WechatSDK.html)同时提供集成方式和官方直接下载入口。仓库可读不等于本机安装、导出已经验证。

微信的导出工程仍需在开发者工具和真机测试，再上传体验/审核版本；远程资源需要相应部署配置。[官方部署说明](https://docs.unity.cn/cn/tuanjiemanual/1.9/Manual/UploadWeixinMiniGame.html)

抖音官方提供 Unity 接入方案及 C# SDK。Native 路线与 WebGL 路线的限制不同；早期调研将能够兼顾多个渠道的 WebGL 路线列为验证候选，当前暂不实施。[抖音接入指南](https://developer.open-douyin.com/docs/resource/zh-CN/mini-game/develop/guide/game-engine/rd-to-SCgame/unity-game-access/install-connect/sc_access_guide)

## 2. 转换的实际含义

标准 Unity Web 路线将 C# 经 IL2CPP 转为 C++，再编译成 WebAssembly；主要玩法不需要人工重写成 JavaScript。平台转换 SDK 负责适配小游戏宿主所需的运行环境和工程结构。[Unity Web 教程](https://learn.unity.com/tutorial/getting-started-with-unity-web?uv=6)

能复用多少取决于实现，不能承诺固定百分比或一键上线。尤其需要处理：

- **原生插件**：Android 的 AAR/JNI、iOS 插件不能直接作为 Web 插件运行，需要兼容实现或平台替代。[Web 原生插件](https://docs.unity3d.com/6000.0/Documentation/Manual/webgl-native-plugins-with-emscripten.html)
- **线程与动态代码**：不能依赖桌面/Android 的 C# 线程模型或 `Reflection.Emit`；按目标 Web/小游戏运行时支持范围设计。[Unity Web 限制](https://docs.unity3d.com/6000.0/Documentation/Manual/webgl-technical-overview.html)
- **持久化**：存档走平台适配接口。团结微信文档说明普通 C# 文件写入不等同于退出后仍保存，需要微信文件/存储能力配合。[微信资源与缓存](https://docs.unity.cn/cn/tuanjiemanual/Manual/WechatHttpCache.html)
- **资源和表现**：纹理、音频、首场景、WASM、分包及内存峰值要针对渠道实测。支持导出不是效果、性能和包体已合格的证明。
- **平台能力**：分享、录屏等按需要接入；本项目不为了接渠道而加入游戏账号、广告、支付或服务器。

## 3. 编辑器与 SDK 版本选择

Unity 全球版和团结引擎是不同发行/版本线。团结由 Unity 中国基于 Unity 2022 LTS 定制，提供国内渠道能力；不将团结当作 Unity 6 的别名，不假定两个工程可以无损双向切换。[官方说明](https://unity.cn/tuanjie/help-center)

抖音当前推荐页给出已验证的 Unity 2021/2022 及团结版本，并明确适配可能滞后于 Unity 的更新。本轮没有得到“任意最新 Unity LTS 都可用于微信/抖音”的证据，也没有验证 Unity 6 的具体渠道支持组合。[推荐版本](https://developer.open-douyin.com/docs/resource/zh-CN/mini-game/develop/guide/game-engine/rd-to-SCgame/unity-game-access/install-connect/access-engine-advice/sc_webgl_recommended_engine_version)

后续启动渠道接入时，先验证 Unity 全球版与官方渠道 SDK 的受支持组合。团结仅作为需单独评估的渠道工具链备选；如果出现必须更换发行版的证据，先说明兼容证据与工程差异，不将团结自动视为既定选择，也不因 SDK 仓库名含团结而迁移工程。选择当前受维护、含必要安全修复的版本，不因旧文档列了某个早期补丁号就直接沿用。记录编辑器发行版/精确版本、模块、SDK 版本或提交、开发者工具版本以及实测宿主版本，不追踪浮动 main/latest。

抖音新包体路线有最低 TTSDK 版本要求，不能把最低版本当作当前最新版。[新格式说明](https://developer.open-douyin.com/docs/resource/zh-CN/mini-game/develop/guide/game-engine/rd-to-SCgame/unity-game-access/install-connect/unity-new)

来源状态：旧 `minigame-unity-webgl-transform` 主仓库本次访问显示 disabled，其旧文档站不可访问；上述 `minigame-tuanjie-transform-sdk` 与团结现行手册可访问。未使用第三方 fork 代替官方 SDK，也未依据旧搜索缓存承诺最新版本支持。

## 4. 共享架构

```text
C# 游戏规则与状态（不引用平台 SDK）
        ↓
Unity 游戏模块（场景、Prefab、表现、输入）
        ↓
平台接口（存档、生命周期、触感、退出、资源位置）
        ├── Android：离线合集，本地资源
        ├── 微信：按玩法构建，微信适配器
        └── 抖音：按玩法构建，TTSDK 适配器
```

使用 asmdef 隔离程序集，平台 SDK 只进入相应目标的适配程序集。独立 APP 不携带微信/抖音 SDK 或远程资源依赖；游戏规则不直接调用 `WX`、`TT` 或 Android API。游戏入口、资源集合和场景由构建配置选择，避免每个玩法复制整份工程。小游戏代码仍随渠道产物编译发布，不设计下载执行任意第三方插件的平台。

## 5. 离线边界

独立 APP 保持“安装后所有玩法可断网使用”的验收目标。小游戏渠道的核心玩法可以无需业务联网，但首次启动、资源下载或缓存被清理后的启动仍由宿主控制，不能承诺与本地 APK 相同的始终离线可用性。

抖音官方说明远程资源可缓存，但长期未打开可能清理；首包也存在下载过程。[缓存说明](https://developer.open-douyin.com/docs/resource/zh-CN/mini-game/develop/guide/game-engine/rd-to-SCgame/unity-game-access/packaging-release/changelog-and-resource/sc_webgl_resource_cache)

CDN 并非必然前置条件：先裁剪并随包交付所需资源，具体超限时再评估。抖音新格式支持把 data 放进 CDN，但会改变启动下载行为。[Data CDN](https://developer.open-douyin.com/docs/resource/zh-CN/mini-game/develop/guide/game-engine/rd-to-SCgame/unity-game-access/packaging-release/webgl-data-cdn-tutorial)

## 6. 当前阶段与后续研究

1. 在已建立基础文件的 XanaduYonder 仓库创建 Unity 工程，固定并验证编辑器与 Android 构建模块；不修改 DawnYew 游戏边界。
2. 用 2048 最小场景打通 Android APP：输入、移动动画、本地保存、退出重进、杀进程恢复、资源加载。
3. 固定可复现的工具版本、构建脚本和资源预算，再完善 2048、大厅与公共服务。
4. 增加跳跃和 3D 堆叠场景，并验证帧时间、连续操作、拾取准确性、内存、发热及后台恢复。

微信、抖音的 SDK 安装、构建与宿主真机验证暂缓。只有实际启动渠道需求后，才重新核对本文的版本与官方路线；这些检查不阻塞当前 APP 开发。

XanaduYonder 已建立仓库基础文件，Unity 工程和引擎版 2048 尚未开发。没有 Android 构建、跨端导出成功或性能达标的实测结论，也没有注册渠道账号、上传或发布游戏。

## 7. 文档复核

微信与抖音两名调研代理分别交叉审阅了本方案和综合选型文档。已修正“团结作为兼容备选”可能被误读成自动迁移授权的表述；其余未发现资料与结论不一致的问题。复核只覆盖文档与官方资料，不代替 SDK 安装、编译或设备验收。
