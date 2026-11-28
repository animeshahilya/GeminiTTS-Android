# Gemini TTS

An Android text-to-speech engine for TalkBack and reading apps powered by the on-device GenAI voices in Google's Speech Services. All synthesis and text processing happen locally on the phone — nothing is sent off-device.

## Features

### Speech, Speed & Audio DSP
- **System TTS engine:** Select **Gemini TTS** under *Settings → Accessibility → Text-to-speech output* so TalkBack, book readers, and other apps speak through it immediately (even before opening the app).
- **Wide rate & pitch control (`Sonic`):** GenAI models generate speech at a single fixed rate. Gemini TTS time-stretches and pitch-shifts PCM audio smoothly across `0.25×`–`4×` (`Base speed` `0.5×`–`3.5×`, `Base pitch` `0.5×`–`2×`).
- **Rate, pitch & volume locks:** Optionally ignore app/TalkBack rate, pitch, and caller volume/ducking parameters (`KEY_PARAM_VOLUME`) so speech stays consistent everywhere.
- **Volume boost & true-peak limiter:** Scale output from `25%` to `200%` with a fast-attack, smooth-release clip limiter and 64-frame raised-cosine de-clicking. When speed, pitch, volume, clarity, and leveler are all at neutral defaults, passthrough remains bit-exact.
- **Voice clarity EQ & loudness leveler:** Optional `2.5–5 kHz` consonant presence boost (`Off`, `Gentle`, `Voice clarity`, `Outdoor boost`) and dynamic loudness leveling (automatically disabled on single-character navigation).
- **Leading & tail silence trimming:** Strips initial neural model dead air (`300 ms` max with an `8 ms` pre-roll cushion) with zero live latency penalty across streamed chunks, and trims trailing dead air on cached clips.

### Reading Styles, Languages & Profiles
- **Reading styles:** `Standard`, `Audiobook`, `Calm`, `News`, `Quick`, or a `Custom` style with configurable speed, pitch, sentence pause, and paragraph pause.
- **Safe long-text chunking:** Automatically splits text longer than `1,400` characters across all styles on paragraph, sentence, or whitespace boundaries without ever breaking inside `TtsSpan` ranges or UTF-16 surrogate pairs.
- **21+ languages & per-language overrides:** Supports auto-downloading needed language voices in the background on first request, multilingual fallback when a language isn't downloaded yet, and per-language voice, speed, pitch, volume, and system-list visibility overrides. Respects TalkBack's whole-utterance `LocaleSpan` language switches.
- **Per-app profiles (no `QUERY_ALL_PACKAGES`):** Resolves calling apps via `SynthesisRequest.getCallerUid()` so TalkBack, book readers, and messaging apps can each use their own voice, reading style, speed, pitch, volume, or text preset (`Clean chat & Markdown`, `Developer / Code`, `OTP & verification codes`).
- **Voice presets + Quick Settings tile:** Save full engine configurations as named presets and cycle through them from the app or the **Voice preset** Quick Settings tile.

### Blind-Friendly Text & Number Pipeline (`TtsSpan`-Safe)
- **Context-aware numbers & OTPs:** Optionally reads OTP/PIN/verification/account codes digit-by-digit near keywords (`OTP`, `PIN`, `2FA`, masked `XXXX1234` card suffixes), preserves leading zeros (`007` → `0 0 7` while leaving hyphenated dates and times untouched), formats Indian `lakh`/`crore`/`arab` currency & comma grouping, and supports custom digit grouping (`Single`, `Pairs`, `Triplets`, with optional pauses) while protecting monetary values.
- **Chat, Markdown & code cleanup:** Normalizes fancy mathematical/fullwidth Unicode letters (`𝐇𝐞𝐥𝐥𝐨` → `Hello`), strips invisible hang-control characters and unpaired surrogates, spaces script punctuation and Hindi visarga colons, collapses repeated emojis (`😂😂😂😂` → `😂, 4`) and symbol dividers, strips Markdown (`**bold**`, headings, task checkboxes, code fences, links) without breaking arithmetic like `2*3 + 4*5`, optionally silences formatting symbols while preserving `-5`, `3.14`, and emails, splits `CamelCase`/`snake_case`, shortens tracking URLs, and clarifies common tech acronyms (`UPI`, `OTP`, `JSON`, `ChatGPT`).
- **Pronunciation dictionary & NVDA `.dic` support:** Supports whole-word, substring, case-sensitive, language-scoped, regular expression (with `\1`–`\9` and `$1`–`$9` backreferences), and single-character / Indic akshara spelling rules — all guarded by `SafeRegex` against catastrophic backtracking and empty matches. Import and export standard tab-separated NVDA `.dic` files.

