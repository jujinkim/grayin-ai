#!/usr/bin/env python3
"""Run held-out synthetic prompts through the local LiteRT-LM CLI release gate."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from prompt_contract import parse_grounded_answer
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
    PinnedTool,
    REPO_ROOT,
    RegularFileSnapshot,
    atomic_write_json,
    atomic_write_jsonl,
    load_release_config,
    offline_environment,
    read_json_object,
    read_regular_snapshot,
    remove_artifact_file,
    repo_relative,
    require_litertlm_file,
    require_new_artifact_paths,
    require_sha256,
    resolve_tool,
    run_bounded_command,
    sha256_file,
    tool_version,
    verify_regular_snapshot,
    verify_pinned_tool,
)
from run_grounded_eval import evaluate, parse_jsonl_bytes


DEFAULT_CONFIG = REPO_ROOT / "model-training/configs/grayin_gemma_lora.yaml"
MAX_CLI_OUTPUT_BYTES = 256 * 1024


def build_run_command(tool: Path, model_file: Path, prompt: str) -> list[str]:
    return [str(tool), "run", str(model_file), f"--prompt={prompt}"]


def extract_grounded_answer(output: str) -> str:
    normalized = output.replace("\r\n", "\n").replace("\r", "\n").strip()
    try:
        parse_grounded_answer(normalized)
    except ValueError as error:
        raise ValueError("LiteRT-LM stdout must be exactly one valid four-line answer") from error
    return normalized


def read_release_jsonl(path: Path, maximum_bytes: int) -> tuple[RegularFileSnapshot, list[dict[str, object]]]:
    snapshot = read_regular_snapshot(path, maximum_bytes)
    records = parse_jsonl_bytes(snapshot.data, str(path))
    verify_regular_snapshot(snapshot)
    return snapshot, records


def load_pinned_eval_fixtures(config) -> tuple[RegularFileSnapshot, list[dict[str, object]]]:
    snapshot, fixtures = read_release_jsonl(config.eval_fixtures, 8 * 1024 * 1024)
    if snapshot.sha256 != config.eval_fixture_sha256:
        raise ValueError("evaluation fixture file does not match the release-config SHA-256")
    if len(fixtures) != config.eval_fixture_count:
        raise ValueError("evaluation fixture count does not match the release config")
    return snapshot, fixtures


def run_command(command: list[str], timeout_seconds: int, pinned_tool: PinnedTool | None = None) -> str:
    result = run_bounded_command(
        command,
        timeout_seconds=timeout_seconds,
        max_stdout_bytes=MAX_CLI_OUTPUT_BYTES,
        max_stderr_bytes=MAX_CLI_OUTPUT_BYTES,
        environment=offline_environment(),
        pinned_tool=pinned_tool,
    )
    if result.returncode != 0:
        tail = result.stderr[-2048:].strip()
        raise RuntimeError(f"LiteRT-LM CLI failed with exit code {result.returncode}: {tail}")
    return result.stdout


def require_export_provenance(config) -> dict[str, object]:
    provenance = read_json_object(config.export_provenance_file)
    if provenance.get("schema_version") != "grayin-litert-export-provenance-v1":
        raise ValueError("export provenance schema is unsupported")
    if provenance.get("model_id") != config.model_id:
        raise ValueError("export provenance model id does not match release config")
    merge = provenance.get("merge_provenance")
    if not isinstance(merge, dict) or (
        merge.get("path") != repo_relative(config.merge_provenance_file)
        or merge.get("sha256") != sha256_file(config.merge_provenance_file)
    ):
        raise ValueError("export provenance does not bind the current merge provenance")
    exporter = provenance.get("exporter")
    if not isinstance(exporter, dict) or (
        exporter.get("name") != "litert-torch"
        or exporter.get("selected_quantization") != config.quantization
        or not isinstance(exporter.get("version_output"), str)
        or not isinstance(exporter.get("executable_sha256"), str)
        or not isinstance(exporter.get("executable_identity"), dict)
    ):
        raise ValueError("exporter provenance is incomplete")
    require_sha256(exporter["executable_sha256"], "exporter executable digest")
    if (
        exporter["executable_identity"].get("path") != exporter.get("executable")
        or exporter["executable_identity"].get("sha256") != exporter.get("executable_sha256")
    ):
        raise ValueError("exporter executable identity is inconsistent")
    require_recorded_export_command(config, exporter)
    template = provenance.get("chat_template")
    if template != {
        "source_repo_id": TEMPLATE_REPO_ID,
        "source_revision": TEMPLATE_REVISION,
        "source_file": TEMPLATE_FILE_NAME,
        "source_url": TEMPLATE_RAW_URL,
        "path": repo_relative(config.chat_template_override_path),
        "size_bytes": TEMPLATE_SIZE_BYTES,
        "sha256": TEMPLATE_SHA256,
    }:
        raise ValueError("export provenance does not bind the pinned chat template")
    model = provenance.get("model_file")
    if not isinstance(model, dict):
        raise ValueError("export provenance model metadata is missing")
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
        raise ValueError("LiteRT-LM file does not match export provenance")
    return provenance


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument("--tool", default="litert-lm")
    parser.add_argument("--expected-tool-sha256")
    parser.add_argument("--limit", type=int)
    parser.add_argument("--timeout-seconds", type=int, default=300)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    config = load_release_config(args.config)
    require_new_artifact_paths((config.eval_predictions_file, config.eval_summary_file))
    fixture_snapshot, fixtures = load_pinned_eval_fixtures(config)
    for fixture in fixtures:
        prompt = fixture.get("prompt")
        if (
            not isinstance(prompt, str)
            or "\x00" in prompt
            or len(prompt.encode("utf-8")) > 64 * 1024
        ):
            raise ValueError(f"{fixture.get('id', '<unknown>')}: prompt exceeds the CLI boundary")
    if args.limit is not None:
        if args.limit <= 0 or args.limit > len(fixtures):
            raise ValueError("limit must be within the fixture count")
        fixtures = fixtures[: args.limit]
    if args.timeout_seconds <= 0 or args.timeout_seconds > 3600:
        raise ValueError("timeout must be between 1 and 3600 seconds")
    if args.dry_run:
        print(
            json.dumps(
                {
                    "status": "planned",
                    "command_template": build_run_command(
                        Path(args.tool),
                        config.litert_output_file,
                        "<synthetic fixture prompt>",
                    ),
                    "first_fixture_id": fixtures[0]["id"],
                    "fixture_count": len(fixtures),
                    "fixture_sha256": fixture_snapshot.sha256,
                    "scope": "full" if args.limit is None else "smoke",
                    "predictions_file": repo_relative(config.eval_predictions_file),
                    "summary_file": repo_relative(config.eval_summary_file),
                    "network": "local paths with model-library offline flags",
                },
                indent=2,
                sort_keys=True,
            ),
        )
        return

    export_provenance = require_export_provenance(config)
    export_provenance_sha256 = sha256_file(config.export_provenance_file)
    fixture_size_bytes = fixture_snapshot.size_bytes
    fixture_sha256 = fixture_snapshot.sha256
    executable = resolve_tool(args.tool, args.expected_tool_sha256 or "")
    version = tool_version(executable)
    predictions: list[dict[str, str]] = []
    for fixture in fixtures:
        verify_pinned_tool(executable, verify_hash=False)
        command = build_run_command(executable.path, config.litert_output_file, fixture["prompt"])
        answer = extract_grounded_answer(run_command(command, args.timeout_seconds, executable))
        verify_pinned_tool(executable, verify_hash=False)
        predictions.append({"id": fixture["id"], "answer": answer})
    verify_pinned_tool(executable)
    if (
        require_export_provenance(config) != export_provenance
        or sha256_file(config.export_provenance_file) != export_provenance_sha256
    ):
        raise ValueError("export provenance or model changed during LiteRT-LM evaluation")
    verify_regular_snapshot(fixture_snapshot)
    summary = evaluate(fixtures, {record["id"]: record["answer"] for record in predictions})
    if summary["failed"] != 0:
        raise ValueError("LiteRT-LM predictions failed the deterministic grounded-answer gate")
    scope = "full" if args.limit is None else "smoke"
    summary["release_gate"] = {
        "passed": scope == "full",
        "scope": scope,
        "fixture_path": repo_relative(config.eval_fixtures),
        "fixture_size_bytes": fixture_size_bytes,
        "fixture_sha256": fixture_sha256,
        "model_path": repo_relative(config.litert_output_file),
        "model_sha256": export_provenance["model_file"]["sha256"],
        "export_provenance_path": repo_relative(config.export_provenance_file),
        "export_provenance_sha256": export_provenance_sha256,
        "runner": {
            "name": "litert-lm",
            "executable": str(executable.path),
            "executable_sha256": executable.sha256,
            "executable_identity": executable.as_dict(),
            "version_output": version,
        },
        "network": {
            "inputs": "local model and synthetic fixtures only",
            "model_library_offline_flags": True,
        },
    }
    prediction_size = sum(
        len(json.dumps(record, ensure_ascii=False, sort_keys=True).encode("utf-8")) + 1
        for record in predictions
    )
    if prediction_size > 8 * 1024 * 1024:
        raise ValueError("prediction output exceeds the release boundary")
    predictions_published = False
    try:
        atomic_write_jsonl(config.eval_predictions_file, predictions)
        predictions_published = True
        prediction_snapshot, published_predictions = read_release_jsonl(
            config.eval_predictions_file,
            8 * 1024 * 1024,
        )
        if published_predictions != predictions:
            raise ValueError("published prediction JSONL differs from the evaluated predictions")
        summary["release_gate"]["predictions_path"] = repo_relative(config.eval_predictions_file)
        summary["release_gate"]["predictions_size_bytes"] = prediction_snapshot.size_bytes
        summary["release_gate"]["predictions_sha256"] = prediction_snapshot.sha256
        verify_regular_snapshot(prediction_snapshot)
        atomic_write_json(config.eval_summary_file, summary)
    finally:
        summary_complete = False
        if predictions_published:
            try:
                summary_complete = read_json_object(config.eval_summary_file) == summary
            except (FileNotFoundError, ValueError):
                summary_complete = False
        if predictions_published and not summary_complete:
            remove_artifact_file(config.eval_predictions_file)
    if scope == "full":
        print(f"LiteRT-LM full release gate passed for {len(fixtures)} synthetic fixtures")
    else:
        print(f"LiteRT-LM smoke checks passed for {len(fixtures)} fixtures; release gate remains false")


if __name__ == "__main__":
    main()
