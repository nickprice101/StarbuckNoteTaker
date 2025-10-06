# Starbuck Note Taker

Starbuck Note Taker is an offline-first Android app for capturing rich, encrypted notes with AI assistance. It is built with Kotlin and Jetpack Compose, stores content locally, and runs its summarisation and classification models entirely on-device.

## Core features

- **Compose-first editing experience.** Create notes with rich text formatting, inline checklists, event metadata, and optional reminders while staying inside a single Compose-powered workflow.
- **Attachments and link previews.** Add images, upload arbitrary files, and generate cached link previews that stay available offline after the first fetch.
- **AI summaries and note classification.** Each note can be condensed through an on-device pipeline that classifies the note type and then runs an int8 FLAN-T5 decoder with graceful fallbacks when models are unavailable.
- **End-to-end encryption.** Notes, attachments, and exported archives are encrypted with a user PIN, with optional biometric unlock for convenience.
- **Import/export without the cloud.** Users can back up or restore their encrypted notes via `.snarchive` files that continue to require the original PIN when imported.

Link previews fetch metadata over HTTPS; all other functionality continues to operate without a network connection.

## Project structure

```
app/                  # Android application module (Compose UI, ViewModel, services)
app/src/main/assets/  # Bundled ML models (not checked into VCS)
scripts/              # Helper scripts for ML asset validation
build_tensor.py       # Training and conversion workflow for the summariser
training_data.py      # Curated training set for note categories and summaries
```

## Getting started

1. Install Android Studio Giraffe (or newer) and ensure JDK 17 is available.
2. Clone the repository and accept Android SDK licenses.
3. Use the Gradle wrapper for builds and tests:
   ```bash
   ./gradlew build    # compile the application
   ./gradlew test     # run unit tests
   ```

For a repeatable developer workstation, `setup_persist.sh` can install the Android SDK, Gradle, and verify that required native libraries and assets are present.

## On-device ML assets

The summariser service expects `note_classifier.tflite` to be present under `app/src/main/assets/`. Large binaries remain untracked; run `scripts/generate_summarizer_assets.py` to check for missing files during setup.

To regenerate the models:

1. Update or extend the labelled examples in `training_data.py`.
2. Execute `build_tensor.py` to fine-tune FLAN-T5, convert the models to TensorFlow Lite, and write the assets into the project structure.
3. Copy the resulting files into `app/src/main/assets/` before building the app.

## Contributing

Pull requests and bug reports are welcome. Please open an issue to discuss substantial changes before submitting a PR.
