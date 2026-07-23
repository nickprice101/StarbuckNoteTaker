# AI / ML assets

This directory contains APK assets and deployment documentation for the app's on-device AI.

## Active runtime

Qwen3 0.6B through LiteRT-LM is the single semantic and generative model for:

- chatbot research planning, grounded answers, verification, and conversation memory;
- hierarchical rich-text note reformatting and repair;
- completed category-aware main-page summaries.

`LlamaModelManager` downloads the pinned mixed-int4 model (~475 MB) at runtime. Public web
resources are discovered and extracted by Android; Qwen receives bounded evidence blocks and
private note text remains on-device.

See `DEPLOYMENT_README.md` for runtime, privacy, fallback, and scheduling details.

## Legacy classifier assets

The following assets remain for compatibility, offline training/model verification, and historical
tooling. They no longer generate completed main-page AI summaries.

### `note_classifier.tflite`

- TensorFlow Lite category classifier.
- Input contract: token IDs (`int32[1,120]`).

### `tokenizer_vocabulary_v2.txt`

- Vocabulary exported from the classifier training pipeline.

### `category_mapping.json`

- Maps classifier output indices to category labels.

### `deployment_metadata.json`

- Classifier training/deployment metadata.

### `note_classifier_smoke_test_output.txt`

- Captured sample output from classifier verification checks.

## Fallback

When Qwen is unavailable, the UI may show a bounded plain-text preview. It is not presented as an
AI-generated summary.
