Before performing any edits in this repository:

1. Ensure the repository matches `origin/main`.
2. Run:
   ```bash
   git fetch origin
   git reset --hard origin/main
   git clean -fd
   ```

Do not proceed with modifications until the repo is synced.

This application is a full-featured, offline-first Android note-taking app with rich text,
checklists, reminders, attachments, encrypted protected notes, and encrypted archived
collections.

Qwen3 0.6B through Google LiteRT-LM is the app's only semantic and generative AI runtime. Summary,
rewrite, chatbot, research-planning, verification, and repair flows must run through the shared
Qwen engine. A bounded plain-text preview may be used while Qwen is unavailable, but it is fallback
UI text and must not be represented as an AI-generated summary.

The canonical Qwen system prompts are in `config/AI_AGENT_PROMPTS.txt`:

- `[AI_SUMMARISER]`
- `[AI_CHATBOT]`
- `[AI_REFORMATTING]`

Gradle copies that file into generated APK assets during `preBuild`, and `AiAgentPrompts.kt` loads
the required sections. Keep production system-prompt changes in that file instead of embedding
replacement base prompts in Kotlin.

`LlamaModelManager.kt` owns the pinned `litert-community/Qwen3-0.6B` model identity, download,
checksum validation, and device support. `LlamaEngine.kt` owns LiteRT-LM initialization, generation,
and prompt-mode routing. `QwenAdkModel.kt` adapts the shared Qwen engine to Google ADK workflows.

Do not add or restore a TensorFlow Lite note classifier, classifier vocabulary/category assets, an
MLC/TVM model compiler, or `mlc-llm` installation steps. The standard Android build must remain a
normal Gradle build with no Python model-compilation dependency.

`app/src/main/assets/DEPLOYMENT_README.md` and
`app/src/main/assets/DEPLOYMENT_INSTRUCTIONS.txt` describe the current Qwen deployment.

During Codex development, run Gradle with `--console=plain` to avoid session issues from large
default console output.
