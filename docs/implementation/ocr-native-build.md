# 离线 OCR 实现与验证记录

日期：2026-10-18。此记录说明真实依赖、实现边界和已执行验证；设备验收未因此自动通过。

## 版本与来源

技术方案第 10 节、`docs/research/2026-10-18-offline-ocr-selection.md` 中的正式路线可取得，无需替换版本或改用联网服务。

| 组件 | 固定标识 | 取得证据 |
| --- | --- | --- |
| PaddleOCR Android SDK | `dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf` | 固定提交的官方 raw 文件返回 HTTP 200，逐文件 URL、大小与原始 SHA-256 见 `ocr-upstream-sdk-manifest.json` |
| ONNX Runtime | `v1.31.0`，commit `402979ac1bef89dcd55add41eaa7277c407599eb` | 官方 tag、源码归档和构建脚本可取得；从源码构建，不使用默认 Maven AAR |
| OpenCV | `4.14.0`，annotated tag object `ea8e60798e0319ccbe902f7994153b574f639809` | 官方 tag、源码归档可取得 |
| PP-OCRv5 mobile det | `e6f4fa85f00e168c862bc462aebca69eef9b3d3d` | 官方 PaddlePaddle Hugging Face 固定 revision；模型 4,826,518 字节 |
| PP-OCRv5 mobile rec | `ed152b8b495f84de93cda5709d768548a9127622` | 官方 PaddlePaddle Hugging Face 固定 revision；模型 16,534,782 字节 |

官方地址：

