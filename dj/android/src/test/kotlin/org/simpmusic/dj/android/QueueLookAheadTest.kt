package org.simpmusic.dj.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.simpmusic.dj.analysis.Harmony
import org.simpmusic.dj.android.auto.QueueLookAhead
import org.simpmusic.dj.android.recommend.PlayerSnapshot
import org.simpmusic.dj.model.DjSettings
import org.simpmusic.dj.model.PlanKind
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.model.TransitionPlan
import org.simpmusic.dj.model.TransitionPlanner
import kotlin.math.abs

@OptIn(ExperimentalCoroutinesApi::class)
class QueueLookAheadTest {
    private val settings = kotlinx.coroutines.flow.MutableStateFlow(DjSettings(enabled = true, autoDj = true))
    private lateinit var repo: FakeAnalysisRepo

    private fun rig(
        vararg bpms: Pair<String, Float>,
        queue: List<String>,
        radio: Boolean = true,
        cur: Float = 124f,
        clashing: Set<String> = emptySet(),
        structure: Set<String> = emptySet(),
    ): Triple<QueueLookAhead, FakePlayer, PlayerSnapshot> {
        repo = FakeAnalysisRepo(analysis("cur", bpm = cur), *bpms.map { analysis(it.first, bpm = it.second) }.toTypedArray())
        val player = FakePlayer()
        val look =
            QueueLookAhead(
                scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
                settings = settings,
                port = player,
                analyses = repo,
                planner = TempoGatePlanner(clashing, structure),
                setKeep = {},
                compute = Dispatchers.Unconfined,
                perTrackWaitMs = 50L,
                budgetMs = 500L,
            )
        val snap = PlayerSnapshot(currentId = "cur", queueIds = listOf("cur") + queue, currentIndex = 0, isRadio = radio)
        return Triple(look, player, snap)
    }

    @Test
    fun aBeatMatchableTrackLaterInTheQueueReplacesANextThatWouldNotBeatMatch() =
        runTest {
            val (look, player, snap) = rig("q1" to 160f, "q2" to 125f, "q3" to 90f, queue = listOf("q1", "q2", "q3"))
            look.handle(snap)
            assertEquals(listOf("move:q2"), player.calls)
        }

    @Test
    fun aNextThatMixesInstrumentalOverInstrumentalStaysAndTheOthersAreNeverAnalysed() =
        runTest {
            // q2 would score a little higher (same tempo), but q1 already mixes at the top tier: no reorder, no extra analysis
            val (look, player, snap) = rig("q1" to 127f, "q2" to 124f, "q3" to 90f, queue = listOf("q1", "q2", "q3"), structure = setOf("q1"))
            repo.stored.remove("q2")
            repo.stored.remove("q3")
            look.handle(snap)
            assertTrue(player.calls.toString(), player.calls.isEmpty())
            assertEquals(emptyList<String>(), repo.requests.map { it.first }.filter { it == "q2" || it == "q3" })
        }

    @Test
    fun aNextThatOnlyCrossfadesGivesWayToOneWhoseInstrumentalLandsOnOurs() =
        runTest {
            // both beat-match; only q3 has an instrumental over the current track's (YouTube picked all three, same style)
            val (look, player, snap) = rig("q1" to 124f, "q2" to 125f, "q3" to 123f, queue = listOf("q1", "q2", "q3"), structure = setOf("q3"))
            look.handle(snap)
            assertEquals(listOf("move:q3"), player.calls)
        }

    @Test
    fun aCrossfadingNextStaysWhenNoOtherCandidateDoesBetter() =
        runTest {
            val (look, player, snap) = rig("q1" to 124f, "q2" to 125f, "q3" to 123f, queue = listOf("q1", "q2", "q3"))
            look.handle(snap)
            assertTrue(player.calls.toString(), player.calls.isEmpty())
        }

    @Test
    fun eightyToOneHundredThreeLooksFurtherAndFindsTheEightyTwo() =
        runTest {
            // device log: 80.0 -> 103.4 bpm fell back to a plain crossfade; an 82 bpm track two places later beat-matches
            val (look, player, snap) = rig("q1" to 103.4f, "q2" to 140f, "q3" to 82f, queue = listOf("q1", "q2", "q3"), cur = 80f)
            look.handle(snap)
            assertEquals(listOf("move:q3"), player.calls)
        }

    @Test
    fun whenNothingBeatMatchesTheOrderStays() =
        runTest {
            val (look, player, snap) = rig("q1" to 160f, "q2" to 90f, "q3" to 70f, queue = listOf("q1", "q2", "q3"))
            look.handle(snap)
            assertTrue(player.calls.toString(), player.calls.isEmpty())
        }

    @Test
    fun aNextThatBeatMatchesButClashesHarmonicallyGivesWayToOneThatFits() =
        runTest {
            val (look, player, snap) = rig("q1" to 124f, "q2" to 125f, "q3" to 90f, queue = listOf("q1", "q2", "q3"), clashing = setOf("q1"))
            look.handle(snap)
            assertEquals(listOf("move:q2"), player.calls)
        }

