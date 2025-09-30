"""Utility script for android-ci workflow to generate summarizer assets."""
from __future__ import annotations

import importlib
import subprocess
import sys
from pathlib import Path


REQUIRED_ASSET_RELATIVE_PATHS = [
    Path("app/src/main/assets/encoder_int8_dynamic.tflite"),
    Path("app/src/main/assets/decoder_step_int8_dynamic.tflite"),
    Path("app/src/main/assets/tokenizer.json"),
]


class TensorFlowUnavailableError(RuntimeError):
    """Raised when TensorFlow (or its dependencies) cannot be imported."""


def _ensure_package(module_name: str, *, package: str | None = None) -> None:
    """Install ``package`` via pip if ``module_name`` cannot be imported."""

    try:
        importlib.import_module(module_name)
    except ModuleNotFoundError:
        install_target = package or module_name
        subprocess.check_call([sys.executable, "-m", "pip", "install", install_target])
        try:
            importlib.import_module(module_name)
        except ModuleNotFoundError as exc:  # pragma: no cover - sanity guard
            raise TensorFlowUnavailableError(
                f"Module '{module_name}' is unavailable even after installation"
            ) from exc


def _repo_root() -> Path:
    path = Path(__file__).resolve().parent
    while path != path.parent and not (path / ".git").exists():
        path = path.parent
    return path


def _resolve_required_asset_paths() -> list[Path]:
    repo_root = _repo_root()
    return [repo_root / rel for rel in REQUIRED_ASSET_RELATIVE_PATHS]


def _report_missing_assets(missing_paths: list[Path]) -> None:
    print("Summarizer assets missing; regenerating via build_tensor.ipynb:")
    for path in missing_paths:
        print(f"  - {path.relative_to(_repo_root())}")


def _generate_with_tensorflow() -> None:
    required_packages = {
        "tensorflow": "tensorflow==2.19.0",
        "tf_keras": "tf-keras==2.19.0",
        "transformers": "transformers==4.44.2",
        "huggingface_hub": "huggingface_hub>=0.24.0",
        "numpy": "numpy==2.0.2",
        "protobuf": "protobuf==5.29.1",
        "ml_dtypes": "ml-dtypes>=0.5.0",
        "datasets": "datasets==3.1.0",
        "sentencepiece": None,
    }

    for module_name, package_spec in required_packages.items():
        try:
            _ensure_package(module_name, package=package_spec)
        except subprocess.CalledProcessError as exc:
            raise TensorFlowUnavailableError(
                f"Failed to install dependency for '{module_name}'"
            ) from exc

    notebook = Path("build_tensor.ipynb")
    if not notebook.exists():
        raise FileNotFoundError("build_tensor.ipynb not found")

    filtered_lines: list[str] = []
    skip_continuation = False
    for line in notebook.read_text().splitlines():
        stripped = line.lstrip()

        if skip_continuation:
            skip_continuation = stripped.endswith("\\")
            continue

        if stripped.startswith("%pip"):
            skip_continuation = stripped.endswith("\\")
            continue

        filtered_lines.append(line)

    code = compile("\n".join(filtered_lines), str(notebook), "exec")
    globals_dict = {"__name__": "__main__"}
    try:
        exec(code, globals_dict)
    except ModuleNotFoundError as exc:
        raise TensorFlowUnavailableError(
            "TensorFlow dependencies missing while executing build_tensor.ipynb"
        ) from exc


def main() -> None:
    required_paths = _resolve_required_asset_paths()

    missing_paths = [path for path in required_paths if not path.exists()]
    if not missing_paths:
        print("Summarizer assets already present; skipping generation.")
        return

    _report_missing_assets(missing_paths)

    _generate_with_tensorflow()

    remaining_missing = [path for path in required_paths if not path.exists()]
    if remaining_missing:
        missing_display = ", ".join(str(path.relative_to(_repo_root())) for path in remaining_missing)
        raise RuntimeError(f"Failed to generate required summarizer assets: {missing_display}")

    print("Summarizer assets generated successfully.")


if __name__ == "__main__":
    main()
