# AI DJ on Android (`:djAndroid` + core patch series)

Status: implemented and unit-tested on the JVM; compiles into the app. **Never run on a device**: see
"What could NOT be verified" before trusting any number in here.

## 1. The design in one page

Two independent ExoPlayers (two AudioTracks) cannot hold two beat grids together to a few milliseconds: each
AudioTrack has its own start latency, its own clock drift and its own position-timestamp jitter. So the live
players never try to beat-match. The mix is **rendered ahead of time into one WAV** (the *transition window*:
outgoing tail + incoming head with every tempo / pitch / EQ / fader / filter lane of the `TransitionPlan` baked in)
and played through **one** audio path, where beat alignment is exact by construction. The only real-time problem
left is getting on and off that WAV without an audible seam:

```
time  ──────────────────────────────────────────────────────────────────────────────────────────►
live outgoing  ████████████████████ plain audio ████████╲ (100 ms linear x-fade, LOCKED)
window player       silent, running, LOCKED ───────────╱█████████ beat-matched mix ███████╲ (100 ms x-fade)
live incoming                                                     silent, LOCKED ─────────╱███ plain audio ►
               │←──── LEAD_IN 2.5 s ────→│← plan pre-roll →│ T0 │←──── overlap ────→│←─ TAIL 6 s ─→│
```

Both hand-offs are between decks playing **identical audio** (the window was rendered from the same decoded
source), so a 100 ms *linear* cross-fade (gain sum = 1, because the signals are correlated, not equal-power) is
seamless **provided the decks are aligned**. Alignment is measured and corrected while the follower is still
silent (`AlignmentLoop`): measure the median position offset, hard-seek the silent follower by minus that offset,
let the pipeline settle, measure again (max 3 seeks, tolerance 6 ms). A failed lock **before the commit point is
free**: nothing audible has changed, the adapter just carries on with the normal crossfade.

## 2. Components (`dj/android`, package `org.simpmusic.dj.android`)

| Piece | File | Notes |
|---|---|---|
| Decode | `decode/MediaCodecPcmDecoder.kt`, `AudioInput.kt`, `AudioSourceResolver.kt`, `StreamingResampler.kt`, `MediaCodecTrackDecoder.kt` | MediaExtractor + MediaCodec, streaming, cancellable between codec buffers; ranged decode = seek to previous sync sample + trim by presentation time. Windowed-sinc polyphase resampler (Blackman, 48 taps, 1024 phases). Whole-track analysis decode is mono 22.05 kHz (≈21 MB for 4 min); ranges are stereo 48 kHz. |
| Source | `AudioSourceResolver.kt` | Cache first: download cache then player cache, key = videoId (the same key `provideResolvingDataSourceFactory` uses), read through a cache-**only** `CacheDataSource` (no upstream, so it can never fetch). Same completeness test as core `Cache.isFullyCached` (declared content length known and every byte cached). A **partially** cached file is never decoded (the analysis would silently cover only the buffered part): it is reported (`AudioOrigin.PARTIAL_CACHE_REFETCHED`) and the stream URL is fetched by the platform extractor (`NewFormatEntity.audioUrl` when not expired and not 403, else `StreamRepository.getStream`). Never touches playback. |
| Store | `store/AnalysisStore.kt` | One JSON per videoId under `filesDir/dj/analysis`, atomic write (temp + `ATOMIC_MOVE`), stale when `schemaVersion` or `analyzerId` differ (deleted on sight), 48 MB / 4000 entry cap with LRU trimming (reads refresh mtime). No Room. |
| Scheduler | `scheduler/DjAnalysisScheduler.kt`, `DeviceConditions.kt` | The app's `TrackAnalysisRepository`. Priority NOW_PLAYING > NEXT_UP > BACKGROUND, one worker on a background-priority thread, dedup + priority raise, `cancelStale`, battery-saver / low-battery / metered policy (`AnalysisPolicy`), retry back-off 5/20/80 s then a 10 min failure memory. Placeholder `UnavailableAnalyzer` throws `AnalyzerUnavailableException`, which the scheduler recognises and stops on. |
| Settings | `settings/DjSettingsRepository.kt` | Own Preferences DataStore (`dj_settings`), `Flow<DjSettings>`, values clamped on read. |
| Engine | `DjEngine.kt` | analyses -> planner -> decode two ranges -> renderer -> `PreparedTransition` -> callback to the adapter. Exposes `debug: StateFlow<DjDebugState>` (the "why" line). |
| Window maths | `window/WindowTimeline.kt` | Plan time / window time / source time mapping (source position = integral of the rate lane), eligibility, decode ranges, all timing constants (`WindowTuning`). |
| Controller | `window/TransitionController.kt`, `Alignment.kt`, `Deck.kt` | Pure, single-threaded state machine over `Deck`s + `DjClock`: LOCK_OUT -> XFADE_OUT -> WINDOW -> LOCK_IN -> XFADE_IN -> SETTLE -> DONE, abort from any phase. |
| Device glue | `window/ExoDeck.kt`, `DjTransitionRunner.kt` | `Deck` over ExoPlayer; runner ticks the controller every 10 ms and bridges to the adapter through `DjPlayerPort`. |
| DI | `di/DjModule.kt` | Koin; three placeholder seams (analyzer / planner / renderer, below). |
| UI | `composeApp/.../expect/ui/DjSettingsUi*.kt`, `SettingScreen.kt` | Settings -> Playback block next to the crossfade block. |

