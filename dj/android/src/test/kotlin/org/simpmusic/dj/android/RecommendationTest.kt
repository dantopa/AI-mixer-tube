package org.simpmusic.dj.android

import com.maxrave.domain.data.model.browse.album.Track
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.simpmusic.dj.android.library.CandidateSource
import org.simpmusic.dj.android.recommend.AnalysisPool
import org.simpmusic.dj.android.recommend.DjNextResult
import org.simpmusic.dj.android.recommend.DjPlayerPort
import org.simpmusic.dj.android.recommend.DjRanking
import org.simpmusic.dj.android.recommend.DjRecommendationService
import org.simpmusic.dj.android.recommend.PlayerSnapshot
import org.simpmusic.dj.model.AnalysisPriority
import org.simpmusic.dj.model.DjSettings
import org.simpmusic.dj.model.Mode
import org.simpmusic.dj.model.MusicalKey
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.model.TrackAnalysisRepository
import org.simpmusic.dj.model.TransitionPlan
import org.simpmusic.dj.model.TransitionPlanner
import kotlinx.coroutines.Dispatchers

/** In-memory [TrackAnalysisRepository]: what is "stored", who was asked for, and a flow of arrivals. */
class FakeAnalysisRepo(vararg initial: TrackAnalysis) : TrackAnalysisRepository {
    val stored = HashMap<String, TrackAnalysis>().apply { initial.forEach { put(it.videoId, it) } }
    val requests = ArrayList<Pair<String, AnalysisPriority>>()
    private val arrivals = MutableSharedFlow<String>(extraBufferCapacity = 16)

    override suspend fun get(videoId: String) = stored[videoId]

    override fun observe(videoId: String): Flow<TrackAnalysis?> =
        arrivals.filter { it == videoId }.map { stored[videoId] }.onStart { emit(stored[videoId]) }

    override suspend fun request(videoId: String, priority: AnalysisPriority) {
        requests += videoId to priority
    }

    suspend fun arrive(a: TrackAnalysis) {
        stored[a.videoId] = a
        arrivals.emit(a.videoId)
    }
}

class FakePlayer(var snap: PlayerSnapshot = PlayerSnapshot()) : DjPlayerPort {
    val stream = MutableStateFlow(snap)
    override val snapshots: Flow<PlayerSnapshot> get() = stream
    val calls = ArrayList<String>()

    override fun snapshot(): PlayerSnapshot = snap

    fun set(s: PlayerSnapshot) {
        snap = s
        stream.value = s
    }

    override suspend fun playNext(track: Track) {
        calls += "next:${track.videoId}"
    }

    override suspend fun append(track: Track) {
        calls += "append:${track.videoId}"
    }

    override suspend fun playNow(track: Track) {
        calls += "now:${track.videoId}"
    }

    override suspend fun moveToNext(videoId: String): Boolean {
        calls += "move:$videoId"
        return true
    }
}

/** A planner that never plans a mix: every score then differs only by the musical facts. */
class NoMixPlanner : TransitionPlanner {
    var calls = 0

    override fun plan(from: TrackAnalysis?, to: TrackAnalysis?, settings: DjSettings): TransitionPlan {
        calls++
        return FallbackOnlyPlanner().plan(from, to, settings)
    }
}

private val A_MINOR = MusicalKey(9, Mode.MINOR) // 8A
private val E_MINOR = MusicalKey(4, Mode.MINOR) // 9A: next to 8A
private val F_SHARP_MAJOR = MusicalKey(6, Mode.MAJOR) // 2B: far from 8A

@OptIn(ExperimentalCoroutinesApi::class)
class RecommendationTest {
    private val settings = DjSettings(enabled = true)

