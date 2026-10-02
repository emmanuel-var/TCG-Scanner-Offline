# Optional TensorFlow Lite artwork model

The scanner always works with OCR and perceptual hashes. The learned embedding is an optional download:

1. Export an image feature extractor (e.g. MobileNetV2 without the classification head, global-average-pooled) to TFLite. Input `[1, H, W, 3]` (float32 in −1..1, or uint8); output `[1, N]`.
2. Publish it as `card_embedder.tflite` in a public location, e.g. a GitHub release of this repository. The default URL is `ModelConfig.DEFAULT_URL`; users can override it in Settings → Visual engine.
3. Set `ModelConfig.SHA256` to the file's lowercase SHA-256 so downloads are verified (leave empty to skip).

On the device the file lives in `Context.filesDir/card_embedder.tflite`. `ModelDownloadWorker` fetches it (resumable, `.part` file, atomic rename); `ModelRepository` exposes `StateFlow<ModelState>` which the camera screen collects. When the state becomes `Ready` the interpreter is loaded and "Identify by artwork" is enabled, with no restart. After installing the model, rebuild the artwork index (Settings → Scanner artwork index) so embeddings are stored with each card signature.

The embedder is applied to the artwork window of the card (see `VisualSignature.artCrop`). Check the licence of any model you publish.
