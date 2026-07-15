#!/usr/bin/env python3
"""Validate Grayin model-training setup without requiring ML dependencies."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
from pathlib import Path

from prompt_contract import PROMPT_CONTRACT_VERSION, parse_grounded_answer, render_evidence_pack_prompt
from run_grounded_eval import score_fixture


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

REQUIRED_LANGUAGES = {"en", "ko", "ja"}
REQUIRED_BENCHMARK_FAMILIES = {
    "yesterday_route",
    "daily_activity",
    "drinking_context",
    "family_call_missing",
    "meetings",
    "future_busyness",
    "food_photos",
    "historical_route",
    "non_agentic_action",
    "privacy_boundary",
}


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


def validate_policy_records(path: Path, expected_source_prefix: str) -> list[dict[str, object]]:
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
    return records


def normalized_text(value: str) -> str:
    return " ".join(value.casefold().split())


def validate_dataset_records(
    path: Path,
    records: list[dict[str, object]],
    split: str,
) -> None:
    expected_type = "training" if split == "train" else "evaluation"
    seen_record_ids: set[str] = set()
    seen_queries: set[str] = set()
    seen_prompt_hashes: set[str] = set()
    seen_evidence_ids: set[str] = set()
    seen_citation_ids: set[str] = set()
    coverage: set[tuple[str, str]] = set()

    for index, record in enumerate(records, start=1):
        label = f"{path}:{index}"
        record_id = record.get("id")
        if not isinstance(record_id, str) or not record_id:
            raise ValueError(f"{label}: id is missing")
        if record_id in seen_record_ids:
            raise ValueError(f"{label}: duplicate record id: {record_id}")
        seen_record_ids.add(record_id)
        if record.get("record_type") != expected_type:
            raise ValueError(f"{label}: record_type must be {expected_type}")
        language = record.get("language")
        family = record.get("benchmark_family")
        if language not in REQUIRED_LANGUAGES:
            raise ValueError(f"{label}: language is invalid")
        if family not in REQUIRED_BENCHMARK_FAMILIES:
            raise ValueError(f"{label}: benchmark_family is invalid")
        pair = (str(family), str(language))
        if pair in coverage:
            raise ValueError(f"{label}: duplicate benchmark family/language pair: {pair}")
        coverage.add(pair)
        if record.get("prompt_contract_version") != PROMPT_CONTRACT_VERSION:
            raise ValueError(f"{label}: prompt_contract_version is invalid")

        pack = record.get("evidence_pack")
        if not isinstance(pack, dict):
            raise ValueError(f"{label}: evidence_pack is missing")
        prompt = record.get("prompt")
        if prompt != render_evidence_pack_prompt(pack):
            raise ValueError(f"{label}: prompt is not aligned with EvidencePackPromptBuilder")
        query = pack.get("query")
        if not isinstance(query, str):
            raise ValueError(f"{label}: evidence_pack.query is invalid")
        normalized_query = normalized_text(query)
        if normalized_query in seen_queries:
            raise ValueError(f"{label}: duplicate normalized query")
        seen_queries.add(normalized_query)
        prompt_hash = hashlib.sha256(str(prompt).encode("utf-8")).hexdigest()
        if prompt_hash in seen_prompt_hashes:
            raise ValueError(f"{label}: duplicate rendered prompt")
        seen_prompt_hashes.add(prompt_hash)

        for evidence in pack["evidence_items"]:
            evidence_id = evidence["id"]
            if evidence_id in seen_evidence_ids:
                raise ValueError(f"{label}: duplicate dataset evidence id: {evidence_id}")
            seen_evidence_ids.add(evidence_id)
        for citation in pack["citations"]:
            citation_id = citation["id"]
            if citation_id in seen_citation_ids:
                raise ValueError(f"{label}: duplicate dataset citation id: {citation_id}")
            seen_citation_ids.add(citation_id)

        reference_answer = record.get("reference_answer")
        if not isinstance(reference_answer, str):
            raise ValueError(f"{label}: reference_answer is missing")
        result = score_fixture(record, reference_answer)
        if not result.passed:
            raise ValueError(f"{label}: reference_answer failed: {'; '.join(result.errors)}")

        messages = record.get("messages")
        if split == "train":
            expected_messages = [
                {"role": "user", "content": prompt},
                {"role": "assistant", "content": reference_answer},
            ]
            if messages != expected_messages:
                raise ValueError(f"{label}: training messages do not match prompt/answer contract")
        elif messages is not None:
            raise ValueError(f"{label}: held-out evaluation records must not contain training messages")

    expected_coverage = {
        (family, language)
        for family in REQUIRED_BENCHMARK_FAMILIES
        for language in REQUIRED_LANGUAGES
    }
    missing_coverage = expected_coverage - coverage
    if missing_coverage:
        raise ValueError(f"{path}: missing benchmark coverage: {sorted(missing_coverage)}")


def validate_no_train_eval_leakage(
    train_path: Path,
    train_records: list[dict[str, object]],
    eval_path: Path,
    eval_records: list[dict[str, object]],
) -> None:
    train_ids = {str(record["id"]) for record in train_records}
    eval_ids = {str(record["id"]) for record in eval_records}
    overlapping_ids = train_ids & eval_ids
    if overlapping_ids:
        raise ValueError(f"train/eval record id leakage: {sorted(overlapping_ids)}")

    def queries(records: list[dict[str, object]]) -> set[str]:
        return {
            normalized_text(str(record["evidence_pack"]["query"]))
            for record in records
        }

    overlapping_queries = queries(train_records) & queries(eval_records)
    if overlapping_queries:
        raise ValueError("train/eval normalized query leakage detected")

    def prompt_hashes(records: list[dict[str, object]]) -> set[str]:
        return {
            hashlib.sha256(str(record["prompt"]).encode("utf-8")).hexdigest()
            for record in records
        }

    overlapping_prompts = prompt_hashes(train_records) & prompt_hashes(eval_records)
    if overlapping_prompts:
        raise ValueError("train/eval rendered prompt leakage detected")

    def answer_bodies(records: list[dict[str, object]]) -> set[str]:
        return {
            normalized_text(parse_grounded_answer(str(record["reference_answer"])).answer)
            for record in records
        }

    overlapping_answers = answer_bodies(train_records) & answer_bodies(eval_records)
    if overlapping_answers:
        raise ValueError("train/eval reference answer leakage detected")

    train_evidence_ids = {
        str(evidence["id"])
        for record in train_records
        for evidence in record["evidence_pack"]["evidence_items"]
    }
    eval_evidence_ids = {
        str(evidence["id"])
        for record in eval_records
        for evidence in record["evidence_pack"]["evidence_items"]
    }
    if train_evidence_ids & eval_evidence_ids:
        raise ValueError("train/eval evidence id leakage detected")

    if train_path.resolve() == eval_path.resolve():
        raise ValueError("train and eval paths must differ")


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
    has_config = (path / "config.json").is_file()
    has_tokenizer = (path / "tokenizer.json").is_file() or (path / "tokenizer.model").is_file()
    has_weights = any(path.glob("*.safetensors")) or any(path.glob("*.bin"))
    return has_config and has_tokenizer and has_weights


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

    train_records = validate_policy_records(train_jsonl, "synthetic_train")
    eval_records = validate_policy_records(eval_path, "synthetic_eval")
    validate_dataset_records(train_jsonl, train_records, "train")
    validate_dataset_records(eval_path, eval_records, "eval")
    validate_no_train_eval_leakage(train_jsonl, train_records, eval_path, eval_records)

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
        f"train={len(train_records)} eval={len(eval_records)} "
        f"languages={len(REQUIRED_LANGUAGES)} families={len(REQUIRED_BENCHMARK_FAMILIES)} "
        f"reference_model={status}",
    )


if __name__ == "__main__":
    main()
