package org.simpmusic.dj.android.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.simpmusic.dj.ai.MixBrief
import org.simpmusic.dj.ai.MixTools
import org.simpmusic.dj.android.log.DjLog
import java.time.Duration
import java.util.Base64

/**
 * Asks Claude where to mix one pair, through the official Anthropic Java SDK (Messages API, structured output: the answer
 * is JSON that matches [MixBrief.SCHEMA]). Blocking: call it off the main thread. One client per key and model, rebuilt
 * when either changes.
 */
class ClaudeMixClient {
    /** Claude's answer as text (the JSON), with what it cost. */
    class Answer(
        val json: String,
        val inputTokens: Long,
        val outputTokens: Long,
        val model: ClaudeModel,
        val elapsedMs: Long,
        /** Tool mode: the committed points (already validated), how many turns and planner tries it took. */
        val committed: org.simpmusic.dj.model.AiMixPoints? = null,
        val turns: Int = 1,
        val tries: Int = 0,
        /** Prompt-cache tokens: read back (10 % of the input price) and written (125 %); [inputTokens] are the uncached rest. */
        val cacheReadTokens: Long = 0,
        val cacheWriteTokens: Long = 0,
    )

    @Volatile private var client: Pair<String, AnthropicClient>? = null

    private fun clientFor(apiKey: String): AnthropicClient {
        client?.takeIf { it.first == apiKey }?.let { return it.second }
        val c = AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(Duration.ofSeconds(TIMEOUT_S)).maxRetries(1).build()
        client = apiKey to c
        return c
    }

