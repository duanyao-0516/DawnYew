# Release OCR JNI / R8 修复与交付门禁

日期：2026-10-18。

## 真实故障与根因

真机 Release 在 `OrtSession.run → convertToTensorInfo → GetMethodID` 中触发 `java_class == null`，见 `artifacts/device/release-crash-buffer.txt` 的 16:48:25 崩溃。初始化模型成功不代表运行输出转换安全。

当前 ORT 1.31.0 自编 AAR 不含 `proguard.txt`。旧 Release 的合并 R8 configuration 仅有 Sodium 和 OpenCV 规则；纯 Java 的 ORT 信息类及其构造器可被删除或改名。旧 APK 静态验证 SHA-256 为 `01e59be919dc5aa748a5aed5f1e336afff70179e8f719aeb1cf2869d98ee4b10`，缺失 1,144 项绑定定义，包括 `TensorInfo(long[], String[], int)` 和 `OnnxJavaType`。不能仅保留声明 native 方法的类。

ORT 源码 `java/src/main/native/OrtJniUtil.c:836–838` 使用字面名称 `ai/onnxruntime/TensorInfo` 和精确 JNI 构造器签名 `([J[Ljava/lang/String;I)V`。同一源码还构造 MapInfo、SequenceInfo、OnnxTensor、OnnxSparseTensor、OnnxSequence、OnnxMap、OrtException；`ai_onnxruntime_OrtSession.c` 构造 NodeInfo 和 OnnxModelMetadata；SessionOptions JNI 构造 EpContextDataReadCallbackRegistration。因此不能只补发生崩溃的单个类。

## 修复

`platform/android/ocr-consumer-rules.pro` 由平台模块 `consumerProguardFiles` 导出：

- ORT 完整包保留采用 [ONNX Runtime 官方 Android R8 规则](https://onnxruntime.ai/docs/build/android.html#note-proguard-rules-for-r8-minimization-android-app-builds-to-work)：`-keep class ai.onnxruntime.** { *; }`。
- OpenCV 完整包保留基于本项目实际生成 JNI 源码：CvException 构造器、Range.start/end、CleanableMat.nativeObj 和 native 方法均有固定名称依赖；这是项目保守规则，不声称为 OpenCV 官方推荐。
- 仅保留两套 JNI Java 绑定边界；Release 的 minify、资源缩减及应用代码优化保持开启。未修改 native AAR、模型、共享库及其固定 SHA。

## 防回归与构建证据

执行：

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17) GRADLE_USER_HOME="$PWD/.local/gradle-home" \
  .local/gradle-9.5.0/bin/gradle \
  :platform:android:verifyReleaseOcrJni :androidApp:lintRelease --offline --console=plain
```

`verifyReleaseOcrJni` 依赖 `assembleRelease`，随后运行 `scripts/build-ocr-verify-release.py`。脚本从两份固定 AAR 的 classes.jar 解析全部类定义、字段、方法及 JVM 描述符；逐项匹配 APK 中实际 DEX class_defs 与 encoded fields/methods，而不是搜索字符串或方法引用。仅排除 JNI 不会查找的 `<clinit>`（D8 可折叠空静态初始化）。额外锁定此次故障的 TensorInfo 构造器存在于输入绑定，并校验 mapping 中至少一个应用类确实以混淆后的名字定义在最终 DEX 中。

本次最终检查：129 类、3,006 方法、1,352 字段全部原名/原签名保留，应用混淆仍生效。静态 JSON 证据见 `ocr-release-jni-verification.json`。旧产物失败记录为 `.local/ocr-work/unit-tests/release-jni-red.{log,json}`；新构建和检查记录为 `release-jni-green.log`，Release 专项 lint 为 `release-jni-lint-release.log`。

最终未签名 APK：`androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk`，SHA-256 `7d3eceefc1a794eace9ff769d0bfc9ce572a953ae8b7ef3a02c8b3d1506ca92c`。

静态检查证明 R8 没有破坏已打包 JNI 绑定 ABI；不替代设备执行。主线程必须用最终签名 Release 重跑真实模型推理、输出转换与截图确认链，确认无崩溃后才能交付。

## 主线程最终设备复验

2026-10-18：签名后的最终优化 Release（SHA-256 `204a972125e7dc8b6dc27968b5de8fa39c87e62a7825bf7381a117a43a21ace4`）已覆盖安装到 PKM110/API36。真实 SAF 选择合成 PNG，识别 12.34、日期时间与商户；明确核对后保存成功。再次选择同图显示重复摘要/同日同额提示，双确认后第二次保存成功。崩溃日志仅有修复前 16:48 记录；最终应用冷启动正常。详见最终设备验收报告。
