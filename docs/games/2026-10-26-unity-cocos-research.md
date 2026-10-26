# 独立小游戏合集：Unity、Cocos、Phaser、Unreal 调研

> 调研快照：用户随后已确定 Unity + C#，以生态学习与后续扩展为优先。本文保留当时的横向比较；当前决定见[技术选型](2026-10-26-game-product-and-engine-selection.md)，渠道路径见[发布方案](2026-10-26-unity-minigame-publishing.md)。

调研日期：2026-10-26。目标是自研、离线 Android 小游戏合集，首批包含 2048、蓄力跳跃、3D 物体堆叠与点击拾取。本文是官方资料核查与工程取舍，未进行同素材引擎性能实测。

## 结论

这几类游戏并不能证明 Godot 必然最优。Unity 与 Cocos 都能覆盖需求：如果抓取游戏的 3D 制作、物理调试及商业资源工作流成为主要投入，Unity 应成为首要对照；如果未来微信、抖音等小游戏分发成为核心，Cocos 应重新排到优先位置。当前已知目标只有独立离线 Android、小游戏规模、源码可控，尚无证据需要为了上述条件放弃 Godot，但必须用 3D 堆叠实机原型检验选择。

| 方案 | 与本项目匹配的能力 | 当前不作为默认选择的原因 | 会改变选择的条件 |
| --- | --- | --- | --- |
| Unity 6 | 2D/3D、PhysX、可视化场景与资源制作、Android 构建、性能分析 | 商业编辑器授权与版本维护是额外责任；不能仅凭生态推断小游戏包体或帧时间更好 | 重 3D 美术/物理、团队已有 Unity 经验、明确使用其现成制作资源 |
| Cocos Creator 3.8 LTS | TypeScript、2D/3D、原生 Android、局部加载的本地 Asset Bundle、小游戏平台导出 | 3.8 与 COCOS 4/CLI 是不同技术代际，需要明确锁定版本；目前没有国内小游戏分发需求来发挥其明显适配价值 | 微信/抖音等平台成为主要渠道，团队偏 TypeScript |
| COCOS 4 / CLI | MIT 引擎与命令行工具，适合自动化工作流 | 官方发布列表当前仍为 4.0.0-alpha.35，不能把开源发布等同于稳定生产版本 | 稳定发布与 Android 工具链经实际验证 |
| Phaser + Android WebView | 2D、JS/TS、浏览器制作与发布，资源可随 APK 离线打包 | Phaser 本身不含 3D 渲染或 3D 物理；做真实堆叠需额外引擎，增加维护面 | 全部玩法明确为 2D，Web 发布优先 |
| Unreal Engine | 成熟 3D、移动渲染、Blueprint、物理与工具体系 | 当前短局休闲玩法没有足够需求收益，支撑更复杂的渲染配置与制作管线 | 目标转为高规格 3D 内容，且团队已有 UE 能力 |

表格中的“默认选择”是针对本项目的工程判断，不是各引擎性能排名。

## 版本与授权核查

