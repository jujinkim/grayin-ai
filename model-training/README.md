# Grayin App Model Training

This directory contains training code and policy for a Grayin-specific local model. It tracks only reproducible training instructions, configs, synthetic examples, and small metadata files.

It must not track Gemma reference weights or generated model artifacts.

## Goal

- Start from a Gemma instruction model.
- Tune behavior for Grayin's evidence-grounded, non-agentic recall flow.
- Prefer LoRA/QLoRA first, then merge/export into a runtime model file outside git.
- Keep APK/AAB model-free. App receives model files only through runtime download/import.

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
- `scripts/`: tracked preparation, training, export-manifest, and artifact-policy checks.
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

2. Put Gemma reference weights under ignored local path:

   ```text
   model-training/reference-models/gemma-4-e2b-it/
   ```

   Or use the download helper:

   ```bash
   python3 model-training/scripts/download_reference_model.py --dry-run
   python3 model-training/scripts/download_reference_model.py
   ```

3. Install training dependencies in separate environment:

   ```bash
   python3 -m venv .venv-model-training
   . .venv-model-training/bin/activate
   pip install -r model-training/requirements.txt
   ```

4. Run LoRA/QLoRA training:

   ```bash
   python3 model-training/scripts/train_lora.py \
     --config model-training/configs/grayin_gemma_lora.yaml
   ```

   For environment verification on an 8 GB GPU, run the one-step smoke config:

   ```bash
   python3 model-training/scripts/train_lora.py \
     --config model-training/configs/grayin_gemma_lora_smoke.yaml
   ```

5. Validate setup and ignored artifact paths:

   ```bash
   python3 model-training/scripts/validate_training_setup.py
   ```

   Use this stricter check before real training:

   ```bash
   python3 model-training/scripts/validate_training_setup.py --require-reference-model
   ```

6. Export or convert artifacts outside git. Generated files remain under ignored `outputs/`.

7. Check git artifact policy before commit:

   ```bash
   python3 model-training/scripts/check_artifact_policy.py
   ```

## Evaluation Seed

`data/synthetic/grayin_eval.jsonl` contains synthetic evaluation prompts for:

- missing source honesty
- cited grounded answers
- non-agentic refusals
- no-evidence answers

## Run Records

Training attempts are summarized in `RUNS.md`. That file records commands, environment, metrics, and ignored artifact paths only. It must not include model weights, raw user data, private prompts, or full generated model output.

## Make Targets

From repository root:

```bash
make -f model-training/Makefile corpus
make -f model-training/Makefile validate
make -f model-training/Makefile download-reference-dry-run
make -f model-training/Makefile validate-strict
make -f model-training/Makefile train-smoke
make -f model-training/Makefile train
make -f model-training/Makefile artifact-policy
```

## Release Rule

Only publish final model files through a separate file server or official model host. Do not commit model files to this repository.
