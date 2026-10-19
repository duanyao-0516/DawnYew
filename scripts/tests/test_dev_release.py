"""Exercise release command dispatch without builds, devices, or signing keys."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


DEV_SCRIPT = Path(__file__).resolve().parents[2] / "dev.sh"


class DevReleaseTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="dawnyew-dev-release-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.events = self.root / "events.txt"
        self.gradle_args = self.root / "gradle-args.txt"
        (self.root / "scripts").mkdir()
        (self.root / "bin").mkdir()
        shutil.copyfile(DEV_SCRIPT, self.root / "dev.sh")
        self.write_script("scripts/project-env.sh", """
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
""")
        self.write_script("scripts/project-gradle.sh", """
printf 'gradle\\n' >> "$DAWNYEW_TEST_EVENTS"
printf '%s\\n' "$@" > "$DAWNYEW_TEST_GRADLE_ARGS"
exit "${DAWNYEW_TEST_GRADLE_EXIT:-0}"
""")
        self.write_script("bin/python3", """
printf '%s\\n' "$2" >> "$DAWNYEW_TEST_EVENTS"
""")

    def write_script(self, name, body):
        script = self.root / name
        script.write_text("#!/usr/bin/env bash\nset -euo pipefail\n" + body)
        script.chmod(0o755)

    def run_release(self, *arguments, gradle_exit=0):
        self.events.write_text("")
        self.gradle_args.write_text("")
        environment = dict(os.environ, PATH=f"{self.root / 'bin'}:{os.environ['PATH']}",
                           DAWNYEW_TEST_EVENTS=str(self.events),
                           DAWNYEW_TEST_GRADLE_ARGS=str(self.gradle_args),
                           DAWNYEW_TEST_GRADLE_EXIT=str(gradle_exit))
        result = subprocess.run(["bash", str(self.root / "dev.sh"), "release", *arguments],
                                cwd=self.root.parent, env=environment,
                                capture_output=True, text=True, timeout=10)
        return result, self.events.read_text().splitlines()

    def test_normal_build_checks_before_gradle_and_packages_after_success(self):
        arguments = ("--offline", "--console=plain", "-PdawnyewAbis=arm64-v8a")
        result, events = self.run_release(*arguments)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(events, ["check", "gradle", "package"])
        self.assertEqual(self.gradle_args.read_text().splitlines(), [
            ":platform:android:verifyReleaseOcrJni", ":androidApp:lintRelease", *arguments,
        ])

    def test_failed_gradle_never_packages(self):
        result, events = self.run_release(gradle_exit=17)
        self.assertEqual(result.returncode, 17, result.stderr)
        self.assertEqual(events, ["check", "gradle"])

    def test_nonexecuting_options_never_package_an_existing_apk(self):
        for argument in ("--help", "-h", "-?", "--version", "-v", "--dry-run", "-m",
                         "--task-graph", "--status", "--stop"):
            with self.subTest(argument=argument):
                result, events = self.run_release(argument)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertNotIn("package", events)

    def test_project_overrides_and_gate_exclusions_are_rejected(self):
        options = (("-p", "--project-dir", "other-project"),
                   ("-b", "--build-file", "other.gradle"),
                   ("-c", "--settings-file", "other-settings.gradle"),
                   ("-x", "--exclude-task", ":androidApp:lintRelease"))
        for short, long, value in options:
            for arguments in ((short, value), (short + value,), (long, value), (long + "=" + value,)):
                with self.subTest(arguments=arguments):
                    result, events = self.run_release(*arguments)
                    self.assertNotEqual(result.returncode, 0, result.stdout)
                    self.assertNotIn("package", events)

    def test_show_version_options_still_build_and_package(self):
        for argument in ("--show-version", "-V"):
            with self.subTest(argument=argument):
                result, events = self.run_release(argument)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(events, ["check", "gradle", "package"])


if __name__ == "__main__":
    unittest.main()
