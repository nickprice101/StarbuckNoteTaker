# AI Deployment Notes (MLC LLM — Llama 3.2 3B Instruct)

This document describes the AI inference stack currently integrated into the Android app.

## Runtime overview

- Inference is performed on-device via **MLC LLM** (`ai.mlc.mlcllm.MLCEngine`).
- The model is **Llama 3.2 3B Instruct** (q4f16_0 quantisation).
- Devices with less than **4 GB** total RAM have AI features disabled at runtime and fall back to rule-based heuristics (`DeviceCapabilityChecker`).
- Thermal throttling is applied on API 31+ devices when `PowerManager.thermalHeadroom` is critically low.

## Build-time requirements

### Compiled model library (`.so`)

The MLC-compiled model library must be placed in the APK before building:

```
app/src/main/jniLibs/arm64-v8a/libLlama-3.2-3B-Instruct-q4f16_0-MLC.so
```

Obtain it from the MLC prebuilt release:
<https://github.com/mlc-ai/binary-mlc-llm-libs/releases/tag/Android-09262024>

```bash
unzip mlc-chat.apk "lib/arm64-v8a/libLlama-3.2-3B-Instruct-q4f16_0-MLC.so" -d extracted/
cp extracted/lib/arm64-v8a/libLlama-3.2-3B-Instruct-q4f16_0-MLC.so \
   app/src/main/jniLibs/arm64-v8a/
```

The `.so` is excluded from the repository via `.gitignore`. Each developer and CI environment must obtain and place it locally before building.

## Runtime weight download

The model weights (~2 GB) are **not bundled** in the APK. They are downloaded from HuggingFace on first use and cached at:

```
filesDir/models/Llama-3.2-3B-Instruct-q4f16_0-MLC/
```

The download is managed by `LlamaModelManager`, which exposes a `modelStatus` `StateFlow` for UI progress. The following shards are retrieved:

- `mlc-chat-config.json`
- `ndarray-cache.json`
- `tokenizer.json` / `tokenizer_config.json`
- `params_shard_*.bin` (many shards)

## AI modes

| Mode | Description |
|------|-------------|
| `SUMMARISE` | Concise 1–3 line note preview |
| `REWRITE` | Rewrites the note in a clean, professional style |
| `QUESTION` | Answers a free-form question using optional note context |

## Foreground service

Heavy inference runs inside `LlamaForegroundService` to prevent the OS from killing the process during extended computation.

## Fallback behaviour

When the model is unavailable (weights not yet downloaded, `.so` missing, or insufficient device RAM), all AI operations fall back automatically to the lightweight rule-based heuristics in `Summarizer`.

## Legacy TFLite assets

The files `note_classifier.tflite`, `tokenizer_vocabulary_v2.txt`, `category_mapping.json`, and `deployment_metadata.json` are retained in `app/src/main/assets/` as legacy artifacts from the previous TFLite classification path. They are no longer used at runtime.

