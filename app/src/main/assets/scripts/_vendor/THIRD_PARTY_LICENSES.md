# Third-Party Licenses

The vendored modules under this `_vendor` directory are checked in so the model-verification tooling can run without extra external package installs.

## FlatBuffers (Python runtime)

- **Source:** https://github.com/google/flatbuffers
- **License:** Apache License 2.0
- **Usage in this repo:** FlatBuffer table/utility support for reading `.tflite` model structure.

## TensorFlow Lite schema bindings (Python)

- **Source:** https://github.com/tensorflow/tensorflow (TFLite schema-generated Python package)
- **License:** Apache License 2.0
- **Usage in this repo:** Parsing operator codes and metadata for `verify_note_classifier_model.py`.

## Scope note

Only the minimal subset needed by repository verification scripts is vendored.
