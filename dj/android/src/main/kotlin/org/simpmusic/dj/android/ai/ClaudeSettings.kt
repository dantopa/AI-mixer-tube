package org.simpmusic.dj.android.ai

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.simpmusic.dj.android.log.DjLog
import java.io.File

/** The Claude models "AI Mix" can ask, cheapest first, with their list prices (input / output per million tokens). */
enum class ClaudeModel(val id: String, val label: String, val price: String) {
    HAIKU("claude-haiku-5-5", "Haiku 5.5", "\$0.10 / \$0.50"),
    SONNET("claude-sonnet-5-5", "Sonnet 5.5", "\$2 / \$10"),
    OPUS("claude-opus-5-5", "Opus 5.5", "\$4 / \$20"),
}

/**
 * The owner's Claude API key and model choice for "AI Mix". The key is typed into the app (Settings -> AI DJ), never
 * shipped, logged or exported: it lives in a file under `noBackupFilesDir`, which Android leaves out of every backup and
 * device transfer on every version, and it leaves the phone only in the request to api.anthropic.com. The model is a
 * plain preference (`dj_claude`), Haiku by default (the owner's choice: a mix decision needs a few thousand tokens).
 */
object ClaudeSettings {
    /** [auto]: Claude chooses every mix by itself (once per pair), not only when ✨ is tapped. */
    data class State(
        val hasKey: Boolean = false,
        val keyHint: String? = null,
        val model: ClaudeModel = ClaudeModel.HAIKU,
        val auto: Boolean = false,
        /** "With tools" (the owner's experiment): Claude zooms, tries mixes on the planner and commits; else one answer. */
        val tools: Boolean = true,
        /** With [auto]: Claude also keeps the radio's upcoming tracks in mixing order by itself ("Order list" on its own). */
        val autoOrder: Boolean = true,
        /**
         * Pro mode (the owner: "no matter the tokens, with the whole toolbox"): maximum effort, up to 20 turns, and the
         * extra tools (a measured map of candidates, spectrograms of the real audio, the mix rendered and measured, web
         * search). Off by default; one switch turns it off again.
         */
        val pro: Boolean = false,
        /** The owner's own instructions, appended to Claude's system prompt (blank = none). */
        val instructions: String = "",
        /** Reasoning effort chosen by the owner: "" = the app's default (medium on Opus, max in Pro), else low..max. */
        val effort: String = "",
        /** The owner's own version of Claude's main prompt ("" = the app's [org.simpmusic.dj.ai.MixBrief.SYSTEM]). */
        val prompt: String = "",
    )

    private const val PREFS = "dj_claude"
    private const val PREF_MODEL = "model"
    private const val PREF_AUTO = "auto"
    private const val PREF_TOOLS = "tools"
    private const val PREF_AUTO_ORDER = "auto_order"
    private const val PREF_PRO = "pro"
    private const val PREF_INSTRUCTIONS = "instructions"
    private const val PREF_EFFORT = "effort"
    private const val PREF_PROMPT = "prompt"
    val EFFORTS = listOf("", "low", "medium", "high", "xhigh", "max")
    private const val MAX_PROMPT = 30_000

