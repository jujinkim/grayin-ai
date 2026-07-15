#!/usr/bin/env python3
"""Shared, dependency-free Grayin EvidencePack prompt and answer contract."""

from __future__ import annotations

from dataclasses import dataclass
import re
from typing import Any


EVIDENCE_PACK_SCHEMA_VERSION = "grayin-evidence-pack-v1"
PROMPT_CONTRACT_VERSION = "evidence-pack-prompt-v1"

MAX_EVIDENCE_ITEMS = 12
MAX_MISSING_SOURCES = 12
MAX_SUMMARY_CHARS = 600
MAX_MISSING_CHARS = 300

CONFIDENCE_LEVELS = {"UNKNOWN", "LOW", "MEDIUM", "HIGH"}
EVENT_KINDS = {
    "PLACE_VISIT",
    "PLACE_CLUSTER",
    "CALENDAR_EVENT",
    "PHOTO_INDEX",
    "PHOTO_CLUSTER",
    "PAYMENT",
    "DELIVERY",
    "RESERVATION",
    "TRANSPORT",
    "APP_USAGE",
    "LOCAL_FILE_INDEX",
    "DAILY_SUMMARY",
    "INFERRED_CONTEXT",
}
MEMORY_CAPABILITIES = {
    "HAS_TIME",
    "HAS_LOCATION",
    "HAS_MEDIA",
    "HAS_CALENDAR",
    "HAS_PAYMENT",
    "HAS_DELIVERY",
    "HAS_RESERVATION",
    "HAS_TRANSPORT",
    "HAS_APP_USAGE",
    "HAS_TEXT",
    "HAS_PERSON",
    "HAS_VISUAL_LABEL",
}
SOURCE_AVAILABILITIES = {
    "AVAILABLE",
    "DISABLED",
    "DENIED",
    "UNSUPPORTED",
    "NOT_INDEXED",
    "STALE",
    "MISSING_PERMISSION",
}

_EVIDENCE_ID = re.compile(r"[A-Za-z0-9._:-]{1,200}\Z")
_ANSWER_PREFIXES = ("Answer:", "Evidence:", "Missing:", "Confidence:")


@dataclass(frozen=True)
class ParsedGroundedAnswer:
    answer: str
    evidence_ids: tuple[str, ...]
    missing_capabilities: tuple[str, ...]
    missing: str
    confidence: str


def _require_string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{label} must be a non-empty string")
    return value


def _require_list(value: Any, label: str) -> list[Any]:
    if not isinstance(value, list):
        raise ValueError(f"{label} must be a list")
    return value


