# Delfina

Delfina is an Android voice-assistant project being built in stages. The first milestone provides a native Compose interface, Android speech input, Android text-to-speech, an online OpenAI-compatible chat endpoint, and a deterministic offline fallback.

## Build an APK

Every push to `main` runs **Actions → Build Delfina APK**. Download the `Delfina-debug-apk` artifact from the successful workflow run.

You can also build locally with Gradle 8.9+ and JDK 17:

```bash
gradle :app:assembleDebug
```

## Online mode

Open Settings in the app and enter an OpenAI-compatible chat-completions endpoint, model name, and API key. The API key is stored locally in app preferences. Use a provider and endpoint you trust; never commit keys to this repository.

## Offline mode

When the endpoint is not configured or a network request fails, Delfina uses its built-in offline response rules and Android's installed on-device text-to-speech engine. Android speech recognition may use an installed offline language pack, depending on the device and language.

**Important:** this initial milestone does not yet include an embedded local large language model, custom wake-word detection, speaker verification, or guaranteed always-on background listening. Those require separately integrated and tested model runtimes and licensed model files. The app does not claim those features yet.

## Voice setup

For the best available voice, open Android **Settings → General management / Language and input → Text-to-speech output** (the path varies by phone). Select a high-quality voice and install the languages you want Delfina to speak. Delfina automatically selects a matching installed voice. If a language voice is missing, Android may fall back to its default voice. A particular female voice cannot be guaranteed because the Android TTS engine controls which voices are available.

## Next engineering milestones

1. Add a true on-device LLM runtime and a downloadable, license-checked model profile.
2. Add offline-capable multilingual STT and verify language-pack availability.
3. Add a foreground microphone service, wake-word pipeline, and speaker enrollment with Android-version-specific permissions.
4. Add streaming responses and incremental speech playback.
5. Test battery, thermal behavior, latency, and screen-off operation on real devices.


## Online and offline speech

Delfina supports an optional online neural speech service and an offline Android TTS engine.
For online speech, set a compatible chat-completions URL and API key. If that URL ends in
`/chat/completions`, Delfina derives the matching `/audio/speech` URL; alternatively, enter
a separate Speech endpoint in Settings. It requests `gpt-4o-mini-tts` with the `shimmer`
voice and warm, feminine delivery instructions. The same API key is reused. Failed or
unsupported online speech automatically falls back to the currently selected Android TTS engine.

### Better offline neural voice (optional)

Delfina uses Android's standard TextToSpeech API for its offline route. To replace the stock
phone voice with an on-device neural voice, install a compatible Android TTS engine such as
the official sherpa-onnx TTS Engine APK, then install/select a voice model in that engine.
Open Delfina → Settings → Configure offline voice engine and choose the installed engine as
the system preferred engine. Because Android routes TextToSpeech through the selected engine,
Delfina will use that neural voice when the network is unavailable.

Official engine/model downloads and supported models:
https://k2-fsa.github.io/sherpa/onnx/tts/apk-engine.html

Choose an English female voice model in the catalog for the closest match to the online
Shimmer persona. For Hindi, the sherpa-onnx catalog lists a Hindi Supertonic-3 engine build;
check the model's available speakers and license before selecting it. Models have different
licenses, sizes, voices and language coverage; check each model's license before commercial
distribution. The online Shimmer voice and any separately trained offline voice are not
literally the same recording/timbre, but both are configured for a warm feminine style.

Online neural speech requires internet access, a compatible provider, and an API plan/key that
permits speech generation; it may incur provider charges. The offline neural engine and voice
must be installed once and need no network for synthesis. If no neural engine is installed,
Delfina falls back to the phone's built-in TTS voice.

## Realtime backend deployment

The repository includes a Vercel serverless endpoint at `/api/realtime-token`.
It creates short-lived OpenAI Realtime client secrets; it does not expose the permanent
OpenAI API key to the Android app. The Android Realtime/WebRTC client is a separate
integration step and is not yet complete.

### One-time setup

1. Create a free personal Vercel account at https://vercel.com and import
   `inkbymuntaha-alt/Delfina` as a project. Keep the project root at the repository root.
2. In Vercel → Project → Settings → Environment Variables, add:
   - `OPENAI_API_KEY`: your OpenAI **API** key (never put it in a Git commit or chat).
   - `DELFINA_ACCESS_CODE`: a long, randomly generated private code. Keep it secret.
   - Optional: `OPENAI_REALTIME_MODEL`: set to a Realtime model enabled for your API account.
   Apply these variables to Production, then redeploy once.
3. In Vercel → Project → Settings → General, copy the Project ID. Find the Team/Account ID
   under Vercel account settings. Create a Vercel access token in Account Settings → Tokens.
4. In GitHub → this repository → Settings → Secrets and variables → Actions, add these
   repository secrets:
   - `VERCEL_TOKEN`
   - `VERCEL_ORG_ID`
   - `VERCEL_PROJECT_ID`
5. Run **Actions → Deploy Delfina Realtime Backend → Run workflow**. Future changes to
   `api/**` or `vercel.json` on `main` deploy automatically.

After deployment, the endpoint URL is
`https://YOUR-VERCEL-PROJECT.vercel.app/api/realtime-token`.
The Android app must send a POST request with
`Authorization: Bearer YOUR_DELFINA_ACCESS_CODE` to obtain a short-lived client secret.
Do not share the OpenAI API key or the access code in screenshots, issues, or chat.

Vercel Hobby is free for personal, non-commercial projects and has usage limits. OpenAI
Realtime usage is billed separately by OpenAI; this backend does not make API usage free.
The access code is a basic private gate, not full user authentication; rotate it if exposed.
