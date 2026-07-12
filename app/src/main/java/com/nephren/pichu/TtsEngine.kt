package com.nephren.pichu

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Speaks Japanese text aloud. The whole app talks to this interface only, so a
 * cloud Japanese TTS (Google/Azure/ElevenLabs) can replace [SherpaTtsEngine]
 * without touching the UI if on-device pitch-accent quality is too low to shadow.
 */
interface TtsEngine {
    /** Generates + plays [text]; [onDone] fires (background thread) when finished. */
    fun speak(text: String, onDone: () -> Unit)

    /** Stops playback immediately (used for Phase 5 barge-in). */
    fun stop()

    fun release()
}

/**
 * On-device Japanese TTS via Android's built-in TextToSpeech. Correct Japanese
 * pronunciation (unlike sherpa-onnx Kokoro, whose Japanese G2P is missing), no
 * model download. Requires the device to have Japanese TTS voice data installed
 * (most phones do or can fetch it; a bare emulator may not — [available] reports).
 *
 * NOTE: init is async — construct early and it'll be ready by the time the user
 * speaks. [onInitDone] fires with whether Japanese is usable.
 */
class AndroidTtsEngine(
    context: Context,
    private val onInitDone: (available: Boolean) -> Unit = {},
) : TtsEngine {
    @Volatile var available = false
        private set
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            available = if (status == TextToSpeech.SUCCESS) {
                val res = tts?.setLanguage(Locale.JAPANESE)
                res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
            } else {
                false
            }
            Log.i("pichu", "Android TTS init: status=$status japaneseAvailable=$available")
            onInitDone(available)
        }
    }

    override fun speak(text: String, onDone: () -> Unit) {
        val t = tts
        if (t == null || !available) {
            Log.w("pichu", "Android TTS not available; skipping speak")
            onDone()
            return
        }
        val id = "u" + System.nanoTime()
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id) onDone() }
            @Deprecated("deprecated") override fun onError(utteranceId: String?) {
                if (utteranceId == id) onDone()
            }
        })
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    override fun stop() {
        tts?.stop()
    }

    override fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}

/**
 * On-device TTS via sherpa-onnx Kokoro (multi-lang v1.0), played through
 * AudioTrack in ENCODING_PCM_FLOAT. Generation runs on a single background
 * thread; audio streams to AudioTrack as it's produced (generate → play pipeline).
 *
 * Kokoro v1.0 Japanese voices are sid 37..41 (37 jf_alpha, 38 jf_gongitsune,
 * 39 jf_nezumi, 40 jf_tebukuro, 41 jm_kumo). Sample rate is read from the model
 * (24 kHz), never hardcoded.
 */
class SherpaTtsEngine(
    modelDir: File,          // .../models/kokoro-int8-multi-lang-v1_0
    private val sid: Int = 37,     // jf_alpha — Japanese female
    private val speed: Float = 1.0f,
) : TtsEngine {
    private val tts: OfflineTts
    private val track: AudioTrack
    private val exec = Executors.newSingleThreadExecutor()

    init {
        val cfg = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = File(modelDir, "model.int8.onnx").absolutePath,
                    voices = File(modelDir, "voices.bin").absolutePath,
                    tokens = File(modelDir, "tokens.txt").absolutePath,
                    dataDir = File(modelDir, "espeak-ng-data").absolutePath,
                    // en/zh lexicons (Japanese is phonemized via espeak-ng "ja").
                    lexicon = File(modelDir, "lexicon-us-en.txt").absolutePath + "," +
                        File(modelDir, "lexicon-zh.txt").absolutePath,
                ),
                numThreads = 2,
                provider = "cpu",
            ),
        )
        tts = OfflineTts(assetManager = null, config = cfg)

        val sampleRate = tts.sampleRate()
        val bufLength = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val attr = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()
        track = AudioTrack(
            attr, format, bufLength, AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        track.play()
        Log.i("pichu", "TTS ready: sampleRate=$sampleRate speakers=${tts.numSpeakers()} sid=$sid")
    }

    override fun speak(text: String, onDone: () -> Unit) {
        exec.submit {
            try {
                // Generate the whole clip, then play it. (We avoid
                // generateWithCallback: its callback gets D8-desugared into a
                // synthetic lambda lacking the specialized invoke([F)Integer the
                // JNI looks up, which aborts the process. Replies are short, so
                // one-shot generation is fine.)
                val audio = tts.generate(text = text, sid = sid, speed = speed)
                val samples = audio.samples
                Log.i("pichu", "TTS generated ${samples.size} samples @${audio.sampleRate}Hz for '$text'")
                if (samples.isNotEmpty()) {
                    track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                }
            } catch (e: Throwable) {
                Log.e("pichu", "TTS generate failed", e)
            } finally {
                onDone()
            }
        }
    }

    override fun stop() {
        try {
            track.pause()
            track.flush()
            track.play()
        } catch (e: Throwable) {
            Log.w("pichu", "TTS stop failed", e)
        }
    }

    override fun release() {
        exec.shutdownNow()
        try {
            track.stop()
            track.release()
        } catch (_: Throwable) {
        }
        tts.release()
    }
}