### Seams other work plugs into (the only bindings that change on merge)

```kotlin
single<TrackAnalyzer>            { UnavailableAnalyzer() }      // -> DspTrackAnalyzer (id "dsp-1")
single<TransitionPlanner>        { FallbackOnlyPlanner() }      // -> DjTransitionPlanner
single<TransitionWindowRenderer> { UnavailableWindowRenderer() } // -> adapter over OfflineMixRenderer
```

Until all three are bound DJ mode degrades to the normal crossfade: no analyzer -> no analysis -> the engine never
gets a plan; `FallbackOnlyPlanner` answers `SIMPLE_CROSSFADE`; a renderer that throws is reported in the debug line
(`error`) and nothing else happens.

**Contracts the merged code must satisfy**

* `TrackAnalyzer.analyze(videoId, PcmAudio)` gets mono float PCM at **22 050 Hz**, whole track. Output times are
  source-timeline ms. `id` participates in cache invalidation.
* `TransitionPlanner.plan(from, to, settings)` must return a plan for which `WindowTimeline.check(plan)` is `Ok`
  when it wants a window, otherwise the engine falls back with the rejection reason in the debug line:
  * `kind` BEAT_MATCHED (or CUT), `overlapMs > 0`;
  * the **outgoing** lanes are the identity at plan time `preRollMs - 2500 ms` (rate 1, pitch 0, volume 1, lowCut <=
    25 Hz, highCut >= 19 kHz) - the live player already plays exactly that;
  * once every lane has ended (`settled` = max(overlapMs, last keyframe)) the **incoming** deck is at rate 1.0 (+-0.003),
    pitch 0, unfiltered, at a constant gain in 0.05..4 (the loudness-match gain), and the outgoing volume is 0;
  * `outgoingSource(overlapMs) <= from.durationMs`, `entryPointMs < to.durationMs`, and the window must not start
    within the first 2 s of the outgoing track.
* `TransitionWindowRenderer.render(RenderRequest)` (`render/WindowRendering.kt`): output sample 0 is the mix at plan
  time `startRelMs`, the last sample the mix at `endRelMs`, exactly `sampleRate` Hz stereo, **no latency padding**;
  `outgoingTail.startMs` / `incomingHead.startMs` are the source position of each PCM's sample 0. The window is
  `[startRelMs, endRelMs] = [preRollMs - 2500, settled + 6000]`. The renderer must write to a temp name and rename.

### Wiring the real planner/renderer (NOT done: the merge was blocked)

The coordinator announced `DjTransitionPlanner` and `OfflineMixRenderer` on branch `ccr-f6e68cc7-xs37nk`. Merging that
branch from this worktree was **denied by the permission system** ("untrusted code integration"). I did not read or
integrate it by any other route, so `DjModule` still binds the three placeholders and nothing below is compiled against
the real classes. Everything I know of their API comes from the coordinator's message, not from the code. To finish:

1. Merge `ccr-f6e68cc7-xs37nk` into this branch (an owner decision).
2. In `di/DjModule.kt` replace `FallbackOnlyPlanner()` by `DjTransitionPlanner()` and `UnavailableWindowRenderer()` by
   the adapter below.
3. Check `WindowTimeline.check(plan)` accepts real plans (contract in section 2). If the planner exposes
   `TransitionPlan.settleMs`, use it instead of `max(overlapMs, last keyframe)` in `WindowTimeline.build/check`.
4. Add a JVM test that renders a real plan through the adapter and asserts the WAV length equals
   `windowMs` and that the first `LEAD_IN` of the window equals the outgoing source audio.