    /**
     * One mix decision. With [tools] Claude may zoom, try mixes on the planner and must commit (the tool loop below);
     * without, it answers once with JSON ([MixBrief.SCHEMA]). [image] (the fingerprint PNG) rides in the first message.
     */
    fun ask(brief: MixBrief.Brief, image: ByteArray? = null, tools: MixTools? = null): Result<Answer> {
        val apiKey = ClaudeSettings.apiKey() ?: return Result.failure(IllegalStateException("no Claude API key (Settings -> AI DJ)"))
        val model = ClaudeSettings.state.value.model
        val first = ArrayList<ContentBlockParam>()
        image?.let { first += ContentBlockParam.ofImage(Base64ImageSource.builder().data(Base64.getEncoder().encodeToString(it)).mediaType(Base64ImageSource.MediaType.IMAGE_PNG).build()) }
        first += ContentBlockParam.ofText(brief.user)
        val messages = arrayListOf(MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(first).build())
        val t0 = System.nanoTime()
        DjLog.i(TAG, "asking ${model.id}: brief ${brief.user.length} chars, image ${image?.size ?: 0} bytes, ${if (tools != null) "with tools" else "one answer"}, exit bars ${brief.exitFrom}..${brief.exitTo}")
        var inTok = 0L
        var outTok = 0L
        var cacheRead = 0L
        var cacheWrite = 0L
        val ephemeral = CacheControlEphemeral.builder().build()
        // the system prompt is the same for every pair: cached on its own, it is read back by the next pair's question
        val system = TextBlockParam.builder().text((if (tools != null) brief.system + MixTools.TOOL_RULES else brief.system) + OwnerLessons.block()).cacheControl(ephemeral).build()
        return try {
            val client = clientFor(apiKey)
            // a turn that ends on text with no tool call (a progress note, a plan announced but not taken) is nudged on, at
            // most CONTINUATIONS times, instead of failing the question: those turns count on top of MAX_TURNS
            val turns = if (tools != null) MixTools.MAX_TURNS + CONTINUATIONS else 1
            var nudges = 0
            for (turn in 1..turns) {
                val b =
                    MessageCreateParams.builder()
                        .model(model.id)
                        .maxTokens(MAX_TOKENS)
                        .systemOfTextBlockParams(listOf(system))
                        .messages(messages)
                // tool mode: every turn re-sends the brief, the picture and the turns before; automatic caching puts the
                // breakpoint on the last block, so each turn reads all of that back from the cache
                if (tools != null) b.cacheControl(ephemeral)
                if (tools != null) {
                    MixTools.SPECS.forEach { b.addTool(toolOf(it)) }
                    effortOf(model)?.let { b.outputConfig(OutputConfig.builder().effort(it).build()) }
                } else {
                    val schema = JsonOutputFormat.Schema.builder().apply { MixBrief.SCHEMA.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }.build()
                    b.outputConfig(OutputConfig.builder().format(JsonOutputFormat.builder().schema(schema).build()).apply { effortOf(model)?.let { effort(it) } }.build())
                }
                val m = client.messages().create(b.build())
                inTok += m.usage().inputTokens()
                outTok += m.usage().outputTokens()
                cacheRead += m.usage().cacheReadInputTokens().orElse(0L)
                cacheWrite += m.usage().cacheCreationInputTokens().orElse(0L)
                when (m.stopReason().orElse(null)) {
                    StopReason.REFUSAL -> return Result.failure(IllegalStateException("Claude declined the request${refusalOf(m)}"))
                    StopReason.MAX_TOKENS -> return Result.failure(IllegalStateException("Claude's answer was cut off"))
                    else -> {}
                }
                val elapsed = { (System.nanoTime() - t0) / 1_000_000 }
                if (tools == null) {
                    val text = m.content().mapNotNull { blk -> blk.text().orElse(null)?.text() }.joinToString("").trim()
                    if (text.isEmpty()) return Result.failure(IllegalStateException("Claude gave no answer"))
                    DjLog.i(TAG, "answer from ${model.id} in ${elapsed()} ms ($inTok in / $outTok out / $cacheRead cache read / $cacheWrite cache write tokens): $text")
                    return Result.success(Answer(text, inTok, outTok, model, elapsed(), cacheReadTokens = cacheRead, cacheWriteTokens = cacheWrite))
                }
                val uses = m.content().mapNotNull { blk -> blk.toolUse().orElse(null) }
                if (uses.isEmpty()) {
                    val said = m.content().mapNotNull { blk -> blk.text().orElse(null)?.text() }.joinToString(" ").take(200)
                    if (nudges >= CONTINUATIONS) return Result.failure(IllegalStateException("Claude stopped without committing a mix ($said)"))
                    nudges++
                    DjLog.i(TAG, "turn $turn: ended on text without a tool call, nudged on ($nudges/$CONTINUATIONS): $said")
                    messages += m.toParam()
                    messages += MessageParam.builder().role(MessageParam.Role.USER).content(CONTINUE).build()
                    continue
                }
                messages += m.toParam()
                val results = ArrayList<ContentBlockParam>()
                for (u in uses) {
                    val input = jsonOf(u._input().convert(Any::class.java)).toString()
                    when (val o = tools.call(u.name(), input)) {
                        is MixTools.Outcome.Committed -> {
                            val p = o.points
                            val json = """{"exit_ms":${p.exitMs},"entry_ms":${p.entryMs},"overlap_bars":${p.overlapBars},"why":${JsonPrimitive(p.why)}}"""
                            DjLog.i(TAG, "commit from ${model.id} after $turn turns and ${tools.tries} tries in ${elapsed()} ms ($inTok in / $outTok out / $cacheRead cache read / $cacheWrite cache write tokens): $input")
                            return Result.success(Answer(json, inTok, outTok, model, elapsed(), committed = p, turns = turn, tries = tools.tries, cacheReadTokens = cacheRead, cacheWriteTokens = cacheWrite))
                        }
                        is MixTools.Outcome.Result -> {
                            DjLog.i(TAG, "turn $turn: ${u.name()} $input -> ${o.text.lineSequence().firstOrNull()?.take(160)}")
                            results += ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(u.id()).content(o.text).isError(o.isError).build())
                        }
                    }
                }
                messages += MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(results).build()
            }
            Result.failure(IllegalStateException("no commit after ${MixTools.MAX_TURNS} turns"))
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

    /**
     * One structured-output question with no tools ("order the list"): [system] cached, [user] as the message, the answer
     * JSON matching [schema]. Blocking: call it off the main thread.
     */
    fun askJson(system: String, user: String, schema: Map<String, Any>, what: String): Result<Answer> {
        val apiKey = ClaudeSettings.apiKey() ?: return Result.failure(IllegalStateException("no Claude API key (Settings -> AI DJ)"))
        val model = ClaudeSettings.state.value.model
        val t0 = System.nanoTime()
        DjLog.i(TAG, "asking ${model.id} ($what): ${user.length} chars")
        return try {
            val format = JsonOutputFormat.Schema.builder().apply { schema.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }.build()
            val b =
                MessageCreateParams.builder()
                    .model(model.id)
                    .maxTokens(MAX_TOKENS)
                    .systemOfTextBlockParams(listOf(TextBlockParam.builder().text(system).cacheControl(CacheControlEphemeral.builder().build()).build()))
                    .addUserMessage(user)
                    .outputConfig(OutputConfig.builder().format(JsonOutputFormat.builder().schema(format).build()).apply { effortOf(model)?.let { effort(it) } }.build())
            val m = clientFor(apiKey).messages().create(b.build())
            when (m.stopReason().orElse(null)) {
                StopReason.REFUSAL -> return Result.failure(IllegalStateException("Claude declined the request${refusalOf(m)}"))
                StopReason.MAX_TOKENS -> return Result.failure(IllegalStateException("Claude's answer was cut off"))
                else -> {}
            }
            val text = m.content().mapNotNull { blk -> blk.text().orElse(null)?.text() }.joinToString("").trim()
            if (text.isEmpty()) return Result.failure(IllegalStateException("Claude gave no answer"))
            val elapsed = (System.nanoTime() - t0) / 1_000_000
            val u = m.usage()
            DjLog.i(TAG, "answer from ${model.id} ($what) in $elapsed ms (${u.inputTokens()} in / ${u.outputTokens()} out tokens): $text")
            Result.success(
                Answer(
                    text, u.inputTokens(), u.outputTokens(), model, elapsed,
                    cacheReadTokens = u.cacheReadInputTokens().orElse(0L), cacheWriteTokens = u.cacheCreationInputTokens().orElse(0L),
                ),
            )
        } catch (e: Exception) {
            Result.failure(failureOf(e, model))
        }
    }

    private fun failureOf(e: Exception, model: ClaudeModel): Exception =
        when (e) {
            is UnauthorizedException -> IllegalStateException("the API key was refused (401)")
            is PermissionDeniedException -> IllegalStateException("the API key may not use ${model.label} (403)")
            is RateLimitException -> IllegalStateException("rate limited, try again in a minute (429)")
            is AnthropicServiceException -> IllegalStateException("Claude answered ${e.statusCode()}: ${e.message?.take(120)}")
            is AnthropicIoException -> IllegalStateException("no connection to Claude (${e.javaClass.simpleName})")
            else -> IllegalStateException("${e.javaClass.simpleName}: ${e.message?.take(120)}")
        }

    /** " (category: explanation)" of a safeguard decline, or "". */
    private fun refusalOf(m: com.anthropic.models.messages.Message): String {
        val d = m.stopDetails().orElse(null) ?: return ""
        val c = d.category().map { it.toString() }.orElse("unknown")
        return " ($c${d.explanation().map { ": " + it.take(120) }.orElse("")})"
    }

    /**
     * Effort, set explicitly only where it is documented: Opus 5.5 at medium, its default (Anthropic's guide: medium on
     * Opus 5.5 matches Opus 5 at high; set it explicitly and lower it before prompting for less thinking). Haiku and Sonnet
     * keep their own defaults (null: the field is not sent).
     */
    private fun effortOf(model: ClaudeModel): OutputConfig.Effort? = if (model == ClaudeModel.OPUS) OutputConfig.Effort.MEDIUM else null

    private fun toolOf(spec: MixTools.Spec): Tool {
        @Suppress("UNCHECKED_CAST")
        val props = spec.schema["properties"] as Map<String, Any>
        val propsBuilder = Tool.InputSchema.Properties.builder()
        props.forEach { (k, v) -> propsBuilder.putAdditionalProperty(k, JsonValue.from(v)) }
        @Suppress("UNCHECKED_CAST")
        return Tool.builder()
            .name(spec.name)
            .description(spec.description)
            .strict(true)
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(propsBuilder.build())
                    .required(spec.schema["required"] as List<String>)
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build(),
            )
            .build()
    }

