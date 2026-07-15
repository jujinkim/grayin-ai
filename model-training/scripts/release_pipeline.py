#!/usr/bin/env python3
"""Dependency-free helpers for Grayin's local model release pipeline."""

from __future__ import annotations

from contextlib import contextmanager
import ctypes
from dataclasses import dataclass
import errno
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import stat
import struct
import subprocess
import tempfile
import time
from typing import Any, Iterable


REPO_ROOT = Path(__file__).resolve().parents[2]
MODEL_TRAINING_ROOT = REPO_ROOT / "model-training"
REFERENCE_ROOT = MODEL_TRAINING_ROOT / "reference-models"
OUTPUT_ROOT = MODEL_TRAINING_ROOT / "outputs"
DATA_ROOT = MODEL_TRAINING_ROOT / "data/synthetic"
CONFIG_ROOT = MODEL_TRAINING_ROOT / "configs"

MIN_LITERTLM_BYTES = 1024 * 1024
MAX_LITERTLM_BYTES = 8 * 1024 * 1024 * 1024
LITERTLM_MAGIC = b"LITERTLM"
LITERTLM_PREAMBLE_BYTES = 32
MAX_LITERTLM_HEADER_BYTES = 16 * 1024
REQUIRED_CONTAINER_MAJOR_VERSION = 1
TREE_DIGEST_ALGORITHM = "sha256-path-size-content-v1"
SAFE_ID = re.compile(r"[A-Za-z0-9._-]{1,100}\Z")
SAFE_SOURCE_MODEL_ID = re.compile(r"[A-Za-z0-9._-]+/[A-Za-z0-9._-]+\Z")
PINNED_REVISION = re.compile(r"[a-f0-9]{40}\Z")
SHA256_HEX = re.compile(r"[a-f0-9]{64}\Z")
SAFE_ENVIRONMENT_KEYS = (
    "CUDA_VISIBLE_DEVICES",
    "LANG",
    "LC_ALL",
    "LC_CTYPE",
    "NVIDIA_VISIBLE_DEVICES",
    "TZ",
)
RELEASE_RUNTIME_ROOT = OUTPUT_ROOT / ".release-runtime"


@dataclass(frozen=True)
class ReleaseConfig:
    config_path: Path
    run_name: str
    source_model_id: str
    source_model_revision: str
    base_model_path: Path
    train_jsonl: Path
    adapter_path: Path
    merged_output_dir: Path
    merge_provenance_file: Path
    litert_output_file: Path
    export_provenance_file: Path
    local_release_manifest_file: Path
    chat_template_override_path: Path
    eval_fixtures: Path
    eval_fixture_count: int
    eval_fixture_sha256: str
    eval_predictions_file: Path
    eval_summary_file: Path
    model_id: str
    quantization: str
    runtime_catalog_target: Path


@dataclass(frozen=True)
class TreeDigest:
    algorithm: str
    sha256: str
    file_count: int
    total_bytes: int

    def as_dict(self) -> dict[str, object]:
        return {
            "algorithm": self.algorithm,
            "sha256": self.sha256,
            "file_count": self.file_count,
            "total_bytes": self.total_bytes,
        }


@dataclass(frozen=True)
class PinnedTool:
    path: Path
    sha256: str
    device: int
    inode: int
    size_bytes: int
    mode: int
    uid: int
    mtime_ns: int
    interpreter_identity: dict[str, object] | None

    def as_dict(self) -> dict[str, object]:
        return {
            "path": str(self.path),
            "sha256": self.sha256,
            "device": self.device,
            "inode": self.inode,
            "size_bytes": self.size_bytes,
            "mode": oct(self.mode & 0o777),
            "uid": self.uid,
            "mtime_ns": self.mtime_ns,
            "interpreter_identity": self.interpreter_identity,
        }


@dataclass(frozen=True)
class CommandResult:
    returncode: int
    stdout: str
    stderr: str


@dataclass(frozen=True)
class RegularFileSnapshot:
    path: Path
    data: bytes
    sha256: str
    size_bytes: int
    device: int
    inode: int
    mode: int
    mtime_ns: int
    ctime_ns: int


def read_flat_yaml(path: Path) -> dict[str, str]:
    """Read the simple two-level scalar YAML used by model-training configs."""
    values: dict[str, str] = {}
    section: str | None = None
    try:
        encoded = read_regular_bytes(path, 256 * 1024)
        lines = encoded.decode("utf-8").splitlines()
    except UnicodeDecodeError as error:
        raise ValueError(f"release config is not UTF-8: {path}") from error
    for line_number, raw_line in enumerate(lines, start=1):
        stripped = raw_line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if raw_line.startswith("\t") or "\t" in raw_line[: len(raw_line) - len(raw_line.lstrip())]:
            raise ValueError(f"{path}:{line_number}: tabs are not supported")
        indentation = len(raw_line) - len(raw_line.lstrip(" "))
        if indentation == 0 and stripped.endswith(":"):
            section = stripped[:-1]
            if not section:
                raise ValueError(f"{path}:{line_number}: empty section")
            continue
        if indentation != 2 or section is None or ":" not in stripped:
            raise ValueError(f"{path}:{line_number}: unsupported YAML structure")
        key, value = stripped.split(":", 1)
        key = key.strip()
        value = value.strip()
        if not key or not value:
            raise ValueError(f"{path}:{line_number}: scalar key/value is required")
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {'"', "'"}:
            value = value[1:-1]
        full_key = f"{section}.{key}"
        if full_key in values:
            raise ValueError(f"{path}:{line_number}: duplicate key: {full_key}")
        values[full_key] = value
    return values


