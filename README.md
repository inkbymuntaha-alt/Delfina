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
