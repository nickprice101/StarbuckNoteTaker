# Note Classifier - Deployment Package

## Performance
- Validation Accuracy: 96.33%
- Test Accuracy: 85.7%
- Model Size: 11.10 MB

## Files␊
1. note_classifier.tflite - TFLite model␊
2. category_mapping.json - Category names␊
3. deployment_metadata.json - Training results␊
4. note_classifier_final.keras - Optional Keras checkpoint for reference (not bundled in app)

## Android Integration

```kotlin
val interpreter = Interpreter(loadModelFile("note_classifier.tflite"))
val input = arrayOf(noteText)
val output = Array(1) { FloatArray(14) }
interpreter.run(input, output)
val categoryIndex = output[0].indices.maxByOrNull { output[0][it] } ?: 0
val category = categories[categoryIndex]
```

Generated: 2025-10-09 09:05:42
