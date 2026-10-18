#!/usr/bin/env python3
"""Build the pinned CPU-only Android OCR libraries from auditable upstream sources.

Requires Python >= 3.12, JDK 17, CMake >= 3.28, Ninja, Android SDK and NDK r28c.
All downloaded sources and generated intermediates stay in .local/ocr-work.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / ".local/ocr-work"
LIBS = ROOT / "platform/android/libs"
PINS = {
    "onnxruntime-1.31.0": (
        "https://codeload.github.com/microsoft/onnxruntime/tar.gz/refs/tags/v1.31.0",
        "a7e84230b5d509e48fbd6b73c2453a56345ccb22da34a46dac1f0cb4a2d6507b",
    ),
    "opencv-4.14.0": (
        "https://codeload.github.com/opencv/opencv/tar.gz/refs/tags/4.14.0",
        "ee8fb9b30eb60850431b4656447080e3737b56e45719c92b67f245950609f86e",
    ),
}


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def source(name: str) -> Path:
    url, expected = PINS[name]
    archive = WORK / "downloads" / f"{name}.tar.gz"
    archive.parent.mkdir(parents=True, exist_ok=True)
    if not archive.exists():
        temporary = archive.with_suffix(".partial")
        with urllib.request.urlopen(url, timeout=120) as response, temporary.open("wb") as out:
            shutil.copyfileobj(response, out)
        temporary.replace(archive)
    if digest(archive) != expected:
        raise RuntimeError(f"Source digest mismatch: {archive}")
    directory = WORK / "sources" / name
    if not directory.exists():
        directory.parent.mkdir(parents=True, exist_ok=True)
        with tarfile.open(archive) as tar:
            tar.extractall(directory.parent, filter="data")
    return directory


def run(command, cwd=None, *, label: str, env: dict):
    log = WORK / "logs" / f"{label}.log"
    log.parent.mkdir(parents=True, exist_ok=True)
    print(f"{label}: running; log {log}", flush=True)
    (log.with_suffix(".command.json")).write_text(json.dumps([str(a) for a in command], indent=2))
    with log.open("w") as output:
        result = subprocess.run([str(a) for a in command], cwd=cwd, env=env, stdout=output, stderr=subprocess.STDOUT)
    if result.returncode:
        print("\n".join(log.read_text(errors="replace").splitlines()[-65:]), file=sys.stderr)
        raise RuntimeError(f"{label} failed with exit code {result.returncode}")
    print(f"{label}: passed", flush=True)


def aar(path: Path, package: str, jar: Path, libraries: dict[str, Path], licenses: dict[str, Path]):
    path.parent.mkdir(parents=True, exist_ok=True)
    entries = {
        "AndroidManifest.xml": f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}"><uses-sdk android:minSdkVersion="30"/></manifest>'.encode(),
        "classes.jar": jar.read_bytes(),
        "proguard.txt": ("-keep class ai.onnxruntime.** { *; }\n" if "onnxruntime" in package else "-keep class org.opencv.** { *; }\n").encode(),
    }
    entries.update({name: file.read_bytes() for name, file in libraries.items()})
    entries.update({f"assets/ocr/licenses/{name}": file.read_bytes() for name, file in licenses.items()})
    preserve_other_abis(path, entries)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as out:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, date_time=(2026, 10, 18, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            out.writestr(info, data)
    print(f"Packaged {path}; SHA-256={digest(path)}", flush=True)


def preserve_other_abis(path: Path, entries: dict):
    """A development x86_64 build must not remove the production arm64 binaries."""
    incoming_abis = {name.split('/')[1] for name in entries if name.startswith('jni/')}
    if path.exists():
        with zipfile.ZipFile(path) as previous:
            for name in previous.namelist():
                if name.startswith('jni/') and name.split('/')[1] not in incoming_abis:
                    entries[name] = previous.read(name)


def build_ort(args, env, cmake, ninja, ndk, sdk):
    src = source("onnxruntime-1.31.0")
    build = WORK / "build" / f"ort-{args.abi}"
    command = [sys.executable, src / "tools/ci_build/build.py",
               "--build_dir", build, "--config", "MinSizeRel", "--update", "--build",
               "--android", "--android_abi", args.abi, "--android_api", "30",
               "--android_sdk_path", sdk, "--android_ndk_path", ndk,
               "--build_shared_lib", "--build_java", "--no_telemetry", "--skip_tests", "--compile_no_warning_as_error",
               "--skip_submodule_sync", "--parallel", str(args.jobs),
               "--cmake_path", cmake, "--cmake_generator", "Ninja",
               "--cmake_extra_defines", f"CMAKE_MAKE_PROGRAM={ninja}",
               "CMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
               "onnxruntime_BUILD_UNIT_TESTS=OFF", "onnxruntime_USE_NNAPI_BUILTIN=OFF",
               "onnxruntime_ENABLE_PYTHON=OFF", "onnxruntime_USE_TELEMETRY=OFF"]
    run(command, cwd=src, label=f"ort-{args.abi}", env=env)
    cache = (build / "MinSizeRel/CMakeCache.txt").read_text()
    if "onnxruntime_USE_TELEMETRY:BOOL=OFF" not in cache:
        raise RuntimeError("ORT build cache does not explicitly disable telemetry")
    candidates = list((build / "MinSizeRel/java/build/android/outputs/aar").glob("*-release.aar"))
    if len(candidates) != 1:
        raise RuntimeError(f"Expected exactly one ORT release AAR, got {candidates}")
    target = LIBS / "onnxruntime-no-telemetry-1.31.0.aar"
    LIBS.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(candidates[0]) as incoming:
        entries = {name: incoming.read(name) for name in incoming.namelist() if not name.endswith('/')}
    preserve_other_abis(target, entries)
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, date_time=(2026, 10, 18, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)
    print(f"ORT AAR SHA-256={digest(target)}", flush=True)


def build_opencv(args, env, cmake, ninja, ndk, sdk):
    src = source("opencv-4.14.0")
    build = WORK / "build" / f"opencv-{args.abi}"
    command = [cmake, "-S", src, "-B", build, "-G", "Ninja",
               f"-DCMAKE_MAKE_PROGRAM={ninja}",
               f"-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake",
               f"-DANDROID_ABI={args.abi}", "-DANDROID_PLATFORM=android-30",
               "-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=MinSizeRel",
               "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
               "-DBUILD_LIST=core,imgproc,imgcodecs,java", "-DBUILD_SHARED_LIBS=OFF",
               "-DBUILD_opencv_java=ON", "-DBUILD_ANDROID_PROJECTS=OFF", "-DANDROID_PROJECTS_BUILD_TYPE=GRADLE",
               "-DBUILD_TESTS=OFF", "-DBUILD_PERF_TESTS=OFF", "-DBUILD_EXAMPLES=OFF",
               "-DBUILD_DOCS=OFF", "-DBUILD_JAVA=ON", "-DWITH_OPENCL=OFF",
               "-DWITH_IPP=OFF", "-DWITH_ITT=OFF", "-DWITH_PROTOBUF=OFF",
               "-DWITH_FFMPEG=OFF", "-DWITH_GSTREAMER=OFF", "-DWITH_TIFF=OFF",
               "-DWITH_OPENEXR=OFF", "-DWITH_WEBP=OFF", "-DWITH_JASPER=OFF",
               "-DWITH_OPENJPEG=OFF", "-DWITH_AVIF=OFF", "-DBUILD_ANDROID_EXAMPLES=OFF",
               f"-DPYTHON_DEFAULT_EXECUTABLE={sys.executable}", f"-DANDROID_SDK={sdk}"]
    run(command, label=f"opencv-configure-{args.abi}", env=env)
    run([cmake, "--build", build, "--target", "opencv_java", "--parallel", str(args.jobs)],
        label=f"opencv-build-{args.abi}", env=env)
    generated = build / "modules/java_bindings_generator/gen/java"
    java_files = [p for p in generated.rglob("*.java") if "/osgi/" not in str(p)]
    java_files.append(src / "modules/java/generator/android/java/org/opencv/android/Utils.java")
    if not java_files or not generated.exists():
        raise RuntimeError("OpenCV Java bindings were not generated")
    classes = build / "dawnyew-classes"
    classes.mkdir(exist_ok=True)
    javac = Path(env["JAVA_HOME"]) / "bin/javac"
    jar = Path(env["JAVA_HOME"]) / "bin/jar"
    run([javac, "-source", "8", "-target", "8", "-cp", sdk / "platforms/android-35/android.jar",
         "-d", classes, *java_files], label=f"opencv-javac-{args.abi}", env=env)
    output_jar = build / "dawnyew-opencv.jar"
    run([jar, "cf", output_jar, "-C", classes, "."], label=f"opencv-jar-{args.abi}", env=env)
    libraries = list(build.rglob("libopencv_java4.so"))
    if len(libraries) != 1:
        raise RuntimeError(f"Expected exactly one OpenCV JNI library, got {libraries}")
    aar(LIBS / "opencv-4.14.0.aar", "org.opencv", output_jar,
        {f"jni/{args.abi}/libopencv_java4.so": libraries[0]}, {"OpenCV-LICENSE": src / "LICENSE"})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--component", choices=("ort", "opencv", "all"), default="all")
    parser.add_argument("--abi", choices=("arm64-v8a", "x86_64"), default="arm64-v8a")
    parser.add_argument("--jobs", type=int, default=4)
    args = parser.parse_args()
    if sys.version_info < (3, 12):
        raise RuntimeError("Use Python >= 3.12 for safe archive extraction and the ORT build driver")
    env = os.environ.copy()
    ndk = Path(env.get("OCR_NDK_PATH", ROOT / ".local/android-ndk-r28c")).resolve()
    sdk = Path(env.get("ANDROID_HOME", Path.home() / "Library/Android/sdk")).resolve()
    cmake = env.get("OCR_CMAKE_BIN") or shutil.which("cmake")
    ninja = env.get("OCR_NINJA_BIN") or shutil.which("ninja")
    if not cmake or not ninja or not (ndk / "source.properties").exists():
        raise RuntimeError("Set OCR_CMAKE_BIN, OCR_NINJA_BIN and install NDK r28c in .local/android-ndk-r28c")
    if "28.2.13676358" not in (ndk / "source.properties").read_text():
        raise RuntimeError("Expected the pinned NDK r28c (28.2.13676358)")
    env["JAVA_HOME"] = env.get("JAVA_HOME") or subprocess.check_output(["/usr/libexec/java_home", "-v", "17"], text=True).strip()
    env["ANDROID_HOME"] = str(sdk)
    env["GRADLE_USER_HOME"] = str(WORK / "gradle-user-home")
    env["PATH"] = str(Path(ninja).parent) + os.pathsep + env.get("PATH", "")
    env["ORT_DISABLE_TELEMETRY"] = "1"
    if args.component in ("ort", "all"):
        build_ort(args, env, cmake, ninja, ndk, sdk)
    if args.component in ("opencv", "all"):
        build_opencv(args, env, cmake, ninja, ndk, sdk)


if __name__ == "__main__":
    main()
