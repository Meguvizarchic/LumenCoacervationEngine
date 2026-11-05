import importlib.util
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parent / "engine_device_tests.py"
SPEC = importlib.util.spec_from_file_location("engine_device_tests", SCRIPT)
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)


class EngineDeviceTestsTest(unittest.TestCase):
    def test_only_a_complete_successful_run_passes(self):
        ok = "INSTRUMENTATION_STATUS_CODE: 0\n\nOK (2 tests)\n\n\nINSTRUMENTATION_CODE: -1\n"
        self.assertEqual(("PASSED", 2), RUNNER.classify(ok, False))
        self.assertEqual("TIMEOUT", RUNNER.classify(ok, True)[0])
        failed = "FAILURES!!!\nTests run: 1,  Failures: 1\n\nINSTRUMENTATION_CODE: -1\n"
        self.assertEqual("FAILED", RUNNER.classify(failed, False)[0])
        # adb exit 0 with a crashed or truncated runner is not a pass.
        self.assertEqual("INCOMPLETE", RUNNER.classify("INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n", False)[0])
        self.assertEqual("INCOMPLETE", RUNNER.classify("OK (1 test)\n", False)[0])
        self.assertEqual("INCOMPLETE", RUNNER.classify(ok + "INSTRUMENTATION_CODE: -1\n", False)[0])
        self.assertEqual("INCOMPLETE", RUNNER.classify("OK (0 tests)\nINSTRUMENTATION_CODE: -1\n", False)[0])

    def test_discovery_reads_run_with_classes_only(self):
        with tempfile.TemporaryDirectory() as root:
            folder = Path(root)
            (folder / "A.kt").write_text("@RunWith(AndroidJUnit4::class)\nclass ATest {\n}", encoding="utf-8")
            (folder / "B.kt").write_text("@SdkSuppress(minSdkVersion = 31)\n@RunWith(AndroidJUnit4::class)\n@LargeTest\nclass BTest", encoding="utf-8")
            (folder / "Helper.kt").write_text("internal object Helper", encoding="utf-8")
            self.assertEqual(["ATest", "BTest"], RUNNER.discover(folder))

    def test_discovery_covers_the_real_package(self):
        names = RUNNER.discover()
        self.assertIn("ViewStepInstrumentedTest", names)
        self.assertIn("LiquidCustomBackdropMemoryInstrumentedTest", names)
        self.assertEqual(len(names), len(set(names)))


if __name__ == "__main__":
    unittest.main()
