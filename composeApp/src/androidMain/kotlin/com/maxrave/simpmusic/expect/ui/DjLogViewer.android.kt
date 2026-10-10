package com.maxrave.simpmusic.expect.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.simpmusic.ui.icon.Close
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.theme.typo
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.simpmusic.dj.android.DjHooks
import org.simpmusic.dj.android.diag.DjDiagnostics
import org.simpmusic.dj.android.diag.ProbeStep
import org.simpmusic.dj.android.log.DjLog
import org.simpmusic.dj.android.scheduler.AnalysisStatus
import org.simpmusic.dj.android.scheduler.DjAnalysisScheduler
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.ai_dj_diag_errors
import simpmusic.composeapp.generated.resources.ai_dj_diag_next
import simpmusic.composeapp.generated.resources.ai_dj_diag_playing
import simpmusic.composeapp.generated.resources.dj_log_clear
import simpmusic.composeapp.generated.resources.dj_log_copy_all
import simpmusic.composeapp.generated.resources.dj_log_copied
import simpmusic.composeapp.generated.resources.dj_log_empty
import simpmusic.composeapp.generated.resources.dj_log_export_analyses
import simpmusic.composeapp.generated.resources.dj_log_nothing_playing
import simpmusic.composeapp.generated.resources.dj_log_run_now
import simpmusic.composeapp.generated.resources.dj_log_self_check
import simpmusic.composeapp.generated.resources.dj_log_share
import simpmusic.composeapp.generated.resources.dj_log_share_title
import simpmusic.composeapp.generated.resources.dj_log_title
import java.io.File

/** What the viewer's header needs to know about the playing and the next track. */
private data class TrackIds(val current: String?, val next: String?)

/**
 * Full-screen DJ log: a per-track status summary on top, the actions (copy, share, clear, run the analysis now,
 * self-check) and the live tail of the log below (monospace, follows the end unless the user scrolls up).
 */
