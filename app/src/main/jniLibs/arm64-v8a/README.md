# `jniLibs/arm64-v8a/` - native libraries

This directory is the standard Android location for prebuilt native shared
libraries (`.so` files). The Gradle build packages everything here into the APK.

## TVM Runtime - `libtvm4j_runtime_packed.so`

The packed TVM Android runtime is stored in:

```text
mlc4j/src/main/jniLibs/arm64-v8a/libtvm4j_runtime_packed.so
```

That runtime is not committed to the repository. Build it from MLC source before
assembling the app:

```bash
bash scripts/install_mlc_llm.sh
bash scripts/fetch_mlc_native.sh
```

The runtime must expose TVM FFI system-library support (`ffi.SystemLib` and
`TVMFFIEnvModRegisterSystemLibSymbol`). The old `Android-09262024` binary APK
runtime is not compatible with the checked-in Llama archive.

## Model Library

The project links the model archive:

```text
app/src/main/assets/Llama-3.2-3B-Instruct-q4f16_0-MLC-android.tar
```

into:

```text
app/src/main/jniLibs/arm64-v8a/libLlama-3.2-3B-Instruct-q4f16_0-MLC.so
```

Gradle task `buildModelLibSo` performs this link step automatically once the
compatible TVM runtime is present.

## Model Weights

The model weights are not bundled in the APK. They are downloaded at runtime via
the Settings screen (`LlamaModelManager.downloadModel()`).

## Other `.so` Files

`libdjl_tokenizer.so` and `libc++_shared.so` are generated automatically by the
Gradle build tasks (`preparePenguinNativeLibs`, `prepareLibcxxShared`).
