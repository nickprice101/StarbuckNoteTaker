This directory contains the offline training pipeline and dataset that produce
`note_classifier.tflite`.

## Regenerating the model

1. Create a Python environment that includes **TensorFlow 2.16.1**. The Android
   app bundles the 2.16.1 TensorFlow Lite runtime, so the model must be exported
   with the exact same version to keep operator versions compatible. Install the
   required packages:

   ```bash
   pip install tensorflow==2.16.1 tf_keras==2.16.0 scikit-learn
   ```

2. Run the duplicate check any time you edit the training data to ensure notes remain diverse:

   ```bash
   python detect_duplicates.py --threshold 0.85
   ```

   The script prints any pairs of notes with high cosine similarity within each category so they can be refreshed before training.

3. Run the full pipeline from this folder:

   ```bash
   python complete_pipeline.py
   ```

   The script trains the classifier, prints evaluation examples, and writes the
   `.tflite`, `.keras`, and metadata files. It also verifies that any
   `FULLY_CONNECTED` operators in the flatbuffer stay at version ≤ 11 so the
   generated model can be loaded by TensorFlow Lite 2.16.1.

4. Copy the generated `note_classifier.tflite`, `category_mapping.json`, and
   `deployment_metadata.json` into `app/src/main/assets/` for Android builds.

Intermediate training artefacts (SavedModel directories, `.keras` files, etc.)
are not committed to source control.
