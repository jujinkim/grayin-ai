# Grayin App Model Training

This directory contains training code and policy for a Grayin-specific local model. It tracks only reproducible training instructions, configs, synthetic examples, and small metadata files.

It must not track Gemma reference weights or generated model artifacts.

## Goal

- Start from a Gemma instruction model.
- Tune behavior for Grayin's evidence-grounded, non-agentic recall flow.
- Prefer LoRA/QLoRA first, then merge/export into a runtime model file outside git.
- Keep APK/AAB model-free. App receives model files only through fixed-catalog runtime download or explicit local import under `docs/network-policy.md`.

## Privacy Boundary

Training data in this repository must be synthetic or hand-authored policy data only.

Never use:

- user local files
- notification text
- calendar raw records
- photo bytes or raw metadata dumps
- app usage raw dumps
- SQLCipher database exports
- EvidencePack prompts from real users
- answers generated from real indexed memories

Allowed examples may include synthetic `EvidencePack`-shaped records that use fake source IDs, fake timestamps, fake place names, and fake citations.

## Directory Layout

- `configs/`: tracked training configs.
- `contracts/`: cross-language golden fixtures for the app/runtime prompt contract.
- `scripts/`: tracked preparation, training, export-manifest, and artifact-policy checks.
- `tests/`: dependency-free corpus, prompt-contract, leakage, and scorer tests.
- `data/synthetic/`: tracked or generated synthetic JSONL examples.
- `data/private/`: ignored. Local-only experiments; do not commit.
- `reference-models/`: ignored Gemma reference model cache.
- `checkpoints/`: ignored training checkpoints.
- `outputs/`: ignored adapters, merged weights, LiteRT exports, manifests from private runs.

## Workflow

1. Create synthetic corpus:

   ```bash
   python3 model-training/scripts/build_training_corpus.py
   ```

   The generator writes 30 training and 30 held-out evaluation records: ten benchmark families in English, Korean, and Japanese. `--check` verifies that the committed JSONL still matches the deterministic generator without rewriting it.

2. Validate the local-only contract and reference answers:

   ```bash
   python3 -m unittest discover -s model-training/tests -p 'test_*.py' -v
   python3 model-training/scripts/validate_training_setup.py
   python3 model-training/scripts/run_grounded_eval.py
   ```

   Validation rejects duplicate IDs, duplicate normalized queries or prompts, train/eval query or prompt leakage, incomplete family/language coverage, citation mismatches, malformed four-line answers, unknown evidence IDs, missing-source mismatches, and confidence mismatches.

3. Put Gemma reference weights under ignored local path:

   ```text
   model-training/reference-models/gemma-4-e2b-it/
   ```

   Or use the download helper:

   ```bash
   python3 model-training/scripts/download_reference_model.py --dry-run
   python3 model-training/scripts/download_reference_model.py
   ```

4. Install training dependencies in separate environment:

   ```bash
   python3 -m venv .venv-model-training
   . .venv-model-training/bin/activate
   pip install -r model-training/requirements.txt
   ```

5. Run LoRA/QLoRA training:

   ```bash
   python3 model-training/scripts/train_lora.py \
     --config model-training/configs/grayin_gemma_lora.yaml
   ```

   For environment verification on an 8 GB GPU, run the one-step smoke config:

   ```bash
   python3 model-training/scripts/train_lora.py \
     --config model-training/configs/grayin_gemma_lora_smoke.yaml
   ```

6. Validate reference weights and ignored artifact paths:

   ```bash
   python3 model-training/scripts/validate_training_setup.py
   ```

   Use this stricter check before real training:

   ```bash
   python3 model-training/scripts/validate_training_setup.py --require-reference-model
   ```

7. Export or convert artifacts outside git. Generated files remain under ignored `outputs/`.

8. Check git artifact policy before commit:

   ```bash
   python3 model-training/scripts/check_artifact_policy.py
   ```

## Prompt and Answer Contract

`scripts/prompt_contract.py` mirrors `EvidencePackPromptBuilder`. The fixture and golden prompt under `contracts/` are checked from both Python and Kotlin tests. Training records contain the exact runtime prompt as the user message; `train_lora.py` then applies the pinned Gemma tokenizer chat template instead of training on a hand-written `SYSTEM/USER/ASSISTANT` wrapper.

Every reference or predicted answer must use exactly four lines:

```text
Answer: <concise answer in the query language>
Evidence: <exact evidence IDs, comma-separated; or none>
Missing: <CAPABILITY: explanation entries, semicolon-separated; or none>
Confidence: LOW, MEDIUM, HIGH, or UNKNOWN
```

The deterministic scorer does not call a model or network service. Without `--predictions`, it verifies the held-out fixtures and their reference answers. A later host or device model runner can provide JSONL predictions containing `id` and `answer`:

```bash
python3 model-training/scripts/run_grounded_eval.py \
  --predictions model-training/outputs/eval/predictions.jsonl \
  --json-out model-training/outputs/eval/summary.json
```

The scorer validates format, exact cited evidence, exact missing capabilities, confidence, and fixture-specific required/forbidden answer terms. It is a deterministic policy gate, not a semantic or device-runtime quality benchmark; final `.litertlm` latency, memory, and answer quality still require the release artifact and representative Android devices.

## Run Records

Training attempts are summarized in `RUNS.md`. That file records commands, environment, metrics, and ignored artifact paths only. It must not include model weights, raw user data, private prompts, or full generated model output.

## Make Targets

From repository root:

```bash
make -f model-training/Makefile corpus
make -f model-training/Makefile corpus-check
make -f model-training/Makefile test
make -f model-training/Makefile eval
make -f model-training/Makefile validate
make -f model-training/Makefile download-reference-dry-run
make -f model-training/Makefile validate-strict
make -f model-training/Makefile train-smoke
make -f model-training/Makefile train
make -f model-training/Makefile artifact-policy
```

## Release Rule

Only publish final model files through an immutable HTTPS artifact URL on an approved static host. Publish pinned SHA-256 metadata and, for remote manifest updates, an ECDSA P-256 signature verifiable by the app. Do not commit model files, private keys, or credentials to this repository.
