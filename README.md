# Starbuck Note Taker

Starbuck Note Taker is an Android note-taking app built with Kotlin + Jetpack Compose. It is designed to keep core note functionality available offline while storing note data locally in encrypted form.

## What the current app does

- Create and edit notes with:
  - plain text and rich text formatting
  - checklist notes
  - optional event metadata (start/end, timezone, location)
- Attach images and files to notes.
- Generate note summaries on-device using **MLC LLM** with **Llama 3.2 3B Instruct** (q4f16_0 quantisation) via `LlamaEngine`; falls back to lightweight rule-based heuristics when the model is unavailable or the device has insufficient RAM.
  - **Summarise** — concise 1–3 line note preview
  - **Rewrite** — rewrites a note in a clean, professional style
  - **Question** — answers a free-form question using optional note context
- AI features require ≥ 4 GB total device RAM (`DeviceCapabilityChecker`); the rule-based fallback is used automatically on devices below this threshold.
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

### MLC model library (required before building)

The compiled Llama 3.2 3B model library must be placed in `app/src/main/jniLibs/arm64-v8a/` before building:

```bash
# 1. Download the MLC prebuilt APK
#    https://github.com/mlc-ai/binary-mlc-llm-libs/releases/tag/Android-09262024
# 2. Extract the .so
unzip mlc-chat.apk "lib/arm64-v8a/libLlama-3.2-3B-Instruct-q4f16_0-MLC.so" -d extracted/
# 3. Copy into the project
cp extracted/lib/arm64-v8a/libLlama-3.2-3B-Instruct-q4f16_0-MLC.so \
   app/src/main/jniLibs/arm64-v8a/
```

The `.so` is the compiled model library (~MB range). Model **weights** (~2 GB) are downloaded automatically at runtime from HuggingFace on first use.

### Legacy TFLite assets (optional)

The files `note_classifier.tflite`, `tokenizer_vocabulary_v2.txt`, `category_mapping.json`, and `deployment_metadata.json` in `app/src/main/assets/` are legacy artifacts from the previous TFLite classification path. They are no longer used at runtime (the rule-based fallback fills this role now) but are retained for reference. To regenerate them:

```bash
python3 app/src/main/assets/scripts/complete_pipeline.py
```
