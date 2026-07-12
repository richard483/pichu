package com.nephren.pichu

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.getEndpointConfig
import com.k2fsa.sherpa.onnx.getModelConfig
import com.k2fsa.sherpa.onnx.getVadModelConfig
import java.io.File
import kotlin.concurrent.thread

private const val TAG = "pichu"
private const val REQUEST_RECORD_AUDIO_PERMISSION = 200

// ASR model (Phase 1): English Nemotron streaming, type 32 =
// sherpa-onnx-nemotron-speech-streaming-en-0.6b-560ms-int8-2026-04-25.
// Swapped for the ja-JP multilingual export in Phase 5.
private const val MODEL_TYPE = 32
private const val FEATURE_DIM = 128 // Nemotron uses 128-dim mel features
private const val VAD_MODEL = "silero_vad.onnx"

// Trailing silence fed to the recognizer at end-of-turn so the streaming model
// has enough right-context to emit the last word (which is otherwise dropped).
// ~0.8s comfortably covers the 560ms chunk lookahead.
private const val TAIL_PADDING_SEC = 0.8

// How long you must pause before Silero VAD ends a turn. The default (0.25s) cuts
// mid-sentence on natural comma pauses ("done, but" -> loses "but"). 0.6s lets you
// breathe without ending the turn; raise it if you still get cut off, lower it for
// snappier turn-taking.
private const val VAD_MIN_SILENCE_SEC = 0.6f

// Kokoro TTS (Japanese). v1.0 Japanese voices are sid 37..41
// (37 jf_alpha, 38 jf_gongitsune, 39 jf_nezumi, 40 jf_tebukuro, 41 jm_kumo).
private const val KOKORO_DIR = "kokoro-int8-multi-lang-v1_0"
private const val TTS_SID = 37

