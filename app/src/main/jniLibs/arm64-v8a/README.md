# `jniLibs/arm64-v8a/` — native libraries

This directory is the standard Android location for pre-built native shared
libraries (`.so` files).  The Gradle build packages everything here into the APK
automatically.

## TVM runtime — `libtvm4j_runtime_packed.so`

This is the packed TVM Android runtime that backs the MLC LLM inference engine
(`MLCEngine` / `ChatModule`).  It provides all JNI functions declared in
`mlc4j/src/main/java/ai/mlc/mlcllm/ChatModule.kt`.

**This file is NOT committed to the repository** (it is listed in `.gitignore`).

Run the fetch script once before building:

```bash
bash scripts/fetch_mlc_native.sh
```

The script downloads `mlc-chat.apk` from the MLC LLM binary release
(`Android-09262024`) and extracts `libtvm4j_runtime_packed.so` from it.

The CI workflow (`deploy.yml`) runs this step automatically.

## System-lib model archive — `.tar` flow

The project uses the **modern system-lib `.tar` flow** rather than a compiled
`.so` for the model library.  The model is distributed as:

```
app/src/main/assets/Llama-3.2-3B-Instruct-q4f16_0-MLC-android.tar
```

The archive contains the compiled TVM object files (`lib0.o`,
`llama_q4f16_0_devc.o`).  On the first inference attempt
`LlamaModelManager.extractModelLibIfNeeded()` extracts the archive to
`filesDir/lib/Llama-3.2-3B-Instruct-q4f16_0-MLC-android/` and the resulting
directory path is passed to `MLCEngine.reload()` as `modelLib`.

To rebuild the real `.tar` from the Llama 3.2 3B weights:

```bash
bash scripts/compile_model_tar.sh
```

## Model weights (~2 GB)

The model weights are **not bundled** in the APK.  They are downloaded at
runtime via the Settings screen (`LlamaModelManager.downloadModel()`).

## Other `.so` files

`libdjl_tokenizer.so` and `libc++_shared.so` are generated automatically by
the Gradle build tasks (`preparePenguinNativeLibs`, `prepareLibcxxShared`).
You do not need to place them here manually.

