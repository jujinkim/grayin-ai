#!/usr/bin/env python3
"""Fail when model weights or private training data are tracked by git."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]

DISALLOWED_PREFIXES = (
    "model-training/reference-models/",
    "model-training/outputs/",
    "model-training/checkpoints/",
    "model-training/data/private/",
    "model-training/data/raw/",
)

ALLOWED_PLACEHOLDERS = {
    "model-training/reference-models/.gitkeep",
    "model-training/outputs/.gitkeep",
    "model-training/checkpoints/.gitkeep",
    "model-training/data/private/.gitkeep",
    "model-training/data/raw/.gitkeep",
}

DISALLOWED_EXTENSIONS = {
    ".safetensors",
    ".bin",
    ".gguf",
    ".pt",
    ".pth",
    ".ckpt",
    ".onnx",
    ".tflite",
    ".litertlm",
    ".task",
    ".mlmodel",
}


def tracked_files() -> list[str]:
    result = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=REPO_ROOT,
        check=True,
        capture_output=True,
    )
    return [item.decode("utf-8") for item in result.stdout.split(b"\0") if item]


def violations(paths: list[str]) -> list[str]:
    bad: list[str] = []
    for path in paths:
        if path in ALLOWED_PLACEHOLDERS:
            continue
        if path.startswith(DISALLOWED_PREFIXES):
            bad.append(path)
            continue
        if path.startswith("model-training/") and Path(path).suffix.lower() in DISALLOWED_EXTENSIONS:
            bad.append(path)
    return bad


def main() -> None:
    bad = violations(tracked_files())
    if bad:
        print("Tracked model/private artifacts found:", file=sys.stderr)
        for path in bad:
            print(f"- {path}", file=sys.stderr)
        sys.exit(1)
    print("artifact policy ok")


if __name__ == "__main__":
    main()
