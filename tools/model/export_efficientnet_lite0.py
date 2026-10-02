#!/usr/bin/env python3
"""Phase 3 model: EfficientNet-Lite0 as an image EMBEDDER for "Identify by artwork". No training.

Takes the pretrained EfficientNet-Lite0 feature extractor (ImageNet) and wraps it so that it matches what the app feeds:

    input  float32 [1, 224, 224, 3], RGB in [-1, 1]      (the app sends pixel / 127.5 - 1 of the card's artwork window)
    output float32 [1, 1280], L2-normalised

The pretrained network is used as it is: two images of the same card produce close vectors because the network encodes
colour, texture and composition of the artwork. The app compares vectors with cosine similarity in RAM.

    python export_efficientnet_lite0.py --out build                      # TF Hub / Kaggle Models weights
    python export_efficientnet_lite0.py --out build --saved-model ./lite0_feature_vector   # an already downloaded SavedModel
    python export_efficientnet_lite0.py --out build --source keras-b0     # offline stand-in (EfficientNetB0), see below

Publish `build/card_embedder.tflite` next to the other packs (see README) and, optionally, put its SHA-256 in
`EnginePacks.EMBEDDER_FILE`'s PackFile.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from pathlib import Path

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
import numpy as np  # noqa: E402
import tensorflow as tf  # noqa: E402
import keras  # noqa: E402

HUB_HANDLE = "https://tfhub.dev/tensorflow/efficientnet/lite0/feature-vector/2"
SIZE = 224


@keras.saving.register_keras_serializable(package="tcg")
class L2Normalize(keras.layers.Layer):
    def call(self, x):
        return tf.math.l2_normalize(x, axis=-1)


@keras.saving.register_keras_serializable(package="tcg")
class MinusOneOneToZeroOne(keras.layers.Layer):
    """The app sends [-1, 1]; the TF Hub EfficientNet-Lite models expect [0, 1]."""

    def call(self, x):
        return (x + 1.0) * 0.5


def build(source: str, saved_model: str | None, weights: str | None) -> keras.Model:
    inputs = keras.Input((SIZE, SIZE, 3), name="art")
    if source == "hub":
        import tensorflow_hub as hub  # imported lazily: only this path needs it

        backbone = hub.KerasLayer(saved_model or HUB_HANDLE, trainable=False, name="efficientnet_lite0")
        x = backbone(MinusOneOneToZeroOne()(inputs))
    else:
        # Stand-in used when TF Hub is unreachable: Keras EfficientNetB0 (same family, a bit larger). It rescales
        # internally from [0, 255], so undo the app's [-1, 1] range first.
        base = keras.applications.EfficientNetB0(include_top=False, weights=weights, input_shape=(SIZE, SIZE, 3), pooling="avg")
        x = base(keras.layers.Rescaling(127.5, offset=127.5)(inputs))
    return keras.Model(inputs, L2Normalize(name="embedding")(x), name="card_embedder")


def convert(model: keras.Model, quantize: str) -> bytes:
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    if quantize == "fp16":
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        converter.target_spec.supported_types = [tf.float16]
    elif quantize == "dynamic":
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
    return converter.convert()


def check(tflite_bytes: bytes) -> dict:
    """Contract check: shapes, normalisation, and that two different images give different vectors."""
    try:
        from ai_edge_litert.interpreter import Interpreter  # type: ignore
    except ImportError:
        Interpreter = tf.lite.Interpreter
    it = Interpreter(model_content=tflite_bytes)
    it.allocate_tensors()
    inp, out = it.get_input_details()[0], it.get_output_details()[0]
    assert list(inp["shape"]) == [1, SIZE, SIZE, 3] and inp["dtype"] == np.float32, f"unexpected input {inp['shape']} {inp['dtype']}"
    assert len(out["shape"]) == 2 and out["shape"][0] == 1, f"unexpected output {out['shape']}"

    def embed(img):
        it.set_tensor(inp["index"], img[None].astype(np.float32))
        it.invoke()
        return it.get_tensor(out["index"])[0].copy()

    rng = np.random.default_rng(0)
    a, b = embed(rng.uniform(-1, 1, (SIZE, SIZE, 3))), embed(rng.uniform(-1, 1, (SIZE, SIZE, 3)))
    assert abs(float(np.linalg.norm(a)) - 1.0) < 1e-2, "output is not L2-normalised"
    return {"dim": int(out["shape"][1]), "cosine_of_two_random_images": round(float(a @ b), 4)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", type=Path, default=Path("build"))
    parser.add_argument("--source", choices=["hub", "keras-b0"], default="hub")
    parser.add_argument("--saved-model", help="local SavedModel directory (or hub handle) instead of the default handle")
    parser.add_argument("--keras-weights", choices=["imagenet", "none"], default="imagenet", help="only for --source keras-b0")
    parser.add_argument("--quantize", choices=["fp16", "dynamic", "none"], default="fp16")
    args = parser.parse_args()

    model = build(args.source, args.saved_model, None if args.keras_weights == "none" else "imagenet")
    data = convert(model, args.quantize)
    info = check(data)

    args.out.mkdir(parents=True, exist_ok=True)
    path = args.out / "card_embedder.tflite"
    path.write_bytes(data)
    digest = hashlib.sha256(data).hexdigest()
    (args.out / "card_embedder.sha256").write_text(digest + "\n")
    report = {"file": path.name, "size_mb": round(len(data) / 1e6, 2), "sha256": digest, "source": args.source, "quantize": args.quantize, **info}
    (args.out / "card_embedder.json").write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
