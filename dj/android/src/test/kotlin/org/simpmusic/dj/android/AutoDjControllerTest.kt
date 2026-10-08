package org.simpmusic.dj.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.simpmusic.dj.android.auto.AnalysisPrefetcher
import org.simpmusic.dj.android.auto.AnalysisRequester
import org.simpmusic.dj.android.auto.AutoDjController
import org.simpmusic.dj.android.auto.AutoDjDecision
import org.simpmusic.dj.android.library.CandidateSource
import org.simpmusic.dj.android.recommend.AnalysisPool
import org.simpmusic.dj.android.recommend.PlayerSnapshot
import org.simpmusic.dj.android.recommend.RepeatKind
import org.simpmusic.dj.model.AnalysisPriority
import org.simpmusic.dj.model.DjSettings
import org.simpmusic.dj.model.EnergyArc

@OptIn(ExperimentalCoroutinesApi::class)
class AutoDjControllerTest {
    private val scopes = ArrayList<CoroutineScope>()

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private class Rig(
        val controller: AutoDjController,
        val player: FakePlayer,
        val repo: FakeAnalysisRepo,
        val settings: MutableStateFlow<DjSettings>,
        val library: FakeLibrarySource,
    )

    /** A library of [size] analysed tracks `lib1..libN` (all in a compatible key and tempo) plus the analysed track `cur`. */
    private fun TestScope.rig(size: Int = 12, settings: DjSettings = DjSettings(enabled = true, autoDj = true), analysedCurrent: Boolean = true, minPool: Int = 8): Rig {
        val ids = (1..size).map { "lib$it" }
        val analyses = ids.mapIndexed { i, id -> analysis(id, bpm = 122f + i % 5) } + if (analysedCurrent) listOf(analysis("cur")) else emptyList()
        // The tracks a test queues up ("q1"...) are analysed too (a queue's last track is what an append chains from) but are not library songs.
        val queued = (1..50).map { analysis("q$it") }
        val repo = FakeAnalysisRepo(*(analyses + queued).toTypedArray())
        val library = FakeLibrarySource((ids + "cur").map { cand(it, CandidateSource.LIKED) })
        val player = FakePlayer()
        val flow = MutableStateFlow(settings)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { scopes += it }
        val pool = AnalysisPool(library, { id -> repo.stored[id] })
        val c =
            AutoDjController(
                scope = scope,
                settings = flow,
                port = player,
                pool = pool,
                analyses = repo,
                source = library,
                planner = NoMixPlanner(),
                compute = Dispatchers.Unconfined,
                minPool = minPool,
                waitForAnalysisMs = 60_000L,
            )
        return Rig(c, player, repo, flow, library)
    }

    private fun finiteQueue(ahead: Int, current: String = "cur", before: List<String> = emptyList()) =
        PlayerSnapshot(
            currentId = current,
            queueIds = before + current + (1..ahead).map { "q$it" },
            currentIndex = before.size,
        )

    private fun radioQueue(ahead: Int, current: String = "cur") = finiteQueue(ahead, current).copy(isRadio = true)

    @Test
    fun aFiniteQueueAboutToRunOutGetsTheBestNextTrackAppended() =
        runTest {
            val r = rig()
            r.controller.handle(finiteQueue(ahead = 1))
            assertEquals(1, r.player.calls.size)
            assertTrue(r.player.calls.single().startsWith("append:lib"))
            val d = r.controller.lastDecision.value!!
            assertEquals(AutoDjDecision.Action.APPEND, d.action)
            assertNotNull(d.pickedId)
            assertTrue("the decision carries its reason for the settings line", d.reason.isNotBlank())
            assertTrue(d.summary().startsWith("Queued:"))
        }

    @Test
    fun aLongQueueIsLeftAloneAndTheAppsOwnEndlessQueueKeepsIt() =
        runTest {
            val r = rig()
            r.controller.handle(finiteQueue(ahead = AutoDjController.APPEND_WHEN_AHEAD + 1))
            assertTrue(r.player.calls.isEmpty())
            r.controller.handle(finiteQueue(ahead = AutoDjController.APPEND_WHEN_AHEAD))
            assertEquals("acts at 2 tracks left, before the endless queue reacts at 1", 1, r.player.calls.size)
        }

    @Test
    fun theDjActsBeforeTheEndlessQueueSoItNeverGetsToFire() {
        // The app's loadMore condition (MediaServiceHandlerImpl.onMediaItemTransition): size - currentIndex < 3, i.e. 1 track or fewer ahead.
        val endlessQueueFiresAtAhead = 1
        assertTrue(AutoDjController.APPEND_WHEN_AHEAD > endlessQueueFiresAtAhead)
    }