    /** A Jackson-converted value (maps, lists, numbers, strings) as kotlinx JSON, for [MixTools.call]. */
    private fun jsonOf(v: Any?): JsonElement =
        when (v) {
            null -> JsonNull
            is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to jsonOf(x) })
            is List<*> -> JsonArray(v.map { jsonOf(it) })
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }

    /** Cost of [a] in US dollars at the model's list price. */
    fun costUsd(a: Answer): Double {
        val (inPerM, outPerM) =
            when (a.model) {
                ClaudeModel.HAIKU -> 0.10 to 0.50
                ClaudeModel.SONNET -> 2.0 to 10.0
                ClaudeModel.OPUS -> 4.0 to 20.0
            }
        return (a.inputTokens * inPerM + a.cacheReadTokens * inPerM * 0.1 + a.cacheWriteTokens * inPerM * 1.25 + a.outputTokens * outPerM) / 1_000_000.0
    }

    private companion object {
        const val TAG = "ai"
        /**
         * The answer is a small JSON or a tool call; the rest is room for the model's thinking, which counts toward
         * max_tokens even when it is not returned. 8 000 was sized for thinking off; only the tokens used are billed.
         */
        const val MAX_TOKENS = 32_000L

        /** Text-only turns nudged on before the question fails (Anthropic: two or three, so a stuck run still ends). */
        const val CONTINUATIONS = 2
        const val CONTINUE =
            "You ended the turn without a tool call, so nothing was committed and the mix is still owed. Continue: try_mix " +
                "what you were about to try, or commit_mix the best candidate so far. If something blocks every candidate, " +
                "commit the cleanest one and say why in its why."
        const val TIMEOUT_S = 90L
    }
}
