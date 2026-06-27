# Model Training Runs

This file records local training attempts without storing model artifacts in git.

## 2026-06-27: Gemma 4 E2B LoRA Smoke

Purpose:

- Verify reference Gemma weights, CUDA dependencies, QLoRA load, language-only LoRA targeting, synthetic corpus, and ignored output path.

Command:

```bash
.venv-model-training/bin/python model-training/scripts/train_lora.py \
  --config model-training/configs/grayin_gemma_lora_smoke.yaml
```

Environment:

- GPU: NVIDIA GeForce RTX 4070 Laptop GPU, 8 GB VRAM.
- Torch: 2.12.1+cu130.
- Base model: `google/gemma-4-E2B-it@70af34e20bd4b7a91f0de6b22675850c43922a03`.
- Base model local path: ignored `model-training/reference-models/gemma-4-e2b-it/`.

Result:

- One training step completed.
- Loss: `4.179`.
- Mean token accuracy: `0.6455`.
- Runtime: `9.837s` after model load.

Ignored artifacts:

- `model-training/outputs/adapters/grayin-gemma-4-e2b-lora-smoke/adapter_model.safetensors`
- `model-training/outputs/adapters/grayin-gemma-4-e2b-lora-smoke/checkpoint-1/`

Notes:

- Smoke adapter is not release-ready.
- Production training should use `model-training/configs/grayin_gemma_lora.yaml` or a larger curated synthetic dataset.
- Keep all reference weights, checkpoints, adapters, merged weights, and `.litertlm` exports outside git.