    @Test
    fun aRadioIsLeftToYouTubeTheDjAddsNothing() =
        runTest {
            // The owner (2026-10-08): YouTube's radio picks tracks of the same style; the DJ inserted ~85 BPM reggaeton into
            // a cumbia radio. On a radio it now only reorders (QueueLookAhead), never adds.
            val r = rig()
            r.controller.handle(radioQueue(ahead = 40))
            r.controller.handle(radioQueue(ahead = 40))
            assertTrue(r.player.calls.isEmpty())
            val d = r.controller.lastDecision.value!!
            assertEquals(AutoDjDecision.Action.SKIPPED, d.action)
            assertTrue(d.reason, "YouTube" in d.reason)
        }

    @Test
    fun neverRepeatsTheLast30_TheQueue_OrTheCurrentTrack() =
        runTest {
            val r = rig(size = 40)
            val recent = (1..30).map { "lib$it" }
            r.library.recent = recent
            val queued = listOf("lib31", "lib32")
            // 2 tracks ahead, so the DJ acts; the last queued track is lib32
            r.controller.handle(PlayerSnapshot(currentId = "cur", queueIds = listOf("lib34", "cur") + queued, currentIndex = 1))
            val picks = r.player.calls.map { it.substringAfter(':') }
            assertEquals(1, picks.size)
            assertTrue("picked $picks", picks.none { it in recent || it in queued || it == "cur" || it == "lib34" })
        }

    /** Plays the queue forward one track at a time, keeping one track ahead, and returns what the DJ appended. */
    private suspend fun playForward(r: Rig, steps: Int): List<String> {
        var queue = listOf("cur", "q1")
        repeat(steps) {
            r.controller.handle(PlayerSnapshot(currentId = queue[queue.size - 2], queueIds = queue, currentIndex = queue.size - 2))
            queue = queue + r.player.calls.last().substringAfter(':')
        }
        return queue.drop(2)
    }

    @Test
    fun theDjsOwnRecentPicksAreNotPickedAgain() =
        runTest {
            val r = rig(size = 12)
            val picks = playForward(r, 8)
            assertEquals("no repeats: $picks", picks.size, picks.toSet().size)
        }

    @Test
    fun staysOutInEveryCaseTheTransitionEngineStaysOut_PlusRepeatAllAndShuffle() =
        runTest {
            val cases =
                mapOf(
                    "repeat one" to finiteQueue(0).copy(repeat = RepeatKind.ONE),
                    "repeat all" to finiteQueue(0).copy(repeat = RepeatKind.ALL),
                    "shuffle" to finiteQueue(0).copy(shuffle = true),
                    "casting" to finiteQueue(0).copy(casting = true),
                    "listen together" to finiteQueue(0).copy(listenTogether = true),
                    "video" to finiteQueue(0).copy(currentIsVideo = true),
                )
            for ((reason, snap) in cases) {
                val r = rig()
                r.controller.handle(snap)
                assertTrue("$reason: must not touch the queue", r.player.calls.isEmpty())
                val d = r.controller.lastDecision.value!!
                assertEquals(AutoDjDecision.Action.SKIPPED, d.action)
                assertTrue("reason mentions $reason: ${d.reason}", d.reason.contains(reason))
            }
        }

    @Test
    fun aColdLibraryLeavesTheNormalRadioAlone() =
        runTest {
            val r = rig(size = 5) // fewer than 8 analysed tracks
            r.controller.handle(finiteQueue(0))
            assertTrue(r.player.calls.isEmpty())
            assertTrue(r.controller.lastDecision.value!!.reason.contains("normal queue continues"))
        }

