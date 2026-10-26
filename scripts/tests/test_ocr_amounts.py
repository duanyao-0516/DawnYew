import importlib.util
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "test-ocr-amounts.py"
spec = importlib.util.spec_from_file_location("ocr_amount_test", SCRIPT)
ocr = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ocr)


class ScreenshotLabelsTest(unittest.TestCase):
    def test_explicit_positive_sign_and_unsigned_amount_have_different_directions(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("+12.34.png", "-12.34.png", "12.34.png"):
                (root / name).write_bytes(b"image")
            samples = {s["source"].name: s for s in ocr.collect_samples(root)}
            self.assertEqual(1234, samples["+12.34.png"]["expectedSignedMinor"])
            self.assertEqual(1234, samples["12.34.png"]["expectedSignedMinor"])
            self.assertEqual("INCOME", samples["+12.34.png"]["expectedDirection"])
            self.assertEqual("EXPENSE", samples["12.34.png"]["expectedDirection"])
            self.assertEqual("EXPENSE", samples["-12.34.png"]["expectedDirection"])

    def test_signed_decimal_labels_are_exact_minor_units_and_inputs_are_anonymous(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "-1.98.jpeg").write_bytes(b"first")
            (root / "-35.50.jpeg").write_bytes(b"second")
            (root / "0.29.png").write_bytes(b"third")
            samples = ocr.collect_samples(root)
            self.assertEqual([-198, -3550, 29], [s["expectedSignedMinor"] for s in samples])
            self.assertEqual(["case-001", "case-002", "case-003"], [s["id"] for s in samples])
            self.assertNotEqual(samples[0]["sha256"], samples[1]["sha256"])

    def test_invalid_or_ambiguous_labels_are_not_silently_coerced(self):
        for name in ("1.989.png", "01.98.png", "price-1.98.png", "NaN.png", "1.png", "1e2.png", "92233720368547758.08.png"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                (Path(directory) / name).write_bytes(b"image")
                with self.assertRaises(ValueError):
                    ocr.collect_samples(Path(directory))

    def test_empty_corpus_and_empty_image_are_errors(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(ValueError):
                ocr.collect_samples(root)
            (root / "1.98.jpeg").touch()
            with self.assertRaises(ValueError):
                ocr.collect_samples(root)


if __name__ == "__main__":
    unittest.main()
