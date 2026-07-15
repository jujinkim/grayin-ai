PYTHON ?= python3
PYTHON_FLAGS ?= -B
CONFIG ?= model-training/configs/grayin_gemma_lora.yaml
SMOKE_CONFIG ?= model-training/configs/grayin_gemma_lora_smoke.yaml
BASE_TREE_SHA256 ?=
ADAPTER_TREE_SHA256 ?=
SOURCE_DATE_EPOCH ?= 0
LITERT_TORCH ?= litert-torch
LITERT_LM ?= litert-lm
LITERT_TORCH_SHA256 ?=
LITERT_LM_SHA256 ?=

.PHONY: corpus corpus-check test eval validate validate-strict download-reference-dry-run \
	download-export-template-dry-run download-export-template train train-smoke \
	merge-plan merge-input-digests merge export-plan export litert-eval-plan \
	litert-eval litert-smoke-plan litert-smoke local-release-plan \
	local-release-manifest release-plan release-gate \
	artifact-policy

corpus:
	$(PYTHON) $(PYTHON_FLAGS) model-training/scripts/build_training_corpus.py

corpus-check:
	$(PYTHON) $(PYTHON_FLAGS) model-training/scripts/build_training_corpus.py --check

test:
	$(PYTHON) $(PYTHON_FLAGS) -m unittest discover -s model-training/tests -p 'test_*.py' -v

eval:
	$(PYTHON) $(PYTHON_FLAGS) model-training/scripts/run_grounded_eval.py

validate:
	$(PYTHON) $(PYTHON_FLAGS) model-training/scripts/validate_training_setup.py --config "$(CONFIG)"

validate-strict:
	$(PYTHON) $(PYTHON_FLAGS) model-training/scripts/validate_training_setup.py --config "$(CONFIG)" --require-reference-model

download-reference-dry-run:
	$(PYTHON) $(PYTHON_FLAGS) model-training/scripts/download_reference_model.py --config "$(CONFIG)" --dry-run

download-export-template-dry-run:
	$(PYTHON) $(PYTHON_FLAGS) model-training/scripts/download_litert_template.py --config "$(CONFIG)" --dry-run

download-export-template:
	$(PYTHON) $(PYTHON_FLAGS) model-training/scripts/download_litert_template.py --config "$(CONFIG)"

train:
	$(PYTHON) $(PYTHON_FLAGS) model-training/scripts/train_lora.py --config "$(CONFIG)"

train-smoke:
	$(PYTHON) $(PYTHON_FLAGS) model-training/scripts/train_lora.py --config "$(SMOKE_CONFIG)"

merge-plan:
	$(PYTHON) $(PYTHON_FLAGS) model-training/scripts/merge_lora.py --config "$(CONFIG)" --dry-run

merge-input-digests:
	$(PYTHON) $(PYTHON_FLAGS) model-training/scripts/merge_lora.py --config "$(CONFIG)" --print-input-digests

merge:
	$(PYTHON) $(PYTHON_FLAGS) model-training/scripts/merge_lora.py --config "$(CONFIG)" \
		--expected-base-sha256="$(BASE_TREE_SHA256)" \
		--expected-adapter-sha256="$(ADAPTER_TREE_SHA256)" \
		--source-date-epoch="$(SOURCE_DATE_EPOCH)"

export-plan:
	$(PYTHON) $(PYTHON_FLAGS) model-training/scripts/export_litertlm.py --config "$(CONFIG)" --tool "$(LITERT_TORCH)" --dry-run

export:
	$(PYTHON) $(PYTHON_FLAGS) model-training/scripts/export_litertlm.py --config "$(CONFIG)" \
		--tool "$(LITERT_TORCH)" --expected-tool-sha256="$(LITERT_TORCH_SHA256)"

litert-eval-plan:
	$(PYTHON) $(PYTHON_FLAGS) model-training/scripts/run_litert_eval.py --config "$(CONFIG)" --tool "$(LITERT_LM)" --dry-run

litert-eval:
	$(PYTHON) $(PYTHON_FLAGS) model-training/scripts/run_litert_eval.py --config "$(CONFIG)" \
		--tool "$(LITERT_LM)" --expected-tool-sha256="$(LITERT_LM_SHA256)"

litert-smoke-plan:
	$(PYTHON) $(PYTHON_FLAGS) model-training/scripts/run_litert_eval.py --config "$(SMOKE_CONFIG)" --tool "$(LITERT_LM)" --limit 1 --dry-run

litert-smoke:
	$(PYTHON) $(PYTHON_FLAGS) model-training/scripts/run_litert_eval.py --config "$(SMOKE_CONFIG)" \
		--tool "$(LITERT_LM)" --expected-tool-sha256="$(LITERT_LM_SHA256)" --limit 1

local-release-plan:
	$(PYTHON) $(PYTHON_FLAGS) model-training/scripts/write_export_manifest.py --config "$(CONFIG)" --dry-run

local-release-manifest:
	$(PYTHON) $(PYTHON_FLAGS) model-training/scripts/write_export_manifest.py --config "$(CONFIG)"

release-plan:
	$(MAKE) -f model-training/Makefile CONFIG=$(CONFIG) merge-plan
	$(MAKE) -f model-training/Makefile CONFIG=$(CONFIG) export-plan
	$(MAKE) -f model-training/Makefile CONFIG=$(CONFIG) litert-eval-plan
	$(MAKE) -f model-training/Makefile CONFIG=$(CONFIG) local-release-plan

release-gate:
	$(MAKE) -f model-training/Makefile CONFIG=$(CONFIG) corpus-check
	$(MAKE) -f model-training/Makefile CONFIG=$(CONFIG) validate
	$(MAKE) -f model-training/Makefile CONFIG=$(CONFIG) eval
	$(MAKE) -f model-training/Makefile CONFIG=$(CONFIG) test
	$(MAKE) -f model-training/Makefile CONFIG=$(CONFIG) artifact-policy
	$(MAKE) -f model-training/Makefile CONFIG=$(CONFIG) litert-eval
	$(MAKE) -f model-training/Makefile CONFIG=$(CONFIG) local-release-manifest

artifact-policy:
	$(PYTHON) $(PYTHON_FLAGS) model-training/scripts/check_artifact_policy.py
