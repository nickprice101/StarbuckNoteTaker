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

To enable the optional summarizer, download the TensorFlow Lite model files from the
project's release page and place them in `app/src/main/assets/`:

- `encoder_int8_dynamic.tflite`
- `decoder_step_int8_dynamic.tflite`
- `spiece.model`

Release downloads: https://github.com/nickprice101/StarbuckNoteTaker/releases/tag/v1.0.0

These binaries are excluded from version control and must be added manually.

## Requirements

- Android Studio Giraffe (or newer)
- JDK 17

## Contributing

Contributions are welcome. Feel free to open issues or submit pull requests.
