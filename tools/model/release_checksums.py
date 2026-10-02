#!/usr/bin/env python3
"""Lists the pack files of a release folder with sizes and SHA-256, in the shape of EnginePacks.kt's PackFile entries.

    python release_checksums.py build/        # the folder you upload to the GitHub release
"""
import hashlib
import sys
from pathlib import Path

NAMES = ["card_detector.tflite", "ocr_det.onnx", "ocr_rec.onnx", "ocr_dict.txt", "ocr_rec_japan.onnx", "ocr_dict_japan.txt", "card_embedder.tflite"]

folder = Path(sys.argv[1] if len(sys.argv) > 1 else "build")
for name in NAMES:
    path = folder / name
    if not path.is_file():
        print(f"missing  {name}")
        continue
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    print(f'PackFile("{name}", {path.stat().st_size / 1e6:.1f}, {max(100, path.stat().st_size // 4)}, sha256 = "{digest}"),')