    @Test
    fun aClashingNextStaysWhenEveryOtherCandidateClashesToo() =
        runTest {
            val (look, player, snap) = rig("q1" to 124f, "q2" to 125f, queue = listOf("q1", "q2"), clashing = setOf("q1", "q2"))
            look.handle(snap)
            assertTrue(player.calls.toString(), player.calls.isEmpty())
        }

    @Test
    fun aPlaylistKeepsItsOrder() =
        runTest {
            val (look, player, snap) = rig("q1" to 160f, "q2" to 125f, queue = listOf("q1", "q2"), radio = false)
            look.handle(snap)
            assertTrue(player.calls.isEmpty())
        }

    /**
     * Device log (build ba): the player re-emitted the same state every ~5 s, each emission restarted the decision, and only
     * the first candidate was ever requested, so the 97 -> 125 bpm pair it should have avoided was played.
     */
    @Test
    fun anEqualSnapshotEmittedAgainDoesNotRestartTheDecision() =
        runTest {
            repo = FakeAnalysisRepo(analysis("cur", bpm = 97f), analysis("q1", bpm = 125f))
            val snap = PlayerSnapshot(currentId = "cur", queueIds = listOf("cur", "q1", "q2", "q3"), currentIndex = 0, isRadio = true)
            val flow = kotlinx.coroutines.flow.MutableSharedFlow<PlayerSnapshot>(replay = 1, extraBufferCapacity = 16)
            val calls = ArrayList<String>()
            val port =
                object : org.simpmusic.dj.android.recommend.DjPlayerPort {
                    override val snapshots: kotlinx.coroutines.flow.Flow<PlayerSnapshot> = flow
                    override fun snapshot() = snap
                    override suspend fun playNext(track: com.maxrave.domain.data.model.browse.album.Track) {}
                    override suspend fun append(track: com.maxrave.domain.data.model.browse.album.Track) {}
                    override suspend fun playNow(track: com.maxrave.domain.data.model.browse.album.Track) {}
                    override suspend fun moveToNext(videoId: String): Boolean { calls += "move:$videoId"; return true }
                }
            val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Default)
            val look =
                QueueLookAhead(
                    scope = scope, settings = settings, port = port, analyses = repo, planner = TempoGatePlanner(emptySet(), emptySet()),
                    setKeep = {}, compute = Dispatchers.Unconfined, perTrackWaitMs = 5_000L, budgetMs = 20_000L,
                )
            look.start()
            flow.emit(snap)
            kotlinx.coroutines.withContext(Dispatchers.Default) {
                repeat(6) { kotlinx.coroutines.delay(60); flow.emit(snap.copy()) }
                kotlinx.coroutines.delay(100)
            }
            val q2Requests = synchronized(repo.requests) { repo.requests.count { it.first == "q2" } }
            assertEquals("q2 requested once, the decision was not restarted", 1, q2Requests)
            repo.arrive(analysis("q2", bpm = 126f))
            repo.arrive(analysis("q3", bpm = 98f))
            kotlinx.coroutines.withContext(Dispatchers.Default) { kotlinx.coroutines.delay(400) }
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            assertEquals(listOf("move:q3"), calls)
        }

    /** The owner's radio: 97 bpm cumbia with 125 / 122 / 108 bpm tracks mixed in; the 97 goes next, not the 125. */
    @Test
    fun aRadioThatMixesTemposIsOrderedByTempo() =
        runTest {
            val (look, player, snap) =
                rig("ocv" to 125f, "ixo" to 96.9f, "4eu" to 122f, "1ez" to 107.9f, "a7f" to 102f, queue = listOf("ocv", "ixo", "4eu", "1ez", "a7f"), cur = 96.9f)
            look.handle(snap)
            assertEquals(listOf("move:ixo"), player.calls)
        }

    @Test
    fun decidesOncePerTrack() =
        runTest {
            val (look, player, snap) = rig("q1" to 160f, "q2" to 125f, queue = listOf("q1", "q2"))
            look.handle(snap)
            look.handle(snap.copy(queueIds = listOf("cur", "q2", "q1")))
            assertEquals(1, player.calls.size)
        }
}

/**
 * Beat-matches when the tempos are within the settings' bend (no half/double), a plain crossfade otherwise; a pair whose
 * incoming track is in [clashing] reports a harmonic clash in its reason, as the real planner does, and one in [structure]
 * an instrumental-over-instrumental hand-off.
 */
class TempoGatePlanner(private val clashing: Set<String> = emptySet(), private val structure: Set<String> = emptySet()) : TransitionPlanner {
    override fun plan(from: TrackAnalysis?, to: TrackAnalysis?, settings: DjSettings): TransitionPlan {
        val base = FallbackOnlyPlanner().plan(from, to, settings)
        val a = from?.bpm?.value ?: return base
        val b = to?.bpm?.value ?: return base
        if (abs(a - b) / b > settings.maxTempoBend) return base
        val clash = to.videoId in clashing
        val tag = if (to.videoId in structure) org.simpmusic.dj.planner.PlanTags.STRUCTURE else ""
        return base.copy(kind = PlanKind.BEAT_MATCHED, reason = tag + base.reason + if (clash) "; ${Harmony.CLASH_TAG} (excess 0.140)" else "")
    }
}
