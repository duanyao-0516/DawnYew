#!/usr/bin/env python3
"""Verify the JNI binding ABI in the actual R8 APK, without Android/device tools.

The platform intentionally keeps the complete ORT/OpenCV Java binding packages.
Read class definitions and their declared fields/methods from the pinned AARs;
require the same names and JVM descriptors in DEX class definitions (references
alone are insufficient). <clinit> is excluded because D8 may fold empty static
initializers; JNI never resolves it. Also require evidence of app obfuscation.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import struct
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
AARS = ("onnxruntime-no-telemetry-1.31.0.aar", "opencv-4.14.0.aar")


class Reader:
    def __init__(self, data):
        self.data, self.pos = data, 0

    def uint(self, size):
        value = int.from_bytes(self.data[self.pos:self.pos + size], "big")
        self.pos += size
        return value

    def skip_attributes(self):
        for _ in range(self.uint(2)):
            self.uint(2)
            length = self.uint(4)
            self.pos += length


def class_abi(data):
    r = Reader(data)
    assert r.uint(4) == 0xCAFEBABE, "invalid classfile"
    r.uint(4)
    pool = [None] * r.uint(2)
    i = 1
    while i < len(pool):
        tag = r.uint(1)
        if tag == 1:
            length = r.uint(2)
            pool[i] = data[r.pos:r.pos + length].decode("utf-8", errors="replace")
            r.pos += length
        elif tag in (7, 8, 16, 19, 20):
            pool[i] = r.uint(2)
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            r.uint(4)
        elif tag in (5, 6):
            r.uint(8)
            i += 1
        elif tag == 15:
            r.uint(3)
        else:
            raise AssertionError(f"unsupported constant-pool tag {tag}")
        i += 1
    r.uint(2)
    name = "L" + pool[pool[r.uint(2)]] + ";"
    r.uint(2)
    interfaces = r.uint(2)
    r.pos += 2 * interfaces
    result = {"fields": set(), "methods": set()}
    for kind in ("fields", "methods"):
        for _ in range(r.uint(2)):
            r.uint(2)
            member, descriptor = pool[r.uint(2)], pool[r.uint(2)]
            if member != "<clinit>":
                result[kind].add((member, descriptor))
            r.skip_attributes()
    return name, result


def dex_abi(data):
    assert data[:4] == b"dex\n", "expected standard DEX"
    def u4(offset):
        return struct.unpack_from("<I", data, offset)[0]
    def leb(offset):
        value, shift = 0, 0
        while True:
            byte = data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value, offset
            shift += 7
            assert shift <= 35, "malformed DEX ULEB128"
    strings = []
    for i in range(u4(56)):
        _, offset = leb(u4(u4(60) + i * 4))
        strings.append(data[offset:data.index(0, offset)].decode("utf-8", errors="replace"))
    types = [strings[u4(u4(68) + i * 4)] for i in range(u4(64))]
    protos = []
    for i in range(u4(72)):
        _, ret, params = struct.unpack_from("<III", data, u4(76) + i * 12)
        args = [] if not params else [types[struct.unpack_from("<H", data, params + 4 + j * 2)[0]] for j in range(u4(params))]
        protos.append("(" + "".join(args) + ")" + types[ret])
    fields, methods = [], []
    for i in range(u4(80)):
        owner, typ, name = struct.unpack_from("<HHI", data, u4(84) + i * 8)
        fields.append((types[owner], strings[name], types[typ]))
    for i in range(u4(88)):
        owner, proto, name = struct.unpack_from("<HHI", data, u4(92) + i * 8)
        methods.append((types[owner], strings[name], protos[proto]))
    result = {}
    for i in range(u4(96)):
        values = struct.unpack_from("<8I", data, u4(100) + i * 32)
        owner, offset = types[values[0]], values[6]
        members = {"fields": set(), "methods": set()}
        result[owner] = members
        if not offset:
            continue
        counts = []
        for _ in range(4):
            count, offset = leb(offset)
            counts.append(count)
        for section, count in enumerate(counts):
            index = 0
            for _ in range(count):
                delta, offset = leb(offset)
                index += delta
                _, offset = leb(offset)  # access flags
                if section >= 2:
                    _, offset = leb(offset)  # code offset, zero for native/abstract methods
                kind, table = ("fields", fields) if section < 2 else ("methods", methods)
                declared_owner, name, descriptor = table[index]
                assert declared_owner == owner, "DEX member owner mismatch"
                members[kind].add((name, descriptor))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, default=ROOT / "androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk")
    parser.add_argument("--mapping", type=Path, default=ROOT / "androidApp/build/outputs/mapping/release/mapping.txt")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    expected, inputs = {}, []
    for filename in AARS:
        path = ROOT / "platform/android/libs" / filename
        with zipfile.ZipFile(path) as aar, zipfile.ZipFile(io.BytesIO(aar.read("classes.jar"))) as jar:
            for entry in jar.namelist():
                if entry.endswith(".class"):
                    name, members = class_abi(jar.read(entry))
                    expected[name] = members
        inputs.append({"file": str(path.relative_to(ROOT)), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    # Native convertToTensorInfo resolves this exact constructor; lock the regression trigger.
    assert ("<init>", "([J[Ljava/lang/String;I)V") in expected["Lai/onnxruntime/TensorInfo;"]["methods"]
    actual = {}
    with zipfile.ZipFile(args.apk) as apk:
        dex_names = [n for n in apk.namelist() if re.fullmatch(r"classes(?:\d+)?\.dex", n)]
        assert dex_names, "APK contains no DEX"
        for name in dex_names:
            actual.update(dex_abi(apk.read(name)))
    missing = []
    counts = {"classes": len(expected), "methods": 0, "fields": 0}
    for name, members in sorted(expected.items()):
        if name not in actual:
            missing.append(name)
        for kind in ("fields", "methods"):
            counts[kind] += len(members[kind])
            for member, descriptor in sorted(members[kind] - actual.get(name, {}).get(kind, set())):
                missing.append(f"{name}->{member}:{descriptor}")
    mapping = args.mapping.read_text()
    app_renamed = any(old != new and "L" + new.replace(".", "/") + ";" in actual
                      and "L" + old.replace(".", "/") + ";" not in actual
                      for old, new in re.findall(r"^(com\.dawnyew\.[^ ]+) -> ([^:]+):$", mapping, re.M))
    if not app_renamed:
        missing.append("No R8 class-renaming evidence for com.dawnyew (release optimization must stay enabled)")
    try:
        report_apk = args.apk.resolve().relative_to(ROOT).as_posix()
    except ValueError:
        report_apk = args.apk.name
    report = {"apk": report_apk, "apk_sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
              "mapping_sha256": hashlib.sha256(args.mapping.read_bytes()).hexdigest(),
              "aar_inputs": inputs, "dex_files": dex_names, "required_definitions": counts,
              "app_obfuscation_active": app_renamed, "missing_definitions": missing, "passed": not missing,
              "scope": "Static JNI ABI retention only; real Android inference remains a separate device gate."}
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n")
    if missing:
        raise AssertionError(f"{len(missing)} missing/invalid JNI ABI definitions:\n" + "\n".join(missing[:20]))
    print(f"Release JNI ABI passed: {counts}; {len(dex_names)} DEX files; app obfuscation active.")
    print(f"APK SHA-256: {report['apk_sha256']}")


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, OSError, KeyError, ValueError, zipfile.BadZipFile, struct.error) as error:
        print(f"Release OCR JNI verification failed: {error}", file=sys.stderr)
        sys.exit(1)
