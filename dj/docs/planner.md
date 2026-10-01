# Transition planner and offline mix renderer

Packages `org.simpmusic.dj.planner` (decides) and `org.simpmusic.dj.render` (executes). Both are pure Kotlin/JVM, no
Android, no I/O. The planner turns two `TrackAnalysis` into a `TransitionPlan`; the renderer turns a plan plus the
audio into the mixed window. The renderer is the executable specification of the lane semantics and is also what the
device runs (pre-render the window, then play it as one stream).

## 1. Timeline semantics (what every executor must implement)

* **T0** is the wall-clock instant the incoming deck starts, at source position `entryPointMs`. The outgoing deck is at
  `exitPointMs` at that same instant. All lane keyframes are wall-clock ms relative to T0 (negative = before T0).
* **Source position is the integral of the rate lane over wall time**, anchored at T0:
  `outgoingSrc(t) = exitPointMs + integral_0^t outgoing.rate`, `incomingSrc(t) = entryPointMs + integral_0^t incoming.rate`
  (`t` may be negative for the outgoing deck: that is the pre-roll). `model.DeckClock` is the reference implementation
  (1 ms Simpson table, `sourceAt(t)` and the inverse `wallAt(source)`).
* Rate is a **pitch-preserving tempo multiplier**; `pitchSemitones` is on top of it and does not change the speed.
* The incoming deck plays only from t = 0 (its lanes before 0 are ignored). The outgoing deck plays from the start of the
  window until its `volume` lane is 0 for good (`silentFromMs()`), which for planned overlaps is exactly `overlapMs`.
* Per deck the signal path is: stretch/pitch -> low-cut (high-pass, `lowCutHz`) -> high-cut (low-pass, `highCutHz`) ->
  fader (`volume`, linear amplitude) -> sum -> master soft clip (identity below 0.95, never above 1.0).
  Ease applies to the segment that ENDS at the keyframe; the lanes hold their first/last value outside their keys.
* **Echo-out send (ECHO_OUT plans only, `plan.echoOut`).** The outgoing deck gets an aux send: a beat-synced feedback delay
  (`BeatEcho`) is fed from the stretched signal **before** the deck's filters and fader (send lane `echoOut.send`, like a
  send ahead of the channel EQ, so the repeats carry the full signal while the dry signal thins out under the rising
  high-pass) and its wet return (lane `echoOut.wet`) is added **after** the fader, so the tail keeps ringing once the
  fader is 0. The deck's source stops when its fader and its send are both closed; the delay is then fed silence until
  `echoOut.tailMs`. `settleMs` includes the tail. (The first version of this design put the send after the filters; the
  rising high-pass then removed most of what the delay could repeat: measured 12-18 dB quieter tails.)
