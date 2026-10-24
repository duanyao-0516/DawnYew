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
  ./dev.sh release     检查优化 Release/JNI/Lint，签名并导出带版本号的 APK
  ./dev.sh init-release-key  首次初始化本机正式签名密钥（已有密钥不覆盖）
  ./dev.sh test        运行共享层与 Android 平台主机测试
  ./dev.sh lint        运行 Release Lint
  ./dev.sh check       主机测试 + Debug/AndroidTest + Release/JNI/Lint 检查
  ./dev.sh clean       清理 Gradle 构建产物（保留项目 .local 缓存和密钥）
  ./dev.sh gradle ...  直接运行任意 Gradle 任务
  ./dev.sh help        查看帮助

构建命令后可追加 Gradle 参数，例如：./dev.sh debug --offline --console=plain
Debug:   androidApp/build/outputs/apk/debug/androidApp-debug.apk
Release: artifacts/releases/DawnYew-v<versionName>-<ABI>.apk（附 .sha256）

首次发布先运行 init-release-key，并私密备份 .local/signing/release/。
Release 不自动创建替代密钥；密钥、密码、APK、构建产物及缓存不提交到 Git。
环境准备与平台加密测试所需的本机 JNI 库见 README.md。
HELP
}

TEST_TASKS=(
    :shared:core:jvmTest
    :shared:protocol:jvmTest
    :shared:storage:jvmTest
    :shared:presentation:jvmTest
    :shared:ui:jvmTest
    :platform:android:testDebugUnitTest
    :androidApp:testDebugUnitTest
)

case "$COMMAND" in
    help|-h|--help) usage; exit 0 ;;
    debug) TASKS=(:androidApp:assembleDebug) ;;
    init-release-key)
        source "$PROJECT_ROOT/scripts/project-env.sh"
        exec python3 "$PROJECT_ROOT/scripts/release-apk.py" init
        ;;
    release)
        source "$PROJECT_ROOT/scripts/project-env.sh"
        INSPECT_ONLY=false
        for argument in "$@"; do
            case "$argument" in
                --dry-run|-m|--help|-h|'-?'|--version|-v|--task-graph|--status|--stop) INSPECT_ONLY=true ;;
                --project-dir|--project-dir=*|-p*|--build-file|--build-file=*|-b*|\
                --settings-file|--settings-file=*|-c*|--exclude-task|--exclude-task=*|-x*)
                    printf 'release 不允许切换工程或跳过检查：%s。自定义任务请用 ./dev.sh gradle。\n' "$argument" >&2
                    exit 2
                    ;;
            esac
        done
        if [[ "$INSPECT_ONLY" == true ]]; then
            printf '仅查看 Gradle 信息或任务计划，跳过签名和导出。\n'
            exec "$PROJECT_ROOT/scripts/project-gradle.sh" :platform:android:verifyReleaseOcrJni :androidApp:lintRelease "$@"
        fi
        python3 "$PROJECT_ROOT/scripts/release-apk.py" check
        "$PROJECT_ROOT/scripts/project-gradle.sh" :platform:android:verifyReleaseOcrJni :androidApp:lintRelease "$@"
        exec python3 "$PROJECT_ROOT/scripts/release-apk.py" package
        ;;
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
