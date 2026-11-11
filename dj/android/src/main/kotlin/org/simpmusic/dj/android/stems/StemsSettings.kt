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
        /** Where Demucs runs: the owner's own server, or Replicate (a cloud GPU, paid per run). */
        val backend: Backend = Backend.REPLICATE,
        val hasReplicateToken: Boolean = false,
    ) {
        val usable: Boolean
            get() = enabled && when (backend) {
                Backend.SERVER -> url.isNotBlank()
                Backend.REPLICATE -> hasReplicateToken
            }
    }

    enum class Backend { SERVER, REPLICATE }

    private const val PREFS = "dj_stems"
    private const val PREF_ENABLED = "enabled"
    private const val PREF_URL = "url"
    private const val PREF_UNMETERED = "unmetered_only"
    private const val TOKEN_FILE = "dj_stems_token"
    private const val PREF_BACKEND = "backend"
    private const val REPLICATE_FILE = "dj_replicate_token"
    private const val TAG = "stems"

    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var tokenFile: File? = null
    @Volatile private var token: String? = null
    @Volatile private var replicateFile: File? = null
    @Volatile private var replicate: String? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun install(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        tokenFile = File(context.noBackupFilesDir, TOKEN_FILE)
        token = runCatching { tokenFile?.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()
        replicateFile = File(context.noBackupFilesDir, REPLICATE_FILE)
        replicate = runCatching { replicateFile?.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()
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

    fun setBackend(b: Backend) {
        prefs?.edit()?.putString(PREF_BACKEND, b.name)?.apply()
        publish()
        DjLog.i(TAG, "stems backend ${b.name.lowercase()}")
    }

    /** Stores the Replicate API token (blank = forget it). A file under noBackupFilesDir, never logged or exported. */
    fun setReplicateToken(t: String?) {
        val v = t?.trim()?.takeIf { it.isNotEmpty() }
        val f = replicateFile ?: return
        runCatching { if (v == null) f.delete() else f.writeText(v) }
        replicate = v
        publish()
        DjLog.i(TAG, "replicate token ${if (v != null) "stored" else "forgotten"}")
    }

    internal fun replicateToken(): String? = replicate

    private fun publish() {
        val p = prefs
        _state.value =
            State(
                enabled = p?.getBoolean(PREF_ENABLED, false) ?: false,
                url = p?.getString(PREF_URL, "").orEmpty(),
                hasToken = token != null,
                unmeteredOnly = p?.getBoolean(PREF_UNMETERED, true) ?: true,
                backend = p?.getString(PREF_BACKEND, null)?.let { n -> Backend.entries.firstOrNull { it.name == n } } ?: Backend.REPLICATE,
                hasReplicateToken = replicate != null,
            )
    }
}
