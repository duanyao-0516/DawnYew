#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK="$ROOT/.local/crypto-work"
"$ROOT/scripts/build-crypto.sh" host
mkdir -p "$WORK/vector-recheck"
clang -std=c11 -Wall -Wextra -Werror -I"$WORK/host/include" \
    "$ROOT/platform/android/src/test/native/generate_vectors.c" "$WORK/host/lib/libsodium.a" \
    -o "$WORK/vector-recheck/generate_vectors"
(cd "$WORK/vector-recheck" && ./generate_vectors > vectors.txt)
for FILE in "$ROOT"/platform/android/src/test/resources/crypto-v1/pattern-*.dwybak; do
    cmp "$FILE" "$WORK/vector-recheck/$(basename "$FILE")"
done
echo 'All six independent native golden vectors match the checked-in fixtures.'