* `overlapMs` = wall time from T0 until the outgoing fader is 0. `plan.settleMs` = time after which every lane is
  constant (for BEAT_MATCHED that is the end of the incoming deck's tempo ramp back to 1.0).

## 2. Planner

`DjTransitionPlanner().plan(from, to, settings, constraints = PlanConstraints())` is a pure function. It never throws (an exception anywhere ends in a
SIMPLE_CROSSFADE with the error in `reason`), and the fuzz tests throw 4000 random garbage analyses (unsorted, negative,
NaN, empty grids, absurd durations, null facts, hostile settings) plus 500 realistic ones at it.

### Contract additions (2026-09-30, all additive and backward compatible)

* `DjSettings.mixPoint: MixPoint = ANYWHERE` (`AT_END` = the earlier behaviour) and
  `DjSettings.minPlayedFraction: Float = 0.55f`. Stored settings without the fields decode to the defaults.
* `PlanConstraints(earliestExitMs: Long = 0)`, passed as a 4th argument: `TransitionPlanner.plan(from, to, settings,
  constraints)`. The interface keeps the 3-argument method abstract and gives the 4-argument one a default that ignores
  the constraints, so existing implementers and callers compile unchanged; `DjTransitionPlanner` implements both.
  `earliestExitMs` bounds the OUTGOING source position the plan may touch: `exitPointMs` **and** the start of any
  pre-roll ramp (`exitPointMs + preRollMs`) are both >= it. The engine should pass "playback position + time to decode
  and render the window (>= 40 s)".
* `PlanKind.ECHO_OUT` and `TransitionPlan.echoOut: EchoOutSpec? = null` (`delayMs`, `feedback`, `send`, `wet`, `tailMs`,
  `highpassHz`, `dampHz`, `pingPong`). `PlanKind.CUT` stays in the enum (stored plans still parse) but **the planner
  never emits it any more**. `settleMs` counts the echo tail.

### Decision flow

1. **Gate.** Needs both beat grids (`Trust.BEATS`), both bpm (`Trust.BPM`), usable beat spacing, tracks >= 4 s.
   The analysis bpm must agree with the median of its own beat grid (x1, x2 or x0.5, 4%): the *grid* is authoritative
   for phase, the bpm only proves the grid is not a half/double artefact. Anything missing or low -> SIMPLE_CROSSFADE.
2. **Tempo.** Lattice periods `Lo = sOut*Po`, `Li = sIn*Pi` where `(sOut, sIn)` is 1:1, 1:2 or 2:1 (half/double-time
   equivalence, e.g. 87 -> 174 needs no stretch at all). Both decks are bent by half the log-difference:
   `rateOut = sqrt(Lo/Li)`, `rateIn = sqrt(Li/Lo)` (geometric split). Why: it minimises the larger of the two stretches,
   so the tolerated total difference is (1+bend)^2 (8% per deck = 16.6% total), and neither track is "the" stretched one.
   The option with the smallest bend wins, 1:1 on ties. The "bridge" (both decks ramp towards each other, meeting in the
   middle) is this same split: each deck bends by half the log difference, so it closes gaps up to (1+bend)^2 and never
   stretches further than that. If even that exceeds `maxTempoBend` on a deck: **ECHO_OUT** (never a cut). Periods are refined by a least-squares fit of the local grid around the
   chosen points (not the global bpm), and a grid whose fit residual exceeds 12% of a beat is rejected as irregular.
3. **Mix point.** With `mixPoint = ANYWHERE` (default) steps 3-4 are replaced by the pair search of section "Mix anywhere"
   below; `AT_END` keeps the classic pick described here. **Exit (outgoing, AT_END).** Grid points in this order: phrase starts, downbeats, beats. If sections are trusted and there is an
   OUTRO: the first grid point at/after the outro start that leaves at least a 2-bar overlap, else the point (up to a
   phrase earlier) that allows the longest overlap. Without an outro: the last point that leaves room for
   `overlapBars` at the mix tempo + a 400 ms margin, else the last with room for one bar. Never before the room needed for
   the tempo pre-roll. "End of the audio" is the last audible energy hop, not the file length (trailing silence).
   **Entry (incoming, AT_END).** The first phrase start (else downbeat, else beat) at or after the first audible energy hop
   (leading silence is skipped, half a beat of tolerance).
5. **Overlap.** `overlapBars` bars at the mix tempo (a bar is `beatsPerBar` lattice steps; if the two tracks disagree on
   beats per bar or downbeats are untrusted the unit is one beat and the minimum is 4 beats). Clamped by: audio left in
   each file (hard), OUTRO length of the outgoing track and INTRO length of the incoming one (soft: floor of 2 bars when
   the audio allows), a key clash (<= 9 s). Always a whole number of units. < 1 bar possible -> SIMPLE_CROSSFADE.
6. **Key.** `Camelot.distance <= 1` is fine. Otherwise, if `allowKeyShift`, the smallest pitch shift (<= `maxPitchShift`)
   on either deck that makes the distance <= 1 (ties: the outgoing deck, because it is about to disappear and no
   return swoop is needed). Outgoing shifts ramp in during the pre-roll; an incoming shift is held through the overlap
   and glides back to 0 over 16 beats. No fix: the overlap is cut to <= 9 s ("short EQ-heavy": bass swap plus a
   high-cut sweep on the outgoing deck and an opening high-cut on the incoming one), and `reason` says `CLASH`.
7. **Lanes** (below), **confidence** = min of the facts actually relied on (beats, bpm, downbeats if bars are used,
   sections if the exit/clamp came from them, key if it drove a decision, grid regularity). Below `minConfidence`
   -> SIMPLE_CROSSFADE.

### Mix anywhere (`MixPoint.ANYWHERE`)

The owner's point: a mix does not have to happen at the end of the song. The planner searches pairs (exit on the
outgoing track, entry on the incoming one) across the whole tracks and scores them; the classic end-of-track pick is
always one candidate, so it stays reachable.

