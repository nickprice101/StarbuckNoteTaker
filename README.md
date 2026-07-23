# Starbuck Note Taker

Starbuck Note Taker is an offline-first Android note-taking app built with Kotlin and Jetpack
Compose. Core notes, checklists, reminders, attachments, and protected-note workflows remain local
to the device.

## Features

- Create rich-text, checklist, and event notes with attachments.
- Protect selected notes and archived collections with PIN-based encryption and optional biometric
  unlock.
- Schedule reminders and full-screen alarm flows.
- Accept shared text and files through Android share intents.
- Transcribe audio through Android `SpeechRecognizer`.
- Generate summaries, rewrite notes, and answer questions with one on-device Qwen model.
- Perform assistant web research on-device when a question needs current public information. See
  [on-device web research](docs/ON_DEVICE_WEB_RESEARCH.md).

## AI architecture

Qwen3 0.6B is the only semantic and generative model used by the app. The model runs through
Google LiteRT-LM:

- `SUMMARISE` creates grounded, category-aware note previews.
- `REWRITE` corrects and restructures a note without changing protected facts.
- `QUESTION` answers with optional note context, related-note retrieval, and bounded public web
  evidence.

The canonical system prompts live in
[`config/AI_AGENT_PROMPTS.txt`](config/AI_AGENT_PROMPTS.txt). Its `[AI_SUMMARISER]`,
`[AI_CHATBOT]`, and `[AI_REFORMATTING]` sections are copied into the APK during `preBuild` and
loaded by `AiAgentPrompts`.

The pinned mixed-int4 model, `qwen3_0_6b_mixed_int4.litertlm`, is downloaded from
`litert-community/Qwen3-0.6B` on first use and verified for expected size and SHA-256 checksum. The
download is approximately 475 MB and is stored under the app's private `filesDir/models/`
directory. ARM64 devices try the GPU backend before CPU; x86_64 emulator builds use CPU. Devices
need at least 4 GB of total RAM to load the model.

There is no TensorFlow Lite note classifier and no MLC/TVM compiler step in the application build.
The Android dependency graph uses `com.google.ai.edge.litertlm:litertlm-android`.

When Qwen is unavailable, summary surfaces may show a bounded plain-text preview, rewriting returns
the original text, and questions report that the model is unavailable. These results are fallback
UI behavior, not output from a second AI model.

## Privacy and network behavior

Notes and attachments are stored locally. Notes are saved in encrypted local storage (`notes.enc`)
using AES/GCM with a key derived from the user PIN.

Network access may be used for:

- the one-time Qwen model download;
- public assistant web research;
- link-preview metadata;
- speech recognition, depending on the device speech service.

Private note content remains on-device. The app performs public page discovery and extraction, then
passes bounded evidence to Qwen. Related-note retrieval excludes locked notes unless the user has
unlocked them for the current process.

## Project structure

```text
app/src/main/java/...       Android application and Qwen/LiteRT-LM integration
app/src/main/assets/...     APK asset documentation
config/AI_AGENT_PROMPTS.txt Canonical Qwen system prompts
docs/...                    Architecture and feature documentation
```

## Build and test

JDK 17, Android SDK 34, build tools 34.0.0, and NDK 26.1.10909125 are required.

```bash
./gradlew test --no-daemon --console=plain
./gradlew assembleDebug --no-daemon --console=plain
```

The manual `deploy.yml` workflow runs the same standard Gradle APK build. It does not install
Python AI packages or compile model-native artifacts.