def _required(values: dict[str, str], key: str) -> str:
    value = values.get(key)
    if value is None or not value.strip():
        raise ValueError(f"release config is missing {key}")
    return value


def _required_positive_int(values: dict[str, str], key: str) -> int:
    raw_value = _required(values, key)
    try:
        value = int(raw_value)
    except ValueError as error:
        raise ValueError(f"release config {key} must be a positive integer") from error
    if value <= 0:
        raise ValueError(f"release config {key} must be a positive integer")
    return value


def resolve_repo_path(value: str, allowed_root: Path) -> Path:
    raw_path = Path(value)
    candidate = raw_path if raw_path.is_absolute() else REPO_ROOT / raw_path
    resolved = candidate.resolve(strict=False)
    allowed = allowed_root.resolve(strict=True)
    try:
        resolved.relative_to(allowed)
    except ValueError as error:
        raise ValueError(f"release path must stay under {allowed.relative_to(REPO_ROOT)}: {value}") from error
    return resolved


def load_release_config(path: Path) -> ReleaseConfig:
    config_path = path if path.is_absolute() else REPO_ROOT / path
    if config_path.is_symlink():
        raise ValueError("release config must not be a symbolic link")
    config_path = config_path.resolve(strict=True)
    try:
        config_path.relative_to(CONFIG_ROOT.resolve(strict=True))
    except ValueError as error:
        raise ValueError("release config must stay under model-training/configs") from error
    values = read_flat_yaml(config_path)
    config = ReleaseConfig(
        config_path=config_path,
        run_name=_required(values, "run.name"),
        source_model_id=_required(values, "model.source_model_id"),
        source_model_revision=_required(values, "model.source_model_revision"),
        base_model_path=resolve_repo_path(_required(values, "model.base_model_path"), REFERENCE_ROOT),
        train_jsonl=resolve_repo_path(_required(values, "dataset.train_jsonl"), DATA_ROOT),
        adapter_path=resolve_repo_path(_required(values, "training.output_dir"), OUTPUT_ROOT),
        merged_output_dir=resolve_repo_path(_required(values, "export.merged_output_dir"), OUTPUT_ROOT),
        merge_provenance_file=resolve_repo_path(_required(values, "export.merge_provenance_file"), OUTPUT_ROOT),
        litert_output_file=resolve_repo_path(_required(values, "export.litert_output_file"), OUTPUT_ROOT),
        export_provenance_file=resolve_repo_path(_required(values, "export.export_provenance_file"), OUTPUT_ROOT),
        local_release_manifest_file=resolve_repo_path(
            _required(values, "export.local_release_manifest_file"),
            OUTPUT_ROOT,
        ),
        chat_template_override_path=resolve_repo_path(
            _required(values, "export.chat_template_override_path"),
            REFERENCE_ROOT,
        ),
        eval_fixtures=resolve_repo_path(_required(values, "export.eval_fixtures"), DATA_ROOT),
        eval_fixture_count=_required_positive_int(values, "export.eval_fixture_count"),
        eval_fixture_sha256=_required(values, "export.eval_fixture_sha256"),
        eval_predictions_file=resolve_repo_path(
            _required(values, "export.eval_predictions_file"),
            OUTPUT_ROOT,
        ),
        eval_summary_file=resolve_repo_path(_required(values, "export.eval_summary_file"), OUTPUT_ROOT),
        model_id=_required(values, "export.model_id"),
        quantization=_required(values, "export.quantization"),
        runtime_catalog_target=resolve_repo_path(
            _required(values, "export.runtime_catalog_target"),
            REPO_ROOT / "app/src/main",
        ),
    )
    if not SAFE_ID.fullmatch(config.run_name) or not SAFE_ID.fullmatch(config.model_id):
        raise ValueError("run name and model id must be bounded safe identifiers")
    if not SAFE_SOURCE_MODEL_ID.fullmatch(config.source_model_id):
        raise ValueError("source model id must be a fixed owner/repository identifier")
    if not PINNED_REVISION.fullmatch(config.source_model_revision):
        raise ValueError("source model revision must be a full lowercase commit digest")
    if config.quantization != "dynamic_wi8_afp32":
        raise ValueError("release quantization must match the reviewed required LiteRT Torch recipe")
    require_sha256(config.eval_fixture_sha256, "expected evaluation fixture digest")
    if config.eval_fixture_count > 100:
        raise ValueError("release evaluation fixture count exceeds the bounded gate")
    quantization_id = "wi8-afp32"
    if quantization_id not in config.model_id.lower() or quantization_id not in config.litert_output_file.name.lower():
        raise ValueError("model id and LiteRT-LM file name must identify the configured quantization")
    if config.litert_output_file.name != f"{config.model_id}.litertlm":
        raise ValueError("LiteRT-LM file name must exactly match the configured model id")
    for output_file, suffix in (
        (config.merge_provenance_file, ".json"),
        (config.export_provenance_file, ".json"),
        (config.local_release_manifest_file, ".json"),
        (config.eval_predictions_file, ".jsonl"),
        (config.eval_summary_file, ".json"),
    ):
        if output_file.suffix != suffix:
            raise ValueError(f"release output must use {suffix}: {output_file}")
    if config.chat_template_override_path.name != "chat_template.jinja":
        raise ValueError("release chat-template path must end in chat_template.jinja")
    generated_files = (
        config.merge_provenance_file,
        config.litert_output_file,
        config.export_provenance_file,
        config.local_release_manifest_file,
        config.eval_predictions_file,
        config.eval_summary_file,
    )
    if len(set(generated_files)) != len(generated_files):
        raise ValueError("release output files must use distinct paths")
    for output_file in generated_files:
        for artifact_tree in (config.adapter_path, config.merged_output_dir):
            try:
                output_file.relative_to(artifact_tree)
            except ValueError:
                continue
            raise ValueError("release output files cannot be nested under adapter or merged model trees")
    for first, second in (
        (config.adapter_path, config.merged_output_dir),
        (config.merged_output_dir, config.adapter_path),
    ):
        try:
            first.relative_to(second)
        except ValueError:
            continue
        raise ValueError("adapter and merged model directories must not overlap")
    try:
        config.chat_template_override_path.relative_to(config.base_model_path)
    except ValueError:
        pass
    else:
        raise ValueError("export chat template must remain outside the pinned base-model tree")
    return config


