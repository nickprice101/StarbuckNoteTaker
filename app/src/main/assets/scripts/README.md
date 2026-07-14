# Model training scripts (fast TFLite summary classifier)

> **Note:** Automatic note summaries use `note_classifier.tflite` as a fast
> category signal and `FastNoteSummarizer` for content-aware prose generation.
> Rewrite and question-answering still use **MLC LLM with Llama 3.2 3B Instruct**.

## Main scripts

- `complete_pipeline.py` - end-to-end training, evaluation, export, and enhanced-summary validation output.
- `training_data_large.py` - labeled training corpus.
- `detect_duplicates.py` - duplicate/high-similarity data quality checks.
- `verify_note_classifier_model.py` - post-export model compatibility and smoke-test verifier.

## Typical workflow

1. Install Python dependencies (TensorFlow 2.16.1 line and scikit-learn).
2. Run duplicate checks after editing training data.
3. Run `complete_pipeline.py` to train/export artifacts.
4. Run `verify_note_classifier_model.py` against the exported model.
5. Copy output artifacts into `app/src/main/assets/`.

## Context

The classifier should be treated as a routing hint, not a prose generator. The
runtime summary must stay aligned with the enhanced-summary validation shape in
`complete_pipeline.py`, while avoiding repetitive category-only templates.

