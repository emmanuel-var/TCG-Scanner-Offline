#!/usr/bin/env python3
"""Phase 1 model: YOLO11n (or YOLOv8n) CARD DETECTOR exported to TensorFlow Lite.

The detector only answers "where is the card in this frame?". The app crops that box, straightens the card and hands
the clean image to OCR; recognising WHICH card it is stays the OCR's (and, optionally, EfficientNet's) job.

You need weights of a ONE-CLASS "card" detector. Generic COCO weights (yolo11n.pt) do not know trading cards. Options:
  * download a public trading-card / playing-card detector (Roboflow Universe, Ultralytics HUB, Hugging Face): check its
    licence, then pass the .pt here;
  * train one: it is a standard single-class detection job (a few hundred labelled photos of cards on tables / in hands
    is enough for a nano model):  yolo detect train data=cards.yaml model=yolo11n.pt imgsz=640 epochs=60

    python export_yolo_card_detector.py --weights card_detector.pt --out build [--check photo.jpg]

Output: build/card_detector.tflite   (input [1,S,S,3] RGB 0..1, output [1, 4+classes, anchors], float16 weights).
The Kotlin decoder (YoloDecoder) accepts channel-first or transposed outputs and pixel or normalised boxes.
"""
from __future__ import annotations

import argparse
import hashlib
import shutil
import sys
from pathlib import Path

import numpy as np


def find_tflite(exported: str) -> Path:
    path = Path(exported)
    if path.is_file() and path.suffix == ".tflite":
        return path
    candidates = sorted(path.rglob("*.tflite"), key=lambda p: ("float16" not in p.name, p.name)) if path.is_dir() else []
    if not candidates:
        sys.exit(f"Could not find the exported .tflite under {exported}")
    return candidates[0]


def letterbox(image, size: int):
    """Same preprocessing as the app: aspect-preserving resize onto a grey (114) square."""
    from PIL import Image

    w, h = image.size
    scale = min(size / w, size / h)
    nw, nh = max(1, int(w * scale)), max(1, int(h * scale))
    canvas = Image.new("RGB", (size, size), (114, 114, 114))
    pad_x, pad_y = (size - nw) / 2, (size - nh) / 2
    canvas.paste(image.resize((nw, nh), Image.BILINEAR), (int(pad_x), int(pad_y)))
    return canvas, scale, pad_x, pad_y


def decode(output: np.ndarray, size: int, scale: float, pad_x: float, pad_y: float, src_w: int, src_h: int, threshold: float = 0.35):
    """Mirror of YoloDecoder.kt: layout and box units are auto-detected, best class score is the confidence."""
    out = output[0]
    channels_first = out.shape[0] < out.shape[1]
    rows = out.T if channels_first else out            # [anchors, 4 + classes]
    scores = rows[:, 4:].max(axis=1)
    keep = scores >= threshold
    if not keep.any():
        return []
    rows, scores = rows[keep], scores[keep]
    unit = size if rows[:50, 2:4].max() <= 2.0 else 1.0  # normalised boxes -> pixels
    cx, cy, w, h = (rows[:, i] * unit for i in range(4))
    boxes = np.stack([cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2], axis=1)
    boxes[:, [0, 2]] = ((boxes[:, [0, 2]] - pad_x) / scale).clip(0, src_w)
    boxes[:, [1, 3]] = ((boxes[:, [1, 3]] - pad_y) / scale).clip(0, src_h)
    order = np.argsort(-scores)
    return [(boxes[i].round(1).tolist(), float(scores[i])) for i in order[:5]]


def check(tflite_path: Path, image_path: Path | None) -> None:
    try:
        from ai_edge_litert.interpreter import Interpreter  # type: ignore
    except ImportError:
        import tensorflow as tf

        Interpreter = tf.lite.Interpreter
    interp = Interpreter(model_path=str(tflite_path))
    interp.allocate_tensors()
    inp, out = interp.get_input_details()[0], interp.get_output_details()[0]
    print(f"input  {list(inp['shape'])} {inp['dtype'].__name__}")
    print(f"output {list(out['shape'])} {out['dtype'].__name__}")
    shape = list(out["shape"])
    assert len(shape) == 3 and 5 in (shape[1], shape[2]) or min(shape[1:]) >= 5, "unexpected detector output shape"
    if image_path is None:
        return
    from PIL import Image

    size = int(inp["shape"][1])
    image = Image.open(image_path).convert("RGB")
    boxed, scale, pad_x, pad_y = letterbox(image, size)
    data = (np.asarray(boxed, dtype=np.float32) / 255.0)[None]
    interp.set_tensor(inp["index"], data)
    interp.invoke()
    detections = decode(interp.get_tensor(out["index"]), size, scale, pad_x, pad_y, *image.size)
    print("detections (left, top, right, bottom), score:" if detections else "no detections")
    for box, score in detections:
        print(" ", box, round(score, 3))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--weights", required=True, help="a one-class card detector .pt (YOLO11n / YOLOv8n)")
    parser.add_argument("--out", type=Path, default=Path("build"))
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--no-half", action="store_true", help="export float32 instead of float16")
    parser.add_argument("--allow-generic", action="store_true", help="skip the check that the model has a card-like class")
    parser.add_argument("--check", type=Path, help="run the exported model on this photo and print the detections")
    args = parser.parse_args()

    from ultralytics import YOLO

    model = YOLO(args.weights)
    names = [str(n).lower() for n in (model.names.values() if isinstance(model.names, dict) else model.names)]
    if not args.allow_generic and not any("card" in n for n in names):
        sys.exit(
            f"The model's classes are {names[:8]}...: none looks like a trading card.\n"
            "Use a card detector (see this script's header) or pass --allow-generic if you know what you are doing."
        )
    exported = model.export(format="tflite", imgsz=args.imgsz, half=not args.no_half)
    source = find_tflite(str(exported))
    args.out.mkdir(parents=True, exist_ok=True)
    target = args.out / "card_detector.tflite"
    shutil.copyfile(source, target)
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    (args.out / "card_detector.sha256").write_text(digest + "\n")
    print(f"wrote {target} ({target.stat().st_size / 1e6:.1f} MB)\nsha256 {digest}")
    check(target, args.check)
    return 0


if __name__ == "__main__":
    sys.exit(main())
