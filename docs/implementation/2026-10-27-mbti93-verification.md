# MyMBTI 93 题实现与验证记录

状态：93 题功能已实现，来源校验、262 项 JVM 测试、Android 编译、lint 及 OPPO 真机三项回归通过。验证边界见下文。

## 本轮范围

用户指定 MyMBTI 的 93 题版本并确认研究使用授权，替代此前讨论的 Global 92/143 题需求。固定来源为 `saogegood/MyMBTI` 提交 `492f8e08a6c5ab3a68f60ad24618e3b96ddf1dde`，HTML SHA-256 为 `8cbc7165f12b94114a9f1e085d0dca66eaa7c87735a97af2444b4ec4179c78b9`。题库、计分及来源差异详见[来源契约](../research/2026-10-27-mymbti93-source.md)，实施状态见[计划](../superpowers/plans/2026-10-27-mbti93.md)。

- `shared/core`：固定 93 题文案与映射，校验完整答案并计算类型和八项原始整数分。
- `shared/presentation`：一次进程内测试，负责作答、缺答定位、导航、提交、改答失效及访问投影。
- `shared/ui`：更多/我的共用工具箱入口，介绍、逐题作答、题号目录、结果、重测与清除。
- Android 装配：提供当前数据作用域和前台访问状态，后台或锁定时隐藏投影，恢复/清空/数据失效时清除测试。

答案与结果不写入 Bundle、preferences、Room 或备份；运行时不解析 HTML，不使用 WebView 或网络人格详情页。进程结束后不恢复测试。

## 源算法与产品流程

保留原始源码的非对称计分：A 为 E/S/T/J 时档 1、2 分别加 2、1；A 为 I/N/F/P 时分别加 1、2；档 3 不计分；档 4、5 给 B 分别加 1、2。四轴同分取 E、S、T、J。

产品要求 93 题全部明确作答后计算，中立档算已答。源页将缺答与中立档一起计为未测，此处只调整完成度流程，完整答案的计分保持一致。全中立得到 ESTJ 与八项 0 分，同时说明不能从这些答案判断倾向。结果不显示概率、准确率或官方报告。

## 已完成验证

| 层级 | 命令 / 证据 | 当前结果 |
| --- | --- | --- |
| 来源一致性 | `python3 scripts/verify-mbti93-source.py /tmp/dawnyew-mymbti-20261027/mbti_test_93questions.htm` | 通过：93 题文案/极性、465 个单题档位贡献、40 组完整答案 |
| 核心 JVM | `scripts/project-gradle.sh :shared:core:jvmTest --offline` | 96 tests，0 failures / errors / skipped；其中 MBTI 8 项 |
| 表现层 JVM | `scripts/project-gradle.sh :shared:presentation:jvmTest --offline` | 149 tests，0 failures / errors / skipped；其中 MBTI controller 9 项 |
| UI JVM | `scripts/project-gradle.sh :shared:ui:jvmTest --offline` | 17 tests，0 failures / errors / skipped |
| Android 隔离包构建 | `scripts/project-gradle.sh :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest -PdawnyewIsolatedDeviceTest=true --offline` | debug 与 androidTest assemble 通过；日志 `.local/mbti-android-green.log` |
| 独立源码审阅 | 题库/算法、controller、返回代理、UI、Android 装配及数据作用域链路 | 已完成；发现的访问同步问题已修复并复核，无遗留有证据的 P 级问题 |
| Android lint | `scripts/project-gradle.sh :androidApp:lintDebug -PdawnyewIsolatedDeviceTest=true --offline` | 0 errors、33 warnings；无 MBTI 文件条目 |
| OPPO 真机 | `adb -s 3B6F4NE8A66ZXTRL shell am instrument -w -r -e class com.dawnyew.app.MbtiDeviceTest com.dawnyew.app.validation.test/androidx.test.runner.AndroidJUnitRunner` | `OK (3 tests)`，62.284 秒；Android 16 / API 36，PKM110 |

core 结果来自 `shared/core/build/test-results/jvmTest/TEST-*.xml`，presentation 结果来自 `shared/presentation/build/test-results/jvmTest/TEST-*.xml`；本轮重新汇总了两组 XML 数量。Android 构建日志为 `.local/mbti-android-build.log`。

