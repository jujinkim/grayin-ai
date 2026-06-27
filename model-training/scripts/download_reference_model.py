#!/usr/bin/env python3
"""Download Gemma reference model into ignored local training cache."""

from __future__ import annotations

import argparse
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONFIG = REPO_ROOT / "model-training/configs/grayin_gemma_lora.yaml"

REQUIRED_KEYS = (
    "source_model_id",
    "source_model_revision",
    "base_model_path",
)

ALLOW_PATTERNS = (
    "config.json",
    "generation_config.json",
    "chat_template.jinja",
    "processor_config.json",
    "tokenizer.json",
    "tokenizer.model",
    "tokenizer_config.json",
    "special_tokens_map.json",
    "model.safetensors",
    "model-*.safetensors",
    "*.safetensors.index.json",
)


def read_scalar_config(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or ":" not in line:
            continue
        key, value = line.split(":", 1)
        key = key.strip()
        value = value.strip()
        if key in REQUIRED_KEYS:
            values[key] = value
    missing = [key for key in REQUIRED_KEYS if key not in values]
    if missing:
        raise ValueError(f"Missing config keys: {', '.join(missing)}")
    return values


def repo_path(value: str) -> Path:
    path = Path(value)
    if path.is_absolute():
        return path
    return REPO_ROOT / path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    config_path = args.config if args.config.is_absolute() else REPO_ROOT / args.config
    values = read_scalar_config(config_path)
    repo_id = values["source_model_id"]
    revision = values["source_model_revision"]
    destination = repo_path(values["base_model_path"])

    if args.dry_run:
        print(f"would download {repo_id}@{revision} to {destination.relative_to(REPO_ROOT)}")
        return

    from huggingface_hub import snapshot_download

    destination.mkdir(parents=True, exist_ok=True)
    snapshot_download(
        repo_id=repo_id,
        revision=revision,
        local_dir=str(destination),
        allow_patterns=list(ALLOW_PATTERNS),
    )
    print(f"downloaded {repo_id}@{revision} to {destination.relative_to(REPO_ROOT)}")


if __name__ == "__main__":
    main()
