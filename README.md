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

## Next engineering milestones

1. Add a true on-device LLM runtime and a downloadable, license-checked model profile.
2. Add offline-capable multilingual STT and verify language-pack availability.
3. Add a foreground microphone service, wake-word pipeline, and speaker enrollment with Android-version-specific permissions.
4. Add streaming responses and incremental speech playback.
5. Test battery, thermal behavior, latency, and screen-off operation on real devices.
