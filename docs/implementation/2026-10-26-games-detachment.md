# DawnYew 小游戏运行依赖拆除记录

日期：2026-10-26。分支：`p/duanyao.0516/codex/dawnyew-android-app`。

## 本轮边界

用户明确要求猜拳继续作为聚会工具留在 DawnYew，其他个人小游戏独立开发；DawnYew 不继续承担游戏宿主职责，也不提供旧游戏存档迁移。本次仅拆除此前新增的 Compose 2048 集成，不调整财务、OCR 或全局悬浮截图链路。

## 已移除的活动代码

| 位置 | 处理 |
| --- | --- |
| `settings.gradle.kts` | 移除 `:games:api`、`:games:game2048` 模块注册 |
| `androidApp/build.gradle.kts`、`shared/ui/build.gradle.kts` | 移除对小游戏模块的依赖 |
| `DawnYewApplication.kt`、`MainActivity.kt` | 移除游戏目录、存档提供者及 UI 注入参数，保留悬浮截图改动 |
| `DawnYewApp.kt`、`MoreScreen.kt` | 移除小游戏入口、宿主路由、游戏标题与目录参数 |
| `GameHubPage.kt` | 移出活动源码 |
| `games/api`、`games/game2048` | 移出当前项目，包括 Compose 游戏实现、规则、存档编解码、会话及其测试 |
| `androidApp/.../games` | 移除 Android 游戏存储实现、对应单元测试与设备测试 |
| `GameDeviceTest.kt`、`PreviewGames.kt` | 移除旧宿主专用测试及预览注入 |
| `ConvergenceDeviceTest.kt`、`FinanceDeviceTest.kt`、`HomeRangeDeviceTest.kt`、`HomeScopeDeviceTest.kt` | 移除额外的游戏参数，保留财务测试 |

猜拳仍通过“更多 → 工具箱 → 石头剪刀布”访问，使用原有 `RockPaperScissorsPage` 与 `judgeRps`。没有迁移入口，也没有删除任何设备上的存档或用户数据。

## 本地留档

旧实现的 20 个专用源文件和配置文件已保存在忽略目录 `.local/retired-compose-games-2026-10-26/`，目录结构保持不变，排除 `build`、`.gradle` 和 `.kotlin` 等生成文件。留档只用于本机追溯，不属于新游戏的运行依赖或交付内容。

`manifest.json` 记录了这些文件的 SHA256；复制后逐项与原文件校验一致。`integration-before/` 保存本次修改前的 11 个集成文件，便于追溯本轮拆除边界。

依赖校验元数据保留既有校验记录，没有把校验白名单误当作实际运行依赖，也没有批量回滚此前 OCR 所需的校验记录或锁文件。旧版本的游戏评审、验证记录属于历史证据，不能用于宣称当前包仍含有小游戏。

## 静态验证

- `git diff --check` 通过。
- 对 `androidApp`、`shared`、`platform` 以及根构建配置扫描，排除生成目录后，没有 `com.dawnyew.games`、`com.dawnyew.app.games`、`:games:`、游戏宿主/存档注入、`Game2048`、`game-2048` 或“小游戏”入口残留。
- 猜拳 UI、规则、规则测试和图标文件的 SHA256 与拆除前一致；猜拳入口与路由仍存在。
- `settings.gradle.kts`、两个模块构建文件、`DawnYewApp.kt`、`MoreScreen.kt` 和四份财务设备测试在移除游戏接线后回到 HEAD 的原有内容。
- `gradle.properties`、`gradle/gradle-daemon-jvm.properties`、`docs/implementation/ocr-release-jni-verification.json` 的 SHA256 与拆除前一致。

## 本轮构建与独立复核

根代理执行：

```sh
GRADLE_USER_HOME="$PWD/.local/gradle-home" ./gradlew \
  :shared:ui:jvmTest :shared:presentation:jvmTest :androidApp:testDebugUnitTest \
  :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest :androidApp:assembleRelease \
  --offline --console=plain
```

结果 `BUILD SUCCESSFUL in 36s`。309 个任务中 54 执行、10 命中构建缓存、245 up-to-date；没有把缓存结果表述为全部重新执行。

| 验证项 | 结果 |
| --- | --- |
| shared UI JVM 测试报告 | 17 项，0 失败/错误/跳过 |
| presentation JVM 测试报告 | 140 项，0 失败/错误/跳过，含猜拳九种有序出拳组合测试 |
| Android App 单测报告 | 22 项，0 失败/错误/跳过 |
| Debug、AndroidTest APK、Release R8 构建 | 通过 |
| `debugRuntimeClasspath` 依赖报告 | 成功生成，无 `:games:` 模块依赖 |
| 新 Debug APK 的 DEX 检查 | 无原游戏包、GameHubPage、Game2048Plugin 标识；保留 RockPaperScissorsScreen |

日志保存在 `.local/games-detachment-build.log` 和 `.local/games-detachment-dependencies.log`。新 Debug APK 为 `androidApp/build/outputs/apk/debug/androidApp-debug.apk`，89,614,109 字节，SHA256 `2d2007b9018e83a3b44f1a7c322d6d523001e22b85b17e9459c56db93403d774`。该文件会被后续构建覆盖；没有将其安装到用户设备。

独立 reviewer `games_detachment_review` 检查了修改前留档与当前源码，确认仅移除游戏接线，猜拳、财务测试和悬浮截图改动保留，未发现代码阻塞。其发现的当前接入 README 与实际状态不一致问题已修正，历史游戏/架构记录已添加被新方案取代的标记。

本轮没有设备交互回归或引擎性能测试。此前 2026-10-25 的 APK 与设备报告不代表本次拆除后的产物；本记录只支持上述编译、单测报告、依赖和静态检查结论。
