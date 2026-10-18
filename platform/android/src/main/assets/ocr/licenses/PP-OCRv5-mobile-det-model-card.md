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
- textline_detection
---

# PP-OCRv5_mobile_det

## Introduction

PP-OCRv5_mobile_det is one of the PP-OCRv5_det series, the latest generation of text detection models developed by the PaddleOCR team. It aims to efficiently and accurately supports the detection of text in diverse scenarios—including handwriting, vertical, rotated, and curved text—across multiple languages such as Simplified Chinese, Traditional Chinese, English, and Japanese. Key features include robust handling of complex layouts, varying text sizes, and challenging backgrounds, making it suitable for practical applications like document analysis, license plate recognition, and scene text detection. The key accuracy metrics are as follow:

| Handwritten Chinese | Handwritten English | Printed Chinese | Printed English | Traditional Chinese | Ancient Text | Japanese | General Scenario | Pinyin | Rotation | Distortion | Artistic Text | Average |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 0.744 | 0.777 | 0.905 | 0.910	| 0.823 | 0.581 | 0.727	 | 0.721 | 0.575 | 0.647 | 0.827 | 0.525 | 0.770 |

## Model Usage

### Install Dependencies

```shell
pip install -U paddleocr
pip install -U onnxruntime-gpu
```

### CLI Usage

```shell
paddleocr text_detection -i ./demo.png --model_name PP-OCRv5_mobile_det --engine onnxruntime
```

### Python API Usage

```python
from paddleocr import TextDetection

model = TextDetection(
    model_name="PP-OCRv5_mobile_det",
    engine="onnxruntime",
)
output = model.predict("./demo.png", batch_size=1)
for res in output:
    res.print()
    res.save_to_img(save_path="./output/")
    res.save_to_json(save_path="./output/res.json")
```