核心测试的失败到通过记录见来源文档：API 缺失日志 `/tmp/dawnyew-mbti93-core-red-api.log`，空实现行为失败日志 `/tmp/dawnyew-mbti93-core-red-behavior.log`，完整实现通过日志 `/tmp/dawnyew-mbti93-core-green.log`。这些日志是本地验证证据，不作为仓库交付物。

来源 oracle 直接运行固定 HTML 中的计分与判型函数，使用受限 Node VM 和 checked-only DOM stub。应用本身不依赖 Python 或 Node。单题贡献、混合答案、16 种类型、零分与非零同分、缺答、非法题号和越界选项均有核心测试覆盖。

controller 测试覆盖实际改答使结果失效、仅查看不改变结果、暂停时隐藏投影且恢复后保留答案、旧 generation 回调拒绝、同 datasetId 新 epoch 清除和数据不可用清除。它们没有验证真实 Activity 回调、系统锁屏或 Compose 布局。

## 真机回归和问题闭环

- OPPO 使用 `com.dawnyew.app.validation` 与 `.validation.test` 隔离包，未覆盖正式版或正式账本。最终日志：`.local/mbti-oppo-device-verified.log`。
- 逐题实际操作完成 93 题：全选第一档得到 ENTJ，八项分数为 `16,12,10,12,32,17,28,9`；从目录把第 1 题改为第五档后，旧结果失效，重算为 `16,12,10,12,32,17,26,11`。
- 验证“更多”和“我的”两处工具箱入口、题目回退、Activity 重建、切后台再回来保留答案、结果返回介绍并重新打开、清除确认的系统返回与确认清除、全中立结果展示。Activity 重建覆盖状态保留路径，不等于实际旋转设备验收。
- 目录验证可滚到第 93 题、跳题作答、关闭和重开、系统返回关闭；缺答提交会定位第一道未答题。关闭后检查弹窗控件消失，重开后检查重新出现，避免误点同名标题造成假阳性。
- `MainActivity` 原来只观察 `ui`，遗漏 `pendingOperation` 独立变更。新增真实仓库回执/冲突用例取得红灯后，改为合并观察两条流；OPPO 证明 pending 出现时隐藏答案、清除后恢复答案，隐藏和恢复期间均拒绝旧 generation。独立状态审阅复核通过。
- 确认包中没有 `android.permission.INTERNET`，题库和计分均内置。没有更改用户手机的飞行模式或网络设置。
- 真机截图通过测试进程绘制本应用窗口获得，没有移除生产 `FLAG_SECURE`；默认字体截图位于 `.local/oppo-mbti93/verified-default/`。

首次共享模拟器运行被其他任务的 Unity Activity 抢占，不作为产品红灯。后续隔离模拟器取得 pending 回归红灯；用户要求使用 OPPO 后，立即停止并删除本轮新增的 AVD，后续验收全部在 OPPO 上执行。测试脚本对离屏/已选单选项以及弹窗窗口就绪的等待已修正；最终完整回归重新执行通过。

## 验证边界

真机已覆盖暗色、默认字体以及 2.0 倍大字体。大字体下目录滚动、跳题、关闭/重开、缺答定位与系统返回专项通过（`OK (1 test)`，8.033 秒，`.local/mbti-oppo-large-font.log`）；测试结束已恢复手机原字体倍率 1.0，未更改原分辨率或显示密度。大字体截图位于 `.local/oppo-mbti93/verified-large-font/`。没有专项执行真实系统安全锁解锁、进程被系统回收、备份恢复及 ClearAll 的端到端破坏性场景；数据代际清除、同 ID 新 epoch、旧回调和暂停恢复有 controller 单测及 Android 接入源码审阅覆盖。没有启动 TalkBack，读屏名称通过真实 accessibility 节点操作校验。重测确认具备实现与源码审阅，但没有独立完整真机重测用例。

## 交付边界

本轮只在当前功能分支做本地提交，不推送或发布。预存的 `gradle.properties`、`gradle/gradle-daemon-jvm.properties` 与 `docs/implementation/ocr-release-jni-verification.json` 不属于本轮功能交付；文档整理未修改这些文件。后续提交仅包含本轮文件。

该测试用于自我了解，非官方 Global 量表；算法复算一致和单元测试通过不代表心理测量效度验证。
