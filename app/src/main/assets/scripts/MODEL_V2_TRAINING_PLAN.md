# Note Classifier V2 Training Plan

_Last updated: April 3, 2026_

## Objectives
- Improve real-world classification quality for all 15 note categories.
- Preserve a fully offline Android inference path (no cloud dependencies).
- Produce a TFLite artifact that does not require `SELECT_TF_OPS`.
- Keep summary output aligned with the **"Enhanced summary"** format validated at the end of `complete_pipeline.py`.

## Scope and Non-Goals
### In Scope
- Data quality hardening (deduplication, class-balance review, label audits).
- Model quality improvements that still fit mobile constraints.
- Reproducible export of model + tokenizer + metadata artifacts.
- Regression checks for category predictions and enhanced summary structure.

### Out of Scope
- Any online model serving.
- Runtime dependency on remote tokenization or remote category metadata.
- Changes that break backward compatibility for existing local notes.

## Pipeline Summary
1. Validate and deduplicate training data (`training_data_large.py`, `detect_duplicates.py`).
2. Train token-ID classifier (TextVectorization only in training/export workflow).
3. Evaluate with per-class metrics + confusion matrix and targeted error notes.
4. Export artifacts and run smoke tests against representative notes.
5. Verify enhanced summary structure against script-level validation examples.
6. Bundle artifacts into app assets and run offline Android inference checks.

## Baseline Architecture (V2)
- Input: integer token IDs, shape `[batch, 120]`.
- Embedding: vocab size `12,000`, embedding dim `128`.
- Pooling: `GlobalAveragePooling1D` + `GlobalMaxPooling1D` (concatenated).
- Dense head: `256 -> dropout(0.35) -> 128 -> dropout(0.30) -> softmax(15)`.

## Training Settings
- Split: stratified train/validation (`75/25`).
- Optimizer: Adam (`1e-3`).
- Loss: SparseCategoricalCrossentropy with label smoothing (`0.05`).
- Batch size: `32`.
- Epochs: up to `120` with early stopping.
- LR schedule: `ReduceLROnPlateau` on validation accuracy.
- Seed policy: fixed training seed for reproducibility during candidate comparison.

## Evaluation and Acceptance Criteria
### Metrics to Track
- Overall validation accuracy.
- Macro F1 and weighted F1.
- Per-class precision/recall/F1.
- Confusion matrix for systematic misclassification pairs.

### Minimum Acceptance Gates
- No category with F1 below agreed floor from previous blessed model.
- Macro F1 improves versus the currently bundled `note_classifier.tflite`.
- Smoke test set has no severe regressions in top-1 category behavior.
- Enhanced summary examples remain structurally compliant.

## Artifact Contract
Required artifacts after a successful training run:
- `note_classifier.tflite`
- `tokenizer_vocabulary_v2.txt`
- `category_mapping.json`
- `deployment_metadata.json`

Optional backup artifact:
- `note_classifier_final.keras`

## Android Integration Contract
- Tokenization must use `tokenizer_vocabulary_v2.txt` exactly.
- Input tensor shape/type must remain `int32[1,120]`.
- Output tensor shape/type must remain `float32[1,15]` (class probabilities).
- App behavior must continue to work fully offline.

## Execution Steps
1. Run the full training/export pipeline:
   ```bash
   python3 app/src/main/assets/scripts/complete_pipeline.py
   ```
2. Run model verification:
   ```bash
   python3 app/src/main/assets/scripts/verify_note_classifier_model.py
   ```
3. If Android validation is required, use Gradle with plain console output:
   ```bash
   ./gradlew --console=plain <task>
   ```

## Rollout Checklist
- [ ] New artifacts generated and checked into assets.
- [ ] Category mapping compatibility validated.
- [ ] Smoke test output reviewed for regressions.
- [ ] Enhanced summary output reviewed against validation examples.
- [ ] Offline behavior verified on-device/emulator.
