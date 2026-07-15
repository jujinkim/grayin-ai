#!/usr/bin/env python3
"""Fetch the pinned Gemma 4 LiteRT-LM chat template into ignored local storage."""

from __future__ import annotations

import argparse
from contextlib import contextmanager
import hashlib
import json
from pathlib import Path
import signal
import ssl
import threading
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
READ_CHUNK_BYTES = 4 * 1024


class _HardDeadlineSignal(BaseException):
    pass


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        return None


def build_template_opener():
    return urllib.request.build_opener(
        urllib.request.ProxyHandler({}),
        urllib.request.HTTPSHandler(context=ssl.create_default_context()),
        NoRedirectHandler(),
    )


@contextmanager
def _hard_request_deadline(timeout_seconds: float):
    """Apply a process-level hard deadline without replacing an active alarm."""
    if threading.current_thread() is not threading.main_thread():
        raise RuntimeError("pinned template hard deadline requires the main thread")
    if not all(
        hasattr(signal, attribute)
        for attribute in ("SIGALRM", "ITIMER_REAL", "getitimer", "setitimer")
    ):
        raise RuntimeError("pinned template hard deadline requires POSIX interval timers")
    previous_timer = signal.getitimer(signal.ITIMER_REAL)
    if previous_timer[0] > 0 or previous_timer[1] > 0:
        raise RuntimeError("pinned template hard deadline refuses to replace an active alarm")

    previous_handler = signal.getsignal(signal.SIGALRM)
    deadline_fired = False

    def handle_timeout(_signal_number, _frame) -> None:
        nonlocal deadline_fired
        deadline_fired = True
        raise _HardDeadlineSignal()

    signal.signal(signal.SIGALRM, handle_timeout)
    timer_armed = False
    deadline_signal: _HardDeadlineSignal | None = None
    try:
        try:
            signal.setitimer(signal.ITIMER_REAL, timeout_seconds)
            timer_armed = True
            yield
        finally:
            try:
                if timer_armed:
                    signal.setitimer(signal.ITIMER_REAL, 0)
            finally:
                signal.signal(signal.SIGALRM, previous_handler)
    except _HardDeadlineSignal as error:
        deadline_signal = error
    if deadline_signal is not None or deadline_fired:
        raise TimeoutError(
            "pinned template request exceeded the overall timeout",
        ) from deadline_signal


def _ensure_before_deadline(deadline: float, monotonic) -> None:
    if deadline - monotonic() <= 0:
        raise TimeoutError("pinned template request exceeded the overall timeout")


def _read_bounded_payload(response, deadline: float, monotonic) -> bytes:
    read_once = getattr(response, "read1", None)
    if not callable(read_once):
        raise RuntimeError("pinned template response does not support bounded single reads")

    chunks: list[bytes] = []
    remaining_bytes = TEMPLATE_SIZE_BYTES + 1
    while remaining_bytes > 0:
        _ensure_before_deadline(deadline, monotonic)
        requested_bytes = min(READ_CHUNK_BYTES, remaining_bytes)
        try:
            chunk = read_once(requested_bytes)
        except TimeoutError as error:
            raise TimeoutError("pinned template request exceeded a read timeout") from error
        if monotonic() >= deadline:
            raise TimeoutError("pinned template request exceeded the overall timeout")
        if not chunk:
            break
        if not isinstance(chunk, bytes) or len(chunk) > requested_bytes:
            raise ValueError("pinned template response violated the bounded read contract")
        chunks.append(chunk)
        remaining_bytes -= len(chunk)
    return b"".join(chunks)


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
    with _hard_request_deadline(OVERALL_TIMEOUT_SECONDS):
        deadline = monotonic() + OVERALL_TIMEOUT_SECONDS
        with client.open(request, timeout=CONNECT_READ_TIMEOUT_SECONDS) as response:
            _ensure_before_deadline(deadline, monotonic)
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
            payload = _read_bounded_payload(response, deadline, monotonic)
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
