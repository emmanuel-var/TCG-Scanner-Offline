# Models for the on-device scan pipeline (no training)

The app reads cards with a hybrid pipeline; every model is a **pretrained, off-the-shelf network that is only converted**:

| Phase | Model | Script | Pack files |
|---|---|---|---|
| 1. read the set code | PaddleOCR-Mobile (ONNX) | `prepare_ocr_pack.py` | `ocr_det.onnx`, `ocr_rec.onnx`, `ocr_dict.txt` (+ `ocr_rec_japan.onnx`, `ocr_dict_japan.txt`) |
| 2. artwork fallback | EfficientNet-Lite0 feature vector (TFLite) | `export_efficientnet_lite0.py` | `card_embedder.tflite` |

No model here is fine-tuned for recognising cards, and there is deliberately **no neural card detector**: the card edges are found with plain image processing in the app (`QuadRefiner`), so no AGPL-licensed detector weights are needed.

```
pip install -r requirements.txt
python prepare_ocr_pack.py --out build --japanese
python export_efficientnet_lite0.py --out build
python release_checksums.py build/
```

Publish: upload the files of `build/` to a GitHub release (default tag `models-v1`, see `EnginePacks.DEFAULT_BASE_URL`), or to
any https folder and paste that folder in Settings -> Scan engine. Optionally put the printed SHA-256 values into the
`PackFile` entries of `EnginePacks.kt` so downloads are verified.

## Contracts the Kotlin code relies on

* **PaddleOCR** detector `[N,3,H,W]` (BGR, ImageNet mean/std) -> probability map; recogniser `[N,3,48,W]` (BGR, `(x/255-.5)/.5`) ->
  `[N,T,classes]` with `classes = dictionary lines + 2` (CTC blank first, space last). `prepare_ocr_pack.py` verifies this.
* **EfficientNet-Lite0** `[1,224,224,3]` RGB in [-1,1] (the artwork window of the card, `VisualSignature.artCrop`) -> `[1,1280]`, L2-normalised.

Status: the Keras path of `export_efficientnet_lite0.py` was run end to end here (TensorFlow 2.21, random weights, because the
model hubs are unreachable from the build sandbox); the TF Hub path, the PaddleOCR conversion is written
against the public tools but **were not executed**: run them once and check the printed shapes before publishing.
