# SBTI 离线接入与验证记录

日期：2026-10-27。工作树 `a44d/DawnYew`，分支 `p/duanyao.0516/feature/mbti-tool-design`，基线 `a1e86d14d0a19c6aabe4351ed03411bec664b74a`。用户指定 [UnluckyNinja/SBTI-test](https://github.com/UnluckyNinja/SBTI-test) 并确认完整授权。本记录对应功能开发及隔离 Debug 验证，受测包版本为 0.1.2（3）；之后用户授权合并推送 master 并发布新版本，发布阶段另见 [0.1.3 发布记录](../releases/0.1.3.md)。

## 实现范围

入口为“更多 / 我的 → 工具箱 → SBTI 测试”，与 MBTI 并列。包含介绍、逐题作答、题号目录、缺答定位、结果、检查改答、确认重测和确认清除。每次开始随机排列 30 道常规题，再插入爱好题；选择饮酒立即增加饮酒态度题，31 题变为 32 题。取消饮酒会删除补充题答案，再选饮酒必须重新作答。

计分、27 种结果的完整文案和原图、十五维评分及解释全部打包在本地。没有 WebView、运行时 JS、远程图片、新增联网权限或新增存储表。进度只在进程内保存，MBTI 与 SBTI 的答案、题序和结果互相独立；后台暂停隐藏投影，返回前台保留当前数据作用域内的私有进度。结束进程、数据集替换或 epoch 改变后清除进度。

分层为：`shared/core/personality` 的纯题库与计分，`shared/presentation` 的专属 SBTI 状态机，`shared/ui` 的原生 Compose 页面和资源，Android 的统一访问事件。只抽取 MBTI/SBTI 共同的 `PersonalityAccessSession`，不引入通用问卷框架；平台通过 `PersonalityTestsAccess` 一次通知前后台、待处理操作、备份恢复和清空数据边界。既有 MBTI 93 题与比分规则保持原样。

设计和任务检查表分别见 [设计文档](../superpowers/specs/2026-10-27-sbti-tool-design.md) 与 [实施计划](../superpowers/plans/2026-10-27-sbti.md)。

## 来源对照

固定来源提交 `6c24c9f2a4d3548010af44c9e1322c206f64f73d`，原 `index.html` SHA-256 为 `bfc7bbe66ae9a361220b2816651250b8769b0158c6aab8da882e6f8b2f22cea5`。算法保持原始选项 value、15 维分档、25 模板的稳定距离排序、DRUNK 优先覆盖和小于 60 的 HHHH 兜底。q14/q27 的显示顺序为值 3/2/1，按真实值计分。

独立来源提取器生成 294 组 JS oracle，包含 25 个完整模板、饮酒覆盖、60 边界和稳定同分；Kotlin 对照断言包含完整排名及维度得分。只读脚本核对 30 道常规题、2 道补充题定义、27 类型、25 模板、15 维度、45 解释和所有图片。

```sh
node scripts/verify-sbti-source.cjs --source-dir /tmp/dawnyew-sbti-20261027
```

结果通过。27 图合计 4,730,813 字节，与来源及 APK 内资源逐个 SHA-256 一致。PNG/JPG 后缀保留，WOC! 显式映射 WOC.png。IMSB/POOR/SHIT 的文本与海报名称差异、DRUN-K 海报码及原图灰条按上游保留，详见 [来源合同](../research/2026-10-27-sbti-source.md)。此验证证明移植一致性，不是心理测量效度验证。

## 构建与主机测试

实现前，核心 9 例行为测试取得失败结果；控制器测试因新 API 尚未实现而编译失败，没有把该次编译红灯记为行为测试已执行。完成实现后运行完整受影响模块。

```sh
scripts/project-gradle.sh \
  :shared:core:jvmTest :shared:presentation:jvmTest :shared:ui:jvmTest \
  :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest :androidApp:lintDebug \
  -PdawnyewIsolatedDeviceTest=true --offline
```

最终命令 `BUILD SUCCESSFUL`。Core 105、presentation 158、UI 17，共 280 项，失败/错误/跳过均为 0。包含原 MBTI 控制器测试、动态题删除与重现、随机范围、陈旧回调拒绝、暂停/数据代际清空和两个测试隔离。Lint 为 0 error、33 warning；未将编译器和 Lint 的既有警告表述为全部消除。

本次首次增加与现有 Compose 同版本的 `components-resources:1.12.2`。首次离线尝试因缓存缺失失败，在线下载后因严格依赖校验缺少记录再次停止；补充 lockfile 与 6 个 artifact 的校验记录，并逐项对照 Maven Central 发布的 SHA-256 后，上述最终离线命令通过。未关闭依赖校验、升级既有依赖或清缓存。变更限 UI/App 两份 lockfile 和 verification-metadata。

隔离 Debug APK：`androidApp/build/outputs/apk/debug/androidApp-debug.apk`，94,645,774 字节，SHA-256 `edbfa10e5bcef685a9bf3fc1df14ee43eb33862fda2fdc75ed3bc3d0f52a1154`。与 OPPO 已安装验证包的 `base.apk` SHA-256 一致。该文件是验证包，未替换正式应用或 GitHub Release。

## OPPO 真机

设备为 OPPO PKM110、Android 16 / API 36，物理 1080×2373、逻辑密度 480、初始字体 1.0。只安装 `com.dawnyew.app.validation` 与其测试 APK，使用已连接 OPPO；没有新建或使用模拟器，没有覆盖正式包。

按测试类分别执行，最终 SBTI 为 `OK (3 tests)`、35.028 秒，MBTI 为 `OK (3 tests)`、65.927 秒。对应报告保存在 `.local/sbti-oppo-final.log` 与 `.local/sbti-mbti-regression.log`。

```sh
adb -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e class com.dawnyew.app.SbtiDeviceTest \
  com.dawnyew.app.validation.test/androidx.test.runner.AndroidJUnitRunner
# ANDROID_SERIAL 为 adb 列出的 OPPO 序列号；MBTI 回归替换 class。
```

为避免主机 ADB 连接中断丢失输出，最终命令通过设备端 `nohup sh` 执行并写 `/data/local/tmp/`，结束后拉取日志。

| 范围 | 实际设备覆盖 |
| --- | --- |
| SBTI 普通与隐藏结果 | 真实控件逐题完成全部 31 题；全选值 1 得 DEAD，各维均 2 分；回看爱好题激活第 32 题并得 DRUNK；取消后删答案、再次激活必须重答；取消和确认重测；MBTI 原答案保持 |
| 会话和操作门控 | Activity 重建、后台隐藏答案、返回前台固定题序；真实仓库回执冲突触发 pending 流，同时暂停 MBTI/SBTI；测试直接调用 `acknowledgeOperation` 确认仓库回执后恢复进度，旧 token 仍拒绝，不宣称点击过该确认弹窗 |
| 长内容和结果资源 | 真实目录跳题、长题滚动选择、目录关闭重开和系统返回；HHHH 57% 兜底、Dior-s JPG 原图；清除弹窗取消与确认；安装包无 INTERNET 权限 |
| MBTI 回归 | 实际控件完成原 93 题，类型及八项得分断言、回看改答、清除、后台重建、目录和 pending 流原三个用例全部通过 |

初次组合执行先遇到 ADB 回传中断；设备端保存日志后的组合运行是 **5 通过、1 teardown 失败**，保留于 `.local/sbti-oppo-combined-retry.log`。唯一失败栈为 MBTI 完整答题用例的 `ActivityScenario.close()` 等待 DESTROYED，停在 PAUSED，没有业务断言或 suppressed 异常。现场 logcat 观察到 OplusHansManager 将验证进程冻结 141 秒，SIGQUIT 后继续执行。独立审阅未发现共享会话/helper 中有可导致等待的锁、IO 或新增生命周期调用。随后未改应用或测试代码，按类独立执行取得上述两个全绿报告；不将原组合失败改写为通过。

正常字体截图在 `.local/sbti-oppo-normal-final/`，已查看长题、目录及 DEAD/DRUNK/HHHH/Dior-s 的实际图像像素；图片未缺失、拉伸或被资源层裁切，长内容可滚动，底部操作保持可见。

另以字体倍率 2.0 执行 `SbtiDeviceTest#longQuestionDirectoryFallbackAndJpegPostersRemainUsable`，`OK (1 test)`、7.763 秒，日志为 `.local/sbti-oppo-large-font.log`。已查看 `.local/sbti-oppo-large-font/05-large-question.png`、`06-directory.png`、`08-jpeg-result.png`：长题能滚到选项，目录与底部按钮可操作，结果文本按大字体换行，图片可见部分正常加载。设备端 EXIT trap 恢复倍率，随后再次读取为 **1.0**；原分辨率和密度未修改。没有启动 TalkBack，读屏标签通过实际 accessibility 节点操作验证。

## 独立审阅与问题关闭

来源审阅覆盖纯算法、294 oracle、图片 SHA 与 code→resource 映射；发现 q10 文案引用“A 和 C”，最初 UI 未展示字母，已给所有选项补上相同的可见 A/B/C/D 和读屏标签，点击仍使用真实 value。来源审阅者已复核关闭，补齐原图与文本差异记录。

架构审阅覆盖共享访问会话、数据作用域实时 provider、Android 五处平台通知、恢复/ClearAll 的 finally、两个测试的独立返回处理及设备 helper 提取。未发现需修改的有证据缺陷。原 MBTI 三个设备用例的行为断言和实际控件流程未弱化；共享 helper 只提供点击、等待、非交互内容滚动定位、主题与截图操作。

## 验证边界与工作区保护

- Activity `recreate()` 和前后台切换不等同真实屏幕旋转或进程回收。
- HHHH/Dior-s 用源 oracle 通过生产 controller 填入答案，再核对结果与实际页面；不宣称这两项逐题通过 UI 作答。
- 备份恢复、ClearAll、系统安全锁的真实端到端操作本轮未重跑；对应门控与清理链路经过源码交叉审阅，数据代际和旧回调经过控制器测试。
- 本轮没有重新执行全部账务/OCR/悬浮窗回归，也没有构建和安装正式签名 Release。没有自动合并、推送或发布。
- 原有 `gradle.properties`、`gradle/gradle-daemon-jvm.properties` 和 `docs/implementation/ocr-release-jni-verification.json` 均保留，不进入本轮提交。交付前 SHA-256 分别核对为 `0e175e5aec4bb16d23362bbf92c7d645fd3055b825e9033e688589e67f6128c6`、`6c13aa5ba36690ccfbbb25a8807d01d98c15833e446d7ae82ddc726472cb8ef7`、`82cb39a02549e59d7de695465693e0c608b1844443d73e2b61975343b4325727`。
