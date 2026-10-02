# Scan engine packs

The scanner always works out of the box with ML Kit text recognition. Optional packs make it more precise. They are plain files in
`Context.filesDir`, downloaded by WorkManager from one folder URL (`EnginePacks.DEFAULT_BASE_URL`, overridable in Settings -> Scan engine).

| Pack | Files | Used for |
|---|---|---|
| `DETECTOR` | `card_detector.tflite` | Phase 1: YOLO11n finds the card, the app straightens it |
| `OCR` | `ocr_det.onnx`, `ocr_rec.onnx`, `ocr_dict.txt` | Phase 2: PaddleOCR-Mobile reads the set code |
| `OCR_JA` | `ocr_rec_japan.onnx`, `ocr_dict_japan.txt` | Phase 2 for Japanese games (shares `ocr_det.onnx`) |
| `EMBEDDER` | `card_embedder.tflite` | Phase 3: EfficientNet-Lite0 artwork vectors ("Identify by artwork") |

Build them with `tools/model` (conversion of pretrained models; the only model that needs task data is the one-class YOLO card detector, see that script's
header). Upload the files to a release folder and, optionally, set the SHA-256 values in the `PackFile` entries of `EnginePacks.kt`.

## Reactive flow

`ModelDownloadWorker` downloads every file of a pack (resumable HTTP Range, `.part` files, atomic rename, size / checksum validation) and publishes progress.
`ModelRepository` combines the pack's `WorkInfo` with the files on disk into a `StateFlow<ModelState>` (`Missing -> Downloading(progress) -> Ready`). When it turns
`Ready` the matching engine (`YoloCardDetector`, `PaddleOcrEngine`, `TfliteEmbedder`) is loaded; unusable files are deleted and the state becomes `Failed`. The scanner
collects `scanEngineState` (detector + OCR) and `visualEngineState`: the notice disappears and the pipeline switches engines on the next frame, and the artwork button
enables, with no restart.

## Artwork index in RAM

Reference embeddings are produced on the device by the artwork indexer (Settings -> Scanner artwork index) and stored in Room. `ScannerViewModel` loads the active
game's embeddings once into a `VectorIndex` and answers every artwork query with cosine similarity over memory; Room is not queried per identification. Rebuilding the
index triggers a reload automatically.

## Contracts

* detector: `[1,S,S,3]` RGB 0..1 -> `[1,4+classes,anchors]` (channel-first or transposed, pixel or normalised boxes)
* PaddleOCR det `[N,3,H,W]` BGR ImageNet-normalised; rec `[N,3,48,W]` BGR `(x/255-.5)/.5`, `classes = dictionary lines + 2`
* embedder: `[1,224,224,3]` RGB in [-1,1] of the card's artwork window (`VisualSignature.artCrop`) -> `[1,D]` L2-normalised
