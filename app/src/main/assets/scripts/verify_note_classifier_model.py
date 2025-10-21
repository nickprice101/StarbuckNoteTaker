#!/usr/bin/env python3
"""Verify note_classifier.tflite operator compatibility for the Android runtime."""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

try:
    import numpy as np
except ImportError:  # pragma: no cover - optional dependency for inference smoke test
    np = None

try:
    import tensorflow as tf
except ImportError:  # pragma: no cover - optional dependency for inference smoke test
    tf = None

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
SAMPLE_NOTE_TEXT = (
    "Homemade pasta recipe: mix 2 cups flour with 3 eggs until dough forms, "
    "knead for 10 minutes until smooth and elastic, roll thin with pasta machine, "
    "cut into fettuccine strips, boil in salted water for 3 minutes."
)
EXPECTED_SAMPLE_CATEGORY = "FOOD_RECIPE"


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

    _run_inference_smoke_test(path)

    return 0


def _load_category_mapping(path: Path) -> list[str]:
    if not path.exists():
        raise FileNotFoundError(f"Category mapping file not found: {path}")

    with path.open("r", encoding="utf-8") as handle:
        data = json.load(handle)

    categories = data.get("categories")
    if not isinstance(categories, list) or not categories:
        raise ValueError("Category mapping must provide a non-empty 'categories' array.")

    return [str(item) for item in categories]


def _build_food_recipe_summary(note_text: str) -> str:
    context, _, content = note_text.partition(":")
    context = context.strip()
    content = content.strip()
    is_long = len(content.split()) > 50

    if context:
        recipe_name = (
            context.lower()
            .replace(" recipe", "")
            .replace(" how to make", "")
            .strip()
        )
        if not recipe_name:
            recipe_name = "the dish"
        if is_long:
            return f"Recipe with detailed instructions for preparing {recipe_name}"
        return f"Recipe for {recipe_name}"

    if is_long:
        return "Recipe note with detailed cooking instructions"
    return "Recipe note with cooking instructions"


def _build_personal_daily_life_summary(note_text: str) -> str:
    context, _, _ = note_text.partition(":")
    context = context.strip().lower()
    activity = context if context else "daily activity"
    return f"Daily life note about {activity}"


def _build_sample_summary(note_text: str, category: str) -> str:
    category = category.upper()
    if category == "FOOD_RECIPE":
        return _build_food_recipe_summary(note_text)
    if category == "PERSONAL_DAILY_LIFE":
        return _build_personal_daily_life_summary(note_text)
    friendly = category.replace("_", " ").title()
    return f"{friendly} summary example unavailable for sample input"


def _run_inference_smoke_test(model_path: Path) -> None:
    if tf is None or np is None:
        print("TensorFlow not available; skipping inference smoke test.")
        return

    try:
        categories = _load_category_mapping(model_path.with_name("category_mapping.json"))
    except Exception as exc:
        print(f"Unable to load category mapping for inference smoke test: {exc}")
        return

    try:
        interpreter = tf.lite.Interpreter(model_path=str(model_path))
        interpreter.allocate_tensors()

        input_details = interpreter.get_input_details()
        if len(input_details) != 1:
            raise RuntimeError(
                f"Expected model to expose a single input tensor, found {len(input_details)}"
            )

        input_info = input_details[0]
        input_index = input_info["index"]
        input_dtype = input_info["dtype"]

        supported_dtypes = {np.object_}
        bytes_dtype = getattr(np, "bytes_", None)
        str_dtype = getattr(np, "str_", None)
        if bytes_dtype is not None:
            supported_dtypes.add(bytes_dtype)
        if str_dtype is not None:
            supported_dtypes.add(str_dtype)

        if input_dtype not in supported_dtypes:
            raise RuntimeError(
                "TensorFlow Lite model input dtype mismatch: expected a string-compatible "
                f"dtype but received {input_dtype!r}."
            )

        if bytes_dtype is not None and input_dtype == bytes_dtype:
            input_value = np.array([[SAMPLE_NOTE_TEXT.encode("utf-8")]], dtype=bytes_dtype)
        elif str_dtype is not None and input_dtype == str_dtype:
            input_value = np.array([[SAMPLE_NOTE_TEXT]], dtype=str_dtype)
        else:
            input_value = np.array([[SAMPLE_NOTE_TEXT]], dtype=np.object_)

        interpreter.set_tensor(input_index, input_value)

        interpreter.invoke()

        output_details = interpreter.get_output_details()
        if not output_details:
            raise RuntimeError("TensorFlow Lite model did not expose any output tensors.")

        output_info = output_details[0]
        output_index = output_info["index"]
        output = interpreter.get_tensor(output_index)
        if output.ndim != 2 or output.shape[0] != 1:
            raise RuntimeError(
                f"Unexpected output tensor shape {output.shape}; expected [1, num_categories]."
            )

        scores = output[0]
        if scores.size != len(categories):
            raise RuntimeError(
                "Output category count mismatch: interpreter returned "
                f"{scores.size} scores but mapping defines {len(categories)} categories."
            )

        predicted_index = int(np.argmax(scores))
        predicted_category = categories[predicted_index]
        print(
            "Inference smoke test predicted category: "
            f"{predicted_category} (score={scores[predicted_index]:.3f})"
        )

        if predicted_category != EXPECTED_SAMPLE_CATEGORY:
            print(
                f"Warning: Expected sample note to classify as {EXPECTED_SAMPLE_CATEGORY} "
                f"but received {predicted_category}."
            )

        summary = _build_sample_summary(SAMPLE_NOTE_TEXT, predicted_category)
        print(f"Example enhanced summary: {summary}")
    except Exception as exc:  # pragma: no cover - diagnostic output for build failures
        raise SystemExit(
            f"TensorFlow Lite inference smoke test failed: {exc}"
        ) from exc


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
