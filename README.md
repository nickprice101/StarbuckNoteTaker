# Starbuck Note Taker

A simple Android note taking application built with Kotlin and Jetpack Compose. The app lets you create notes with optional images and browse them in a searchable list.

## Features

- Create notes with titles, text content and image attachments
- Search and browse notes from a list
- View note details with clickable links and inline images
- Built entirely with Jetpack Compose and Navigation

## Development

This repository uses the included Gradle wrapper. Typical commands:

```bash
./gradlew build    # compile the application
./gradlew test     # run unit tests
```

## On-device summarization models

The TensorFlow Lite encoder/decoder models and tokenizer JSON now ship with the
app under `app/src/main/assets/`. The notebook `build_tensor.ipynb` fine-tunes
the FLAN-T5 model, converts it to `encoder_int8_dynamic.tflite` and
`decoder_step_int8_dynamic.tflite`, and copies those files plus `tokenizer.json`
into the assets directory. At runtime the app copies the bundled assets into
`context.filesDir/models` before loading them. If the interpreter or tokenizer
cannot be prepared, the summariser gracefully falls back to the extractive
strategy.

The large binaries remain untracked by Git. After regenerating the models, run
the notebook and keep only the metadata/documentation changes in version
control.

## Requirements

- Android Studio Giraffe (or newer)
- JDK 17

## Contributing

Contributions are welcome. Feel free to open issues or submit pull requests.
