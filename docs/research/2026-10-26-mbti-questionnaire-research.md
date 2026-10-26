# MBTI 与十六型问卷选型调研

调研日期：2026-10-26。适用项目：DawnYew，基线 `e80600e`。本轮只交付调研与[接入设计](../superpowers/specs/2026-10-26-mbti-tool-design.md)，未加入题库或实现页面。

## 1. 结论与建议

“标准 MBTI”不是一套可以按题数随意替换的网络题库。官方 MBTI、公开的荣格类型问卷、16Personalities 的 NERIS 和大五人格量表，是不同的测量工具，不能共用一个“标准版”名称、计分器或结果解释。

针对 DawnYew 不登录、不联网、公开源代码的定位，建议分清两个目标：

1. **如果必须是正式 MBTI：选择 Global Step I 简体中文版作为授权评估对象。** 它针对四对偏好与十六类型，比包含二十个子维度的 Step II 更符合工具箱场景。题目、计分、中文报告和离线分发权都要由授权材料明确，不能靠网上的 93 题或结果网页补齐。
2. **如果目标是可离线使用的十六型自测：优先评估 OEJTS 1.2 的 32 题 PDF。** 它的题目和计分合同可固定、可复算，适合先验证交互与算法；产品名称应为“十六型人格自测”，注明非官方 MBTI。它包含非商业许可条件，当前还不能认定适合随 DawnYew 的 GPL 仓库与 APK 直接发布。
3. **IPIP 是许可更宽松的另一条路线，但应作为“大五人格测试”独立产品选择。** 不能为了绕过前两项的授权条件，把大五分数拼成 MBTI 四字母。

本轮的技术设计以 OEJTS 32 题为**有条件候选**，不是已经获准发布的题库。正式题库接入前，需要落定其分发许可及中文版本；这两个问题不能用“仅供娱乐”或把数据移到另一个目录来代替解决。

## 2. 官方 MBTI 有哪些版本

| 工具 / 版本 | 可核验题数 | 主要输出 | 对本项目的意义 |
| --- | ---: | --- | --- |
| Step I，Form M | 93 | 四对偏好、十六类型 | 常见旧版来源，不等于任意网络“93 题标准版” |
| Step II，Form Q | 144 | 四对偏好及 20 个子维度 | 包含 Step I 内容；更适合深入反馈，不宜作为工具箱首版 |
| Global Step I | 92 | 四对偏好，更新后的计分与报告 | 如做正式 MBTI，优先评估这一产品的中文授权 |
| Global Step II | 143 | 四对偏好及 20 个子维度 | 不能把旧 Form Q 的题目或算法直接套上新名称 |
| 历史欧洲版 Step I / II | 88 / 166 | 对应区域版本的偏好 / 子维度 | 官方历史上存在这些题数，但不能据此认证网上中文题库 |
| Step III | 本轮未核实，不填猜测值 | 面向个体发展与专业反馈 | 不纳入首版；不能当作单纯“更多题的高级版” |

