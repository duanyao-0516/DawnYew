# DawnYew 交互规范与设计工具选型

日期：2026 年 10 月 18 日。面向 Android 优先、KMP 与 Compose、本地免费记账、多用途账本、图表、历史文件导入、截图草稿和应用锁。

选型结论是 **Material 3 稳定组件＋Android Adaptive 为主体系，WCAG 2.2 与图表规范作补充，Figma Design 为设计稿主交付工具**。具体页面与视觉参数已落在[交互与 UI 规范 v1.0](../superpowers/specs/2026-10-18-dawnyew-ui-interaction-standard.md)。

调研覆盖 Google、Apple、Microsoft、Ant Design、W3C、IBM Carbon、ONS、NN/g，以及 Figma、Stitch、Penpot、Pixso、Motiff 的官方资料；这是一组与本场景相关的代表性方案比较，不是声称穷尽所有网站。排名是针对本项目约束的工程判断，不是设计工具或用户效率实测排行榜。

## 怎样判断适合本项目

主规范必须支持频繁输入、中文金额、纠错与可核对图表，并能映射到 Compose 稳定组件。跨平台应保留共享语义，同时允许系统返回、文件选择和认证遵循各平台习惯。设计工具则必须产出可编辑结构、可复用组件和可验证的交互，而不仅是一张漂亮图片。

## 规范比较