* **Exit candidates**: every phrase start (else every downbeat, else every 8th beat) of the outgoing track inside
  `[max(earliestExitMs, minPlayed, 4 beats + 250 ms), audibleEnd - 1 bar - 400 ms]`, where
  `minPlayed = min(minPlayedFraction * audibleEnd, 75 s)`; at most 40 (evenly thinned).
* **Entry candidates**: every phrase start (else downbeat, else every 16th beat) from the first audible sound to 60 % of
  the incoming track, with room for the overlap; at most 24. Intro start, first body, drops and breakdown ends are
  all phrase starts, so all of them are candidates.
* **Pair score** = weighted sum of terms in 0..1 (weights in one place, `MixScoring`): exit at a natural section
  boundary of the outgoing track (0.24: start of an OUTRO 1.0, start of a BREAKDOWN 0.95, end of a BODY/DROP 0.9; without
  trusted sections the energy curve: a fall from the 8 s before to the 8 s after), how much of the track has played
  (0.12), room left for the overlap (0.02), energy continuity between the outgoing exit region and the incoming entry
  region plus entry >= exit level (0.20, the `energy` curve), the same on the low band (0.06, the kick / bass), entry
  kind (0.16: intro start 0.9, body / drop start 1.0, breakdown start 0.4 unless the outgoing exit is inside a
  breakdown too, energy rise when there are no sections), grid level (0.06: phrase > downbeat > beat), how much of the
  incoming track is left (0.08, the tie-breaker among near-equal candidates), tempo bend (0.06, beat-matched pairs
  only). Ties break on exit then entry time: the same input always gives the same plan.
* **Search order**: (1) the 14 best-scoring pairs whose LOCAL tempos are compatible are tried as BEAT_MATCHED (local
  grid fit, irregular-window halving of the overlap, drift and confidence checks, the pre-roll must start at or after
  `earliestExitMs`); the first that works wins. (2) Otherwise the 8 best-scoring pairs are tried as ECHO_OUT. (3) Otherwise
  the classic AT_END logic (which also honours `earliestExitMs`), and finally a SIMPLE_CROSSFADE whose reason says why.
  A crossfade whose natural start is before `earliestExitMs` is shortened to what is left of the track.
* **No trusted beat grid on either track** (or one below `minConfidence`) is still a SIMPLE_CROSSFADE, never an echo.
* **Tempo-following** (2026-10-01): when one constant rate per deck would let the beats slide more than 4 ms over the
  overlap, the rate lanes follow each deck's local grid instead (`warpFor`): the quadratic `LocalGrid` fit makes the
  source position a quadratic of the beat index, so the rate that keeps one lattice step per wall beat is a LINEAR ramp
  over the overlap. Both ends must sit inside the bend; otherwise the old path (shorten the overlap until the drift fits
  `MAX_DRIFT_MS`, or give up) runs. The incoming still ramps back to 1.0 after the overlap. Verified on synthetic
  click tracks only (plan 0.76 ms, rendered audio 0.61 ms, vs 119 ms with a constant rate).

### Echo-out (`PlanKind.ECHO_OUT`)

For tempos that cannot be matched (beyond the bend, or no candidate pair locks): the classic DJ echo-out. Nothing is
stretched: both decks run at native rate 1.0, no pitch shift (the tail is high-passed and short; a key clash is only
noted in the reason).

| lane | outgoing | incoming |
|---|---|---|
| volume | equal-power `cos` over the last bar before T0 (`dryFadeMs` = one bar, 1.5-4.8 s), **0 at T0** | 0 -> 1 in 2 ms at T0 (de-click), constant 1; a louder incoming deck starts at the matched gain and glides to 1.0 at `tailMs` |
| lowCut | exponential 20 Hz -> 700 Hz over the same bar (the dry signal thins out, its bass is gone at T0) | off |
| echoOut.send | 0 -> 1 over half a beat, held, closes over the last quarter beat before T0 | - |
| echoOut.wet | 1.0, then a smoothstep fade to 0 over the last bar (or half the tail) ending at `tailMs` | - |

Delay = 3/4 of the outgoing beat when that lies in 200-750 ms, else 1/2, 1/4, 1 beat; feedback 0.6; the loop has a
280 Hz high-pass (repeats never carry bass, so they do not fight the incoming kick) and a 6 kHz damping low-pass, both
gain <= 1, so it is stable; ping-pong (left/right alternate). Tail = 3 bars clamped to 3.5-8 s. Exit and entry come from the
pair search (or, for AT_END, from the classic pick); `overlapMs = 0`. Confidence = min of the beat grid confidences (and
sections when the exit came from an outro), checked against `minConfidence`.

