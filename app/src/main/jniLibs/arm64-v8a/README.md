# Place the MLC prebuilt `.so` here

The Llama 3.2 3B Instruct model library (`libLlama-3.2-3B-Instruct-q4f16_0-MLC.so`)
must be placed in this directory before building the APK.

## Steps

1. Download the prebuilt APK from the MLC binary release:
   https://github.com/mlc-ai/binary-mlc-llm-libs/releases/tag/Android-09262024

2. Extract the `.so` from the APK (APKs are ZIP archives):
   ```bash
   unzip mlc-chat.apk "lib/arm64-v8a/libLlama-3.2-3B-Instruct-q4f16_0-MLC.so" -d extracted/
   ```

3. Copy the file into this directory:
   ```bash
   cp extracted/lib/arm64-v8a/libLlama-3.2-3B-Instruct-q4f16_0-MLC.so \
      app/src/main/jniLibs/arm64-v8a/
   ```

4. Rebuild the project — Gradle packages the `.so` automatically into the APK.

## Notes

- The `.so` is the **compiled model library** (a few MB). It is separate from the
  model **weights** (~2 GB), which are downloaded at runtime by `LlamaModelManager`.
- Only `arm64-v8a` is required for modern Android devices (all phones since ~2014
  that can realistically run this model are arm64).
- This file is intentionally excluded from the repository (`.gitignore`) because
  binary files should not be committed to source control. Each developer or CI
  environment must obtain and place it locally before building.
