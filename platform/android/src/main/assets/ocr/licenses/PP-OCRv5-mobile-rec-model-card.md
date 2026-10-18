---
license: apache-2.0
library_name: PaddleOCR
language:
- en
- zh
pipeline_tag: image-to-text
tags:
- OCR
- PaddlePaddle
- PaddleOCR
- textline_recognition
---

# PP-OCRv5_mobile_rec

## Introduction

PP-OCRv5_mobile_rec is one of the PP-OCRv5_rec that are the latest generation text line recognition models developed by PaddleOCR team. It aims to efficiently and accurately support the recognition of four major languages—Simplified Chinese, Traditional Chinese, English, and Japanese—as well as complex text scenarios such as handwriting, vertical text, pinyin, and rare characters using a single model. The key accuracy metrics are as follow:

| Handwritten Chinese | Handwritten English | Printed Chinese | Printed English | Traditional Chinese | Ancient Text | Japanese | General Scenario | Pinyin | Rotation | Distortion | Artistic Text | Average |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 0.4166 | 0.4944 | 0.8605 | 0.8753 | 0.7199 | 0.5786 | 0.7577 | 0.5570 | 0.7703 | 0.7248 | 0.8089 | 0.5398 | 0.8015 |

**Note**: If any character (including punctuation) in a line is incorrect, the entire line is marked as wrong. This ensures higher accuracy in practical applications.

## Model Usage

### Install Dependencies

```shell
pip install -U paddleocr
pip install -U onnxruntime-gpu
```

### CLI Usage

```shell
paddleocr text_recognition -i ./demo.png --model_name PP-OCRv5_mobile_rec --engine onnxruntime
```

### Python API Usage

```python
from paddleocr import TextRecognition

model = TextRecognition(
    model_name="PP-OCRv5_mobile_rec",
    engine="onnxruntime",
)
output = model.predict("./demo.png", batch_size=1)
for res in output:
    res.print()
    res.save_to_json(save_path="./output/res.json")
```