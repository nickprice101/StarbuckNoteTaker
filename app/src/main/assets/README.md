# Model assets

This directory ships the summarisation assets that are embedded in the APK. The
`build_tensor.ipynb` notebook now stages the generated binaries here
automatically after conversion, copying the following files into this folder:

- `encoder_int8_dynamic.tflite`
- `decoder_step_int8_dynamic.tflite`
- `tokenizer.json`

The app copies these assets into `context.filesDir/models` before loading them,
and the binaries remain untracked by git for size reasons (the files are still
listed in `.gitignore`).
