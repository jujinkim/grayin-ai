# Grounded Answer Format

MVP 8 added a grounded answer contract. The current Ask flow uses a local Gemma LiteRT-LM adapter when available, with the template generator as fallback.

## Required Fields

Every generated `GroundedAnswer` includes:

- answer
- evidence
- inference
- confidence
- missing data
- source references through citations

## Citation Rule

`Gemma4LocalLanguageModel` receives only the `EvidencePack` prompt built from derived evidence, citations, and missing-source rows.

`TemplateGroundedAnswerGenerator` uses only evidence items that reference known citation IDs from the `EvidencePack`.

Before generation, `LocalModelGrounding` removes evidence without a known citation, rejects citations linked to a different derived event, and removes citation references that are not present in the pack.

The local-model response contract contains exactly four lines in order: `Answer:`, `Evidence:`, `Missing:`, and `Confidence:`. `Evidence:` contains exact, comma-separated `EvidenceItem.id` values. `Missing:` uses `CAPABILITY: explanation` entries separated by semicolons. The Kotlin runtime parses this same strict contract, presents only the parsed `Answer:` value, and accepts a model draft only when it is non-empty, claims at least one exact cited ID, and its missing-capability set exactly matches the `EvidencePack`. Extra or reordered lines, malformed missing/confidence fields, prefix matches, duplicate claims, unknown IDs, uncited IDs, and a mixed known-plus-unknown claim are rejected. Rejected drafts fall back to the deterministic grounded-answer generator.

The synthetic training/evaluation pipeline mirrors the runtime prompt through the versioned `evidence-pack-prompt-v1` contract. Its dependency-free scorer checks exact evidence and missing-capability sets, confidence, format, and fixture-specific required/forbidden expressions without using a remote model grader. This is a policy-contract gate; final answer quality still requires evaluation of the exported local model.

Answers must be assembled from cited evidence summaries only. Inference steps point back to the evidence item IDs used for each claim.

## Missing Data

Missing sources from the evidence pack are preserved on the answer. If no cited evidence is available, the answer says it cannot answer from indexed, cited evidence and returns unknown confidence.
