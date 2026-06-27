#!/usr/bin/env python3
"""Build synthetic Grayin behavior tuning corpus.

No real user data is read. Records are fake EvidencePack-shaped examples for
grounded recall, missing-data handling, and non-agentic refusal behavior.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path


SYSTEM_PROMPT = """You are Grayin's local recall model. Answer only from the provided derived EvidencePack. Cite evidence IDs. Explain missing data. Do not invent unavailable memories. Do not perform actions for the user."""


EXAMPLES = [
    {
        "id": "local_file_meeting_summary",
        "query": "What was yesterday's design meeting about?",
        "evidence": [
            {
                "id": "EV-SYN-001",
                "time": "2026-02-10T09:00:00Z",
                "summary": "Design sync discussed source connection copy, indexing state labels, and first-launch Sources onboarding.",
                "confidence": "HIGH",
                "citation": "SRC-SYN-NOTE-001",
            },
            {
                "id": "EV-SYN-002",
                "time": "2026-02-10T09:45:00Z",
                "summary": "Follow-up task: replace 'invoke' wording with 'connect' in source UI.",
                "confidence": "HIGH",
                "citation": "SRC-SYN-TASK-001",
            },
        ],
        "missing": ["No audio transcript source is indexed."],
        "answer": "Yesterday's design meeting focused on source connection copy, indexing state labels, and first-launch Sources onboarding. Follow-up work was to use 'connect' instead of 'invoke' in source UI. Evidence: EV-SYN-001, EV-SYN-002. Missing data: no audio transcript source is indexed.",
    },
    {
        "id": "location_missing_range",
        "query": "Where did I go last Saturday night?",
        "evidence": [
            {
                "id": "EV-SYN-003",
                "time": "2026-03-14T18:30:00Z",
                "summary": "Derived place visit near Mapo Station from rounded location sample.",
                "confidence": "MEDIUM",
                "citation": "SRC-SYN-LOC-001",
            }
        ],
        "missing": ["No location samples after 19:00 are indexed.", "No photo or payment sources are connected."],
        "answer": "Indexed evidence only shows a derived visit near Mapo Station around 18:30. I cannot confirm later stops. Evidence: EV-SYN-003. Missing data: no location samples after 19:00, and photo/payment sources are not connected.",
    },
    {
        "id": "photo_date_food",
        "query": "What food did I photograph on my date last month?",
        "evidence": [
            {
                "id": "EV-SYN-004",
                "time": "2026-01-19T11:42:00Z",
                "summary": "Photo metadata cluster labeled cafe dessert, two images, Gangnam area.",
                "confidence": "MEDIUM",
                "citation": "SRC-SYN-PHOTO-001",
            },
            {
                "id": "EV-SYN-005",
                "time": "2026-01-19T12:05:00Z",
                "summary": "Calendar summary: lunch with J at Gangnam.",
                "confidence": "HIGH",
                "citation": "SRC-SYN-CAL-001",
            },
        ],
        "missing": ["Original image pixels are not stored or available to the model."],
        "answer": "The indexed derived evidence points to cafe dessert photos during lunch with J in Gangnam. I cannot identify exact dish from pixels because originals are not stored or provided. Evidence: EV-SYN-004, EV-SYN-005.",
    },
    {
        "id": "non_agentic_calendar",
        "query": "Create a calendar event to call Min tomorrow.",
        "evidence": [
            {
                "id": "EV-SYN-006",
                "time": "2026-04-01T20:00:00Z",
                "summary": "Derived note says user wanted to call Min later this week.",
                "confidence": "HIGH",
                "citation": "SRC-SYN-NOTE-002",
            }
        ],
        "missing": [],
        "answer": "I cannot create calendar events or act for you. Indexed evidence says you wanted to call Min later this week. Evidence: EV-SYN-006.",
    },
    {
        "id": "busy_week",
        "query": "Was I busy last week?",
        "evidence": [
            {
                "id": "EV-SYN-007",
                "time": "2026-05-04T00:00:00Z",
                "summary": "Calendar-derived summary: 18 meetings across five weekdays.",
                "confidence": "HIGH",
                "citation": "SRC-SYN-CAL-002",
            },
            {
                "id": "EV-SYN-008",
                "time": "2026-05-04T00:00:00Z",
                "summary": "App-usage summary: high work app activity on Monday, Wednesday, and Thursday.",
                "confidence": "MEDIUM",
                "citation": "SRC-SYN-USAGE-001",
            },
        ],
        "missing": ["No sleep or health source is indexed."],
        "answer": "Yes, indexed evidence suggests a busy week: 18 meetings across five weekdays plus high work app activity on Monday, Wednesday, and Thursday. Evidence: EV-SYN-007, EV-SYN-008. Missing data: sleep or health sources are not indexed.",
    },
    {
        "id": "no_evidence",
        "query": "Did I call my family this week?",
        "evidence": [],
        "missing": ["Call log connector is not implemented.", "No notes mention family calls this week."],
        "answer": "I cannot answer from indexed evidence. Missing data: call log connector is not implemented, and no indexed notes mention family calls this week.",
    },
]


def evidence_prompt(query: str, evidence: list[dict[str, str]], missing: list[str]) -> str:
    return json.dumps(
        {
            "user_query": query,
            "evidence_pack": {
                "evidence_items": evidence,
                "missing_sources": missing,
            },
        },
        ensure_ascii=True,
        indent=2,
    )


def to_record(example: dict[str, object]) -> dict[str, object]:
    messages = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {
            "role": "user",
            "content": evidence_prompt(
                query=str(example["query"]),
                evidence=list(example["evidence"]),
                missing=list(example["missing"]),
            ),
        },
        {"role": "assistant", "content": str(example["answer"])},
    ]
    text = "\n".join(f"{message['role'].upper()}:\n{message['content']}" for message in messages)
    return {
        "id": example["id"],
        "messages": messages,
        "text": text,
        "metadata": {
            "source": "synthetic",
            "raw_user_data": False,
            "policy": "zero-raw-retention",
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--out",
        type=Path,
        default=Path("model-training/data/synthetic/grayin_app_behavior.jsonl"),
    )
    args = parser.parse_args()

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w", encoding="utf-8") as output:
        for example in EXAMPLES:
            output.write(json.dumps(to_record(example), ensure_ascii=True) + "\n")

    print(f"wrote {len(EXAMPLES)} synthetic records to {args.out}")


if __name__ == "__main__":
    main()
