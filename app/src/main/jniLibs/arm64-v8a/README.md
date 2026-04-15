# Place the MLC compiled `.so` here

The Llama 3.2 3B Instruct model library (`libLlama-3.2-3B-Instruct-q4f16_0-MLC.so`)
must be placed in this directory before building the APK.

> **Note:** There is no prebuilt download available for this file. The
> `mlc-ai/binary-mlc-llm-libs` repo does not contain a Llama-3.2-3B Android `.so`,
> and the `mlc-chat.apk` from that release only contains the generic TVM runtime
> (`libtvm4j_runtime_packed.so`), not a model-specific library. You must compile it
> yourself using the MLC-LLM toolchain.

## Steps

### Prerequisites

- Python 3.10+
- Android NDK r27+ (install via Android Studio → SDK Manager → NDK)
- Set environment variables:
  ```bash
  export ANDROID_NDK=~/Android/Sdk/ndk/<version>
  export TVM_NDK_CC=$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang
  ```

### 1. Install MLC-LLM

```bash
pip install mlc-llm
```

See https://llm.mlc.ai/docs/install/index.html for full installation instructions.

### 2. Download the quantised weights from HuggingFace

```bash
git clone https://huggingface.co/mlc-ai/Llama-3.2-3B-Instruct-q4f16_0-MLC
```

### 3. Compile the model library for Android

```bash
mlc_llm compile \
  ./Llama-3.2-3B-Instruct-q4f16_0-MLC \
  --target android \
  --device android:arm64-v8a \
  -o Llama-3.2-3B-Instruct-q4f16_0-MLC-android.so
```

### 4. Place the output file here

```bash
cp Llama-3.2-3B-Instruct-q4f16_0-MLC-android.so \
   app/src/main/jniLibs/arm64-v8a/libLlama-3.2-3B-Instruct-q4f16_0-MLC.so
```

Note the `lib` prefix — Android requires JNI `.so` files to be named with a `lib` prefix.

### 5. Rebuild the project

Gradle packages the `.so` automatically into the APK.

## Notes

- The `.so` is the **compiled model library** (a few MB). It is separate from the
  model **weights** (~2 GB), which are downloaded at runtime by `LlamaModelManager`.
- Only `arm64-v8a` is required for modern Android devices.
- This file is intentionally excluded from the repository (`.gitignore`) because
  binary files should not be committed to source control. Each developer or CI
  environment must compile and place it locally before building.
