# AI summary model assets

This directory contains the runtime artifacts bundled into the APK for on-device note summarization/classification.

## Files currently expected by the app

### `note_classifier.tflite`
- TensorFlow Lite classifier model loaded by the Android `Summarizer`.
- Input contract: token IDs (`int32[1,120]`).

### `tokenizer_vocabulary_v2.txt`
- Vocabulary exported from the training pipeline.
- Used to reproduce training-time tokenization behavior on Android.

### `category_mapping.json`
- Maps output indices to category labels used by enhanced-summary generation.

### `deployment_metadata.json`
- Training/deployment metadata (version and quality indicators).

### `note_classifier_smoke_test_output.txt`
- Captured sample output from verification/smoke checks for the bundled model.

## Notes

- These files are consumed entirely locally by the app at runtime.
- If any required artifact is missing or incompatible, the app falls back to non-ML summary generation.
