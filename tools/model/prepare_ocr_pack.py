#!/usr/bin/env python3
"""Phase 2 models: PaddleOCR-Mobile (PP-OCR) converted to ONNX for the app. No training.

Downloads the official PaddleOCR inference models, converts them with paddle2onnx and writes the OCR pack:

    ocr_det.onnx         DB text detector (multilingual, works for Latin and Japanese)
    ocr_rec.onnx         English / Latin recogniser            + ocr_dict.txt
    ocr_rec_japan.onnx   Japanese recogniser (optional)        + ocr_dict_japan.txt

    python prepare_ocr_pack.py --out build            # Latin pack
    python prepare_ocr_pack.py --out build --japanese # also the Japanese recogniser

Download locations are the public PaddleOCR model zoo / repository paths; they change between PaddleOCR releases, so
every URL can be overridden (--det-url, --rec-url, --dict-url, ...). After converting, the script loads each ONNX file
and checks the contract the app relies on (input layout, recogniser classes == dictionary size + 2).
"""
from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path

import requests

BCE = "https://paddleocr.bj.bcebos.com"
RAW = "https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/main/ppocr/utils"
DEFAULTS = {
    "det": f"{BCE}/PP-OCRv3/multilingual/Multilingual_PP-OCRv3_det_infer.tar",
    "rec": f"{BCE}/PP-OCRv4/english/en_PP-OCRv4_rec_infer.tar",
    "dict": f"{RAW}/en_dict.txt",
    "rec_ja": f"{BCE}/PP-OCRv3/multilingual/japan_PP-OCRv3_rec_infer.tar",
    "dict_ja": f"{RAW}/dict/japan_dict.txt",
}


def download(url: str, target: Path) -> Path:
    print(f"downloading {url}")
    response = requests.get(url, timeout=300, headers={"User-Agent": "tcg-scanner-model-tools/1.0"})
    response.raise_for_status()
    target.write_bytes(response.content)
    return target


def to_onnx(tar_url: str, out_file: Path, workdir: Path) -> None:
    archive = download(tar_url, workdir / Path(tar_url).name)
    with tarfile.open(archive) as tar:
        tar.extractall(workdir)
    model_dir = next(p.parent for p in workdir.rglob("inference.pdmodel")) if list(workdir.rglob("inference.pdmodel")) else None
    if model_dir is None:
        model_dir = next(p.parent for p in workdir.rglob("*.pdmodel"))
    pdmodel = next(model_dir.glob("*.pdmodel"))
    pdiparams = next(model_dir.glob("*.pdiparams"))
    subprocess.run(
        ["paddle2onnx", "--model_dir", str(model_dir), "--model_filename", pdmodel.name, "--params_filename", pdiparams.name,
         "--save_file", str(out_file), "--opset_version", "11", "--enable_onnx_checker", "True"],
        check=True,
    )
    for p in workdir.iterdir():  # keep the temp dir small between conversions
        if p.is_dir():
            import shutil
            shutil.rmtree(p, ignore_errors=True)


def check(det: Path | None, rec: Path | None, dictionary: Path | None) -> None:
    try:
        import onnxruntime as ort
    except ImportError:
        print("onnxruntime not installed: skipping the contract check")
        return
    if det:
        s = ort.InferenceSession(str(det))
        shape = s.get_inputs()[0].shape
        print(f"{det.name}: input {shape} output {s.get_outputs()[0].shape}")
        assert len(shape) == 4 and shape[1] == 3, "detector input must be [N,3,H,W]"
    if rec and dictionary:
        s = ort.InferenceSession(str(rec))
        shape = s.get_inputs()[0].shape
        classes = s.get_outputs()[0].shape[-1]
        lines = len(dictionary.read_text(encoding="utf-8").splitlines())
        print(f"{rec.name}: input {shape} output {s.get_outputs()[0].shape}; dictionary lines {lines}")
        assert len(shape) == 4 and shape[1] == 3 and shape[2] in (48, "height", None), "recogniser input must be [N,3,48,W]"
        if isinstance(classes, int):
            assert classes == lines + 2, f"recogniser has {classes} classes but dictionary + blank + space = {lines + 2}: wrong dictionary?"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", type=Path, default=Path("build"))
    parser.add_argument("--japanese", action="store_true", help="also build the Japanese recogniser")
    for key, url in DEFAULTS.items():
        parser.add_argument(f"--{key.replace('_', '-')}-url", default=url)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        det, rec = args.out / "ocr_det.onnx", args.out / "ocr_rec.onnx"
        to_onnx(args.det_url, det, work)
        to_onnx(args.rec_url, rec, work)
        dictionary = args.out / "ocr_dict.txt"
        download(args.dict_url, dictionary)
        check(det, rec, dictionary)
        if args.japanese:
            rec_ja, dict_ja = args.out / "ocr_rec_japan.onnx", args.out / "ocr_dict_japan.txt"
            to_onnx(args.rec_ja_url, rec_ja, work)
            download(args.dict_ja_url, dict_ja)
            check(None, rec_ja, dict_ja)

    for f in sorted(args.out.glob("ocr_*")):
        print(f"{f.name:22s} {f.stat().st_size / 1e6:6.2f} MB  sha256 {hashlib.sha256(f.read_bytes()).hexdigest()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
