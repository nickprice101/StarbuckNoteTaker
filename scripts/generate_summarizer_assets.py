"""Utility script for android-ci workflow to generate summarizer assets."""
from __future__ import annotations

import importlib
import subprocess
import sys
from pathlib import Path


def _ensure_package(module_name: str, *, package: str | None = None) -> None:
    """Install ``package`` via pip if ``module_name`` cannot be imported."""

    try:
        importlib.import_module(module_name)
    except ModuleNotFoundError:
        install_target = package or module_name
        subprocess.check_call([sys.executable, "-m", "pip", "install", install_target])


def main() -> None:
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


if __name__ == "__main__":
    main()
