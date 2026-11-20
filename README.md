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

Delfina now tries OpenAI-compatible neural speech when the configured chat endpoint ends in
`/chat/completions`. It derives the matching `/audio/speech` endpoint and requests the
`gpt-4o-mini-tts` model with the `shimmer` voice and warm, feminine delivery instructions.
The same API key is reused. Providers that do not expose this speech endpoint, or requests
that fail, automatically fall back to Android TextToSpeech. That local voice can work offline
when the required language voice is installed on the phone.

Online neural speech requires internet access, a compatible provider, and an API plan/key that
permits speech generation; it may incur provider charges. The local fallback is not the same
quality as neural speech. Voice availability and language pronunciation vary by provider and
installed Android voices.
