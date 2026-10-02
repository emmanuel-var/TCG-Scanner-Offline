# Models for the on-device scan pipeline (no training)

The app reads cards with a hybrid pipeline; every model is a **pretrained, off-the-shelf network that is only converted**:

| Phase | Model | Script | Pack files |
|---|---|---|---|
| 1. isolate the card | YOLO11n / YOLOv8n **card detector** (TFLite) | `export_yolo_card_detector.py` | `card_detector.tflite` |
| 2. read the set code | PaddleOCR-Mobile (ONNX) | `prepare_ocr_pack.py` | `ocr_det.onnx`, `ocr_rec.onnx`, `ocr_dict.txt` (+ `ocr_rec_japan.onnx`, `ocr_dict_japan.txt`) |
| 3. artwork fallback | EfficientNet-Lite0 feature vector (TFLite) | `export_efficientnet_lite0.py` | `card_embedder.tflite` |

The previous embedding-training script was removed: nothing here is fine-tuned for recognising cards. The only model that
needs task data is the YOLO **detector**, which answers "where is a card?" and is a standard one-class detection job (see the
header of `export_yolo_card_detector.py`); the rest are published weights.

```
pip install -r requirements.txt
python export_yolo_card_detector.py --weights card_detector.pt --out build --check some_photo.jpg
python prepare_ocr_pack.py --out build --japanese
python export_efficientnet_lite0.py --out build
python release_checksums.py build/
```

Publish: upload the files of `build/` to a GitHub release (default tag `models-v1`, see `EnginePacks.DEFAULT_BASE_URL`), or to
any https folder and paste that folder in Settings -> Scan engine. Optionally put the printed SHA-256 values into the
`PackFile` entries of `EnginePacks.kt` so downloads are verified.

## Contracts the Kotlin code relies on

* **Detector** `[1,S,S,3]` RGB 0..1 -> `[1,4+classes,anchors]` (or transposed), boxes normalised or in pixels; best class
  score = confidence (`YoloDecoder`). Letterboxed with grey 114 padding. Export with `format=tflite`; int8 exports are not supported.
* **PaddleOCR** detector `[N,3,H,W]` (BGR, ImageNet mean/std) -> probability map; recogniser `[N,3,48,W]` (BGR, `(x/255-.5)/.5`) ->
  `[N,T,classes]` with `classes = dictionary lines + 2` (CTC blank first, space last). `prepare_ocr_pack.py` verifies this.
* **EfficientNet-Lite0** `[1,224,224,3]` RGB in [-1,1] (the artwork window of the card, `VisualSignature.artCrop`) -> `[1,1280]`, L2-normalised.

Status: the Keras path of `export_efficientnet_lite0.py` was run end to end here (TensorFlow 2.21, random weights, because the
model hubs are unreachable from the build sandbox); the TF Hub path, the YOLO export and the PaddleOCR conversion are written
against the public tools but **were not executed**: run them once and check the printed shapes before publishing.
