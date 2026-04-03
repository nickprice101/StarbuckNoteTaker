Before performing any edits in this repository:

1. Ensure the repository matches `origin/main`.
2. Run:
   ```bash
   git fetch origin
   git reset --hard origin/main
   git clean -fd
   ```

Do not proceed with modifications until the repo is synced.

This application is a full-featured note-taking app with checklist and reminder functionality. It is intended to work offline for core note workflows and supports encryption for user-selected notes and archived note collections.

The app uses an on-device TensorFlow Lite model (`note_classifier.tflite`) in the assets directory to classify/summarize notes. The model is generated offline and bundled with the APK so summarization works without cloud inference.

Model-building code lives in `app/src/main/assets/scripts/complete_pipeline.py`, and training data lives in `app/src/main/assets/scripts/training_data_large.py`.

Category mapping information is stored in `category_mapping.json` (next to the `.tflite` file). `DEPLOYMENT_README.md` and `DEPLOYMENT_INSTRUCTIONS.txt` provide Android deployment details.

`complete_pipeline.py` includes validation output for an "Enhanced summary" format; runtime summarization should remain aligned to that structure.

During Codex development, run Gradle with `--console=plain` to avoid session issues from large default console output.
