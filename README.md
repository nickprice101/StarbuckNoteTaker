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

The app downloads its TensorFlow Lite summarisation models from a public
endpoint at `https://music.corsicanescape.com/apk/` on first run and caches
them under internal storage. If the download fails, summaries gracefully fall
back to a simple extractive method.

The model URLs are configured in `ModelFetcher`. Update these constants when
publishing new model versions or moving the files.

## Requirements

- Android Studio Giraffe (or newer)
- JDK 17

## Contributing

Contributions are welcome. Feel free to open issues or submit pull requests.
