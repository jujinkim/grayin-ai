from __future__ import annotations

import base64
import importlib.util
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "model-training/scripts/sign_model_release_manifest.py"


@unittest.skipUnless(shutil.which("openssl"), "OpenSSL is required for manifest signing")
class SignedModelReleaseManifestTest(unittest.TestCase):
    def test_signs_canonical_android_compatible_envelope(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            private_key = root / "private.pem"
            public_key = root / "public.pem"
            model_file = root / "grayin-gemma-4-E2B-it-wi8-afp32-v1.litertlm"
            envelope_file = root / "manifest.json"
            model_file.write_bytes(b"LITERTLM" + bytes(1024 * 1024 - 8))
            subprocess.run(
                ["openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", private_key],
                check=True,
                capture_output=True,
            )
            subprocess.run(
                ["openssl", "pkey", "-in", private_key, "-pubout", "-out", public_key],
                check=True,
                capture_output=True,
            )

            subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    "--model-file",
                    str(model_file),
                    "--model-id",
                    "grayin-gemma-4-E2B-it-wi8-afp32-v1",
                    "--release-version",
                    "1.0.0",
                    "--download-url",
                    "https://models.example.test/releases/v1/model.litertlm",
                    "--license-url",
                    "https://models.example.test/terms/v1",
                    "--sequence",
                    "7",
                    "--issued-at-epoch-seconds",
                    "1784073600",
                    "--expires-at-epoch-seconds",
                    "1784160000",
                    "--minimum-app-version-code",
                    "1",
                    "--key-id",
                    "grayin-model-manifest-test-1",
                    "--private-key",
                    str(private_key),
                    "--public-key",
                    str(public_key),
                    "--out",
                    str(envelope_file),
                ],
                cwd=REPO_ROOT,
                check=True,
                capture_output=True,
            )

            encoded = envelope_file.read_bytes()
            self.assertFalse(encoded.startswith(b" "))
            envelope = json.loads(encoded)
            self.assertEqual(["keyId", "payload", "signature"], list(envelope))
            payload_bytes = decode_base64url(envelope["payload"])
            fixture = (
                REPO_ROOT / "app/src/test/resources/model_manifest_payload_v1.json"
            ).read_bytes().rstrip(b"\n")
            self.assertEqual(fixture, payload_bytes)
            payload = json.loads(payload_bytes)
            self.assertEqual(
                [
                    "schemaVersion",
                    "sequence",
                    "issuedAtEpochSeconds",
                    "expiresAtEpochSeconds",
                    "minimumAppVersionCode",
                    "models",
                ],
                list(payload),
            )
            self.assertEqual("0.13.1", payload["models"][0]["liteRtLmRuntimeVersion"])
            self.assertEqual(1, payload["models"][0]["containerMajorVersion"])
            self.assertEqual(1024 * 1024, payload["models"][0]["sizeBytes"])

    def test_rejects_private_key_inside_repository(self) -> None:
        spec = importlib.util.spec_from_file_location("sign_model_release_manifest", SCRIPT)
        self.assertIsNotNone(spec)
        self.assertIsNotNone(spec.loader)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)

        with self.assertRaisesRegex(ValueError, "outside the repository"):
            module.require_external_private_key(SCRIPT)


def decode_base64url(value: str) -> bytes:
    padding = "=" * (-len(value) % 4)
    return base64.urlsafe_b64decode(value + padding)


if __name__ == "__main__":
    unittest.main()
