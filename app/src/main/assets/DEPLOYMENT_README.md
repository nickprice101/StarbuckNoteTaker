# AI Deployment Notes (MLC LLM - Llama 3.2 3B Instruct)

This document describes the AI inference stack integrated into the Android app.

## Runtime Overview

- Inference is performed on-device via MLC LLM (`ai.mlc.mlcllm.MLCEngine`).
- The model is Llama 3.2 3B Instruct with q4f16_0 quantization.
- Devices with less than 4 GB total RAM have AI features disabled at runtime and fall back to rule-based heuristics (`DeviceCapabilityChecker`).
- Thermal throttling is applied on API 31+ devices when `PowerManager.thermalHeadroom` is critically low.

## Build-Time Requirements

The build has two Llama native ABI profiles:

| ABI | Model archive | TVM runtime source |
|-----|---------------|--------------------|
| `arm64-v8a` | `app/src/main/assets/Llama-3.2-3B-Instruct-q4f16_0-MLC-android.tar` | `bash scripts/fetch_mlc_native.sh` |
| `x86_64` | `app/src/main/assets/Llama-3.2-3B-Instruct-q4f16_0-MLC-android-x86_64.tar` | `TARGET_ABI=x86_64 bash scripts/fetch_mlc_native.sh` |

The checked-in model archive uses TVM FFI system-library metadata
(`__tvm_ffi_*`, `library_bin`, and `library_ctx`). It requires a
TVM FFI-capable `libtvm4j_runtime_packed.so`; the old `Android-09262024`
binary APK runtime does not provide `ffi.SystemLib` and cannot load this archive.

## TVM Runtime

The packed TVM Android runtime must be present before building:

```text
mlc4j/src/main/jniLibs/<abi>/libtvm4j_runtime_packed.so
```

Install the MLC Python tooling, then build the runtime from MLC source:

```bash
bash scripts/install_mlc_llm.sh
bash scripts/fetch_mlc_native.sh
```

For an x86_64 web or desktop emulator, build both the x86_64 model archive and
runtime:

```bash
bash scripts/install_mlc_llm.sh
TARGET_ABI=x86_64 bash scripts/compile_model_tar.sh
TARGET_ABI=x86_64 bash scripts/fetch_mlc_native.sh
```

Gradle task `buildModelLibSo` validates that the runtime contains
`ffi.SystemLib` and `TVMFFIEnvModRegisterSystemLibSymbol` before linking the
model objects. This prevents builds that package the stale runtime and later fail
with `Cannot find system lib with llama_q4f16_0`.

## Model Library Archive

The compiled model library is distributed as a tar archive bundled inside the APK:

```text
app/src/main/assets/Llama-3.2-3B-Instruct-q4f16_0-MLC-android.tar
```

The archive contains TVM system-lib object files such as `lib0.o` and
`llama_q4f16_0_devc.o`. Gradle links those objects into:

```text
app/src/main/jniLibs/<abi>/libLlama-3.2-3B-Instruct-q4f16_0-MLC.so
```

At runtime `LlamaEngine` loads that shared library with `System.load(path)` and
passes `system://llama_q4f16_0` to `MLCEngine.reload()`, causing MLC to retrieve
the registered module through `ffi.SystemLib`.

Regenerate the arm64 model archive with:

```bash
bash scripts/compile_model_tar.sh
```

The script downloads the quantized weights from HuggingFace, runs
`mlc_llm compile --target android --system-lib`, verifies the output, and writes
the `.tar` to the assets directory.

## Runtime Weight Download

The model weights, approximately 2 GB, are not bundled in the APK. They are
downloaded from HuggingFace on first use via the Settings screen and cached at:

```text
filesDir/models/Llama-3.2-3B-Instruct-q4f16_0-MLC/
```

The download is managed by `LlamaModelManager`, which exposes a `modelStatus`
`StateFlow` for UI progress.

## AI Modes

| Mode | Description |
|------|-------------|
| `SUMMARISE` | Concise 1-3 line note preview |
| `REWRITE` | Rewrites the note in a clean, professional style |
| `QUESTION` | Answers a free-form question using optional note context |

## Fallback Behavior

When the model is unavailable, native artifacts are missing, the engine reports a
background failure, or the device has insufficient RAM, AI operations fall back
automatically to the lightweight rule-based heuristics in `Summarizer`.

## Legacy TFLite Assets

The files `note_classifier.tflite`, `tokenizer_vocabulary_v2.txt`,
`category_mapping.json`, and `deployment_metadata.json` are retained in
`app/src/main/assets/` as legacy artifacts from the previous TFLite
classification path. They are no longer used at runtime.