来源：[亚洲官方产品页（Form M / Q）](https://asia.themyersbriggs.com/instruments/mbti/)、[官方 Global 产品页](https://www.themyersbriggs.com/en-US/Access-Resources/MBTI-Global-Version)、[英国 Global 手册补充资料的版本沿革](https://www.themyersbriggs.com/hubfs/MyersBriggsCompany_January2026/pdfs/MBTIGlobalManualSuppUK.pdf?hsLang=en)、[基金会对三种工具的说明](https://www.myersbriggs.org/unique-features-of-myers-briggs/three-unique-instruments/)。

Global 版本于 2018 年发布。官方公开材料说明它采用新的 Probability Index，区别于旧版 Preference Clarity Index；不能把简单答题占比标成官方指标。官方反馈也区分问卷报告类型与受测者结合反馈确认的 best-fit 类型。[Global FAQ](https://ap.themyersbriggs.com/content/MBTI_Global_Assessmment_FAQ_2.pdf)、[基金会工具说明](https://www.myersbriggs.org/unique-features-of-myers-briggs/three-unique-instruments/)

官方有简体和繁体中文材料，不能因为某个英语销售页就判断“不支持中文”。具体采购对象必须锁定产品、地区、语言和报告版本。[语言手册补充资料](https://www.themyersbriggs.com/en-US/Access-Resources/MBTI-Manual-Supplements)、[官方中文样例报告目录](https://asia.themyersbriggs.com/resources/sample-reports/)

对常见网络“28 / 60 / 88 / 93 题版”的处理原则是逐份查来源、版本、许可与计分。**题数只是属性，不是版本身份证明。** 本轮未找到能把任意网络短题库认证为正式 MBTI 的官方依据。

## 3. 为什么不能直接内置官方问卷和报告

官方版权页面明确要求，复制到软件、数据库或网络，以及改编、翻译题目和报告等材料，需要事先书面授权。购买个人测评或能下载示例报告，不等于获得 App 内置及再分发权。[官方许可说明](https://www.themyersbriggs.com/en-US/Support/Copyright-and-Permissions)、[MBTIonline 使用条款](https://www.mbtionline.com/legal)

本轮没有核实到供 DawnYew 直接采用的公开离线 SDK、完整可执行计分合同或题库再分发许可。这个结论是“未取得可采用的依据”，不是断言商业合作一定不可能。

正式路线需要交付的材料至少包括：

- 指定版本和语言的完整合法题库，以及 APK 和源代码仓库各自的分发范围。
- 计分接口或可执行算法、缺答处理、边界条件和对照样本；不能自行猜测权重。
- 中文报告内容、品牌名称使用条件，以及是否要求专业反馈流程。
- 完全离线运行的权利与技术条件。若只能跳转在线平台，就不符合本次离线需求。

## 4. 可公开获取的候选工具

| 候选 | 题目与计分可得性 | 权利与语言 | 判断 |
| --- | --- | --- | --- |
| OEJTS 1.2，32 题 PDF | 双极描述、5 点回答，四轴公式完整 | CC BY-NC-SA 4.0；英文原版，本轮未找到作者提供并验证的中文版本 | 可复算的首选研究样本；许可及翻译尚未达到发布条件 |
| OJTS / OEJTS 在线版 | 页面出现 48 / 60 等不同数量，开发页还有另一种计分题与实验题划分 | 不能推定与 32 题 PDF 同一内容版本 | 不混合题目与算法，不作为首版锁定来源 |
| 16Personalities / NERIS | 官方解释为重新组织的大五框架，含 A/T 第五轴 | 网站题目、翻译、报告等受其条款保护 | 不是官方 MBTI，也不是可抓取重发的免费题库 |
| IPIP / Mini-IPIP 等 | 有公开题目、计分键；Mini-IPIP 为 20 题、五因素 | IPIP 官方声明公有领域，可商业或非商业使用；中文译本仍应逐份核对 | 适合另一款“大五人格测试”，不能直接满足 MBTI 要求 |

来源：[OEJTS PDF](https://openpsychometrics.org/tests/OJTS/development/OEJTS1.2.pdf)、[OJTS 测试页](https://openpsychometrics.org/tests/OJTS/)、[OEJTS 页面](https://openpsychometrics.org/tests/OEJTS/)、[开发页](https://openpsychometrics.org/tests/OJTS/development/)、[NERIS 理论说明](https://www.16personalities.com/articles/our-theory)、[16Personalities 条款](https://www.16personalities.com/terms/general)、[IPIP 许可](https://ipip.ori.org/newPermission.htm)、[Mini-IPIP 计分键](https://ipip.ori.org/MiniIPIPKey.htm)。

### OEJTS 版本锁定与许可

固定来源为 Eric Jorgenson 的 2015-03-03 PDF，文件 SHA-256：

```text
813844c6ebbd323545ec67caf1a056a07518fea485f0cc6a56a34508fae09478
```

该哈希为本轮实际下载文件计算结果，用于避免以后网页更新导致研究对象悄悄变化。PDF 未提交到仓库，本轮也不复制完整问卷。

采用其材料需要遵循署名、非商业、相同方式共享等条件；翻译不能被重新标为 DawnYew 原创题库或官方中文版。[CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/)

仓库根目录 [LICENSE](../../LICENSE) 为 GPLv3。这里应分别确认代码、题库及译文的权利，不能把带 NC 条件的数据统一宣称为 GPL 可自由商用。当前未完成许可组合与 APK 分发方案的核定；建议优先争取适合本项目分发方式的额外授权。仅分目录、分模块、项目当前免费，都不足以自动得出兼容结论。

网站的结果解释也不自动与问卷享有同一许可。[其结果页](https://openpsychometrics.org/tests/OJTS/results.php)另行标注了说明文本来源。因此设计采用自行撰写的结果说明，不抓取该页或 16Personalities 的称号、插画和长报告。

### IPIP 的可用边界

IPIP 允许复制、编辑和翻译其题目，但这不等于可以把大五量表变成正式 MBTI。其解释指南也提醒连续分数不宜被随意切成类别。[IPIP 首页](https://ipip.ori.org/)、[分数解释指南](https://ipip.ori.org/InterpretingIndividualIPIPScaleScores.htm)

IPIP 列有中文译本，但声明翻译未由站点人员逐项核实。译文来源、版本、验证样本和本项目适用人群要分别记录，不将“网站收录”写成“官方中文认证”。[翻译目录](https://ipip.ori.org/newItemTranslations.htm)、[中文 IPIP-120 样本资料](https://ipip.ori.org/ChineseIPIP-120norms.htm)

## 5. OEJTS 32 题的可复算合同

以下仅用于固定候选算法，不意味着授权已经解决。每题答案为整数 1–5，表示从左侧描述到右侧描述的位置；不是所有题统一的“不同意 / 同意”。原始题号、两端文案及方向必须保持一致。

```text
IE = 30 - Q3 - Q7 - Q11 + Q15 - Q19 + Q23 + Q27 - Q31
SN = 12 + Q4 + Q8 + Q12 + Q16 + Q20 - Q24 - Q28 + Q32
FT = 30 - Q2 + Q6 + Q10 - Q14 - Q18 + Q22 - Q26 - Q30
JP = 18 + Q1 + Q5 - Q9 + Q13 - Q17 + Q21 - Q25 + Q29
```

源规则是严格大于 24 时依次得到 E、N、T、P，否则为 I、S、F、J。公式与阈值来源：[PDF 第 3 页](https://openpsychometrics.org/tests/OJTS/development/OEJTS1.2.pdf)。

以下是本轮按公式推导的测试向量，不是作者提供的信效度结论：

| 32 题输入 | IE | SN | FT | JP | 源规则类型 | 展示注意事项 |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| 全选 1 | 28 | 16 | 28 | 20 | ESTJ | 不能通过“总选左侧”来猜四轴方向 |
| 全选 3 | 24 | 24 | 24 | 24 | ISFJ | 实际四轴均在中点，不应显示为明确 ISFJ 结论 |
| 全选 5 | 20 | 32 | 20 | 28 | INFP | 应与逐题系数计算一致 |

据公式可推导每轴范围为 8–40，中点为 24。源规则与产品解释应分层：保留可复核的 `sourceType`，中点轴的展示标为“未分化”，不强推完整类型报告。分数位置不是官方 Probability Index、能力、人口百分位或诊断准确率。确切展示规则见设计文档。

中文译题需要独立双语审阅、回译和目标用户可理解性检查，保持每对描述的相对方向与含义。不承诺仅靠翻译审阅就获得中文信效度；没有相关验证时，在来源说明中如实标注。

## 6. 结果数据应如何取得

- **正式 MBTI**：采用被明确授权的对应报告数据，不能自行写一份报告后称为官方解释。
- **公开十六型自测**：类型代码、四轴分数来自锁定算法；概览、偏好解释、反思问题由 DawnYew 原创，并独立版本化。避免把类型变成职业资格、能力高低或固定人格判断。
- **IPIP 路线**：按照相应五因素量表展示；不输出 MBTI 四字母及其类型报告。

“可验证的题目来源 + 确切计分版本 + 可追溯的结果文字”比多提供几个无法核实的“精简 / 标准 / 专业版”更适合首版。

## 7. 调研验证边界

本轮由官方资料、开放题库、项目架构三个方向独立调研，并由主代理核对关键原始页面、公式和源文件哈希。确认的是公开资料与代码现状，不是已经取得出版方授权、完成中文心理测量验证或通过设备验收。

题库正式发布的外部依赖仍是授权依据和中文内容审定。当前调研文档本身不引入受限题库、授权 SDK 或联网依赖。
