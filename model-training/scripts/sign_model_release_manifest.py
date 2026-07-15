#!/usr/bin/env python3
"""Build and sign Grayin's canonical remote model-release manifest.

The model file and private key must remain outside git. The output envelope is
bounded and canonical so the Android verifier can reject ambiguous JSON before
accepting an ECDSA P-256 signature.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import re
import subprocess
import tempfile
from pathlib import Path
from urllib.parse import urlsplit


REPO_ROOT = Path(__file__).resolve().parents[2]
MAX_MODEL_BYTES = 8 * 1024 * 1024 * 1024
MAX_VALIDITY_SECONDS = 31 * 24 * 60 * 60
SAFE_ID = re.compile(r"[A-Za-z0-9._-]{1,80}\Z")
SAFE_VERSION = re.compile(r"[A-Za-z0-9][A-Za-z0-9._+-]{0,63}\Z")
SAFE_FILE_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}\Z")
SUPPORTED_LITERTLM_RUNTIME_VERSION = "0.13.1"
EC_PUBLIC_KEY_OID_DER = bytes.fromhex("06072a8648ce3d0201")
P256_CURVE_OID_DER = bytes.fromhex("06082a8648ce3d030107")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for block in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def validate_fixed_https_url(value: str, field_name: str) -> None:
    parsed = urlsplit(value)
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.port is not None
        or not parsed.path.startswith("/")
        or parsed.query
        or parsed.fragment
    ):
        raise ValueError(f"{field_name} must be a fixed HTTPS origin and path")


def canonical_json(value: dict[str, object]) -> bytes:
    return json.dumps(value, ensure_ascii=True, separators=(",", ":")).encode("utf-8")


def base64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def build_payload(args: argparse.Namespace, model_file: Path) -> dict[str, object]:
    if args.sequence <= 0:
        raise ValueError("sequence must be positive")
    if args.minimum_app_version_code <= 0:
        raise ValueError("minimum app version code must be positive")
    if args.maximum_app_version_code is not None and (
        args.maximum_app_version_code < args.minimum_app_version_code
    ):
        raise ValueError("maximum app version code precedes minimum")
    if args.issued_at_epoch_seconds <= 0 or args.expires_at_epoch_seconds <= args.issued_at_epoch_seconds:
        raise ValueError("manifest validity window is invalid")
    if args.expires_at_epoch_seconds - args.issued_at_epoch_seconds > MAX_VALIDITY_SECONDS:
        raise ValueError("manifest validity exceeds 31 days")
    if not SAFE_ID.fullmatch(args.model_id):
        raise ValueError("model id is invalid")
    if not SAFE_VERSION.fullmatch(args.release_version):
        raise ValueError("release version is invalid")
    if not SAFE_ID.fullmatch(args.key_id):
        raise ValueError("key id is invalid")
    if args.litertlm_runtime_version != SUPPORTED_LITERTLM_RUNTIME_VERSION:
        raise ValueError(f"LiteRT-LM runtime must be {SUPPORTED_LITERTLM_RUNTIME_VERSION}")
    if not SAFE_FILE_NAME.fullmatch(model_file.name) or not model_file.name.endswith(".litertlm"):
        raise ValueError("model file name is invalid")
    size_bytes = model_file.stat().st_size
    if size_bytes < 1024 * 1024 or size_bytes > MAX_MODEL_BYTES:
        raise ValueError("model size is outside the Android import boundary")
    validate_fixed_https_url(args.download_url, "download URL")
    validate_fixed_https_url(args.license_url, "license URL")

    model: dict[str, object] = {
        "modelId": args.model_id,
        "releaseVersion": args.release_version,
        "fileName": model_file.name,
        "downloadUrl": args.download_url,
        "sizeBytes": size_bytes,
        "sha256": sha256_file(model_file),
        "licenseUrl": args.license_url,
        "liteRtLmRuntimeVersion": args.litertlm_runtime_version,
        "containerMajorVersion": 1,
        "deprecated": args.deprecated,
    }
    if args.replaces_version is not None:
        if not SAFE_VERSION.fullmatch(args.replaces_version):
            raise ValueError("replaced version is invalid")
        model["replacesVersion"] = args.replaces_version

    payload: dict[str, object] = {
        "schemaVersion": 1,
        "sequence": args.sequence,
        "issuedAtEpochSeconds": args.issued_at_epoch_seconds,
        "expiresAtEpochSeconds": args.expires_at_epoch_seconds,
        "minimumAppVersionCode": args.minimum_app_version_code,
    }
    if args.maximum_app_version_code is not None:
        payload["maximumAppVersionCode"] = args.maximum_app_version_code
    payload["models"] = [model]
    return payload


def require_external_private_key(path: Path) -> Path:
    resolved = path.expanduser().resolve(strict=True)
    try:
        resolved.relative_to(REPO_ROOT)
    except ValueError:
        return resolved
    raise ValueError("private signing key must remain outside the repository")


def require_p256_public_key(path: Path) -> None:
    encoded = subprocess.run(
        ["openssl", "pkey", "-pubin", "-in", str(path), "-pubout", "-outform", "DER"],
        check=True,
        capture_output=True,
    ).stdout
    if EC_PUBLIC_KEY_OID_DER not in encoded or P256_CURVE_OID_DER not in encoded:
        raise ValueError("manifest public key must be ECDSA P-256")


def verify_with_openssl(payload: bytes, signature: bytes, public_key: Path, work_dir: Path) -> None:
    with tempfile.TemporaryDirectory(prefix=".grayin-manifest-", dir=work_dir) as temporary:
        temporary_dir = Path(temporary)
        signature_file = temporary_dir / "signature.der"
        payload_file = temporary_dir / "payload.json"
        signature_file.write_bytes(signature)
        payload_file.write_bytes(payload)
        subprocess.run(
            [
                "openssl",
                "dgst",
                "-sha256",
                "-verify",
                str(public_key),
                "-signature",
                str(signature_file),
                str(payload_file),
            ],
            check=True,
            capture_output=True,
        )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-file", required=True, type=Path)
    parser.add_argument("--model-id", required=True)
    parser.add_argument("--release-version", required=True)
    parser.add_argument("--download-url", required=True)
    parser.add_argument("--license-url", required=True)
    parser.add_argument("--litertlm-runtime-version", default="0.13.1")
    parser.add_argument("--sequence", required=True, type=int)
    parser.add_argument("--issued-at-epoch-seconds", required=True, type=int)
    parser.add_argument("--expires-at-epoch-seconds", required=True, type=int)
    parser.add_argument("--minimum-app-version-code", required=True, type=int)
    parser.add_argument("--maximum-app-version-code", type=int)
    parser.add_argument("--replaces-version")
    parser.add_argument("--deprecated", action="store_true")
    parser.add_argument("--key-id", required=True)
    parser.add_argument("--private-key", required=True, type=Path)
    parser.add_argument("--public-key", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()

    model_file = args.model_file.expanduser().resolve(strict=True)
    if not model_file.is_file():
        raise FileNotFoundError(model_file)
    private_key = require_external_private_key(args.private_key)
    public_key = args.public_key.expanduser().resolve(strict=True)
    if not public_key.is_file():
        raise FileNotFoundError(public_key)
    require_p256_public_key(public_key)

    payload = canonical_json(build_payload(args, model_file))
    signature = subprocess.run(
        ["openssl", "dgst", "-sha256", "-sign", str(private_key)],
        input=payload,
        check=True,
        capture_output=True,
    ).stdout

    args.out.parent.mkdir(parents=True, exist_ok=True)
    verify_with_openssl(payload, signature, public_key, args.out.parent)
    envelope = {
        "keyId": args.key_id,
        "payload": base64url(payload),
        "signature": base64url(signature),
    }
    encoded = canonical_json(envelope)
    if len(encoded) > 64 * 1024:
        raise ValueError("signed envelope exceeds Android boundary")
    args.out.write_bytes(encoded)
    print(
        f"wrote signed manifest sequence={args.sequence} "
        f"payload_sha256={hashlib.sha256(payload).hexdigest()} to {args.out}",
    )


if __name__ == "__main__":
    main()
