#!/usr/bin/env python3
"""Patch upstream MLC Android prepare_libs.py for this app's CI build."""

from __future__ import annotations

import argparse
from pathlib import Path


UPSTREAM_LOGGING_IMPORT = (
    "from mlc_llm.support import logging\n\n"
    "logging.enable_logging()\n"
    "logger = logging.getLogger(__name__)\n"
)
STANDARD_LOGGING_IMPORT = (
    "import logging\n\n"
    "logging.basicConfig(level=logging.INFO)\n"
    "logger = logging.getLogger(__name__)\n"
)


def patch_prepare_libs(path: Path, target_abi: str, rust_target: str) -> None:
    text = path.read_text(encoding="utf-8")

    if UPSTREAM_LOGGING_IMPORT in text:
        text = text.replace(UPSTREAM_LOGGING_IMPORT, STANDARD_LOGGING_IMPORT)
    elif "from mlc_llm.support import logging" in text:
        raise RuntimeError("prepare_libs.py uses an unknown mlc_llm logging import pattern")

    text = text.replace("-DANDROID_ABI=arm64-v8a", f"-DANDROID_ABI={target_abi}")
    text = text.replace(
        '"rustup", "target", "add", "aarch64-linux-android"',
        f'"rustup", "target", "add", "{rust_target}"',
    )

    if "from mlc_llm.support import logging" in text:
        raise RuntimeError("prepare_libs.py still imports mlc_llm.support.logging")
    if f"-DANDROID_ABI={target_abi}" not in text:
        raise RuntimeError(f"prepare_libs.py was not patched for Android ABI {target_abi}")
    if f'"rustup", "target", "add", "{rust_target}"' not in text:
        raise RuntimeError(f"prepare_libs.py was not patched for Rust target {rust_target}")

    path.write_text(text, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepare-libs", required=True, type=Path)
    parser.add_argument("--target-abi", required=True)
    parser.add_argument("--rust-target", required=True)
    args = parser.parse_args()

    patch_prepare_libs(args.prepare_libs, args.target_abi, args.rust_target)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