| 体系 | 官方定位与证据 | 本项目取舍 |
| --- | --- | --- |
| Material 3 与 Android Adaptive | Android 的组件、颜色角色、字体层级与自适应导航/多窗格体系。[组件概览](https://developer.android.com/design/ui/mobile/guides/components/material-overview)、[自适应布局](https://developer.android.com/develop/ui/compose/layouts/adaptive/canonical-layouts) | 主规范；和 Android 首发、Compose 实现路径最直接 |
| Material 3 Expressive | M3 的表达扩展，强调视觉层级与交互反馈；部分 Compose API 仍具有实验或预览边界。[研究](https://design.google/library/expressive-material-design-google-research)、[发布记录](https://developer.android.com/jetpack/androidx/releases/compose-material3) | 吸收重点强调与分组，不将全部新组件作为稳定依赖 |
| Apple HIG | Apple 平台的人机交互规范，覆盖布局、系统交互与辅助技术。[HIG](https://developer.apple.com/design/human-interface-guidelines/)、[布局](https://developer.apple.com/design/human-interface-guidelines/layout) | 后续 iOS 适配依据；不以 iOS 视觉为 Android 全局模板 |
| Ant Design Mobile | 官方定位为移动 Web UI 构件，技术栈为 React 等。[官方仓库](https://github.com/ant-design/ant-design-mobile) | 参考业务表单和列表组织；不是 Compose/KMP 原生组件库 |
| Fluent 2 | 跨平台设计体系，分别提供平台开发资源和无障碍指导。[开发资源](https://fluent2.microsoft.design/get-started/develop)、[无障碍](https://fluent2.microsoft.design/accessibility) | 可借鉴语义 token 与焦点处理；本项目整体改用它收益不足 |
| WCAG 2.2 与 WCAG2ICT | WCAG 是 Web 标准；WCAG2ICT 给出在原生软件等非 Web 场景的解释，属于指导性文件。[W3C 说明](https://www.w3.org/WAI/standards-guidelines/wcag/non-web-ict/) | 作为质量检查基线，结合 Android 辅助技术验收；不凭视觉稿宣称已经合规 |
| Carbon 与 ONS 图表规范 | 包含数据配色、坐标轴、基线和正负值呈现。[Carbon 配色](https://www.carbondesignsystem.com/building-blocks/data-visualization/color-palettes)、[ONS 坐标轴](https://service-manual.ons.gov.uk/data-visualisation/guidance/axes-and-gridlines) | 补足图表口径与可读性；不套用整套企业后台外观 |
| Nielsen 交互原则 | 系统状态可见、用户控制、错误预防、识别优于记忆等。[NN/g](https://www.nngroup.com/articles/ten-usability-heuristics/) | 用于审查记账、导入、退款和恢复流程，不替代组件尺寸与视觉规范 |

纯 M3、M3 加项目语义规范、完全自定义体系三条路线中，选择第二条。纯 M3 不会自动定义退款口径、来源去重和图表下钻；完全自定义则增加组件行为、辅助技术和多平台适配成本。项目语义层覆盖金额、账本、导入状态和恢复确认，其余使用成熟组件。

## 直接影响设计的规范事实

- Android 底部导航适用于 3–5 个同级目的地；DawnYew 选择明细、统计、设置三项，账本切换保留在顶部，记一笔作为操作。[导航指南](https://developer.android.com/design/ui/mobile/guides/layout-and-content/layout-and-nav-patterns)
- 可用窗口宽度以 600dp、840dp 等为重要分界；高度和姿态也会影响导航与分栏，不能只按设备名称判断。[窗口尺寸](https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes)
- Android 交互目标至少 48×48dp；不要将 WCAG 的 CSS 像素指标机械换成原生 dp。正文和表单应随系统字号调整。[Compose 无障碍](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)
- 文字对比度通常至少 4.5:1；必要图形和控件状态至少 3:1，并用颜色以外的线索表达含义。项目选择将所有常规文字都按 4.5:1 检查。[文字对比](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html)、[非文字对比](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html)、[颜色使用](https://www.w3.org/WAI/WCAG22/Understanding/use-of-color.html)
- 对用户数据的关键提交应提供可逆、校验纠错或提交前复核等保护；这不意味着每次普通保存都要弹框。[错误预防](https://www.w3.org/WAI/WCAG22/Understanding/error-prevention-legal-financial-data.html)
- 图表精确内容必须有文字与辅助技术入口，不能全部藏在手势里；比较型柱图保留零基线，负值不能取绝对值混入占比图。[Apple Charts](https://developer.apple.com/design/human-interface-guidelines/charts)、[Carbon 坐标轴](https://www.carbondesignsystem.com/building-blocks/data-visualization/axes-and-labels)

图表原始支出、退款、净支出与预算的口径来自 DawnYew 需求，不由视觉规范另行重定义。动态配色、环图或 Expressive 动效都不能掩盖金额和状态。

## 可以直接出图的工具

| 工具 | 可以产生什么 | 关键边界 | 本项目位置 |
| --- | --- | --- | --- |
| Figma Design 与 agent | 原生可编辑页面、Auto Layout、组件、变量及设计系统；Design 本身支持可点击原型 | 内置 agent 当前把原型连线与导航列为尚未提供，需单独连接与验收；AI 权限和文件权限分别检查 | 首选主设计文件，保留长期可维护的设计资产 |
| Figma Make | 由文本或设计生成可运行交互原型及代码 | 主要是代码驱动原型，不是 Compose 项目；转成 Design 图层不等于交互、变量和组件都自动保留 | 复杂表单/筛选状态的可选演示，不是必需前置 |
| Google Stitch | 文本/图片生成界面、多方向探索、屏幕连接与 Play 流程预览，DESIGN.md 复用规则 | Labs 产品；不能保证 Figma 导出无损、组件变量保留或输出 Compose | 早期视觉探索备选，不作为唯一规范存档 |
| Penpot 与官方 MCP | 原生可编辑设计、组件/variants、tokens、可点击原型；外部 agent 可操作设计结构 | 需账号或实例与 MCP 连接；开放工具不代表外部模型免费；常规 Inspect 主要是 Web 格式 | 开源、自托管或更重视工具自主性的备选 |
| Pixso | 中文环境下文生可编辑设计、组件变量、自动布局和原型交互 | AI 点数与席位分别计费，编辑器 AI 与 Paico 点数不同；未核实 Compose 直接输出 | 中文协作备选，当前会话未连接 |
| Motiff | 官网仍保留旧 UI 生成宣传 | 官网已公告停止服务，数据导出截至 2026-10-31 | 排除，不用于新项目 |

工具事实来源：

- Figma 的[agent 支持表](https://help.figma.com/hc/en-us/articles/37998629035799-Work-with-the-Figma-agent-in-design-files)和[AI 产品边界](https://help.figma.com/hc/en-us/articles/23870272542231-Get-started-with-Figma-AI)。First Draft 是旧入口，官方说明自 2026-05-20 起由 agent 逐步接替，不能把旧付费与功能规则直接套在当前入口上。[First Draft 说明](https://help.figma.com/hc/en-us/articles/23955143044247-Use-First-Draft-with-Figma-AI)
- Make 的 Copy design 受方案限制，转换后的修改不自动回写 Make，不能把单个预览状态转换等同完整原型转换。[Make FAQ](https://help.figma.com/hc/en-us/articles/31722591905559-Figma-Make-FAQs)、[代码交接](https://help.figma.com/hc/en-us/articles/35710574222487-Beyond-the-basics-Using-Figma-Make)
- Stitch 的[2026 年 3 月说明](https://blog.google/innovation-and-ai/models-and-research/google-labs/stitch-ai-ui-design/)和[5 月更新](https://blog.google/innovation-and-ai/models-and-research/google-labs/stitch-updates/)说明生成与交互能力。官方社区 7 月回复仍确认 Copy to Figma 入口，同时报告复杂项目或部分模式会失败，因此不能沿用“只有旧 Standard 可导出”的结论，也不能承诺稳定无损。[支持回复](https://discuss.ai.google.dev/t/hero-section-breaks-when-exporting-ui-to-figma-via-copy-to-figma/134346)
- Penpot 的[AI 工作流](https://penpot.app/ai/ai-workflows)、[MCP 前提](https://help.penpot.app/mcp/)、[免费与付费边界](https://penpot.app/pricing)、[开发格式](https://help.penpot.app/user-guide/dev-tools/)。
- Pixso 的[文生设计](https://pixso.cn/ai-introduce/)、[产品能力](https://pixso.cn/)及[方案与点数](https://pixso.cn/price/)。
- Motiff 的[官网公告](https://www.motiff.com/)优先于同页仍存在的旧功能营销文案。

## 当前会话的可执行路径

本次只读核验确认已连接 Figma，现有工具支持创建原生 Design 文件，编辑文本、向量、组件、变量和布局；接口文档也提供原型 reactions 的读写。后续应使用这些原生能力建稿并连接流程，再读回结构与查看截图，不能把 Figma 内置 agent 尚未支持的原型连线当作已经完成。

账号返回包含个人 Starter 的 View 席位并允许 AI。View 不等于个人草稿不可编辑：官方允许用户在自己的 Drafts 中要求 agent 编辑。共享文件的编辑资格另受席位和文件权限约束；当前未创建目标文件或验证其写入。账号返回 Code Layers 未启用，不能将其列为当前必用能力。[Drafts 权限](https://help.figma.com/hc/en-us/articles/23870272542231-Get-started-with-Figma-AI)、[Starter 范围](https://help.figma.com/hc/en-us/articles/13838684089751-Starter-plan-overview)

连接中存在多个团队或组织；真正创建文件时需明确目标空间，建议使用个人项目空间。调用创建工具前按工具要求取得目标选择，不自行落到组织文件夹。本轮只核验连接，没有创建或修改任何云端设计文件。

Material 官方提供 Figma 设计套件和 Theme Builder，可加快基础样式建立。但最新版套件包含 Expressive 内容，仍要选择与实现基线对应的组件；主题生成器也不会替代账务色、图表与实际叠层对比度检查。[M3 资源入口](https://m3.material.io/)、[Theme Builder](https://github.com/material-foundation/material-theme-builder/blob/main/README.md)

## 交付工作流

1. 使用已经确定的 UI 规范建立颜色、字体、间距变量及基础组件。首批只做明细、记一笔、统计和账本选择，检查中文、金额与小屏布局。
2. 原生图层出稿后，连通选账本、记账、保存、统计下钻；同时出空态、输入错误和深色版本。先走通任务，再扩展导入、OCR、恢复和锁定状态。
3. 以原生 Design 文件保存规范和组件；如果简单连线不足以演示复杂状态，再引入独立 Make 原型，不额外重建一套产品规则。
4. 对编辑结构、实际截图、颜色对比和点击流程分别验收。交付原生文件、原型入口、预览图、组件规范与缺口清单。
5. 根据验收后的设计实现 KMP/Compose。浏览器原型不能证明 Android 系统认证、文件解析、离线 OCR 或真实数据安全已实现。

设计阶段工具可以联网，与产品运行时完全离线的要求分开。只使用虚构或经授权脱敏的数据；首轮稿无需等待真实账单模板，导入页面先表达概念字段和状态，具体适配仍等待用户补充。

目前已经完成规范选型与出图任务书，尚未产生 UI 设计稿或可点击原型。下一步可以直接按规范的批次 A 在选定的 Figma 空间出图，无需再从头讨论整体视觉体系。
