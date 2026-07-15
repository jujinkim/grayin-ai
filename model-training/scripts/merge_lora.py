#!/usr/bin/env python3
"""Merge a local LoRA adapter into pinned Gemma weights from local-only inputs."""

from __future__ import annotations

import argparse
import importlib.metadata
import json
import os
from pathlib import Path
import platform
import tempfile

from release_pipeline import (
    REPO_ROOT,
    atomic_write_json,
    fsync_tree,
    harden_tree_permissions,
    load_release_config,
    offline_environment,
    publish_directory_no_replace,
    read_json_object,
    regular_file_size,
    remove_artifact_tree,
    repo_relative,
    require_new_artifact_paths,
    require_sha256,
    secure_artifact_parent,
    sha256_file,
    tree_digest,
)


DEFAULT_CONFIG = REPO_ROOT / "model-training/configs/grayin_gemma_lora.yaml"


def validate_gemma4_base(path: Path) -> None:
    metadata = read_json_object(path / "config.json")
    architectures = metadata.get("architectures")
    if metadata.get("model_type") != "gemma4" and (
        not isinstance(architectures, list) or "Gemma4ForCausalLM" not in architectures
    ):
        raise ValueError("base model config is not Gemma4ForCausalLM")
    if not list(path.glob("*.safetensors")):
        raise ValueError("base model has no safetensors weights")


def validate_adapter(path: Path, expected_base_references: set[str]) -> None:
    metadata = read_json_object(path / "adapter_config.json")
    if metadata.get("peft_type") != "LORA":
        raise ValueError("adapter is not a LoRA adapter")
    if metadata.get("base_model_name_or_path") not in expected_base_references:
        raise ValueError("adapter base-model reference does not match the configured Gemma base")
    if not list(path.glob("*.safetensors")):
        raise ValueError("adapter has no safetensors weights")


