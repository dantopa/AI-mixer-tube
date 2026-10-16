package org.simpmusic.dj.android.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import org.simpmusic.dj.ai.MixBrief
import org.simpmusic.dj.android.log.DjLog
import java.time.Duration

/**
 * Asks Claude where to mix one pair, through the official Anthropic Java SDK (Messages API, structured output: the answer
 * is JSON that matches [MixBrief.SCHEMA]). Blocking: call it off the main thread. One client per key and model, rebuilt
 * when either changes.
 */
class ClaudeMixClient {
    /** Claude's answer as text (the JSON), with what it cost. */
    class Answer(val json: String, val inputTokens: Long, val outputTokens: Long, val model: ClaudeModel, val elapsedMs: Long)

    @Volatile private var client: Pair<String, AnthropicClient>? = null

    private fun clientFor(apiKey: String): AnthropicClient {
        client?.takeIf { it.first == apiKey }?.let { return it.second }
        val c = AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(Duration.ofSeconds(TIMEOUT_S)).maxRetries(1).build()
        client = apiKey to c
        return c
    }

    fun ask(brief: MixBrief.Brief): Result<Answer> {
        val apiKey = ClaudeSettings.apiKey() ?: return Result.failure(IllegalStateException("no Claude API key (Settings -> AI DJ)"))
        val model = ClaudeSettings.state.value.model
        val schema = JsonOutputFormat.Schema.builder().apply { MixBrief.SCHEMA.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }.build()
        val params =
            MessageCreateParams.builder()
                .model(model.id)
                .maxTokens(MAX_TOKENS)
                .system(brief.system)
                .addUserMessage(brief.user)
                .outputConfig(OutputConfig.builder().format(JsonOutputFormat.builder().schema(schema).build()).build())
                .build()
        val t0 = System.nanoTime()
        DjLog.i(TAG, "asking ${model.id}: brief ${brief.user.length} chars, exit bars ${brief.exitFrom}..${brief.exitTo}")
        return try {
            val m = clientFor(apiKey).messages().create(params)
            val elapsed = (System.nanoTime() - t0) / 1_000_000
            val stop = m.stopReason().orElse(null)
            when (stop) {
                StopReason.REFUSAL -> return Result.failure(IllegalStateException("Claude declined the request"))
                StopReason.MAX_TOKENS -> return Result.failure(IllegalStateException("Claude's answer was cut off"))
                else -> {}
            }
            val text = m.content().mapNotNull { b -> b.text().orElse(null)?.text() }.joinToString("").trim()
            if (text.isEmpty()) return Result.failure(IllegalStateException("Claude gave no answer"))
            val u = m.usage()
            DjLog.i(TAG, "answer from ${model.id} in $elapsed ms (${u.inputTokens()} in / ${u.outputTokens()} out tokens): $text")
            Result.success(Answer(text, u.inputTokens(), u.outputTokens(), model, elapsed))
        } catch (e: UnauthorizedException) {
            Result.failure(IllegalStateException("the API key was refused (401)"))
        } catch (e: PermissionDeniedException) {
            Result.failure(IllegalStateException("the API key may not use ${model.label} (403)"))
        } catch (e: RateLimitException) {
            Result.failure(IllegalStateException("rate limited, try again in a minute (429)"))
        } catch (e: AnthropicServiceException) {
            Result.failure(IllegalStateException("Claude answered ${e.statusCode()}: ${e.message?.take(120)}"))
        } catch (e: AnthropicIoException) {
            Result.failure(IllegalStateException("no connection to Claude (${e.javaClass.simpleName})"))
        } catch (e: Exception) {
            Result.failure(IllegalStateException("${e.javaClass.simpleName}: ${e.message?.take(120)}"))
        }
    }

    /** Cost of [a] in US dollars at the model's list price. */
    fun costUsd(a: Answer): Double {
        val (inPerM, outPerM) =
            when (a.model) {
                ClaudeModel.HAIKU -> 0.10 to 0.50
                ClaudeModel.SONNET -> 2.0 to 10.0
                ClaudeModel.OPUS -> 4.0 to 20.0
            }
        return (a.inputTokens * inPerM + a.outputTokens * outPerM) / 1_000_000.0
    }

    private companion object {
        const val TAG = "ai"
        /** The answer is a small JSON; the rest is room for the model's own (adaptive) thinking. */
        const val MAX_TOKENS = 8_000L
        const val TIMEOUT_S = 60L
    }
}