def repo_relative(path: Path) -> str:
    resolved = path.resolve(strict=False)
    try:
        return resolved.relative_to(REPO_ROOT).as_posix()
    except ValueError as error:
        raise ValueError(f"path is outside repository: {path}") from error


def _lexical_relative(path: Path, root: Path) -> Path:
    absolute = Path(os.path.abspath(path))
    absolute_root = Path(os.path.abspath(root))
    try:
        return absolute.relative_to(absolute_root)
    except ValueError as error:
        raise ValueError(f"path must stay under {repo_relative(absolute_root)}: {path}") from error


def artifact_root_for(path: Path) -> Path:
    for root in (OUTPUT_ROOT, REFERENCE_ROOT):
        try:
            _lexical_relative(path, root)
        except ValueError:
            continue
        return root
    raise ValueError(f"artifact path is outside ignored release roots: {path}")


def verify_no_symlink_components(directory: Path, root: Path) -> None:
    relative = _lexical_relative(directory, root)
    root_status = root.lstat()
    if stat.S_ISLNK(root_status.st_mode) or not stat.S_ISDIR(root_status.st_mode):
        raise ValueError(f"release root is not a regular directory: {root}")
    current = root
    for part in relative.parts:
        current = current / part
        try:
            status = current.lstat()
        except FileNotFoundError:
            return
        if stat.S_ISLNK(status.st_mode) or not stat.S_ISDIR(status.st_mode):
            raise ValueError(f"release path contains a symlink or non-directory component: {current}")


def secure_open_directory(directory: Path, root: Path, create: bool) -> int:
    """Open a directory beneath a fixed root without following path-component symlinks."""
    relative = _lexical_relative(directory, root)
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC | os.O_NOFOLLOW
    current_fd = os.open(root, flags)
    try:
        for part in relative.parts:
            if create:
                try:
                    os.mkdir(part, mode=0o700, dir_fd=current_fd)
                except FileExistsError:
                    pass
            next_fd = os.open(part, flags, dir_fd=current_fd)
            os.close(current_fd)
            current_fd = next_fd
        return current_fd
    except BaseException:
        os.close(current_fd)
        raise


def secure_artifact_parent(path: Path, create: bool = True) -> int:
    return secure_open_directory(path.parent, artifact_root_for(path), create=create)


def require_git_ignored(path: Path) -> None:
    artifact_root_for(path)
    verify_no_symlink_components(path.parent, artifact_root_for(path))
    git = shutil.which("git", path=os.defpath)
    if git is None:
        raise FileNotFoundError("git is required to verify ignored release paths")
    git_path = Path(git).resolve(strict=True)
    git_status = git_path.stat()
    if not stat.S_ISREG(git_status.st_mode) or git_status.st_mode & 0o022:
        raise ValueError("git executable is not a trusted regular file")
    result = subprocess.run(
        [str(git_path), "check-ignore", "-q", "--", repo_relative(path)],
        cwd=REPO_ROOT,
        env=release_environment(offline=True),
        stdin=subprocess.DEVNULL,
        capture_output=True,
        timeout=10,
        check=False,
    )
    if result.returncode != 0:
        raise ValueError(f"release artifact path is not ignored by git: {repo_relative(path)}")


def require_new_artifact_paths(paths: Iterable[Path]) -> None:
    for path in paths:
        require_git_ignored(path)
        if path.exists() or path.is_symlink():
            raise FileExistsError(f"refusing to overwrite release artifact: {repo_relative(path)}")


def _open_stable_regular_file(path: Path) -> tuple[int, os.stat_result]:
    flags = os.O_RDONLY | os.O_CLOEXEC | os.O_NOFOLLOW | os.O_NONBLOCK
    try:
        artifact_root = artifact_root_for(path)
    except ValueError:
        fd = os.open(path, flags)
    else:
        parent_fd = secure_open_directory(path.parent, artifact_root, create=False)
        try:
            fd = os.open(path.name, flags, dir_fd=parent_fd)
        finally:
            os.close(parent_fd)
    status = os.fstat(fd)
    if not stat.S_ISREG(status.st_mode):
        os.close(fd)
        raise ValueError(f"expected a regular non-symlink file: {path}")
    return fd, status


def _require_unchanged(fd: int, before: os.stat_result, path: Path) -> None:
    after = os.fstat(fd)
    identity_before = (
        before.st_dev,
        before.st_ino,
        before.st_mode,
        before.st_size,
        before.st_mtime_ns,
        before.st_ctime_ns,
    )
    identity_after = (
        after.st_dev,
        after.st_ino,
        after.st_mode,
        after.st_size,
        after.st_mtime_ns,
        after.st_ctime_ns,
    )
    if identity_before != identity_after:
        raise ValueError(f"file changed while it was being read: {path}")


