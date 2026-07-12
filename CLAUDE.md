# CLAUDE.md — pichu

Agent knowledge for this repo. Read this first.

## What this is
**pichu** is an Android app for practicing Japanese by voice: you speak Japanese →
on-device ASR transcribes it → a cloud LLM replies in Japanese → on-device TTS
speaks the reply so you can **shadow** it. There is also a planned dedicated
*shadowing* mode. Package `com.nephren.pichu`.

Target device: **Samsung Galaxy A56** (Exynos 1580, 12 GB RAM, `arm64-v8a`).
Dev/testing also runs on an **x86_64 Android Studio emulator**.

**Everything runs on-device except the LLM** (cloud, HTTPS). There is **no local
LLM** to install.

## Current status (phased build)
Do one phase, let the user test, then continue. Done so far:

1. ✅ **English streaming ASR** — mic → live transcript (Nemotron).
2. ✅ **Silero VAD endpointing** — live partial + end-of-utterance turn detection.
3. ✅ **LLM turn (STUBBED)** — final transcript → `LlmClient` → streamed reply,
   shown as `You:` / `JA:` with an Idle→Listening→Thinking→Speaking status.
4. ✅ **TTS** — reply spoken aloud. Currently **Android system TTS** (see TTS note).

Next / not done:
- **On-device high-quality Japanese TTS** — the user wants to attempt running
  **onnx-community/Qwen3-TTS-12Hz-0.6B-CustomVoice** on-device (see TTS note). This
  is a deliberate side-project, not a config change (7-model ONNX pipeline + custom
  autoregressive runtime; sherpa-onnx does NOT support it).
- **Phase 5** — swap English ASR for multilingual **Nemotron-3.5** pinned `ja-JP`;
  build the `Idle→Listening→Thinking→Speaking` state machine with **barge-in**
  (stop TTS when the user starts talking). May need an AAR built from sherpa master.
- **Phase 6** — shadowing mode (speak a target JA sentence, show furigana/romaji,
  user repeats, diff transcript vs target, replay).

## Stack
| Component | Choice |
|---|---|
| ASR | NVIDIA Nemotron streaming (INT8 ONNX) via sherpa-onnx, on-device |
| VAD | Silero VAD (sherpa-onnx), on-device |
| LLM | Cloud API over HTTPS (currently `StubLlmClient`) |
| TTS | Android system TextToSpeech (JA); target = better JA later |
| Audio in | `AudioRecord` 16 kHz mono PCM16 |
| Audio out | `AudioTrack` `ENCODING_PCM_FLOAT` |
| UI | XML Views |

## sherpa-onnx integration (NO AAR)
Avoids the "AGP 8+ rejects a raw .aar in a library module" landmine. Instead:
- Kotlin API `.kt` files are **vendored** into `app/src/main/java/com/k2fsa/sherpa/onnx/`
  (`OnlineRecognizer`, `OnlineStream`, `FeatureConfig`, `QnnConfig`,
  `HomophoneReplacerConfig`, `Vad`, `Tts`). Copied verbatim from a sherpa-onnx
  checkout's `sherpa-onnx/kotlin-api/`.
- Native libs are committed under `app/src/main/jniLibs/{arm64-v8a,x86_64}/`
  (`libsherpa-onnx-jni.so`, `libonnxruntime.so`), extracted from the
  `sherpa-onnx-v<ver>-android.tar.bz2` release. Keep the Kotlin sources and `.so`
  from the **same** release in sync.

## Key files
- `app/src/main/java/com/nephren/pichu/MainActivity.kt` — audio loop, VAD turns,
  LLM + TTS wiring, the display/state machine. Tunables at top: `MODEL_TYPE`,
  `FEATURE_DIM`, `TAIL_PADDING_SEC`, `VAD_MIN_SILENCE_SEC`, `TTS_SID`.
- `Llm.kt` — `LlmClient` interface + `StubLlmClient` + `SYSTEM_PROMPT`. Implement
  `LlmClient` (OkHttp + SSE) and change one line in MainActivity to go live.