### Sleep Timer, Recent Speech & Backup
- **Accessibility-safe sleep timer:** Arms a `15–60` minute sleep timer that returns `ERROR_SERVICE` to reading apps when expired while **never** silencing enabled spoken-feedback accessibility services (such as TalkBack).
- **Opt-in Recent speech buffer + Quick Settings tile:** Strictly off by default and memory-only (`15` utterances max, `5` minutes retention, never written to disk or backups, and gated behind `KeyguardManager` lock checks). Open via Settings or the **Recent speech** Quick Settings tile to replay at `1×` or `0.6×`, spell character-by-character (`BreakIterator`), or copy with `EXTRA_IS_SENSITIVE`.
- **Backup & restore:** Export and import a single JSON file (Settings, custom style, per-language overrides, per-app profiles, presets, and dictionary rules) via Android's document picker. Device-local items (recent-speech opt-in, active sleep timer, downloaded voice cache metadata) are excluded.
- **Direct Boot & instant phrase cache:** Preferences and dictionary live in device-protected storage for use before first unlock, alongside an up to `64 MB` phrase cache invalidated automatically when Google updates its speech engine.

## App Layout

The interface stays uncluttered by separating everyday controls from deeper customization:

- **Voice tab:** Set your default voice (with localized per-voice preview), pick or tune a Reading Style, switch saved presets, and test your active setup.
- **Languages tab:** Toggle automatic downloads, download offline voices, and tune per-language overrides.
- **Dictionary tab:** Add, edit, search, filter (`All` / `Words` / `Single char`), live-test, and import/export NVDA `.dic` rules.
- **Settings (`Settings` in the top bar):** Clean categorized preference hub covering *Speed, pitch & clarity*, *Text, numbers & OTPs*, *Per-app overrides*, *Voice presets*, *Sleep timer & recent speech*, and *Backup, restore & about*.
- **Quick Settings tiles:** Add **Voice preset** and **Recent speech** directly from Android's Quick Settings edit panel.

## How It Works

ML Kit's `genai-text-to-speech` (`1.0.0-alpha1`) is used solely to discover, check status, and download Google's GenAI voices. Because calling ML Kit's own `synthesize()` plays audio directly through `speak()`, this service instead binds Google's TTS engine (`com.google.android.tts`) via `PlatformSynth`, warms the default voice in the background, and streams raw 16-bit PCM using `synthesizeToFile()` into `/dev/null` (capturing chunks via `UtteranceProgressListener.onAudioAvailable()`).

```
Caller (TalkBack / App)
  └─▶ GenAiTtsService (Direct Boot prefs, sleep timer & caller UID check)
        └─▶ Language & Voice Plan (LocaleSpan ─▶ App profile ─▶ Language override ─▶ Voice)
              └─▶ TextPipeline + Dictionary (TtsSpan-safe) ─▶ Pause & Chunk Splitter (<= 1,400 chars)
                    ├─ Cache hit ────────────────────────┐
                    └─ Google TTS (GenAI /dev/null PCM) ─▼
                                                   LeadingSilenceTrimmer ─▶ Sonic ─▶ AudioOptimizer ─▶ SynthesisCallback
```

## Privacy & Permissions

- **Offline only:** Requests no `INTERNET` permission; voice downloads are delegated to Google Play services / Speech Services via ML Kit.
- **Minimal permissions:** Uses only `WAKE_LOCK` (60s-bounded per synthesis request) and package `<queries>` restricted to TTS services, launcher activities, and enabled accessibility services (no `QUERY_ALL_PACKAGES`).
- **Ephemeral Recent speech:** Captured only when explicitly enabled, stored only in process RAM for at most 5 minutes, hidden while locked, and cleared immediately when turned off or when the Quick Settings tile is removed.

## Requirements

- **Android 8.0+ (API 26)**; compiled and targeted against **Android 17 (SDK 37 / 37.2)**.
- **Google Speech Services (`com.google.android.tts`)** on a device offering on-device GenAI voices (falls back cleanly to the engine's standard voices elsewhere).
- Originally benchmarked by the project author on a Galaxy S25 Ultra (Android 17): voices synthesize ~4× real time on CPU with ~0.4 s cold first-audio latency and instant playback on cached phrases.

## Building & Verification

Requires JDK 17 and Android SDK platform `37.2` (the Gradle wrapper handles all dependencies).

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew assembleRelease   # signed when keystore.properties is present
```

Release signing reads a gitignored `keystore.properties` at the repository root (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); without it, the release APK is built unsigned with R8 minification and resource shrinking enabled.

### Test Coverage Notes
- `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease` currently runs **50 JVM unit tests across 7 suites** (`AudioOptimizerAndTrimTest`, `DictionaryAndBackupTest`, `DictionaryTest`, `PcmProcessorTest`, `ReadingStyleTest`, `TextPipelineAndNumberTest`, and `VoicesTest`) alongside Android Lint (`lintDebug`, `lintVitalRelease`) and full R8 release packaging.
- Unit tests run with `unitTests.isReturnDefaultValues = true` and `FakeSpanned` for `TtsSpan` boundary coverage; Android ART `SpannableStringBuilder`, Quick Settings tiles (`TileService`), and live on-device TalkBack synthesis require manual or instrumented testing on hardware.

## Disclaimer & License

Independent project — not affiliated with, endorsed by, or supported by Google. "Google", "Gemini", "Android", and "TalkBack" are trademarks of Google LLC. No voice weights or model files are bundled in this repository.

Licensed under the Apache License 2.0. See [LICENSE](LICENSE).