- Unity 官方列出 6.3 LTS，支持到 2027 年 12 月；Update 分支也可用于生产，不必把 LTS 写成唯一稳定分支。[官方支持说明](https://unity.com/releases/unity-6/support)
- Unity Runtime Fee 已取消，不适用于任何 Unity 版本。当前 Personal 财务门槛为最近十二个月 20 万美元；具体资格要按使用主体、客户及资助口径判断，不能简单写成“本小游戏没赚钱所以永久免费”。[价格变更](https://activation.unity3d.com/products/pricing-updates)、[资格条款](https://unity.com/pages/license-compliance)
- Creator 3.8 的运行时源码 MIT 与编辑器协议需要区分：编辑器协议提供目标应用/游戏开发许可，不能泛化为整个工具完全同一 MIT 授权。[运行时许可](https://github.com/cocos/cocos-engine/blob/v3.8.9/LICENSE.md)、[编辑器协议](https://github.com/cocos/cocos-engine/blob/v3.8.9/licenses/EDITOR_license_and_terms_en.txt)
- COCOS 4 及 CLI 已有单独 MIT 仓库；截至本次核查，发布列表最新为 2026-10-13 的 `4.0.0-alpha.35`，不能沿用“所有 Cocos 编辑工具都闭源”的旧结论，也不应将 alpha 直接作为当前项目生产基线。[COCOS 4](https://github.com/cocos/cocos4)、[发布列表](https://github.com/cocos/cocos4/releases)、[CLI](https://github.com/cocos/cocos-cli)
- Phaser 的开源框架为 MIT。官方当前下载页提供 4.2.1；Phaser 4 仍是 2D，不能将另一个 Phaser AE 产品展示的 3D 能力混同为开源 Phaser 4 能力。[许可](https://github.com/phaserjs/phaser/blob/master/LICENSE.md)、[下载页](https://phaser.io/download)、[官方产品区分](https://phaser.io/news/2026/07/no-you-didn-t-miss-a-3d-update)
- Unreal 游戏产品采用版税路径，标准说明为产品终身总收入超过 100 万美元的部分收取 5%，另有具体排除和优惠；当前不因“未盈利”就把长期授权成本忽略。[官方许可概要](https://www.unrealengine.com/license)

## 关键能力与离线边界

Unity 的默认 3D 物理使用 PhysX；Android Player 支持 APK/AAB 与可配置的联网权限。独立离线游戏可以只打包本地资源并移除云服务、广告、遥测和远程资源依赖，编辑器授权与最终玩家是否需要联网是两件事。[3D 物理](https://docs.unity3d.com/6000.3/Documentation/Manual/PhysicsOverview.html)、[Android Player 配置](https://docs.unity.com/en-us/engine/6000.6/manual/platform-specific/android/getting-started/class-player-settings)

Cocos 的 3D 物理可选择 Bullet、PhysX 等，Builtin 只提供碰撞检测，不提供完整刚体模拟，因此“抓大鹅”式堆叠不能只选 Builtin 就认为物理已完成。Asset Bundle 可放本地，适合在一个合集内按需加载各游戏；Android 是原生导出路径，不需要用 WebView 承载 Cocos 游戏。[物理系统](https://docs.cocos.com/creator/3.8/manual/en/physics/physics)、[Asset Bundle](https://docs.cocos.com/creator/3.8/manual/en/asset/bundle.html)、[Android 发布](https://docs.cocos.com/creator/3.8/manual/en/editor/publish/android/index.html)

Phaser 官方明确是 Web 优先的 2D 框架，原生封装依靠其他工具。Android WebView 能通过本地资产加载器加载包内内容，所以 WebView 不等于必须联网，但其生命周期、音频、触摸与具体 WebView/GPU 组合仍要验证。[Phaser 定位](https://docs.phaser.io/)、[Android 本地内容](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content)

Unreal 有专门移动渲染路径与帧节奏配置；选择 Unreal 并不能自动将桌面高规格渲染变成移动端流畅体验。[移动渲染文档](https://dev.epicgames.com/documentation/unreal-engine/mobile-rendering-features-in-unreal-engine)

## 对 2048 重做与性能验证的建议

采用同一个游戏引擎重做 2048 是合理的产品和维护决策：将滑动、合并、生成、反馈、输入缓冲作为明确的动画过程实现，游戏规则与表现层分别验证。理由是建立统一游戏制作流程，不能宣称 Compose 在原理上无法达到流畅 2048，也不能宣称换引擎即可自动得到高质量动效。

引擎原型至少覆盖两种负载：2048 连续滑动与合并；抓取游戏 50/100/200 个简单刚体的堆叠、睡眠、遮挡点击与再次唤醒。数量是建议的测试梯度，不是已确认的产品需求。使用相同模型、纹理、碰撞体、渲染分辨率与目标帧率，在同一中低端 Android 真机比较帧时间分布、输入响应、冷启动、内存和长时间运行温升，再决定是否需要用 Unity 或 Cocos 替换候选 Godot。

本次未安装这些引擎、未导出它们的 APK、未测量任何引擎包体或 FPS。不存在可据此宣布“最丝滑”“包体最小”的实测证据。