Adapter sketch (**names of `Options`/`Window` members unverified**; the mapping is the point: this app's window is
`[preRollMs - LEAD_IN_MS, settled + TAIL_MS]`, i.e. renderer lead = `LEAD_IN_MS` of plain outgoing audio *before* the
plan's pre-roll and tail = `TAIL_MS` after the lanes settle; both decoded PCMs must share ONE sample rate, which
`MediaCodecTrackDecoder` guarantees with `RENDER_SAMPLE_RATE`):

```kotlin
class OfflineMixRendererAdapter(private val renderer: OfflineMixRenderer = OfflineMixRenderer()) : TransitionWindowRenderer {
    override fun render(request: RenderRequest): RenderedWindow {
        fun seg(p: StereoPcm) = AudioSegment(org.simpmusic.dj.render.StereoPcm(left(p), right(p), p.sampleRate), p.startMs)
        val w = renderer.render(request.plan, seg(request.outgoingTail), seg(request.incomingHead),
            OfflineMixRenderer.Options(/* leadMs = WindowTuning.LEAD_IN_MS, tailMs = WindowTuning.TAIL_MS */))
        val tmp = File(request.output.parentFile, request.output.name + ".tmp")
        WavIo.write(tmp, WavIo.Wav(arrayOf(w.left, w.right), w.sampleRate))
        Files.move(tmp.toPath(), request.output.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return RenderedWindow(request.output, w.sampleRate, w.frames.toLong())
    }
}
```

Two things to reconcile at merge time because they were designed independently:

1. The renderer's own hand-off notion (`Window.handoffMs`, `handoffIncomingSourceMs`) versus this app's
   lock-then-cross-fade at `lockInWindowMs .. xfadeInWindowMs`. The app deliberately hands off *later* than the earliest
   possible point (it needs seconds of plain, settled audio to phase-lock a silent player onto), so it only needs the
   renderer to keep rendering plain incoming audio for `TAIL_MS`. The default tail of 500 ms is NOT enough: pass 6000 ms.
2. The decoded ranges: the renderer wants the outgoing audio from `exit - integral(rate over pre-roll) - >= 50 ms`; this
   app decodes from `outgoingSourceAtWindowStart - 750 ms` (a superset, since it also covers the lead-in).

## 3. Hook points in the patched core

Patch series: `patches/core/0001-…` (DelegatingForwardingPlayer overrides) and `0002-…` (adapter, DI, gradle). Apply
with `scripts/apply-core-patches.sh` (idempotent; `--check` reports applied / pending / CONFLICT; it never commits inside the
submodule). `scripts/dj-build.sh` applies then runs Gradle. Line numbers below are for `core@0b9ce7b` with both
patches applied.

| Hook | Where (`core/media/media3/src/main/java/com/maxrave/media3/…`) |
|---|---|
| `DjHooks` constructor parameter (null = original behaviour) | `exoplayer/CrossfadeExoPlayerAdapter.kt:92`; passed via `getOrNull<DjHooks>()` in `di/Media3ServiceModule.kt:198`; `djModule` loaded in `loadMediaService()` `:555` |
| Prepared-window callback wiring | `CrossfadeExoPlayerAdapter.kt:148` (`init`) |
| Crossfade is the DJ fallback: `crossfadeActive`, `crossfadeDurationMs` getter (uses `DjSettings.fallbackCrossfadeMs` while DJ is on) | `:335-345` |
| **(a) trigger**: 50 ms position poll -> `djPollTick` starts a prepared window when the outgoing SOURCE position reaches `outgoingSourceAtWindowStart - startLatency` | `:3154` (call), `:2918` (impl); guards `djBlockedReason()` (casting, listen-together `crossfadeSuppressed`, repeat one, video, speed/pitch != 1, album) |
| Plain crossfade held back while a window is ready/running | poll `:3161`, EOF path `:2056` (`djHoldsCrossfade`) |
| **(b)+(c) player plumbing**: `djPort` (window player = `createExoPlayerInstance()`, `commitToIncoming`, `onWindowRetired`, `onFinished`, `onFailed`) reusing `secondaryPlayer`, `setupPlayerListenerInternal`, `swapDelegate`, `setCrossfading`, `finalizeCrossfade` | `:3006-3122` |
| Synchronous abort at every crossfade-cancel site (commit-incoming, `seekTo(index)`, cleanup, load, release, cast) | `djAbortInternal()` calls at `:483 :770 :1450 :1623 :1940 :1965` |
| UI position while the window is audible | poll `:3145` (`djUiPositionMs`), session via `DelegatingForwardingPlayer.positionOverride` |
| EOF path | **no DJ trigger there, deliberately**: a window needs ~2.5 s of lead-in before the mix and is rendered ahead, so an end-of-track trigger cannot exist; if the poll missed the window the existing `handleTrackEndInternal` crossfade/normal transition runs untouched |