def validate_evidence_pack(pack: dict[str, Any]) -> None:
    if not isinstance(pack, dict):
        raise ValueError("evidence_pack must be an object")
    if pack.get("schema_version") != EVIDENCE_PACK_SCHEMA_VERSION:
        raise ValueError("evidence_pack.schema_version is unsupported")
    _require_string(pack.get("id"), "evidence_pack.id")
    _require_string(pack.get("query"), "evidence_pack.query")
    _require_string(pack.get("generated_at"), "evidence_pack.generated_at")

    evidence_items = _require_list(pack.get("evidence_items"), "evidence_pack.evidence_items")
    citations = _require_list(pack.get("citations"), "evidence_pack.citations")
    missing_sources = _require_list(pack.get("missing_sources"), "evidence_pack.missing_sources")
    if len(evidence_items) > MAX_EVIDENCE_ITEMS:
        raise ValueError(f"evidence_pack has more than {MAX_EVIDENCE_ITEMS} evidence items")
    if len(missing_sources) > MAX_MISSING_SOURCES:
        raise ValueError(f"evidence_pack has more than {MAX_MISSING_SOURCES} missing sources")

    citation_events: dict[str, str | None] = {}
    for index, citation in enumerate(citations):
        if not isinstance(citation, dict):
            raise ValueError(f"citation[{index}] must be an object")
        citation_id = _require_string(citation.get("id"), f"citation[{index}].id")
        if citation_id in citation_events:
            raise ValueError(f"duplicate citation id: {citation_id}")
        _require_string(citation.get("source_reference_id"), f"citation[{index}].source_reference_id")
        _require_string(citation.get("label"), f"citation[{index}].label")
        event_id = citation.get("derived_memory_event_id")
        if event_id is not None:
            _require_string(event_id, f"citation[{index}].derived_memory_event_id")
        confidence = citation.get("confidence", "UNKNOWN")
        if confidence not in CONFIDENCE_LEVELS:
            raise ValueError(f"citation[{index}].confidence is invalid")
        citation_events[citation_id] = event_id

    evidence_ids: set[str] = set()
    used_citation_ids: set[str] = set()
    for index, evidence in enumerate(evidence_items):
        if not isinstance(evidence, dict):
            raise ValueError(f"evidence_item[{index}] must be an object")
        evidence_id = _require_string(evidence.get("id"), f"evidence_item[{index}].id")
        if not _EVIDENCE_ID.fullmatch(evidence_id):
            raise ValueError(f"evidence_item[{index}].id is invalid")
        if evidence_id in evidence_ids:
            raise ValueError(f"duplicate evidence id: {evidence_id}")
        evidence_ids.add(evidence_id)
        event_id = _require_string(
            evidence.get("derived_memory_event_id"),
            f"evidence_item[{index}].derived_memory_event_id",
        )
        _require_string(evidence.get("summary"), f"evidence_item[{index}].summary")
        if evidence.get("event_kind") not in EVENT_KINDS:
            raise ValueError(f"evidence_item[{index}].event_kind is invalid")
        occurred_at = evidence.get("occurred_at")
        if occurred_at is not None:
            _require_string(occurred_at, f"evidence_item[{index}].occurred_at")
        if evidence.get("confidence") not in CONFIDENCE_LEVELS:
            raise ValueError(f"evidence_item[{index}].confidence is invalid")
        capabilities = _require_list(evidence.get("capabilities"), f"evidence_item[{index}].capabilities")
        if len(capabilities) != len(set(capabilities)) or any(
            capability not in MEMORY_CAPABILITIES for capability in capabilities
        ):
            raise ValueError(f"evidence_item[{index}].capabilities are invalid")
        citation_ids = _require_list(evidence.get("citation_ids"), f"evidence_item[{index}].citation_ids")
        if not citation_ids:
            raise ValueError(f"evidence_item[{index}] must have a citation")
        if len(citation_ids) != len(set(citation_ids)):
            raise ValueError(f"evidence_item[{index}] has duplicate citation ids")
        for citation_id in citation_ids:
            if citation_events.get(citation_id) != event_id:
                raise ValueError(f"evidence_item[{index}] has an unknown or mismatched citation")
            used_citation_ids.add(citation_id)

    if used_citation_ids != set(citation_events):
        raise ValueError("evidence_pack contains unused citations")

    missing_capabilities: set[str] = set()
    for index, missing in enumerate(missing_sources):
        if not isinstance(missing, dict):
            raise ValueError(f"missing_source[{index}] must be an object")
        capability = missing.get("capability")
        if capability not in MEMORY_CAPABILITIES:
            raise ValueError(f"missing_source[{index}].capability is invalid")
        if capability in missing_capabilities:
            raise ValueError(f"duplicate missing capability: {capability}")
        missing_capabilities.add(capability)
        if missing.get("availability") not in SOURCE_AVAILABILITIES:
            raise ValueError(f"missing_source[{index}].availability is invalid")
        _require_string(missing.get("explanation"), f"missing_source[{index}].explanation")
        connector_id = missing.get("connector_id")
        if connector_id is not None:
            _require_string(connector_id, f"missing_source[{index}].connector_id")


