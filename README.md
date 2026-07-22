# Starbuck Note Taker

Web research for the chat assistant is crawled, extracted, ranked, and cached on the Android device.
See [on-device web research](docs/ON_DEVICE_WEB_RESEARCH.md).

Starbuck Note Taker is an Android note-taking app built with Kotlin + Jetpack Compose. It is designed to keep core note functionality available offline while storing note data locally in encrypted form.

## What the current app does

- Create and edit notes with:
  - plain text and rich text formatting
  - checklist notes
  - optional event metadata (start/end, timezone, location)
- Attach images and files to notes.
- Generate note summaries on-device with a fast local path: `note_classifier.tflite` provides an optional category signal and `FastNoteSummarizer` extracts specific items/actions/details from the note contents.
  - **Summarise** - concise note preview without loading the 2 GB Llama model
  - **Rewrite** - rewrites a note in a clean, professional style via Llama 3.2 3B when available
  - **Question** - answers a free-form question using optional note context via Llama 3.2 3B when available
- LLM-backed rewrite/question features require >= 4 GB total device RAM (`DeviceCapabilityChecker`); summaries remain available through the local fast path.
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
- discovering and downloading public pages for assistant web research,
- fetching new link-preview metadata,
- speech recognition depending on the device’s speech engine configuration,
- downloading Llama 3.2 3B model weights (~2 GB) on first use (stored locally under `filesDir/models/` afterward).

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

## AI / ML setup

### MLC native artifacts (required before building)

The app builds ABI-specific Llama model `.so` files from MLC system-library tar archives. Arm64 phones use the published MLC TVM runtime; x86_64 emulators require a locally built x86_64 TVM runtime because the public MLC Android APK only ships `arm64-v8a`.

```bash
# Arm64 phone artifacts
bash scripts/compile_model_tar.sh
bash scripts/fetch_mlc_native.sh

# x86_64 emulator artifacts
TARGET_ABI=x86_64 bash scripts/compile_model_tar.sh
TARGET_ABI=x86_64 bash scripts/build_mlc_tvm_runtime.sh
```

The generated model `.so` files are written under `app/src/main/jniLibs/<abi>/` by Gradle task `buildModelLibSo`. Model **weights** (~2 GB) are still downloaded automatically at runtime from HuggingFace on first use.

### Fast TFLite summary assets

The files `note_classifier.tflite`, `tokenizer_vocabulary_v2.txt`, `category_mapping.json`, and `deployment_metadata.json` in `app/src/main/assets/` support the fast summary classifier path. To regenerate them:

```bash
python3 app/src/main/assets/scripts/complete_pipeline.py
```
