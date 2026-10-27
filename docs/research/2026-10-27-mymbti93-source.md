# MyMBTI 93 题来源与离线计分契约

## 来源与使用范围

- 项目：[saogegood/MyMBTI](https://github.com/saogegood/MyMBTI)。
- 固定提交：`492f8e08a6c5ab3a68f60ad24618e3b96ddf1dde`。
- [题库与原始算法](https://github.com/saogegood/MyMBTI/blob/492f8e08a6c5ab3a68f60ad24618e3b96ddf1dde/mbti_test_93questions.htm)。
- HTML 原始字节 SHA-256：`8cbc7165f12b94114a9f1e085d0dca66eaa7c87735a97af2444b4ec4179c78b9`。
- [同版本 README](https://github.com/saogegood/MyMBTI/blob/492f8e08a6c5ab3a68f60ad24618e3b96ddf1dde/README.md) 明确欢迎分享、改造和引用。用户已确认研究使用授权并指定本版本。

本轮移植这个第三方项目的 93 题及其结果算法，不将其标注为 DawnYew 原创题库或官方 Global 量表。来源授权说明不构成对该题库心理测量效度的证明。

## 题面与数据

应用内 `Mbti93Catalog` 是本地 Kotlin 常量，包含 1–93 连续题号，每题一个题干、A/B 两段原文和 A 所属极性。HTML 的 279 个可见标签是题面来源，`myMBTItest()` 的 `MBTI_Questions` 表是映射来源。只移除已单独建模的题号、A/B 前缀，并将 HTML 空白归一化；措辞、标点和原映射保留。

第 27–53、74–93 题的“你更喜欢：”题干只出现在可见 HTML 中，已纳入题库。五档用整数 1–5 表达；源页通过 `optionN_1` 到 `optionN_5` 的 checked 状态读取答案，单选控件本身没有 value。

源映射存在语义异常，当前为保证来源一致性原样保留。例如第 63 题“定日程很合我意”映射 P，第 88 题“不受拘束/有计划”映射 J/P，第 52 题“竞争性/好心肠”映射 F/T，第 57 题落在 SN，第 58 题落在 TF。不能按题意自行更正后仍声称与源算法一致。

题量分别为 EI 20、SN 17、TF 33、JP 23；每轴最多原始分分别为 40、34、66、46。题库的归一化 canonical SHA-256（每题 id、prompt、optionA、optionB、firstLetter 以 tab 分隔，题目间以 LF 分隔）为 `bd18c6bd80f0869b8e7b88d241d6b94ab3bd07850da9df069d3a41d656ef4611`。

## 计分契约

| A 所属极性 | 档 1 | 档 2 | 档 3 | 档 4 | 档 5 |
|---|---:|---:|---:|---:|---:|
| E/S/T/J | A +2 | A +1 | 0 | B +1 | B +2 |
| I/N/F/P | A +1 | A +2 | 0 | B +1 | B +2 |

第二行 A 端的权重反转是源代码真实行为，必须保留。结果使用原始八项整数分，轴顺序固定为 E/I、S/N、T/F、J/P。每轴同分取 E、S、T、J；全中间档得到 ESTJ 和八项 0 分。结果没有百分比、置信度、概率或自动生成的人格报告。中文类型名称来自 `getChinesePersonality()`，包括 INTP 的“逻辑学家型人格”。

`Mbti93Scoring.score` 要求恰好 93 个合法题号，每个值为 1–5。缺答、额外/非法题号和越界值均抛出 `IllegalArgumentException`。明确选择 3 算已答且不加分，`neutralCount` 记录这个数量。这与源页将缺答和中间档一起计为“未测”不同，是产品的完成度流程约束；全部作答后的分数和类型与来源保持一致。

## 独立对照与可重现验证

运行来源校验需要本地 Python 3、Node 和固定版本 HTML；不下载网络内容，也不要求这些工具随应用发布：

```bash
python3 scripts/verify-mbti93-source.py /path/to/mbti_test_93questions.htm
scripts/project-gradle.sh :shared:core:jvmTest --offline
```

脚本先校验 HTML 原始 SHA-256，再提取可见题面与极性，逐项对照 Kotlin 题库。计分 oracle 来自源 `myMBTItest()` 本身；仅将其、`showTotal8type()` 的纯判型前缀、`getChinesePersonality()` 放进受限 Node VM。document stub 仅允许 checked 查询；没有执行完整 HTML、页面渲染、定时器、外链跳转或网络请求。`--write-fixtures` 仅用于显式重新生成测试 oracle；普通校验不会写文件。

静态测试 oracle 覆盖 465 个单题/档位贡献，以及 40 组完整答案：5 组同档、2 组交替、1 组四轴非零同分、16 类型和 16 组确定性混合。core JVM 测试还覆盖题库摘要、连续编号、所有题号的缺答、额外/替换非法题号、每题越界值、中立数、同分及输入顺序无关性。另有独立审阅从源映射和 32 条计分语句重新核算全部 oracle，避免以另一份相同 Kotlin 算法自证。

以下分数顺序为 `E,I,S,N,T,F,J,P`：

| 完整答案 | 八项原始分 | 类型 |
|---|---|---|
| 全 1 | 16,12,10,12,32,17,28,9 | ENTJ |
| 全 2 | 8,24,5,24,16,34,14,18 | INFP |
| 全 3 | 0,0,0,0,0,0,0,0 | ESTJ |
| 全 4 | 12,8,12,5,17,16,9,14 | ESTP |
| 全 5 | 24,16,24,10,34,32,18,28 | ESTP |
| 奇数 1 / 偶数 5 | 22,14,16,11,42,18,22,18 | ESTJ |
| 奇数 5 / 偶数 1 | 18,14,18,11,24,31,24,19 | ESFJ |
| 非零同分构造 | 14,14,11,11,24,24,18,18 | ESTJ |

非零同分构造：以全 1 为底，改 `65→4、75→5、81→5、83→5、84→4、86→5、87→5、88→5、90→5、91→4、93→5`。

## 本轮验证记录

- 先写 8 项测试，第一次执行因 `Mbti93Result` 等 API 尚不存在在 `compileTestKotlinJvm` 失败，日志：`/tmp/dawnyew-mbti93-core-red-api.log`。
- 补齐 API 空实现后再次执行，8 项中 7 项失败，明确暴露题库缺失、分数/类型不符、输入未校验；日志：`/tmp/dawnyew-mbti93-core-red-behavior.log`。剩余一项是输入不变与顺序无关性，空实现天然满足，不作为红灯证据。
- 完整实现后来源校验通过，确认 93 题题面/映射、465 单项和 40 完整案例与固定来源一致。
- `scripts/project-gradle.sh :shared:core:jvmTest --offline` 全量通过：96 tests、0 failures、0 errors、0 skipped，`BUILD SUCCESSFUL in 13s`。日志：`/tmp/dawnyew-mbti93-core-green.log`。现有 AGP commonTest host-test 配置提示、DateRanges/PanelQueries/FinanceTest 编译警告仍存在，本轮未修改这些配置或文件。

应用运行时不解析 HTML，不需要网络、WebView 或源页外部资源。源页的自动跳转、倒计时、远程图标和人格详情外链未移植。核心单元测试覆盖纯业务行为；Android UI、生命周期和设备交互由对应层验收，不能用 core JVM 通过代替。
