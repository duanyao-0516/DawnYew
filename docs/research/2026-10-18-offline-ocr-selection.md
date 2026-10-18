# DawnYew Android 完全离线 OCR 选型研究

调研日期：2026 年 10 月 18 日。状态：技术设计依据与 PoC 计划，尚未完成模型运行、Android 集成或真机验收。本文只研究并定义验证方案，不创建应用工程、不安装依赖、不提供微信、支付宝或 DawnYew 历史账单文件适配器。

输入基线：[需求设计](../superpowers/specs/2026-10-18-dawnyew-requirements-design.md)、[离线快速记账与数据能力调研](2026-10-18-offline-capture-and-data-research.md)。Android 是本轮唯一实现和验收平台；iOS 只保留接口边界，不建立目标工程、不编译验证、不实现平台 OCR。

## 1. 结论与选型顺序

建议主选 **PaddleOCR 官方 Android SDK 路线，使用官方 PP-OCRv5 mobile 检测与识别 ONNX 制品作为初始基准，运行时采用编译排除遥测的 ONNX Runtime，先使用 CPU**。把 PP-OCRv6 tiny 作为同一路线的必测挑战者：如果它在同一套业务样本上不退化、资源更合适，就换用 tiny。这里的“主选”是优先实现 PoC 的方案，不是准确率或生产稳定性已验证的结论。

独立运行时替代为 **ncnn + 可追溯的 PP-OCR mobile 转换制品 + 自有 Android 接口**。它可避免绑定 ORT 的分发和遥测实现，但要承担模型转换一致性、JNI 与后处理维护成本。若 ORT 的无遥测构建、原生库兼容或资源门槛不能满足，再用同一套样本评估替代，不能仅凭“更小、更快”宣传切换。Tesseract 保留为独立识别基线；Paddle Lite 保留为现成工具链可复现时的次级候选。

不选 Google ML Kit bundled Chinese 作为当前生产主线：模型随包与输入在端侧处理有官方依据，但官方同时披露指标采集和服务器联系，无法直接满足本项目“运行时零请求、无遥测”。不能把关闭网络权限后暂时能识别视为整个 SDK 已符合要求。

本轮发现两个会改变旧结论的事实：

