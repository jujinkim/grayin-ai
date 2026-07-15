from __future__ import annotations

import copy
import json
from pathlib import Path
import sys
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = REPO_ROOT / "model-training/scripts"
sys.path.insert(0, str(SCRIPTS))

from build_training_corpus import (  # noqa: E402
    LANGUAGES,
    SCENARIOS,
    build_records,
    serialize_jsonl,
)
from prompt_contract import (  # noqa: E402
    parse_grounded_answer,
    render_evidence_pack_prompt,
)
from run_grounded_eval import evaluate, load_jsonl, score_fixture  # noqa: E402
from validate_training_setup import validate_no_train_eval_leakage  # noqa: E402


class PromptContractTest(unittest.TestCase):
    def test_python_renderer_matches_kotlin_golden_contract(self) -> None:
        fixture = json.loads(
            (REPO_ROOT / "model-training/contracts/evidence_pack_prompt_v1_fixture.json").read_text(
                encoding="utf-8",
            ),
        )
        golden = (REPO_ROOT / "model-training/contracts/evidence_pack_prompt_v1_golden.txt").read_text(
            encoding="utf-8",
        )

        self.assertEqual(golden, render_evidence_pack_prompt(fixture))

    def test_four_line_parser_rejects_duplicate_evidence_ids(self) -> None:
        with self.assertRaisesRegex(ValueError, "duplicate"):
            parse_grounded_answer(
                "Answer: grounded\n"
                "Evidence: evidence:event:1, evidence:event:1\n"
                "Missing: none\n"
                "Confidence: HIGH",
            )

    def test_four_line_parser_requires_structured_missing_capabilities(self) -> None:
        with self.assertRaisesRegex(ValueError, "CAPABILITY"):
            parse_grounded_answer(
                "Answer: grounded\n"
                "Evidence: evidence:event:1\n"
                "Missing: Photos were unavailable\n"
                "Confidence: HIGH",
            )


class CorpusTest(unittest.TestCase):
    def test_generated_corpus_has_complete_language_and_family_coverage(self) -> None:
        train = build_records("train")
        evaluation = build_records("eval")
        expected_pairs = {
            (scenario["family"], language)
            for scenario in SCENARIOS
            for language in LANGUAGES
        }

        self.assertEqual(len(expected_pairs), len(train))
        self.assertEqual(len(expected_pairs), len(evaluation))
        self.assertEqual(expected_pairs, {(record["benchmark_family"], record["language"]) for record in train})
        self.assertEqual(
            expected_pairs,
            {(record["benchmark_family"], record["language"]) for record in evaluation},
        )

    def test_committed_jsonl_matches_deterministic_generator(self) -> None:
        train_path = REPO_ROOT / "model-training/data/synthetic/grayin_app_behavior.jsonl"
        eval_path = REPO_ROOT / "model-training/data/synthetic/grayin_eval.jsonl"

        self.assertEqual(serialize_jsonl(build_records("train")), train_path.read_text(encoding="utf-8"))
        self.assertEqual(serialize_jsonl(build_records("eval")), eval_path.read_text(encoding="utf-8"))

    def test_train_and_eval_have_no_exact_query_prompt_id_or_evidence_leakage(self) -> None:
        train_path = REPO_ROOT / "model-training/data/synthetic/grayin_app_behavior.jsonl"
        eval_path = REPO_ROOT / "model-training/data/synthetic/grayin_eval.jsonl"
        validate_no_train_eval_leakage(
            train_path,
            build_records("train"),
            eval_path,
            build_records("eval"),
        )

    def test_leakage_validator_rejects_a_reused_eval_query(self) -> None:
        train = build_records("train")
        evaluation = copy.deepcopy(build_records("eval"))
        evaluation[0]["evidence_pack"]["query"] = train[0]["evidence_pack"]["query"]

        with self.assertRaisesRegex(ValueError, "query leakage"):
            validate_no_train_eval_leakage(
                Path("train.jsonl"),
                train,
                Path("eval.jsonl"),
                evaluation,
            )

    def test_leakage_validator_rejects_a_reused_reference_answer(self) -> None:
        train = build_records("train")
        evaluation = copy.deepcopy(build_records("eval"))
        evaluation[0]["reference_answer"] = train[0]["reference_answer"]

        with self.assertRaisesRegex(ValueError, "answer leakage"):
            validate_no_train_eval_leakage(
                Path("train.jsonl"),
                train,
                Path("eval.jsonl"),
                evaluation,
            )


class GroundedEvalTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.fixtures = load_jsonl(REPO_ROOT / "model-training/data/synthetic/grayin_eval.jsonl")

    def test_all_reference_answers_pass_deterministic_eval(self) -> None:
        summary = evaluate(self.fixtures)

        self.assertEqual(len(self.fixtures), summary["total"])
        self.assertEqual(len(self.fixtures), summary["passed"])
        self.assertEqual(0, summary["failed"])
        self.assertTrue(all(value == 1.0 for value in summary["metrics"].values()))

    def test_mixed_known_and_unknown_evidence_claim_fails(self) -> None:
        fixture = next(item for item in self.fixtures if item["evidence_pack"]["evidence_items"])
        answer = fixture["reference_answer"].replace(
            "\nMissing:",
            ", evidence:event:unknown\nMissing:",
            1,
        )

        result = score_fixture(fixture, answer)

        self.assertFalse(result.passed)
        self.assertFalse(result.evidence_valid)
        self.assertTrue(any("unknown ids" in error for error in result.errors))

    def test_wrong_missing_capability_and_confidence_fail(self) -> None:
        fixture = next(item for item in self.fixtures if item["expectations"]["missing_capabilities"])
        answer = fixture["reference_answer"]
        missing_line = next(line for line in answer.splitlines() if line.startswith("Missing:"))
        answer = answer.replace(missing_line, "Missing: HAS_DELIVERY: synthetic mismatch")
        answer = answer.replace(
            f"Confidence: {fixture['expectations']['confidence']}",
            "Confidence: LOW",
        )

        result = score_fixture(fixture, answer)

        self.assertFalse(result.passed)
        self.assertFalse(result.missing_valid)
        self.assertFalse(result.confidence_valid)


if __name__ == "__main__":
    unittest.main()
