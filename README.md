# Starbuck Note Taker

Starbuck Note Taker is an Android note-taking app built with Kotlin + Jetpack Compose. It is designed to keep core note functionality available offline while storing note data locally in encrypted form.

## What the current app does

- Create and edit notes with:
  - plain text and rich text formatting
  - checklist notes
  - optional event metadata (start/end, timezone, location)
- Attach images and files to notes.
- Generate note summaries on-device with TensorFlow Lite (`note_classifier.tflite`) and local fallback logic when ML is unavailable.
- Add URL link previews (metadata fetch requires internet once; cached preview data remains local afterward).
- Protect notes with PIN-based encryption, with optional biometric unlock support.
- Schedule event reminders and full-screen alarm flows.
- Accept shared content from other apps via Android share intents (`SEND`, `SEND_MULTIPLE`).
- Support in-app audio transcription using Android `SpeechRecognizer` (availability/quality depends on device speech services and permissions).

## Security and storage model

- Notes are saved in encrypted local storage (`notes.enc`) using AES/GCM with a key derived from the user PIN.
- Attachments are stored locally and referenced from note records.
- Locked notes remain hidden until unlocked with the configured PIN/biometric flow.

## Offline behavior

The app is offline-first for note creation, editing, storage, reminders, and summarization.

Network may still be used for:
- fetching new link-preview metadata,
- speech recognition depending on the device’s speech engine configuration.

## Project structure

```text
app/
  src/main/java/...                Android app source
  src/main/assets/                 Bundled ML assets + deployment docs
  src/main/assets/scripts/         Offline model training + verification scripts
scripts/                           Repository helper scripts
```

## Build and test

> During Codex development, run Gradle with `--console=plain`.

```bash
./gradlew test --console=plain
./gradlew assembleDebug --console=plain
```

The build runs `verifyNoteClassifierModel` before `preBuild` to validate the bundled model contract.

## ML assets required in `app/src/main/assets/`

- `note_classifier.tflite`
- `tokenizer_vocabulary_v2.txt`
- `category_mapping.json`
- `deployment_metadata.json`

To retrain/regenerate these artifacts, use:

```bash
python3 app/src/main/assets/scripts/complete_pipeline.py
```
