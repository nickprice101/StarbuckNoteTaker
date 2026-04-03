# Note Classifier Deployment Package (Current)

This document describes the model artifacts currently integrated into the Android app.

## Runtime expectations

- Model is loaded from app assets and executed on-device via TensorFlow Lite.
- The app expects integer-token input and category-probability output.
- Build-time verification enforces compatibility constraints (including operator version checks).

## Artifact list

1. `note_classifier.tflite` - required runtime model.
2. `tokenizer_vocabulary_v2.txt` - required tokenizer vocabulary.
3. `category_mapping.json` - required class index mapping.
4. `deployment_metadata.json` - required deployment metadata.
5. `note_classifier_final.keras` - optional training checkpoint; not required in APK.

## Compatibility checks

The Android Gradle build invokes:

```bash
./gradlew verifyNoteClassifierModel --console=plain
```

The verifier script checks for:
- valid TFLite flatbuffer,
- FULLY_CONNECTED operator compatibility,
- category mapping sanity,
- optional inference smoke test when local TensorFlow/NumPy runtime is available.

## Regeneration flow

```bash
python3 app/src/main/assets/scripts/complete_pipeline.py
```

After retraining, copy updated artifacts into `app/src/main/assets/` and rerun verification.
