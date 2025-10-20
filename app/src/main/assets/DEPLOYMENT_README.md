# Note Classifier - Deployment Package

## Performance
- Validation Accuracy: 69.45%
- Test Accuracy: 42.9%
- Model Size: 11.50 MB

## Files␊
1. note_classifier.tflite - TFLite model␊
2. category_mapping.json - Category names␊
3. deployment_metadata.json - Training results␊
4. note_classifier_final.keras - Optional Keras checkpoint for reference (not bundled in app)

## Android Integration

```kotlin
val interpreter = Interpreter(loadModelFile("note_classifier.tflite"))
val input = arrayOf(noteText)
val output = Array(1) { FloatArray(15) }
interpreter.run(input, output)
val categoryIndex = output[0].indices.maxByOrNull { output[0][it] } ?: 0
val category = categories[categoryIndex]
```

Generated: 2025-10-20 08:29:07
