# AI / ML assets

This directory contains assets bundled into the APK and related deployment documentation.

## Active runtime: MLC LLM (Llama 3.2 3B Instruct)

The primary on-device AI now uses **MLC LLM** with Llama 3.2 3B Instruct. The compiled model library (`.so`) must be placed in `app/src/main/jniLibs/arm64-v8a/` before building (see `DEPLOYMENT_README.md`). Model weights (~2 GB) are downloaded at runtime by `LlamaModelManager`.

## Legacy TFLite assets (no longer used at runtime)

The following files from the previous TFLite classification pipeline are retained for reference but are **not loaded at runtime**:

### `note_classifier.tflite`
- Former TensorFlow Lite classifier model.
- Input contract: token IDs (`int32[1,120]`).

### `tokenizer_vocabulary_v2.txt`
- Vocabulary exported from the TFLite training pipeline.

### `category_mapping.json`
- Maps output indices to category labels.

### `deployment_metadata.json`
- Training/deployment metadata (version and quality indicators).

### `note_classifier_smoke_test_output.txt`
- Captured sample output from legacy TFLite verification checks.

## Notes

- All AI inference is performed entirely on-device.
- If the Llama model library or weights are unavailable, the app falls back to rule-based summary heuristics in `Summarizer`.

