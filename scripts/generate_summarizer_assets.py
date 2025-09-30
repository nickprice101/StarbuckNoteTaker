"""Utility script for android-ci workflow to generate summarizer assets."""
from __future__ import annotations

from pathlib import Path


def main() -> None:
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
