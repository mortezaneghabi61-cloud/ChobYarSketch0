# ChobYar AI backend

This small server keeps the OpenAI API key off the Android APK and exposes one endpoint used by the app:

`POST /api/chobyar-ai`

## Environment variables

- `OPENAI_API_KEY` — required, server-side only
- `OPENAI_MODEL` — optional, defaults to `gpt-5-mini`
- `PORT` — optional, defaults to `8787`

## Run locally

```bash
cd server
npm install
OPENAI_API_KEY="..." npm start
```

Health check:

```text
GET /health
```

The Android app has an **AI چوب‌یار → اتصال** button. Enter the deployed HTTPS endpoint, for example:

```text
https://your-domain.example/api/chobyar-ai
```

Do not put `OPENAI_API_KEY` in Android source code, Gradle files, GitHub commits, or the APK.
