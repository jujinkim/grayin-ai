#!/usr/bin/env python3
"""Run deterministic, local-only grounded-answer checks over synthetic fixtures."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import json
from pathlib import Path
from typing import Any

from prompt_contract import (
    CONFIDENCE_LEVELS,
    MEMORY_CAPABILITIES,
    PROMPT_CONTRACT_VERSION,
    parse_grounded_answer,
    render_evidence_pack_prompt,
    validate_evidence_pack,
)


DEFAULT_FIXTURES = Path("model-training/data/synthetic/grayin_eval.jsonl")


@dataclass(frozen=True)
class EvaluationResult:
    fixture_id: str
    format_valid: bool
    evidence_valid: bool
    missing_valid: bool
    confidence_valid: bool
    content_valid: bool
    errors: tuple[str, ...]

    @property
    def passed(self) -> bool:
        return not self.errors


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    seen_ids: set[str] = set()
    with path.open("r", encoding="utf-8") as source:
        for line_number, line in enumerate(source, start=1):
            if not line.strip():
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError as error:
                raise ValueError(f"{path}:{line_number}: invalid JSON: {error}") from error
            if not isinstance(record, dict):
                raise ValueError(f"{path}:{line_number}: record must be an object")
            fixture_id = record.get("id")
            if not isinstance(fixture_id, str) or not fixture_id:
                raise ValueError(f"{path}:{line_number}: id is missing")
            if fixture_id in seen_ids:
                raise ValueError(f"{path}:{line_number}: duplicate id: {fixture_id}")
            seen_ids.add(fixture_id)
            records.append(record)
    if not records:
        raise ValueError(f"{path}: no records")
    return records


def validate_fixture(fixture: dict[str, Any]) -> None:
    fixture_id = fixture.get("id", "<unknown>")
    if fixture.get("prompt_contract_version") != PROMPT_CONTRACT_VERSION:
        raise ValueError(f"{fixture_id}: prompt contract version is invalid")
    pack = fixture.get("evidence_pack")
    validate_evidence_pack(pack)
    if fixture.get("prompt") != render_evidence_pack_prompt(pack):
        raise ValueError(f"{fixture_id}: prompt does not match EvidencePackPromptBuilder contract")
    expectations = fixture.get("expectations")
    if not isinstance(expectations, dict):
        raise ValueError(f"{fixture_id}: expectations are missing")
    expected_ids = expectations.get("evidence_ids")
    expected_missing = expectations.get("missing_capabilities")
    if (
        not isinstance(expected_ids, list)
        or any(not isinstance(evidence_id, str) or not evidence_id for evidence_id in expected_ids)
        or len(expected_ids) != len(set(expected_ids))
    ):
        raise ValueError(f"{fixture_id}: expected evidence ids are invalid")
    if (
        not isinstance(expected_missing, list)
        or any(capability not in MEMORY_CAPABILITIES for capability in expected_missing)
        or len(expected_missing) != len(set(expected_missing))
    ):
        raise ValueError(f"{fixture_id}: expected missing capabilities are invalid")
    if expectations.get("confidence") not in CONFIDENCE_LEVELS:
        raise ValueError(f"{fixture_id}: expected confidence is invalid")
    pack_ids = {item["id"] for item in pack["evidence_items"]}
    pack_missing = {item["capability"] for item in pack["missing_sources"]}
    if any(evidence_id not in pack_ids for evidence_id in expected_ids):
        raise ValueError(f"{fixture_id}: expected evidence id is not in the pack")
    if any(capability not in pack_missing for capability in expected_missing):
        raise ValueError(f"{fixture_id}: expected missing capability is not in the pack")
    for key in ("required_answer_terms", "forbidden_answer_terms"):
        terms = expectations.get(key)
        if not isinstance(terms, list) or any(not isinstance(term, str) or not term for term in terms):
            raise ValueError(f"{fixture_id}: {key} is invalid")


def score_fixture(fixture: dict[str, Any], candidate_answer: str) -> EvaluationResult:
    validate_fixture(fixture)
    fixture_id = fixture["id"]
    expectations = fixture["expectations"]
    errors: list[str] = []
    try:
        parsed = parse_grounded_answer(candidate_answer)
    except ValueError as error:
        return EvaluationResult(
            fixture_id=fixture_id,
            format_valid=False,
            evidence_valid=False,
            missing_valid=False,
            confidence_valid=False,
            content_valid=False,
            errors=(f"format: {error}",),
        )

    pack_ids = {item["id"] for item in fixture["evidence_pack"]["evidence_items"]}
    claimed_ids = set(parsed.evidence_ids)
    expected_ids = set(expectations["evidence_ids"])
    unknown_ids = claimed_ids - pack_ids
    evidence_valid = not unknown_ids and claimed_ids == expected_ids
    if unknown_ids:
        errors.append(f"evidence: unknown ids: {', '.join(sorted(unknown_ids))}")
    if claimed_ids != expected_ids:
        errors.append("evidence: claimed ids do not match the expected grounded set")

    claimed_missing = set(parsed.missing_capabilities)
    expected_missing = set(expectations["missing_capabilities"])
    missing_valid = claimed_missing == expected_missing
    if not missing_valid:
        errors.append("missing: capabilities do not match the expected missing-source set")

    confidence_valid = parsed.confidence == expectations["confidence"]
    if not confidence_valid:
        errors.append("confidence: value does not match the expected confidence")

    folded_answer = parsed.answer.casefold()
    missing_required_terms = [
        term for term in expectations["required_answer_terms"] if term.casefold() not in folded_answer
    ]
    present_forbidden_terms = [
        term for term in expectations["forbidden_answer_terms"] if term.casefold() in folded_answer
    ]
    content_valid = not missing_required_terms and not present_forbidden_terms
    if missing_required_terms:
        errors.append("content: required answer terms are missing")
    if present_forbidden_terms:
        errors.append("content: forbidden answer terms are present")

    return EvaluationResult(
        fixture_id=fixture_id,
        format_valid=True,
        evidence_valid=evidence_valid,
        missing_valid=missing_valid,
        confidence_valid=confidence_valid,
        content_valid=content_valid,
        errors=tuple(errors),
    )


def prediction_map(path: Path) -> dict[str, str]:
    predictions: dict[str, str] = {}
    for record in load_jsonl(path):
        answer = record.get("answer")
        if not isinstance(answer, str):
            raise ValueError(f"{path}: {record['id']}: answer must be a string")
        predictions[record["id"]] = answer
    return predictions


def evaluate(
    fixtures: list[dict[str, Any]],
    predictions: dict[str, str] | None = None,
) -> dict[str, Any]:
    if not fixtures:
        raise ValueError("evaluation requires at least one fixture")
    fixture_ids = {fixture["id"] for fixture in fixtures}
    if predictions is not None:
        extra_ids = set(predictions) - fixture_ids
        if extra_ids:
            raise ValueError(f"predictions contain unknown fixture ids: {', '.join(sorted(extra_ids))}")
    results: list[EvaluationResult] = []
    for fixture in fixtures:
        candidate = (
            fixture.get("reference_answer")
            if predictions is None
            else predictions.get(fixture["id"])
        )
        if not isinstance(candidate, str):
            results.append(
                EvaluationResult(
                    fixture_id=fixture["id"],
                    format_valid=False,
                    evidence_valid=False,
                    missing_valid=False,
                    confidence_valid=False,
                    content_valid=False,
                    errors=("prediction: answer is missing",),
                ),
            )
            continue
        results.append(score_fixture(fixture, candidate))

    total = len(results)
    metric_fields = (
        "format_valid",
        "evidence_valid",
        "missing_valid",
        "confidence_valid",
        "content_valid",
    )
    summary = {
        "prompt_contract_version": PROMPT_CONTRACT_VERSION,
        "total": total,
        "passed": sum(result.passed for result in results),
        "failed": sum(not result.passed for result in results),
        "metrics": {
            field: sum(getattr(result, field) for result in results) / total
            for field in metric_fields
        },
        "failures": [
            {
                "id": result.fixture_id,
                "errors": list(result.errors),
            }
            for result in results
            if not result.passed
        ],
    }
    return summary


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixtures", type=Path, default=DEFAULT_FIXTURES)
    parser.add_argument("--predictions", type=Path)
    parser.add_argument("--json-out", type=Path)
    args = parser.parse_args()

    fixtures = load_jsonl(args.fixtures)
    predictions = prediction_map(args.predictions) if args.predictions else None
    summary = evaluate(fixtures, predictions)
    rendered = json.dumps(summary, indent=2, sort_keys=True) + "\n"
    if args.json_out:
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    raise SystemExit(0 if summary["failed"] == 0 else 1)


if __name__ == "__main__":
    main()