    /** Longest owner instruction text kept (it rides in every question). */
    const val MAX_INSTRUCTIONS = 4_000
    private const val KEY_FILE = "dj_claude_key"

    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var keyFile: File? = null
    @Volatile private var key: String? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun install(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        keyFile = File(context.noBackupFilesDir, KEY_FILE)
        key = runCatching { keyFile?.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()
        publish()
        DjLog.i(TAG, "claude: key ${if (key != null) "set" else "not set"}, model ${_state.value.model.id}")
    }

    /** Stores [apiKey] (blank = forget it). */
    fun setKey(apiKey: String?) {
        val k = apiKey?.trim()?.takeIf { it.isNotEmpty() }
        val f = keyFile ?: return
        runCatching {
            if (k == null) {
                f.delete()
            } else {
                val tmp = File(f.parentFile, "$KEY_FILE.tmp")
                tmp.writeText(k)
                if (!tmp.renameTo(f)) {
                    f.writeText(k)
                    tmp.delete()
                }
            }
        }.onFailure { DjLog.w(TAG, "claude: key not stored: ${it.javaClass.simpleName}") }
        key = k
        publish()
        DjLog.i(TAG, "claude: key ${if (k != null) "stored" else "forgotten"}")
    }

    fun setModel(model: ClaudeModel) {
        prefs?.edit()?.putString(PREF_MODEL, model.name)?.apply()
        publish()
        DjLog.i(TAG, "claude: model ${model.id}")
    }

    /** "Claude chooses every mix": off by default (each answer costs tokens), switched from Now Playing or Settings. */
    fun setAuto(on: Boolean) {
        prefs?.edit()?.putBoolean(PREF_AUTO, on)?.apply()
        publish()
        DjLog.i(TAG, "claude: auto ${if (on) "on" else "off"}")
    }

    fun setAutoOrder(on: Boolean) {
        prefs?.edit()?.putBoolean(PREF_AUTO_ORDER, on)?.apply()
        publish()
        DjLog.i(TAG, "claude: auto order ${if (on) "on" else "off"}")
    }

    fun setTools(on: Boolean) {
        prefs?.edit()?.putBoolean(PREF_TOOLS, on)?.apply()
        publish()
        DjLog.i(TAG, "claude: tools ${if (on) "on" else "off"}")
    }

    fun setPro(on: Boolean) {
        prefs?.edit()?.putBoolean(PREF_PRO, on)?.apply()
        publish()
        DjLog.i(TAG, "claude: pro ${if (on) "on" else "off"}")
    }

    fun setInstructions(text: String) {
        val t = text.trim().take(MAX_INSTRUCTIONS)
        prefs?.edit()?.putString(PREF_INSTRUCTIONS, t)?.apply()
        publish()
        DjLog.i(TAG, "claude: owner instructions ${if (t.isEmpty()) "cleared" else "set (${t.length} chars)"}")
    }

    fun setEffort(e: String) {
        val v = e.takeIf { it in EFFORTS } ?: ""
        prefs?.edit()?.putString(PREF_EFFORT, v)?.apply()
        publish()
        DjLog.i(TAG, "claude: effort ${v.ifEmpty { "default" }}")
    }

    /** [text] equal to the app's prompt (or blank) is stored as "" so later app updates of the prompt still reach it. */
    fun setPrompt(text: String) {
        val t = text.trim().take(MAX_PROMPT).takeUnless { it.isEmpty() || it == org.simpmusic.dj.ai.MixBrief.SYSTEM.trim() } ?: ""
        prefs?.edit()?.putString(PREF_PROMPT, t)?.apply()
        publish()
        DjLog.i(TAG, "claude: main prompt ${if (t.isEmpty()) "back to the app's" else "edited (${t.length} chars)"}")
    }

    internal fun apiKey(): String? = key

    private fun publish() {
        val model = prefs?.getString(PREF_MODEL, null)?.let { n -> ClaudeModel.entries.firstOrNull { it.name == n } } ?: ClaudeModel.HAIKU
        // only the last four characters ever reach the UI
        _state.value = State(hasKey = key != null, keyHint = key?.takeLast(4)?.let { "…$it" }, model = model, auto = prefs?.getBoolean(PREF_AUTO, false) ?: false, tools = prefs?.getBoolean(PREF_TOOLS, true) ?: true, autoOrder = prefs?.getBoolean(PREF_AUTO_ORDER, true) ?: true, pro = prefs?.getBoolean(PREF_PRO, false) ?: false, instructions = prefs?.getString(PREF_INSTRUCTIONS, "").orEmpty(), effort = prefs?.getString(PREF_EFFORT, "").orEmpty().takeIf { it in EFFORTS } ?: "", prompt = prefs?.getString(PREF_PROMPT, "").orEmpty())
    }

    private const val TAG = "ai"
}