def render_evidence_pack_prompt(pack: dict[str, Any]) -> str:
    """Mirror EvidencePackPromptBuilder exactly for synthetic training/eval data."""
    validate_evidence_pack(pack)
    citations_by_id = {citation["id"]: citation for citation in pack["citations"]}
    lines = [
        "You are Grayin AI, a local-first personal memory recall engine.",
        "Use only DERIVED_EVIDENCE and MISSING_SOURCES below.",
        "Never claim access to original files, photos, notifications, calendar records, usage logs, maps, accounts, cloud, or network.",
        "You may infer cautiously from indexed derived evidence, but every claim must be grounded in evidence ids.",
        "If required data is missing, say what is missing.",
        "Answer in the same language as USER_QUERY when practical.",
        "",
        "USER_QUERY:",
        pack["query"],
        "",
        "DERIVED_EVIDENCE:",
    ]
    if not pack["evidence_items"]:
        lines.append("- none")
    else:
        for number, evidence in enumerate(pack["evidence_items"][:MAX_EVIDENCE_ITEMS], start=1):
            citation_labels = "; ".join(
                f"{citation_id}:{citations_by_id[citation_id]['label']}"
                for citation_id in evidence["citation_ids"]
                if citation_id in citations_by_id
            ) or "none"
            capabilities = ", ".join(evidence["capabilities"])
            lines.extend(
                [
                    f"- E{number}",
                    f"  evidence_id: {evidence['id']}",
                    f"  event_id: {evidence['derived_memory_event_id']}",
                    f"  kind: {evidence['event_kind']}",
                    f"  occurred_at: {evidence.get('occurred_at') or 'unknown'}",
                    f"  confidence: {evidence['confidence']}",
                    f"  capabilities: {capabilities}",
                    f"  citations: {citation_labels}",
                    f"  summary: {evidence['summary'][:MAX_SUMMARY_CHARS]}",
                ],
            )
    lines.extend(["", "MISSING_SOURCES:"])
    if not pack["missing_sources"]:
        lines.append("- none")
    else:
        for missing in pack["missing_sources"][:MAX_MISSING_SOURCES]:
            lines.append(
                f"- {missing['capability']}: {missing['availability']} - "
                f"{missing['explanation'][:MAX_MISSING_CHARS]}",
            )
    lines.extend(
        [
            "",
            "Return format:",
            "Answer: <concise answer>",
            "Evidence: <exact evidence_id values used, separated by commas; none if no evidence is used>",
            "Missing: <CAPABILITY: concise explanation entries separated by semicolons; none if no data is missing>",
            "Confidence: LOW, MEDIUM, HIGH, or UNKNOWN",
        ],
    )
    return "\n".join(lines) + "\n"


def build_grounded_answer(
    answer: str,
    evidence_ids: list[str],
    missing_sources: list[dict[str, Any]],
    confidence: str,
) -> str:
    _require_string(answer, "answer")
    if confidence not in CONFIDENCE_LEVELS:
        raise ValueError("confidence is invalid")
    evidence_value = ", ".join(evidence_ids) if evidence_ids else "none"
    missing_value = "; ".join(
        f"{missing['capability']}: {missing['explanation']}" for missing in missing_sources
    ) or "none"
    output = (
        f"Answer: {answer}\n"
        f"Evidence: {evidence_value}\n"
        f"Missing: {missing_value}\n"
        f"Confidence: {confidence}"
    )
    parse_grounded_answer(output)
    return output


def parse_grounded_answer(output: str) -> ParsedGroundedAnswer:
    if not isinstance(output, str):
        raise ValueError("grounded answer must be a string")
    lines = output.strip().splitlines()
    if len(lines) != len(_ANSWER_PREFIXES):
        raise ValueError("grounded answer must contain exactly four lines")
    values: list[str] = []
    for line, prefix in zip(lines, _ANSWER_PREFIXES, strict=True):
        if not line.startswith(prefix):
            raise ValueError(f"grounded answer line must start with {prefix}")
        value = line[len(prefix) :].strip()
        if not value:
            raise ValueError(f"grounded answer {prefix[:-1]} value must not be empty")
        values.append(value)

    answer, evidence_value, missing_value, confidence = values
    evidence_ids: tuple[str, ...]
    if evidence_value.casefold() == "none":
        evidence_ids = ()
    else:
        evidence_ids = tuple(item.strip() for item in evidence_value.split(","))
        if any(not item or not _EVIDENCE_ID.fullmatch(item) for item in evidence_ids):
            raise ValueError("Evidence contains an invalid id")
        if len(evidence_ids) != len(set(evidence_ids)):
            raise ValueError("Evidence contains a duplicate id")

    missing_capabilities: tuple[str, ...]
    if missing_value.casefold() == "none":
        missing_capabilities = ()
    else:
        parsed_capabilities: list[str] = []
        for entry in missing_value.split(";"):
            capability, separator, explanation = entry.strip().partition(":")
            if not separator or capability not in MEMORY_CAPABILITIES or not explanation.strip():
                raise ValueError("Missing entries must use CAPABILITY: explanation")
            parsed_capabilities.append(capability)
        if len(parsed_capabilities) != len(set(parsed_capabilities)):
            raise ValueError("Missing contains a duplicate capability")
        missing_capabilities = tuple(parsed_capabilities)

    if confidence not in CONFIDENCE_LEVELS:
        raise ValueError("Confidence is invalid")
    return ParsedGroundedAnswer(
        answer=answer,
        evidence_ids=evidence_ids,
        missing_capabilities=missing_capabilities,
        missing=missing_value,
        confidence=confidence,
    )
