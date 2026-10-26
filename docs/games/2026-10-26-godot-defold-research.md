# Godot、Defold 与原生游戏框架调研

> 调研快照：用户随后已确定 Unity + C#，以生态学习与后续扩展为优先。本文保留当时的横向比较；当前决定见[技术选型](2026-10-26-game-product-and-engine-selection.md)，渠道路径见[发布方案](2026-10-26-unity-minigame-publishing.md)。

调研日期：2026-10-26。范围是独立、离线 Android 小游戏合集：2048、蓄力跳跃、3D 物体堆叠与拾取。这里只做文档与官方资料调研，没有实施同场景性能测试；包体、帧率、功耗和物理稳定性均不声称已验证。

## 判断

Godot 是本组候选中的推荐首选，理由是用一套场景、动画、2D/3D 物理和资源工具覆盖上述三类游戏，且 MIT 许可边界简单。推荐语言是 GDScript；不为复用 Kotlin 存档代码引入额外桥接，也不加入 Python 运行时。Defold 是需要认真保留的轻量候选，不能用“只支持 2D”排除；libGDX 则适合将 Kotlin/Java 和代码控制权放在首位的团队。

这是一项基于需求与工具链的工程判断，不代表 Godot 的帧率或包体实测优于另外两者。如果目标收缩为极小包体的 2D 游戏合集，Defold 的优先级会上升；如果目标变成复杂 3D 生产、成熟商业资产工作流，则应与 Unity 重新比较。

## 当前版本与许可

| 候选 | 本次官方页面显示版本 | 许可与本项目相关边界 |
| --- | --- | --- |
| Godot | 下载页显示 4.7.2 stable；预览版不作为发布基线 | MIT，可商用及修改；分发引擎时保留版权和许可说明，游戏内容可以自行选择许可 |
| Defold | 官方 GitHub latest stable 显示 1.14.0 | Defold License，可商业发布游戏、修改引擎；不允许商业化出售 Defold 编辑器/引擎本身；不能直接称为 Apache 2.0 |
| libGDX | 官方 GitHub latest 指向 1.14.2 | Apache 2.0；以 Java 为中心的跨平台框架，可用于 Kotlin 项目 |

