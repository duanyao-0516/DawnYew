#!/usr/bin/env bash
# Rebuild verified libsodium and the minimal JNI bridge without changing global tools.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK="$ROOT/.local/crypto-work"
MODE=${1:-all}
SOURCE_SHA=adbdd8f16149e81ac6078a03aca6fc03b592b89ef7b5ed83841c086191be3349
PUBLIC_KEY=RWQf6LRCGA9i53mlYecO4IzT51TGPpvWucNSCh1CBM0QTaLn73Y7GFO3
mkdir -p "$WORK/downloads" "$WORK/logs"
fetch() { if [ ! -f "$2" ]; then curl --fail --location --silent --show-error "$1" -o "$2"; fi; }
fetch https://download.libsodium.org/libsodium/releases/libsodium-1.0.22.tar.gz "$WORK/downloads/libsodium-1.0.22.tar.gz"
fetch https://download.libsodium.org/libsodium/releases/libsodium-1.0.22.tar.gz.minisig "$WORK/downloads/libsodium-1.0.22.tar.gz.minisig"
printf '%s  %s\n' "$SOURCE_SHA" "$WORK/downloads/libsodium-1.0.22.tar.gz" | shasum -a 256 -c -
if [ ! -x "$WORK/minisign/minisign" ]; then
    [ "$(uname -s)" = Darwin ] || { echo 'Install minisign locally or provide the verified macOS build environment.' >&2; exit 1; }
    fetch https://github.com/jedisct1/minisign/releases/download/0.12/minisign-0.12-macos.zip "$WORK/downloads/minisign-0.12-macos.zip"
    printf '%s  %s\n' 89000b19535765f9cffc65a65d64a820f433ef6db8020667f7570e06bf6aac63 "$WORK/downloads/minisign-0.12-macos.zip" | shasum -a 256 -c -
    mkdir -p "$WORK/minisign"
    unzip -q "$WORK/downloads/minisign-0.12-macos.zip" -d "$WORK/minisign"
fi
"$WORK/minisign/minisign" -VP "$PUBLIC_KEY" -m "$WORK/downloads/libsodium-1.0.22.tar.gz" | tee "$WORK/logs/signature-verification.txt"
if [ "$MODE" = host ] || [ "$MODE" = all ]; then
    if [ ! -f "$WORK/host/lib/libsodium.a" ]; then
        mkdir -p "$WORK/host-src"
        tar -xzf "$WORK/downloads/libsodium-1.0.22.tar.gz" -C "$WORK/host-src" --strip-components=1
        (cd "$WORK/host-src" && ./configure --disable-shared --prefix="$WORK/host" && make -j8 install && make -j8 check) > "$WORK/logs/host-build.log" 2>&1
    fi
    TASK_JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}
    clang -std=c11 -Wall -Wextra -Werror -O2 -fvisibility=hidden -dynamiclib \
        -I"$WORK/host/include" -I"$TASK_JAVA_HOME/include" -I"$TASK_JAVA_HOME/include/darwin" \
        "$ROOT/platform/android/src/main/cpp/dawnyew_crypto.c" "$WORK/host/lib/libsodium.a" \
        -o "$WORK/host/libdawnyew_crypto.dylib"
    echo "Host JNI: $WORK/host/libdawnyew_crypto.dylib"
fi
if [ "$MODE" = android ] || [ "$MODE" = all ]; then
    NDK_DIR=${DAWNYEW_NDK_DIR:-$ROOT/.local/android-ndk-r28c}
    rg -q 'Pkg.Revision = 28.2.13676358' "$NDK_DIR/source.properties" || { echo 'Expected project NDK r28c 28.2.13676358.' >&2; exit 1; }
    TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/darwin-x86_64/bin"
    AAR="$WORK/android-src/libsodium-1.0.22.0.aar"
    if [ ! -f "$AAR" ]; then
        mkdir -p "$WORK/android-src"
        tar -xzf "$WORK/downloads/libsodium-1.0.22.tar.gz" -C "$WORK/android-src" --strip-components=1
        env ANDROID_NDK_HOME="$NDK_DIR" NDK_PLATFORM=android-30 \
            AR="$TOOLCHAIN/llvm-ar" RANLIB="$TOOLCHAIN/llvm-ranlib" NM="$TOOLCHAIN/llvm-nm" STRIP="$TOOLCHAIN/llvm-strip" \
            "$WORK/android-src/dist-build/android-aar.sh" > "$WORK/logs/android-aar-r28c.log" 2>&1
    fi
    # Upstream android-aar.sh can exit successfully after failed child builds.
    # Every expected actual library and header must exist before accepting its AAR.
    python3 - "$AAR" "$WORK/android-prefab" <<'PY'
import sys,zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    for abi in ('arm64-v8a','armeabi-v7a','x86','x86_64'):
        for module,library in (('sodium','libsodium.so'),('sodium-static','libsodium-static.a'),('sodium-minimal','libsodium.so'),('sodium-minimal-static','libsodium-minimal-static.a')):
            base=f'prefab/modules/{module}/libs/android.{abi}/'
            assert z.getinfo(base+library).file_size>0,(abi,module,'empty library')
            assert z.getinfo(base+'include/sodium.h').file_size>0,(abi,module,'missing header')
    z.extractall(sys.argv[2])
print('Verified AAR: all 16 native library variants and headers exist.')
PY
    for ABI in arm64-v8a armeabi-v7a x86 x86_64; do
        case "$ABI" in
            arm64-v8a) TARGET=aarch64-linux-android30 ;;
            armeabi-v7a) TARGET=armv7a-linux-androideabi30 ;;
            x86) TARGET=i686-linux-android30 ;;
            x86_64) TARGET=x86_64-linux-android30 ;;
        esac
        SODIUM_DIR="$WORK/android-prefab/prefab/modules/sodium-static/libs/android.$ABI"
        OUT_DIR="$ROOT/platform/android/src/main/jniLibs/$ABI"
        mkdir -p "$OUT_DIR"
        "$TOOLCHAIN/$TARGET-clang" -std=c11 -Wall -Wextra -Werror -O2 -fPIC -fvisibility=hidden -shared \
            -Wl,-z,max-page-size=16384 -Wl,-z,relro,-z,now -Wl,--exclude-libs,ALL -Wl,--no-undefined \
            -I"$SODIUM_DIR/include" "$ROOT/platform/android/src/main/cpp/dawnyew_crypto.c" \
            "$SODIUM_DIR/libsodium-static.a" -o "$OUT_DIR/libdawnyew_crypto.so"
        "$TOOLCHAIN/llvm-strip" --strip-unneeded "$OUT_DIR/libdawnyew_crypto.so"
        "$TOOLCHAIN/llvm-readelf" -lW "$OUT_DIR/libdawnyew_crypto.so" > "$WORK/logs/jni-$ABI-elf.txt"
        python3 - "$WORK/logs/jni-$ABI-elf.txt" <<'PY'
import sys
loads=[line.split() for line in open(sys.argv[1]) if line.lstrip().startswith('LOAD ')]
assert loads and all(int(row[-1],16)>=16384 for row in loads),loads
PY
        shasum -a 256 "$OUT_DIR/libdawnyew_crypto.so"
    done
fi
case "$MODE" in host|android|all) ;; *) echo 'Usage: scripts/build-crypto.sh [host|android|all]' >&2; exit 2 ;; esac
