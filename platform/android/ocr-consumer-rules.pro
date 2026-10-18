# ORT uses FindClass/GetMethodID with literal Java names, including pure-Java
# metadata classes and constructors (TensorInfo, MapInfo, NodeInfo, etc.).
# Keeping only native methods does not preserve this ABI. Official ORT guidance:
# https://onnxruntime.ai/docs/build/android.html#note-proguard-rules-for-r8-minimization-android-app-builds-to-work
-keep class ai.onnxruntime.** { *; }

# OpenCV's generated JNI also accesses Java constructors/fields by name (e.g.
# CvException(String), Range.start/end and CleanableMat.nativeObj). Keep the
# packaged binding surface; app code and other dependencies remain optimized.
-keep class org.opencv.** { *; }
