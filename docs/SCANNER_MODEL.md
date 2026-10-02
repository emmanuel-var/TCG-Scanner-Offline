# Visual engine (TensorFlow Lite artwork embedder)

The scanner always works with OCR and perceptual hashes. The learned embedding is an optional ~5-15 MB download.

**Build it** with `tools/model/` (see its README): `fetch_images.py` collects one reference image per card,
`train_card_embedder.py` trains a MobileNetV2 embedder with camera-like augmentation, exports fp16 TFLite and reports
retrieval top-1 / top-5 on strongly augmented queries.

**Publish it**: attach `card_embedder.tflite` to a GitHub release, set `ModelConfig.DEFAULT_URL` to the asset URL and
`ModelConfig.SHA256` to `build/card_embedder.sha256` (downloads are verified when it is non-empty). Users can also paste
another URL in Settings → Visual engine.

**On the device** the file lives in `Context.filesDir/card_embedder.tflite`. `ModelDownloadWorker` fetches it (resumable,
`.part` file, atomic rename); `ModelRepository` exposes `StateFlow<ModelState>` which the camera screen collects. When the
state becomes `Ready` the interpreter is loaded and "Identify by artwork" is enabled, with no restart. After installing the
model, rebuild the artwork index (Settings → Scanner artwork index) so embeddings are stored with each card signature.

**Contract**: input `float32 [1,S,S,3]` in [-1,1]; the artwork window (x 8-92 %, y 12-58 %) of the card, squashed to S x S;
output `float32 [1,D]`, L2-normalised. S and D are read from the model at load time.
