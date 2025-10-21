# Third-Party Licenses

The files under this `_vendor` directory include source code from the following
projects. They are bundled to allow TensorFlow Lite FlatBuffer inspection
without requiring additional pip installations.

## FlatBuffers (Python runtime)

- **Source:** https://github.com/google/flatbuffers
- **License:** Apache License 2.0
- **Notes:** Provides the FlatBuffer reader utilities used by the generated
  TensorFlow Lite schema bindings.

## TensorFlow Lite schema (Python bindings)

- **Source:** https://github.com/tensorflow/tensorflow (package `tflite` on PyPI)
- **License:** Apache License 2.0
- **Notes:** Only the minimal subset of generated bindings required for operator
  inspection (`Model`, `OperatorCode`, `Operator`, `SubGraph`, `Metadata`,
  `Buffer`, and `BuiltinOperator`) is vendored.