def sha256_file(path: Path) -> str:
    fd, before = _open_stable_regular_file(path)
    digest = hashlib.sha256()
    try:
        with os.fdopen(fd, "rb", closefd=False) as source:
            for block in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(block)
        _require_unchanged(fd, before, path)
    finally:
        os.close(fd)
    return digest.hexdigest()


def read_regular_bytes(path: Path, maximum_bytes: int) -> bytes:
    return read_regular_snapshot(path, maximum_bytes).data


def read_regular_snapshot(path: Path, maximum_bytes: int) -> RegularFileSnapshot:
    """Read a bounded regular file once and bind its digest to those exact bytes."""
    fd, before = _open_stable_regular_file(path)
    try:
        if before.st_size > maximum_bytes:
            raise ValueError(f"file exceeds the bounded read limit: {path}")
        with os.fdopen(fd, "rb", closefd=False) as source:
            value = source.read(maximum_bytes + 1)
        _require_unchanged(fd, before, path)
        if len(value) > maximum_bytes:
            raise ValueError(f"file exceeds the bounded read limit: {path}")
        if len(value) != before.st_size:
            raise ValueError(f"file read did not match its stable size: {path}")
        return RegularFileSnapshot(
            path=path,
            data=value,
            sha256=hashlib.sha256(value).hexdigest(),
            size_bytes=len(value),
            device=before.st_dev,
            inode=before.st_ino,
            mode=before.st_mode,
            mtime_ns=before.st_mtime_ns,
            ctime_ns=before.st_ctime_ns,
        )
    finally:
        os.close(fd)


def verify_regular_snapshot(snapshot: RegularFileSnapshot) -> None:
    """Revalidate that a parsed snapshot is still the file at its configured path."""
    fd, current = _open_stable_regular_file(snapshot.path)
    try:
        identity = (
            current.st_dev,
            current.st_ino,
            current.st_mode,
            current.st_size,
            current.st_mtime_ns,
            current.st_ctime_ns,
        )
        expected = (
            snapshot.device,
            snapshot.inode,
            snapshot.mode,
            snapshot.size_bytes,
            snapshot.mtime_ns,
            snapshot.ctime_ns,
        )
        if identity != expected:
            raise ValueError(f"file changed after its bounded snapshot was read: {snapshot.path}")
    finally:
        os.close(fd)


def read_regular_prefix(path: Path, maximum_bytes: int) -> bytes:
    fd, before = _open_stable_regular_file(path)
    try:
        with os.fdopen(fd, "rb", closefd=False) as source:
            value = source.read(maximum_bytes)
        _require_unchanged(fd, before, path)
        return value
    finally:
        os.close(fd)


def require_regular_file(path: Path) -> None:
    fd, _status = _open_stable_regular_file(path)
    os.close(fd)


def regular_file_size(path: Path) -> int:
    fd, status = _open_stable_regular_file(path)
    os.close(fd)
    return status.st_size


def read_json_object(path: Path) -> dict[str, Any]:
    fd, before = _open_stable_regular_file(path)
    try:
        if before.st_size > 1024 * 1024:
            raise ValueError(f"JSON metadata exceeds 1 MiB: {path}")
        with os.fdopen(fd, "rb", closefd=False) as source:
            encoded = source.read(1024 * 1024 + 1)
        _require_unchanged(fd, before, path)
        if len(encoded) > 1024 * 1024:
            raise ValueError(f"JSON metadata exceeds 1 MiB: {path}")
        value = json.loads(encoded.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"invalid JSON metadata: {path}") from error
    finally:
        os.close(fd)
    if not isinstance(value, dict):
        raise ValueError(f"JSON metadata must be an object: {path}")
    return value


def tree_digest(path: Path) -> TreeDigest:
    if path.is_symlink():
        raise ValueError(f"expected a non-symlink directory: {path}")
    root = path.resolve(strict=True)
    if not root.is_dir():
        raise ValueError(f"expected a regular directory: {path}")
    files: list[tuple[str, Path]] = []
    for candidate in root.rglob("*"):
        if candidate.is_symlink():
            raise ValueError(f"release tree contains a symbolic link: {candidate}")
        if candidate.is_file():
            files.append((candidate.relative_to(root).as_posix(), candidate))
        elif not candidate.is_dir():
            raise ValueError(f"release tree contains an unsupported entry: {candidate}")
    if not files:
        raise ValueError(f"release tree contains no files: {path}")
    if len(files) > 100_000:
        raise ValueError("release tree contains too many files")

    digest = hashlib.sha256()
    total_bytes = 0
    for relative, file_path in sorted(files):
        encoded_path = relative.encode("utf-8")
        fd, before = _open_stable_regular_file(file_path)
        size = before.st_size
        try:
            digest.update(len(encoded_path).to_bytes(4, "big"))
            digest.update(encoded_path)
            digest.update(size.to_bytes(8, "big"))
            with os.fdopen(fd, "rb", closefd=False) as source:
                for block in iter(lambda: source.read(1024 * 1024), b""):
                    digest.update(block)
            _require_unchanged(fd, before, file_path)
        finally:
            os.close(fd)
        total_bytes += size
    return TreeDigest(
        algorithm=TREE_DIGEST_ALGORITHM,
        sha256=digest.hexdigest(),
        file_count=len(files),
        total_bytes=total_bytes,
    )


def fsync_file(path: Path) -> None:
    fd, before = _open_stable_regular_file(path)
    try:
        os.fsync(fd)
        _require_unchanged(fd, before, path)
    finally:
        os.close(fd)


