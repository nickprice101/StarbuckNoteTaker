# AI Deployment Notes (LiteRT-LM Qwen3 0.6B)

This document describes the production AI inference stack integrated into the Android app.

## Runtime Overview

- Qwen3 0.6B is the single on-device semantic and generative model for chatbot answers, note
  reformatting, and completed main-page summaries.
- The model runs through Google's LiteRT-LM Android runtime. ARM64 devices try the GPU backend
  first and fall back to CPU; x86/x86_64 emulator profiles use CPU.
- `LlamaEngineProvider` keeps one process-local engine warm so consecutive tasks do not repeatedly
  load model weights.
- Devices with less than 4 GB total RAM do not load the model.
- Thermal throttling reduces generation budgets when Android reports critically low thermal
  headroom.

The Android app, rather than the model runtime, performs public web discovery, HTTPS retrieval,
page extraction, caching, and citation formatting. Only a planned search query is sent to public
search providers. Private note content remains on-device and retrieved evidence is passed back to
Qwen in a bounded `web_research` block.

## Model Download

The mixed-int4 model is not bundled in the APK. `LlamaModelManager` downloads the pinned
`qwen3_0_6b_mixed_int4.litertlm` artifact from `litert-community/Qwen3-0.6B`, verifies its expected
size, and stores it under:

```text
filesDir/models/Qwen3-0.6B-LiteRT-LM/
```

The download is approximately 475 MB. Model status and download progress are exposed to the
Settings UI through a `StateFlow`.

## AI Modes

| Mode | Qwen workflow |
|------|---------------|
| `SUMMARISE` | Category-aware JSON summary, hierarchical reduction for long notes, grounding repair, and content-hash caching |
| `REWRITE` | Global document plan, token-bounded structured fragments, continuation, protected-fact repair, and a consistent Markdown result |
| `QUESTION` | Discrete current-note context, `/note` evidence control, Qwen web-research planning, extracted-page synthesis, answer verification, and rolling per-note memory |

All Qwen prompts append `/no_think` to the active user turn to avoid a hidden reasoning phase and
improve time to first token.

## Main-Page Summary Scheduling

Saving a note immediately stores a bounded plain-text placeholder so the UI remains responsive.
Completed AI summaries always come from Qwen:

1. content-addressed cache lookup;
2. serialized inference through the shared engine;
3. salience-aware selection of beginning, middle, ending, structured, and fact-bearing chunks;
4. per-chunk Qwen summaries followed by a Qwen reduction when needed;
5. deterministic high-risk fact validation and one targeted Qwen repair pass.

If Qwen is unavailable or a result remains ungrounded after repair, the app retains a plain
truncated preview and marks the summarizer as being in fallback mode. That preview is not treated
as an AI-generated summary.

## Reformatting and Online Evidence

Ordinary formatting is fully offline. Online retrieval is enabled only by an explicit instruction
such as fact checking, citation verification, applying APA/MLA/Chicago guidance, or using linked
public material. Retrieved evidence may support only that requested operation and may not silently
enrich the note.

The reformatter protects URLs, code, numbers, dates, measurements, attachment placeholders, and
other high-risk facts. It preserves rich-text citations and copies attachment metadata when a
reformatted note is created as a new note.

## Conversation Memory and Privacy

Qwen maintains a compact memory containing durable preferences, decisions, constraints, named
entities, and unresolved questions with provenance labels. Memory for ordinary notes is stored
locally by note ID. Memory for locked notes remains process-local and is not written to persistent
preferences.

Related-note retrieval excludes locked notes unless the user has already unlocked them for the
current process.

## Prompt Source

The canonical system prompts are maintained in `config/AI_AGENT_PROMPTS.txt`. Gradle copies this
file into generated APK assets during `preBuild`; `AiAgentPrompts` requires the summariser, chatbot,
and reformatting sections and `LlamaEngine` routes every user-facing AI mode through them.

The APK contains no TensorFlow Lite note classifier. The build also has no MLC/TVM compiler step or
`mlc-llm` Python dependency.
