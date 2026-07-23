# AI assets

This directory contains documentation for the app's on-device Qwen integration. The model itself
is not bundled in the APK.

## Runtime model

`LlamaModelManager` downloads the pinned mixed-int4 Qwen3 0.6B LiteRT-LM bundle on first use,
verifies its size and SHA-256 checksum, and stores it in the app's private model directory.

Qwen handles completed note summaries, rewriting, chatbot answers, research planning, answer
verification, and repair. If it is unavailable, the app may display bounded plain-text fallback
content; no secondary classifier or generative model is used.

## Prompts

The editable source is `config/AI_AGENT_PROMPTS.txt` at the repository root. Gradle copies it into
generated APK assets, where `AiAgentPrompts` loads the summariser, chatbot, and reformatting
sections.

See `DEPLOYMENT_README.md` for the runtime architecture and
`DEPLOYMENT_INSTRUCTIONS.txt` for build and deployment checks.
