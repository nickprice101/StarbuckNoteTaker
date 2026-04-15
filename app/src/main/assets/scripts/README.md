# Model training scripts (legacy TFLite workflow)

> **Note:** The primary on-device AI has been replaced by **MLC LLM with Llama 3.2 3B Instruct**.
> The scripts in this folder produce legacy TFLite classification artifacts that are no longer
> loaded at runtime. They are retained for reference and historical reproducibility.

## Main scripts

- `complete_pipeline.py` - end-to-end training, evaluation, export, and enhanced-summary validation output.
- `training_data_large.py` - labeled training corpus.
- `detect_duplicates.py` - duplicate/high-similarity data quality checks.
- `verify_note_classifier_model.py` - post-export model compatibility and smoke-test verifier.

## Typical workflow (legacy TFLite reproduction)

1. Install Python dependencies (TensorFlow 2.16.1 line and scikit-learn).
2. Run duplicate checks after editing training data.
3. Run `complete_pipeline.py` to train/export artifacts.
4. Run `verify_note_classifier_model.py` against the exported model.
5. Copy output artifacts into `app/src/main/assets/` for reference (not required for app builds).

## Context

The Android app's `LiteInterpreter` interface is now a compatibility stub only. All AI inference goes through `LlamaEngine` (MLC LLM) at runtime, falling back to rule-based heuristics in `Summarizer` when the Llama model is unavailable.