**Skip-silence** is turned off on the outgoing player when a window starts and on the window / incoming players;
restored by `finalizeCrossfade` (`internalSkipSilence`) or `djRestoreAfterAbort`.

**Which user effects apply**: the window player and the parked incoming player are ordinary
`createExoPlayerInstance()` players, so the user's equalizer / delay / reverb / sleep-fade processors apply to each
deck exactly as to any player (they are per-player instances reading the same `@Volatile` state) - i.e. exactly once
on whatever is audible. The crossfade filter of those players is disabled.

### Who is "current" during the window

* Before the commit (first ~1-2 s, silent lock) nothing changes: outgoing is current, the window is invisible.
* **At the commit** (`commitToIncoming`, just before the live->window cross-fade) the queue flips to the incoming
  track: `localCurrentMediaItemIndex`, `onMediaItemTransition`, listener moved to the parked live incoming player -
  the same "UI shows the next track when the fade starts" behaviour the plain crossfade has. The incoming player is
  `secondaryPlayer` and `isCrossfading` is true, so every existing guard (no radio trim, queue edits, `pause()`,
  `seekTo`...) already treats the mix as a crossfade.
* MediaSession delegate = the window player (a playing player, so the notification never shows "paused"); its
  MediaItem carries the incoming track's metadata. `DelegatingForwardingPlayer.positionOverride` reports the
  incoming track's mapped position (frozen at the entry point until T0, then `incomingSourceAt(t)`) and its duration;
  session seeks are rerouted to `adapter.seekTo`, which aborts the mix. When the window retires
  (`onWindowRetired`) the delegate moves to the live incoming player.
* The adapter's own progress (`cachedPosition`) follows `TransitionController.uiPositionMs()`.

### Abort semantics (seek, skip, pause, queue change, release, cast, errors)