    @Test
    fun rankPutsTheCompatibleKeyAndTempoFirstAndKeepsThePlannerOffTheWholePool() {
        val current = analysis("cur", bpm = 124f, key = A_MINOR)
        val pool =
            listOf(analysis("far", 150f, F_SHARP_MAJOR)) +
                (1..120).map { analysis("filler$it", bpm = 100f + (it % 60), key = if (it % 2 == 0) F_SHARP_MAJOR else E_MINOR) } +
                analysis("good", 126f, A_MINOR)
        val planner = NoMixPlanner()
        val ranked = DjRanking.rank(current, pool, settings, planner, limit = 5)
        assertEquals("good", ranked.first().videoId)
        assertTrue("the planner only sees the shortlist, not the 122 candidates", planner.calls <= DjRanking.SHORTLIST)
        assertTrue(ranked.first().breakdown.containsKey("transition"))
        assertTrue(ranked.first().reason.isNotBlank())
    }

    @Test
    fun rankSkipsHistoryAndTheCurrentTrack() {
        val current = analysis("cur")
        val ranked = DjRanking.rank(current, listOf(analysis("cur"), analysis("seen"), analysis("new")), settings, null, history = setOf("seen"))
        assertEquals(listOf("new"), ranked.map { it.videoId })
    }

    @Test
    fun pickNextUsesBeamSearchOnALargePoolAndNeverPicksSomethingExcluded() {
        val current = analysis("cur")
        val pool = (1..30).map { analysis("t$it", bpm = 120f + it % 8, key = if (it % 3 == 0) A_MINOR else E_MINOR) }
        val pick = DjRanking.pickNext(current, pool, settings, NoMixPlanner(), history = setOf("t3", "t6"))
        assertNotNull(pick)
        assertTrue(pick!!.recommendation.videoId !in setOf("t3", "t6", "cur"))
        assertEquals(30 - 2, pick.poolSize)
        if (pick.viaBeamSearch) {
            assertEquals(pick.recommendation.videoId, pick.lookAhead.first())
            assertTrue(pick.lookAhead.size <= DjRanking.LOOK_AHEAD)
        }
    }

    @Test
    fun aSmallPoolIsRankedGreedilyWithoutTheBeam() {
        val current = analysis("cur")
        val pool = (1..DjRanking.BEAM_MIN_POOL - 1).map { analysis("t$it", bpm = 124f, key = A_MINOR) }
        val pick = DjRanking.pickNext(current, pool, settings, NoMixPlanner())
        assertNotNull(pick)
        assertEquals(false, pick!!.viaBeamSearch)
    }

    @Test
    fun pickNextRefusesWhenNothingFitsSoTheNormalRadioCarriesOn() {
        val current = analysis("cur", bpm = 124f, key = A_MINOR)
        // 200 BPM is far beyond the tempo bend and 2B is far from 8A: every score is crushed
        val pool = (1..12).map { analysis("t$it", bpm = 200f, key = F_SHARP_MAJOR, introEnergy = 0.05f) }
        val pick = DjRanking.pickNext(current, pool, settings, NoMixPlanner())
        assertNull(pick)
    }

    @Test
    fun energyArcSteersThePickAndTheDefaultIsSteady() {
        assertEquals(org.simpmusic.dj.model.EnergyArc.STEADY, DjSettings().autoDjArc)
        val current = analysis("cur", outroEnergy = 0.5f)
        val hot = analysis("hot", introEnergy = 0.75f)
        val cool = analysis("cool", introEnergy = 0.3f)
        val up = DjRanking.pickNext(current, listOf(cool, hot), settings, null, arc = listOf(1f))!!
        val down = DjRanking.pickNext(current, listOf(cool, hot), settings, null, arc = listOf(-1f))!!
        assertEquals("hot", up.recommendation.videoId)
        assertEquals("cool", down.recommendation.videoId)
        assertEquals(0f, org.simpmusic.dj.model.EnergyArc.STEADY.trendAt(5), 0f)
        assertTrue(org.simpmusic.dj.model.EnergyArc.BUILD.trendAt(0) > 0f)
        assertTrue(org.simpmusic.dj.model.EnergyArc.WAVE.trendAt(0) > 0f && org.simpmusic.dj.model.EnergyArc.WAVE.trendAt(8) < 0f)
    }