@Composable
fun DjLogViewerDialog(onDismiss: () -> Unit) {
    val hooks = koinInject<DjHooks>()
    val diagnostics = koinInject<DjDiagnostics>()
    val scheduler = koinInject<DjAnalysisScheduler>()
    val handler = koinInject<MediaPlayerHandler>()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    val debug by hooks.debug.collectAsStateWithLifecycle()
    val steps by diagnostics.steps.collectAsStateWithLifecycle()
    val probeRunning by diagnostics.running.collectAsStateWithLifecycle()
    val selfCheck by diagnostics.selfCheckResults.collectAsStateWithLifecycle()
    val nowPlaying by handler.nowPlaying.collectAsStateWithLifecycle()
    val queue by handler.queueData.collectAsStateWithLifecycle()
    val version by DjLog.version.collectAsStateWithLifecycle()
    val recentErrors by scheduler.state.collectAsStateWithLifecycle()

    val ids =
        remember(nowPlaying, queue) {
            val current = nowPlaying?.mediaId?.removePrefix(com.maxrave.common.MERGING_DATA_TYPE.VIDEO)
            val list = queue?.data?.listTracks.orEmpty()
            TrackIds(current, list.getOrNull(handler.currentOrderIndex() + 1)?.videoId?.takeIf { it != current })
        }
    // Live, and independent of the engine: what the scheduler says about each of the two tracks, once a second.
    val statuses by produceState<Pair<AnalysisStatus?, AnalysisStatus?>>(null to null, ids) {
        while (true) {
            value = ids.current?.let { scheduler.statusOf(it) } to ids.next?.let { scheduler.statusOf(it) }
            delay(1000)
        }
    }

    val lines = remember(version) { DjLog.lines() }
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            val info = listState.layoutInfo
            follow = (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(lines.size, follow) {
        if (follow && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    val copiedText = stringResource(Res.string.dj_log_copied)
    val shareTitle = stringResource(Res.string.dj_log_share_title)
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF101010)) {
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(stringResource(Res.string.dj_log_title), style = typo().titleMedium, color = Color.White, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { androidx.compose.foundation.Image(SimpIcons.Close, contentDescription = null, colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White)) }
                }
                // ---- per-track status ----
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 6.dp)) {
                    Text(
                        stringResource(Res.string.ai_dj_diag_playing, if (ids.current == null) stringResource(Res.string.dj_log_nothing_playing) else "${ids.current}: ${statuses.first.label()}"),
                        style = typo().bodySmall,
                        color = Color.White,
                    )
                    if (ids.next != null) {
                        Text(stringResource(Res.string.ai_dj_diag_next, "${ids.next}: ${statuses.second.label()}"), style = typo().bodySmall, color = Color.White)
                    }
                    Text("engine: ${debug.summary()}", style = typo().bodySmall, color = Color(0xFFB0B0B0))
                    if (recentErrors.recentErrors.isNotEmpty()) {
                        Text(stringResource(Res.string.ai_dj_diag_errors, recentErrors.recentErrors.joinToString(" | ")), style = typo().bodySmall, color = Color(0xFFFF8A80))
                    }
                    steps.forEach { s ->
                        val mark =
                            when (s.state) {
                                ProbeStep.State.RUNNING -> "…"
                                ProbeStep.State.OK -> "OK"
                                ProbeStep.State.FAILED -> "FAILED"
                            }
                        Text("${s.name}: $mark ${s.detail} ${if (s.ms > 0) "(${s.ms} ms)" else ""}", style = typo().bodySmall, color = if (s.state == ProbeStep.State.FAILED) Color(0xFFFF8A80) else Color(0xFF9CCC65))
                    }
                    selfCheck.forEach { r ->
                        Text("${r.name}: ${if (r.ok) "PASS" else "FAIL"} ${r.detail}", style = typo().bodySmall, color = if (r.ok) Color(0xFF9CCC65) else Color(0xFFFF8A80))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = {
                            clipboard.setText(AnnotatedString(DjLog.text()))
                            copied = true
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp),
                    ) { Text(if (copied) copiedText else stringResource(Res.string.dj_log_copy_all), fontSize = 12.sp) }
                    OutlinedButton(onClick = { shareDjLog(context, shareTitle) }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                        Text(stringResource(Res.string.dj_log_share), fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { DjLog.clear() }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                        Text(stringResource(Res.string.dj_log_clear), fontSize = 12.sp)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { diagnostics.runNow(ids.current) }, enabled = !probeRunning, contentPadding = PaddingValues(horizontal = 10.dp)) {
                        Text(stringResource(Res.string.dj_log_run_now), fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { diagnostics.runSelfCheck() }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                        Text(stringResource(Res.string.dj_log_self_check), fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            val names =
                                queue?.data?.listTracks.orEmpty().associate { t ->
                                    t.videoId to "${t.title} - ${t.artists.orEmpty().joinToString(", ") { it.name }}"
                                }
                            shareDjAnalyses(context, shareTitle, names)
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp),
                    ) { Text(stringResource(Res.string.dj_log_export_analyses), fontSize = 12.sp) }
                }
                // ---- the log ----
                if (lines.isEmpty()) {
                    Text(stringResource(Res.string.dj_log_empty), color = Color.Gray, modifier = Modifier.padding(8.dp))
                }
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                    items(lines) { line ->
                        Text(
                            line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            lineHeight = 11.sp,
                            color = lineColor(line),
                        )
                    }
                }
            }
        }
    }
}

/** ERROR red, WARN amber, the rest light grey: the level letter sits after the fixed-width tag column. */
private fun lineColor(line: String): Color =
    when {
        line.contains(" E ") && levelOf(line) == 'E' -> Color(0xFFFF8A80)
        levelOf(line) == 'W' -> Color(0xFFFFCC80)
        else -> Color(0xFFD0D0D0)
    }

/** The level letter of a formatted DJ log line (`MM-dd HH:mm:ss.SSS +Nms [thread] tag     L message`). */
private fun levelOf(line: String): Char? {
    val close = line.indexOf("] ")
    if (close < 0) return null
    val afterTag = close + 2 + org.simpmusic.dj.android.log.DjLogger.TAG_WIDTH + 1
    return line.getOrNull(afterTag)
}

