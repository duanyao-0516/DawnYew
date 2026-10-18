#!/usr/bin/env python3
"""Verify bundled OCR assets and the actual ELF files inside the native AARs."""
import hashlib
import io
import pathlib
import struct
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
PLATFORM = ROOT / "platform/android"
MODELS = {
    "det/inference.onnx": "a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d",
    "det/inference.yml": "98069072e1b6b37d727fd9d9f11725faa46d6ea0de012f2ed26caea011c37699",
    "rec/inference.onnx": "da72dc72ca4dc220df0dfde68c1dedc31c58d3e76a25871122e5056227d50092",
    "rec/inference.yml": "5dfeb2777f6d0db8177d8128a8acfcf6e6276dc4ac73ea3bf0dc06d6a5e85d8e",
}


def verify_elf(data, name):
    assert data[:6] == b"\x7fELF\x02\x01", f"{name}: expected little-endian ELF64"
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phentsize, phnum = struct.unpack_from("<HH", data, 54)
    loads = []
    for i in range(phnum):
        offset = phoff + i * phentsize
        if struct.unpack_from("<I", data, offset)[0] == 1:
            alignment = struct.unpack_from("<Q", data, offset + 48)[0]
            assert alignment >= 16384, f"{name}: PT_LOAD alignment {alignment} is below 16 KiB"
            loads.append(alignment)
    assert loads, f"{name}: no PT_LOAD entries"
    return loads


def main():
    for filename, expected in MODELS.items():
        path = PLATFORM / "src/main/assets/ocr" / filename
        assert path.is_file(), f"missing bundled model/config: {path}"
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        assert actual == expected, f"asset digest mismatch: {filename}"
        print(f"asset verified: {filename}")
    for filename in ("onnxruntime-no-telemetry-1.31.0.aar", "opencv-4.14.0.aar"):
        path = PLATFORM / "libs" / filename
        assert path.is_file(), f"missing native AAR: {path}"
        with zipfile.ZipFile(path) as archive:
            assert b"android.permission.INTERNET" not in archive.read("AndroidManifest.xml"), filename
            libs = [n for n in archive.namelist() if n.startswith("jni/") and n.endswith(".so")]
            assert any(n.startswith("jni/arm64-v8a/") for n in libs), f"{filename}: no arm64 native libraries"
            for name in libs:
                data = archive.read(name)
                alignments = verify_elf(data, name)
                if filename.startswith("onnxruntime"):
                    for forbidden in (b"Microsoft::Applications::Events", b"events.data.microsoft.com", b"OneDsTelemetry", b"cpp_client_telemetry"):
                        assert forbidden not in data, f"{name}: telemetry marker {forbidden!r}"
                print(f"ELF verified: {name}; PT_LOAD={alignments}")
            if filename.startswith("onnxruntime"):
                with zipfile.ZipFile(io.BytesIO(archive.read("classes.jar"))) as classes:
                    assert not any("telemetry" in n.lower() or "microsoft/applications" in n.lower() for n in classes.namelist()), "telemetry Java classes found"
        print(f"AAR SHA-256: {filename} {hashlib.sha256(path.read_bytes()).hexdigest()}")
    print("OCR asset and native artifact checks passed; device/runtime acceptance is separate.")


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, OSError, zipfile.BadZipFile) as error:
        print(f"OCR verification failed: {error}", file=sys.stderr)
        sys.exit(1)
