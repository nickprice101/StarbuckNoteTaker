#!/usr/bin/env python3
"""Capture the TensorFlow Lite smoke test output for the bundled note classifier."""
from __future__ import annotations

import argparse
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
VERIFY_SCRIPT = REPO_ROOT / "app" / "src" / "main" / "assets" / "scripts" / "verify_note_classifier_model.py"
DEFAULT_OUTPUT = REPO_ROOT / "app" / "src" / "main" / "assets" / "note_classifier_smoke_test_output.txt"


def run_smoke_test(model_path: Path) -> str:
    if not VERIFY_SCRIPT.exists():
        raise FileNotFoundError(f"Unable to locate verification script: {VERIFY_SCRIPT}")

    command = [sys.executable, str(VERIFY_SCRIPT), "--model", str(model_path)]
    result = subprocess.run(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        check=False,
    )

    output = result.stdout
    if result.returncode != 0:
        raise RuntimeError(
            "TensorFlow Lite smoke test failed with exit code "
            f"{result.returncode}. Output:\n{output.strip()}"
        )
    return output


def write_output(output_path: Path, contents: str) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now(timezone.utc).isoformat()
    sanitized = contents.replace("\x00", "")
    if not sanitized.endswith("\n"):
        sanitized = f"{sanitized}\n"
    header = (
        "# TensorFlow Lite smoke test output\n"
        f"# Captured at {timestamp}\n"
        f"# Model path: note_classifier.tflite\n\n"
    )
    output_path.write_text(header + sanitized, encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--model",
        type=Path,
        default=VERIFY_SCRIPT.parent.parent / "note_classifier.tflite",
        help="Path to the TensorFlow Lite model to verify.",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=DEFAULT_OUTPUT,
        help="File path to write the captured smoke test output.",
    )

    args = parser.parse_args(argv)

    model_path = args.model
    if not model_path.exists():
        raise FileNotFoundError(f"TensorFlow Lite model not found: {model_path}")

    output = run_smoke_test(model_path)
    write_output(args.output, output)

    print(f"Smoke test output captured to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
