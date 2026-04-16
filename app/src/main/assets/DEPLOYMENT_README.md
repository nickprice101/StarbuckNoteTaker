# AI Deployment Notes (MLC LLM — Llama 3.2 3B Instruct)

This document describes the AI inference stack integrated into the Android app.

## Runtime overview

- Inference is performed on-device via **MLC LLM** (`ai.mlc.mlcllm.MLCEngine`).
- The model is **Llama 3.2 3B Instruct** (q4f16_0 quantisation).
- Devices with less than **4 GB** total RAM have AI features disabled at runtime and fall back to rule-based heuristics (`DeviceCapabilityChecker`).
- Thermal throttling is applied on API 31+ devices when `PowerManager.thermalHeadroom` is critically low.

## Build-time requirements

### 1. TVM runtime — `libtvm4j_runtime_packed.so`

The packed TVM Android runtime must be present before building:

```
app/src/main/jniLibs/arm64-v8a/libtvm4j_runtime_packed.so
```

Run the fetch script (requires `curl` and `unzip`):

```bash
bash scripts/fetch_mlc_native.sh
```

This downloads `mlc-chat.apk` from the `mlc-ai/binary-mlc-llm-libs` release
`Android-09262024` and extracts the `.so` from it.  The CI workflow runs this
step automatically.

### 2. Model library archive — system-lib `.tar`

The compiled model library is distributed as a gzip-compressed tar archive
bundled inside the APK:

```
app/src/main/assets/Llama-3.2-3B-Instruct-q4f16_0-MLC-android.tar
```

The archive contains the TVM system-lib object files (`lib0.o`,
`llama_q4f16_0_devc.o`).  At runtime `LlamaModelManager.extractModelLibIfNeeded()`
extracts these to `filesDir/lib/Llama-3.2-3B-Instruct-q4f16_0-MLC-android/` and
passes the directory path to `MLCEngine.reload()` as `modelLib`.

**The placeholder `.tar` currently committed contains stub `.o` files and must
be replaced with a real compiled archive** before inference will work.  Run:

```bash
bash scripts/compile_model_tar.sh
```

Prerequisites: Python 3.10+, `mlc_llm` Python package, Android NDK r27+.

```bash
pip install mlc-llm
# Set ANDROID_NDK if not already on PATH
export ANDROID_NDK=/path/to/android-ndk-r27
bash scripts/compile_model_tar.sh
```

The script downloads the quantised weights from HuggingFace, runs
`mlc_llm compile --target android --system-lib`, verifies the output, and
writes the `.tar` to the assets directory.

## Runtime weight download

The model weights (~2 GB) are **not bundled** in the APK.  They are downloaded
from HuggingFace on first use via the Settings screen and cached at:

```
filesDir/models/Llama-3.2-3B-Instruct-q4f16_0-MLC/
```

The download is managed by `LlamaModelManager`, which exposes a `modelStatus`
`StateFlow` for UI progress.

## AI modes

| Mode | Description |
|------|-------------|
| `SUMMARISE` | Concise 1–3 line note preview |
| `REWRITE` | Rewrites the note in a clean, professional style |
| `QUESTION` | Answers a free-form question using optional note context |

## Foreground service

Heavy inference runs inside `LlamaForegroundService` to prevent the OS from
killing the process during extended computation.

## Fallback behaviour

When the model is unavailable (weights not yet downloaded, native library
missing, or insufficient device RAM), all AI operations fall back automatically
to the lightweight rule-based heuristics in `Summarizer`.

## Legacy TFLite assets

The files `note_classifier.tflite`, `tokenizer_vocabulary_v2.txt`,
`category_mapping.json`, and `deployment_metadata.json` are retained in
`app/src/main/assets/` as legacy artifacts from the previous TFLite
classification path.  They are no longer used at runtime.