    // ---- the service ----

    private fun service(
        library: List<String>,
        repo: FakeAnalysisRepo,
        player: FakePlayer = FakePlayer(),
        recent: List<String> = emptyList(),
        analyzerAvailable: Boolean = true,
    ): DjRecommendationService {
        val source = FakeLibrarySource(library.map { cand(it, CandidateSource.LIKED) }, recent)
        val pool = AnalysisPool(source, { id -> repo.stored[id] })
        return DjRecommendationService(pool, source, repo, NoMixPlanner(), { settings }, player, { analyzerAvailable }, Dispatchers.Unconfined)
    }

    @Test
    fun aTrackThatIsNotAnalysedYetIsRequestedAtTheHighestPriorityAndSaidSo() =
        runTest {
            val repo = FakeAnalysisRepo()
            val r = service(listOf("a", "b"), repo).recommend("playing")
            assertTrue(r is DjNextResult.CurrentNotAnalysed)
            assertEquals(listOf("playing" to AnalysisPriority.NOW_PLAYING), repo.requests)
        }

    @Test
    fun withoutAnAnalyzerTheServiceSaysSoInsteadOfWaiting() =
        runTest {
            val r = service(emptyList(), FakeAnalysisRepo(), analyzerAvailable = false).recommend("x")
            assertEquals(DjNextResult.AnalyzerMissing, r)
        }

    @Test
    fun recommendsFromTheAnalysedLibraryWithFactsAndSkipsRecentAndQueuedAndCurrent() =
        runTest {
            val ids = listOf("good", "recent", "queued", "ok", "notAnalysed")
            val repo =
                FakeAnalysisRepo(
                    analysis("cur", 124f, A_MINOR),
                    analysis("good", 125f, A_MINOR),
                    analysis("recent", 125f, A_MINOR),
                    analysis("queued", 125f, A_MINOR),
                    analysis("ok", 130f, E_MINOR),
                )
            val player = FakePlayer(PlayerSnapshot(currentId = "cur", queueIds = listOf("cur", "queued"), currentIndex = 0))
            val r = service(ids + "cur", repo, player, recent = listOf("recent")).recommend("cur") as DjNextResult.Ready
            assertEquals(listOf("good", "ok"), r.items.map { it.track.videoId })
            assertEquals("8A", r.currentCamelot)
            val top = r.items.first()
            assertEquals("8A", top.camelot)
            assertEquals(125f, top.bpm!!, 0.01f)
            assertNotNull(top.energy)
            assertTrue(top.reason.contains("BPM"))
            assertTrue(top.breakdown.keys.containsAll(listOf("harmony", "tempo", "energy", "timbre", "transition")))
            assertEquals("only analysed library tracks are compared", 5, r.poolSize)
        }

    @Test
    fun playNextAndPlayNowEnqueueTheSuggestedTrackThroughThePlayer() =
        runTest {
            val repo = FakeAnalysisRepo(analysis("cur"), analysis("nxt"))
            val player = FakePlayer(PlayerSnapshot(currentId = "cur"))
            val svc = service(listOf("nxt"), repo, player)
            val r = svc.recommend("cur") as DjNextResult.Ready
            svc.playNext(r.items.single())
            svc.playNow(r.items.single())
            assertEquals(listOf("next:nxt", "now:nxt"), player.calls)
        }

    @Test
    fun awaitAnalysisReturnsAsSoonAsTheTrackArrives() =
        runTest {
            val repo = FakeAnalysisRepo()
            val svc = service(emptyList(), repo)
            val a = analysis("x")
            val waiter = async(Dispatchers.Unconfined) { svc.awaitAnalysis("x", timeoutMs = 60_000) }
            repo.arrive(a)
            assertEquals(a, waiter.await())
            assertTrue(repo.requests.any { it.first == "x" && it.second == AnalysisPriority.NOW_PLAYING })
        }
}
