# SBTI 来源、算法与离线移植核对

## 固定来源

- 仓库：[UnluckyNinja/SBTI-test](https://github.com/UnluckyNinja/SBTI-test)。
- 提交：`6c24c9f2a4d3548010af44c9e1322c206f64f73d`。
- [固定版本 index.html](https://github.com/UnluckyNinja/SBTI-test/blob/6c24c9f2a4d3548010af44c9e1322c206f64f73d/index.html)，SHA-256：`bfc7bbe66ae9a361220b2816651250b8769b0158c6aab8da882e6f8b2f22cea5`。
- 原作者署名：HTML 介绍写“B站@Q肉儿串儿”，README 和“作者的话”写“蛆肉儿串儿”，作者 UID 为 `417038183`；[原作视频](https://www.bilibili.com/video/BV1LpDHByET6/)。镜像维护者为 UnluckyNinja，镜像地址为 `sbti.unun.dev`。
- 源 README 明确未附 license。此次移植依据用户在本轮对来源使用的完整授权，不将该授权写成上游已经授予开源许可证。保留来源署名、原作者的话和娱乐提示。

## 内容与源位置

逐段核对固定版本 HTML，重新从原文提取常量并与独立审计数据比对，而非仅复用之前导出的 JSON。以下行号均针对上述固定版本：

| 内容 | 源位置 | 移植内容 |
| --- | --- | --- |
| 维度名称、模型名称 | `dimensionMeta`，第 807 行起 | 15 个维度，固定顺序 S1/S2/S3/E1/E2/E3/A1/A2/A3/Ac1/Ac2/Ac3/So1/So2/So3 |
| 常规题 | `questions`，第 824 行起 | 30 道原题面、全部选项文案和真实 value |
| 补充题 | `specialQuestions`，第 1096 行起 | 爱好题 4 个选项，饮酒态度题 2 个选项 |
| 结果 | `TYPE_LIBRARY`、`TYPE_IMAGES`，第 1121/1285 行起 | 27 种 code、中文名、intro、完整 desc、原图文件名 |
| 常规模板 | `NORMAL_TYPES`，第 1315 行起 | 25 个模板，保留原数组顺序 |
| 维度解释 | `DIM_EXPLANATIONS`，第 1417 行起 | 每维 L/M/H 各一条，共 45 条 |
| 出题 | `shuffle`、`getVisibleQuestions`、`startTest` | 30 常规题先随机，爱好题插入零基位置 1..30；饮酒态度题按条件紧随其后 |
| 计分 | `sumToLevel` 至 `computeResult`，第 1609 行起 | 15 维计分、匹配、稳定排序及特殊结果优先级 |

`SbtiCatalog.kt` 只含 Kotlin 常量；没有把 JavaScript、网页运行时或远程资源放入应用。题序和动态题生命周期由 presentation 控制器负责，纯计分器只消费明确的 `questionId → value`。

## 计分合同

每维两题相加得到 2..6 分；2/3→L、4→M、5/6→H。L/M/H 转为 1/2/3，与 25 个常规模板计算 15 维曼哈顿距离。匹配度保留源表达式 `max(0, Math.round((1 - distance / 30) * 100))`。排序为距离升序、同档命中数降序、匹配度降序；全部相同则保留原模板顺序。Kotlin 明确以源模板索引作最后排序项。

饮酒态度值 2 优先产生 DRUNK；否则最高常规匹配度小于 60 时产生 HHHH。60 恰好不会兜底。IMSB 和 WOC! 都是普通模板；没有额外触发规则。`ranked` 保留 25 个常规匹配，DRUNK/HHHH 不会伪装成常规匹配的分数。匹配度是算法指标，不表示心理学准确率。

q14、q27 的显示选项值依次为 **3、2、1**，其余常规题为 1、2、3。爱好题第 4 个选项合法且不参与维度分数。答案以源 value 计分，不能用按钮下标。

原网页在提交按钮和动态题更新处约束作答，`computeResult` 本身会把缺失答案当零分，且直接读 `drink_gate_q2`。离线核心在入口明确拒绝缺答、未知 ID、非法选项和未激活饮酒题残留，避免调用方绕过页面门控后得到伪结果。合法完整可见题集的计算行为与源 JS 一致。控制器负责删去隐藏题的旧答案，计分器不会代为忽略错误输入。

## 保留的来源差异

- IMSB 的 `TYPE_LIBRARY.cn` 是“傻者”（固定版本 HTML 第 1244 行），原海报 `IMSB.png` 写“自我攻击者”。
- POOR 的 `TYPE_LIBRARY.cn` 是“贫困者”（第 1232 行），原海报 `POOR.png` 写“贫穷者”。
- SHIT 的 `TYPE_LIBRARY.cn` 是“愤世者”（第 1220 行），原海报 `SHIT.png` 写“狗屎人”。
- DRUNK 的 `TYPE_LIBRARY.code` 是 `DRUNK`（第 1279 行），原海报 `DRUNK.png` 写 `DRUN-K`。计算、类型标识和图片映射继续使用源码中的 `DRUNK`。
- `DEAD.png`、`SHIT.png`、`ZZZZ.png` 顶部自带“可能包含引人不适的内容”灰条，属于原图片的截图像素，并非应用新增的提示层。
- 上述四组名称/code 差异与三张图片灰条均已直接查看原 PNG 确认，文案和图片分别原样保留，不统一改名、不裁去灰条或重绘海报。
- 类型 code `WOC!` 的原图片名是 `WOC.png`，不能按 code 拼图名。
- `Dior-s` 和 `JOKE-R` 原图为 JPG，其余 25 张为 PNG。27 张图按原字节复制，只将打包资源名改为小写并将 `-` 改为 `_`，加 `sbti_` 前缀。
- 作者在介绍与作者的话中使用不同署名写法，按源出处留档，不将其解释为不同作者。

## 可重复验证

```sh
node scripts/verify-sbti-source.cjs --source-dir /path/to/pinned/SBTI-test
scripts/project-gradle.sh :shared:core:jvmTest --offline
```

验证脚本离线、只读，不修改源码、fixture、图片或文档。先核验整个 `index.html` 的固定 SHA-256，再仅将审读过的常量定义和 `sumToLevel` 至 `computeResult` 的纯算法放入限时 VM；禁用动态字符串/wasm 代码生成，不提供 DOM、network、process、require、timer 或文件系统。完整 HTML、页面事件及渲染函数从不执行。

脚本从原 JS 重现 Kotlin 常量文件及 294 组 oracle，逐字比较题面、选项、结果文案、45 条解释和模板；对全部 27 张源图先核对固定摘要，再与包内图片逐一比对。没有从 Kotlin 重算期望结果。oracle 包含 3 组同分值、3 组同显示选项、25 个精确模板、4 个阈值/同分情形、3 个饮酒情形和 256 个固定种子随机样本；每组记录结果类型/模式、15 维原始分/L-M-H，以及完整 25 项排名的 code/距离/命中数/匹配度。

本轮证据：

- 红灯：`.local/sbti-core-red.log`，9 个 SBTI 测试在最小空实现上全部失败。
- 绿灯：`.local/sbti-domain-green.log`，core 全量 **105 项**、presentation 全量 **158 项**，失败/错误/跳过均为 0。Gradle 由主代理统一运行，避免并行占用。
- 源比对通过：30+2 题、27 类型、25 模板、15 维、45 条解释、294 案例；27 图片共 **4,730,813 字节**。
- 内存注入负向验证：修改原 HTML、题面、oracle 结果或包内图片字节都会被拒绝；禁止 `writeFileSync` 时正常验证仍通过。
- 该证据证明数据和算法移植、JVM 行为测试；不代表真机交互验收或心理测量有效性。Android/UI 验收另见本轮实施验证记录。

完整题库/结果/解释/模板规范化摘要：`0812e418b1070bf8c5a08f63fcfd0dd7433aa16da18401e2a6e6e7a24eb549c9`。

## 原图片摘要

下表摘要与脚本内固定摘要一一对应，均针对原字节。

| code | 原文件 | 字节 | SHA-256 |
| --- | --- | ---: | --- |
| IMSB | IMSB.png | 269351 | `c76f1b460cd4ea5dc5ff802d84619b6e2d22bdcfccdeb25ad977b476dd30d434` |
| BOSS | BOSS.png | 167151 | `15835541156378cc99aac0e53dfb9300fd663db52c37bfbfcaa9f18cb228b355` |
| MUM | MUM.png | 136647 | `4781faa501be7c6c8448622d98e44bc940c3ee1ea51c9b7f9f4bcf4a48558607` |
| FAKE | FAKE.png | 172477 | `11e1bb8e81625e60b9cba219d1f9fe61bc5bd98516b40a5b8c371ccae779f2ea` |
| Dior-s | Dior-s.jpg | 54221 | `01ad9e4561add759852e4e2f1aeee4360c1f9c53fc39fc96ed66d2ed81eb8547` |
| DEAD | DEAD.png | 241648 | `36a01e7dc94556a9725f436c77ee5d52ed34be494963efc0154875d0a7d90a6a` |
| ZZZZ | ZZZZ.png | 252388 | `566fc774c38e3a8c4ec52f243ef400fefda753184fd93bfb99bf9d5d60ca5271` |
| GOGO | GOGO.png | 163936 | `4b74a1db85e503e11009e5a6b3670afffe71b698a1a059858ed9c6f34744e333` |
| FUCK | FUCK.png | 247246 | `e325278c37ce7599af94ed2ac3da934654ac65a0906a4ba8c27e3076e5221e34` |
| CTRL | CTRL.png | 131414 | `761aeb9803c466ffa4786fbb123d7af58e5b3c9d4eeff2d5172178bd2976e488` |
| HHHH | HHHH.png | 206282 | `3f9b8ca33302a6d5710e8725dbc5db2a4e71fff82830147a3d710a3bc5e693b7` |
| SEXY | SEXY.png | 165839 | `43f7da99802985e13c6c2130f4808d60c1ee1575a4d0a6c7026b0c1298b35e4e` |
| OJBK | OJBK.png | 133654 | `c4bd40603e63f9abbc82f9b64be9f93a9bf4e7e45ca2e04097e54a7108706728` |
| JOKE-R | JOKE-R.jpg | 40206 | `71cd777402a175262ec066bac91b3bed822278bd9f5f2dfcafc36249d38bff78` |
| POOR | POOR.png | 189755 | `b491d139ab6ee97009e0536b7abd80089c3131d7ac8dbd9d5e3f59bfe81de043` |
| OH-NO | OH-NO.png | 207308 | `4cfa50279423f07908aff7b49c25a5a4445a585d0f9351dbdd6d8741afd64b79` |
| MONK | MONK.png | 188431 | `322a4be5f3b8c83ca82e996481570fa509ee819c46810bc18b2425279780ad1e` |
| SHIT | SHIT.png | 186441 | `1853e7edd9c436f354e30e426c77fa1a6044e48227287fce45516944fa6b9fba` |
| THAN-K | THAN-K.png | 145963 | `306a033d0ce6344c5bdbceb388d284f7786db8c43f06ad8557e2e341715ee2f2` |
| MALO | MALO.png | 138043 | `1af0f71c77853d8c9d1121581218f62423d635b52980e5f14dbc93592edb5f9a` |
| ATM-er | ATM-er.png | 157119 | `8838ddd4db8408b7d0c2d16b98d00a7e7634f238050812343c7ba88c34c3ffd3` |
| THIN-K | THIN-K.png | 233374 | `4d0614454d4566ea1a3b446c6705441aad3e12a406d2de4d3ffc76f35c7a920c` |
| SOLO | SOLO.png | 140640 | `6e89bd226e9292777bdd1e44bdf33bb07278fb5b7bc14dfe4567790e9b58683b` |
| LOVE-R | LOVE-R.png | 195531 | `a576c51c7f8162ba028bd92ebeb08fdd832ad4346c3b0f41eaddb36b61843f81` |
| WOC! | WOC.png | 191351 | `26fc7ef737e1353673188f827b3ff18f693603ad05ed920516aa710f4b85b918` |
| DRUNK | DRUNK.png | 221369 | `52c9fd903d4cefdb3bbf539092928ae02ba653d18a4c8b941448a69eb1afb2a2` |
| IMFW | IMFW.png | 153028 | `2079f1d17ec519fe0dee62c059f8c01a9cbb0d20617a7360b548564c6597a156` |
