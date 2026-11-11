package org.simpmusic.dj.android.stems

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.simpmusic.dj.android.log.DjLog
import java.io.File

/**
 * The owner's stems server (`dj/stems-server`, Demucs on their own computer or cloud machine): its address, its token and
 * the switch. The token is a file under `noBackupFilesDir` like the Claude key: never in a backup, the log or an export.
 */
object StemsSettings {
    data class State(
        val enabled: Boolean = false,
        val url: String = "",
        val hasToken: Boolean = false,
        /** Upload audio only on an unmetered network (Wi-Fi): about 10 MB per track. */
        val unmeteredOnly: Boolean = true,
    ) {
        val usable: Boolean get() = enabled && url.isNotBlank()
    }

    private const val PREFS = "dj_stems"
    private const val PREF_ENABLED = "enabled"
    private const val PREF_URL = "url"
    private const val PREF_UNMETERED = "unmetered_only"
    private const val TOKEN_FILE = "dj_stems_token"
    private const val TAG = "stems"

    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var tokenFile: File? = null
    @Volatile private var token: String? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun install(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        tokenFile = File(context.noBackupFilesDir, TOKEN_FILE)
        token = runCatching { tokenFile?.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()
        publish()
    }

    fun setEnabled(on: Boolean) {
        prefs?.edit()?.putBoolean(PREF_ENABLED, on)?.apply()
        publish()
        DjLog.i(TAG, "stems server ${if (on) "on" else "off"}")
    }

    fun setUrl(url: String) {
        val u = url.trim().trimEnd('/')
        prefs?.edit()?.putString(PREF_URL, u)?.apply()
        publish()
        DjLog.i(TAG, "stems server address ${if (u.isEmpty()) "cleared" else "set"}")
    }

    fun setUnmeteredOnly(on: Boolean) {
        prefs?.edit()?.putBoolean(PREF_UNMETERED, on)?.apply()
        publish()
    }

    /** Stores [t] (blank = forget it). */
    fun setToken(t: String?) {
        val v = t?.trim()?.takeIf { it.isNotEmpty() }
        val f = tokenFile ?: return
        runCatching { if (v == null) f.delete() else f.writeText(v) }
        token = v
        publish()
        DjLog.i(TAG, "stems token ${if (v != null) "stored" else "forgotten"}")
    }

    internal fun token(): String? = token

    private fun publish() {
        val p = prefs
        _state.value =
            State(
                enabled = p?.getBoolean(PREF_ENABLED, false) ?: false,
                url = p?.getString(PREF_URL, "").orEmpty(),
                hasToken = token != null,
                unmeteredOnly = p?.getBoolean(PREF_UNMETERED, true) ?: true,
            )
    }
}
