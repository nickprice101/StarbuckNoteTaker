# Note Classifier - Deployment Package

## Performance
- Validation Accuracy: 95.92%
- Test Accuracy: 42.9%
- Model Size: 2.84 MB

## Files
1. note_classifier.tflite - TFLite model
2. category_mapping.json - Category names
3. deployment_metadata.json - Training results
4. note_classifier_final.keras - Keras model

## Android Integration

```kotlin
val interpreter = Interpreter(loadModelFile("note_classifier.tflite"))
val input = arrayOf(noteText)
val output = Array(1) { FloatArray(14) }
interpreter.run(input, output)
val categoryIndex = output[0].indices.maxByOrNull { output[0][it] } ?: 0
val category = categories[categoryIndex]
```

Generated: 2025-10-07 09:28:24
