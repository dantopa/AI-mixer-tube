<div align="center">
  <img src="androidApp/src/main/res/drawable/app_icon.png" width="220" alt="Bagracho DJ">
  <h1>Bagracho DJ</h1>
  <b>A YouTube Music player for Android that mixes like a DJ: beat on beat, the 1 on the 1, and the 16th bar on the 16th bar.</b>
  <br><br>
  <a href="https://github.com/dantopa/ai-mixer-tube/raw/apk-dist/aidj-arm64-profile.apk">Download the latest test APK (arm64)</a>
</div>

---

Most auto-mix features line up **beats**. Bagracho DJ tries to go further and line up **phrases**. A chorus runs 16 bars, and the next section starts on the following 1. If the next song enters there, nothing gets cut in half. That is where a human DJ would mix, and it is the point Bagracho DJ is looking for.

It is a fork of [SimpMusic](https://github.com/maxrave-dev/SimpMusic) (GPL-3). The player, library, lyrics and everything else come from SimpMusic. This fork adds the AI DJ.

## What the DJ does

| Step | How | Where |
|---|---|---|
| **Decode** | MediaCodec decodes the cached stream to PCM, mono, 22 050 Hz. | `dj/android` |
| **Beats** | [Beat This!](https://github.com/CPJKU/beat_this) (MIT, ISMIR 2024) runs on-device through ONNX Runtime and gives beats plus a downbeat logit per beat. A DSP analyzer (spectral-flux onsets, autocorrelation tempo, Ellis DP beat tracking) adds key, energy, bass, timbre and the fallback grid. | `dj/ml`, `dj/brain` |
| **Grid repair** | Rebuilds grids that drop beats or lock onto the tresillo (common in cumbia) from a steady bar. | `GridRepair` |
| **Which beat is the 1** | Viterbi over position-in-bar, using every beat's downbeat logit at once. It is trusted only when the margin is clear. You can tap the 1 or shift it one beat at a time. | `BarPhase` |
| **Phrase lines (the 1 of 16)** | Per-bar evidence from changes in loudness, bass, highs, harmony and timbre, plus repetition (where a section comes back). A Viterbi over the 16-bar block chooses the lines, and the confidence is checked against a shuffled-bars null. When it is unsure, the bar counter (optional, in Settings) shows amber and you can mark the line yourself. | `PhraseGrid` |
| **Plan** | Scores exits and entries anywhere in the track. Beat-matches with tempo following and swaps the bass. Uses echo-out or a plain crossfade when tempos don't fit. **Perfect mix** puts the outgoing 1 on the incoming 1, and the 16-bar line on the 16-bar line when both tracks have one. It is tried first on every transition, and the regular plan is used when a pair does not qualify. | `DjTransitionPlanner` |
| **Render & splice** | The mix is pre-rendered (phase-vocoder time-stretch, EQ) and spliced *inside* the two players at an exact sample by an audio processor, instead of trusting player clocks. | `dj/android` (`splice/`) |
| **Choose the next track** | Library analysis while charging, "DJ: what next?", Auto DJ, and queue look-ahead that moves a track which beat-matches into the next slot. | `dj/android` (`auto/`, `recommend/`) |

The DJ is **on by default**. On Now Playing a **DJ** button turns it on or off; while it is on, a status line shows what it is doing, with **Mix now** beside it. Turn on Settings → AI DJ → **Bar counter** to see a **1 2 3 4** counter and the **n/16** phrase position, and to correct the 1 or mark phrases. Everything is logged (DJ log, exportable with the analyses and phasegrams).

**Explainer:** [`dj/docs/how-it-listens.html`](dj/docs/how-it-listens.html) is an illustrated walkthrough of every step on a real track (in Spanish; download it and open it in a browser): spectrogram, onsets, tempo, the phasegram and the phrase matrix.

## Honest status

- Beats: very good on the test corpus and on real cumbia exports.
- The 1: 1081 of 1112 bars right on a 13-track electronic corpus. There are no labels for Latin music yet, so your taps are the ground truth there.
- 8-bar phrases are trusted on roughly 1 track in 10. The 16-bar line is not yet trusted on real music without a manual mark. This is the current frontier.
- Much of this is verified only in simulation and unit tests. Device and by-ear results come from one tester's sessions.

Design notes live in [`dj/docs/`](dj/docs): `analysis.md`, `planner.md`, `android.md`, `ml.md` and `ai-landscape.md`.

## Building

```bash
# the Beat This! model is not in git; rebuild it (see dj/docs/ml.md) into
# dj/android/src/main/assets/beat_this_int8mm.onnx — without it the DJ falls back to DSP-only analysis
scripts/dj-build.sh                      # applies patches/core/*.patch to the core submodule
./gradlew :androidApp:assembleProfile    # R8 + AOT, non-debuggable, installs over the .dev debug build
```

The DJ hooks the player through a patch series on the `core` submodule (`patches/core/`), because this fork does not push to upstream `core`. Run the apply script before building.

Unit tests for the DJ logic: `./gradlew -p dj :brain:test`.

## Credits

- [SimpMusic](https://github.com/maxrave-dev/SimpMusic) by maxrave-dev and contributors: the whole app this is built on.
- [Beat This!](https://github.com/CPJKU/beat_this) (Foscarin, Schlüter, Widmer; MIT): the neural beat and downbeat tracker.
- [ONNX Runtime](https://onnxruntime.ai/), [Media3/ExoPlayer](https://developer.android.com/media/media3).
- Test corpus: tracks by Kevin MacLeod (incompetech.com, CC-BY 4.0). Audio is not stored in this repo.

## Disclaimer

This is a free, non-commercial, open-source personal project licensed under GPL-3, like the SimpMusic code it is based on. It hosts no media. Everything streams from YouTube's servers and remains the property of its owners. It is provided "as is", without warranty. Please support artists, for example with [YouTube Premium](https://www.youtube.com/premium). For the upstream app's own terms, see the [SimpMusic README](https://github.com/maxrave-dev/SimpMusic#legal-disclaimer--terms-of-use).
