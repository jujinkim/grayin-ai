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
- `scripts/`: tracked preparation, merge, LiteRT-LM export/evaluation, release-metadata, and artifact-policy checks.
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

7. Pin the exact local base-model and adapter trees, then merge LoRA offline:

   ```bash
   make -f model-training/Makefile merge-input-digests
   make -f model-training/Makefile merge \
     BASE_TREE_SHA256=<digest printed above> \
     ADAPTER_TREE_SHA256=<digest printed above> \
     SOURCE_DATE_EPOCH=<positive build epoch>
   ```

   The merge refuses unpinned input hashes, symlinks, non-Gemma-4 base metadata, existing outputs, and paths outside ignored local artifact roots. It sets Hugging Face/Transformers/Datasets offline mode, rechecks the base, adapter, corpus, and config after the merge, and records the source revision, corpus/config hashes, complete input/output tree hashes, source epoch, and observed Python package versions. It does not claim byte-for-byte reproducibility across unrecorded hardware or software stacks; the recorded output tree digest is the local output identity.

   The current base/adapter digest workflow is operator-supplied trust on first use (TOFU), not independent authentication of training inputs. Package version strings also do not bind the installed wheel contents or the complete training environment. External release remains blocked until reviewers independently approve the upstream base and adapter provenance, licenses, exact digests, and a hermetic training environment or reviewed wheel/environment tree lock. This pipeline records and checks the local result; it does not replace a reproducible training supply chain.

8. Install the LiteRT Torch exporter and LiteRT-LM CLI in a separate local environment using the current official instructions. Do not add an invented exporter version pin to this repository. Before each real run, resolve each executable to an absolute non-symlink path and pin its current SHA-256; the pipeline also records its exact `--version` output, file identity, and digest.

   - Gemma 4 LiteRT-LM guide: `https://developers.google.com/edge/litert-lm/models/gemma-4`
   - LiteRT Torch conversion guide: `https://developers.google.com/edge/litert/conversion/pytorch/genai`
   - LiteRT-LM CLI guide: `https://developers.google.com/edge/litert-lm/cli/usage`

   The current official exporter install package is `litert-torch-nightly`. Check the upstream guide at release time because that tool is independently versioned and may change.

   ```bash
   command -v litert-torch
   sha256sum /absolute/path/to/litert-torch
   command -v litert-lm
   sha256sum /absolute/path/to/litert-lm
   ```

   Real commands reject relative paths, symlink executables, group/world-writable executables, changed inode metadata, or a SHA-256 mismatch. The launcher is opened and rehashed before descriptor-pinned execution, then checked again after execution. Caller credentials, proxy variables, Python injection variables, dynamic-loader injection variables, and user home/cache paths are not forwarded to release subprocesses.

   A Python console-launcher digest plus interpreter digest does **not** bind imported `site-packages`, native libraries, or their transitive dependencies. A real release therefore requires a hermetic, network-isolated environment/container or an independently reviewed wheel and environment-tree lock in addition to these launcher checks. The bounded stdout/stderr files are polled every 50 ms, so a trusted pinned local tool can briefly exceed the configured output cap between polls; this is a documented residual, not a sandbox for hostile tools. Do not add `RLIMIT_FSIZE` merely to close that polling interval because it would also cap the legitimate multi-gigabyte model file written by the exporter.

9. Fetch the exact pinned local Gemma 4 Jinja template, inspect the export plan, and export:

   ```bash
   make -f model-training/Makefile download-export-template-dry-run
   make -f model-training/Makefile download-export-template
   make -f model-training/Makefile export-plan
   make -f model-training/Makefile export \
     LITERT_TORCH=/absolute/path/to/litert-torch \
     LITERT_TORCH_SHA256=<sha256>
   ```

   The real wrapper executes the official local command shape:

   ```text
   litert-torch export_hf --model=<local merged dir> --output_dir=<ignored staging dir> --quantization_recipe=dynamic_wi8_afp32 --externalize_embedder --jinja_chat_template_override=<pinned local file>
   ```

   Merge and export accept local paths only and set the standard Hugging Face, Transformers, and Datasets offline flags. For OS-level assurance, run the release commands on a network-isolated build host. The template setup helper is the only intentionally networked release-preparation command. It performs an unauthenticated, proxy-free GET to one pinned commit-specific HTTPS raw URL, rejects redirects, applies connect/read and overall time bounds, requires HTTP 200 plus `text/plain` and the exact `Content-Length`, reads at most the expected size plus one byte, and verifies the pinned SHA-256 before atomic publication. Export accepts only the resulting local file. The wrapper revalidates merged-tree, merge-provenance, template, launcher, and published-file identities after conversion; validates the app's bounded LiteRT-LM v1 container shape; publishes without overwrite under ignored `outputs/`; and records launcher/interpreter identity and version output, exact command, merged-tree digest, template identity, explicitly selected `dynamic_wi8_afp32` recipe, byte count, and model SHA-256. Release IDs and file names use `wi8-afp32`; actual size/digest and remote transport remain unset until a real artifact passes every gate.

