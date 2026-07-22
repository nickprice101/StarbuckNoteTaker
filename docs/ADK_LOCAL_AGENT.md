# Kotlin ADK on-device note agent

## Decision

The app uses Google Agent Development Kit for Kotlin to define and run the agent, while a
custom ADK `Model` implementation sends each ADK model request to the existing MLC runtime.
The production profile remains Llama 3.2 3B Instruct q4f16 on ARM64; the x86_64 emulator uses
the existing 1B q4f32 compatibility profile.

ADK owns the `LlmAgent`, instructions, runner, event stream, and in-memory conversation session.
`MlcAdkModel` only translates ADK content into the local MLC chat protocol, so note inference does
not require a hosted-model key. The note itself remains on-device. When a question needs research,
only the question is sent to public search providers for URL discovery. The phone downloads,
extracts, ranks, and caches the public pages locally; note context is not included in search requests.

The project is pinned to `google-adk-kotlin-core-android:0.4.0`. That artifact is compatible with
this AGP 8.6/R8 and Kotlin 2.1.20 build. The provider runtimes for hosted Gemini and ML Kit Gemini
Nano are excluded because this app supplies its own `Model`; omitting them also avoids packaging
unused cloud authentication and a second on-device inference runtime.

## Model fit assessment

| Candidate | Strengths | Cost or constraint | Decision |
| --- | --- | --- | --- |
| Existing MLC Llama 3.2 3B q4f16 | Already integrated and downloadable; general instruction/chat model; works offline on supported ARM64 devices; one shared warm engine | About 2 GB storage; app enforces at least 4 GB device RAM; slower and less reliable than a larger hosted model | Selected for production |
| MLC Llama 3.2 1B q4f32 | Small, portable CPU profile suitable for x86_64 emulator and CI smoke tests | Material quality reduction for grammar, structure, and longer conversations | Emulator compatibility only |
| ADK LiteRT-LM with Gemma | Official local ADK adapter; CPU and GPU backends | Adds a new runtime, new model packaging/download path, and another large local model to an app that already ships MLC integration | Revisit when replacing MLC, not in parallel |
| Gemini Nano through ML Kit | Official ADK Android on-device provider and no app-managed model weights | Availability depends on supported devices and the system model; it would narrow the current supported-device path | Not selected |

Llama 3.2 3B is fit for this bounded editing workload because it is an instruction-tuned 3.21B
text model and the tasks require rewriting and short contextual dialogue, not factual research or
autonomous tool use. The upstream model supports much more context, but the app intentionally uses
small mobile budgets: 6,000 prompt characters/768 output tokens on a physical device and 2,200/320
on the emulator. Reformat fragments are sized from the active prompt budget so they are never
silently truncated.

Quality controls for reformatting are deliberately conservative:

- temperature is 0.1 and only one agent step is allowed;
- the instruction requires fact preservation and spelling, grammar, punctuation, headings, lists,
  and indentation only where appropriate;
- output must be non-empty and retain at least 45% of the source word count for non-trivial notes;
- long notes are split on paragraphs and reassembled in order;
- an inference error leaves the source text in place and is surfaced in the note status;
- attachment references are retained when the current note is updated.

The model is not a proofreader with deterministic guarantees. The validation blocks obviously
empty or severely shortened output, but users should still review names, dates, and decisions. A
larger evaluation set of representative user notes is the appropriate gate before changing model
or quantization.

## Runtime flows

### Reformat

1. The user taps the auto-fix icon and chooses **Update this note** or **Create new note**.
2. The selected destination is reserved with the original text, so a failed model call does not
   destroy the user's content.
3. `LlamaForegroundService` runs `NoteAiAgent.reformat` as an ADK `LlmAgent` turn.
4. `MlcAdkModel` streams the request through the shared local MLC engine.
5. Valid Markdown is parsed back into the rich-text document and applied to the destination.

### Chat

1. The Chat attachment action opens a full-screen chat with the current draft as context.
2. A dedicated ADK `InMemorySessionService` retains user and model turns for that dialog.
3. Current, explicit lookup, and unfamiliar questions use on-device web extraction; stable questions
   fall back to the on-device model when internet is unavailable.
4. Responses stream into standard user/agent bubbles, with Markdown links rendered as citation pills.
5. **Add to note** parses an agent response as Markdown and appends it to the draft while preserving
   citation metadata.
6. Closing the dialog discards that in-memory chat; chat messages are not saved as note data.

## Relevant official references

- [ADK Kotlin quickstart](https://adk.dev/get-started/kotlin/)
- [ADK Kotlin API reference](https://adk.dev/api-reference/kotlin/)
- [Build ADK agents for Android](https://developer.android.com/ai/adk)
- [ADK LiteRT-LM model adapter](https://adk.dev/agents/models/litert-lm/)
- [Meta Llama 3.2 3B Instruct model card](https://huggingface.co/meta-llama/Llama-3.2-3B-Instruct)
