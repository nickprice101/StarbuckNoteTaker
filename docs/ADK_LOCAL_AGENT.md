# Kotlin ADK on-device note agent

## Architecture

Google Agent Development Kit for Kotlin owns the app's `LlmAgent`, instructions, runner, event
stream, and in-memory conversation session. `QwenAdkModel` translates provider-neutral ADK
requests into messages for the shared on-device Qwen3 0.6B engine.

The engine uses Google LiteRT-LM 0.14.0. The same pinned mixed-int4 `.litertlm` model runs on ARM64
phones and x86_64 emulators, so the app does not need ABI-specific model compilation. ARM64 tries
GPU before CPU; x86_64 uses CPU. `LlamaEngineProvider` keeps one process-local engine warm and
serializes inference.

The project uses `google-adk-kotlin-core-android:0.4.0`. Hosted Gemini and ML Kit provider runtimes
are excluded because the app supplies `QwenAdkModel`; note inference does not require a hosted-model
key or a second on-device AI runtime.

The canonical system prompts are the `[AI_SUMMARISER]`, `[AI_CHATBOT]`, and `[AI_REFORMATTING]`
sections of `config/AI_AGENT_PROMPTS.txt`. Gradle copies the file into the APK and
`AiAgentPrompts` validates it at runtime.

## Privacy

Every chat is scoped to exactly one current note; content is never retrieved from another note.
The current note and its per-note conversation memory remain on-device. When a question requires
current public information, Qwen may use a non-sensitive place, topic, or named entity from the
current note to make the planned search query self-contained. Android downloads, extracts, ranks,
and caches the pages locally, then supplies a bounded evidence block to Qwen. Raw note text and
private details are not included in search requests.

## Quality controls

- Reformatting uses a global plan, bounded fragments, and conservative sampling.
- Names, dates, URLs, code, amounts, measurements, attachments, and other protected facts are
  validated and repaired through Qwen when necessary.
- Completed summaries use content-hash caching, hierarchical reduction for long notes, grounding
  validation, and one Qwen repair pass.
- Chat answers that use web evidence receive a Qwen verification pass and a deterministic check
  that rejects citation-only replies without extracted findings.
- An unavailable model preserves original note content and produces only documented plain fallback
  UI.

## Runtime flows

### Reformat

1. The user chooses to update the current note or create a new note.
2. The destination is reserved with the original text so a failed model call cannot destroy user
   content.
3. `LlamaForegroundService` runs `NoteAiAgent.reformat` as an ADK turn.
4. `QwenAdkModel` streams the request through the shared LiteRT-LM Qwen engine.
5. Valid Markdown is parsed into the rich-text document and applied to the destination.

### Chat

1. The chat action opens a full-screen conversation scoped to the current note.
2. An ADK in-memory session retains the dialog's user and model turns.
3. Without `/note`, the current note supplies context only; `/note` explicitly allows extraction,
   summarisation, or other requested work using the note as evidence.
4. Current, explicit-lookup, recommendation, and unfamiliar questions use on-device public-page
   extraction.
5. Qwen summarises the extracted findings, verifies the grounded answer, and renders Markdown links
   as citation pills.
6. The user may append an answer to the note while preserving citation metadata.
7. Closing the dialog discards the in-memory messages.

## References

- [ADK Kotlin quickstart](https://adk.dev/get-started/kotlin/)
- [ADK Kotlin API reference](https://adk.dev/api-reference/kotlin/)
- [Build ADK agents for Android](https://developer.android.com/ai/adk)
- [ADK LiteRT-LM model adapter](https://adk.dev/agents/models/litert-lm/)
