#!/usr/bin/env bash
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/project-env.sh"
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
