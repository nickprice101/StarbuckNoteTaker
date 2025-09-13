# Model assets

This directory is intentionally left empty. On first run the application
downloads the required TensorFlow Lite models from the project's GitHub
Releases and stores them under the app's internal `files/models` directory.

If you prefer to bundle the models manually (for offline installs), place the
following files here:

- `encoder_int8_dynamic.tflite`
- `decoder_step_int8_dynamic.tflite`
- `spiece.model`

These binaries remain untracked by git.
