#!/usr/bin/env bash
set -euo pipefail

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
# Development signing stays inside this ignored project directory. Preserve an existing
# standard debug identity for installed development apps; never modify the source key.
SIGNING_DIR="$PROJECT_ROOT/.local/signing"
if [[ ! -f "$SIGNING_DIR/debug.keystore" ]]; then
    mkdir -p "$SIGNING_DIR"
    chmod 700 "$SIGNING_DIR"
    SIGNING_TEMP="$SIGNING_DIR/.debug.keystore.$$"
    if [[ -f "$HOME/.android/debug.keystore" ]]; then
        cp "$HOME/.android/debug.keystore" "$SIGNING_TEMP"
    else
        "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$SIGNING_TEMP" \
            -storepass android -keypass android -alias androiddebugkey \
            -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 \
            -validity 10000 >"$SIGNING_DIR/key-generation.log" 2>&1
    fi
    chmod 600 "$SIGNING_TEMP"
    mv -n "$SIGNING_TEMP" "$SIGNING_DIR/debug.keystore"
    rm -f "$SIGNING_TEMP"
fi
cd "$PROJECT_ROOT"
exec "$PROJECT_ROOT/gradlew" "$@"
