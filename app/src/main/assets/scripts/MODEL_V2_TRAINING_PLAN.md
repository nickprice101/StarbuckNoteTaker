# Note Classifier V2 Training Plan

## Objectives
- Improve real-world classification quality for 15 note categories.
- Produce a TFLite artifact that does not require `SELECT_TF_OPS`.
- Keep full offline functionality in Android.

## Pipeline Summary
1. Train a token-ID classifier (TextVectorization used only during training/export tooling).
2. Export `tokenizer_vocabulary_v2.txt`.
3. Convert model with Optimize.DEFAULT and representative dataset.
4. Ship `note_classifier.tflite` + vocabulary + mapping metadata.

## Architecture
- Input: integer token IDs, shape `[batch, 120]`.
- Embedding: vocab 12,000, dim 128.
- Pooling: GlobalAveragePooling1D + GlobalMaxPooling1D, concatenated.
- Dense head: 256 -> dropout(0.35) -> 128 -> dropout(0.30) -> softmax(15).

## Training Settings
- Split: stratified train/validation (75/25).
- Optimizer: Adam (1e-3).
- Loss: SparseCategoricalCrossentropy with label smoothing 0.05.
- Batch size: 32.
- Epochs: up to 120 with early stopping.
- LR schedule: ReduceLROnPlateau on validation accuracy.

## Evaluation
- Track best validation accuracy.
- Print full classification report (precision/recall/F1 per class).
- Print confusion matrix for targeted data improvements.
- Keep curated smoke tests and enhanced summary examples as regression checks.

## Export Artifacts
- `note_classifier.tflite`
- `tokenizer_vocabulary_v2.txt`
- `category_mapping.json`
- `deployment_metadata.json`
- `note_classifier_final.keras` (optional backup)

## Retraining Command
```bash
python3 app/src/main/assets/scripts/complete_pipeline.py
```

## Android Integration Contract
- App must tokenize text using `tokenizer_vocabulary_v2.txt` exactly.
- Input tensor must be `int32[1,120]`.
- Output tensor remains `float32[1,15]` class probabilities.
