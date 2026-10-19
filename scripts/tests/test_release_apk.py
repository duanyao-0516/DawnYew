"""Release packaging behavior; all signing identities live in temporary roots."""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile


SCRIPT = Path(__file__).resolve().parents[1] / "release-apk.py"
JAVA_KEYTOOL = Path(os.environ.get("JAVA_HOME", "/nonexistent")) / "bin/keytool"
KEYTOOL = JAVA_KEYTOOL if JAVA_KEYTOOL.is_file() else shutil.which("keytool")


class ReleaseApkTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue(SCRIPT.is_file(), "Release signing/packaging implementation is missing")
        spec = importlib.util.spec_from_file_location("release_apk", SCRIPT)
        self.release = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.release)
        temporary = tempfile.TemporaryDirectory(prefix="dawnyew-release-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.signing = self.root / ".local/signing/release"
        self.apk_dir = self.root / "androidApp/build/outputs/apk/release"

    def signing_fixture(self):
        self.signing.mkdir(parents=True, mode=0o700)
        files = self.signing / "release.keystore", self.signing / "password.txt"
        for file in files:
            file.write_bytes(b"temporary-fixture")
            file.chmod(0o600)
        return files

    def apk_fixture(self, abis=("arm64-v8a",), version="1.2.3-rc.1"):
        self.apk_dir.mkdir(parents=True, exist_ok=True)
        apk = self.apk_dir / "androidApp-release-unsigned.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("classes.dex", b"fixture")
            for abi in abis:
                archive.writestr(f"lib/{abi}/libfixture.so", b"fixture")
        metadata = {"variantName": "release", "elements": [
            {"outputFile": apk.name, "versionName": version, "versionCode": 9}
        ]}
        self.write_metadata(metadata)
        return apk, metadata

    def write_metadata(self, metadata):
        (self.apk_dir / "output-metadata.json").write_text(json.dumps(metadata))

    @unittest.skipUnless(KEYTOOL, "A JDK keytool is required")
    def test_real_key_is_private_valid_and_reused_without_change(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            self.release.init_signing(self.root, Path(KEYTOOL))
        key, password_file = self.release.check_signing(self.root)
        self.assertEqual((key, password_file), (self.signing / "release.keystore", self.signing / "password.txt"))
        password = password_file.read_text().strip()
        self.assertGreaterEqual(len(password), 16)
        self.assertFalse(password in output.getvalue(), "Initialization exposed the password")
        self.assertEqual(stat.S_IMODE(self.signing.stat().st_mode), 0o700)
        self.assertTrue(all(stat.S_IMODE(p.stat().st_mode) == 0o600 for p in (key, password_file)))
        original = [(p.read_bytes(), p.stat().st_mtime_ns) for p in (key, password_file)]
        self.release.init_signing(self.root, Path(KEYTOOL))
        self.assertTrue(original == [(p.read_bytes(), p.stat().st_mtime_ns) for p in (key, password_file)])
        result = subprocess.run([str(KEYTOOL), "-list", "-keystore", str(key), "-storetype", "PKCS12",
                                 "-storepass:file", str(password_file), "-alias", "dawnyew-release"],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, "Generated keystore/alias could not be opened")

    def test_partial_signing_identity_is_never_regenerated(self):
        key, password = self.signing_fixture()
        for missing, remaining in ((key, password), (password, key)):
            with self.subTest(missing=missing.name):
                missing.unlink()
                original = remaining.read_bytes()
                with self.assertRaises(RuntimeError):
                    self.release.init_signing(self.root, Path(KEYTOOL or "keytool"))
                self.assertFalse(missing.exists())
                self.assertTrue(original == remaining.read_bytes())
                missing.write_bytes(b"temporary-fixture")
                missing.chmod(0o600)

    def test_check_rejects_missing_empty_and_overexposed_secrets(self):
        with self.assertRaises(RuntimeError):
            self.release.check_signing(self.root)
        files = self.signing_fixture()
        for target in (*files, self.signing):
            with self.subTest(exposed=target.name):
                original_mode = stat.S_IMODE(target.stat().st_mode)
                target.chmod(0o755 if target.is_dir() else 0o644)
                with self.assertRaises(RuntimeError):
                    self.release.check_signing(self.root)
                target.chmod(original_mode)
        for target in files:
            with self.subTest(empty=target.name):
                original = target.read_bytes()
                target.write_bytes(b"")
                with self.assertRaises(RuntimeError):
                    self.release.check_signing(self.root)
                target.write_bytes(original)

    def test_name_uses_metadata_version_and_actual_sorted_native_abis(self):
        apk, _ = self.apk_fixture(("x86_64", "arm64-v8a"))
        self.assertEqual(self.release.release_input(self.root),
                         (apk, "DawnYew-v1.2.3-rc.1-arm64-v8a+x86_64.apk"))

    def test_no_native_libraries_produce_universal_name(self):
        apk, _ = self.apk_fixture((), "2.0.0")
        self.assertEqual(self.release.release_input(self.root), (apk, "DawnYew-v2.0.0-universal.apk"))

    @unittest.skipUnless(KEYTOOL, "A JDK keytool is required")
    def test_real_release_apk_is_signed_with_temporary_identity_and_matching_checksum(self):
        built_apk = SCRIPT.parents[1] / "androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk"
        if not built_apk.is_file():
            self.skipTest("Build :androidApp:assembleRelease before running APK signing integration")
        try:
            apksigner, _ = self.release.android_tools()
        except RuntimeError as error:
            self.skipTest(f"Android SDK signing tools unavailable: {error}")
        apk, _ = self.apk_fixture(version="9.1.0-test")
        shutil.copyfile(built_apk, apk)
        with contextlib.redirect_stdout(io.StringIO()):
            self.release.init_signing(self.root, Path(KEYTOOL))
        # Capture native tool output too, while executing the real packaging pipeline.
        command = """import importlib.util, sys
from pathlib import Path
spec = importlib.util.spec_from_file_location('release_apk', sys.argv[1])
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
release.package_release(Path(sys.argv[2]), *release.android_tools())
"""
        result = subprocess.run([sys.executable, "-c", command, str(SCRIPT), str(self.root)],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        _, filename = self.release.release_input(self.root)
        signed = self.root / "artifacts/releases" / filename
        self.assertTrue(signed.is_file(), "Signed release artifact is missing")
        digest = hashlib.sha256(signed.read_bytes()).hexdigest()
        self.assertEqual(signed.with_suffix(".apk.sha256").read_text(), f"{digest}  {filename}\n")
        verification = subprocess.run([str(apksigner), "verify", str(signed)], capture_output=True, text=True)
        self.assertEqual(verification.returncode, 0, verification.stderr)

    def test_rejects_debug_multiple_outputs_and_unsafe_names(self):
        _, valid = self.apk_fixture()
        invalid = [dict(valid, variantName="debug"), dict(valid, elements=valid["elements"] * 2)]
        for field, values in {"outputFile": ("../outside.apk", "/tmp/outside.apk", "nested/file.apk"),
                              "versionName": ("../1.0", "1.0\nsecret", "")}.items():
            for value in values:
                invalid.append(dict(valid, elements=[dict(valid["elements"][0], **{field: value})]))
        for index, metadata in enumerate(invalid):
            with self.subTest(case=index):
                self.write_metadata(metadata)
                with self.assertRaises(RuntimeError):
                    self.release.release_input(self.root)

    def test_publish_failure_preserves_old_pair_or_leaves_no_partial_first_release(self):
        self.assertTrue(hasattr(self.release, "publish_release"), "Recoverable APK publication is missing")
        original_replace = Path.replace
        for existing in (False, True):
            for failed_name in ("DawnYew.apk.sha256", "DawnYew.apk"):
                with self.subTest(existing=existing, failed_file=failed_name):
                    case = self.root / f"{existing}-{failed_name}"
                    staging, destination = case / "staging", case / "releases"
                    staging.mkdir(parents=True)
                    destination.mkdir()
                    signed, checksum = staging / "DawnYew.apk", staging / "DawnYew.apk.sha256"
                    signed.write_bytes(b"new signed APK")
                    checksum.write_bytes(b"new checksum")
                    final_apk, final_checksum = destination / signed.name, destination / checksum.name
                    if existing:
                        final_apk.write_bytes(b"published APK")
                        final_checksum.write_bytes(b"published checksum")
                    failed = False

                    def fail_one_export(source, target):
                        nonlocal failed
                        if source == staging / failed_name and not failed:
                            failed = True
                            raise OSError("Injected export failure")
                        return original_replace(source, target)

                    with patch.object(Path, "replace", autospec=True, side_effect=fail_one_export):
                        with self.assertRaises(OSError):
                            self.release.publish_release(signed, checksum, destination)
                    self.assertTrue(failed, "The intended publication failure was not exercised")
                    if existing:
                        self.assertEqual(final_apk.read_bytes(), b"published APK")
                        self.assertEqual(final_checksum.read_bytes(), b"published checksum")
                    else:
                        self.assertFalse(final_apk.exists())
                        self.assertFalse(final_checksum.exists())


if __name__ == "__main__":
    unittest.main()
