from __future__ import annotations

from dataclasses import replace
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = REPO_ROOT / "model-training/scripts"
OUTPUT_ROOT = REPO_ROOT / "model-training/outputs"
sys.path.insert(0, str(SCRIPTS))

import download_litert_template as template_download  # noqa: E402
from download_litert_template import TEMPLATE_RAW_URL  # noqa: E402
from export_litertlm import build_export_command  # noqa: E402
from release_pipeline import (  # noqa: E402
    LITERTLM_MAGIC,
    LITERTLM_PREAMBLE_BYTES,
    MIN_LITERTLM_BYTES,
    atomic_write_json,
    load_release_config,
    publish_directory_no_replace,
    publish_file_no_replace,
    read_regular_snapshot,
    read_flat_yaml,
    regular_file_size,
    release_environment,
    repo_relative,
    require_git_ignored,
    require_litertlm_file,
    resolve_repo_path,
    resolve_tool,
    run_bounded_command,
    sha256_file,
    tool_version,
    tree_digest,
    verify_regular_snapshot,
    verify_pinned_tool,
)
from run_grounded_eval import evaluate, load_jsonl  # noqa: E402
from run_litert_eval import (  # noqa: E402
    build_run_command,
    extract_grounded_answer,
    load_pinned_eval_fixtures,
)
from write_export_manifest import validated_release_inputs  # noqa: E402


DEFAULT_CONFIG = REPO_ROOT / "model-training/configs/grayin_gemma_lora.yaml"


def write_fake_litertlm(path: Path) -> None:
    header = bytearray(16)
    struct.pack_into("<I", header, 0, 8)
    struct.pack_into("<HH", header, 4, 4, 4)
    struct.pack_into("<i", header, 8, 4)
    preamble = struct.pack(
        "<8siiiiq",
        LITERTLM_MAGIC,
        1,
        0,
        0,
        0,
        LITERTLM_PREAMBLE_BYTES + len(header),
    )
    path.write_bytes(preamble + header + bytes(MIN_LITERTLM_BYTES - len(preamble) - len(header)))


def write_fake_tool(path: Path, version: str) -> object:
    path.write_text(f"#!/bin/sh\nprintf '{version}\\n'\n", encoding="utf-8")
    path.chmod(0o700)
    return resolve_tool(str(path), sha256_file(path))


