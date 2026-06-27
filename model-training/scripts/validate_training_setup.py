#!/usr/bin/env python3
"""Validate Grayin model-training setup without requiring ML dependencies."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONFIG = REPO_ROOT / "model-training/configs/grayin_gemma_lora.yaml"
DEFAULT_EVAL = REPO_ROOT / "model-training/data/synthetic/grayin_eval.jsonl"

REQUIRED_SCALAR_KEYS = (
    "source_model_id",
    "source_model_revision",
    "base_model_path",
    "train_jsonl",
    "output_dir",
    "merged_output_dir",
    "litert_output_file",
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
        if key in REQUIRED_SCALAR_KEYS:
            values[key] = value
    missing = [key for key in REQUIRED_SCALAR_KEYS if key not in values]
    if missing:
        raise ValueError(f"Missing config keys: {', '.join(missing)}")
    return values


def repo_path(value: str) -> Path:
    path = Path(value)
    if path.is_absolute():
        return path
    return REPO_ROOT / path


def load_jsonl(path: Path) -> list[dict[str, object]]:
    records: list[dict[str, object]] = []
    with path.open("r", encoding="utf-8") as file:
        for line_number, line in enumerate(file, start=1):
            if not line.strip():
                continue
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError as error:
                raise ValueError(f"{path}:{line_number}: invalid JSON: {error}") from error
    if not records:
        raise ValueError(f"{path}: no records")
    return records


def validate_policy_records(path: Path, expected_source_prefix: str) -> int:
    records = load_jsonl(path)
    for index, record in enumerate(records, start=1):
        metadata = record.get("metadata")
        if not isinstance(metadata, dict):
            raise ValueError(f"{path}:{index}: metadata object missing")
        source = metadata.get("source")
        if not isinstance(source, str) or not source.startswith(expected_source_prefix):
            raise ValueError(f"{path}:{index}: metadata.source must start with {expected_source_prefix}")
        if metadata.get("raw_user_data") is not False:
            raise ValueError(f"{path}:{index}: metadata.raw_user_data must be false")
        if metadata.get("policy") != "zero-raw-retention":
            raise ValueError(f"{path}:{index}: metadata.policy must be zero-raw-retention")
    return len(records)


def git_check_ignore(path: Path) -> bool:
    result = subprocess.run(
        ["git", "check-ignore", "-q", str(path.relative_to(REPO_ROOT))],
        cwd=REPO_ROOT,
    )
    return result.returncode == 0


def validate_ignored(path: Path) -> None:
    probe = path
    if path.suffix == "":
        probe = path / "probe.safetensors"
    if not git_check_ignore(probe):
        raise ValueError(f"{probe.relative_to(REPO_ROOT)} is not ignored by git")


def reference_model_ready(path: Path) -> bool:
    if not path.is_dir():
        return False
    markers = (
        "config.json",
        "tokenizer.json",
        "tokenizer.model",
    )
    if any((path / marker).is_file() for marker in markers):
        return True
    return any(path.glob("*.safetensors")) or any(path.glob("*.bin"))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument("--eval-jsonl", type=Path, default=DEFAULT_EVAL)
    parser.add_argument("--require-reference-model", action="store_true")
    args = parser.parse_args()

    config_path = args.config if args.config.is_absolute() else REPO_ROOT / args.config
    eval_path = args.eval_jsonl if args.eval_jsonl.is_absolute() else REPO_ROOT / args.eval_jsonl

    values = read_scalar_config(config_path)
    source_model_id = values["source_model_id"]
    source_model_revision = values["source_model_revision"]
    base_model_path = repo_path(values["base_model_path"])
    train_jsonl = repo_path(values["train_jsonl"])
    output_dir = repo_path(values["output_dir"])
    merged_output_dir = repo_path(values["merged_output_dir"])
    litert_output_file = repo_path(values["litert_output_file"])

    train_count = validate_policy_records(train_jsonl, "synthetic")
    eval_count = validate_policy_records(eval_path, "synthetic_eval")

    validate_ignored(base_model_path)
    validate_ignored(output_dir)
    validate_ignored(merged_output_dir)
    validate_ignored(litert_output_file)

    ready = reference_model_ready(base_model_path)
    if args.require_reference_model and not ready:
        print(
            f"reference model missing or incomplete: {base_model_path.relative_to(REPO_ROOT)}",
            file=sys.stderr,
        )
        sys.exit(2)

    status = "ready" if ready else "missing"
    print(
        "training setup ok: "
        f"source_model={source_model_id}@{source_model_revision} "
        f"train={train_count} eval={eval_count} reference_model={status}",
    )


if __name__ == "__main__":
    main()
