# Grounded Answer Format

MVP 8 adds a template-based grounded answer generator.

## Required Fields

Every generated `GroundedAnswer` includes:

- answer
- evidence
- inference
- confidence
- missing data
- source references through citations

## Citation Rule

`TemplateGroundedAnswerGenerator` uses only evidence items that reference known citation IDs from the `EvidencePack`.

Evidence without a valid citation is excluded from the answer and inference steps.

The answer text is assembled from cited evidence summaries only. Inference steps point back to the evidence item IDs used for each claim.

## Missing Data

Missing sources from the evidence pack are preserved on the answer. If no cited evidence is available, the answer says it cannot answer from indexed, cited evidence and returns unknown confidence.
