# Crawl4AI research service

The Android app keeps note inference on-device and uses a self-hosted
[Crawl4AI](https://github.com/unclecode/crawl4ai) server only when a chat question needs web
research. Search is used to discover public pages, then the app sends those URLs to Crawl4AI's
`POST /crawl` endpoint and supplies the extracted Markdown to the on-device model.

## Local Android emulator setup

Crawl4AI 0.9 is secure by default. Set a strong token before exposing its Docker port:

```powershell
$env:CRAWL4AI_API_TOKEN = '<random-long-token>'
docker run --rm -p 11235:11235 `
  -e CRAWL4AI_API_TOKEN=$env:CRAWL4AI_API_TOKEN `
  --shm-size=1g unclecode/crawl4ai:0.9.2
```

Configure the Android build through environment variables or Gradle properties:

```powershell
$env:CRAWL4AI_BASE_URL = 'http://10.0.2.2:11235'
$env:CRAWL4AI_API_TOKEN = '<the-same-token>'
./gradlew.bat :app:assembleDebug --console=plain
```

`10.0.2.2` is the Android emulator's route to the development host. The app permits cleartext
traffic only to local emulator/loopback hosts; remote and production Crawl4AI endpoints must use
HTTPS.

## Production

Put Crawl4AI behind a TLS-terminating reverse proxy, keep its egress controls enabled, and set
`CRAWL4AI_BASE_URL` to the public HTTPS origin at build time. Use a dedicated, revocable API token.
BuildConfig values can be extracted from an APK, so do not reuse an infrastructure-wide secret.

If the endpoint is unconfigured, offline, or unreachable, current/explicit web requests show an
internet-connectivity alert. Questions answerable from the note or the local model continue to work
without the research service.
