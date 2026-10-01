package org.simpmusic.dj.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.simpmusic.dj.android.auto.QueueLookAhead
import org.simpmusic.dj.android.recommend.PlayerSnapshot
import org.simpmusic.dj.model.DjSettings
import org.simpmusic.dj.model.EnergyArc

@OptIn(ExperimentalCoroutinesApi::class)
class QueueLookAheadTest {
    private val settings = kotlinx.coroutines.flow.MutableStateFlow(DjSettings(enabled = true, autoDj = true))

    private fun rig(vararg bpms: Pair<String, Float>, queue: List<String>, radio: Boolean = true): Triple<QueueLookAhead, FakePlayer, PlayerSnapshot> {
        val repo = FakeAnalysisRepo(analysis("cur", bpm = 124f), *bpms.map { analysis(it.first, bpm = it.second) }.toTypedArray())
        val player = FakePlayer()
        val look =
            QueueLookAhead(
                scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
                settings = settings,
                port = player,
                analyses = repo,
                planner = NoMixPlanner(),
                setKeep = {},
                compute = Dispatchers.Unconfined,
                perTrackWaitMs = 50L,
                budgetMs = 500L,
            )
        val snap = PlayerSnapshot(currentId = "cur", queueIds = listOf("cur") + queue, currentIndex = 0, isRadio = radio)
        return Triple(look, player, snap)
    }

    @Test
    fun aBetterMatchingTrackLaterInTheQueueMovesIntoTheNextSlot() =
        runTest {
            val (look, player, snap) = rig("q1" to 160f, "q2" to 125f, "q3" to 90f, queue = listOf("q1", "q2", "q3"))
            look.handle(snap)
            assertEquals(listOf("move:q2"), player.calls)
        }

    @Test
    fun anAlreadyGoodNextTrackStays() =
        runTest {
            val (look, player, snap) = rig("q1" to 125f, "q2" to 126f, "q3" to 90f, queue = listOf("q1", "q2", "q3"))
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

    @Test
    fun decidesOncePerTrack() =
        runTest {
            val (look, player, snap) = rig("q1" to 160f, "q2" to 125f, queue = listOf("q1", "q2"))
            look.handle(snap)
            look.handle(snap.copy(queueIds = listOf("cur", "q2", "q1")))
            assertEquals(1, player.calls.size)
        }
}
