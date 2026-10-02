#!/usr/bin/env python3
"""Download card images for training the embedder.

Reads any JSON catalog (a Scryfall / Pokemon TCG API / YGOPRODeck dump, a community card list, or the app's own
catalog format), finds the card objects, and saves one image per card as

    <out>/<game>/<setCode>__<number>[__<printTag>].jpg

Only use sources whose terms allow this, keep the request rate polite, and do not redistribute the images.

    python fetch_images.py --catalog scryfall-default-cards.json --game mtg --out images --limit 5000
    python fetch_images.py --catalog https://example.com/cards.json --game onepiece --out images
"""
from __future__ import annotations

import argparse
import concurrent.futures as cf
import json
import re
import sys
import time
from pathlib import Path

import requests

NAME_KEYS = ["nickname", "name", "cardname", "title"]
NUMBER_KEYS = ["cardnumber", "number", "collectornumber", "setnumber", "localid", "cardid", "code", "id"]
SET_KEYS = ["setcode", "setid", "set", "series", "expansion", "packid"]
IMAGE_KEYS = ["imageurl", "image", "img", "imgurl", "cardimage", "faceurl", "picture", "imguri"]


def norm(key: str) -> str:
    return re.sub(r"[^a-z0-9]", "", key.lower())


def lookup(obj: dict, keys: list[str]):
    table = {norm(k): v for k, v in obj.items()}
    for key in keys:
        value = table.get(key)
        if value not in (None, "", []):
            return value
    return None


def image_of(obj: dict) -> str | None:
    value = lookup(obj, IMAGE_KEYS)
    if isinstance(value, dict):
        value = lookup(value, ["small", "normal", "large", "png", "thumbnail"])
    if value is None:  # Scryfall / Pokemon TCG API keep images in nested objects
        nested = lookup(obj, ["imageuris", "images"])
        if isinstance(nested, dict):
            value = lookup(nested, ["small", "normal", "large", "png"])
        faces = lookup(obj, ["cardfaces"])
        if value is None and isinstance(faces, list) and faces and isinstance(faces[0], dict):
            sub = lookup(faces[0], ["imageuris"])
            if isinstance(sub, dict):
                value = lookup(sub, ["small", "normal", "large", "png"])
    if isinstance(value, str) and value.startswith(("http://", "https://")):
        return value.replace("http://", "https://", 1)
    return None


def find_cards(node, depth=0):
    """Yield card-like dicts (a name plus a number or an image) anywhere in a JSON tree."""
    if depth > 7:
        return
    if isinstance(node, list):
        for item in node:
            yield from find_cards(item, depth + 1)
    elif isinstance(node, dict):
        name = lookup(node, NAME_KEYS)
        if isinstance(name, str) and name.strip() and (lookup(node, NUMBER_KEYS) is not None or image_of(node)):
            yield node
        else:
            for value in node.values():
                yield from find_cards(value, depth + 1)


def safe(text: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]+", "-", str(text)).strip("-")[:48] or "x"


def load_json(source: str):
    if source.startswith(("http://", "https://")):
        response = requests.get(source, timeout=120, headers={"User-Agent": "tcg-scanner-model-tools/1.0"})
        response.raise_for_status()
        return response.json()
    with open(source, "r", encoding="utf-8") as handle:
        return json.load(handle)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--catalog", required=True, help="path or https URL of a JSON catalog")
    parser.add_argument("--game", required=True, help="game folder name, e.g. pokemon, mtg, onepiece")
    parser.add_argument("--out", default="images")
    parser.add_argument("--limit", type=int, default=0, help="max images (0 = all)")
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--delay", type=float, default=0.05, help="seconds between requests per worker")
    args = parser.parse_args()

    root = load_json(args.catalog)
    # Scryfall bulk descriptor: follow the download URI once.
    if isinstance(root, dict) and "download_uri" in root:
        root = load_json(root["download_uri"])

    out_dir = Path(args.out) / args.game
    out_dir.mkdir(parents=True, exist_ok=True)

    jobs: dict[str, str] = {}
    for card in find_cards(root):
        url = image_of(card)
        if not url:
            continue
        number = lookup(card, NUMBER_KEYS)
        set_code = lookup(card, SET_KEYS)
        if isinstance(set_code, dict):
            set_code = lookup(set_code, ["id", "code"])
        stem = f"{safe(set_code or 'set')}__{safe(number or lookup(card, NAME_KEYS))}"
        n, base = 1, stem
        while stem in jobs:  # same set + number twice (alternate arts): keep both
            n += 1
            stem = f"{base}__{n}"
        jobs[stem] = url
        if args.limit and len(jobs) >= args.limit:
            break
    print(f"{len(jobs)} images to fetch into {out_dir}")

    session = requests.Session()
    session.headers["User-Agent"] = "tcg-scanner-model-tools/1.0"

    def fetch(item):
        stem, url = item
        target = out_dir / f"{stem}.jpg"
        if target.exists() and target.stat().st_size > 0:
            return True
        for attempt in range(3):
            try:
                response = session.get(url, timeout=30)
                response.raise_for_status()
                target.write_bytes(response.content)
                time.sleep(args.delay)
                return True
            except requests.RequestException:
                time.sleep(1.5 * (attempt + 1))
        return False

    ok = 0
    with cf.ThreadPoolExecutor(max_workers=args.workers) as pool:
        for i, success in enumerate(pool.map(fetch, jobs.items()), 1):
            ok += bool(success)
            if i % 200 == 0:
                print(f"  {i}/{len(jobs)}")
    print(f"done: {ok}/{len(jobs)} downloaded")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