1. PaddleOCR 当前已有独立 Android SDK/AAR 示例，采用 ONNX Runtime，列出 PP-OCRv5 mobile、PP-OCRv6 tiny/small 的随包部署方式。不能继续用旧版 Paddle Lite 示例代表全部 Android 支持。[官方 Android 文档快照](https://github.com/PaddlePaddle/PaddleOCR/blob/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/docs/version3.x/inference_deployment/cross_platform/android_deployment.en.md)
2. ONNX Runtime **v1.31.0** 的隐私文档明确包括 Android 默认 1DS 遥测；“ORT 遥测只存在于 Windows”的旧说法已不适用于该版本。必须按固定版本、构建选项与最终制品判断。[v1.31.0 隐私文档](https://github.com/microsoft/onnxruntime/blob/v1.31.0/docs/Privacy.md)

推荐依据是模型、前后处理和制品来源较清晰，Android 接口可复用，隐私风险可在可审查构建中排除；这是一项工程判断。只有第 8 节全部通过，才能把候选升级为交付基线。最大模型、最新版本或通用 OCR 榜单第一名，都不是独立的选择理由。

## 2. 约束与证据口径

本项目所称完全离线，必须同时满足：

- 飞行模式下全新安装，未运行过应用、未缓存模型时，第一张支持范围截图即可识别。检测模型、识别模型、字符表和配置随安装交付，不使用按需模型下载或在线降级。
- 网络打开时运行同样流程，也不产生模型下载、图片上传、遥测、远程配置、在线字典或其他网络请求。
- 不依赖 Google Play 服务的安装与更新。开源路线不调用 GMS 是可实现的架构条件；仍须在无 GMS 真机验证最终包。ML Kit 的 bundled/unbundled 区别不能扩大为“所有 Google 库都强制依赖 GMS”这一未经证明的结论。
- 用户主动分享或选取一张 PNG/JPEG；先过应用锁，再识别；至多生成一条可编辑草稿；确认前账务库、图表和预算不变化。
- 仅优先覆盖微信、支付宝单笔消费支付成功页与消费详情页。文件历史导入是另一条解析链路，待用户提供模板，不从 OCR 输出推导文件格式。

本文区分三种证据：**官方事实**是官方文档、源码或制品元数据直接支持的内容；**工程判断**是基于这些事实的取舍；**待 PoC**是只有实际构建、运行或业务样本才能回答的问题。文件字节数、参数量、官方示例耗时、OCR 字符准确率、字段准确率和最终记账成功率是不同指标，不能相互替代。

## 3. 候选比较

| 路线 | 已核实的能力与边界 | Android 集成及维护判断 | 本项目位置 |
| --- | --- | --- | --- |
| ML Kit bundled Chinese | 中文模型构建时静态随包，可立即使用；API 返回文字及布局；有指标采集披露 | Kotlin/Java 接入工作较少；闭源 SDK 和模型限制审计与制品控制 | 不进入当前生产主线，可在单独隔离评估中作质量参考 |
| PaddleOCR 官方 Android SDK + ORT | 官方 `ppocr-sdk` 与 Demo 分离，支持本地 ONNX、AAR；列出 v5 mobile、v6 tiny/small | 可从官方源代码取用平台接口；需调整旧依赖、关闭 ORT 遥测并验证原生库 | **主选 PoC 路线** |
| PP-OCR mobile + Paddle Lite | 官方旧部署文档有 `.nb` 模型、ARM 手机运行及 Android 示例 | 依赖模型优化器、Paddle Lite 算子与目标 ABI 配套；维护 NDK/JNI、OpenCV；不能把 v3 的兼容证明移植给 v5/v6 | 次级候选 |
| RapidOCR + ORT Android | 主仓库是 Python；另有 Android JNI 与 Kotlin/Compose 仓库，模型可位于 assets | 桌面或 Python 更新不能证明 Android 包同步维护；必须选具体仓库与版本，不能 `pip install` 后称为 Android SDK | 前后处理参考、交叉校验；不再额外叠加一层默认包装 |
| Tesseract + Tesseract4Android | 本地语言数据支持中文及多语言组合；Android Java/JNI 包存在 | 集成边界成熟清晰；需持有 traineddata，布局/分割模式与中文小字效果待测；不能预设比神经 OCR 差或快 | 独立基线，达标才可转替代 |
| ncnn + PP-OCR mobile | Tencent ncnn 官方仓库已有 PP-OCRv5 示例及转换步骤；另有 Android 演示 | 可控 C++ 推理；要核验转换与后处理一致性，承担 JNI/ABI/生命周期维护 | **独立运行时替代** |
| MNN / LiteRT + 自选 OCR 模型 | 都有移动端运行时；运行时本身不包含完整的中文支付截图识别产品 | 仍需确定检测/识别模型、转换、字符表和 Android 封装；额外路线暂未提供足以超过主线的项目证据 | 后续候选，首轮不扩散实现 |
| 系统相册 OCR、厂商 SDK、AndroidX PDF OCR | 系统 UI 的文字提取能力不等于任意第三方应用都有统一接口；AndroidX PDF 的 ML Kit provider 仍是 ML Kit | 覆盖、模型预置、服务依赖和网络行为要分别证明；不能用一台手机相册成功替代三厂商覆盖 | 不作为首版基础依赖 |
| 云 OCR / 在线视觉模型 | 远端 API 处理需要网络 | 即使效果好也不满足首装断网和零请求 | 排除，不设自动回退 |

各路线的一手来源与容易混淆之处如下。

### 3.1 ML Kit：随包可离线推理，但不等于无遥测

官方 Android 文档当前列出的中文随包依赖是 `com.google.mlkit:text-recognition-chinese:16.0.1`，unbundled 则是 `com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.1`。官方给出的随包增量约为每种文字、每架构 4 MB，不能当成 DawnYew APK 的实测增量。正文要求 API 23；该页面自动摘要仍出现 API 21，集成以版本正文与实际 AAR 为准。[Android 接入与安装差异](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)

官方支持语言表包括中文以及相关拉丁文本；中文、数字、小数点、货币符号和中英文商户名在支付页上的混排质量仍须样本验证。[语言表](https://developers.google.com/ml-kit/vision/text-recognition/v2/languages)

隐私条款说明输入和输出不发送给 Google，但 SDK 可能为更新、兼容信息联系服务器并发送使用/性能指标；Android 披露页还明确包括 bundled features 的安装标识等数据。没有在本次查阅的一手资料中找到可据以承诺该中文版本完全停止这些行为的正式配置。**这不是指控它上传账单图片，而是它与项目更严格的零请求边界不一致。** [条款与隐私](https://developers.google.com/ml-kit/terms)、[Android 数据披露](https://developers.google.com/ml-kit/android-data-disclosure)

移除 `INTERNET` 是应用必须落实的防线，但不能把被权限阻止的遥测尝试、后台队列或外部服务行为解释成“无遥测实现”。也不建议为消除这些行为逆向修改闭源 SDK；其 API/模型使用受专门条款约束，网页示例代码的 Apache 许可不等于模型许可。[ML Kit 条款](https://developers.google.com/ml-kit/terms)

### 3.2 官方 PaddleOCR Android：当前优先验证入口

此次核查的 PaddleOCR 源码快照为 `dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf`。Android SDK 当前声明 `minSdk 26`，示例版本为 ORT 1.21.1、QuickBird OpenCV 4.5.3。SDK 自身清单为空，但这只证明该文件，不证明传递依赖或最终合并清单。示例版本也不是 DawnYew 已选定的生产版本。[SDK 构建文件](https://github.com/PaddlePaddle/PaddleOCR/blob/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/deploy/ppocr-android/ppocr-sdk/build.gradle.kts)、[版本目录](https://github.com/PaddlePaddle/PaddleOCR/blob/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/deploy/ppocr-android/gradle/libs.versions.toml)、[SDK 清单](https://github.com/PaddlePaddle/PaddleOCR/blob/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/deploy/ppocr-android/ppocr-sdk/src/main/AndroidManifest.xml)

工程策略是保留官方检测、识别与字符解码流程的可追溯性，用项目自己的窄接口隔离 SDK；不照搬 Demo 的页面、日志、图片加载器或任意联网能力。先验证原始模型和 CPU，再做裁剪、量化与加速；任何改变预处理、宽高缩放、字符表、精度或模型转换的操作都要重跑业务样本。

PP-OCRv6 已于 2026 年发布，官方将 tiny/small/medium 面向不同资源场景。其跨语言能力与通用基准值得纳入比较，但官方桌面 CPU、Apple M4、服务器 GPU 的耗时不能转写成 Android 手机成绩。v5 mobile 基准与 v6 tiny 挑战者均保留到同机对照后再决定，不默认选择 server/medium。[官方发布记录](https://github.com/PaddlePaddle/PaddleOCR/releases/tag/v3.7.0)、[PP-OCRv6 官方算法说明](https://github.com/PaddlePaddle/PaddleOCR/blob/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/docs/version3.x/algorithm/PP-OCRv6/PP-OCRv6.en.md)

### 3.3 Paddle Lite：可用历史路径，不直接等同于最新模型兼容

PaddleOCR 的旧版端侧文档明确介绍 Paddle Lite 在 ARM 手机上运行超轻量中文模型，包含模型优化和 `.nb` 部署步骤。它提供可调查的替代工具链，但旧模型和旧优化器能跑，不代表新模型所有算子可运行。[PaddleOCR 2.10 端侧文档](https://paddlepaddle.github.io/PaddleOCR/v2.10.0/infer_deploy/lite.html)

此次 GitHub release 查询中，Paddle Lite 最新标记 release 为 `v2.14-rc`，发布时间 2024-07-19。这个信息不足以断言项目已停止维护，但足以要求单独核验稳定标签、优化器与 Android 二进制配套；不能把 RC 自动当成稳定交付基线。若选择该路线，必须固定模型转换命令、Lite 版本、NDK 和 JNI 构建，验证 16 KB 页面兼容后再入包。[Paddle Lite releases](https://github.com/PaddlePaddle/Paddle-Lite/releases)

### 3.4 RapidOCR：区分主仓库、Android 包装与运行时

RapidOCR 主仓库说明其核心模型来自 PaddleOCR，当前主仓库容纳 Python 组件，其他语言已拆仓。Python 侧离线部署、模型列表或新版本支持，不自动等于 Android 包可直接采用。[主仓库与许可说明](https://github.com/RapidAI/RapidOCR/blob/7cfd3d9ef786a2e538ca30df700325e72e85b5f5/README.md)、[模型列表](https://rapidai.github.io/RapidOCRDocs/main/en/model_list/)

旧 Android ONNX 项目使用 JNI/C++；其 README 指向 Kotlin/Compose 版本。Compose 仓库说明模型放在 assets，但示例说明中的 ORT/OpenCV 版本较旧，且明确提示 OpenCV 未裁剪会增大包体。因此可参考其封装与算法实现，不建议直接拿预编译 Demo AAR 作为供应链。[Android ONNX](https://github.com/RapidAI/RapidOcrAndroidOnnx)、[Android Compose](https://github.com/RapidAI/RapidOcrAndroidOnnxCompose)

当前主仓库 README 链接的 `MODEL_LICENSES.md` 在本次读取时返回 404。README 有模型许可说明仍有价值，但不能把不存在的逐模型清单当成已完成的来源与哈希审计。优先使用 PaddlePaddle 官方模型仓库的固定 revision；若改用 RapidAI 转换文件，应补齐上游权重、转换步骤、字符表、输出校验与独立摘要。

### 3.5 Tesseract：维护清楚的独立基线

Tesseract 官方提供不同取舍的 traineddata，包括 `tessdata_fast` 和 `tessdata_best`；fast/best 使用 LSTM 引擎。先测 `chi_sim+eng`，需要繁体时再加入对应数据，不能默认为语言文件越多越准确。fast/best 名称是官方模型族定位，不是 DawnYew 手机端性能排名。[Tesseract 数据说明](https://tesseract-ocr.github.io/tessdoc/Data-Files.html)、[fast 模型说明](https://tesseract-ocr.github.io/tessdoc/Data-Files-in-tessdata_fast.html)

Tesseract4Android 当前 README 给出 4.9.0 包，使用 Tesseract 5.5.1、Leptonica 1.85.0，支持 API 21 起。语言文件需要应用可读的本地目录；其示例会从 assets 复制到私有目录，因此安装后可能保留两份，必须计入磁盘预算。README 的联网下载建议不适用本项目。实例线程安全、取消与释放也必须遵守封装约束。[Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android)

工程判断：先在小字、深色背景、分散文本块和多个金额的支付截图上比较。没有本项目数据前，不声称它“中文一定差”，也不假定换成 best 就能解决字段歧义。

### 3.6 ncnn：实际可行，但转换和后处理是责任边界

Tencent ncnn 是面向移动端的 C++ 推理框架；官方 `examples/ppocrv5.cpp` 已给出 PP-OCRv5 mobile 转换与运行示例，说明这条路线不只是设想。[ncnn](https://github.com/Tencent/ncnn)、[官方 PP-OCRv5 示例](https://github.com/Tencent/ncnn/blob/master/examples/ppocrv5.cpp)

该示例对检测框扩张有简化处理，不能默认与 PaddleOCR 官方后处理完全一致。独立 Android 演示仓库也只是集成参考；本次 `nihui/ncnn-android-ppocrv5` 根目录 LICENSE/API 查询没有取得独立许可证，不能因 ncnn 本体是 BSD 就推定整个演示和资产也是 BSD。生产路线应使用许可明确的代码、官方模型自行转换，并保留转换日志。[Android 演示](https://github.com/nihui/ncnn-android-ppocrv5)、[示例后处理源码](https://github.com/Tencent/ncnn/blob/master/examples/ppocrv5.cpp)

RapidAI 的 Android ncnn 项目提供另一种 AAR/JNI 路径，其旧模型、依赖和许可需按该仓库独立核查。[RapidOcrAndroidNcnn](https://github.com/RapidAI/RapidOcrAndroidNcnn)

首轮使用 CPU，不启用 Vulkan、NNAPI 或厂商加速。理由是先固定可复现的跨厂商行为和误差来源；这不表示 GPU 一定更慢。只有 CPU 未过速度/功耗门槛，且加速路径经过同样准确性、兼容性和零网络验证时才启用。

### 3.7 其他路线为何暂不进入首轮实现

MNN 与 LiteRT 都能作为端侧模型运行时，但需要另行确定完整的检测与中文识别模型。对于当前单张支付截图任务，增加一套转换和前后处理链的成本，尚无本项目实测收益支持。[MNN 官方仓库](https://github.com/alibaba/MNN)、[LiteRT Android](https://developers.google.com/edge/litert/android)

AndroidX PDF 的 `MlKitOcrProvider` 明确是基于 ML Kit 的实现，不是可绕过其模型和隐私条件的系统免费 OCR 引擎。本次未找到能保证三厂商、无 GMS、首装断网的统一 Android 框架 OCR 服务，因此不依赖相册“提取文字”或系统页面能力。[AndroidX PDF OCR provider](https://developer.android.com/reference/androidx/pdf/ocr/playservices/package-summary)

华为等厂商 SDK 有端侧文字能力，但 SDK/模型授权、预置服务、首次资源获取和诊断行为要独立审核；本次证据不足以将其列为满足全部约束的替代。厂商示例的开源许可不替代 SDK 许可。[华为 ML Kit 官方资料](https://developer.huawei.com/consumer/en/doc/hiai-guides/ml-android-0000001050817043)、[HMS 官方示例](https://github.com/HMS-Core/hms-ml-demo)

Cloud Vision 等远端 OCR 必须通过服务端 API 获取结果；免费额度、缓存和失败降级都不能使它满足本应用的离线协议，因此排除。大视觉语言模型也不因为可以下载就优先于移动 OCR：本轮没有其端侧体积、耗时或更低字段错误率的证据。[Cloud Vision OCR](https://docs.cloud.google.com/vision/docs/ocr)

## 4. 许可证与制品需要分层锁定

| 层 | 已核查内容 | 交付前必须补齐 |
| --- | --- | --- |
| PaddleOCR SDK/算法代码 | 仓库 Apache-2.0 | 采用的 commit、修改记录、LICENSE/NOTICE、第三方代码清单 |
| Paddle 官方 ONNX 权重 | 下节四个具体模型仓库均标注 Apache-2.0 | 固定 revision、模型和字符表 SHA-256；随包保留许可和归属 |
| ORT | MIT；隐私行为随版本和构建变化 | 源码标签、构建参数、原生制品哈希及传递依赖许可；许可证宽松不代表无遥测 |
| Paddle Lite | Apache-2.0 | 模型转换器、目标库、JNI wrapper 的版本和许可独立记录 |
| RapidOCR | 主仓库代码 Apache-2.0，README 说明 Paddle 派生模型沿用上游许可 | Android 子仓库、每个转换模型和来源不能用主仓库许可替代 |
| ncnn | BSD-3-Clause；官方 PP-OCRv5 示例有同类 SPDX 声明 | Android 包装、转换权重、字典、OpenCV 各自核查；没有许可证的演示代码不直接纳入 |
| Tesseract/Tesseract4Android/traineddata | 引擎、包装和已查官方语言数据为 Apache-2.0 | Leptonica 的 BSD 条款、libpng/libjpeg 等依赖许可另存；固定语言数据版本 |
| OpenCV 及包装 | QuickBird 包装仓库为 BSD-3-Clause；OpenCV 本体和内置第三方代码另有版本对应的许可 | 不用 wrapper 的单个许可证覆盖 native 二进制全部内容；优先可复现的受支持构建 |
| ML Kit SDK 与模型 | Google 专门服务条款；示例代码许可不是 SDK/模型许可 | 当前方案不纳入；若重新评审，须先取得与零网络一致的证据 |

许可依据：[PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR/blob/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/LICENSE)、[ORT](https://github.com/microsoft/onnxruntime/blob/v1.31.0/LICENSE)、[Paddle Lite](https://github.com/PaddlePaddle/Paddle-Lite/blob/develop/LICENSE)、[ncnn](https://github.com/Tencent/ncnn/blob/master/LICENSE.txt)、[Tesseract](https://github.com/tesseract-ocr/tesseract/blob/main/LICENSE)、[tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast/blob/main/LICENSE)、[tessdata_best](https://github.com/tesseract-ocr/tessdata_best/blob/main/LICENSE)、[Leptonica](https://github.com/DanBloomberg/leptonica/blob/master/leptonica-license.txt)、[QuickBird OpenCV 包装](https://github.com/QuickBirdEng/opencv-android/blob/master/LICENSE)。这是一份选型证据清单，不代替对最终发布包逐项生成的第三方声明。

### 4.1 已查询到的模型制品快照

以下数据来自 2026-10-18 对 PaddlePaddle 官方 Hugging Face 模型仓库元数据的读取，未下载或运行模型。字节数是公开 `inference.onnx` 文件大小；不是安装包增量、解压后总占用、运行内存或性能。模型卡标注许可，但文件的数据类型和完整运行依赖尚须在 PoC 解包检查。

| 候选文件 | 固定 revision | 文件字节数 | 上游公布的 LFS SHA-256 |
| --- | --- | ---: | --- |
| [v5 mobile det](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det_onnx/tree/e6f4fa85f00e168c862bc462aebca69eef9b3d3d) | `e6f4fa85f00e168c862bc462aebca69eef9b3d3d` | 4,826,518 | `a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d` |
| [v5 mobile rec](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_rec_onnx/tree/ed152b8b495f84de93cda5709d768548a9127622) | `ed152b8b495f84de93cda5709d768548a9127622` | 16,534,782 | `da72dc72ca4dc220df0dfde68c1dedc31c58d3e76a25871122e5056227d50092` |
| [v6 tiny det](https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_det_onnx/tree/2ba1506c0380b8f0b03dd142459aac66d4421f6c) | `2ba1506c0380b8f0b03dd142459aac66d4421f6c` | 1,780,590 | `193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8` |
| [v6 tiny rec](https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_rec_onnx/tree/2612ab37152ae0a677521bae4e1e3d4fb4cf7c30) | `2612ab37152ae0a677521bae4e1e3d4fb4cf7c30` | 4,462,639 | `9ef676d6ed3c88256a2d92c640c44f25b0c40947e111b14b8be8f594091563e6` |

两组 det+rec 文件总量分别约 **20.37 MiB**、**5.95 MiB**，尚未包含配置、字典、运行时、OpenCV 与额外磁盘副本。小文件只是 v6 tiny 值得比较的理由，不证明其字段准确率或处理耗时更好。上述哈希是上游元数据，实际下载后必须重新计算核对，不能在尚未下载时写成“已验签”或“本地哈希校验通过”。

模型清单最终还须记录：模型 ID、下载 URL、revision、许可证和归属、每个本地文件的 SHA-256、输入输出名称/形状/dtype、动态宽度约束、opset、字符表长度及 blank/空格规则、缩放和归一化参数、转换工具及参数、精度设置、兼容 runtime/ABI、随包路径。模型升级是应用版本的一部分，不后台更新。

## 5. 零网络与 Android 原生依赖的门槛

### 5.1 ONNX Runtime 必须固定版本和构建行为

此次查询到最新正式 release 为 v1.31.0，发布于 2026-10-09；其官方隐私文档与 v1.21.1 明显不同。v1.21.1 文档说当时遥测仅实现于 Windows，v1.31.0 则明确 Android 使用 1DS，官方构建默认开启；不能把任一版本的说明推广到全部 ORT。[v1.31.0 release](https://github.com/microsoft/onnxruntime/releases/tag/v1.31.0)、[v1.21.1 隐私文档](https://github.com/microsoft/onnxruntime/blob/v1.21.1/docs/Privacy.md)

v1.31.0 的关闭方式有重要差异：编译参数 `--no_telemetry` 排除 provider；初始化前设置 `ORT_DISABLE_TELEMETRY=1` 可阻止 uploader、事件和持久标识创建；API 开关只抑制非必要事件，初始化事件可能已经发出。因此本项目优先要求**编译时排除**，不能只在创建 session 后调用关闭 API。自编译也并非天然无遥测，默认构建仍须明确关闭。[固定版本的关闭说明](https://github.com/microsoft/onnxruntime/blob/v1.31.0/docs/Privacy.md#disabling-telemetry)

生产 ORT 版本暂不冻结；首组 PoC 明确从 **v1.31.0 源码、`--no_telemetry`、CPU Execution Provider** 开始，版本组合见第 7 节。该选择使隐私行为与已核查的版本证据对应，不代表已经通过 Android 构建、算子或性能验证。记录构建脚本、参数和 AAR 哈希；若因兼容问题换版，必须重新核查该版本的隐私实现并记录决策。不能为省去审查长期盲用 1.21.1，也不能自动升级到最新 Maven 包。

ORT 官方提供 Android Java/C/C++ 包与裁剪自定义包路径。先跑完整且无遥测的 CPU 构建确认正确性，再按固定模型算子裁剪；裁剪不能改变输入处理和输出语义。Release/R8 规则、JNI 资源释放和实际 ABI 均需验证。[移动部署](https://onnxruntime.ai/docs/tutorials/mobile/)、[Android 构建](https://onnxruntime.ai/docs/build/android.html)

### 5.2 包级和运行级同时检查

- 合并清单不含 `INTERNET`，依赖清单不含模型下载、崩溃上传、埋点和远程配置组件；检查所有变体与打包后的真实清单，不只检查应用源清单。
- 平台入口不把图片转交有联网行为的外部 OCR 服务；GMS/HMS 不参与核心链路。仅系统选图提供本地 URI，不以云相册中尚未下载的图片测试离线承诺。
- 联网状态下测冷启动、首次识别、多次识别、取消、失败、后台返回、退出重开、空闲后重开等场景；结合应用 UID 流量、DNS/连接跟踪和依赖行为检查。短时间无包不能证明永不联网；二进制审查、无权限、代码路径和运行观测共同构成证据。
- 日志只保留阶段耗时、错误类型、模型版本等不含账单内容的信息。识别全文、商户、金额、原始路径、图片像素和用户备注不得进入日志或崩溃附件；调试样本输出只存在于明确的离线验证环境。

所有候选都包含或可能包含原生库。必须检查 arm64-v8a，以及工程实际承诺的其他 ABI；在 Android 15 及以上 16 KB 页面环境验证 native ELF 和 APK 打包对齐、启动、推理与释放。更新 AGP 或应用自身 NDK 不会自动修复依赖内旧 `.so`。[Android 16 KB 页面指导](https://developer.android.com/guide/practices/page-sizes)

## 6. OCR 和账务的技术边界

建议公共层只依赖 `OcrPort` 和不含 Android 类型的结果协议。Android 实现负责授权 URI、受限解码、方向校正、模型与会话、像素预处理、推理及资源释放；公共层负责字段候选、支付语义判定、草稿状态、确认与账务校验。iOS 只保留同一接口，不在本轮填充空实现或自动在线实现。

OCR 结果至少包含处理尺寸、坐标变换信息、文本块/行、四点框、原始置信分数、模型/解析规则版本、错误或取消结果。不同引擎的置信分数不可直接当成“字段有 99% 正确概率”，也不可跨引擎统一取 0.9 就宣布可靠。低置信数字、多个支付状态、金额冲突和关键文字缺失要进入待核对或不支持状态。

内部流程为：**分享/选图 → 锁检查 → 输入边界校验 → 本地受限解码 → OCR → 语义筛查与候选字段 → 单条可编辑草稿 → 用户确认 → 正式账务事务**。草稿阶段无正式流水写入权限。

| 情况 | 处理规则 |
| --- | --- |
| 金额文字正确，但同时有原价、优惠、实付 | 结合标签、相对位置与状态筛选；无唯一证据则展示候选，不默认选择最大/最粗/最后一个数字 |
| “支付失败”“待支付”“交易关闭”或状态冲突 | 不生成看似已成功消费的普通草稿；明确不支持或要求手动重新录入，不把已识别金额当成成功证据 |
| 退款、转账、收入、多笔列表、长截图 | 首版不推断为普通支出；退款必须从有效原支出建立关联 |
| 日期缺失 | 可以建议今天，但保留“待确认”来源；不能把截图时间自动写成消费发生时间 |
| 类别或账本建议 | 仅依据可解释的本地规则和当前上下文；渠道不等于用途账本；所有建议可改 |
| 缺字或识别失败 | 提供清楚错误状态与手动入口；不得因手动兜底存在而把 OCR 样本计为识别成功 |
| 疑似重复图片或同额同日流水 | 提示疑似重复，允许用户明确保留；同一草稿提交 ID 幂等，不将不同真实消费合并 |
| 取消、权限拒绝、URI 失效、超限、进程中断 | 不写正式流水；清理应用临时副本；原相册图片不删除；下次安全启动清理残留 |

输入资源限制属于实现设计：采用有界字节流和解码尺寸预检，先拒绝明显超大/畸形/不支持图片，再分配像素内存；一次仅运行一张图，限制待处理队列与识别框数量。图片上限、最大边长和缩放策略应与总方案一致，并用原始字号可读性验证，不能只为了速度缩小到金额小数点无法辨认。任何裁剪都不能移除支付失败/退款等关键状态后继续判定成功。

## 7. PoC 组合、记录与决策方式

首轮只做能回答选择问题的组合，避免同时维护所有路线：

| 编号 | 组合 | 要回答的问题 |
| --- | --- | --- |
| A（首个固定候选） | PaddleOCR Android SDK commit `dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf` + ORT `v1.31.0` 源码构建 `--no_telemetry`、仅 CPU EP + OpenCV `4.14.0` 官方源码构建；v5 mobile det/rec 使用第 4.1 节固定 revision、配套配置和字符表 | 主线能否满足全部离线、准确性、速度、资源与维护门槛；先验证组合可构建和输出一致，再开展性能对照 |
| B | 与 A 相同 runtime/前后处理边界，替换为 v6 tiny 官方 ONNX及匹配配置 | 更小制品能否保持金额/危险状态质量，是否有真实资源收益 |
| C | Tesseract4Android + 固定 `chi_sim+eng` fast；必要时同组 best | 独立引擎在支付页上的质量与资源基线，是否存在更简单的达标选择 |
| D | ncnn + 来源可追溯的 v5 mobile 转换制品 | A/B 的构建、包体或隐私维护成本过高时，独立运行时能否达标 |

OpenCV `4.14.0` 已有官方发布，首组采用其源码构建替换示例的旧 QuickBird 4.5.3 包装。选择仍属工程判断：沿用 4.x API 路线，暂不把 OpenCV 5 的迁移增加到首轮变量中；官方 5.0 发布页另外提示原始 Android SDK 包存在 16 KB 对齐问题，不能由“官方包”推断所有原生制品均合格。4.14.0 自编译也必须验证 Java/JNI API、前后处理一致性、第三方依赖许可、ELF/APK 对齐和真机行为；不据此宣称该组合已兼容或已胜出。NDK、CMake、构建参数及产物 SHA 在实际 PoC 构建时与主方案工程候选一起固定，生产锁版仍以 G2 为门槛。[OpenCV 4.14.0 官方发布](https://github.com/opencv/opencv/releases/tag/4.14.0)、[OpenCV 5.0 官方发布与 Android 提醒](https://github.com/opencv/opencv/releases/tag/5.0.0)

ML Kit 只有在明确隔离的评估工程中才能作为可选文本质量参考，不进入 DawnYew 生产依赖；其参考结果也不能证明零网络达标。Paddle Lite、MNN、LiteRT 仅在上述路线有具体阻碍时启动，不能无限扩大模型对比。

每次实验记录：源码和模型摘要、构建/依赖锁、ABI、页面大小、系统版本、设备 SoC/RAM、GMS 状态、Release/R8 配置、线程数、加速器、输入分辨率、原始/处理尺寸、冷暖状态、检测/识别/解析/草稿渲染分段耗时、完整耗时、APK/安装体积、PSS/native/Java 内存、结果和失败类别。原始样本与结果只在授权的本地验证目录保存。

先排除不满足零网络、首装可用、许可、危险状态和稳定性的组合，再比较满足门槛者的完整录入耗时、包体和维护成本。若 A/B 都通过，选择在冻结样本上字段质量不退化、三厂商资源结果更好且可重复构建的较小方案；否则保留更可靠者。不要仅靠平均 OCR 推理耗时做加权排名，把一次严重的支付状态误判“平均掉”。

## 8. 验收：240 张冻结样本、三厂商与完整录入

本节沿用需求中已有数值，并细化统计口径。新补充的资源和危险状态阈值是**建议工程门槛**，尚非实测成绩；进入开发基线前随技术方案一起收口。任何不达标都需要有记录的重新评审，不能悄悄放宽范围。

### 8.1 样本与设备

冻结验收集至少 **240 张**：160 张支持范围内单笔成功消费，微信/支付宝各 80，建议各自支付成功页 40、详情页 40；另 80 张异常样本按下表分组。深浅色、系统字号、屏幕比例、中英文商户、人民币符号、小数点、千分位、不同金额位数、截图分享压缩和支付页面版本要有标记与交叉覆盖，不能只改同一模板的金额凑足样本。

| 异常主分组 | 数量 | 必须检查的危险情况 |
| --- | ---: | --- |
| 失败、待支付、取消、关闭、状态冲突 | 16 | 有金额但不是成功支出；“未支付”不能只匹配“支付” |
| 退款、部分退款、退款处理中 | 16 | 不错记为支出/收入，不建立无原支出的退款 |
| 转账、收款与非消费方向 | 12 | 识别到金额不能默认支出 |
| 多金额、原价/优惠/实付、复杂抵扣 | 12 | 歧义必须显式，不静默选择错误金额 |
| 多笔列表、长截图、拼接或重复页面 | 12 | 不把多个交易合成一笔，不仅截取一块后忽略上下文 |
| 模糊、小数点丢失、截断、畸形/超限输入 | 12 | 不用补零或猜测弥补证据；超限前置拒绝，无 OOM |

至少三台不同厂商 Android 真机，每台运行同一完整 240 张；至少一台没有 GMS。建议覆盖两种 SoC 家族，包含 Android 12 及以上、6GB RAM 的既定中端基准机；另加 16 KB 页面设备或环境做兼容门槛。设备清单未落实前不声称有机型覆盖。

使用授权脱敏图或合成图，不默认读取用户相册。标注至少包括支持/不支持原因、关键文本框、实付金额（分）、收支方向、支付状态、日期是否存在、预期候选字段和危险处理状态。两人独立标注关键金额及状态，分歧先复核。开发调参样本与这 240 张验收集分开；不能反复按验收集修规则后仍称作独立验证。

### 8.2 准确性与业务安全

| 指标 | 门槛与口径 |
| --- | --- |
| 首装离线 | 每台全新安装、无历史模型缓存、飞行模式首次识别支持图成功；模型缺失不得联网补齐 |
| 文本层诊断 | 记录字符错误率 CER、金额字符串和关键支付状态文字召回；仅用于定位，不替代下述字段与业务指标 |
| 金额字段完全匹配 | 160 张支持图中，每台至少 157 张金额（按分）完全匹配，即 ≥98%；无候选、错金额、需人工改金额均计失败，不从分母剔除 |
| 类型字段 | 160 张中每台至少 159 张正确，即 ≥99%；成功集全部是消费时该指标不能证明异常识别，必须同时看危险集 |
| 危险状态处理 | 80 张每张进入标注的歧义/不支持/手动处理状态；**将失败、退款、转账等高风险页无提示地当成普通成功消费的次数为 0**。建议作为硬门槛 |
| 缺失和推测字段 | 日期缺失、分类或账本建议明确待核对；不允许把推测当作已识别事实；单独报告日期/商户提取率和准确率，不藏入总准确率 |
| 草稿边界 | 全部 240 张识别完成但未确认时，正式流水和统计变化为 0；所有草稿都允许修改和取消 |
| 最终业务正确性 | 测试任务由使用者检查并确认后，金额、类型、日期、分类、账本与标注一致；重复点击和中断重试至多写一条；测试内最终错误数为 0 |

不把三台设备的结果简单合并后掩盖某台失败；每台分别过门槛，并分平台、深浅色和异常类型报告。240 张是首轮验收证据，不是未来所有页面的统计保证。最终无错也不反向说明 OCR 全对：必须报告用户更正数量和耗时。

### 8.3 耗时、包体、内存与稳定性

| 项目 | 门槛与测量方法 |
| --- | --- |
| 解锁后至草稿可编辑 | 统一 1080p 支持图，P95 ≤3 秒，包含读取、解码、必要模型加载、推理、解析和渲染；冷进程首张与暖态分开报告并分别过门槛，不删除首次模型复制时间 |
| 耗时样本数 | 每机至少 30 次冷进程首张；40 张代表图各暖态测 3 次，报告 P50/P95/最大值与超时。全新安装第一张另列，不能混为暖态 |
| 完整录入价值 | 至少 5 人、每人同组 20 笔；手动与截图任务顺序平衡。截图端计入截图、分享/选图、解锁、等待、核对、纠错和保存；截图中位耗时较手动至少下降 20%，最终测试账单无错 |
| APK 增量（新增建议） | 同一 Release、同一 arm64-v8a 分发口径，OCR 相对无 OCR 基线增量 ≤50 MiB；模型文件大小不能代替实测；多 ABI/universal APK 另列 |
| 安装后增量（新增建议） | 含 native 库、模型与首次提取副本 ≤100 MiB；不得为了减少 APK 指标把模型移到首启联网下载 |
| 峰值内存（新增建议） | 6GB 基准机 1080p 流程，相对同页面无 OCR 的峰值总 PSS 增量 ≤180 MiB，进程总 PSS ≤300 MiB；同时报告 native/Java heap 与系统压力下行为 |
| 连续运行（新增建议） | 100 次识别/取消交替后无崩溃、ANR、OOM、会话泄漏；释放后稳定 PSS 相对初始稳定点增长 ≤20 MiB；记录热状态和尾部耗时，不能只测前几张 |
| 失败与取消 | 解码超限、模型损坏、会话失败、取消和进程结束不写正式流水；UI 可恢复、资源可释放；底层若不能立即取消，丢弃结果并阻止并发累积 |
| 网络 | 最终生产变体无 INTERNET、无模型下载和遥测依赖路径；联网验证场景请求数 0，留存清单/依赖/构建和观察记录 |

资源阈值是为控制小工具的成本提出的预算，不是任何引擎已达到的成绩。若实测需要调整，应说明具体设备、失败原因和用户价值影响并更新方案；不以“手机有 6GB 内存”作为无限放大内存的理由。

## 9. 定型前仍待回答的问题

1. 官方 SDK 前后处理对两组具体模型是否完全匹配，ONNX 输入类型、opset、字符表和动态宽度是否验证；同图在官方参考流水线与 Android 上的关键输出是否一致。
2. 哪个 ORT 稳定标签能以明确无遥测配置复现，如何锁定工具链和依赖，最终 AAR 是否确实排除 1DS；现代 OpenCV 与 ORT 组合是否通过所有 ABI/16 KB 页面检查。
3. v5 mobile 与 v6 tiny 在小数点、混排商户、浅色小字、支付状态和多金额场景中谁更可靠；是否有必要增加方向分类器，还是仅凭有界方向校正已覆盖。
4. 模型/预处理裁剪、量化或 ncnn 转换能否保持相同字段与危险状态结果；额外维护成本是否值得资源收益。
5. 三厂商首次断网、240 张冻结集、完整录入价值和资源门槛是否通过；目前全部缺少实测记录。
6. 最终图片边界、主方案支持的 Android 最低版本、设备/SoC 与分发 ABI 是否已与完整技术方案统一。

当前可冻结的是接口隔离、随包模型、零网络构建要求、单图单草稿与确认事务、证据和验收方法；具体 runtime 生产版本、模型胜出者、性能结论与交付完成状态仍不能冻结。若首轮均未达标，应重新评审截图 P0 的引擎或范围，不以手动输入可用来替代 OCR 验收。
