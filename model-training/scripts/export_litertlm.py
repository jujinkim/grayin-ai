#!/usr/bin/env python3
"""Export merged local Gemma weights with the official LiteRT Torch command."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import tempfile

from download_litert_template import (
    TEMPLATE_FILE_NAME,
    TEMPLATE_REPO_ID,
    TEMPLATE_REVISION,
    TEMPLATE_RAW_URL,
    TEMPLATE_SHA256,
    TEMPLATE_SIZE_BYTES,
    verify_template,
)
from release_pipeline import (
    REPO_ROOT,
    atomic_write_json,
    fsync_file,
    load_release_config,
    offline_environment,
    publish_file_no_replace,
    read_json_object,
    regular_file_size,
    remove_artifact_file,
    remove_artifact_tree,
    repo_relative,
    require_litertlm_file,
    require_new_artifact_paths,
    resolve_tool,
    run_bounded_command,
    secure_artifact_parent,
    sha256_file,
    tool_version,
    tree_digest,
    verify_pinned_tool,
)


DEFAULT_CONFIG = REPO_ROOT / "model-training/configs/grayin_gemma_lora.yaml"


def build_export_command(
    tool: Path,
    merged_model: Path,
    output_dir: Path,
    template: Path,
    quantization: str,
) -> list[str]:
    return [
        str(tool),
        "export_hf",
        f"--model={merged_model}",
        f"--output_dir={output_dir}",
        f"--quantization_recipe={quantization}",
        "--externalize_embedder",
        f"--jinja_chat_template_override={template}",
    ]


def require_recorded_export_command(config, exporter: dict[str, object]) -> None:
    command = exporter.get("command")
    executable = exporter.get("executable")
    if not isinstance(command, list) or not isinstance(executable, str) or len(command) != 7:
        raise ValueError("recorded LiteRT Torch command is incomplete")
    expected_fixed = [
        executable,
        "export_hf",
        f"--model={config.merged_output_dir}",
    ]
    if command[:3] != expected_fixed or command[4:] != [
        f"--quantization_recipe={config.quantization}",
        "--externalize_embedder",
        f"--jinja_chat_template_override={config.chat_template_override_path}",
    ]:
        raise ValueError("recorded LiteRT Torch command differs from the reviewed command")
    output_argument = command[3]
    if not isinstance(output_argument, str) or not output_argument.startswith("--output_dir="):
        raise ValueError("recorded LiteRT Torch output directory is invalid")
    output_dir = Path(output_argument.removeprefix("--output_dir="))
    if (
        output_dir.parent != config.litert_output_file.parent
        or not output_dir.name.startswith(f".{config.litert_output_file.stem}.")
        or not output_dir.name.endswith(".tmp")
    ):
        raise ValueError("recorded LiteRT Torch staging directory is outside the release boundary")


def require_merge_provenance(config) -> tuple[dict[str, object], dict[str, object]]:
    provenance = read_json_object(config.merge_provenance_file)
    if provenance.get("schema_version") != "grayin-lora-merge-provenance-v1":
        raise ValueError("merge provenance schema is unsupported")
    source = provenance.get("source_model")
    if not isinstance(source, dict) or any(
        source.get(key) != expected
        for key, expected in (
            ("id", config.source_model_id),
            ("revision", config.source_model_revision),
            ("path", repo_relative(config.base_model_path)),
        )
    ) or not isinstance(source.get("tree"), dict):
        raise ValueError("merge provenance source model does not match release config")
    adapter = provenance.get("adapter")
    if not isinstance(adapter, dict) or (
        adapter.get("path") != repo_relative(config.adapter_path)
        or not isinstance(adapter.get("tree"), dict)
    ):
        raise ValueError("merge provenance adapter does not match release config")
    corpus = provenance.get("training_corpus")
    if not isinstance(corpus, dict) or (
        corpus.get("path") != repo_relative(config.train_jsonl)
        or corpus.get("size_bytes") != regular_file_size(config.train_jsonl)
        or corpus.get("sha256") != sha256_file(config.train_jsonl)
    ):
        raise ValueError("merge provenance corpus does not match release config")
    training_config = provenance.get("training_config")
    if not isinstance(training_config, dict) or (
        training_config.get("path") != repo_relative(config.config_path)
        or training_config.get("sha256") != sha256_file(config.config_path)
    ):
        raise ValueError("merge provenance config does not match the current release config")
    merged = provenance.get("merged_model")
    if not isinstance(merged, dict) or merged.get("path") != repo_relative(config.merged_output_dir):
        raise ValueError("merge provenance output path does not match release config")
    recorded_tree = merged.get("tree")
    actual_tree = tree_digest(config.merged_output_dir).as_dict()
    if not isinstance(recorded_tree, dict) or recorded_tree != actual_tree:
        raise ValueError("merged model tree does not match merge provenance")
    return provenance, actual_tree


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument("--tool", default="litert-torch")
    parser.add_argument("--expected-tool-sha256")
    parser.add_argument("--timeout-seconds", type=int, default=14_400)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    config = load_release_config(args.config)
    require_new_artifact_paths((config.litert_output_file, config.export_provenance_file))
    planned_output_dir = config.litert_output_file.parent / ".litert-export-staging"
    planned_command = build_export_command(
        Path(args.tool),
        config.merged_output_dir,
        planned_output_dir,
        config.chat_template_override_path,
        config.quantization,
    )
    if args.dry_run:
        print(
            json.dumps(
                {
                    "status": "planned",
                    "command": planned_command,
                    "expected_tool": "litert-torch (local install; version recorded at execution)",
                    "final_model_file": repo_relative(config.litert_output_file),
                    "provenance_file": repo_relative(config.export_provenance_file),
                    "network": "local paths with model-library offline flags",
                    "quantization": f"{config.quantization} (explicit required recipe)",
                },
                indent=2,
                sort_keys=True,
            ),
        )
        return

    merge_provenance, merged_tree = require_merge_provenance(config)
    merge_provenance_sha256 = sha256_file(config.merge_provenance_file)
    verify_template(config.chat_template_override_path)
    if args.timeout_seconds < 60 or args.timeout_seconds > 86_400:
        raise ValueError("export timeout must be between 60 and 86400 seconds")
    executable = resolve_tool(args.tool, args.expected_tool_sha256 or "")
    version = tool_version(executable)
    parent_fd = secure_artifact_parent(config.litert_output_file, create=True)
    os.close(parent_fd)
    staging = Path(
        tempfile.mkdtemp(
            prefix=f".{config.litert_output_file.stem}.",
            suffix=".tmp",
            dir=config.litert_output_file.parent,
        ),
    )
    published = False
    provenance: dict[str, object] | None = None
    command = build_export_command(
        executable.path,
        config.merged_output_dir,
        staging,
        config.chat_template_override_path,
        config.quantization,
    )
    try:
        verify_pinned_tool(executable)
        result = run_bounded_command(
            command,
            timeout_seconds=args.timeout_seconds,
            max_stdout_bytes=4 * 1024 * 1024,
            max_stderr_bytes=4 * 1024 * 1024,
            environment=offline_environment(),
            pinned_tool=executable,
        )
        verify_pinned_tool(executable)
        if result.returncode != 0:
            tail = (result.stderr or result.stdout)[-4096:].strip()
            raise RuntimeError(f"LiteRT Torch export failed with exit code {result.returncode}: {tail}")
        current_merge_provenance, current_merged_tree = require_merge_provenance(config)
        if (
            current_merge_provenance != merge_provenance
            or current_merged_tree != merged_tree
            or sha256_file(config.merge_provenance_file) != merge_provenance_sha256
        ):
            raise ValueError("merged model provenance changed during LiteRT export")
        verify_template(config.chat_template_override_path)
        generated = staging / "model.litertlm"
        container = require_litertlm_file(generated)
        model_sha256 = sha256_file(generated)
        os.chmod(generated, 0o600, follow_symlinks=False)
        fsync_file(generated)
        publish_file_no_replace(generated, config.litert_output_file)
        published = True
        published_container = require_litertlm_file(config.litert_output_file)
        published_sha256 = sha256_file(config.litert_output_file)
        if published_container != container or published_sha256 != model_sha256:
            raise ValueError("published LiteRT-LM file differs from the validated export")
        provenance = {
            "schema_version": "grayin-litert-export-provenance-v1",
            "model_id": config.model_id,
            "merged_model": {
                "path": repo_relative(config.merged_output_dir),
                "tree": merged_tree,
            },
            "merge_provenance": {
                "path": repo_relative(config.merge_provenance_file),
                "sha256": merge_provenance_sha256,
            },
            "chat_template": {
                "source_repo_id": TEMPLATE_REPO_ID,
                "source_revision": TEMPLATE_REVISION,
                "source_file": TEMPLATE_FILE_NAME,
                "source_url": TEMPLATE_RAW_URL,
                "path": repo_relative(config.chat_template_override_path),
                "size_bytes": TEMPLATE_SIZE_BYTES,
                "sha256": TEMPLATE_SHA256,
            },
            "exporter": {
                "name": "litert-torch",
                "executable": str(executable.path),
                "executable_sha256": executable.sha256,
                "executable_identity": executable.as_dict(),
                "version_output": version,
                "command": command,
                "selected_quantization": config.quantization,
            },
            "model_file": {
                "path": repo_relative(config.litert_output_file),
                "size_bytes": container["size_bytes"],
                "sha256": model_sha256,
                **{key: value for key, value in container.items() if key != "size_bytes"},
            },
            "network": {
                "inputs": "local paths only",
                "model_library_offline_flags": True,
            },
        }
        atomic_write_json(config.export_provenance_file, provenance)
    finally:
        if staging.exists():
            remove_artifact_tree(staging)
        metadata_complete = False
        if published and provenance is not None:
            try:
                metadata_complete = read_json_object(config.export_provenance_file) == provenance
            except (FileNotFoundError, ValueError):
                metadata_complete = False
        if published and not metadata_complete:
            remove_artifact_file(config.litert_output_file)
    print(f"exported model and wrote provenance to {repo_relative(config.export_provenance_file)}")


if __name__ == "__main__":
    main()
