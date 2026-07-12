# pichu — Japanese Voice Conversation + Shadowing App

On-device voice loop for practicing Japanese: **you speak → on-device ASR
transcribes → your cloud LLM replies in Japanese → on-device TTS speaks the reply
so you can shadow it.** Plus a dedicated *shadowing* mode.

Target device: **Samsung Galaxy A56** (Exynos 1580, 12 GB RAM, `arm64-v8a`).

Built by adapting the official [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
Android examples (ASR / VAD+ASR / TTS) into one app.

> **Status: Phase 1 — English ASR spike.** Mic → streaming Nemotron ASR → live
> transcript. English first (stable), Japanese swapped in at Phase 5. See
> "Roadmap" at the bottom.

---

## Architecture (locked-in stack)

| Component | Choice | Runs where |
|---|---|---|
| ASR | NVIDIA Nemotron streaming (INT8 ONNX) via sherpa-onnx | On-device |
| Endpointing | Silero VAD (bundled in sherpa-onnx) | On-device *(Phase 2)* |
| LLM | Your cloud API over HTTPS | Cloud *(Phase 3)* |
| TTS | Japanese VITS/MMS via sherpa-onnx | On-device *(Phase 4)* |
| Audio I/O | `AudioRecord` 16 kHz mono in, `AudioTrack` float PCM out | On-device |
| Runtime | sherpa-onnx v1.13.3 (ONNX Runtime under the hood) | On-device |
| UI | XML Views | — |

**There is no local LLM.** The LLM is your cloud service; the only on-device
models are ASR, VAD and TTS.

---

## How sherpa-onnx is integrated (no AAR)

We avoid the AAR entirely (sidestepping **Landmine #1**, "AGP 8+ rejects a raw
`.aar` in a library module"). Instead, following the official examples:

- **Kotlin API sources** copied into `app/src/main/java/com/k2fsa/sherpa/onnx/`
  (`OnlineRecognizer.kt`, `OnlineStream.kt`, `FeatureConfig.kt`, `QnnConfig.kt`,
  `HomophoneReplacerConfig.kt`). These are verbatim from the cloned repo's
  `sherpa-onnx/sherpa-onnx/kotlin-api/`.
- **Native libraries** extracted from `sherpa-onnx-v1.13.3-android.tar.bz2` into
  `app/src/main/jniLibs/arm64-v8a/`:
  - `libsherpa-onnx-jni.so`
  - `libonnxruntime.so`

If you upgrade sherpa-onnx later, re-copy both the Kotlin sources **and** the
`.so` files from the matching release so the JNI signatures stay in sync.

### Toolchain

The initial scaffold used bleeding-edge **AGP 9.2.1** preview DSL. Per the build
plan (don't debug bleeding-edge tooling and app architecture at once), this was
downgraded to a stable combo:

| | Version |
|---|---|
| Android Gradle Plugin | 8.7.2 |
| Gradle wrapper | 8.9 |
| Kotlin | 2.0.20 |
| compileSdk / targetSdk | 35 |
| minSdk | 26 |
| ABI | `arm64-v8a` only |

`app/build.gradle.kts` also sets `androidResources { noCompress += "onnx" }` as
belt-and-suspenders against **Landmine #2** (SIGBUS from a memory-mapped,
compressed `.onnx`). Phase 1 loads models from the filesystem anyway (below).

---

## Build & run (Phase 1)

### 1. Prerequisites
- Android Studio (or CLI) with **Android SDK platform 35** + **build-tools 35**.
- A `local.properties` with your SDK path, e.g. `sdk.dir=C:/Android/Sdk`
  (already present on this machine; not committed).
- JDK 17–21.

### 2. Get the English ASR model

Phase 1 uses the **stable English** Nemotron streaming model
(`sherpa-onnx-nemotron-speech-streaming-en-0.6b-560ms-int8-2026-04-25`) from the
k2-fsa `asr-models` release. Download and extract it, e.g.:

```bash
curl -SL -O https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemotron-speech-streaming-en-0.6b-560ms-int8-2026-04-25.tar.bz2
tar xjf sherpa-onnx-nemotron-speech-streaming-en-0.6b-560ms-int8-2026-04-25.tar.bz2
```

The extracted folder must contain `encoder.int8.onnx`, `decoder.int8.onnx`,
`joiner.int8.onnx`, `tokens.txt`.

Also grab the **Silero VAD** model (Phase 2, ~640 KB) — it goes directly under
`models/`, not in the ASR subfolder:

```bash
curl -SL -O https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx
```

And the **Kokoro TTS** model (Phase 4, Japanese voice). NOTE: Kokoro **v1.1-zh
has no Japanese** — use **v1.0**, which has Japanese voices at sid 37–41:

```bash
curl -SL -O https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2
tar xjf kokoro-int8-multi-lang-v1_0.tar.bz2   # -> kokoro-int8-multi-lang-v1_0/ (push whole folder)
```

> The 560 ms variant is a good latency/accuracy balance. To try another chunk
> size, download the matching 80 ms / 160 ms / 1120 ms export and change
> `MODEL_TYPE` in [`MainActivity.kt`](app/src/main/java/com/nephren/pichu/MainActivity.kt)
> (30 = 80 ms, 31 = 160 ms, 32 = 560 ms, 33 = 1120 ms).

### 3. Push the model to the device

Models are **not** bundled in the APK. Build & install first, then push the
model into the app's external files dir (no `run-as` needed):

```bash
./gradlew :app:installDebug
# creates the external files dir on first launch; then:
adb shell mkdir -p /sdcard/Android/data/com.nephren.pichu/files/models
adb push sherpa-onnx-nemotron-speech-streaming-en-0.6b-560ms-int8-2026-04-25 \
  /sdcard/Android/data/com.nephren.pichu/files/models/
```

Final layout on device — **the 4 files must sit inside the model-named
subfolder, NOT directly in `models/`** (the app builds paths as
`models/<model-name>/encoder.int8.onnx`):
```
/sdcard/Android/data/com.nephren.pichu/files/models/
  ├─ sherpa-onnx-nemotron-speech-streaming-en-0.6b-560ms-int8-2026-04-25/   <-- this subfolder is required
  │    ├─ encoder.int8.onnx
  │    ├─ decoder.int8.onnx
  │    ├─ joiner.int8.onnx
  │    └─ tokens.txt
  ├─ silero_vad.onnx                                                        <-- directly under models/
  └─ kokoro-int8-multi-lang-v1_0/                                           <-- whole Kokoro TTS folder
       ├─ model.int8.onnx, voices.bin, tokens.txt
       └─ espeak-ng-data/ , lexicon-*.txt , …
```

If the model is missing (or the files are in the wrong place), the app shows
the exact expected paths on screen instead of crashing — read that message to
see where it's looking.

### Emulator note (ABI)

`app/build.gradle.kts` vendors both `arm64-v8a` (the A56) and `x86_64` (PC
emulator) native libs. Most Android Studio emulators are `x86_64`; some images
also run `arm64-v8a` via ARM translation. Either works. The models themselves
are architecture-independent — only the `.so` files are per-ABI.

### 4. Run
```bash
./gradlew :app:assembleDebug     # or installDebug
```
Launch **pichu**, grant the microphone permission, tap **Start**, and speak
English. A live transcript appears; endpoints split it into numbered lines.

---

## TODO markers (you supply these)

- **`MODEL_TYPE` / `FEATURE_DIM`** in `MainActivity.kt` — ASR model selection.
  Nemotron needs `FEATURE_DIM = 128`.
- **LLM endpoint + key** — *Phase 3, stubbed.* `Llm.kt` defines the `LlmClient`
  interface + `StubLlmClient` (canned streaming reply, no network). To go live,
  implement `LlmClient` with your API (OkHttp + SSE streaming) and swap the one
  line `private val llm: LlmClient = StubLlmClient()` in `MainActivity`. Tune
  `SYSTEM_PROMPT` for the learner level.
- **Final TTS model** — *Phase 4.* MMS-Japanese baseline, behind an interface so
  a cloud Japanese TTS can replace it if on-device pitch-accent quality is too
  low for shadowing.

---

## Roadmap (phased — test after each)

1. **✅ ASR spike (English)** — mic → streaming transcript.
2. **✅ VAD endpointing (Silero)** — live partial transcript + Silero VAD marks
   end-of-utterance, emitting one final numbered turn each time you pause.
   `onFinalTranscript()` in `MainActivity` is the seam Phase 3 plugs the LLM into.
   *(this phase)*
3. **✅ LLM turn (stubbed)** — each final transcript → `LlmClient.streamReply()` →
   streamed Japanese reply shown as `You:` / `JA:` lines, with an
   Idle→Listening→Thinking→Speaking status. `StubLlmClient` returns a canned
   streaming reply; implement `LlmClient` with your real API to go live (see
   `Llm.kt`, `SYSTEM_PROMPT`). *(this phase)*
4. **✅ TTS** — reply spoken aloud via **Android's built-in TextToSpeech**
   (Japanese), behind a swappable `TtsEngine` interface (`TtsEngine.kt`).
   *Why not sherpa-onnx:* the release has **no working on-device Japanese voice**
   — MMS has no `jpn`, and Kokoro's Japanese needs a `misaki` G2P sherpa doesn't
   bundle (it spells characters via espeak → ~17 s of "japanese letter"). A
   `SherpaTtsEngine` (Kokoro) is kept in the file for reference, and `AndroidTts
   Engine` is the active one. For best pitch-accent (shadowing), swap in a cloud
   JA TTS via the same interface. Requires Japanese TTS voice data on the device
   (Settings ▸ Text-to-speech). Models load on a background thread. *(this phase)*
5. First-launch model downloader into `filesDir` (replaces the manual `adb push`).
6. Swap to multilingual Nemotron-3.5 pinned to `ja-JP`; state machine
   `Idle → Listening → Thinking → Speaking` with barge-in. **May require an AAR
   built from sherpa-onnx master if the stable release lacks multilingual
   Nemotron** — verify before this phase.
7. Shadowing mode — target sentence spoken + shown (furigana/romaji toggle),
   capture the user's repeat, diff vs. target, replay original vs. attempt.

---

## Known landmines (reference)

1. **AGP 8+ rejects a raw `.aar` in a library module.** → We use Kotlin sources +
   `jniLibs`, no AAR.
2. **SIGBUS on model load** from a compressed memory-mapped `.onnx`. → Load from
   filesystem (`getExternalFilesDir`); `noCompress += "onnx"` as backup.
3. **Multilingual Nemotron-3.5 is newer than the last stable AAR.** → Phase 1 is
   English on purpose; verify multilingual support before Phase 6.
4. **Japanese TTS pitch-accent quality is the weak link.** → MMS-JPN baseline
   behind an interface; cloud TTS fallback.
5. **TTS is non-streaming.** → Generate off the main thread; split long replies
   into sentences and pipeline generate→play.
