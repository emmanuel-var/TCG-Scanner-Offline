# Optional TensorFlow Lite artwork model

The scanner always works with perceptual hashes. To use a learned embedding instead:

1. Export an image feature extractor (e.g. MobileNetV2 without the classification head, global-average-pooled) to TFLite. Input `[1, H, W, 3]` (float32 in −1..1, or uint8); output `[1, N]`.
2. Save it as `app/src/main/assets/models/card_embedder.tflite` (the build already stores `.tflite` uncompressed).
3. Rebuild. In Settings → *Scanner artwork index* clear and rebuild the index so embeddings are stored; matching then uses cosine similarity.

The embedder is applied to the artwork window of the card (see `VisualSignature.artCrop`). Check the licence of any model you bundle.