10. Run every held-out prompt through the official local CLI, then create local release metadata:

   ```bash
   make -f model-training/Makefile litert-eval-plan
   make -f model-training/Makefile litert-eval \
     LITERT_LM=/absolute/path/to/litert-lm \
     LITERT_LM_SHA256=<sha256>
   make -f model-training/Makefile local-release-manifest
   ```

   Each fixture invokes `litert-lm run <model.litertlm> --prompt=<synthetic prompt>` with local inputs and model-library offline flags. After line-ending normalization and outer trim, the entire stdout must be exactly one valid four-line grounded answer; logs or any other prefix/suffix text fail the gate. The config pins the full 30-record fixture file by exact count and SHA-256. Fixture and prediction JSONL are each read once through a bounded `O_NOFOLLOW` regular-file snapshot, hashed and parsed from those same bytes, then identity-checked again before publication. The wrapper also rechecks the model, export-provenance, and runner identities. All 30 fixtures and every deterministic metric must pass before `write_export_manifest.py` emits an ignored local release manifest binding model, merge/export provenance, predictions, evaluation summary, runtime catalog baseline, sizes, SHA-256 values, and observed launcher versions. `--limit` is smoke-only and can never satisfy the full release gate.

   A one-fixture CLI smoke target uses the separate smoke artifact paths and explicitly leaves the release gate false:

   ```bash
   make -f model-training/Makefile litert-smoke-plan
   make -f model-training/Makefile litert-smoke \
     LITERT_LM=/absolute/path/to/litert-lm \
     LITERT_LM_SHA256=<sha256>
   ```

   These commands have fail-closed, dependency-free plan modes that need no weights or tools:

   ```bash
   make -f model-training/Makefile release-plan
   ```

   Plan output always says `planned`; it never reports an artifact as built or tested.

   `make -f model-training/Makefile release-gate` runs `corpus-check`, setup validation, deterministic evaluation, the complete Python test suite, and artifact-policy inspection sequentially before invoking the real LiteRT-LM CLI and local-manifest writer.

11. Run device acceptance, provide the immutable artifact and license URLs, align the catalog label/id/size/hash with the reviewed artifact, and use `sign_model_release_manifest.py` with an external ECDSA P-256 private key. The ignored local manifest is provenance input only; it is not a signed remote manifest and does not enable download.

12. Check git artifact policy before commit:

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
make -f model-training/Makefile download-export-template-dry-run
make -f model-training/Makefile validate-strict
make -f model-training/Makefile train-smoke
make -f model-training/Makefile train
make -f model-training/Makefile release-plan
make -f model-training/Makefile merge-input-digests
make -f model-training/Makefile merge BASE_TREE_SHA256=<sha256> ADAPTER_TREE_SHA256=<sha256> SOURCE_DATE_EPOCH=<epoch>
make -f model-training/Makefile download-export-template
make -f model-training/Makefile export LITERT_TORCH=/absolute/path LITERT_TORCH_SHA256=<sha256>
make -f model-training/Makefile litert-smoke-plan
make -f model-training/Makefile litert-smoke LITERT_LM=/absolute/path LITERT_LM_SHA256=<sha256>
make -f model-training/Makefile litert-eval LITERT_LM=/absolute/path LITERT_LM_SHA256=<sha256>
make -f model-training/Makefile local-release-manifest
make -f model-training/Makefile artifact-policy
```

## Release Rule

Only publish final model files through an immutable HTTPS artifact URL on an approved static host after the full local CLI gate and representative Android-device acceptance. Publish pinned SHA-256 metadata and, for remote manifest updates, an ECDSA P-256 signature verifiable by the app. A local provenance manifest is not publication approval. Do not commit model files, generated provenance/predictions, private keys, or credentials to this repository.
