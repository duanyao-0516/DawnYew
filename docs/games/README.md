# 小游戏产品与技术方案

当前已确定 **Unity + C#**，独立仓库为 [XanaduYonder](https://github.com/duanyao-0516/XanaduYonder)。本阶段只开发离线 Android APP；微信、抖音等小游戏渠道暂不接入。技术选型见 [2026-10-26 产品形态与技术选型](2026-10-26-game-product-and-engine-selection.md)。

DawnYew 保留猜拳作为聚会工具。2048 等个人小游戏转向独立合集产品；DawnYew 已移除对应宿主、存储和运行依赖，不提供旧游戏存档迁移。

- [Unity 多平台发布调研（渠道接入暂缓）](2026-10-26-unity-minigame-publishing.md)
- [Godot、Defold、libGDX 调研](2026-10-26-godot-defold-research.md)
- [Unity、Cocos、Phaser、Unreal 调研](2026-10-26-unity-cocos-research.md)
- [DawnYew 游戏依赖拆除记录](../implementation/2026-10-26-games-detachment.md)

旧 Compose 2048 接入方案及 2026-10-25 验证记录只作历史参考。XanaduYonder 已建立仓库基础文件，Unity 工程和引擎版 2048 尚未开发，Android 构建与真机性能尚未验证；渠道 SDK 验证不作为当前 APP 开发的前置条件。