def package_versions() -> dict[str, str]:
    return {
        package: importlib.metadata.version(package)
        for package in ("torch", "transformers", "peft", "safetensors")
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument("--expected-base-sha256")
    parser.add_argument("--expected-adapter-sha256")
    parser.add_argument("--source-date-epoch", type=int)
    parser.add_argument("--device-map", choices=("cpu", "auto", "cuda"), default="cpu")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--print-input-digests", action="store_true")
    args = parser.parse_args()

    config = load_release_config(args.config)
    require_new_artifact_paths((config.merged_output_dir, config.merge_provenance_file))
    plan = {
        "status": "planned",
        "operation": "merge LoRA into local Gemma 4 base",
        "source_model_id": config.source_model_id,
        "source_model_revision": config.source_model_revision,
        "base_model_path": repo_relative(config.base_model_path),
        "adapter_path": repo_relative(config.adapter_path),
        "merged_output_dir": repo_relative(config.merged_output_dir),
        "provenance_file": repo_relative(config.merge_provenance_file),
        "network": "local paths with model-library offline flags",
    }
    if args.dry_run:
        print(json.dumps(plan, indent=2, sort_keys=True))
        return

    validate_gemma4_base(config.base_model_path)
    validate_adapter(
        config.adapter_path,
        {
            str(config.base_model_path),
            repo_relative(config.base_model_path),
            config.source_model_id,
        },
    )
    base_digest = tree_digest(config.base_model_path)
    adapter_digest = tree_digest(config.adapter_path)
    if args.print_input_digests:
        print(
            json.dumps(
                {"base_model": base_digest.as_dict(), "adapter": adapter_digest.as_dict()},
                indent=2,
                sort_keys=True,
            ),
        )
        return

    expected_base = require_sha256(args.expected_base_sha256 or "", "expected base digest")
    expected_adapter = require_sha256(args.expected_adapter_sha256 or "", "expected adapter digest")
    if base_digest.sha256 != expected_base:
        raise ValueError("base model tree does not match the operator-pinned digest")
    if adapter_digest.sha256 != expected_adapter:
        raise ValueError("adapter tree does not match the operator-pinned digest")
    if args.source_date_epoch is None or args.source_date_epoch <= 0:
        raise ValueError("source date epoch must be positive")
    training_corpus = {
        "path": repo_relative(config.train_jsonl),
        "size_bytes": regular_file_size(config.train_jsonl),
        "sha256": sha256_file(config.train_jsonl),
    }
    training_config = {
        "path": repo_relative(config.config_path),
        "sha256": sha256_file(config.config_path),
    }

    environment = offline_environment()
    environment["SOURCE_DATE_EPOCH"] = str(args.source_date_epoch)
    os.environ.clear()
    os.environ.update(environment)

    import torch
    from peft import PeftModel
    from transformers import AutoModelForCausalLM, AutoTokenizer

    torch.manual_seed(42)
    parent_fd = secure_artifact_parent(config.merged_output_dir, create=True)
    os.close(parent_fd)
    staging = Path(
        tempfile.mkdtemp(
            prefix=f".{config.merged_output_dir.name}.",
            suffix=".tmp",
            dir=config.merged_output_dir.parent,
        ),
    )
    published = False
    provenance: dict[str, object] | None = None
    try:
        base_model = AutoModelForCausalLM.from_pretrained(
            str(config.base_model_path),
            local_files_only=True,
            trust_remote_code=False,
            dtype=torch.bfloat16,
            device_map=args.device_map,
        )
        tokenizer = AutoTokenizer.from_pretrained(
            str(config.base_model_path),
            local_files_only=True,
            trust_remote_code=False,
        )
        adapter_model = PeftModel.from_pretrained(
            base_model,
            str(config.adapter_path),
            local_files_only=True,
        )
        merged_model = adapter_model.merge_and_unload(safe_merge=True)
        merged_model.eval()
        merged_model.save_pretrained(
            str(staging),
            safe_serialization=True,
            max_shard_size="4GB",
        )
        tokenizer.save_pretrained(str(staging))
        harden_tree_permissions(staging)
        validate_gemma4_base(staging)
        merged_digest = tree_digest(staging)
        if tree_digest(config.base_model_path) != base_digest:
            raise ValueError("base model tree changed during the merge operation")
        if tree_digest(config.adapter_path) != adapter_digest:
            raise ValueError("adapter tree changed during the merge operation")
        if (
            regular_file_size(config.train_jsonl) != training_corpus["size_bytes"]
            or sha256_file(config.train_jsonl) != training_corpus["sha256"]
        ):
            raise ValueError("training corpus changed during the merge operation")
        if sha256_file(config.config_path) != training_config["sha256"]:
            raise ValueError("training config changed during the merge operation")
        fsync_tree(staging)
        publish_directory_no_replace(staging, config.merged_output_dir, merged_digest)
        published = True
        provenance = {
            "schema_version": "grayin-lora-merge-provenance-v1",
            "run_name": config.run_name,
            "source_date_epoch": args.source_date_epoch,
            "source_model": {
                "id": config.source_model_id,
                "revision": config.source_model_revision,
                "path": repo_relative(config.base_model_path),
                "tree": base_digest.as_dict(),
            },
            "adapter": {
                "path": repo_relative(config.adapter_path),
                "tree": adapter_digest.as_dict(),
            },
            "training_corpus": training_corpus,
            "training_config": training_config,
            "merged_model": {
                "path": repo_relative(config.merged_output_dir),
                "tree": merged_digest.as_dict(),
            },
            "tool_versions": package_versions(),
            "execution": {
                "python_version": platform.python_version(),
                "platform": platform.platform(),
                "device_map": args.device_map,
                "torch_cuda_version": torch.version.cuda,
                "cuda_available": torch.cuda.is_available(),
            },
            "network": {
                "inputs": "local paths only",
                "model_library_offline_flags": True,
            },
        }
        atomic_write_json(config.merge_provenance_file, provenance)
    finally:
        if staging.exists():
            remove_artifact_tree(staging)
        metadata_complete = False
        if published and provenance is not None:
            try:
                metadata_complete = read_json_object(config.merge_provenance_file) == provenance
            except (FileNotFoundError, ValueError):
                metadata_complete = False
        if published and not metadata_complete:
            remove_artifact_tree(config.merged_output_dir)
    print(f"merged model and wrote provenance to {repo_relative(config.merge_provenance_file)}")


if __name__ == "__main__":
    main()
