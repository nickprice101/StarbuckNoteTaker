# Model training scripts (offline workflow)

This folder contains the offline pipeline used to train/export `note_classifier.tflite` and companion assets.

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

## Why this matters

The Android app relies on the enhanced-summary structure validated at the end of `complete_pipeline.py`. Keep runtime summary formatting aligned with that output contract.
