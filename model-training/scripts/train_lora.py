#!/usr/bin/env python3
"""Run LoRA/QLoRA supervised tuning for Grayin synthetic behavior data."""

from __future__ import annotations

import argparse
from pathlib import Path

import torch
import yaml
from datasets import load_dataset
from peft import LoraConfig
from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig
from trl import SFTConfig, SFTTrainer


def load_config(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as config_file:
        return yaml.safe_load(config_file)


def dtype_from_config(value: str):
    if value == "bfloat16":
        return torch.bfloat16
    if value == "float16":
        return torch.float16
    if value == "float32":
        return torch.float32
    raise ValueError(f"Unsupported torch dtype: {value}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True, type=Path)
    args = parser.parse_args()

    config = load_config(args.config)
    model_config = config["model"]
    dataset_config = config["dataset"]
    lora_config = config["lora"]
    quant_config = config["quantization"]
    training_config = config["training"]

    base_model_path = model_config["base_model_path"]
    torch_dtype = dtype_from_config(model_config["torch_dtype"])

    tokenizer = AutoTokenizer.from_pretrained(
        base_model_path,
        trust_remote_code=model_config["trust_remote_code"],
    )
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    quantization_config = None
    if quant_config["load_in_4bit"]:
        quantization_config = BitsAndBytesConfig(
            load_in_4bit=True,
            bnb_4bit_quant_type=quant_config["bnb_4bit_quant_type"],
            bnb_4bit_compute_dtype=dtype_from_config(quant_config["bnb_4bit_compute_dtype"]),
            bnb_4bit_use_double_quant=quant_config.get("bnb_4bit_use_double_quant", False),
        )

    device_map = model_config.get("device_map", "auto")

    model = AutoModelForCausalLM.from_pretrained(
        base_model_path,
        dtype=torch_dtype,
        device_map=device_map,
        quantization_config=quantization_config,
        trust_remote_code=model_config["trust_remote_code"],
    )

    dataset = load_dataset("json", data_files=dataset_config["train_jsonl"], split="train")

    peft_config = LoraConfig(
        r=lora_config["r"],
        lora_alpha=lora_config["alpha"],
        lora_dropout=lora_config["dropout"],
        target_modules=lora_config["target_modules"],
        task_type="CAUSAL_LM",
    )

    trainer = SFTTrainer(
        model=model,
        train_dataset=dataset,
        peft_config=peft_config,
        processing_class=tokenizer,
        args=SFTConfig(
            output_dir=training_config["output_dir"],
            dataset_text_field=dataset_config["text_field"],
            max_length=dataset_config["max_seq_length"],
            per_device_train_batch_size=training_config["per_device_train_batch_size"],
            gradient_accumulation_steps=training_config["gradient_accumulation_steps"],
            learning_rate=training_config["learning_rate"],
            num_train_epochs=training_config["num_train_epochs"],
            max_steps=training_config.get("max_steps", -1),
            warmup_ratio=training_config["warmup_ratio"],
            logging_steps=training_config["logging_steps"],
            save_steps=training_config["save_steps"],
            save_total_limit=training_config["save_total_limit"],
            gradient_checkpointing=training_config["gradient_checkpointing"],
            packing=training_config["packing"],
            report_to=[],
            seed=config["run"]["seed"],
        ),
    )
    trainer.train()
    trainer.save_model(training_config["output_dir"])
    tokenizer.save_pretrained(training_config["output_dir"])


if __name__ == "__main__":
    main()
