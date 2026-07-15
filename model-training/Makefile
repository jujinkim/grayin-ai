PYTHON ?= python3
PYTHON_FLAGS ?= -B
CONFIG ?= model-training/configs/grayin_gemma_lora.yaml
SMOKE_CONFIG ?= model-training/configs/grayin_gemma_lora_smoke.yaml

.PHONY: corpus corpus-check test eval validate validate-strict download-reference-dry-run train train-smoke artifact-policy

corpus:
	$(PYTHON) $(PYTHON_FLAGS) model-training/scripts/build_training_corpus.py

corpus-check:
	$(PYTHON) $(PYTHON_FLAGS) model-training/scripts/build_training_corpus.py --check

test:
	$(PYTHON) $(PYTHON_FLAGS) -m unittest discover -s model-training/tests -p 'test_*.py' -v

eval:
	$(PYTHON) $(PYTHON_FLAGS) model-training/scripts/run_grounded_eval.py

validate:
	$(PYTHON) $(PYTHON_FLAGS) model-training/scripts/validate_training_setup.py --config $(CONFIG)

validate-strict:
	$(PYTHON) $(PYTHON_FLAGS) model-training/scripts/validate_training_setup.py --config $(CONFIG) --require-reference-model

download-reference-dry-run:
	$(PYTHON) $(PYTHON_FLAGS) model-training/scripts/download_reference_model.py --config $(CONFIG) --dry-run

train:
	$(PYTHON) $(PYTHON_FLAGS) model-training/scripts/train_lora.py --config $(CONFIG)

train-smoke:
	$(PYTHON) $(PYTHON_FLAGS) model-training/scripts/train_lora.py --config $(SMOKE_CONFIG)

artifact-policy:
	$(PYTHON) $(PYTHON_FLAGS) model-training/scripts/check_artifact_policy.py