### Lane shapes (BEAT_MATCHED)

| lane | outgoing | incoming |
|---|---|---|
| rate | smoothstep 1.0 -> `rateOut` over `[-ramp, 0]` (ramp = 8 beats, 12 if bend > 2%, 16 if > 5% or key shift), then constant | constant `rateIn` over `[0, overlap]`, smoothstep back to 1.0 over the next 8-16 beats |
| pitch | (only if shifting it) smoothstep 0 -> s over the same pre-roll, held | (only if shifting it) held over the overlap, smoothstep to 0 afterwards |
| volume | equal-power `cos(x*pi/2)` in 24 linear segments over `[0, overlap]`, ends at 0; if it is the louder deck it first dips (smoothstep, over the pre-roll) to the matched gain | `sin(x*pi/2)`, matched gain held for the first half then glides (dB-linear) to 1.0 |
| lowCut | 20 Hz until the middle, exponential to 250 Hz over one beat (swap point at the middle of the overlap) | 250 Hz until the middle, exponential down to 20 Hz over the same beat |
| highCut | 20 kHz until the middle, exponential to 9 kHz at the end (>= 4 lattice beats) | off |

* **Phase lock.** Outgoing wall beat period is `Lo/rateOut = W`, incoming `Li/rateIn = W` with `W = sqrt(Lo*Li)`, and both
  rates are constant from T0 to the end of the overlap, so lattice beats coincide for the whole overlap regardless of what
  the ramp did before T0 (only the anchors matter). The tempo therefore reaches its target *before* T0 while only the
  outgoing deck is audible. Proven in tests on the lane maths (`DeckClock`, <= 1.6 ms over 392 random beat-matched plans,
  which is exit/entry being whole ms) and on rendered audio (0.07-0.9 ms, section 4).
* **Loudness match** only ever attenuates the louder deck (clamped 6 dB), so no gain is above 1.0.
* **Bass swap** happens if `bassSwap` is on, both decks have low-band energy in their overlap windows (> 0.15) and the
  overlap is >= 4 lattice beats.
