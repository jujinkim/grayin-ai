#!/usr/bin/env python3
"""Create dependency-free local release metadata after the full LiteRT-LM gate."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from download_litert_template import (
    TEMPLATE_FILE_NAME,
    TEMPLATE_REPO_ID,
    TEMPLATE_REVISION,
    TEMPLATE_RAW_URL,
    TEMPLATE_SHA256,
    TEMPLATE_SIZE_BYTES,
)
from export_litertlm import require_recorded_export_command
from release_pipeline import (
    REPO_ROOT,
    RegularFileSnapshot,
    atomic_write_json,
    load_release_config,
    read_json_object,
    regular_file_size,
    repo_relative,
    require_litertlm_file,
    require_new_artifact_paths,
    require_sha256,
    resolve_tool,
    sha256_file,
    verify_regular_snapshot,
)
from run_grounded_eval import evaluate, prediction_map_from_records
from run_litert_eval import load_pinned_eval_fixtures, read_release_jsonl


DEFAULT_CONFIG = REPO_ROOT / "model-training/configs/grayin_gemma_lora.yaml"
SUPPORTED_LITERTLM_RUNTIME_VERSION = "0.13.1"
EXPECTED_METRICS = {
    "format_valid",
    "evidence_valid",
    "missing_valid",
    "confidence_valid",
    "content_valid",
}


def require_object(parent: dict[str, object], key: str) -> dict[str, object]:
    value = parent.get(key)
    if not isinstance(value, dict):
        raise ValueError(f"release metadata is missing {key}")
    return value


def validate_tool_metadata(metadata: dict[str, object], expected_name: str) -> None:
    if metadata.get("name") != expected_name:
        raise ValueError(f"{expected_name} provenance has the wrong tool name")
    executable = metadata.get("executable")
    digest = metadata.get("executable_sha256")
    version = metadata.get("version_output")
    identity = metadata.get("executable_identity")
    if (
        not isinstance(executable, str)
        or not Path(executable).is_absolute()
        or not isinstance(digest, str)
        or not isinstance(version, str)
        or not version
        or len(version.encode("utf-8")) > 4096
        or not isinstance(identity, dict)
    ):
        raise ValueError(f"{expected_name} provenance is incomplete")
    require_sha256(digest, f"{expected_name} executable digest")
    pinned = resolve_tool(executable, digest)
    if identity != pinned.as_dict():
        raise ValueError(f"{expected_name} executable identity is stale")


def validated_release_inputs(
    config,
) -> tuple[
    dict[str, object],
    dict[str, object],
    dict[str, object],
    RegularFileSnapshot,
    RegularFileSnapshot,
]:
    merge = read_json_object(config.merge_provenance_file)
    export = read_json_object(config.export_provenance_file)
    summary = read_json_object(config.eval_summary_file)
    if merge.get("schema_version") != "grayin-lora-merge-provenance-v1":
        raise ValueError("merge provenance schema is unsupported")
    if export.get("schema_version") != "grayin-litert-export-provenance-v1":
        raise ValueError("export provenance schema is unsupported")
    source = require_object(merge, "source_model")
    adapter = require_object(merge, "adapter")
    corpus = require_object(merge, "training_corpus")
    training_config = require_object(merge, "training_config")
    tool_versions = require_object(merge, "tool_versions")
    if (
        source.get("id") != config.source_model_id
        or source.get("revision") != config.source_model_revision
        or source.get("path") != repo_relative(config.base_model_path)
        or not isinstance(source.get("tree"), dict)
        or adapter.get("path") != repo_relative(config.adapter_path)
        or not isinstance(adapter.get("tree"), dict)
        or corpus.get("path") != repo_relative(config.train_jsonl)
        or corpus.get("size_bytes") != regular_file_size(config.train_jsonl)
        or corpus.get("sha256") != sha256_file(config.train_jsonl)
        or training_config.get("path") != repo_relative(config.config_path)
        or training_config.get("sha256") != sha256_file(config.config_path)
        or not tool_versions
    ):
        raise ValueError("merge provenance does not match the current release inputs")
    export_merge = require_object(export, "merge_provenance")
    if (
        export_merge.get("path") != repo_relative(config.merge_provenance_file)
        or export_merge.get("sha256") != sha256_file(config.merge_provenance_file)
    ):
        raise ValueError("export provenance does not bind the current merge provenance")
    if export.get("model_id") != config.model_id:
        raise ValueError("export provenance model id does not match release config")
    model = require_object(export, "model_file")
    container = require_litertlm_file(config.litert_output_file)
    if (
        set(model) != {
            "path",
            "size_bytes",
            "sha256",
            "container_major_version",
            "container_minor_version",
            "container_patch_version",
        }
        or model.get("path") != repo_relative(config.litert_output_file)
        or model.get("size_bytes") != container["size_bytes"]
        or model.get("sha256") != sha256_file(config.litert_output_file)
        or any(model.get(key) != value for key, value in container.items() if key != "size_bytes")
    ):
        raise ValueError("export provenance does not bind the current LiteRT-LM file")
    exporter = require_object(export, "exporter")
    validate_tool_metadata(exporter, "litert-torch")
    if (
        exporter.get("selected_quantization") != config.quantization
        or not isinstance(exporter.get("command"), list)
    ):
        raise ValueError("exporter provenance is incomplete")
    require_recorded_export_command(config, exporter)
    template = require_object(export, "chat_template")
    if template != {
        "source_repo_id": TEMPLATE_REPO_ID,
        "source_revision": TEMPLATE_REVISION,
        "source_file": TEMPLATE_FILE_NAME,
        "source_url": TEMPLATE_RAW_URL,
        "path": repo_relative(config.chat_template_override_path),
        "size_bytes": TEMPLATE_SIZE_BYTES,
        "sha256": TEMPLATE_SHA256,
    }:
        raise ValueError("chat template provenance is not the pinned release template")
    fixture_snapshot, fixtures = load_pinned_eval_fixtures(config)
    prediction_snapshot, prediction_records = read_release_jsonl(
        config.eval_predictions_file,
        8 * 1024 * 1024,
    )
    predictions = prediction_map_from_records(prediction_records, str(config.eval_predictions_file))
    recomputed = evaluate(fixtures, predictions)
    gate = summary.get("release_gate")
    if not isinstance(gate, dict) or (
        gate.get("passed") is not True
        or gate.get("scope") != "full"
        or gate.get("fixture_path") != repo_relative(config.eval_fixtures)
        or gate.get("fixture_size_bytes") != fixture_snapshot.size_bytes
        or gate.get("fixture_sha256") != fixture_snapshot.sha256
        or gate.get("model_path") != repo_relative(config.litert_output_file)
        or gate.get("model_sha256") != model.get("sha256")
        or gate.get("export_provenance_path") != repo_relative(config.export_provenance_file)
        or gate.get("export_provenance_sha256") != sha256_file(config.export_provenance_file)
        or gate.get("predictions_path") != repo_relative(config.eval_predictions_file)
        or gate.get("predictions_size_bytes") != prediction_snapshot.size_bytes
        or gate.get("predictions_sha256") != prediction_snapshot.sha256
    ):
        raise ValueError("full LiteRT-LM release gate provenance is missing or stale")
    runner = gate.get("runner")
    if not isinstance(runner, dict):
        raise ValueError("LiteRT-LM runner provenance is incomplete")
    validate_tool_metadata(runner, "litert-lm")
    if (
        summary.get("total") != len(fixtures)
        or summary.get("passed") != len(fixtures)
        or summary.get("failed") != 0
        or not isinstance(summary.get("metrics"), dict)
        or set(summary["metrics"]) != EXPECTED_METRICS
        or any(value != 1.0 for value in summary["metrics"].values())
        or summary.get("failures") != []
        or any(
            summary.get(key) != recomputed.get(key)
            for key in ("prompt_contract_version", "total", "passed", "failed", "metrics", "failures")
        )
    ):
        raise ValueError("full LiteRT-LM evaluation did not pass every deterministic metric")
    verify_regular_snapshot(fixture_snapshot)
    verify_regular_snapshot(prediction_snapshot)
    return merge, export, summary, fixture_snapshot, prediction_snapshot


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    config = load_release_config(args.config)
    require_new_artifact_paths((config.local_release_manifest_file,))
    if args.dry_run:
        print(
            json.dumps(
                {
                    "status": "planned",
                    "manifest": repo_relative(config.local_release_manifest_file),
                    "requires": [
                        repo_relative(config.merge_provenance_file),
                        repo_relative(config.export_provenance_file),
                        repo_relative(config.eval_predictions_file),
                        repo_relative(config.eval_summary_file),
                    ],
                    "remote_release": "not created by this command",
                },
                indent=2,
                sort_keys=True,
            ),
        )
        return

    merge, export, summary, fixture_snapshot, prediction_snapshot = validated_release_inputs(config)
    model = export["model_file"]
    manifest = {
        "schema_version": "grayin-local-model-release-v1",
        "local_release_gate": "passed",
        "remote_release": "not_created",
        "model": {
            **model,
            "model_id": config.model_id,
            "quantization": config.quantization,
            "required_litertlm_runtime_version": SUPPORTED_LITERTLM_RUNTIME_VERSION,
        },
        "source_model": merge["source_model"],
        "adapter": merge["adapter"],
        "training_corpus": merge["training_corpus"],
        "training_config": merge["training_config"],
        "merge": {
            "provenance_path": repo_relative(config.merge_provenance_file),
            "provenance_sha256": sha256_file(config.merge_provenance_file),
            "tool_versions": merge["tool_versions"],
        },
        "export": {
            "provenance_path": repo_relative(config.export_provenance_file),
            "provenance_sha256": sha256_file(config.export_provenance_file),
            "exporter": export["exporter"],
            "chat_template": export["chat_template"],
        },
        "evaluation": {
            "fixtures_path": repo_relative(config.eval_fixtures),
            "fixtures_sha256": fixture_snapshot.sha256,
            "predictions_path": repo_relative(config.eval_predictions_file),
            "predictions_sha256": prediction_snapshot.sha256,
            "summary_path": repo_relative(config.eval_summary_file),
            "summary_sha256": sha256_file(config.eval_summary_file),
            "total": summary["total"],
            "metrics": summary["metrics"],
            "runner": summary["release_gate"]["runner"],
        },
        "runtime_catalog_baseline": {
            "path": repo_relative(config.runtime_catalog_target),
            "sha256": sha256_file(config.runtime_catalog_target),
        },
        "remote_publication_requirements": [
            "immutable fixed HTTPS model URL",
            "reviewed model license URL",
            "canonical ECDSA P-256 signed manifest",
            "exact catalog size and SHA-256 metadata",
            "catalog model id and quantization label aligned with export provenance",
            "representative Android device acceptance",
        ],
    }
    verify_regular_snapshot(fixture_snapshot)
    verify_regular_snapshot(prediction_snapshot)
    atomic_write_json(config.local_release_manifest_file, manifest)
    print(f"local release gate manifest written to {repo_relative(config.local_release_manifest_file)}")


if __name__ == "__main__":
    main()
