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

Before generation, `LocalModelGrounding` removes evidence without a known citation and removes citation references that are not present in the pack.

The local-model response contract includes an `Evidence:` line containing exact, comma-separated `EvidenceItem.id` values. A model draft is accepted only when it is non-empty, claims at least one exact ID, and every claimed ID belongs to the cited pack. Prefix matches, unknown IDs, and uncited IDs are rejected. Rejected drafts fall back to the deterministic grounded-answer generator.

Answers must be assembled from cited evidence summaries only. Inference steps point back to the evidence item IDs used for each claim.

## Missing Data

Missing sources from the evidence pack are preserved on the answer. If no cited evidence is available, the answer says it cannot answer from indexed, cited evidence and returns unknown confidence.