版本需要在创建正式工程时再次锁定编辑器、导出模板和构建依赖，不使用滚动的 latest。来源：[Godot 下载](https://godotengine.org/download/android/)、[Godot 许可](https://godotengine.org/license/)、[Defold 发布](https://github.com/defold/defold/releases)、[Defold 许可](https://defold.com/license/)、[libGDX 发布](https://github.com/libgdx/libgdx/releases/tag/1.14.2)、[libGDX 特性与许可](https://libgdx.com/features/)。

## 能力与取舍

### Godot

- 2D 与 3D 可以放在同一工程内。3D Jolt 已内置，当前官方文档说明新项目默认使用 Jolt；部分关节属性与 Godot Physics 存在差异，不能直接认定所有碰撞行为天然稳定。[Jolt 文档](https://docs.godotengine.org/en/stable/tutorials/physics/using_jolt_physics.html)
- Android 官方导出链路包含 SDK/JDK 配置；C# Android 导出仍被文档标为实验性，因此此项目优先 GDScript，避免为了共用语言而增加平台风险。[Android 导出](https://docs.godotengine.org/en/stable/tutorials/export/exporting_for_android.html)
- Compatibility 面向较广硬件及简单 2D/3D，Mobile 面向较新设备与更多 3D 特性。三款休闲游戏可以先以 Compatibility 原型建立低端机基线，再以真实 3D 场景对比 Mobile；不因名叫 Mobile 就直接选它。[渲染器选择](https://docs.godotengine.org/en/stable/tutorials/rendering/renderers.html)
- `ResourceLoader.load_threaded_request` 支持后台加载；调用 `load_threaded_get` 前仍需确认完成，否则可能阻塞。适合合集大厅进入单个游戏时按需加载场景。[后台资源加载](https://docs.godotengine.org/en/stable/tutorials/io/background_loading.html)
- 有内置脚本/帧分析器；仍应在 Android 设备上观察 GPU、热状态和系统帧时间。编辑器运行正常不能替代手机验收。[Profiler](https://docs.godotengine.org/en/stable/tutorials/scripting/debug/the_profiler.html)

**反对直接宣布 Godot 最佳的证据：** 官方专门说明 shader/pipeline 编译会造成卡顿；Compatibility 无法采用同样的 pipeline 预编译机制，需要加载阶段实际显示材质、粒子等完成预热。首次合并特效或首次展示 3D 物体时的卡顿，不会因改用引擎自动消失。[编译卡顿说明](https://docs.godotengine.org/en/stable/tutorials/performance/pipeline_compilations.html)

### Defold

- 支持完整 3D 模型和 glTF/GLB 导入；可以混合 2D 与 3D。物理提供 Box2D 与 Bullet，当前文档还提供新版 Box2D 的选择。不能沿用“Defold 只有 2D”的过时印象。[模型](https://defold.com/manuals/model/)、[物理](https://defold.com/manuals/physics/)
- Collection proxy 明确支持小游戏世界的动态加载和卸载，默认把引用资源打进本地包，适合离线合集；不必使用 Live Update 或下载资源。[Collection proxy](https://defold.com/manuals/collection-proxy/)
- Android 同时支持 APK/AAB。当前 R8 资源裁剪需要兼容 Extender，开启 R8 也会使用扩展构建服务；这属于开发构建依赖，并不等于玩家运行时联网。[Android 构建](https://defold.com/manuals/android/)
- 本地开发大部分可离线，但首次原生扩展构建默认使用云构建，缓存后可复用，也有自建构建服务路径。纯离线**运行**容易满足；完全离线**构建**需要额外准备，二者不可混为一谈。[离线工作](https://defold.com/manuals/working-offline/)
- 可按 App Manifest 排除不使用的物理、GUI、粒子、Live Update 等功能；有构建报告，以及移动端实时/网页 profiler。适合用实包检验是否有可观体积优势，但本次不引用厂商空工程对比数字作为本产品包体预测。[体积优化](https://defold.com/manuals/optimization-size/)、[性能分析](https://defold.com/manuals/profiling/)

**为何暂排在 Godot 后面：** 本项目包含堆叠拾取 3D 场景，综合场景编辑、动画制作、团队语言适应和资产制作流程，Godot 更符合当前统一开发的预期。这是工程判断，不是 Defold 缺失 3D 能力的事实判断。若 Godot 在目标设备测试失败，应使用同一资产和玩法切片评估 Defold，而不是继续凭印象选型。

### libGDX 与 Android 自定义游戏循环

- libGDX 有精灵批处理、Scene2D、粒子、2D Box2D 与 3D Bullet，以及 Android 等平台后端；其定位是代码为中心的框架。已有 Kotlin 经验有帮助，但关卡编辑、资产预览和制作工具要另外组织，不能把这些工作当作零成本。[功能列表](https://libgdx.com/features/)
- `AssetManager` 提供异步加载和引用计数，资源必须跟随 Android 生命周期管理；官方特别提醒不要随意放在 static 字段。[资源管理](https://libgdx.com/wiki/managing-your-assets)
- 有 FPS、任务时间和 GL 调用分析工具，但工具存在不代表具体游戏性能已经满足。[性能分析](https://libgdx.com/wiki/graphics/profiling)
- 直接用 Android Surface/OpenGL 与自建循环可行，但需要自己处理循环、输入与显示刷新节奏。对于准备持续增加多款自研游戏的需求，重复建设引擎基础能力通常得不偿失。[Android 游戏循环](https://developer.android.com/games/develop/gameloops)

## 对 2048 重做的具体建议

同意在新的游戏工程重做 2048 的表现与交互。保留规则验收样例、确定性随机测试思路即可，不沿用 Compose 宿主和存档格式；没有存档迁移需求。

不能把当前缺少动效归因于 Compose 必然不流畅。Compose 本身支持位置、缩放等动画；最终流畅度取决于实现、布局/绘制开销和设备。[Compose 动画说明](https://developer.android.com/develop/ui/compose/animation/quick-guide)

引擎版本应真正实现游戏交互：规则层一次产出移动与合并事件，表现层保留方块身份并播放位移、合并弹性和新方块出现动画；快速连划采用有上限的输入队列；暂停或退后台时停止动效、保存逻辑状态；音效与触感由宿主统一控制。换一种语言再即时刷新数字并不构成体验升级。

## 正式锁定前的验收切片

这不是已取得的性能结果，而是建议的验收方案：

1. 一款完整 2048：连续快速滑动、合并动画、撤销/重开、暂停恢复、返回大厅再进入。
2. 一段蓄力跳跃：输入反馈、落地判定、镜头平移与失败重开。
3. 一段 3D 堆叠拾取：多物体接触、遮挡拾取、取走后的重排与热运行。
4. 同一台低档和中档 Android 设备、相同资源/质量设置、release 包：记录冷启动、峰值内存、包体、CPU/GPU 帧时间分位数、输入到可见响应及持续运行后的热降频；60 Hz 的单帧预算是 16.67 ms，但不预先保证达标。
5. 验证资源都在本地包，断网可完成全部玩法；退出游戏后不残留音频、计时器和物理场景；不引入 DawnYew 的 OCR、无障碍、账本数据库或账户概念。
