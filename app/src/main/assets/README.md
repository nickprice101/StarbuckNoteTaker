# AI / ML assets

This directory contains assets bundled into the APK and related deployment documentation.

## Active runtime

Automatic note summaries use a fast local path:

- `note_classifier.tflite` provides an optional category signal.
- `tokenizer_vocabulary_v2.txt` keeps Android tokenization aligned with the exported model.
- `category_mapping.json` maps classifier outputs to note categories.
- `FastNoteSummarizer` generates the final preview from the note contents, not from category-only templates.

Interactive rewrite and question-answering use **MLC LLM** with Llama 3.2 3B Instruct. Gradle builds ABI-specific model libraries under `app/src/main/jniLibs/<abi>/` from the MLC tar assets before packaging (see `DEPLOYMENT_README.md`). Model weights (~2 GB) are downloaded at runtime by `LlamaModelManager`.

## Fast summary assets

### `note_classifier.tflite`
- TensorFlow Lite classifier model used as a fast category hint.
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
- Summaries do not require the Llama model download and should complete in a few seconds or less.
- If the TFLite classifier is unavailable, `FastNoteSummarizer` falls back to heuristic category detection and still generates content-aware summaries.

