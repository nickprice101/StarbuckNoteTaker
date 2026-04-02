# Note Classifier - Deployment Package (V2)

## Performance
- Validation Accuracy: generated at training time
- Test Accuracy: generated at training time
- Model Size: generated at training time

## Files
1. `note_classifier.tflite` - TFLite model that accepts token ID input (`int32[1,120]`).
2. `tokenizer_vocabulary_v2.txt` - Required vocabulary for Android tokenization parity.
3. `category_mapping.json` - Category names and output index mapping.
4. `deployment_metadata.json` - Metrics + pipeline metadata (recommended).
5. `note_classifier_final.keras` - Optional backup checkpoint (not bundled in APK).

## Android Integration (Kotlin)

```kotlin
val interpreter = Interpreter(loadModelFile("note_classifier.tflite"))
val tokenIds: IntArray = tokenizeToIds(noteText, vocabulary, sequenceLength = 120)
val input: Array<IntArray> = arrayOf(tokenIds)
val output = Array(1) { FloatArray(15) }
interpreter.run(input, output)
val categoryIndex = output[0].indices.maxByOrNull { output[0][it] } ?: 0
val category = categories[categoryIndex]
```

## Tokenization parity requirements
- lowercase input text
- strip punctuation
- split by whitespace
- map tokens using `tokenizer_vocabulary_v2.txt`
- unknown/padding -> `0`
- truncate or right-pad to exactly 120 tokens

## Regenerate model
```bash
python3 app/src/main/assets/scripts/complete_pipeline.py
```

If running Gradle while validating Android integration, use plain console output:
```bash
./gradlew <task> --console=plain
```
