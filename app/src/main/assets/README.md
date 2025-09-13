# Model assets

This directory holds optional TensorFlow Lite and tokenizer files for on-device
summarisation.

The following files are expected at runtime:

- `encoder_int8_dynamic.tflite`
- `decoder_step_int8_dynamic.tflite`
- `spiece.model`

Download them from the project's release page and place them in this folder:
https://github.com/nickprice101/StarbuckNoteTaker/releases/tag/v1.0.0

These binaries are not tracked in git; they must be added manually after
cloning the repository.