def fsync_tree(path: Path) -> None:
    if path.is_symlink():
        raise ValueError(f"expected a non-symlink directory: {path}")
    root = path.resolve(strict=True)
    directories = [root]
    for candidate in root.rglob("*"):
        if candidate.is_symlink():
            raise ValueError(f"release tree contains a symbolic link: {candidate}")
        if candidate.is_file():
            fsync_file(candidate)
        elif candidate.is_dir():
            directories.append(candidate)
        else:
            raise ValueError(f"release tree contains an unsupported entry: {candidate}")
    for directory in sorted(directories, key=lambda value: len(value.parts), reverse=True):
        directory_fd = os.open(
            directory,
            os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC | os.O_NOFOLLOW,
        )
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)


def harden_tree_permissions(path: Path) -> None:
    root = path.resolve(strict=True)
    if path.is_symlink() or not root.is_dir():
        raise ValueError(f"expected a non-symlink artifact directory: {path}")
    os.chmod(root, 0o700)
    for candidate in root.rglob("*"):
        if candidate.is_symlink():
            raise ValueError(f"release tree contains a symbolic link: {candidate}")
        if candidate.is_dir():
            os.chmod(candidate, 0o700)
        elif candidate.is_file():
            os.chmod(candidate, 0o600)
        else:
            raise ValueError(f"release tree contains an unsupported entry: {candidate}")


def require_litertlm_file(path: Path) -> dict[str, int]:
    fd, before = _open_stable_regular_file(path)
    size_bytes = before.st_size
    try:
        if size_bytes < MIN_LITERTLM_BYTES or size_bytes > MAX_LITERTLM_BYTES:
            raise ValueError("LiteRT-LM file size is outside the Android runtime boundary")
        with os.fdopen(fd, "rb", closefd=False) as source:
            preamble = source.read(LITERTLM_PREAMBLE_BYTES)
            if len(preamble) != LITERTLM_PREAMBLE_BYTES or preamble[:8] != LITERTLM_MAGIC:
                raise ValueError("LiteRT-LM preamble is invalid")
            major, minor, patch, _reserved, header_end = struct.unpack("<iiiiq", preamble[8:])
            if major != REQUIRED_CONTAINER_MAJOR_VERSION:
                raise ValueError("LiteRT-LM container major version is unsupported")
            header_bytes = header_end - LITERTLM_PREAMBLE_BYTES
            if header_bytes < 12 or header_bytes > MAX_LITERTLM_HEADER_BYTES or header_end > size_bytes:
                raise ValueError("LiteRT-LM header boundary is invalid")
            header = source.read(header_bytes)
            if len(header) != header_bytes:
                raise ValueError("LiteRT-LM header is truncated")
        _require_unchanged(fd, before, path)
    finally:
        os.close(fd)
    root_offset = struct.unpack_from("<I", header, 0)[0]
    if root_offset < 4 or root_offset > len(header) - 4:
        raise ValueError("LiteRT-LM FlatBuffer root is invalid")
    vtable_distance = struct.unpack_from("<i", header, root_offset)[0]
    if vtable_distance <= 0 or vtable_distance > root_offset:
        raise ValueError("LiteRT-LM FlatBuffer table is invalid")
    vtable_offset = root_offset - vtable_distance
    if vtable_offset > len(header) - 4:
        raise ValueError("LiteRT-LM FlatBuffer vtable is truncated")
    vtable_bytes, table_bytes = struct.unpack_from("<HH", header, vtable_offset)
    if vtable_bytes < 4 or vtable_bytes % 2 != 0 or table_bytes < 4:
        raise ValueError("LiteRT-LM FlatBuffer sizes are invalid")
    if vtable_offset + vtable_bytes > len(header) or root_offset + table_bytes > len(header):
        raise ValueError("LiteRT-LM FlatBuffer boundary is invalid")
    return {
        "size_bytes": size_bytes,
        "container_major_version": major,
        "container_minor_version": minor,
        "container_patch_version": patch,
    }


def atomic_write_json(path: Path, value: dict[str, Any]) -> None:
    encoded = json.dumps(value, ensure_ascii=True, indent=2, sort_keys=True).encode("utf-8") + b"\n"
    if len(encoded) > 1024 * 1024:
        raise ValueError("JSON release metadata exceeds 1 MiB")
    _atomic_write_bytes(path, (encoded,))


def atomic_write_jsonl(path: Path, records: Iterable[dict[str, Any]]) -> None:
    encoded = (
        json.dumps(record, ensure_ascii=False, sort_keys=True).encode("utf-8") + b"\n"
        for record in records
    )
    _atomic_write_bytes(path, encoded)


def atomic_write_bytes(path: Path, value: bytes) -> None:
    _atomic_write_bytes(path, (value,))


def _atomic_write_bytes(path: Path, chunks: Iterable[bytes]) -> None:
    parent_fd = secure_artifact_parent(path, create=True)
    temporary_name = f".{path.name}.{secrets.token_hex(12)}.tmp"
    file_fd: int | None = None
    published = False
    try:
        try:
            os.stat(path.name, dir_fd=parent_fd, follow_symlinks=False)
        except FileNotFoundError:
            pass
        else:
            raise FileExistsError(path)
        file_fd = os.open(
            temporary_name,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC | os.O_NOFOLLOW,
            0o600,
            dir_fd=parent_fd,
        )
        with os.fdopen(file_fd, "wb", closefd=False) as target:
            for chunk in chunks:
                target.write(chunk)
            target.flush()
            os.fsync(file_fd)
        os.link(
            temporary_name,
            path.name,
            src_dir_fd=parent_fd,
            dst_dir_fd=parent_fd,
            follow_symlinks=False,
        )
        published = True
        os.fsync(parent_fd)
        published = False
    except BaseException:
        if published:
            try:
                os.unlink(path.name, dir_fd=parent_fd)
            except FileNotFoundError:
                pass
        raise
    finally:
        if file_fd is not None:
            os.close(file_fd)
        try:
            os.unlink(temporary_name, dir_fd=parent_fd)
        except FileNotFoundError:
            pass
        os.close(parent_fd)


