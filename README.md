# Gemini TTS

An Android text-to-speech engine for TalkBack and any other app that speaks. It uses the on-device
GenAI voices that Google's Speech Services provide on recent flagship phones, so speech is made on
the phone.

## What it does

- **System TTS engine.** Pick it in Settings → Accessibility → Text-to-speech output, and TalkBack,
  readers and other apps speak with it.
- **Your speed and pitch.** GenAI voices only speak at one speed. Gemini TTS speeds them up or
  changes the pitch to match what the app asks for (Sonic time-stretching), from 0.25× to 4×.
- **Reading styles.** Standard, Audiobook, Calm, News, Quick, or your own Custom style. A style adjusts
  speed and pitch on top of what the app asks for, and adds pauses after sentences and paragraphs. It
  applies to every app, voice and language.
- **Saved phrases.** Phrases you hear often ("Double-tap to activate", button names) are kept at
  normal speed and replay instantly at any speed. Can be turned off; uses up to 64 MB.
- **Pronunciation dictionary.** Change how words are read. Whole words only, with optional regular
  expressions.
- **Works before the first unlock.** Settings and saved phrases live in device-protected storage.
- **Many languages.** Every language Google offers the GenAI voices in (21, including Hindi, German,
  French, Spanish, Japanese and Arabic). You pick one voice and it is used in every language. Each
  language downloads on its own from the Languages tab, for its native pronunciation.
- **Falls back gracefully.** The GenAI voices are multilingual, so a language that isn't downloaded is
  read by your voice in a language that is. Google's standard voice is only used when no GenAI voice
  is downloaded at all.
- **Fast first sentence.** The voice is loaded in the background when the engine starts, so the first
  thing read doesn't wait for it.

## How it works

ML Kit's `genai-text-to-speech` (1.0.0-alpha1, still an alpha) is used only to list and download the
GenAI voices. Those voices are
ordinary voices of Google's TTS engine (`com.google.android.tts`), so ML Kit's own `synthesize()`
calls `speak()` and plays the audio itself. A TTS service built on it would play every utterance
twice, once at 1× speed.

Instead, the service binds Google's engine directly, picks the matching voice, and calls
`synthesizeToFile()` into `/dev/null`. The audio arrives through `onAudioAvailable`, as fast as
it is made, without playing. It then goes through Sonic to the caller's `SynthesisCallback`.

```
TalkBack → GenAiTtsService → dictionary → saved phrase?
                                   ├─ yes → Sonic → SynthesisCallback
                                   └─ no  → Google TTS (GenAI voice) → Sonic → SynthesisCallback
```

TtsSpans (phone numbers, dates, money) are passed through to Google's engine, which reads them
natively.

## Requirements

- Android 8.0 or newer (API 26).
- Google Speech Services, and a phone where ML Kit offers on-device GenAI voices. Google lists Pixel 8
  to 11, Galaxy S25 and S26, and Galaxy Z Fold8 and Z Flip8. Elsewhere the engine still works with the
  standard Google voices.
- Tested on a Galaxy S25 Ultra (Android 17). There Google currently runs these voices on the CPU,
  about four times faster than real time; speech starts about 0.4 s after a request, and saved phrases
  start at once.

## Building

Needs JDK 17 and the Android SDK with platform 37 (the Gradle wrapper fetches everything else).

```bash
./gradlew testDebugUnitTest assembleDebug
./gradlew assembleRelease   # signed when keystore.properties is present
```

Release signing reads a gitignored `keystore.properties` at the project root (`storeFile`,
`storePassword`, `keyAlias`, `keyPassword`). Without it, the release APK is unsigned.

## Not a Google product

This is an independent project. It isn't made, endorsed or supported by Google. "Google", "Gemini",
"Android" and "TalkBack" are trademarks of Google LLC. No model files are included; voices are
downloaded by Google's Speech Services on the phone.

## License

Apache License 2.0. See [LICENSE](LICENSE).
