PYTHON ?= python3
CONFIG ?= model-training/configs/grayin_gemma_lora.yaml
SMOKE_CONFIG ?= model-training/configs/grayin_gemma_lora_smoke.yaml

.PHONY: corpus validate validate-strict download-reference-dry-run train train-smoke artifact-policy

corpus:
	$(PYTHON) model-training/scripts/build_training_corpus.py

validate:
	$(PYTHON) model-training/scripts/validate_training_setup.py --config $(CONFIG)

validate-strict:
	$(PYTHON) model-training/scripts/validate_training_setup.py --config $(CONFIG) --require-reference-model

download-reference-dry-run:
	$(PYTHON) model-training/scripts/download_reference_model.py --config $(CONFIG) --dry-run

train:
	$(PYTHON) model-training/scripts/train_lora.py --config $(CONFIG)

train-smoke:
	$(PYTHON) model-training/scripts/train_lora.py --config $(SMOKE_CONFIG)

artifact-policy:
	$(PYTHON) model-training/scripts/check_artifact_policy.py