* **Before the commit**: `TransitionController.abort()` releases the window, restores the outgoing volume; the
  adapter continues as if nothing had happened (`onFailed(committed = false)`); the pair is marked consumed so it is
  not retried on this playback. A pause / seek / track change is also detected by the controller itself (outgoing
  deck not playing > 400 ms, or the adapter's current player replaced).
* **After the commit** the incoming track is the current track (same direction as the existing crossfade code):
  `djAbortInternal()` is called *synchronously* at the top of every site that cancels a crossfade; it releases the
  window, parks the live incoming player at the **mapped** incoming position (or leaves it alone when it is already
  playing, near-locked), makes it the session delegate, and the site's existing code then promotes it
  (`commitIncomingAsCurrentInternal`). A pause therefore leaves the incoming track paused at the right position.
* Exceptions inside the controller tick or a window-player error take the same path with
  `onFailed(committed = …)`; if the parked incoming player itself is in error the adapter reloads the track at the
  mapped position (`loadAndPlayTrackInternal`).
* Nothing in the DJ path can leave the volume off: `finalizeCrossfade` restores `internalVolume`, and the loudness
  gain baked into the window is ramped to 1.0 over 1.5 s (`SETTLE`) before the promotion.

## 4. Timing, latency and jitter: what the code assumes (and how it was checked)

Read from media3 1.11.1 (`javap -constants` on the shipped classes):

* `DefaultAudioSink.getCurrentPositionUs` -> `AudioTrackPositionTracker`. With an AudioTrack timestamp the position
  is `timestamp position + frames elapsed on the system clock`; `AudioTimestampPoller` polls every **10 ms** while
  initialising (0.5 s), then only every **10 s** (`SLOW_POLL_INTERVAL_US`) once the timestamp advances, with an
  error-state interval of 500 ms; without a usable timestamp it falls back to `getPlaybackHeadPosition` (raw head
  refreshed at most every **5 ms**, `RAW_PLAYBACK_HEAD_POSITION_UPDATE_INTERVAL_MS`) smoothed with the last 10 offsets
  sampled every **30 ms**. `MAX_POSITION_DRIFT_ADVANCING_TIMESTAMP_US` is 1 ms: a timestamp that disagrees with the
  playhead by more is rejected.
* `ExoPlayer.getCurrentPosition()` extrapolates between renderer updates with the system clock, so it is continuous
  but only as good as the above. Expect single readings to wander by several ms (device dependent), which is why
  `PositionEstimator` takes the median of `position - t` over the last 24 ticks instead of one reading (JVM test:
  +-8 ms uniform noise -> worst error < 4 ms).
* Both hand-offs compare two players on the same output path/config, so the (large, device specific) output latency
  cancels; only their relative timestamp jitter and their own start/seek latencies matter.
* `AUDIO_OUTPUT_VOLUME_RAMP_TIME_MS = 20` in `DefaultAudioSink`: volume writes are cheap; 10 ms fade steps are fine
  for a 100 ms cross-fade.
* Sonic accepts any float speed/pitch in 0.1..8, but **no longer matters**: the window design never asks a live player
  for a non-unity rate, so the 0.02 `SPEED_PITCH_STEP` quantisation of the old crossfade is irrelevant here (DJ mode is
  blocked while the user's own speed/pitch is not 1.0 for the same reason).
* Self-calibration (`LatencyCalibrator`, kept for the process lifetime): `startLatencyMs` (how long after `play()` the
  first sample is out; initial guess 120 ms; EMA of the lag actually observed at lock) and `seekBiasMs` (how far a
  follower lags its seek target once the pipeline flushed; initial 80 ms; EMA of the residual after each corrective
  seek). Both start as guesses and converge over the first transitions; the JVM tests show them moving toward the
  simulated 180 ms / 120 ms.

**Expected accuracy (design target, NOT measured):** hand-off residual <= 6 ms (the lock tolerance) plus the unknown
position-signal bias between two ExoPlayers; a hand-off that could not lock within 3 seeks is *forced* only when the
residual is under 35 ms, otherwise the transition is aborted (before the commit) or the mix is cut over hard.
Beat alignment *inside* the mix is sample exact because it is rendered.

## 5. Tests and proof of compilation

* `:djAndroid:testDebugUnitTest`: 51 tests (JVM, no device): `TransitionControllerTest` (fake clock, fake decks with
  configurable start latency / seek latency / jitter / seek landing error: lock convergence from behind and ahead,
  gain sum of both cross-fades, ground-truth alignment at both hand-offs < 10 ms, calibrator learning, aborts in
  every phase, lock failure, unready incoming, exception containment, UI position mapping), `SchedulerTest`,
  `DjEngineTest`, `AnalysisStoreTest`, `ResamplerTest`.
* `:media3:testDebugUnitTest` (existing `EchoAudioProcessorTest`, `PartitionedConvolverTest`) passes with the patches
  applied; `:media3:compileDebugKotlin`, `:composeApp:compileAndroidMain` compile.

## 6. What could NOT be verified without a real device

1. **Hand-off seam quality.** Whether a 100 ms linear cross-fade between two ExoPlayers is inaudible depends on the
   real residual alignment, which depends on the position signal of a real AudioTrack. The whole lock design rests on
   `ExoPlayer.currentPosition` differences being trustworthy to a few ms; that is unmeasured.
2. **Decode skew** (`TransitionController.decodeSkewMs`): the window comes from our MediaExtractor/MediaCodec decode,
   the live decks from ExoPlayer's. Container pre-skip / encoder-delay trimming may place the same sample a few ms apart
   (Opus pre-skip is 6.5 ms; AAC priming differs per file). The lock aligns *reported positions*, not content, so any
   skew is a constant content misalignment at both hand-offs. Measure once per codec by cross-correlating an
   ExoPlayer `AudioProcessor` tap with our decode, then feed the number in.
3. **Start / seek latency values** and whether 3 corrective seeks fit in the 2.5 s lead-in on slow devices.
4. **Ranged `MediaExtractor.seekTo` accuracy** in WebM/Opus and fMP4/AAC as served by YouTube (cues present?), and
   `CacheBackedMediaDataSource` random access over `SimpleCache` spans.
5. **Memory / battery / thermal**: decode+render of ~25 s stereo 48 kHz x2 plus the render itself per transition.
6. MediaSession / notification behaviour when the delegate swaps to the window player and back, Android Auto, and
   Bluetooth variable latency (a route change mid-window would shift only the audible path, but was not exercised).
7. Real-world DJ quality obviously depends on the analyzer/planner/renderer that were not merged when this was
   written (placeholders bound).

## 7. Known limitations

* Only the outgoing -> incoming pair of the *next* queue item is prepared; shuffle/queue edits re-plan (pair changes
  discard the window).
* No BACKGROUND library warming yet: analyses are requested for the current and the next track only (the scheduler
  supports BACKGROUND; nothing enqueues it).
* The notification progress bar is frozen at the entry point until T0 and then follows the mix.
* `RepeatOne`, video, casting, listen-together and non-unity playback speed/pitch skip DJ (documented guards).
* Crossfade/EQ "Auto" durations are ignored while DJ mode is on (its fallback uses `DjSettings.fallbackCrossfadeMs`).
* Dev/QA aid missing: there is no in-app switch to force a specific plan; use the debug line in Settings.
