package com.maxrave.simpmusic.expect.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.simpmusic.ui.component.EndOfModalBottomSheet
import com.maxrave.simpmusic.ui.component.rememberSurfaceDarkColors
import com.maxrave.simpmusic.ui.theme.typo
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.simpmusic.dj.android.library.LibraryAnalysisCoordinator
import org.simpmusic.dj.android.recommend.DjNextResult
import org.simpmusic.dj.android.recommend.DjRecommendationService
import org.simpmusic.dj.android.recommend.DjSuggestion
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.ai_dj_next_analyzer_missing
import simpmusic.composeapp.generated.resources.ai_dj_next_empty
import simpmusic.composeapp.generated.resources.ai_dj_next_facts
import simpmusic.composeapp.generated.resources.ai_dj_next_library_progress
import simpmusic.composeapp.generated.resources.ai_dj_next_not_analysed
import simpmusic.composeapp.generated.resources.ai_dj_next_play_next
import simpmusic.composeapp.generated.resources.ai_dj_next_play_now
import simpmusic.composeapp.generated.resources.ai_dj_next_pool
import simpmusic.composeapp.generated.resources.ai_dj_next_title
import kotlin.math.roundToInt

/**
 * "DJ: what next?": the top 10 songs of the analysed library to play after the current one, each with its Camelot key, BPM,
 * energy and the one-line reason, and Play next / Play now. When the playing track is not analysed yet it says so and shows
 * the library progress; the list appears by itself when the analysis lands.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun DjNextSheet(
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    val service = koinInject<DjRecommendationService>()
    val coordinator = koinInject<LibraryAnalysisCoordinator>()
    val handler = koinInject<MediaPlayerHandler>()
    val nowPlaying by handler.nowPlaying.collectAsStateWithLifecycle()
    val library by coordinator.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val currentId = nowPlaying?.mediaId?.removePrefix(MERGING_DATA_TYPE.VIDEO)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colors = rememberSurfaceDarkColors()

    val result by produceState<DjNextResult?>(null, currentId) {
        value = null
        val id = currentId ?: return@produceState
        var r = service.recommend(id)
        value = r
        if (r is DjNextResult.CurrentNotAnalysed) {
            service.awaitAnalysis(id)
            r = service.recommend(id)
            value = r
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        contentColor = Color.Transparent,
        dragHandle = null,
        scrimColor = Color.Black.copy(alpha = .5f),
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
            colors = CardDefaults.cardColors().copy(containerColor = colors.container),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(stringResource(Res.string.ai_dj_next_title), style = typo().titleMedium, color = colors.content)
                Spacer(Modifier.height(8.dp))
                when (val r = result) {
                    null, is DjNextResult.CurrentNotAnalysed -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Column {
                                Text(stringResource(Res.string.ai_dj_next_not_analysed), style = typo().bodyMedium, color = colors.content)
                                Text(stringResource(Res.string.ai_dj_next_library_progress, library.analysed, library.total), style = typo().bodySmall, color = colors.content.copy(alpha = 0.7f))
                            }
                        }
                    }

                    DjNextResult.AnalyzerMissing -> Text(stringResource(Res.string.ai_dj_next_analyzer_missing), style = typo().bodyMedium, color = colors.content)

                    is DjNextResult.Ready -> {
                        Text(stringResource(Res.string.ai_dj_next_pool, r.poolSize), style = typo().bodySmall, color = colors.content.copy(alpha = 0.7f))
                        Text(stringResource(Res.string.ai_dj_next_library_progress, library.analysed, library.total), style = typo().bodySmall, color = colors.content.copy(alpha = 0.7f))
                        Spacer(Modifier.height(6.dp))
                        if (r.items.isEmpty()) {
                            Text(stringResource(Res.string.ai_dj_next_empty), style = typo().bodyMedium, color = colors.content)
                        } else {
                            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                                items(r.items, key = { it.track.videoId }) { s ->
                                    SuggestionRow(
                                        s = s,
                                        onPlayNext = { scope.launch { service.playNext(s); onDone() } },
                                        onPlayNow = { scope.launch { service.playNow(s); onDone() } },
                                    )
                                    HorizontalDivider(color = colors.content.copy(alpha = 0.12f))
                                }
                            }
                        }
                    }
                }
                EndOfModalBottomSheet()
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    s: DjSuggestion,
    onPlayNext: () -> Unit,
    onPlayNow: () -> Unit,
) {
    val colors = rememberSurfaceDarkColors()
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(s.track.title, style = typo().titleSmall, color = colors.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(s.track.artist, style = typo().bodySmall, color = colors.content.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            stringResource(
                Res.string.ai_dj_next_facts,
                s.camelot ?: "?",
                s.bpm?.let { "${it.roundToInt()} BPM" } ?: "? BPM",
                s.energy?.let { "${(it * 100).roundToInt()}%" } ?: "?",
            ),
            style = typo().labelSmall,
            color = colors.content,
        )
        Text(s.reason, style = typo().labelSmall, color = colors.content.copy(alpha = 0.6f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onPlayNext) { Text(stringResource(Res.string.ai_dj_next_play_next), style = typo().labelMedium) }
            TextButton(onClick = onPlayNow) { Text(stringResource(Res.string.ai_dj_next_play_now), style = typo().labelMedium) }
        }
    }
}