- [PaddleOCR SDK 固定目录](https://github.com/PaddlePaddle/PaddleOCR/tree/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/deploy/ppocr-android/ppocr-sdk)
- [ORT 1.31.0](https://github.com/microsoft/onnxruntime/tree/v1.31.0)、[ORT privacy 文档](https://github.com/microsoft/onnxruntime/blob/v1.31.0/docs/Privacy.md)
- [OpenCV 4.14.0](https://github.com/opencv/opencv/tree/4.14.0)
- [det 固定模型](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det_onnx/tree/e6f4fa85f00e168c862bc462aebca69eef9b3d3d)
- [rec 固定模型](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_rec_onnx/tree/ed152b8b495f84de93cda5709d768548a9127622)

最初 GitHub commit API 的一次 HTTP 403 是请求限流；随后固定 raw 文件和递归 tree 可取得，不能把 403 解释为版本不存在。研究文档中两项 v6 tiny revision 同样可取得；本次未引入，仍需另行挑战评估。

源码归档 SHA-256 锁位于 `scripts/build-ocr-native.py` 和 `ocr-native-source-archives.json`。模型与配置的 SHA-256 锁位于 `platform/android/src/main/assets/ocr/manifest.json`，运行时流式校验四个文件后才创建 session。所有模型随 APK 打包，无首次下载、ML Kit、GMS、HTTP 客户端或动态模型地址。

## 构建与产物

正式 ABI 为 arm64-v8a。额外 x86_64 用于显式选择的开发构建；默认 APK 是否包含它由主工程 ABI 配置决定。

- NDK r28c `28.2.13676358`：项目 `.local/android-ndk-r28c`。官方 darwin zip 为 952,495,160 字节，SHA-1 `fc20a6bf15a30fb3428c9b60a7308793a362dc6d`，下载时匹配官方仓库校验值。
- JDK 17、Python 3.12.14、CMake 3.28.2、Ninja；本机使用 DevEco 附带的 CMake/Ninja，仅调用编译工具，不修改其文件或配置。
- SDK 的 Android 35 `android.jar` 用于 OpenCV Java wrapper 编译；JNI 的最低 API 为 30。应用 compileSdk 由主工程管理。
- 工作文件、下载缓存、上游 Java Gradle 8.7 缓存均在 `.local/ocr-work`，不使用全局 Gradle 缓存。

复现命令（将工具路径替换为本机已安装位置）：

```sh
export OCR_CMAKE_BIN=/path/to/cmake
export OCR_NINJA_BIN=/path/to/ninja
export OCR_NDK_PATH="$PWD/.local/android-ndk-r28c"
python3.12 scripts/build-ocr-native.py --component all --abi arm64-v8a --jobs 8
python3.12 scripts/build-ocr-native.py --component all --abi x86_64 --jobs 8
python3 scripts/build-ocr-verify.py
```

脚本生成 `platform/android/libs/onnxruntime-no-telemetry-1.31.0.aar`、`platform/android/libs/opencv-4.14.0.aar`，保留已构建的另一 ABI。两者为严格文件依赖，不允许缺失时静默跳过。重新构建应使用干净的 `.local/ocr-work/build` 与源码目录；脚本会校验下载归档，但不会覆盖开发者对已解压源码的修改。日志和实际命令保存在 `.local/ocr-work/logs`。

ORT 使用 CPU provider、MinSizeRel、`--no_telemetry`、`onnxruntime_USE_TELEMETRY=OFF`、NNAPI OFF、`--skip_tests`、显式 16KB linker alignment。其 arm64 构建遇到上游 KleidiAI 未用常量被 `-Werror` 提升为错误，采用上游支持的 `--compile_no_warning_as_error` 继续构建；未改动 ORT 算法源码。上游 ORT 单测未运行，不能把交叉编译视作上游测试通过。

OpenCV 使用 core/imgproc/imgcodecs/java、CPU、静态 C++ runtime；OpenCL、IPP、ITT、FFmpeg、GStreamer 等关闭。JNI 由官方源码生成；AAR 中 Java 类由官方生成的 core/imgproc/imgcodecs/utils wrapper 和官方 Android `Utils` 编译，不包含相机 UI。`BUILD_ANDROID_PROJECTS=OFF` 时仍显式保留 Gradle wrapper 生成类型以生成 JNI bindings，再手工打包 AAR。JPEG/PNG 等实际静态链接依赖许可随资产分发。

## 遥测、16KB 与许可证

ORT 两个 ABI 的 CMakeCache 必须存在 `onnxruntime_USE_TELEMETRY:BOOL=OFF`；Windows telemetry 也为 OFF。Android 默认构建启用 1DS，故不能以关闭应用 INTERNET 权限替代编译关闭。本实现另外在首次 `OrtEnvironment` 初始化前设置 `ORT_DISABLE_TELEMETRY=1`，并调用 `setTelemetry(false)`。

`scripts/build-ocr-verify.py` 读取实际 AAR：校验模型哈希；确保 AAR 不声明 INTERNET；解析全部 JNI ELF 的 PT_LOAD，要求 alignment 至少 16KB；检查 ORT native 中无已知 1DS endpoint/类标识、classes.jar 无 telemetry 类。该检查是静态证据，不是运行时抓包或 16KB 设备实测，APK ZIP 对齐还需整包检查。

`assets/ocr/licenses` 分发 PaddleOCR Apache-2.0、ORT MIT 与 ThirdPartyNotices、模型原始 model card、OpenCV 相关 JPEG/PNG/cpufeatures/FlatBuffers/Carotene 许可；OpenCV 主许可随 OpenCV AAR 资产进入 APK。NOTICE 保留 Independent JPEG Group 要求的声明。原始 SDK 文件清单保留上游摘要，修改文件头另有 DawnYew 标记。

## 包装与 SDK 的局部修正

`AndroidOfflineOcrEngine.getInstance(context)` 提供 `suspend recognize(bitmap: Bitmap): com.dawnyew.core.OcrResult` 和 `suspend release()`。Bitmap 不进入 core。调用方负责只在解锁时调用，并保证 Bitmap 直到调用返回（包含取消路径）都不被 recycle。包装不保存图片、OCR 全文、商户或金额，也不记录它们。

单实例运行一个任务，正在识别时新调用报 busy；CPU intra-op 固定 2，inter-op 1，sequential execution，rec batch 1。取消不会强行终止 JNI：实际 native 返回前保留互斥锁，返回后丢弃结果；release 等待实际 native 停止再关闭。初始化在不可取消区域完成并记录 session 所有权，避免返回边界取消泄漏刚创建的 session。

SDK 基于固定提交 vendored，修改仅包括：

1. `ORTSessionManager` 关闭遥测并固定 sequential/inter-op 设置。
2. `RecPreprocessor` 保持 BGR。原 SDK 将 BGR 转为 RGB，而固定 rec `inference.yml` 的 `DecodeImage.img_mode` 为 BGR；det 同样明确 BGR。保留上游归一化与 CTC 字典算法。
3. `DBPostProcessor` 轮廓数超过 1000 时拒绝整张图片，避免静默截断后把多笔页面误作一笔；`OCREngine` 文字区域超过 128 时在裁剪前拒绝。
4. `RecPreprocessor` 在分配前检查归一化宽度上限 3200，并在失败/成功路径释放自己创建的 Mats；不把异常长文字横向压缩成可识别结果。

图片包装最多接受宽、高各 1920；较大图片需由 ImageInputService 安全缩放。模型输出的低置信度文字保留给规则层做拒绝判断，不因置信度低直接删除危险状态。

`PaymentScreenshotParser` 只产生待确认候选：微信/支付宝渠道、单笔成功支出证据成立后才提取；失败、待支付、取消、退款状态、转账、收款、列表均拒绝。`申请退款` 按操作入口处理。金额必须有小数点与两位小数，不补点、不取最大金额；多个实际支付金额或证据不足时无默认金额。字段置信度门槛 0.85；同一 block 内重复成功也拒绝。缺失、非法或冲突日期保持为空，不改成今天。商户来自明确标签；所有结果仍须 UI 核对保存。

## 已执行验证

- 固定 det/rec 的全部四个文件 SHA-256 一致。
- arm64-v8a 与 x86_64 的 ORT/OpenCV 均从源码构建成功；各阶段日志保留。最终 ABI、动态库大小、SHA-256 和全部 PT_LOAD alignment 见 `ocr-native-artifacts.json`。
- 实际两个 AAR 的 classes.jar + Android API 对全部包装及 vendored Kotlin 独立编译成功。
- OCR 规则、取消互斥、资源上限的 23 项 JVM 测试通过；包含先观察失败再修复的歧义、重复成功和无效置信度回归。独立 JUnit 日志：`.local/ocr-work/unit-tests/green.log`。主工程 Gradle 测试结果由统一构建记录提供。
- `scripts/build-ocr-model-smoke.py` 使用项目局部 Python ORT 1.31.0 CPU（导入前关闭 telemetry）与 OpenCV 4.14.0，对生成的非用户文字实际运行 det 和 rec，识别出“支付成功”“12.34”“示例咖啡店”三个区域。模型字典含 CTC blank/space 共 18,385 项，与真实输出维度一致。结果见 `ocr-model-smoke.json`。这验证了真实模型可执行，不代表 Kotlin/OpenCV Android 像素链已验收。

## Release JNI 保留门禁

真机曾在压缩后的 `OrtSession.run` 输出转换阶段发生 JNI 崩溃。仅靠 Debug/模型初始化或 host 推理不能证明 Release 安全。平台现在通过 `ocr-consumer-rules.pro` 导出 ORT 官方完整绑定保留规则及 OpenCV 绑定规则；Release 优化保持开启。

交付前执行 `:platform:android:verifyReleaseOcrJni :androidApp:lintRelease`，前者依赖 assembleRelease，并比较实际 AAR 类 ABI 与最终 APK 的 DEX 类/字段/方法定义。本次 129 类、3,006 方法、1,352 字段全量保留检查通过；旧故障 APK 会被此检查拒绝。完整根因、官方来源、构建日志与产物 SHA 见 [Release 修复记录](ocr-release-r8-fix.md)；JSON 证据见 `ocr-release-jni-verification.json`。最终签名 Release 仍须主线程在设备上重跑实际 OCR。

## 仍需人工/设备验收

尚未通过的 G2：至少 240 张授权样本、微信/支付宝成功消费及危险负样本；3 家厂商真机含无 GMS；离线首次安装；网络请求观测；Android 真正 native inference 与像素/颜色链；16KB 页大小设备加载；金额精确率/拒绝率、冷暖耗时和 P95、峰值 RSS；连续 100 次取消/锁屏/返回的 native session/Bitmap 释放；损坏资源失败展示；最终 release APK 的单 ABI 体积和 ZIP 对齐。

本轮未操作设备、未执行 adb、未下载用户截图。设备验证不便时以上保留人工验证，不宣称离线 OCR 准确率或发布门禁已通过。