class ReleaseConfigTest(unittest.TestCase):
    def test_release_config_scopes_generated_paths_to_ignored_roots(self) -> None:
        config = load_release_config(DEFAULT_CONFIG)

        self.assertEqual("dynamic_wi8_afp32", config.quantization)
        self.assertIn("wi8-afp32", config.model_id.lower())
        self.assertIn("wi8-afp32", config.litert_output_file.name.lower())
        self.assertEqual(30, config.eval_fixture_count)
        self.assertEqual(sha256_file(config.eval_fixtures), config.eval_fixture_sha256)

        for path in (
            config.adapter_path,
            config.merged_output_dir,
            config.merge_provenance_file,
            config.litert_output_file,
            config.export_provenance_file,
            config.local_release_manifest_file,
            config.eval_predictions_file,
            config.eval_summary_file,
            config.chat_template_override_path,
        ):
            require_git_ignored(path)

    def test_flat_yaml_rejects_tab_indentation_and_duplicate_keys(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bad.yaml"
            path.write_text("run:\n\tname: bad\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "tabs"):
                read_flat_yaml(path)
            path.write_text("run:\n  name: one\n  name: two\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "duplicate"):
                read_flat_yaml(path)

    def test_release_paths_and_configs_reject_scope_escape_and_symlink(self) -> None:
        with self.assertRaisesRegex(ValueError, "must stay under"):
            resolve_repo_path("../outside-release-root", OUTPUT_ROOT)
        with self.assertRaisesRegex(ValueError, "model-training/configs"):
            load_release_config(REPO_ROOT / "docs/local-ai.md")
        config_root = REPO_ROOT / "model-training/configs"
        with tempfile.TemporaryDirectory(dir=config_root) as directory:
            link = Path(directory) / "linked.yaml"
            try:
                link.symlink_to(DEFAULT_CONFIG)
            except OSError:
                self.skipTest("symbolic links unavailable")
            with self.assertRaisesRegex(ValueError, "symbolic link"):
                load_release_config(link)


class ArtifactIntegrityTest(unittest.TestCase):
    def test_atomic_json_publication_refuses_overwrite(self) -> None:
        with tempfile.TemporaryDirectory(dir=OUTPUT_ROOT) as directory:
            path = Path(directory) / "metadata.json"
            atomic_write_json(path, {"first": True})
            with self.assertRaises(FileExistsError):
                atomic_write_json(path, {"first": False})
            self.assertEqual({"first": True}, json.loads(path.read_text(encoding="utf-8")))

    def test_atomic_json_rejects_paths_outside_ignored_roots(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "ignored release roots"):
                atomic_write_json(Path(directory) / "metadata.json", {"bad": True})

    def test_ignored_output_rejects_symlinked_parent(self) -> None:
        with tempfile.TemporaryDirectory(dir=OUTPUT_ROOT) as directory, tempfile.TemporaryDirectory() as outside:
            link = Path(directory) / "escape"
            try:
                link.symlink_to(outside, target_is_directory=True)
            except OSError:
                self.skipTest("symbolic links unavailable")
            with self.assertRaisesRegex(ValueError, "symlink"):
                require_git_ignored(link / "metadata.json")

    def test_artifact_read_rejects_symlinked_parent(self) -> None:
        with tempfile.TemporaryDirectory(dir=OUTPUT_ROOT) as directory, tempfile.TemporaryDirectory() as outside:
            target = Path(outside) / "metadata.json"
            target.write_text('{"escaped": true}', encoding="utf-8")
            link = Path(directory) / "escape"
            try:
                link.symlink_to(outside, target_is_directory=True)
            except OSError:
                self.skipTest("symbolic links unavailable")
            with self.assertRaises(OSError):
                sha256_file(link / target.name)

    def test_bounded_snapshot_rejects_symlink_and_fifo_and_detects_path_swap(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "fixtures.jsonl"
            path.write_bytes(b'{"id":"one"}\n')
            snapshot = read_regular_snapshot(path, 1024)
            self.assertEqual(hashlib.sha256(snapshot.data).hexdigest(), snapshot.sha256)
            replacement = root / "replacement.jsonl"
            replacement.write_bytes(b'{"id":"two"}\n')
            os.replace(replacement, path)
            with self.assertRaisesRegex(ValueError, "changed"):
                verify_regular_snapshot(snapshot)

            link = root / "link.jsonl"
            try:
                link.symlink_to(path)
            except OSError:
                self.skipTest("symbolic links unavailable")
            with self.assertRaises(OSError):
                read_regular_snapshot(link, 1024)

            fifo = root / "fixture.fifo"
            try:
                os.mkfifo(fifo)
            except (AttributeError, OSError):
                self.skipTest("FIFOs unavailable")
            with self.assertRaisesRegex(ValueError, "regular"):
                read_regular_snapshot(fifo, 1024)

    def test_file_publication_never_overwrites_existing_destination(self) -> None:
        with tempfile.TemporaryDirectory(dir=OUTPUT_ROOT) as directory:
            root = Path(directory)
            source = root / "source.bin"
            destination = root / "destination.bin"
            source.write_bytes(b"new")
            destination.write_bytes(b"existing")
            with self.assertRaises(FileExistsError):
                publish_file_no_replace(source, destination)
            self.assertEqual(b"existing", destination.read_bytes())

    def test_directory_publication_never_overwrites_existing_destination(self) -> None:
        with tempfile.TemporaryDirectory(dir=OUTPUT_ROOT) as directory:
            root = Path(directory)
            source = root / "source"
            destination = root / "destination"
            source.mkdir()
            destination.mkdir()
            (source / "value").write_text("new", encoding="utf-8")
            (destination / "value").write_text("existing", encoding="utf-8")
            try:
                with self.assertRaises(FileExistsError):
                    publish_directory_no_replace(source, destination)
            except RuntimeError as error:
                self.skipTest(str(error))
            self.assertEqual("existing", (destination / "value").read_text(encoding="utf-8"))
            self.assertEqual("new", (source / "value").read_text(encoding="utf-8"))
            atomic_source = root / "atomic-source"
            atomic_destination = root / "atomic-destination"
            atomic_source.mkdir()
            (atomic_source / "value").write_text("published", encoding="utf-8")
            publish_directory_no_replace(atomic_source, atomic_destination)
            self.assertFalse(atomic_source.exists())
            self.assertEqual(
                "published",
                (atomic_destination / "value").read_text(encoding="utf-8"),
            )
            mismatch_source = root / "mismatch-source"
            mismatch_destination = root / "mismatch-destination"
            mismatch_source.mkdir()
            (mismatch_source / "value").write_text("staged", encoding="utf-8")
            expected_tree = tree_digest(mismatch_source)
            with (
                mock.patch(
                    "release_pipeline.tree_digest",
                    return_value=replace(expected_tree, sha256="0" * 64),
                ),
                self.assertRaisesRegex(ValueError, "differs"),
            ):
                publish_directory_no_replace(
                    mismatch_source,
                    mismatch_destination,
                    expected_tree,
                )
            self.assertFalse(mismatch_source.exists())
            self.assertFalse(mismatch_destination.exists())

    def test_tree_digest_is_order_independent_and_content_bound(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "b.txt").write_text("b", encoding="utf-8")
            (root / "a.txt").write_text("a", encoding="utf-8")
            first = tree_digest(root)
            second = tree_digest(root)
            self.assertEqual(first, second)
            (root / "a.txt").write_text("changed", encoding="utf-8")
            self.assertNotEqual(first.sha256, tree_digest(root).sha256)

    def test_tree_digest_rejects_symbolic_links(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "target"
            target.write_text("content", encoding="utf-8")
            try:
                (root / "link").symlink_to(target)
            except OSError:
                self.skipTest("symbolic links unavailable")
            with self.assertRaisesRegex(ValueError, "symbolic link"):
                tree_digest(root)

    def test_tree_digest_rejects_symbolic_link_root(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            actual = root / "actual"
            actual.mkdir()
            (actual / "value").write_text("content", encoding="utf-8")
            link = root / "link"
            try:
                link.symlink_to(actual, target_is_directory=True)
            except OSError:
                self.skipTest("symbolic links unavailable")
            with self.assertRaisesRegex(ValueError, "non-symlink"):
                tree_digest(link)

    def test_litertlm_policy_accepts_v1_shape_and_rejects_bad_magic(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            model = Path(directory) / "model.litertlm"
            write_fake_litertlm(model)
            metadata = require_litertlm_file(model)
            self.assertEqual(MIN_LITERTLM_BYTES, metadata["size_bytes"])
            self.assertEqual(1, metadata["container_major_version"])
            with model.open("r+b") as file:
                file.write(b"NOTMODEL")
            with self.assertRaisesRegex(ValueError, "preamble"):
                require_litertlm_file(model)


class TemplateDownloadTest(unittest.TestCase):
    class FakeResponse:
        def __init__(
            self,
            payload: bytes,
            *,
            status: int = 200,
            url: str = TEMPLATE_RAW_URL,
            content_type: str = "text/plain; charset=utf-8",
            content_length: int | None = None,
        ) -> None:
            self.payload = payload
            self.status = status
            self.url = url
            self.headers = {
                "Content-Type": content_type,
                "Content-Length": str(len(payload) if content_length is None else content_length),
            }
            self.read_sizes: list[int] = []
            self.offset = 0

        def __enter__(self):
            return self

        def __exit__(self, exception_type, exception, traceback) -> None:
            return None

        def geturl(self) -> str:
            return self.url

        def read1(self, size: int) -> bytes:
            self.read_sizes.append(size)
            chunk = self.payload[self.offset : self.offset + size]
            self.offset += len(chunk)
            return chunk

    class FakeOpener:
        def __init__(self, response) -> None:
            self.response = response
            self.requests: list[object] = []
            self.timeouts: list[int] = []

        def open(self, request, timeout: int):
            self.requests.append(request)
            self.timeouts.append(timeout)
            return self.response

    def test_fixed_template_fetch_is_bounded_unauthenticated_and_digest_checked(self) -> None:
        payload = b"fixed template bytes"
        response = self.FakeResponse(payload)
        opener = self.FakeOpener(response)
        with (
            mock.patch.object(template_download, "TEMPLATE_SIZE_BYTES", len(payload)),
            mock.patch.object(template_download, "TEMPLATE_SHA256", hashlib.sha256(payload).hexdigest()),
        ):
            self.assertEqual(payload, template_download.fetch_template_payload(opener=opener))
        self.assertEqual([len(payload) + 1, 1], response.read_sizes)
        self.assertEqual([template_download.CONNECT_READ_TIMEOUT_SECONDS], opener.timeouts)
        request = opener.requests[0]
        self.assertEqual(TEMPLATE_RAW_URL, request.full_url)
        self.assertEqual("GET", request.get_method())
        headers = {key.lower(): value for key, value in request.header_items()}
        self.assertNotIn("authorization", headers)
        self.assertNotIn("cookie", headers)

    def test_fixed_template_fetch_rejects_redirect_length_type_and_digest_mismatch(self) -> None:
        payload = b"fixed template bytes"
        digest = hashlib.sha256(payload).hexdigest()
        cases = (
            self.FakeResponse(payload, url="https://example.test/redirected"),
            self.FakeResponse(payload, content_length=len(payload) + 1),
            self.FakeResponse(payload, content_type="application/octet-stream"),
            self.FakeResponse(b"x" * len(payload)),
        )
        for response in cases:
            with (
                self.subTest(response=response),
                mock.patch.object(template_download, "TEMPLATE_SIZE_BYTES", len(payload)),
                mock.patch.object(template_download, "TEMPLATE_SHA256", digest),
            ):
                with self.assertRaises(ValueError):
                    template_download.fetch_template_payload(opener=self.FakeOpener(response))

    def test_fixed_template_fetch_enforces_overall_timeout_and_disables_proxies(self) -> None:
        payload = b"fixed template bytes"
        response = self.FakeResponse(payload)
        times = iter((0.0, float(template_download.OVERALL_TIMEOUT_SECONDS + 1)))
        with (
            mock.patch.object(template_download, "TEMPLATE_SIZE_BYTES", len(payload)),
            mock.patch.object(template_download, "TEMPLATE_SHA256", hashlib.sha256(payload).hexdigest()),
            self.assertRaises(TimeoutError),
        ):
            template_download.fetch_template_payload(
                opener=self.FakeOpener(response),
                monotonic=lambda: next(times),
            )
        with (
            mock.patch.object(
                template_download.urllib.request,
                "build_opener",
                return_value=object(),
            ) as build_opener,
            mock.patch.object(template_download.ssl, "create_default_context", return_value=object()),
        ):
            template_download.build_template_opener()
        proxy_handler = build_opener.call_args.args[0]
        self.assertIsInstance(proxy_handler, template_download.urllib.request.ProxyHandler)
        self.assertEqual({}, proxy_handler.proxies)

    def test_fixed_template_fetch_hard_deadline_covers_open_and_restores_handler(self) -> None:
        self.assertEqual((BaseException,), template_download._HardDeadlineSignal.__bases__)
        previous_handler = template_download.signal.getsignal(template_download.signal.SIGALRM)

        def existing_handler(_signal_number, _frame) -> None:
            return None

        class SlowHeaderOpener:
            def __init__(self) -> None:
                self.timeouts: list[int] = []
                self.caught_os_error = False
                self.caught_exception = False
                self.continued_after_signal = False

            def open(self, _request, timeout: int):
                self.timeouts.append(timeout)
                try:
                    try:
                        template_download.signal.raise_signal(template_download.signal.SIGALRM)
                    except OSError:
                        self.caught_os_error = True
                except Exception:
                    self.caught_exception = True
                self.continued_after_signal = True
                raise AssertionError("the hard deadline sentinel did not interrupt open")

        opener = SlowHeaderOpener()
        template_download.signal.signal(template_download.signal.SIGALRM, existing_handler)
        try:
            with self.assertRaisesRegex(TimeoutError, "overall timeout"):
                template_download.fetch_template_payload(opener=opener)
            self.assertIs(
                existing_handler,
                template_download.signal.getsignal(template_download.signal.SIGALRM),
            )
            self.assertEqual(
                (0.0, 0.0),
                template_download.signal.getitimer(template_download.signal.ITIMER_REAL),
            )
        finally:
            template_download.signal.signal(template_download.signal.SIGALRM, previous_handler)
        self.assertEqual([template_download.CONNECT_READ_TIMEOUT_SECONDS], opener.timeouts)
        self.assertFalse(opener.caught_os_error)
        self.assertFalse(opener.caught_exception)
        self.assertFalse(opener.continued_after_signal)

    def test_fixed_template_fetch_refuses_to_replace_an_active_alarm(self) -> None:
        opener = self.FakeOpener(self.FakeResponse(b"unused"))
        with (
            mock.patch.object(template_download.signal, "getitimer", return_value=(5.0, 0.0)),
            mock.patch.object(template_download.signal, "setitimer") as setitimer,
            self.assertRaisesRegex(RuntimeError, "active alarm"),
        ):
            template_download.fetch_template_payload(opener=opener)
        setitimer.assert_not_called()
        self.assertEqual([], opener.requests)

    def test_fixed_template_fetch_stops_a_slow_drip_at_the_overall_deadline(self) -> None:
        payload = b"slow"

        class Clock:
            def __init__(self) -> None:
                self.now = 0.0

            def monotonic(self) -> float:
                return self.now

        clock = Clock()

        class SlowDripResponse(self.FakeResponse):
            def read1(self, size: int) -> bytes:
                self.read_sizes.append(size)
                clock.now += 9.0
                chunk = self.payload[self.offset : self.offset + 1]
                self.offset += len(chunk)
                return chunk

        response = SlowDripResponse(payload)
        with (
            mock.patch.object(template_download, "TEMPLATE_SIZE_BYTES", len(payload)),
            mock.patch.object(template_download, "TEMPLATE_SHA256", hashlib.sha256(payload).hexdigest()),
            self.assertRaisesRegex(TimeoutError, "overall timeout"),
        ):
            template_download.fetch_template_payload(
                opener=self.FakeOpener(response),
                monotonic=clock.monotonic,
            )

        self.assertEqual([5, 4, 3, 2], response.read_sizes)


class CommandContractTest(unittest.TestCase):
    def test_export_command_matches_official_litert_torch_shape(self) -> None:
        command = build_export_command(
            Path("/tools/litert-torch"),
            Path("/local/merged"),
            Path("/local/export"),
            Path("/local/chat_template.jinja"),
            "dynamic_wi8_afp32",
        )
        self.assertEqual(
            [
                "/tools/litert-torch",
                "export_hf",
                "--model=/local/merged",
                "--output_dir=/local/export",
                "--quantization_recipe=dynamic_wi8_afp32",
                "--externalize_embedder",
                "--jinja_chat_template_override=/local/chat_template.jinja",
            ],
            command,
        )

    def test_run_command_and_answer_extraction_are_bounded_to_four_line_contract(self) -> None:
        command = build_run_command(Path("litert-lm"), Path("model.litertlm"), "synthetic prompt")
        self.assertEqual(
            ["litert-lm", "run", "model.litertlm", "--prompt=synthetic prompt"],
            command,
        )
        answer = (
            "Answer: synthetic grounded answer\n"
            "Evidence: none\n"
            "Missing: none\n"
            "Confidence: UNKNOWN"
        )
        self.assertEqual(answer, extract_grounded_answer(f"  \r\n{answer.replace(chr(10), chr(13) + chr(10))}\r\n"))
        with self.assertRaisesRegex(ValueError, "exactly one"):
            extract_grounded_answer(f"local log\n{answer}")
        with self.assertRaisesRegex(ValueError, "exactly one"):
            extract_grounded_answer(f"{answer}\nextra log")

    def test_release_fixture_pin_matches_the_exact_parsed_snapshot(self) -> None:
        config = load_release_config(DEFAULT_CONFIG)
        snapshot, fixtures = load_pinned_eval_fixtures(config)
        self.assertEqual(config.eval_fixture_sha256, snapshot.sha256)
        self.assertEqual(config.eval_fixture_count, len(fixtures))
        with self.assertRaisesRegex(ValueError, "count"):
            load_pinned_eval_fixtures(replace(config, eval_fixture_count=29))

    def test_release_gate_runs_repo_checks_before_real_cli(self) -> None:
        makefile = (REPO_ROOT / "model-training/Makefile").read_text(encoding="utf-8")
        release_gate = makefile.split("release-gate:", 1)[1].split("\n\n", 1)[0]
        required = ("corpus-check", "validate", "eval", "test", "artifact-policy", "litert-eval")
        positions = [release_gate.index(f") {target}") for target in required]
        self.assertEqual(sorted(positions), positions)

    def test_tool_version_records_actual_local_executable_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            executable = Path(directory) / "local-tool"
            resolved = write_fake_tool(executable, "local-tool 9.8.7")
            self.assertEqual("local-tool 9.8.7", tool_version(resolved))

    def test_pinned_subprocess_receives_no_caller_secret(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            executable = Path(directory) / "local-tool"
            executable.write_text(
                "#!/bin/sh\nprintf '%s\\n' \"${AWS_SECRET_ACCESS_KEY-unset}\"\n",
                encoding="utf-8",
            )
            executable.chmod(0o700)
            pinned = resolve_tool(str(executable), sha256_file(executable))
            with mock.patch.dict("os.environ", {"AWS_SECRET_ACCESS_KEY": "secret"}, clear=False):
                self.assertEqual("unset", tool_version(pinned))

    def test_bounded_subprocess_rejects_excess_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            executable = Path(directory) / "noisy-tool"
            executable.write_text(
                "#!/bin/sh\ndd if=/dev/zero bs=4096 count=1 2>/dev/null\n",
                encoding="utf-8",
            )
            executable.chmod(0o700)
            pinned = resolve_tool(str(executable), sha256_file(executable))
            with self.assertRaisesRegex(RuntimeError, "output"):
                run_bounded_command(
                    [str(pinned.path), "--version"],
                    timeout_seconds=10,
                    max_stdout_bytes=1024,
                    max_stderr_bytes=1024,
                    pinned_tool=pinned,
                )

    def test_release_environment_does_not_forward_secrets_or_python_injection(self) -> None:
        with mock.patch.dict(
            "os.environ",
            {
                "AWS_SECRET_ACCESS_KEY": "secret",
                "HF_TOKEN": "secret",
                "HTTP_PROXY": "http://user:secret@example.test",
                "PYTHONPATH": "/attacker",
                "LD_PRELOAD": "/attacker/library.so",
                "LD_LIBRARY_PATH": "/attacker",
                "CUDA_VISIBLE_DEVICES": "0",
            },
            clear=True,
        ):
            environment = release_environment()
        for key in (
            "AWS_SECRET_ACCESS_KEY",
            "HF_TOKEN",
            "HTTP_PROXY",
            "PYTHONPATH",
            "LD_PRELOAD",
            "LD_LIBRARY_PATH",
        ):
            self.assertNotIn(key, environment)
        self.assertEqual("0", environment["CUDA_VISIBLE_DEVICES"])
        self.assertEqual("1", environment["HF_HUB_OFFLINE"])
        self.assertEqual("1", environment["PYTHONNOUSERSITE"])
        self.assertEqual("1", environment["PYTHONSAFEPATH"])

    def test_tool_requires_absolute_pinned_non_writable_executable_and_detects_mutation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            executable = Path(directory) / "local-tool"
            executable.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            executable.chmod(0o700)
            digest = sha256_file(executable)
            with self.assertRaisesRegex(ValueError, "absolute"):
                resolve_tool("local-tool", digest)
            with self.assertRaisesRegex(ValueError, "operator-pinned"):
                resolve_tool(str(executable), "0" * 64)
            executable.chmod(0o722)
            with self.assertRaisesRegex(ValueError, "writable"):
                resolve_tool(str(executable), digest)
            executable.chmod(0o700)
            pinned = resolve_tool(str(executable), digest)
            executable.write_text("#!/bin/sh\nexit 1\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "changed"):
                verify_pinned_tool(pinned)

    def test_all_release_dry_runs_need_neither_weights_nor_tools(self) -> None:
        scripts = (
            "download_litert_template.py",
            "merge_lora.py",
            "export_litertlm.py",
            "run_litert_eval.py",
            "write_export_manifest.py",
        )
        for script in scripts:
            result = subprocess.run(
                [sys.executable, str(SCRIPTS / script), "--dry-run"],
                cwd=REPO_ROOT,
                check=True,
                capture_output=True,
                text=True,
            )
            self.assertEqual("planned", json.loads(result.stdout)["status"])


class LocalManifestGateTest(unittest.TestCase):
    def test_manifest_inputs_bind_model_provenance_and_full_eval(self) -> None:
        base = load_release_config(DEFAULT_CONFIG)
        fixtures = load_jsonl(base.eval_fixtures)
        with tempfile.TemporaryDirectory(dir=OUTPUT_ROOT) as directory:
            root = Path(directory)
            config = replace(
                base,
                litert_output_file=root / "model.litertlm",
                merge_provenance_file=root / "merge.json",
                export_provenance_file=root / "export.json",
                eval_predictions_file=root / "predictions.jsonl",
                eval_summary_file=root / "summary.json",
                local_release_manifest_file=root / "local-release.json",
            )
            write_fake_litertlm(config.litert_output_file)
            exporter_tool = write_fake_tool(root / "litert-torch", "test exporter 1")
            runtime_tool = write_fake_tool(root / "litert-lm", "test runtime 1")
            merge = {
                "schema_version": "grayin-lora-merge-provenance-v1",
                "source_model": {
                    "id": config.source_model_id,
                    "revision": config.source_model_revision,
                    "path": repo_relative(config.base_model_path),
                    "tree": {"sha256": "a" * 64},
                },
                "adapter": {
                    "path": repo_relative(config.adapter_path),
                    "tree": {"sha256": "b" * 64},
                },
                "training_corpus": {
                    "path": repo_relative(config.train_jsonl),
                    "size_bytes": regular_file_size(config.train_jsonl),
                    "sha256": sha256_file(config.train_jsonl),
                },
                "training_config": {
                    "path": repo_relative(config.config_path),
                    "sha256": sha256_file(config.config_path),
                },
                "tool_versions": {"torch": "test"},
            }
            config.merge_provenance_file.write_text(json.dumps(merge), encoding="utf-8")
            predictions = [
                {"id": fixture["id"], "answer": fixture["reference_answer"]}
                for fixture in fixtures
            ]
            config.eval_predictions_file.write_text(
                "".join(json.dumps(record, ensure_ascii=False) + "\n" for record in predictions),
                encoding="utf-8",
            )
            container = require_litertlm_file(config.litert_output_file)
            export = {
                "schema_version": "grayin-litert-export-provenance-v1",
                "model_id": config.model_id,
                "merge_provenance": {
                    "path": repo_relative(config.merge_provenance_file),
                    "sha256": sha256_file(config.merge_provenance_file),
                },
                "model_file": {
                    "path": repo_relative(config.litert_output_file),
                    "sha256": sha256_file(config.litert_output_file),
                    **container,
                },
                "exporter": {
                    "name": "litert-torch",
                    "selected_quantization": config.quantization,
                    "version_output": "test exporter 1",
                    "executable": str(exporter_tool.path),
                    "executable_sha256": exporter_tool.sha256,
                    "executable_identity": exporter_tool.as_dict(),
                    "command": build_export_command(
                        exporter_tool.path,
                        config.merged_output_dir,
                        config.litert_output_file.parent
                        / f".{config.litert_output_file.stem}.test.tmp",
                        config.chat_template_override_path,
                        config.quantization,
                    ),
                },
                "chat_template": {
                    "source_repo_id": "litert-community/gemma-4-E2B-it-litert-lm",
                    "source_revision": "d23202ebbc77c976719090aaa080362f29d746e2",
                    "source_file": "chat_template.jinja",
                    "source_url": TEMPLATE_RAW_URL,
                    "path": repo_relative(config.chat_template_override_path),
                    "size_bytes": 11_995,
                    "sha256": "02b3091acf53c0b722e3db0c7a1b4980363edcc2d85549dafa339ff5dbfff629",
                },
            }
            config.export_provenance_file.write_text(json.dumps(export), encoding="utf-8")
            summary = evaluate(
                fixtures,
                {record["id"]: record["answer"] for record in predictions},
            )
            summary["release_gate"] = {
                "passed": True,
                "scope": "full",
                "fixture_path": repo_relative(config.eval_fixtures),
                "fixture_size_bytes": regular_file_size(config.eval_fixtures),
                "fixture_sha256": sha256_file(config.eval_fixtures),
                "model_path": repo_relative(config.litert_output_file),
                "model_sha256": sha256_file(config.litert_output_file),
                "export_provenance_path": repo_relative(config.export_provenance_file),
                "export_provenance_sha256": sha256_file(config.export_provenance_file),
                "predictions_path": repo_relative(config.eval_predictions_file),
                "predictions_size_bytes": regular_file_size(config.eval_predictions_file),
                "predictions_sha256": sha256_file(config.eval_predictions_file),
                "runner": {
                    "name": "litert-lm",
                    "version_output": "test runtime 1",
                    "executable": str(runtime_tool.path),
                    "executable_sha256": runtime_tool.sha256,
                    "executable_identity": runtime_tool.as_dict(),
                },
            }
            config.eval_summary_file.write_text(json.dumps(summary), encoding="utf-8")

            loaded_merge, loaded_export, loaded_summary, _, _ = validated_release_inputs(config)
            self.assertEqual(merge, loaded_merge)
            self.assertEqual(export, loaded_export)
            self.assertEqual(len(fixtures), loaded_summary["passed"])

            export["exporter"]["selected_quantization"] = "unexpected"
            config.export_provenance_file.write_text(json.dumps(export), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "exporter provenance"):
                validated_release_inputs(config)
            export["exporter"]["selected_quantization"] = config.quantization
            config.export_provenance_file.write_text(json.dumps(export), encoding="utf-8")

            export["exporter"]["command"][4] = "--quantization_recipe=dynamic_wi4_afp32"
            config.export_provenance_file.write_text(json.dumps(export), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "reviewed command"):
                validated_release_inputs(config)
            export["exporter"]["command"][4] = f"--quantization_recipe={config.quantization}"
            config.export_provenance_file.write_text(json.dumps(export), encoding="utf-8")

            summary["release_gate"]["scope"] = "smoke"
            config.eval_summary_file.write_text(json.dumps(summary), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "full LiteRT-LM release gate"):
                validated_release_inputs(config)


if __name__ == "__main__":
    unittest.main()