def publish_file_no_replace(source: Path, destination: Path) -> None:
    source_root = artifact_root_for(source)
    destination_root = artifact_root_for(destination)
    source_parent_fd = secure_open_directory(source.parent, source_root, create=False)
    destination_parent_fd = secure_open_directory(destination.parent, destination_root, create=True)
    published = False
    try:
        source_status = os.stat(source.name, dir_fd=source_parent_fd, follow_symlinks=False)
        if not stat.S_ISREG(source_status.st_mode):
            raise ValueError(f"publish source is not a regular file: {source}")
        os.link(
            source.name,
            destination.name,
            src_dir_fd=source_parent_fd,
            dst_dir_fd=destination_parent_fd,
            follow_symlinks=False,
        )
        published = True
        published_status = os.stat(destination.name, dir_fd=destination_parent_fd, follow_symlinks=False)
        if (source_status.st_dev, source_status.st_ino) != (
            published_status.st_dev,
            published_status.st_ino,
        ):
            raise ValueError("published file identity changed unexpectedly")
        os.fsync(destination_parent_fd)
        published = False
    except BaseException:
        if published:
            try:
                os.unlink(destination.name, dir_fd=destination_parent_fd)
            except FileNotFoundError:
                pass
        raise
    finally:
        os.close(source_parent_fd)
        os.close(destination_parent_fd)


def publish_directory_no_replace(
    source: Path,
    destination: Path,
    expected_tree: TreeDigest | None = None,
) -> None:
    source_root = artifact_root_for(source)
    destination_root = artifact_root_for(destination)
    staged_tree = expected_tree if expected_tree is not None else tree_digest(source)
    source_parent_fd = secure_open_directory(source.parent, source_root, create=False)
    destination_parent_fd = secure_open_directory(destination.parent, destination_root, create=True)
    published = False
    try:
        source_status = os.stat(source.name, dir_fd=source_parent_fd, follow_symlinks=False)
        if not stat.S_ISDIR(source_status.st_mode):
            raise ValueError(f"publish source is not a regular directory: {source}")
        libc = ctypes.CDLL(None, use_errno=True)
        renameat2 = getattr(libc, "renameat2", None)
        if renameat2 is None:
            raise RuntimeError("atomic no-replace directory publication requires renameat2")
        renameat2.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
        renameat2.restype = ctypes.c_int
        result = renameat2(
            source_parent_fd,
            os.fsencode(source.name),
            destination_parent_fd,
            os.fsencode(destination.name),
            1,  # RENAME_NOREPLACE
        )
        if result != 0:
            error_number = ctypes.get_errno()
            if error_number == errno.EEXIST:
                raise FileExistsError(destination)
            raise OSError(error_number, os.strerror(error_number), destination)
        published = True
        destination_status = os.stat(destination.name, dir_fd=destination_parent_fd, follow_symlinks=False)
        if (source_status.st_dev, source_status.st_ino) != (
            destination_status.st_dev,
            destination_status.st_ino,
        ):
            raise ValueError("published directory identity changed unexpectedly")
        if tree_digest(destination) != staged_tree:
            raise ValueError("published directory tree differs from the validated staging tree")
        os.fsync(source_parent_fd)
        os.fsync(destination_parent_fd)
        published = False
    except BaseException:
        if published:
            remove_artifact_tree(destination)
        raise
    finally:
        os.close(source_parent_fd)
        os.close(destination_parent_fd)


def remove_artifact_tree(path: Path) -> None:
    artifact_root_for(path)
    verify_no_symlink_components(path.parent, artifact_root_for(path))
    try:
        status = path.lstat()
    except FileNotFoundError:
        return
    if stat.S_ISLNK(status.st_mode) or not stat.S_ISDIR(status.st_mode):
        raise ValueError(f"refusing to recursively remove non-directory artifact: {path}")
    if not shutil.rmtree.avoids_symlink_attacks:
        raise RuntimeError("secure artifact cleanup requires fd-based shutil.rmtree")
    shutil.rmtree(path)


def remove_artifact_file(path: Path) -> None:
    parent_fd = secure_artifact_parent(path, create=False)
    try:
        try:
            status = os.stat(path.name, dir_fd=parent_fd, follow_symlinks=False)
        except FileNotFoundError:
            return
        if not stat.S_ISREG(status.st_mode):
            raise ValueError(f"refusing to remove non-regular artifact: {path}")
        os.unlink(path.name, dir_fd=parent_fd)
        os.fsync(parent_fd)
    finally:
        os.close(parent_fd)


def release_environment(offline: bool = True) -> dict[str, str]:
    """Build a minimal environment that does not forward caller credentials or injection hooks."""
    environment = {
        key: value
        for key in SAFE_ENVIRONMENT_KEYS
        if (value := os.environ.get(key)) is not None and "\x00" not in value
    }
    environment["PATH"] = os.defpath
    environment["HOME"] = str(RELEASE_RUNTIME_ROOT / "home")
    environment["XDG_CACHE_HOME"] = str(RELEASE_RUNTIME_ROOT / "cache")
    environment["HF_HOME"] = str(RELEASE_RUNTIME_ROOT / "huggingface")
    environment["TMPDIR"] = str(RELEASE_RUNTIME_ROOT / "tmp")
    environment["DO_NOT_TRACK"] = "1"
    environment["HF_HUB_DISABLE_TELEMETRY"] = "1"
    environment["TOKENIZERS_PARALLELISM"] = "false"
    environment["PYTHONNOUSERSITE"] = "1"
    environment["PYTHONSAFEPATH"] = "1"
    if offline:
        environment["HF_HUB_OFFLINE"] = "1"
        environment["TRANSFORMERS_OFFLINE"] = "1"
        environment["HF_DATASETS_OFFLINE"] = "1"
    return environment