class MainActivity : AppCompatActivity() {
    private val permissions: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)

    private var recognizer: OnlineRecognizer? = null
    private var vad: Vad? = null
    private var tts: TtsEngine? = null
    private var audioRecord: AudioRecord? = null
    private lateinit var recordButton: Button
    private lateinit var textView: TextView
    private var recordingThread: Thread? = null

    private val audioSource = MediaRecorder.AudioSource.MIC
    private val sampleRateInHz = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    // --- Phase 3: LLM (stubbed behind an interface) -------------------------
    private val llm: LlmClient = StubLlmClient()
    private val turns = mutableListOf<ChatTurn>() // conversation history for the LLM
    private var llmCancel: LlmClient.Cancellable? = null
    private var llmBusy = false

    // --- Display model. Mutated ONLY on the main thread (all writes happen
    // inside runOnUiThread), so no locking is needed. -----------------------
    private val conversation = StringBuilder() // committed "You: …\nJA: …" lines
    private var statusLine = "Idle"            // Idle -> Listening -> Thinking -> Speaking
    private var currentPartial = ""            // ASR partial in progress
    private var replyBuffer = ""               // LLM reply in progress

    @Volatile
    private var isRecording: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)

        recordButton = findViewById(R.id.record_button)
        textView = findViewById(R.id.my_text)
        textView.movementMethod = ScrollingMovementMethod()

        // On-device Japanese TTS via Android's system engine (sherpa-onnx has no
        // working Japanese voice). Init is async; start it now so it's ready by
        // the time the first reply arrives. If Japanese voice data is missing the
        // reply still shows as text, just isn't spoken.
        tts = AndroidTtsEngine(this) { available ->
            if (!available) {
                Log.w(TAG, "Japanese system TTS unavailable (voice data not installed?)")
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this,
                        "Japanese TTS voice not installed — replies show as text only. " +
                            "Install it in Settings ▸ Text-to-speech.",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }

        // Loading the ASR + TTS models takes several seconds; do it off the main
        // thread so we never risk an ANR, and disable the button until ready.
        recordButton.isEnabled = false
        statusLine = "Loading models…"
        renderNow()
        Log.i(TAG, "Start to initialize models")
        thread(true) {
            val ok = initModels()
            runOnUiThread {
                Log.i(TAG, "Finished initializing models, ok=$ok")
                recordButton.isEnabled = ok
                if (ok) recordButton.setOnClickListener { onclick() }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = requestCode == REQUEST_RECORD_AUDIO_PERMISSION &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (!granted) Log.e(TAG, "Audio record permission was denied")
        else Log.i(TAG, "Audio record permission granted")
    }

    override fun onDestroy() {
        isRecording = false
        llmCancel?.cancel()
        tts?.release()
        super.onDestroy()
    }

    /**
     * Models live under the app's external files dir (adb-pushable):
     *   models/sherpa-onnx-nemotron-.../{encoder,decoder,joiner}.int8.onnx, tokens.txt
     *   models/silero_vad.onnx
     */
    private fun modelRoot(): File = File(getExternalFilesDir(null), "models")

    private fun initModels(): Boolean {
        val root = modelRoot()

        val modelConfig = getModelConfig(type = MODEL_TYPE)
        if (modelConfig == null) {
            runOnUiThread { textView.text = "Internal error: unknown MODEL_TYPE=$MODEL_TYPE" }
            return false
        }

        val transducer = modelConfig.transducer
        transducer.encoder = File(root, transducer.encoder).absolutePath
        transducer.decoder = File(root, transducer.decoder).absolutePath
        transducer.joiner = File(root, transducer.joiner).absolutePath
        modelConfig.tokens = File(root, modelConfig.tokens).absolutePath
        val vadPath = File(root, VAD_MODEL).absolutePath

        val missing = listOf(
            transducer.encoder, transducer.decoder, transducer.joiner, modelConfig.tokens, vadPath,
        ).filter { !File(it).exists() }

        if (missing.isNotEmpty()) {
            Log.e(TAG, "Model files missing: $missing")
            runOnUiThread {
                textView.text = buildString {
                    append("Model(s) not found.\n\nExpected these files:\n")
                    missing.forEach { append("  • $it\n") }
                    append("\nSee README.md (model setup).")
                }
            }
            return false
        }

        modelConfig.numThreads = 4

        val asrConfig = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = sampleRateInHz, featureDim = FEATURE_DIM),
            modelConfig = modelConfig,
            endpointConfig = getEndpointConfig(),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )

        return try {
            recognizer = OnlineRecognizer(assetManager = null, config = asrConfig)

            val vadConfig = getVadModelConfig(type = 0)!! // Silero VAD
            vadConfig.sileroVadModelConfig.model = vadPath
            vadConfig.sileroVadModelConfig.minSilenceDuration = VAD_MIN_SILENCE_SEC
            vad = Vad(assetManager = null, config = vadConfig)

            runOnUiThread {
                statusLine = "Idle"
                renderNow()
            }
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to create models", e)
            runOnUiThread { textView.text = "Failed to load models:\n${e.message}" }
            false
        }
    }

    private fun onclick() {
        if (!isRecording) {
            if (!initMicrophone()) {
                Log.e(TAG, "Failed to initialize microphone")
                return
            }
            audioRecord!!.startRecording()
            recordButton.setText(R.string.stop)
            isRecording = true
            // Fresh conversation.
            llmCancel?.cancel()
            llmCancel = null
            llmBusy = false
            conversation.setLength(0)
            turns.clear()
            currentPartial = ""
            replyBuffer = ""
            statusLine = "Listening…"
            vad?.reset()
            renderNow()
            recordingThread = thread(true) { processSamples() }
            Log.i(TAG, "Started recording")
        } else {
            isRecording = false
            audioRecord!!.stop()
            audioRecord!!.release()
            audioRecord = null
            llmCancel?.cancel()
            llmCancel = null
            tts?.stop()
            llmBusy = false
            recordButton.setText(R.string.start)
            statusLine = "Idle"
            renderNow()
            Log.i(TAG, "Stopped recording")
        }
    }

    /**
     * Hybrid loop: feed each 32 ms chunk to BOTH the streaming recognizer (live
     * partial) and Silero VAD (turn boundary). A completed VAD segment == the user
     * finished speaking -> the recognizer's current text is the final turn.
     */
    private fun processSamples() {
        val recognizer = this.recognizer ?: return
        val vad = this.vad ?: return
        val stream = recognizer.createStream()

        val buffer = ShortArray(512) // 512 samples == Silero VAD window
        var speechActive = false

        while (isRecording) {
            val ret = audioRecord?.read(buffer, 0, buffer.size) ?: 0
            if (ret <= 0) {
                if (ret < 0) Log.e(TAG, "AudioRecord.read error code=$ret")
                continue
            }
            val samples = FloatArray(ret) { buffer[it] / 32768.0f }

            stream.acceptWaveform(samples, sampleRate = sampleRateInHz)
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            var partial = recognizer.getResult(stream).text

            vad.acceptWaveform(samples)
            val speechNow = vad.isSpeechDetected()
            if (speechNow) speechActive = true

            var finalizedThisChunk = false
            while (!vad.empty()) {
                vad.pop()
                // Silero VAD ends a turn after ~0.25s of silence, but the streaming
                // Nemotron model needs more trailing audio (right-context) to commit
                // its last word. Feed tail padding + decode so the final word isn't
                // dropped, THEN read the result.
                val tail = FloatArray((TAIL_PADDING_SEC * sampleRateInHz).toInt())
                stream.acceptWaveform(tail, sampleRate = sampleRateInHz)
                while (recognizer.isReady(stream)) recognizer.decode(stream)

                val finalText = recognizer.getResult(stream).text
                recognizer.reset(stream)
                speechActive = false
                if (finalText.isNotBlank()) onFinalTranscript(finalText.trim())
                partial = ""
                finalizedThisChunk = true
            }

            if (finalizedThisChunk || partial.isNotBlank() || speechNow) {
                val p = partial
                runOnUiThread {
                    currentPartial = p
                    renderNow()
                }
            }
        }
        stream.release()
    }

    /**
     * A completed user turn: record it, then ask the LLM for a Japanese reply and
     * stream it in. (Phase 5 will add proper turn-taking / barge-in; for now, if a
     * reply is still streaming we just record the new user turn without interrupting.)
     */
    private fun onFinalTranscript(text: String) {
        runOnUiThread {
            conversation.append("You: ").append(text).append('\n')
            turns.add(ChatTurn(Role.USER, text))
            currentPartial = ""

            if (llmBusy) {
                renderNow()
                return@runOnUiThread
            }

            llmBusy = true
            replyBuffer = ""
            statusLine = "Thinking…"
            renderNow()

            llmCancel = llm.streamReply(
                history = turns.toList(),
                onToken = { chunk ->
                    runOnUiThread {
                        replyBuffer += chunk
                        statusLine = "Thinking…"
                        renderNow()
                    }
                },
                onComplete = { full ->
                    runOnUiThread {
                        conversation.append("JA:  ").append(full).append('\n')
                        turns.add(ChatTurn(Role.ASSISTANT, full))
                        replyBuffer = ""
                        statusLine = "Speaking…"
                        renderNow()
                    }
                    // Speak the reply aloud; only free the turn once playback ends.
                    val engine = tts
                    if (engine != null) {
                        engine.speak(full) {
                            runOnUiThread {
                                llmBusy = false
                                statusLine = if (isRecording) "Listening…" else "Idle"
                                renderNow()
                            }
                        }
                    } else {
                        runOnUiThread {
                            llmBusy = false
                            statusLine = if (isRecording) "Listening…" else "Idle"
                            renderNow()
                        }
                    }
                },
                onError = { e ->
                    runOnUiThread {
                        Log.e(TAG, "LLM error", e)
                        conversation.append("JA:  [reply error: ${e.message}]\n")
                        replyBuffer = ""
                        llmBusy = false
                        statusLine = if (isRecording) "Listening…" else "Idle"
                        renderNow()
                    }
                },
            )
        }
    }

    /** Composes the whole screen. MUST be called on the main thread. */
    private fun renderNow() {
        val sb = StringBuilder()
        sb.append('[').append(statusLine).append("]\n\n")
        sb.append(conversation)
        if (currentPartial.isNotBlank()) sb.append("You… ").append(currentPartial).append('\n')
        if (replyBuffer.isNotBlank()) sb.append("JA…  ").append(replyBuffer)
        textView.text = sb.toString()
    }

    private fun initMicrophone(): Boolean {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)
            return false
        }

        val numBytes = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)
        val rec = AudioRecord(
            audioSource, sampleRateInHz, channelConfig, audioFormat,
            numBytes * 2
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize (state=${rec.state})")
            rec.release()
            return false
        }
        audioRecord = rec
        return true
    }
}
