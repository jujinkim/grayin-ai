#!/usr/bin/env python3
"""Write metadata for a local training artifact without committing weights."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for block in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-file", required=True, type=Path)
    parser.add_argument("--model-id", required=True)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()

    if not args.model_file.is_file():
        raise FileNotFoundError(args.model_file)

    manifest = {
        "model_id": args.model_id,
        "file_name": args.model_file.name,
        "size_bytes": args.model_file.stat().st_size,
        "sha256": sha256(args.model_file),
        "git_policy": "model file ignored; manifest may be copied into release docs only after review",
    }

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"wrote manifest to {args.out}")


if __name__ == "__main__":
    main()