def prepare_release_environment(offline: bool = True) -> dict[str, str]:
    for directory in (
        RELEASE_RUNTIME_ROOT / "home",
        RELEASE_RUNTIME_ROOT / "cache",
        RELEASE_RUNTIME_ROOT / "huggingface",
        RELEASE_RUNTIME_ROOT / "tmp",
    ):
        directory_fd = secure_open_directory(directory, OUTPUT_ROOT, create=True)
        os.close(directory_fd)
    return release_environment(offline=offline)


def offline_environment() -> dict[str, str]:
    return prepare_release_environment(offline=True)


@contextmanager
def sanitized_environment(offline: bool) -> Iterable[dict[str, str]]:
    previous = os.environ.copy()
    environment = prepare_release_environment(offline=offline)
    os.environ.clear()
    os.environ.update(environment)
    try:
        yield environment
    finally:
        os.environ.clear()
        os.environ.update(previous)


def require_sha256(value: str, name: str) -> str:
    if not SHA256_HEX.fullmatch(value):
        raise ValueError(f"{name} must be a lowercase SHA-256 digest")
    return value


def _interpreter_identity(executable: Path) -> dict[str, object] | None:
    prefix = read_regular_prefix(executable, 4096)
    if not prefix.startswith(b"#!"):
        return None
    first_line = prefix.splitlines()[0][2:].strip()
    try:
        interpreter_text = first_line.split(maxsplit=1)[0].decode("utf-8")
    except (IndexError, UnicodeDecodeError) as error:
        raise ValueError("tool executable has an invalid shebang") from error
    invoked_path = Path(interpreter_text)
    if not invoked_path.is_absolute() or invoked_path.name == "env":
        raise ValueError("tool shebang must use an absolute interpreter without /usr/bin/env")
    resolved_path = invoked_path.resolve(strict=True)
    fd, status = _open_stable_regular_file(resolved_path)
    os.close(fd)
    if status.st_mode & 0o022 or status.st_uid not in {0, os.getuid()} or not os.access(resolved_path, os.X_OK):
        raise ValueError("tool interpreter is writable, unowned, or non-executable")
    return {
        "invoked_path": str(invoked_path),
        "resolved_path": str(resolved_path),
        "sha256": sha256_file(resolved_path),
        "device": status.st_dev,
        "inode": status.st_ino,
        "size_bytes": status.st_size,
        "mode": oct(status.st_mode & 0o777),
        "uid": status.st_uid,
        "mtime_ns": status.st_mtime_ns,
    }


def _verify_interpreter_identity(identity: dict[str, object] | None) -> None:
    if identity is None:
        return
    invoked = Path(str(identity.get("invoked_path")))
    resolved = invoked.resolve(strict=True)
    if str(resolved) != identity.get("resolved_path"):
        raise ValueError("pinned tool interpreter target changed")
    fd, status = _open_stable_regular_file(resolved)
    os.close(fd)
    current = {
        "invoked_path": str(invoked),
        "resolved_path": str(resolved),
        "sha256": sha256_file(resolved),
        "device": status.st_dev,
        "inode": status.st_ino,
        "size_bytes": status.st_size,
        "mode": oct(status.st_mode & 0o777),
        "uid": status.st_uid,
        "mtime_ns": status.st_mtime_ns,
    }
    if current != identity:
        raise ValueError("pinned tool interpreter changed during the release operation")


def resolve_tool(executable: str, expected_sha256: str) -> PinnedTool:
    if not executable or "\x00" in executable:
        raise ValueError("tool executable is invalid")
    candidate = Path(executable)
    if not candidate.is_absolute():
        raise ValueError("real release commands require an absolute tool path")
    try:
        lexical_status = candidate.lstat()
    except FileNotFoundError as error:
        raise FileNotFoundError(f"required local tool is unavailable: {candidate}") from error
    if stat.S_ISLNK(lexical_status.st_mode):
        raise ValueError("tool executable path must not be a symbolic link")
    path = candidate.resolve(strict=True)
    if candidate != path:
        raise ValueError("tool executable path must be canonical and contain no symlink components")
    fd, status = _open_stable_regular_file(path)
    os.close(fd)
    if status.st_mode & 0o022:
        raise ValueError("tool executable must not be group- or world-writable")
    if status.st_uid not in {0, os.getuid()}:
        raise ValueError("tool executable must be owned by the current user or root")
    if not os.access(path, os.X_OK):
        raise ValueError("tool executable is not executable")
    expected = require_sha256(expected_sha256, "expected tool digest")
    actual = sha256_file(path)
    if actual != expected:
        raise ValueError("tool executable does not match the operator-pinned SHA-256")
    return PinnedTool(
        path=path,
        sha256=actual,
        device=status.st_dev,
        inode=status.st_ino,
        size_bytes=status.st_size,
        mode=status.st_mode,
        uid=status.st_uid,
        mtime_ns=status.st_mtime_ns,
        interpreter_identity=_interpreter_identity(path),
    )


