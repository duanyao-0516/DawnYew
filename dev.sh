#!/usr/bin/env bash
# Project-local entry point. Safe to invoke from outside the repository.
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMMAND="${1:-help}"
if [[ $# -gt 0 ]]; then shift; fi

usage() {
    cat <<'HELP'
DawnYew 常用命令

  ./dev.sh debug       编译 Debug APK
  ./dev.sh release     编译优化 Release，检查 OCR JNI 保留规则并运行 Release Lint
  ./dev.sh test        运行共享层与 Android 平台主机测试
  ./dev.sh lint        运行 Release Lint
  ./dev.sh check       主机测试 + Debug/AndroidTest + Release/JNI/Lint 检查
  ./dev.sh clean       清理 Gradle 构建产物（保留项目 .local 缓存和密钥）
  ./dev.sh gradle ...  直接运行任意 Gradle 任务
  ./dev.sh help        查看帮助

构建命令后可追加 Gradle 参数，例如：./dev.sh debug --offline --console=plain
Debug:   androidApp/build/outputs/apk/debug/androidApp-debug.apk
Release: androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk

Release 不自动签名；APK、签名文件、构建产物及缓存不提交到 Git。
环境准备与平台加密测试所需的本机 JNI 库见 README.md。
HELP
}

TEST_TASKS=(
    :shared:core:jvmTest
    :shared:protocol:jvmTest
    :shared:storage:jvmTest
    :shared:presentation:jvmTest
    :platform:android:testDebugUnitTest
)

case "$COMMAND" in
    help|-h|--help) usage; exit 0 ;;
    debug) TASKS=(:androidApp:assembleDebug) ;;
    release) TASKS=(:platform:android:verifyReleaseOcrJni :androidApp:lintRelease) ;;
    test) TASKS=("${TEST_TASKS[@]}") ;;
    lint) TASKS=(:androidApp:lintRelease) ;;
    check) TASKS=("${TEST_TASKS[@]}" :shared:ui:compileKotlinJvm
        :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest
        :platform:android:verifyReleaseOcrJni :androidApp:lintRelease) ;;
    clean) TASKS=(clean) ;;
    gradle)
        if [[ $# -eq 0 ]]; then
            printf 'gradle 需要至少一个任务或参数，例如 ./dev.sh gradle tasks\n' >&2
            exit 2
        fi
        exec "$PROJECT_ROOT/scripts/project-gradle.sh" "$@"
        ;;
    *) printf '未知命令：%s\n\n' "$COMMAND" >&2; usage >&2; exit 2 ;;
esac

exec "$PROJECT_ROOT/scripts/project-gradle.sh" "${TASKS[@]}" "$@"