* `mixBpm` = `60000/W`, the tempo of the lattice (for half/double time that is the slower track's feel).

### Other plan kinds

* `CUT`: **deprecated, no longer planned** (a bare beat-aligned cut was judged unacceptable: the listener hears no blend
  at all). The enum value stays for compatibility; executors keep handling it. Tempo-incompatible pairs get `ECHO_OUT`.
* `SIMPLE_CROSSFADE`: equal-power, `fallbackCrossfadeMs` clamped to the audible end of the outgoing file and the length
  of the incoming one; exit = audible end - fade, entry = first audible hop (0 if < 100 ms). Rates 1.0, no filters,
  `confidence = 1` (it relies on no musical fact; `reason` says why we fell back).

### Failure modes / known weaknesses

* A grid that is straight but *phase-shifted by a fraction of a beat* (analyzer put the beats on the wrong part of the
  hit) still locks exactly to the *analysis*, not to the ear.
* Downbeat errors of a whole beat (bar-1 detected on beat 3) produce a musically wrong but phase-locked mix.
* Sections and the 'first audible' threshold are heuristics of the analyzer; wrong outro = exit in the wrong place.
* Tempo changes inside a track are handled only through the local fit (window = overlap); a track with a real
  tempo ramp inside the window is rejected as irregular.
* Key shift moves *both* tonal and percussive content; > 2 semitones is audible on vocals.
* The loudness match uses integrated RMS loudness, not K-weighted LUFS.

## 3. Renderer

* `DeckStretcher`: phase vocoder, 2048-point FFT at 75% overlap, identity phase locking around spectral peaks, phase reset
  of everything above 150 Hz on transients, stereo phase decisions taken on L+R and applied as one rotation to both
  channels (image preserved), pitch = stretch by `rate/pitch` then cubic resample. WSOLA was rejected: its waveform
  search moves audio by up to its tolerance (a few ms sawtooth) which breaks beat alignment between decks. At rate 1 /
  pitch 1 it is an exact identity (max error 6e-8) and after a rate lane returns to 1.0 the phases relock onto the
  source within ~300 ms (residual -120 dB), which is what makes the hand-off to a live player seamless.
  Sample `i` of the output plays source position `A(i)` = anchor + integral of the rate automation (no delay, no fade-in:
  frames before the first sample are pre-rolled from the source). It queries the rate `latencyFrames` = 1024 (23 ms at
  44.1 kHz) ahead of the sample it is delivering and reads the source that far past `A(i)`; pre-cut segments therefore
  need >= ~25 ms of margin on both sides of what plays (the tests use 200 ms). Rate range 0.25..4 (clamped), stereo or mono.
  Only `kotlin.math` and FloatArray/DoubleArray, no allocation after construction.
* `RbjBiquad` (RBJ cookbook, Butterworth Q) inside `SweepFilter`: cutoff slewed per 32-sample sub-block in the log
  domain (10 ms), and the stage fades in/out of the path (20 -> 45 Hz for the high-pass, 19.5 -> 15 kHz for the low-pass)
  so a lane at its 'off' value is an exact bypass and there is no zipper noise.
* `OfflineMixRenderer.render(plan, AudioSegment out, AudioSegment in, Options): Window` (also a whole-track
  `render(plan, PcmAudio, PcmAudio, leadMs, tailMs)`). Inputs are stereo (`StereoPcm`, mono duplicates) at one sample
  rate (44.1/48 kHz); `AudioSegment.sourceStartMs` lets you pass pre-cut audio while plan times stay in track time.
  First output sample = plan time `-preRollMs` (default lead), the window ends `settleMs + tailMs` after T0 (default
  tail 500 ms), `Window.handoffMs` is where the audio equals the native incoming source. `Window` also exposes
  `outgoingSourceMs(t)`, `incomingSourceMs(t)` and their inverses. Effect hooks (`BlockEffect`) per deck and on the master. An `echoOut` on the plan is executed by the renderer itself
  (`BeatEcho`, see section 1): nothing to pass in `Options`; with `bypassMixer` the echo is skipped.
* `BeatEcho`: two fixed ring buffers (no allocation while processing), ping-pong or per-channel feedback, one-pole
  high-pass and low-pass inside the loop; `processWet` is the aux form, `process` the insert form (`in + wet`) for the
  `BlockEffect` hooks.
* Real-time factor for a 56 s window (24-bar overlap): 0.027 single-threaded on the dev CPU.

## 4. Measured (tests print these; see `dj/brain/src/test`)

* deck alignment, click tracks, each deck rendered alone from the same plan: 124->128, 128->124, 87->174, 174->87,
  120->130, 100->100: max beat misalignment inside the overlap 0.07 / 0.07 / 0.82 / 0.91 / 0.35 / 0.00 ms (limit 5),
  T0 error <= 0.9 ms (fixture beats are whole ms), 48 kHz 0.20 ms.
* stretcher: tempo error over 30 s <= 0.012% (rates 0.7-1.4; <= 0.005% in the DJ range), click position error <= 0.3 ms
  at +-4%, <= 3.4 ms at 0.7; pitch error < 0.1 cent (110 Hz-1.76 kHz, -2..+5 st, with simultaneous tempo change); largest
  sample step of a stretched 440 Hz sine 1.00-1.12x the unstretched one (no clicks, including hard rate steps); source
  position accounting error 0.07 frame under a varying rate lane; transient compactness 0.998.
* full mix of the synthetic band tracks: peak 0.82-0.83 (no clipping), mix never more than 2.05 dB below the weaker
  deck's own level (limit 4 dB), tail vs native source: 0.0 ms lag, -120 dB residual, pre-cut segments identical to
  whole tracks.

### Real music: mix anywhere and echo-out (13 tracks, 156 ordered pairs, Beat This! analyses; 2026-09-30)

Tools: `PlanHistogram` and `MixAnywhereReport` in `dj/ml/src/test` (env `DJ_CACHE`, `BEAT_THIS_CORPUS`, `DJ_OUT`).

| | BEAT_MATCHED | CUT | ECHO_OUT | SIMPLE_CROSSFADE |
|---|---|---|---|---|
| before (end-of-track planner) | 46 | 70 | - | 40 |
| AT_END now | 46 | - | 77 | 33 |
| ANYWHERE now (default) | 61 | - | 87 | 8 |

* Exit position as a share of the outgoing track, pairs per 10 % bucket. Before: 70 %: 3, 80 %: 28, 90 %: 125 (all
  at the end). ANYWHERE: 40 %: 16, 50 %: 10, 60 %: 22, 70 %: 17, 80 %: 17, 90 %: 74; beat-matched median 0.78
  (min 0.45), echo-out median 0.91. The 8 crossfades that remain are analyses below `minConfidence` (0.48 < 0.5) or a
  beat grid that is irregular around every candidate.
* With `earliestExitMs = 130 s` the plans move later (exits 45-99 %) and 17 pairs end in a crossfade because the track has
  no acceptable exit left (or, twice, no echo-out point) - always with the reason in `plan.reason`.
* Determinism: the whole matrix planned twice is identical.
* Rendered echo-out, real music: the tail is 5-15 dB under the outgoing track's level before the fade in the first
  second after T0 and falls ~10 dB/s (synthetic tracks: -40 dB -> -60 dB -> -75 dB per second). Whether that is
  audible enough *under* a loud incoming track is not verified by ear.


### Tempo consistency gate (2026-09-30)

A device log showed a BEAT_MATCHED plan whose incoming deck was stretched from a local tempo of 74 bpm while the same
track's tempo field said 110, its beat count over its length said 103 and its median beat interval said 130. The tracker
had dropped and doubled beats in the intro, and the planner had matched against that grid. `finishBeat` now measures the
tempo the plan would actually stretch each deck by (the local fit's median interval) and requires it to match the track's
overall tempo (`TrackContext.avgBpm`, beats per unit time, or the tempo field), allowing x1 / x2 / x0.5 within 6 %.
Otherwise that pair fails with the numbers in `plan.reason` and the search moves on (usually ending in ECHO_OUT).
On the 13 real tracks (156 pairs) BEAT_MATCHED drops 61 -> 31 and ECHO_OUT rises 87 -> 117; the tracks that lose their
beat-matches are the ones whose grids disagree with themselves (tea_roots, waltz_tschaikovsky_op40, canon_in_d).
Caveat on all the corpus numbers in this file: the corpus is mostly clean electronic music where median = average =
field tempo. Pop / Latin / live recordings (the owner's library) look much messier.

### Mixes were still landing at the outro (2026-09-30, second device log)

Two device logs in a row planned exits at 94 % and 97 % of the outgoing track although `mixPoint` was ANYWHERE. The
scoring had a deliberate late bias (`W_PLAYED` 0.12, "mildly prefer later") and an outro-boundary term, and the top
pair always won. Changes: `W_PLAYED` 0.12 -> 0.02 (weight moved to the boundary and energy terms), an outro-zone
penalty (up to 0.10 for exits past 88 % played), and a seeded variety pick among pairs within 0.05 of the best score
(same pair -> same plan). Exit position, 156 real pairs, per 10 % bucket: before `... 3 71` (>=90 %: 71) ->
now `0 0 3 11 36 25 34 6 0 41` (>=90 %: 41). Still a heuristic: whether a mid-song exit sounds better is not verified by ear.

Correction to the variety pick (third device log): it also varied the ENTRY, and a plan entered the incoming track at 23 %
(34 s in), so the listener never heard its intro. The pick now only varies the exit: entries stay within the first 15 % of
the incoming track unless the best-scoring pair already enters later. Entry position over the 156 real pairs, per 10 %
bucket: `112 28 0 1 7 0 0 0 0 0` (the 8 deeper ones are the best pair's own choice).

### Simple mode and bass drop-out exits (2026-09-30)

Owner feedback after a device test: the echo-out mix "sounded bad and drifted"; the wish is "keep the same base, or else a
crossfade", leaving the outgoing track where its base stops. `DjSettings.allowEchoOut` (default true for the contract, false
under simple mode) makes `buildEchoOut` fail, so incompatible pairs end in SIMPLE_CROSSFADE. `MixCandidates.exits` adds
`bassDrop` (0..1: low band in the 4 s after the exit vs the 8 s before, for exits that had a base, >= 0.12 of the track's
loudest hop) with weight 0.14 (boundary 0.29 -> 0.15). Corpus (156 pairs): exits that have a base and lose more than half
of it right after: 26 -> 59 of 148. Simple mode (no echo, bend 6 %): BEAT_MATCHED 22, SIMPLE_CROSSFADE 134.
