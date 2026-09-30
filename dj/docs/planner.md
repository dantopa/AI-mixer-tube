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
* `overlapMs` = wall time from T0 until the outgoing fader is 0. `plan.settleMs` = time after which every lane is
  constant (for BEAT_MATCHED that is the end of the incoming deck's tempo ramp back to 1.0).

## 2. Planner

`DjTransitionPlanner().plan(from, to, settings)` is a pure function. It never throws (an exception anywhere ends in a
SIMPLE_CROSSFADE with the error in `reason`), and the fuzz tests throw 4000 random garbage analyses (unsorted, negative,
NaN, empty grids, absurd durations, null facts, hostile settings) plus 500 realistic ones at it.

### Decision flow

1. **Gate.** Needs both beat grids (`Trust.BEATS`), both bpm (`Trust.BPM`), usable beat spacing, tracks >= 4 s.
   The analysis bpm must agree with the median of its own beat grid (x1, x2 or x0.5, 4%): the *grid* is authoritative
   for phase, the bpm only proves the grid is not a half/double artefact. Anything missing or low -> SIMPLE_CROSSFADE.
2. **Tempo.** Lattice periods `Lo = sOut*Po`, `Li = sIn*Pi` where `(sOut, sIn)` is 1:1, 1:2 or 2:1 (half/double-time
   equivalence, e.g. 87 -> 174 needs no stretch at all). Both decks are bent by half the log-difference:
   `rateOut = sqrt(Lo/Li)`, `rateIn = sqrt(Li/Lo)` (geometric split). Why: it minimises the larger of the two stretches,
   so the tolerated total difference is (1+bend)^2 (8% per deck = 16.6% total), and neither track is "the" stretched one.
   The option with the smallest bend wins, 1:1 on ties. If even that exceeds `maxTempoBend` on a deck: CUT (both grids
   *and* downbeats trusted) else SIMPLE_CROSSFADE. Periods are refined by a least-squares fit of the local grid around the
   chosen points (not the global bpm), and a grid whose fit residual exceeds 12% of a beat is rejected as irregular.
3. **Exit (outgoing).** Grid points in this order: phrase starts, downbeats, beats. If sections are trusted and there is an
   OUTRO: the first grid point at/after the outro start that leaves at least a 2-bar overlap, else the point (up to a
   phrase earlier) that allows the longest overlap. Without an outro: the last point that leaves room for
   `overlapBars` at the mix tempo + a 400 ms margin, else the last with room for one bar. Never before the room needed for
   the tempo pre-roll. "End of the audio" is the last audible energy hop, not the file length (trailing silence).
4. **Entry (incoming).** The first phrase start (else downbeat, else beat) at or after the first audible energy hop
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

* `CUT`: tempos incompatible, both grids trusted. Exit on a downbeat/phrase (same picker), entry on the first downbeat,
  10 ms fade-out ending at T0, 1 ms fade-in (any longer eats the incoming kick). `overlapMs = 0`. Never cuts on a plain beat.
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
  `outgoingSourceMs(t)`, `incomingSourceMs(t)` and their inverses. Effect hooks (`BlockEffect`) per deck and on the master.
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
