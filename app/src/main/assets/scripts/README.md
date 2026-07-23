# Model training scripts (legacy TFLite category classifier)

> **Note:** Completed chatbot, reformatting, and main-page summary output runs
> through the on-device LiteRT-LM Qwen3 0.6B model. These scripts retain the
> category-classifier training and validation pipeline for compatibility and
> offline quality analysis.

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

The classifier is not a prose generator. Qwen's structured summary prompt stays
aligned with the category-aware enhanced-summary validation shape in
`complete_pipeline.py`.

