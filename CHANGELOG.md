# Changelog

## 1.1

### Audio DSP & Speech Engine
* **Consonant clarity & loudness leveler:** Added optional `2.5–5 kHz` vocal presence boost (`Off`, `Gentle clarity`, `Voice clarity`, `Outdoor boost`), a silence-gated dynamic loudness leveler (auto-disabled during single-character navigation), `25%`–`200%` volume boost with true-peak clip limiting and 64-frame raised-cosine de-clicking, and bit-exact passthrough when neutral.
* **Dead-air trimming & faster streaming:** Added `LeadingSilenceTrimmer` / `trimClip` trailing-silence trimming (preserving soft onsets and sub-threshold audio), rate/pitch/volume locks, tighter `Sonic`/PCM buffer pooling, and reduced idle latency on the service path.
* **TTS audio attribution & engine resilience:** Declared `tts_playback` audio attribution in the manifest, guarded against NaN/infinite parameters and listener races, and hardened atomic cache reads with stale `.tmp` cleanup.

### Languages, Profiles & Quick Controls
* **Per-app overrides & Presets:** Resolve calling apps via UID (no `QUERY_ALL_PACKAGES`) with custom speed, pitch, volume, voice, reading style, and text presets (`Clean chat & Markdown`, `Developer / Code`, `OTP & verification codes`), plus named Voice Presets and a **Voice preset** Quick Settings tile.
* **Language flexibility:** Legacy/alias locale matching (`in` → Indonesian, `no`/`nor` → Norwegian Bokmål) alongside all 21 GenAI languages and per-language visibility/tuning overrides.

### Blind-Friendly Text, Dictionary & Utilities
* **Context-aware numbers & chat formatting:** OTP/PIN digit reading, masked card suffixes, leading-zero preservation, Indian `lakh`/`crore` formatting, configurable digit grouping, fancy Unicode normalization, Markdown cleanup, silenced formatting symbols, `CamelCase`/`snake_case` splitting, tech acronyms, repeated emoji collapsing, and safe URL shortening.
* **Enhanced Dictionary & `.dic` tooling:** Whole-word, substring, case-sensitive, language-scoped, regex (backreferences `\1`–`\9`, `$1`–`$9`, `SafeRegex` ReDoS guard), and Indic akshara / single-char rules, UTF-8/16 BOM `.dic` import, rule search/filters, live phrase testing, and IME-safe dialogs that keep errors and actions above the keyboard.
* **Sleep timer, Recent speech & backup:** Accessibility-safe `15–60` minute sleep timer (never silences TalkBack), ephemeral opt-in **Recent speech** buffer + Quick Settings tile (`1×`/`0.6×` replay, `BreakIterator` spelling, sensitive copy), categorized Settings hub, and `5 MB`-bounded JSON backup/restore.

## 1.0

*   **Speech engine**: Use Google's on-device GenAI voices with TalkBack, book readers and any app that speaks. Nothing leaves your phone.
*   **Languages**: 21 languages. Each one downloads by itself in the background the first time an app reads it (you can turn that off), and the Languages tab shows which ones Google can speak right now. Your chosen voice is used in all of them, and text in another language is still read by a GenAI voice.
*   **Reading styles**: Standard, Audiobook, Calm, News, Quick, or your own Custom speed, pitch and pauses. One style applies everywhere.
*   **Speed and pitch**: Follows the speed and pitch each app asks for, from very slow to very fast.
*   **Quick start**: The voice is ready before the first sentence, and phrases you hear often start instantly. Saved phrases are cleared when Google updates its voices.
*   **Pronunciations**: Teach it how to say words, names and abbreviations.
*   **Works right away**: The GenAI voice is used even if you only pick Gemini TTS in Android's text-to-speech settings, without opening the app.
*   **Accessibility**: Built for TalkBack, with clear labels and headings throughout.
