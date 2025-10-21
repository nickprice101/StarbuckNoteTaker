#!/usr/bin/env python3
"""Verify note_classifier.tflite operator compatibility for the Android runtime."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
VENDOR_DIR = SCRIPT_DIR / "_vendor"
if VENDOR_DIR.exists():
    sys.path.insert(0, str(VENDOR_DIR))

try:
    from tflite.BuiltinOperator import BuiltinOperator
    from tflite.Model import Model
except ImportError as exc:  # pragma: no cover - defensive guard for developer machines
    raise SystemExit(
        "Unable to import the vendored TensorFlow Lite FlatBuffer schema. "
        "Verify that app/src/main/assets/scripts/_vendor is checked out correctly."
    ) from exc

MAX_FULLY_CONNECTED_VERSION = 11


def _decode_optional_string(value: str | bytes | None) -> str | None:
    if value is None:
        return None
    if isinstance(value, bytes):
        try:
            return value.decode("utf-8")
        except UnicodeDecodeError:
            return None
    return value


def _load_model(buffer: bytes) -> Model:
    """Return the FlatBuffer model view for ``buffer``."""

    if not Model.ModelBufferHasIdentifier(buffer, 0):
        raise ValueError("The provided buffer does not contain a valid TFL3 FlatBuffer.")
    return Model.GetRootAs(buffer, 0)


def _collect_fully_connected_codes(model: Model) -> dict[int, int]:
    """Return {operator_code_index: version} for FULLY_CONNECTED operator codes."""

    indices: dict[int, int] = {}
    for idx in range(model.OperatorCodesLength()):
        op_code = model.OperatorCodes(idx)
        if op_code and op_code.BuiltinCode() == BuiltinOperator.FULLY_CONNECTED:
            indices[idx] = op_code.Version()
    return indices


def _summarise_fc_usage(model: Model, fc_versions: dict[int, int]) -> tuple[int, int]:
    """Return (usage_count, highest_version) across all subgraph operators."""

    usage_count = 0
    highest_version = 0

    for subgraph_idx in range(model.SubgraphsLength()):
        subgraph = model.Subgraphs(subgraph_idx)
        if subgraph is None:
            continue
        for op_idx in range(subgraph.OperatorsLength()):
            operator = subgraph.Operators(op_idx)
            if operator is None:
                continue
            version = fc_versions.get(operator.OpcodeIndex())
            if version is None:
                continue
            usage_count += 1
            if version > highest_version:
                highest_version = version

    return usage_count, highest_version


def _extract_min_runtime_version(model: Model) -> str | None:
    """Return the ``min_runtime_version`` metadata entry if present."""

    if model.MetadataIsNone():
        return None

    for meta_idx in range(model.MetadataLength()):
        metadata = model.Metadata(meta_idx)
        if metadata is None:
            continue
        name = _decode_optional_string(metadata.Name())
        if name != "min_runtime_version":
            continue

        buffer_index = metadata.Buffer()
        if buffer_index >= model.BuffersLength():
            return None
        buffer = model.Buffers(buffer_index)
        if buffer is None:
            return None
        data_length = buffer.DataLength()
        if data_length == 0:
            return None
        data_bytes = bytes(buffer.Data(i) for i in range(data_length))
        try:
            return data_bytes.decode("utf-8").strip() or None
        except UnicodeDecodeError:
            return None

    return None


def verify_model(path: Path) -> int:
    buffer = path.read_bytes()
    model = _load_model(buffer)

    fc_versions = _collect_fully_connected_codes(model)
    if not fc_versions:
        print("No FULLY_CONNECTED operator codes were found in the supplied model.")
        return 0

    usage_count, highest_version = _summarise_fc_usage(model, fc_versions)
    declared_runtime = _extract_min_runtime_version(model)

    print(
        "FULLY_CONNECTED operator usage: "
        f"{usage_count} occurrences across {len(fc_versions)} operator codes ("
        f"highest version v{highest_version or 0})."
    )
    if declared_runtime:
        print(f"Declared min_runtime_version: {declared_runtime}")
    else:
        print("Declared min_runtime_version: <not declared>")

    offending = [
        (idx, version)
        for idx, version in sorted(fc_versions.items())
        if version > MAX_FULLY_CONNECTED_VERSION
    ]
    if offending:
        detail = ", ".join(f"index {idx} reports v{version}" for idx, version in offending)
        raise SystemExit(
            "FULLY_CONNECTED operator codes exceed the supported maximum version "
            f"v{MAX_FULLY_CONNECTED_VERSION}: {detail}"
        )

    return 0


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--model",
        type=Path,
        default=SCRIPT_DIR.parent / "note_classifier.tflite",
        help="Path to the TensorFlow Lite model to inspect (defaults to the bundled asset).",
    )
    args = parser.parse_args()

    model_path = args.model
    if not model_path.exists():
        raise SystemExit(f"Unable to locate TensorFlow Lite model: {model_path}")

    try:
        exit_code = verify_model(model_path)
    except OSError as exc:
        raise SystemExit(f"Failed to read model '{model_path}': {exc}") from exc

    raise SystemExit(exit_code)


if __name__ == "__main__":
    main()
