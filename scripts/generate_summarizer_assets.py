"""Utility script to verify summarizer assets required by the Android build."""
from __future__ import annotations

from pathlib import Path


REQUIRED_ASSET_RELATIVE_PATHS = [
    Path("app/src/main/assets/note_classifier.tflite"),
    Path("app/src/main/assets/category_mapping.json"),
    Path("app/src/main/assets/deployment_metadata.json"),
]


def _repo_root() -> Path:
    path = Path(__file__).resolve().parent
    while path != path.parent and not (path / ".git").exists():
        path = path.parent
    return path


def _resolve_required_asset_paths() -> list[Path]:
    repo_root = _repo_root()
    return [repo_root / rel for rel in REQUIRED_ASSET_RELATIVE_PATHS]


def _report_missing_assets(missing_paths: list[Path]) -> None:
    print("⚠️  Summarizer assets missing from the repository:")
    for path in missing_paths:
        print(f"  - {path.relative_to(_repo_root())}")
    print(
        "    These models are produced offline and must be added manually before running "
        "ML-dependent features or tests."
    )


def main() -> None:
    required_paths = _resolve_required_asset_paths()

    missing_paths = [path for path in required_paths if not path.exists()]
    if not missing_paths:
        print("Summarizer assets already present; skipping generation.")
        return

    _report_missing_assets(missing_paths)
    raise SystemExit(1)


if __name__ == "__main__":
    main()
