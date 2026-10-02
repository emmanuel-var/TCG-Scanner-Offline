# Card artwork embedder: training and export

Produces `card_embedder.tflite`, the optional visual engine the app downloads from Settings / the scanner screen.

```
pip install -r requirements.txt

# 1. reference images: one clean image per card (any JSON catalog works, see --help)
python fetch_images.py --catalog https://api.scryfall.com/bulk-data/default-cards --game mtg --out images --limit 20000

# 2. train + export + evaluate (GPU recommended; defaults: 224 px, 128-d, fp16)
python train_card_embedder.py --images images --out build --min-top1 0.9

# 3. publish
#    - attach build/card_embedder.tflite to a GitHub release
#    - ModelConfig.DEFAULT_URL = that asset URL, ModelConfig.SHA256 = contents of build/card_embedder.sha256
```

Train one model for all games: put every game's images under `images/` (sub-folders are fine). The model learns
general "same card under camera conditions" similarity, so it also helps games it has not seen, but recall is best for
the cards it was trained on. Re-run when many new sets are released.

## How it works

* **Task**: each card is a class with a single clean reference image. Every training step generates new camera-like
  augmentations (perspective tilt, rotation, zoom, glare band, blur, noise, colour and JPEG artefacts) and trains a
  MobileNetV2 + 128-d embedding with a CosFace cosine-margin head. The head is dropped at export.
* **Evaluation** (`metrics.json`): the exported `.tflite` embeds all references; strongly augmented queries (stronger
  than training) must retrieve their own card. Reports top-1 / top-5. Use `--min-top1` to fail a bad run in CI.
* **Size**: fp16 MobileNetV2 + dense is about 4.5 MB; the app's UI text says 15 MB so there is headroom for a larger
  backbone or `--embedding-dim`.

## Contract with the Android app

| | |
|---|---|
| input | `float32 [1, S, S, 3]`, RGB, `pixel / 127.5 - 1` |
| crop | artwork window x 8–92 %, y 12–58 % of the card, squashed to S×S (`VisualSignature.artCrop`) |
| output | `float32 [1, D]`, L2-normalised |

`ART_BOX` in the script must equal `VisualSignature.X0/X1/Y0/Y1`. S and D are read from the model by
`TfliteEmbedder`, so you can change `--image-size` / `--embedding-dim` without touching Kotlin.

`reference_embeddings.f16` + `labels.json` are also written: they allow shipping precomputed reference vectors in a
future app version instead of downloading card images on the phone to build the artwork index.

## Smoke test without internet or ImageNet weights

```
python train_card_embedder.py --images images --out build --weights none --image-size 96 \
    --embedding-dim 32 --epochs-warmup 1 --epochs-finetune 1 --max-classes 200
```

Without ImageNet weights accuracy is poor by design; this only checks the pipeline end to end.
