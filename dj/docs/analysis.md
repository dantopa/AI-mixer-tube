# Track analysis (`DspTrackAnalyzer`, id `dsp-1`)

Package `org.simpmusic.dj.analysis`; pure Kotlin (JVM and Android). Mono `PcmAudio` at any rate is resampled to 22 050 Hz.
`DspTrackAnalyzer().analyze(videoId, pcm)`; `analyzeTimed()` returns per-stage milliseconds.

## Pipeline
- **prepare**: NaN/Inf to 0, clamp, Kaiser windowed-sinc rational polyphase resampler (zero delay), ~20 Hz DC blocker.
- **energy**: 100 ms block RMS (total and <200 Hz), `[1 2 1]` smoothed, max = 1. `loudnessDb` = whole-track RMS dBFS.
- **onset STFT**: 1024-pt Hann, hop 220 (9.98 ms), 48 mel bands, SuperFlux-style flux, plus a low-band (kick) curve.
- **spectral STFT**: 4096-pt, 100 ms hop; 13 MFCC, tuning-corrected peak-picked chroma.
- **tempo**: comb of the autocorrelation with a log-Gaussian prior around 120 BPM; the octave is decided from the kick band.
- **beats**: Ellis DP tracker, rigid mode (snap to one global grid when tempo is steady) and flexible mode (drifting period), parabolic sub-frame refinement, robust local line fit. `bpm` = 60 / median 8-beat span.
- **downbeats / meter**: chroma change across the bar line, multi-bar novelty, kick, bass entry; meter 3 only with clear evidence.
- **phrases**: every 4 bars (2 for short tracks) at the best-scoring alignment.
- **key**: Krumhansl-Kessler + Temperley on tuning-corrected chroma, with a "phrase start = tonic" tie-break.
- **sections**: checkerboard novelty on beat-synchronous chroma/MFCC/energy, snapped to phrase/downbeats, heuristic labels.
- **vocals**: always null (no labelled data to validate a heuristic).

## Confidence semantics
All 0..1; <=0.3 means do not act, >=0.7 is as reliable as this analyzer gets.
- bpm/beats: "a steady, onset-supported grid exists". It is NOT octave certainty: accept a partner tempo at x0.5 or x2.
- downbeats: honest and LOW on real music (0.03-0.5 on correct results). Do not gate hard on it for real music.
- key: partly discounted for relative-key and fifth-neighbour confusions.

## Measured
Synthetic: BPM error <= 0.006%, beat median error 1-2 ms, downbeats 1.00, key 24/24, no crash on noise/silence/NaN/short clips.
Real music (24 CC-BY tracks, references from Beat This! and published BPM): beat F@70 between 0.69 and 1.00 (1.00 on steady dance tracks),
tempo within 4% on 21/27 and within an octave on 24/27, key pitch class 10/13 vs librosa.
Speed: ~1 s warm for a 4-minute track on the dev box (phone speed unmeasured).

## Known failure modes
Tempo octave and 3:2 ambiguity; downbeat weights fitted on only 12 tracks; one global key; fuzzy section labels; DJ-mix tempo changes are not followed.
Bump `analyzerId` whenever a parameter changes behaviour.
