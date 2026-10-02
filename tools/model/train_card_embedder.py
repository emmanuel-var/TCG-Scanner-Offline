#!/usr/bin/env python3
"""Train and export the on-device card ARTWORK embedder (TensorFlow Lite) used by TCG Scanner Offline.

What it builds
--------------
A MobileNetV2 feature extractor -> global average pooling -> Dense(embedding_dim) -> L2 normalisation.
Two photos of the same card land close together (cosine similarity), photos of different cards far apart.
The app embeds a camera crop and looks for the nearest stored reference embedding.

How it learns
-------------
Every card has ONE clean reference image, so the training task is "recognise this card under camera
conditions": each step produces fresh, aggressive augmentations of the reference (perspective tilt, rotation,
zoom, glare, blur, noise, colour shifts, JPEG artefacts) and the network is trained with a CosFace
(cosine-margin) classifier head where every card is a class. The classifier head is discarded at export.

Contract with the Android app (do not change without changing the app)
----------------------------------------------------------------------
* input  : float32 [1, S, S, 3], RGB in [-1, 1]  (pixel / 127.5 - 1)   -> TfliteEmbedder.embed()
* content: the ARTWORK window of the card, x 8%..92%, y 12%..58%, squashed to S x S  -> VisualSignature.artCrop()
* output : float32 [1, D], L2-normalised
The crop window below (ART_BOX) must stay equal to VisualSignature.X0/X1/Y0/Y1 in the Kotlin code.

Usage
-----
    pip install -r requirements.txt
    python fetch_images.py --catalog <catalog.json or URL> --game pokemon --out images --limit 20000
    python train_card_embedder.py --images images --out build

    # quick smoke test with no internet / no ImageNet weights:
    python train_card_embedder.py --images images --out build --weights none --image-size 96 \
        --embedding-dim 32 --epochs-warmup 1 --epochs-finetune 1 --max-classes 200

Outputs (in --out): card_embedder.tflite, card_embedder.sha256, labels.json, reference_embeddings.f16,
metrics.json, model_info.json. Publish card_embedder.tflite (for example as a GitHub release asset), put the
SHA-256 in ModelConfig.SHA256 and the URL in ModelConfig.DEFAULT_URL.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import sys
from pathlib import Path

import numpy as np
from PIL import Image

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
import tensorflow as tf  # noqa: E402
import keras  # noqa: E402

# Artwork window of a portrait card as fractions (x0, y0, x1, y1). Keep identical to VisualSignature.kt.
ART_BOX = (0.08, 0.12, 0.92, 0.58)
IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp"}


# ---------------------------------------------------------------------------------------------------------
# Data
# ---------------------------------------------------------------------------------------------------------
def list_images(root: Path, max_classes: int, seed: int) -> list[Path]:
    files = sorted(p for p in root.rglob("*") if p.suffix.lower() in IMAGE_EXTENSIONS)
    if not files:
        sys.exit(f"No images found under {root}. Run fetch_images.py first.")
    if max_classes and len(files) > max_classes:
        rng = np.random.default_rng(seed)
        files = [files[i] for i in sorted(rng.choice(len(files), max_classes, replace=False))]
    return files


def art_crop(path: Path, size: int) -> np.ndarray | None:
    """Same preprocessing as the app: crop the artwork window of the card, squash it to size x size."""
    try:
        with Image.open(path) as image:
            image = image.convert("RGB")
            w, h = image.size
            box = (int(w * ART_BOX[0]), int(h * ART_BOX[1]), int(w * ART_BOX[2]), int(h * ART_BOX[3]))
            return np.asarray(image.crop(box).resize((size, size), Image.BILINEAR), dtype=np.uint8)
    except Exception as error:  # corrupt download, HTML error page saved as .jpg...
        print(f"skip {path.name}: {error}")
        return None


def build_cache(files: list[Path], size: int, cache_dir: Path) -> tuple[np.ndarray, list[str]]:
    """Decode every reference once into a memory-mapped uint8 array so training does not hit the disk each step."""
    cache_dir.mkdir(parents=True, exist_ok=True)
    arrays, labels = [], []
    for i, path in enumerate(files, 1):
        crop = art_crop(path, size)
        if crop is not None:
            arrays.append(crop)
            labels.append(path.stem)
        if i % 2000 == 0:
            print(f"  decoded {i}/{len(files)}")
    if not arrays:
        sys.exit("No usable images.")
    mm = np.lib.format.open_memmap(cache_dir / "refs.npy", mode="w+", dtype=np.uint8, shape=(len(arrays), size, size, 3))
    for i, array in enumerate(arrays):
        mm[i] = array
    mm.flush()
    return np.load(cache_dir / "refs.npy", mmap_mode="r"), labels


# ---------------------------------------------------------------------------------------------------------
# Camera-like augmentation (pure TensorFlow ops, runs inside tf.data)
# ---------------------------------------------------------------------------------------------------------
def random_perspective(image: tf.Tensor, strength: float) -> tf.Tensor:
    size = tf.cast(tf.shape(image)[0], tf.float32)
    s = strength
    a0 = 1.0 + tf.random.uniform([], -0.12, 0.12) * s       # scale x
    b1 = 1.0 + tf.random.uniform([], -0.12, 0.12) * s       # scale y
    a1 = tf.random.uniform([], -0.10, 0.10) * s             # shear
    b0 = tf.random.uniform([], -0.10, 0.10) * s
    angle = tf.random.uniform([], -0.20, 0.20) * s           # radians (~ +-11 degrees)
    cos, sin = tf.cos(angle), tf.sin(angle)
    a0, a1, b0, b1 = a0 * cos, a1 - sin, b0 + sin, b1 * cos
    a2 = tf.random.uniform([], -0.08, 0.08) * size * s
    b2 = tf.random.uniform([], -0.08, 0.08) * size * s
    c0 = tf.random.uniform([], -0.0007, 0.0007) * s * (224.0 / size)   # perspective tilt
    c1 = tf.random.uniform([], -0.0007, 0.0007) * s * (224.0 / size)
    transform = tf.stack([a0, a1, a2, b0, b1, b2, c0, c1])[tf.newaxis, :]
    out = tf.raw_ops.ImageProjectiveTransformV3(
        images=image[tf.newaxis], transforms=transform, output_shape=tf.shape(image)[:2],
        fill_value=0.0, interpolation="BILINEAR", fill_mode="REFLECT",
    )
    return out[0]


def random_blur(image: tf.Tensor, strength: float) -> tf.Tensor:
    sigma = tf.random.uniform([], 0.1, 1.8) * strength
    radius = 3
    x = tf.cast(tf.range(-radius, radius + 1), tf.float32)
    kernel_1d = tf.exp(-(x ** 2) / (2.0 * tf.maximum(sigma, 0.1) ** 2))
    kernel_1d /= tf.reduce_sum(kernel_1d)
    kernel = kernel_1d[:, None] * kernel_1d[None, :]
    kernel = tf.tile(kernel[:, :, None, None], [1, 1, 3, 1])
    blurred = tf.nn.depthwise_conv2d(image[tf.newaxis], kernel, [1, 1, 1, 1], "SAME")[0]
    return tf.where(tf.random.uniform([]) < 0.6, blurred, image)


def random_glare(image: tf.Tensor, strength: float) -> tf.Tensor:
    """A soft bright diagonal band, like a light reflecting off a sleeve or slab."""
    size = tf.shape(image)[0]
    coords = tf.linspace(-1.0, 1.0, size)
    xx, yy = tf.meshgrid(coords, coords)
    theta = tf.random.uniform([], 0.0, math.pi)
    distance = xx * tf.cos(theta) + yy * tf.sin(theta)
    center = tf.random.uniform([], -0.8, 0.8)
    width = tf.random.uniform([], 0.08, 0.35)
    band = tf.exp(-((distance - center) ** 2) / (2.0 * width ** 2))
    gain = tf.random.uniform([], 0.0, 0.7) * strength
    glared = image + gain * band[:, :, None]
    return tf.where(tf.random.uniform([]) < 0.35, glared, image)


def augment(image_u8: tf.Tensor, strength: float = 1.0) -> tf.Tensor:
    """uint8 [S,S,3] -> float32 [S,S,3] in [-1, 1] with random camera-like distortions."""
    image = tf.cast(image_u8, tf.float32) / 255.0
    image = random_perspective(image, strength)
    image = tf.image.random_brightness(image, 0.25 * strength)
    image = tf.image.random_contrast(image, 1.0 - 0.3 * strength, 1.0 + 0.3 * strength)
    image = tf.image.random_saturation(image, 1.0 - 0.35 * strength, 1.0 + 0.35 * strength)
    image = tf.image.random_hue(image, 0.04 * strength)
    image = random_glare(image, strength)
    image = random_blur(image, strength)
    image = image + tf.random.normal(tf.shape(image), stddev=0.03 * strength * tf.random.uniform([]))
    image = tf.clip_by_value(image, 0.0, 1.0)
    image = tf.cond(
        tf.random.uniform([]) < 0.5,
        lambda: tf.image.random_jpeg_quality(image, 35, 95),
        lambda: image,
    )
    return tf.clip_by_value(image, 0.0, 1.0) * 2.0 - 1.0


def to_model_input(image_u8: tf.Tensor) -> tf.Tensor:
    """Clean (un-augmented) preprocessing: exactly what the app feeds the interpreter."""
    return tf.cast(image_u8, tf.float32) / 127.5 - 1.0


def make_train_dataset(refs: np.ndarray, batch_size: int, strength: float, seed: int) -> tf.data.Dataset:
    count = len(refs)

    def generator():
        rng = np.random.default_rng(seed)
        while True:
            for index in rng.permutation(count):
                yield refs[index], index

    spec = (tf.TensorSpec(refs.shape[1:], tf.uint8), tf.TensorSpec((), tf.int32))
    ds = tf.data.Dataset.from_generator(generator, output_signature=spec)
    ds = ds.map(lambda img, label: (augment(img, strength), label), num_parallel_calls=tf.data.AUTOTUNE)
    return ds.batch(batch_size, drop_remainder=True).prefetch(tf.data.AUTOTUNE)


# ---------------------------------------------------------------------------------------------------------
# Model
# ---------------------------------------------------------------------------------------------------------
@keras.saving.register_keras_serializable(package="tcg")
class L2Normalize(keras.layers.Layer):
    def call(self, x):
        return tf.math.l2_normalize(x, axis=-1)


@keras.saving.register_keras_serializable(package="tcg")
class CosFaceHead(keras.layers.Layer):
    """Cosine classifier with an additive margin: logits = s * (cos(theta) - m * onehot(label)). Training only."""

    def __init__(self, num_classes: int, scale: float = 30.0, margin: float = 0.25, **kwargs):
        super().__init__(**kwargs)
        self.num_classes, self.scale, self.margin = num_classes, scale, margin

    def build(self, input_shape):
        self.weight = self.add_weight(
            name="class_embeddings", shape=(int(input_shape[0][-1]), self.num_classes),
            initializer="glorot_uniform", trainable=True,
        )

    def call(self, inputs):
        embeddings, labels = inputs
        cosine = tf.matmul(embeddings, tf.math.l2_normalize(self.weight, axis=0))
        onehot = tf.one_hot(tf.cast(labels, tf.int32), self.num_classes)
        return self.scale * (cosine - self.margin * onehot)

    def get_config(self):
        return {**super().get_config(), "num_classes": self.num_classes, "scale": self.scale, "margin": self.margin}


def build_models(size: int, dim: int, num_classes: int, weights: str | None):
    inputs = keras.Input((size, size, 3), name="art")
    backbone = keras.applications.MobileNetV2(input_shape=(size, size, 3), include_top=False, weights=weights, alpha=1.0)
    x = backbone(inputs, training=False)  # BatchNorm statistics stay frozen; fine for small per-class data
    x = keras.layers.GlobalAveragePooling2D()(x)
    x = keras.layers.Dropout(0.2)(x)
    x = keras.layers.Dense(dim, use_bias=False, name="embedding_dense")(x)
    embedding = L2Normalize(name="embedding")(x)
    embedder = keras.Model(inputs, embedding, name="card_embedder")

    labels = keras.Input((), dtype="int32", name="label")
    logits = CosFaceHead(num_classes, name="cosface")([embedding, labels])
    trainer = keras.Model([inputs, labels], logits, name="trainer")
    return embedder, trainer, backbone


def compile_trainer(trainer: keras.Model, learning_rate) -> None:
    trainer.compile(
        optimizer=keras.optimizers.Adam(learning_rate),
        loss=keras.losses.SparseCategoricalCrossentropy(from_logits=True),
        metrics=[keras.metrics.SparseCategoricalAccuracy(name="acc")],
    )


def fit_phase(trainer, dataset, epochs: int, steps: int, learning_rate, label: str) -> None:
    if epochs <= 0:
        return
    print(f"== {label}: {epochs} epochs x {steps} steps")
    compile_trainer(trainer, learning_rate)
    trainer.fit(dataset.map(lambda img, y: ((img, y), y)), epochs=epochs, steps_per_epoch=steps, verbose=2)


# ---------------------------------------------------------------------------------------------------------
# Export + evaluation
# ---------------------------------------------------------------------------------------------------------
def export_tflite(embedder: keras.Model, refs: np.ndarray, quantize: str) -> bytes:
    converter = tf.lite.TFLiteConverter.from_keras_model(embedder)
    if quantize == "fp16":
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        converter.target_spec.supported_types = [tf.float16]
    elif quantize == "dynamic":
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
    return converter.convert()


def _interpreter(model_bytes: bytes):
    """LiteRT (ai-edge-litert) replaces tf.lite.Interpreter in recent TensorFlow releases; use whichever exists."""
    try:
        from ai_edge_litert.interpreter import Interpreter  # type: ignore
        return Interpreter(model_content=model_bytes)
    except ImportError:
        return tf.lite.Interpreter(model_content=model_bytes)


class TfliteEmbedder:
    def __init__(self, model_bytes: bytes):
        self.interpreter = _interpreter(model_bytes)
        self.interpreter.allocate_tensors()
        self.input = self.interpreter.get_input_details()[0]
        self.output = self.interpreter.get_output_details()[0]

    def __call__(self, batch_f32: np.ndarray) -> np.ndarray:
        out = []
        for image in batch_f32:
            self.interpreter.set_tensor(self.input["index"], image[None].astype(np.float32))
            self.interpreter.invoke()
            vec = self.interpreter.get_tensor(self.output["index"])[0].copy()
            out.append(vec / max(float(np.linalg.norm(vec)), 1e-6))  # the app normalises again as well
        return np.stack(out)


def evaluate(model_bytes: bytes, refs: np.ndarray, queries_per_card: int, max_cards: int, seed: int) -> dict:
    """Embed clean references, then check that strongly augmented photos retrieve the right card."""
    tf.random.set_seed(seed)
    embedder = TfliteEmbedder(model_bytes)
    reference_vectors = embedder(np.stack([to_model_input(tf.constant(r)).numpy() for r in refs]))
    rng = np.random.default_rng(seed)
    chosen = rng.choice(len(refs), min(max_cards, len(refs)), replace=False)
    top1 = top5 = total = 0
    for index in chosen:
        for _ in range(queries_per_card):
            query = embedder(augment(tf.constant(refs[index]), strength=1.3).numpy()[None])[0]
            scores = reference_vectors @ query
            order = np.argsort(-scores)[:5]
            top1 += int(order[0] == index)
            top5 += int(index in order)
            total += 1
    return {"queries": total, "top1": top1 / total, "top5": top5 / total, "references": len(refs)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--images", required=True, type=Path, help="folder with one reference image per card")
    parser.add_argument("--out", default=Path("build"), type=Path)
    parser.add_argument("--image-size", type=int, default=224)
    parser.add_argument("--embedding-dim", type=int, default=128)
    parser.add_argument("--weights", choices=["imagenet", "none"], default="imagenet")
    parser.add_argument("--epochs-warmup", type=int, default=3, help="backbone frozen")
    parser.add_argument("--epochs-finetune", type=int, default=12, help="backbone unfrozen")
    parser.add_argument("--steps-per-epoch", type=int, default=0, help="0 = one pass over the cards")
    parser.add_argument("--batch-size", type=int, default=64)
    parser.add_argument("--max-classes", type=int, default=0, help="train on a random subset of cards (0 = all)")
    parser.add_argument("--quantize", choices=["fp16", "dynamic", "none"], default="fp16")
    parser.add_argument("--eval-cards", type=int, default=500)
    parser.add_argument("--eval-queries", type=int, default=2)
    parser.add_argument("--min-top1", type=float, default=0.0, help="exit with an error if retrieval top-1 is lower")
    parser.add_argument("--seed", type=int, default=7)
    args = parser.parse_args()

    tf.random.set_seed(args.seed)
    np.random.seed(args.seed)
    args.out.mkdir(parents=True, exist_ok=True)

    files = list_images(args.images, args.max_classes, args.seed)
    print(f"{len(files)} reference images")
    refs, labels = build_cache(files, args.image_size, args.out / "cache")
    num_classes = len(labels)
    if args.batch_size > num_classes:
        args.batch_size = max(2, num_classes)
    steps = args.steps_per_epoch or max(1, math.ceil(num_classes / args.batch_size))

    weights = None if args.weights == "none" else "imagenet"
    try:
        embedder, trainer, backbone = build_models(args.image_size, args.embedding_dim, num_classes, weights)
    except Exception as error:  # ImageNet weights not downloadable (offline machine)
        if weights is None:
            raise
        print(f"Could not load ImageNet weights ({error}); training from scratch. Expect worse accuracy.")
        embedder, trainer, backbone = build_models(args.image_size, args.embedding_dim, num_classes, None)

    dataset = make_train_dataset(refs, args.batch_size, strength=1.0, seed=args.seed)

    backbone.trainable = False
    fit_phase(trainer, dataset, args.epochs_warmup, steps, 1e-3, "warm-up (head only)")

    backbone.trainable = True
    total_steps = max(1, args.epochs_finetune * steps)
    schedule = keras.optimizers.schedules.CosineDecay(2e-4, total_steps)
    fit_phase(trainer, dataset, args.epochs_finetune, steps, schedule, "fine-tune (all layers)")

    print("== exporting TensorFlow Lite")
    model_bytes = export_tflite(embedder, refs, args.quantize)
    tflite_path = args.out / "card_embedder.tflite"
    tflite_path.write_bytes(model_bytes)
    digest = hashlib.sha256(model_bytes).hexdigest()
    (args.out / "card_embedder.sha256").write_text(digest + "\n")

    print("== evaluating the exported model")
    metrics = evaluate(model_bytes, refs, args.eval_queries, args.eval_cards, args.seed)
    metrics.update(size_mb=round(len(model_bytes) / 1e6, 2), sha256=digest, quantize=args.quantize)
    (args.out / "metrics.json").write_text(json.dumps(metrics, indent=2))

    reference_vectors = TfliteEmbedder(model_bytes)(np.stack([to_model_input(tf.constant(r)).numpy() for r in refs]))
    (args.out / "reference_embeddings.f16").write_bytes(reference_vectors.astype(np.float16).tobytes())
    (args.out / "labels.json").write_text(json.dumps(labels))
    (args.out / "model_info.json").write_text(json.dumps({
        "input": [1, args.image_size, args.image_size, 3], "input_range": [-1, 1], "output_dim": args.embedding_dim,
        "art_box": ART_BOX, "preprocess": "crop art_box of the card, resize to size x size, pixel/127.5-1",
        "reference_embeddings": "float16 little-endian, row i belongs to labels.json[i]",
    }, indent=2))

    print(json.dumps(metrics, indent=2))
    print(f"\nModel: {tflite_path}  ({metrics['size_mb']} MB)\nSHA-256: {digest}")
    print("Next: upload card_embedder.tflite (e.g. GitHub release), set ModelConfig.SHA256 and DEFAULT_URL.")
    if metrics["top1"] < args.min_top1:
        print(f"top-1 {metrics['top1']:.3f} is below --min-top1 {args.min_top1}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