/** Writes the whole log to a text file in the cache dir and hands it to the system share sheet (same FileProvider as the image share). */
fun shareDjLog(context: Context, chooserTitle: String) {
    runCatching {
        val dir = File(context.cacheDir, "dj_log").apply { mkdirs() }
        val file = File(dir, "dj-log.txt")
        file.writeText(DjLog.text())
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", file)
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(Intent.createChooser(send, chooserTitle).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }
}

/**
 * Zips every stored track analysis (the JSON files under `filesDir/dj/analysis`), the DJ log and a `titles.tsv` (videoId -> title
 * for the tracks of the current queue, so a radio's analyses can be told apart) and hands the zip to the share sheet.
 * Analyses carry no audio: beat grid, tempo, key, energy curves. They are what the planner reads, so a developer can
 * replay every pair of the owner's real queue offline.
 */
fun shareDjAnalyses(context: Context, chooserTitle: String, names: Map<String, String>) {
    // Zipping ~170 analyses and drawing their phasegrams takes seconds: off the main thread (a device log showed the UI
    // frozen 550 ms in the Deflater), then back to it for the share sheet.
    // One export at a time: two taps used to run two threads writing the same zip, and the owner's file came out with its
    // entries interleaved (central directory offsets 410 bytes off the data, 2026-10-08).
    if (!djExportRunning.compareAndSet(false, true)) return
    kotlin.concurrent.thread(name = "dj-export") {
        try {
            buildAndShareDjAnalyses(context, chooserTitle, names)
        } finally {
            djExportRunning.set(false)
        }
    }
}

private val djExportRunning = java.util.concurrent.atomic.AtomicBoolean(false)

private fun buildAndShareDjAnalyses(context: Context, chooserTitle: String, names: Map<String, String>) {
    runCatching {
        val dir = File(context.cacheDir, "dj_log").apply { mkdirs() }
        // a fresh name per export: a share target may still be reading the previous one
        dir.listFiles { f -> f.name.startsWith("dj-analyses") && f.name.endsWith(".zip") }?.forEach { it.delete() }
        val zip = File(dir, "dj-analyses-${System.currentTimeMillis()}.zip")
        java.util.zip.ZipOutputStream(zip.outputStream().buffered()).use { out ->
            fun put(name: String, bytes: ByteArray) {
                out.putNextEntry(java.util.zip.ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
            File(context.filesDir, "dj/analysis").listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { f ->
                put("analysis/${f.name}", f.readBytes())
            }
            put("dj-log.txt", DjLog.text().toByteArray())
            // The in-memory log starts with this process; the file pair on disk also holds earlier sessions (the
            // app may have been restarted since the mix that went wrong).
            for (name in listOf("dj-debug.log.1", "dj-debug.log")) {
                val f = File(context.filesDir, "dj/$name")
                if (f.isFile) put("disk/$name", f.readBytes())
            }
            put("titles.tsv", names.entries.joinToString("\n") { "${it.key}\t${it.value}" }.toByteArray())
            // The owner's tapped 1s (videoId -> source ms): ground truth for the bar-phase vote.
            put("taps.tsv", org.simpmusic.dj.android.perfect.UserDownbeats.all().entries.joinToString("\n") { "${it.key}\t${it.value}" }.toByteArray())
            // The owner's phrase marks (videoId -> source ms): ground truth for the phrase lines.
            put("phrases.tsv", org.simpmusic.dj.android.perfect.UserPhrases.all().entries.joinToString("\n") { "${it.key}\t${it.value}" }.toByteArray())
            // The owner's mix marks (videoId, exit, entry, solo; empty = not marked): labels for learning to mix.
            put("mixmarks.tsv", org.simpmusic.dj.android.perfect.UserMixMarks.all().entries.joinToString("\n") { (id, m) -> "$id\t${m.exitMs ?: ""}\t${m.entryMs ?: ""}\t${m.soloMs ?: ""}" }.toByteArray())
            // One phasegram per analysis that carries the bar-phase evidence: does the "1" sit where the network hears it?
            File(context.filesDir, "dj/analysis").listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { f ->
                runCatching {
                    val a = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(org.simpmusic.dj.model.TrackAnalysis.serializer(), f.readText())
                    org.simpmusic.dj.android.perfect.Phasegram.png(a, names[a.videoId])?.let { put("phasegram/${a.videoId}.png", it) }
                    org.simpmusic.dj.android.perfect.Phrasegram.png(a, names[a.videoId])?.let { put("phrasegram/${a.videoId}.png", it) }
                }
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", zip)
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { context.startActivity(Intent.createChooser(send, chooserTitle).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) }
        }
    }.onFailure { DjLog.w("export", "analysis export failed: ${it.message}") }
}
