#!/usr/bin/env python3
"""Manage a local release identity and sign the APK after Gradle gates pass."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import stat
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ALIAS = "dawnyew-release"


def check_signing(root: Path) -> tuple[Path, Path]:
    directory = root / ".local/signing/release"
    key, password = directory / "release.keystore", directory / "password.txt"
    if not directory.is_dir() or not all(p.is_file() for p in (key, password)):
        raise RuntimeError("Release 签名材料缺失。首次使用请运行 ./dev.sh init-release-key；"
                           "已有发布密钥时请从备份恢复，勿创建新密钥。")
    for path, mode in ((directory, 0o700), (key, 0o600), (password, 0o600)):
        if path.is_symlink() or stat.S_IMODE(path.stat().st_mode) != mode:
            raise RuntimeError(f"签名路径必须为普通目录/文件且权限为 {mode:o}: {path}")
    if any(p.stat().st_size == 0 for p in (key, password)):
        raise RuntimeError("Release 签名文件为空，请从备份恢复；不会自动生成替代密钥。")
    return key, password


def init_signing(root: Path, keytool: Path) -> None:
    directory = root / ".local/signing/release"
    if directory.exists():
        check_signing(root)
        print("已保留现有 Release 签名密钥。")
        return
    # An interrupted initialization remains visibly incomplete; never replace an identity.
    directory.mkdir(parents=True, mode=0o700)
    directory.chmod(0o700)
    password = directory / "password.txt"
    with os.fdopen(os.open(password, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
        stream.write(secrets.token_urlsafe(48) + "\n")
    key = directory / "release.keystore"
    subprocess.run([
        str(keytool), "-genkeypair", "-storetype", "PKCS12", "-keystore", str(key),
        "-storepass:file", str(password), "-keypass:file", str(password),
        "-alias", ALIAS, "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
        "-dname", "CN=DawnYew", "-noprompt",
    ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    key.chmod(0o600)
    print(f"已创建 Release 签名密钥，请私密备份整个目录: {directory}")


def release_input(root: Path) -> tuple[Path, str]:
    directory = root / "androidApp/build/outputs/apk/release"
    metadata = json.loads((directory / "output-metadata.json").read_text())
    elements = metadata.get("elements", [])
    if metadata.get("variantName") != "release" or len(elements) != 1:
        raise RuntimeError("需要单一 Release APK；不接受 Debug 或多个拆分 APK。")
    element = elements[0]
    version, filename = element.get("versionName", ""), element.get("outputFile", "")
    if not isinstance(version, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]*", version):
        raise RuntimeError("Release versionName 为空或含不安全的文件名字符。")
    if not isinstance(filename, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]*\.apk", filename):
        raise RuntimeError("Release outputFile 必须是构建目录内的 APK 文件名。")
    apk = directory / filename
    with zipfile.ZipFile(apk) as archive:
        abis = sorted({name.split("/")[1] for name in archive.namelist()
                       if re.fullmatch(r"lib/[A-Za-z0-9_-]+/[^/]+\.so", name)})
    return apk, f"DawnYew-v{version}-{'+'.join(abis) if abis else 'universal'}.apk"


def android_tools() -> tuple[Path, Path]:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise RuntimeError("请设置 ANDROID_HOME 为 Android SDK 路径。")
    directory = Path(sdk) / "build-tools"
    candidates = []
    if directory.is_dir():
        for path in directory.iterdir():
            if re.fullmatch(r"\d+\.\d+\.\d+", path.name):
                version = tuple(map(int, path.name.split(".")))
                if version >= (35, 0, 0) and all(os.access(path / tool, os.X_OK)
                                               for tool in ("apksigner", "zipalign")):
                    candidates.append((version, path))
    if not candidates:
        raise RuntimeError("需要 Android SDK Build Tools 35.0.0 或更新稳定版（apksigner/zipalign）。")
    selected = max(candidates)[1]
    return selected / "apksigner", selected / "zipalign"


def require_ignored(root: Path) -> None:
    for relative in (".local/signing/release/release.keystore", ".local/signing/release/password.txt",
                     "artifacts/releases/release.apk", "artifacts/releases/release.apk.sha256"):
        result = subprocess.run(["git", "check-ignore", "-q", relative], cwd=root,
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if result.returncode != 0:
            raise RuntimeError(f"拒绝操作：路径未被 Git 忽略或已被跟踪: {relative}")


def publish_release(signed: Path, checksum: Path, destination: Path) -> None:
    """Replace the APK last; restore the old checksum if that final step fails."""
    target_checksum = destination / checksum.name
    backup = checksum.with_suffix(".previous")
    if target_checksum.exists():
        shutil.copyfile(target_checksum, backup)
    checksum.replace(target_checksum)
    try:
        signed.replace(destination / signed.name)
    except OSError:
        if backup.exists():
            backup.replace(target_checksum)
        else:
            target_checksum.unlink()
        raise


def package_release(root: Path, apksigner: Path, zipalign: Path) -> None:
    key, password = check_signing(root)
    apk, filename = release_input(root)
    destination = root / "artifacts/releases"
    destination.mkdir(parents=True, exist_ok=True)
    # Keep an existing published local artifact intact if signing or verification fails.
    with (destination / ".release.lock").open("a") as lock, \
            tempfile.TemporaryDirectory(prefix=".signing-", dir=destination) as temporary:
        fcntl.flock(lock, fcntl.LOCK_EX)
        temporary = Path(temporary)
        aligned, signed = temporary / "aligned.apk", temporary / filename
        subprocess.run([str(zipalign), "-P", "16", "-f", "4", str(apk), str(aligned)], check=True)
        subprocess.run([
            str(apksigner), "sign", "--ks", str(key), "--ks-key-alias", ALIAS,
            # PKCS12 uses the same store/key password. A second file: read consumes
            # another line; let apksigner reuse the store password instead.
            "--ks-pass", f"file:{password}",
            "--v4-signing-enabled", "false", "--out", str(signed), str(aligned),
        ], check=True, stdin=subprocess.DEVNULL)
        subprocess.run([str(apksigner), "verify", "--verbose", "--print-certs", str(signed)], check=True)
        subprocess.run([str(zipalign), "-c", "-P", "16", "4", str(signed)], check=True)
        digest = hashlib.sha256(signed.read_bytes()).hexdigest()
        checksum = temporary / (filename + ".sha256")
        checksum.write_text(f"{digest}  {filename}\n")
        publish_release(signed, checksum, destination)
    print(f"\n已签名 Release APK: {destination / filename}")
    print(f"SHA-256: {digest}")
    print(f"校验文件: {destination / checksum.name}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("init", "check", "package"))
    args = parser.parse_args()
    require_ignored(ROOT)
    java_home = os.environ.get("JAVA_HOME")
    if not java_home or not (keytool := Path(java_home) / "bin/keytool").is_file():
        raise RuntimeError("需要 JDK keytool，请通过 ./dev.sh 调用。")
    if args.command == "init":
        init_signing(ROOT, keytool)
    key, password = check_signing(ROOT)
    # Validate the password and alias before spending time on the Gradle build.
    subprocess.run([str(keytool), "-list", "-keystore", str(key), "-storepass:file", str(password),
                    "-alias", ALIAS], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    if args.command != "init":
        apksigner, zipalign = android_tools()
        if args.command == "package":
            package_release(ROOT, apksigner, zipalign)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        print(f"Release 签名失败: {error}", file=sys.stderr)
        sys.exit(1)
