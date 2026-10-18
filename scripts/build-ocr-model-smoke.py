#!/usr/bin/env python3
"""Run the real pinned models on generated, non-user text (host smoke, not Android acceptance).

Run with ORT_DISABLE_TELEMETRY=1 and project-local host packages:
onnxruntime 1.31.0, opencv-python-headless 4.14.0.94, PyYAML 6.0.2, numpy, Pillow.
"""
import os

# This is set before importing any native ORT module, including the environment initializer.
os.environ["ORT_DISABLE_TELEMETRY"] = "1"

from pathlib import Path
import json
import math
import cv2
import numpy as np
import onnxruntime as ort
from PIL import Image, ImageDraw, ImageFont
import yaml

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / ".local/ocr-work"
ASSETS = ROOT / "platform/android/src/main/assets/ocr"


def main():
    assert ort.__version__ == "1.31.0"
    assert cv2.__version__ == "4.14.0"
    ort.disable_telemetry_events()
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    options.log_severity_level = 3
    sessions = {kind: ort.InferenceSession(str(ASSETS / kind / "inference.onnx"), options, providers=["CPUExecutionProvider"]) for kind in ("det", "rec")}
    config = yaml.safe_load((ASSETS / "rec/inference.yml").read_text())
    characters = [""] + config["PostProcess"]["character_dict"] + [" "]
    assert len(characters) == sessions["rec"].get_outputs()[0].shape[-1] == 18385
    font_path = os.environ.get("OCR_SMOKE_FONT", "/System/Library/Fonts/STHeiti Light.ttc")
    font = ImageFont.truetype(font_path, 48)
    expected = ["支付成功", "12.34", "示例咖啡店"]
    image = Image.new("RGB", (640, 480), "white")
    draw = ImageDraw.Draw(image)
    crops = []
    for index, text in enumerate(expected):
        x, y = 30, 30 + 130 * index
        draw.text((x, y), text, font=font, fill=(8, 96, 24) if index == 0 else (24, 24, 24))
        box = draw.textbbox((x, y), text, font=font)
        crops.append((max(0, box[0] - 8), max(0, box[1] - 8), box[2] + 8, box[3] + 8))
    decoded = []
    for crop in crops:
        pixels = cv2.cvtColor(np.array(image.crop(crop)), cv2.COLOR_RGB2BGR)
        width = math.ceil(48 * pixels.shape[1] / pixels.shape[0])
        resized = cv2.resize(pixels, (width, 48)).astype(np.float32)
        tensor = ((resized / 127.5) - 1).transpose(2, 0, 1)[None, ...]
        output = sessions["rec"].run(None, {"x": tensor})[0][0]
        indices = output.argmax(axis=1)
        keep = (indices != 0) & np.r_[True, indices[1:] != indices[:-1]]
        decoded.append("".join(characters[i] for i in indices[keep]))
    assert decoded == expected, {"expected": expected, "actual": decoded}
    bgr = cv2.cvtColor(np.array(image), cv2.COLOR_RGB2BGR).astype(np.float32)
    tensor = (bgr / 255.0 - np.array([0.485, 0.456, 0.406], dtype=np.float32)) / np.array([0.229, 0.224, 0.225], dtype=np.float32)
    detection = sessions["det"].run(None, {"x": tensor.transpose(2, 0, 1)[None, ...]})[0]
    assert detection.shape == (1, 1, 480, 640)
    assert np.isfinite(detection).all() and float(detection.max()) > 0.9
    contours, _ = cv2.findContours((detection[0, 0] > 0.3).astype(np.uint8), cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    regions = [c for c in contours if cv2.contourArea(c) > 50]
    assert len(regions) >= len(expected), len(regions)
    evidence = {
        "scope": "host model loading and real CPU inference on generated non-user text; not Android or payment accuracy acceptance",
        "runtime": ort.__version__, "opencv": cv2.__version__, "telemetry_disabled_before_import": True,
        "dictionary_with_ctc_blank_and_space": len(characters), "expected": expected, "recognized": decoded,
        "detection_shape": list(detection.shape), "text_regions": len(regions),
        "inputs": {kind: [{"name": i.name, "shape": i.shape, "type": i.type} for i in session.get_inputs()] for kind, session in sessions.items()},
    }
    (WORK / "evidence/model-smoke.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n")
    image.save(WORK / "evidence/generated-smoke.png")
    print(json.dumps(evidence, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
