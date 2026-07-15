#!/usr/bin/env python3
"""Fetch the pinned Gemma 4 LiteRT-LM chat template into ignored local storage."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import ssl
import time
import urllib.request

from release_pipeline import (
    REPO_ROOT,
    atomic_write_bytes,
    load_release_config,
    regular_file_size,
    repo_relative,
    require_git_ignored,
    sanitized_environment,
    sha256_file,
)


DEFAULT_CONFIG = REPO_ROOT / "model-training/configs/grayin_gemma_lora.yaml"
TEMPLATE_REPO_ID = "litert-community/gemma-4-E2B-it-litert-lm"
TEMPLATE_REVISION = "d23202ebbc77c976719090aaa080362f29d746e2"
TEMPLATE_FILE_NAME = "chat_template.jinja"
TEMPLATE_SIZE_BYTES = 11_995
TEMPLATE_SHA256 = "02b3091acf53c0b722e3db0c7a1b4980363edcc2d85549dafa339ff5dbfff629"
TEMPLATE_RAW_URL = (
    "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/raw/"
    f"{TEMPLATE_REVISION}/{TEMPLATE_FILE_NAME}"
)
CONNECT_READ_TIMEOUT_SECONDS = 10
OVERALL_TIMEOUT_SECONDS = 30


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        return None


def build_template_opener():
    return urllib.request.build_opener(
        urllib.request.ProxyHandler({}),
        urllib.request.HTTPSHandler(context=ssl.create_default_context()),
        NoRedirectHandler(),
    )


def fetch_template_payload(opener=None, monotonic=time.monotonic) -> bytes:
    client = opener if opener is not None else build_template_opener()
    request = urllib.request.Request(
        TEMPLATE_RAW_URL,
        headers={
            "Accept": "text/plain",
            "User-Agent": "grayin-model-release/1",
        },
        method="GET",
    )
    started = monotonic()
    with client.open(request, timeout=CONNECT_READ_TIMEOUT_SECONDS) as response:
        if monotonic() - started > OVERALL_TIMEOUT_SECONDS:
            raise TimeoutError("pinned template request exceeded the overall timeout")
        if getattr(response, "status", None) != 200:
            raise ValueError("pinned template endpoint did not return HTTP 200")
        if response.geturl() != TEMPLATE_RAW_URL:
            raise ValueError("pinned template endpoint redirected unexpectedly")
        media_type = (response.headers.get("Content-Type") or "").split(";", 1)[0].strip().lower()
        if media_type != "text/plain":
            raise ValueError("pinned template endpoint returned an unexpected media type")
        content_length = response.headers.get("Content-Length")
        try:
            declared_size = int(content_length or "")
        except ValueError as error:
            raise ValueError("pinned template endpoint omitted a valid Content-Length") from error
        if declared_size != TEMPLATE_SIZE_BYTES:
            raise ValueError("pinned template endpoint returned an unexpected Content-Length")
        payload = response.read(TEMPLATE_SIZE_BYTES + 1)
    if monotonic() - started > OVERALL_TIMEOUT_SECONDS:
        raise TimeoutError("pinned template request exceeded the overall timeout")
    if len(payload) != TEMPLATE_SIZE_BYTES:
        raise ValueError("pinned template response length does not match the release pin")
    if hashlib.sha256(payload).hexdigest() != TEMPLATE_SHA256:
        raise ValueError("pinned template response does not match the release SHA-256")
    return payload


def verify_template(path: Path) -> None:
    if path.is_symlink() or not path.is_file():
        raise ValueError(f"expected a regular template file: {path}")
    if regular_file_size(path) != TEMPLATE_SIZE_BYTES or sha256_file(path) != TEMPLATE_SHA256:
        raise ValueError("downloaded chat template does not match the pinned size and SHA-256")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    config = load_release_config(args.config)
    destination = config.chat_template_override_path
    require_git_ignored(destination)
    plan = {
        "status": "planned",
        "source_repo_id": TEMPLATE_REPO_ID,
        "source_revision": TEMPLATE_REVISION,
        "source_file": TEMPLATE_FILE_NAME,
        "source_url": TEMPLATE_RAW_URL,
        "destination": repo_relative(destination),
        "expected_size_bytes": TEMPLATE_SIZE_BYTES,
        "expected_sha256": TEMPLATE_SHA256,
    }
    if args.dry_run:
        print(json.dumps(plan, indent=2, sort_keys=True))
        return

    if destination.exists() or destination.is_symlink():
        verify_template(destination)
        print(f"verified pinned template at {repo_relative(destination)}")
        return

    with sanitized_environment(offline=False):
        payload = fetch_template_payload()
    atomic_write_bytes(destination, payload)
    verify_template(destination)
    print(f"downloaded and verified pinned template at {repo_relative(destination)}")


if __name__ == "__main__":
    main()