- `TtsEngine.kt` — `TtsEngine` interface; `AndroidTtsEngine` (active),
  `SherpaTtsEngine` (Kokoro, kept for reference but Japanese doesn't work — see below).
  A `CloudTtsEngine` would slot in here.

## Toolchain
Stable, deliberately NOT bleeding-edge (the original scaffold shipped AGP 9.2.1
preview + a missing Kotlin plugin + missing `android.useAndroidX`).
- AGP **8.7.2**, Gradle **8.9**, Kotlin **2.0.20**, compileSdk/targetSdk **35**,
  minSdk **26**, ABI filters `arm64-v8a` + `x86_64`.
- `gradle.properties` has `android.useAndroidX=true` (was missing → build failed).
- `app/build.gradle.kts` sets `androidResources { noCompress += "onnx" }`
  (belt-and-suspenders vs the SIGBUS-on-compressed-mmap'd-onnx landmine; we load
  models from the filesystem anyway).

## Build & run
**The agent cannot run Gradle in this environment** — Gradle forks a single-use
daemon and the sandbox blocks its loopback socket (`Unable to establish loopback
connection`), regardless of `--no-daemon`/shell/sandbox flag. Dependency
resolution works up to the fork; compilation does not. **Hand builds to the user.**

User builds via **Android Studio Terminal**:
```
.\gradlew.bat installDebug
```
(Their Android Studio has a working Gradle daemon; the loopback issue is only in
the agent's tool sandbox.) JDK 17–21. SDK at `C:/Android/Sdk`.

Agent-side tooling that DOES work: `adb` at `C:/Android/Sdk/platform-tools/adb.exe`
(in Git Bash set `export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'` or Unix paths
get mangled). Use `adb push` for models and `adb logcat` for debugging (filter tag
`pichu`).

## Model setup (NOT committed — pushed to device at runtime)
Models live in the app's **external files dir** (adb-pushable, no run-as):
`/sdcard/Android/data/com.nephren.pichu/files/models/`
```
models/
  ├─ sherpa-onnx-nemotron-speech-streaming-en-0.6b-560ms-int8-2026-04-25/   <-- ASR in this subfolder!
  │    encoder.int8.onnx  decoder.int8.onnx  joiner.int8.onnx  tokens.txt
  └─ silero_vad.onnx                                                        <-- directly under models/
```
**Gotcha:** the ASR files must sit inside the model-named subfolder, not directly
in `models/`. If a model is missing the app shows the expected paths on screen
instead of crashing. Phase 4.5 will replace manual push with a first-launch
downloader into `filesDir`. Sources are in the README.

## Emulator notes
- Emulator is **x86_64**; that's why both ABIs are vendored. (Some images also run
  arm64 via translation.)
- Mic: Extended controls (⋯) → Microphone → "Virtual microphone uses host audio
  input". Resets on cold boot.
- ASR is decent but laggy on the emulator (translated CPU); real performance is
  judged on the A56. The emulator is a **bad proxy for on-device TTS speed**.

## Decisions & landmines (don't rediscover these)
- **Last word dropped at end of turn** → feed `TAIL_PADDING_SEC` (0.8 s) of silence
  to the recognizer before reading the final result (streaming model needs
  right-context). See MainActivity finalize block.
- **Mid-sentence cut on comma pauses** → `VAD_MIN_SILENCE_SEC` = 0.6 s (Silero
  default 0.25 s was too eager). Tunable.
- **TTS — sherpa-onnx has NO working on-device Japanese voice.** No `vits-mms-jpn`
  in the release; Kokoro v1.0 Japanese needs a `misaki` G2P sherpa doesn't bundle
  (it spells characters via espeak → ~17 s of "japanese letter" for a short
  sentence); Kokoro v1.1-zh has no Japanese at all. So we use **Android system
  TTS**. `SherpaTtsEngine` is kept for reference only.
- **`OfflineTts.generateWithCallback` crashes** (D8 desugars the callback lambda
  without the specialized `invoke([F)Integer` the JNI looks up → `JNI DETECTED
  ERROR` abort). If using sherpa TTS, call `generate()` (one-shot) instead.
- **Better Japanese TTS options** (Magpie 357M, Qwen3-TTS 0.6B/1.7B) are GPU-class
  LLM-codec pipelines. Best run **server-side** and called via a `CloudTtsEngine`.
  On-device Qwen3-TTS-0.6B is possible but is a multi-week custom 7-model ORT
  pipeline with unproven A56 real-time speed — a deliberate side-project.
- Two scaffold gaps that broke the build early: missing `android.useAndroidX=true`
  and missing Kotlin plugin. Both fixed.

## How to work here
- Match existing code style. Keep all inference/audio off the main thread.
- After code changes, ask the user to rebuild (`installDebug`) and test; then
  read `adb logcat` (tag `pichu`) to verify. The app logs model init, VAD state,
  final turns, and TTS status.