def verify_pinned_tool(tool: PinnedTool, verify_hash: bool = True) -> None:
    fd, status = _open_stable_regular_file(tool.path)
    os.close(fd)
    identity = (status.st_dev, status.st_ino, status.st_size, status.st_mode, status.st_uid, status.st_mtime_ns)
    expected_identity = (
        tool.device,
        tool.inode,
        tool.size_bytes,
        tool.mode,
        tool.uid,
        tool.mtime_ns,
    )
    if identity != expected_identity or (verify_hash and sha256_file(tool.path) != tool.sha256):
        raise ValueError("pinned tool executable changed during the release operation")
    _verify_interpreter_identity(tool.interpreter_identity)


def open_pinned_tool_fd(tool: PinnedTool) -> int:
    fd, status = _open_stable_regular_file(tool.path)
    identity = (status.st_dev, status.st_ino, status.st_size, status.st_mode, status.st_uid, status.st_mtime_ns)
    expected_identity = (
        tool.device,
        tool.inode,
        tool.size_bytes,
        tool.mode,
        tool.uid,
        tool.mtime_ns,
    )
    if identity != expected_identity:
        os.close(fd)
        raise ValueError("pinned tool executable identity changed before execution")
    digest = hashlib.sha256()
    try:
        while block := os.read(fd, 1024 * 1024):
            digest.update(block)
        os.lseek(fd, 0, os.SEEK_SET)
    except BaseException:
        os.close(fd)
        raise
    if digest.hexdigest() != tool.sha256:
        os.close(fd)
        raise ValueError("pinned tool executable content changed before execution")
    try:
        _verify_interpreter_identity(tool.interpreter_identity)
    except BaseException:
        os.close(fd)
        raise
    return fd


def _terminate_process_group(process: subprocess.Popen[bytes]) -> None:
    if process.poll() is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    try:
        process.wait(timeout=5)
        return
    except subprocess.TimeoutExpired:
        pass
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    process.wait(timeout=5)


def run_bounded_command(
    command: list[str],
    *,
    timeout_seconds: int,
    max_stdout_bytes: int,
    max_stderr_bytes: int,
    environment: dict[str, str] | None = None,
    pinned_tool: PinnedTool | None = None,
) -> CommandResult:
    if not command or any("\x00" in argument for argument in command):
        raise ValueError("subprocess command is invalid")
    output_root_fd = secure_open_directory(OUTPUT_ROOT, OUTPUT_ROOT, create=False)
    os.close(output_root_fd)
    with (
        tempfile.TemporaryFile(dir=OUTPUT_ROOT) as stdout,
        tempfile.TemporaryFile(dir=OUTPUT_ROOT) as stderr,
    ):
        tool_fd: int | None = None
        popen_options: dict[str, object] = {}
        if pinned_tool is not None:
            if command[0] != str(pinned_tool.path):
                raise ValueError("pinned subprocess command does not use the pinned tool path")
            tool_fd = open_pinned_tool_fd(pinned_tool)
            proc_fd_path = Path(f"/proc/self/fd/{tool_fd}")
            if not proc_fd_path.exists():
                os.close(tool_fd)
                raise RuntimeError("descriptor-pinned execution requires /proc/self/fd")
            popen_options = {
                "executable": str(proc_fd_path),
                "pass_fds": (tool_fd,),
            }
        try:
            process = subprocess.Popen(
                command,
                cwd=REPO_ROOT,
                env=environment if environment is not None else offline_environment(),
                stdin=subprocess.DEVNULL,
                stdout=stdout,
                stderr=stderr,
                start_new_session=True,
                **popen_options,
            )
        finally:
            if tool_fd is not None:
                os.close(tool_fd)
        try:
            deadline = time.monotonic() + timeout_seconds
            boundary_error: str | None = None
            while process.poll() is None:
                if os.fstat(stdout.fileno()).st_size > max_stdout_bytes:
                    boundary_error = "subprocess stdout exceeds the release boundary"
                    break
                if os.fstat(stderr.fileno()).st_size > max_stderr_bytes:
                    boundary_error = "subprocess stderr exceeds the release boundary"
                    break
                if time.monotonic() >= deadline:
                    boundary_error = "subprocess exceeded the release timeout"
                    break
                time.sleep(0.05)
            if boundary_error is not None:
                _terminate_process_group(process)
                raise RuntimeError(boundary_error)
            returncode = process.wait()
            if (
                os.fstat(stdout.fileno()).st_size > max_stdout_bytes
                or os.fstat(stderr.fileno()).st_size > max_stderr_bytes
            ):
                raise RuntimeError("subprocess output exceeds the release boundary")
            stdout.seek(0)
            stderr.seek(0)
            return CommandResult(
                returncode=returncode,
                stdout=stdout.read().decode("utf-8", errors="replace"),
                stderr=stderr.read().decode("utf-8", errors="replace"),
            )
        except BaseException:
            _terminate_process_group(process)
            raise


def tool_version(tool: PinnedTool) -> str:
    verify_pinned_tool(tool)
    result = run_bounded_command(
        [str(tool.path), "--version"],
        timeout_seconds=30,
        max_stdout_bytes=64 * 1024,
        max_stderr_bytes=64 * 1024,
        pinned_tool=tool,
    )
    verify_pinned_tool(tool)
    if result.returncode != 0:
        raise RuntimeError(f"tool version check failed with exit code {result.returncode}")
    version = "\n".join(part.strip() for part in (result.stdout, result.stderr) if part.strip())
    if not version or len(version.encode("utf-8")) > 4096:
        raise ValueError("tool version output is empty or too large")
    return version
