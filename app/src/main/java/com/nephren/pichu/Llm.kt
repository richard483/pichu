package com.nephren.pichu

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Conversation history entry. USER = the learner's (transcribed) speech,
 * ASSISTANT = the Japanese reply.
 */
enum class Role { USER, ASSISTANT }
data class ChatTurn(val role: Role, val text: String)

/**
 * System prompt for the real LLM adapter (Phase 3). The stub ignores it.
 * TODO(you): tune the learner level / persona to taste.
 */
const val SYSTEM_PROMPT =
    "あなたは日本語の会話練習パートナーです。相手は日本語の学習者です。" +
        "やさしい日本語で、短く（1〜2文）、自然に返答してください。" +
        "難しい語彙は避け、会話が続くように質問を一つ加えてください。"

/**
 * Streaming LLM client. Implementations MUST invoke the callbacks on a background
 * thread (never block the caller). This is the single seam the whole app talks to;
 * swap [StubLlmClient] for a real OkHttp/Retrofit adapter without touching the UI.
 */
interface LlmClient {
    /**
     * Generates a Japanese reply to the latest user turn, given [history]
     * (most recent last, including the latest user turn).
     *
     * @param onToken fired per streamed chunk (background thread)
     * @param onComplete fired once with the full reply text (background thread)
     * @param onError fired on failure (background thread)
     * @return a handle to cancel the in-flight request
     */
    fun streamReply(
        history: List<ChatTurn>,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Throwable) -> Unit,
    ): Cancellable

    fun interface Cancellable {
        fun cancel()
    }
}

/**
 * Offline fake: returns a canned Japanese reply, streamed a few characters at a
 * time so the UI pipeline (and later, Phase 4 TTS) behaves like the real thing.
 * No network. Replace with your real API adapter when ready.
 */
class StubLlmClient : LlmClient {
    private val cannedReplies = listOf(
        "そうですね。もう少し詳しく教えてください。",
        "いいですね！それはいつのことですか？",
        "なるほど、面白いですね。今日は何をしましたか？",
        "わかりました。ほかに話したいことはありますか？",
        "すごいですね！どうしてそう思いましたか？",
    )
    private var counter = 0

    override fun streamReply(
        history: List<ChatTurn>,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Throwable) -> Unit,
    ): LlmClient.Cancellable {
        val reply = cannedReplies[counter % cannedReplies.size]
        counter++

        val cancelled = AtomicBoolean(false)
        val worker = thread(start = true) {
            try {
                // Simulate "thinking" latency, then stream in small chunks.
                Thread.sleep(400)
                val sb = StringBuilder()
                var i = 0
                while (i < reply.length && !cancelled.get()) {
                    val end = minOf(i + 2, reply.length) // 2 chars per chunk
                    val chunk = reply.substring(i, end)
                    sb.append(chunk)
                    onToken(chunk)
                    i = end
                    Thread.sleep(55)
                }
                if (!cancelled.get()) onComplete(sb.toString())
            } catch (e: InterruptedException) {
                Log.i("pichu", "Stub LLM cancelled")
            } catch (e: Throwable) {
                if (!cancelled.get()) onError(e)
            }
        }

        return LlmClient.Cancellable {
            cancelled.set(true)
            worker.interrupt()
        }
    }
}
