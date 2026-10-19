#!/usr/bin/env bash
# Shared environment for Gradle and local release signing. Source this file.
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export GRADLE_USER_HOME="$PROJECT_ROOT/.local/gradle-home"

if [[ -n "${DAWNYEW_JAVA_HOME:-}" ]]; then
    export JAVA_HOME="$DAWNYEW_JAVA_HOME"
elif [[ -x /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/java ]]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
elif [[ -x /usr/libexec/java_home ]]; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
elif [[ -z "${JAVA_HOME:-}" ]]; then
    echo "Set DAWNYEW_JAVA_HOME or JAVA_HOME to a JDK 17 installation." >&2
    exit 1
fi

if [[ -z "${ANDROID_HOME:-}" ]]; then
    if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
        export ANDROID_HOME="$ANDROID_SDK_ROOT"
    elif [[ -d "$HOME/Library/Android/sdk" ]]; then
        export ANDROID_HOME="$HOME/Library/Android/sdk"
    elif [[ -d "$HOME/Android/Sdk" ]]; then
        export ANDROID_HOME="$HOME/Android/Sdk"
    fi
fi
