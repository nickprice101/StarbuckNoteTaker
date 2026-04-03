# Note Classifier V2 Training + Maintenance Plan

_Last updated: April 3, 2026_

## Current status

- V2 classifier artifacts are bundled in `app/src/main/assets/`.
- Android build verifies model compatibility through `verifyNoteClassifierModel`.
- Runtime summarization combines model predictions with heuristic/fallback behavior.

## Ongoing objectives

- Maintain or improve macro/per-class quality without breaking mobile inference compatibility.
- Preserve fully local inference path (no server inference dependency).
- Keep enhanced-summary output shape consistent with `complete_pipeline.py` validation output.

## Required output contract

Every blessed training run should produce:
- `note_classifier.tflite`
- `tokenizer_vocabulary_v2.txt`
- `category_mapping.json`
- `deployment_metadata.json`

Optional:
- `note_classifier_final.keras`

## Maintenance workflow

1. Update/curate examples in `training_data_large.py`.
2. Run duplicate checks:
   ```bash
   python3 app/src/main/assets/scripts/detect_duplicates.py --threshold 0.85
   ```
3. Train/export artifacts:
   ```bash
   python3 app/src/main/assets/scripts/complete_pipeline.py
   ```
4. Validate exported model:
   ```bash
   python3 app/src/main/assets/scripts/verify_note_classifier_model.py --model <path-to-model>
   ```
5. Replace assets in `app/src/main/assets/` and run Android tests/build checks.

## Acceptance gates before shipping new assets

- No compatibility failures in model verifier.
- No severe regression in representative category smoke tests.
- Enhanced summary examples remain structurally compliant.
- Android app still summarizes successfully in offline mode.
