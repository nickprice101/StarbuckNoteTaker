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


def _ensure_package(module_name: str, *, package: str | None = None) -> None:
    """Install ``package`` via pip if ``module_name`` cannot be imported."""

    try:
        importlib.import_module(module_name)
    except ModuleNotFoundError:
        install_target = package or module_name
        subprocess.check_call([sys.executable, "-m", "pip", "install", install_target])


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


def main() -> None:
    required_paths = _resolve_required_asset_paths()

    missing_paths = [path for path in required_paths if not path.exists()]
    if not missing_paths:
        print("Summarizer assets already present; skipping generation.")
        return

    _report_missing_assets(missing_paths)

    _ensure_package("sentencepiece")

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
    exec(code, globals_dict)

    remaining_missing = [path for path in required_paths if not path.exists()]
    if remaining_missing:
        missing_display = ", ".join(str(path.relative_to(_repo_root())) for path in remaining_missing)
        raise RuntimeError(f"Failed to generate required summarizer assets: {missing_display}")

    print("Summarizer assets generated successfully.")


if __name__ == "__main__":
    main()