    @Test
    fun whenNothingFitsItDoesNothingAndSaysSo() =
        runTest {
            val ids = (1..12).map { "far$it" }
            val repo = FakeAnalysisRepo(analysis("cur", 124f), *ids.map { analysis(it, bpm = 200f, key = org.simpmusic.dj.model.MusicalKey(6, org.simpmusic.dj.model.Mode.MAJOR), introEnergy = 0.05f) }.toTypedArray())
            val library = FakeLibrarySource(ids.map { cand(it, CandidateSource.LIKED) })
            val player = FakePlayer()
            val c = AutoDjController(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)), MutableStateFlow(DjSettings(enabled = true, autoDj = true)), player, AnalysisPool(library, { repo.stored[it] }), repo, library, NoMixPlanner(), compute = Dispatchers.Unconfined)
            c.handle(finiteQueue(0))
            assertTrue(player.calls.isEmpty())
            assertTrue(c.lastDecision.value!!.reason.contains("nothing fits"))
        }

    @Test
    fun aTrackThatIsNotAnalysedYetIsAskedForAndTheDjActsWhenItArrives() =
        runTest {
            val r = rig(analysedCurrent = false)
            val job = launch { r.controller.handle(finiteQueue(0)) }
            runCurrent()
            assertTrue("waiting for the analysis: nothing queued yet", r.player.calls.isEmpty())
            assertTrue(r.repo.requests.contains("cur" to AnalysisPriority.NOW_PLAYING))
            r.repo.arrive(analysis("cur"))
            advanceUntilIdle()
            job.join()
            assertEquals(1, r.player.calls.size)
        }

    @Test
    fun aTrackThatNeverGetsAnalysedIsReportedNotWaitedForForever() =
        runTest {
            val r = rig(analysedCurrent = false)
            r.controller.handle(finiteQueue(0))
            assertTrue(r.player.calls.isEmpty())
            assertTrue(r.controller.lastDecision.value!!.reason.contains("not analysed"))
        }

    @Test
    fun theSwitchesGateEverythingAndTheLoopFollowsThePlayerFlow() =
        runTest {
            val r = rig(settings = DjSettings(enabled = true, autoDj = false))
            r.controller.start()
            r.player.set(finiteQueue(0))
            runCurrent()
            assertTrue("Auto DJ off: nothing happens", r.player.calls.isEmpty())
            r.settings.value = DjSettings(enabled = true, autoDj = true)
            runCurrent()
            assertEquals("switching it on acts on the current state", 1, r.player.calls.size)
            r.settings.value = DjSettings(enabled = false, autoDj = true)
            r.player.calls.clear()
            r.player.set(finiteQueue(0, before = listOf("z")))
            runCurrent()
            assertTrue("AI DJ mode off: Auto DJ is off too", r.player.calls.isEmpty())
        }

    @Test
    fun theDecisionsLogKeepsTheLastTwenty() =
        runTest {
            val r = rig(size = 40)
            playForward(r, 25)
            assertEquals(AutoDjController.LOG_SIZE, r.controller.recentDecisions.value.size)
        }

    @Test
    fun energyArcIsReadFromTheSettings() =
        runTest {
            val up = rig(settings = DjSettings(enabled = true, autoDj = true, autoDjArc = EnergyArc.BUILD))
            up.controller.handle(finiteQueue(0))
            assertNotNull(up.controller.lastDecision.value!!.pickedId)
        }

    // ---- prefetch ----

    private class RecordingRequester : AnalysisRequester {
        val requests = ArrayList<Pair<String, AnalysisPriority>>()
        var stale: Pair<String?, String?>? = null

        override suspend fun request(videoId: String, priority: AnalysisPriority) {
            requests += videoId to priority
        }

        override fun cancelStale(current: String?, next: String?) {
            stale = current to next
        }
    }

    @Test
    fun theNextTrackIsRequestedAsSoonAsATrackStarts() =
        runTest {
            val requester = RecordingRequester()
            val player = FakePlayer()
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { scopes += it }
            val prefetcher = AnalysisPrefetcher(scope, MutableStateFlow(DjSettings(enabled = true)), player, requester)
            prefetcher.start()
            player.set(PlayerSnapshot(currentId = "a", queueIds = listOf("a", "b", "c"), currentIndex = 0))
            runCurrent()
            assertEquals(listOf("a" to AnalysisPriority.NOW_PLAYING, "b" to AnalysisPriority.NEXT_UP), requester.requests)
            assertEquals("a" to "b", requester.stale)
            requester.requests.clear()
            player.set(PlayerSnapshot(currentId = "a", queueIds = listOf("a", "b"), currentIndex = 0, casting = true))
            runCurrent()
            assertTrue("no analysis while casting", requester.requests.isEmpty())
        }

    @Test
    fun snapshotArithmetic() {
        assertNull(finiteQueue(1).blockedReason)
        assertEquals(1, finiteQueue(1).tracksAhead)
        val s = PlayerSnapshot(currentId = "c", queueIds = listOf("b", "a", "c", "d"), currentIndex = 2)
        assertEquals(listOf("a", "b"), s.playedBefore(2))
        assertEquals(1, s.tracksAhead)
    }
}
