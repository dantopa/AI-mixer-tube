# Harmonic mixing: theory, evidence, and what it means for Bagracho DJ (2026-10-08)

Research notes requested by the owner ("become an expert in deep, serious mixing theory, about mixing harmonically").
Everything marked **measured** was computed here on the owner's 2026-10-07 export (166 analyses, 70 with
`structureFrames`). It was not judged by ear.

## 1. Why two songs clash: the psychoacoustics

Consonance has two layers, following Terhardt (as used by Gebhardt et al. 2016):

- **Sensory consonance: roughness.** Two partials closer than about one critical band beat against each other. Roughness peaks
  near a quarter of a critical band (Plomp and Levelt's curve). Below ~500 Hz a critical band is ~100 Hz wide, so in
  the bass register even notes a tone or a semitone apart sit inside the rough zone. Two basslines in different keys
  are the worst clash a mix can have. That is the harmonic case for the **bass swap** (one bassline at a time), not only
  the loudness case.
- **Harmony: pitch commonality / root relation.** Whether the two sonorities imply the same or related roots (virtual
  pitch). Two keys that share most of their notes and roots sound "together" even where some roughness exists.

Drums and noise-like sounds carry little pitch information, so they contribute roughness but almost no harmony. **A
percussive overlap is harmonically safe whatever the keys.** That is why DJs mix on drum-only intros and outros.

## 2. The key-based rules (Camelot / circle of fifths)

Camelot numbers the 12 positions of the circle of fifths. A = minor, B = major, and same number = relative keys.
Shared pitch classes between the two diatonic scales explain the folklore:

| Move | Example | Shared notes | DJ meaning |
|---|---|---|---|
| Same key | 8A → 8A | 7/7 | safe |
| Relative (A↔B, same number) | 8A → 8B (Am → C) | 7/7 | safe, changes mood |
| ±1 | 8A → 9A (Am → Em) | 6/7 | safe; +1 (clockwise) feels like a lift |
| Diagonal (±1 and ring swap) | 8A → 9B (Am → G) | 6/7 | usually fine |
| ±2 ("energy boost", Mixed In Key) | 8A → 10A | 5/7 | audible step up; short, percussive blend |
| ±3 / parallel (Am → A) | 8A → 11B | 4/7 | risky |
| ±7 (= one semitone up) | 8A → 3A | 2/7 | a deliberate "key change" moment, never a long blend |
| ±6 (tritone) | 8A → 2A | 2/7 | clash |

The shared-note counts are our own arithmetic on diatonic scales.

Related arithmetic:
- Without key lock, a tempo change of 5.95 % is exactly one semitone, i.e. ±7 on the wheel. Practitioners round this to the "6 % rule" and treat changes under ~3 % as keeping the key.
- Our renderer stretches time with a phase vocoder and keeps the pitch, so our tempo bend never changes the key.

## 3. Where key rules break

1. **Key detection is weak.**
   - Academic EDM key estimation scores roughly in the mid-70s to low-80s on the MIREX weighted score. That score itself gives half credit for a fifth and 0.3 for a relative, and most errors are fifths and relatives (Faraldo / Knees et al., GiantSteps).
   - In Gebhardt's listening test, Traktor agreed with a musical expert on only **6 of 20** short house excerpts. Traktor's key for an excerpt matched its key for the full track only **8 of 20** times.
   - **Measured (ours)**: only **14 of 166** of the owner's tracks (8 %) clear our `Trust.KEY` 0.45 (median confidence 0.26). In practice the planner ignores harmony on ~92 % of this music.
2. **A global key says nothing about the 16 seconds that actually overlap.** Songs modulate (a semitone or a tone up near the end is common in pop and cumbia), and loops and breakdowns use different chords. One global key also cannot rank two candidates that share a key, or tell where in the track the best overlap is.
3. **Tuning.** Tracks are not all at A440. A 30-50 cent offset between two "same key" tracks beats audibly, and whole-semitone shifting cannot fix it.
4. **Modal and loop music** (one-chord vamps, dorian/phrygian riffs, reggaeton's i-VI-III-VII loops) fits the major/minor templates badly. The relative-key ambiguity is structural there, not an error.
5. **Vocals.** Key compatibility matters most where both sides carry melody. A vocal over a vocal is a clash even in the same key, and pitch-shifting vocals sounds unnatural (Gebhardt's stated limitation).

## 4. What working DJs actually do (Kim et al., ISMIR 2020)

Kim et al. aligned 1,557 real mixes (1001Tracklists) with their 13,728 source tracks and 20,765 transitions. They found:

- **Tempo**: 86.1 % of tracks are played within 5 % of their original tempo, 94.5 % within 10 %, and 98.6 % within 20 %. The distribution is double-exponential around 0.
- **Key transposition is rare**: only **2.5 %** of 24,202 tracks are transposed at all, and **94.3 %** of those by exactly **one semitone**. DJs leave key lock on and choose compatible tracks instead of forcing them.
- **Transition lengths** peak at multiples of **32 beats (8 bars)**: DJs follow the phrase structure.
- **Cue points are shared knowledge**: for the same track, 40.4 % of cue pairs between different DJs fall within one bar, 73.6 % within 8 bars, and 86.2 % within 16 bars.

The automatic Drum & Bass DJ of Vande Veire & De Bie (2018) encodes the same practice:
- **Keys:** it allows same key, ±1 fifth or relative only. It also accepts a key one semitone off a compatible one, and pitch-shifts that track by the semitone.
- **Vocals:** it avoids overlapping vocals of both songs with vocal-activity detection.
- **Transitions:** it chooses among three transition types:
  - *double drop*: both drops aligned, 16-bar fade-in and 32-bar fade-out;
  - *rolling*: out 32 bars before the outgoing high-to-low change, in 16 bars before the incoming drop, 16-bar fades;
  - *relaxed*: into a low-energy section, the next song from its start.
  - A small Markov table alternates the three types.

## 5. Beyond the key: measuring consonance from the signal

- **Gebhardt, Davies & Seeber (DAFx-15; Applied Sciences 2016)**: they model consonance directly instead of comparing key labels.
  - **Method:** 20 strongest partials under 5 kHz, median per 1/16 note. One track is scaled over ±6 semitones in 1/8-semitone steps, and Hutchinson-Knopoff roughness (Plomp-Levelt curve on ERB) is computed between the two tracks, then refined with Parncutt/Hofmann-Engl pitch commonality.
  - **Listening test:** musically trained listeners heard 4-bar house excerpts. **Min-roughness mixes were rated significantly more pleasant than Traktor key matching** (p < 0.05), and both were far above no shift or max roughness.
  - **Stated limits:** short excerpts, no vocals.
- **Tonal Interval Vectors (Bernardes et al. 2016; TIV.lib, DAFx-20)**: a cheap chroma-domain version of the same idea.
  - **Definition:** `T(k) = w(k) · DFT_k(c / Σc)`, with k = 1..6 and `w = {3, 8, 11.5, 15, 14.5, 7.5}` (weights from empirical dyad-consonance ratings).
  - **Mixing:** a mix of two signals is the energy-weighted sum of their TIVs. Its **dissonance is `1 − |T| / |w|`**.
  - **Transposition:** transposing by p semitones is a phase rotation, so all 12 shifts cost almost nothing.
  - **Evidence:** a UPF master's thesis used TIVs on source-separated (non-percussive) chroma for EDM, and its suggested transpositions improved mixes for experienced users in **73.7 %** of cases.
- **AutoMashUpper (Davies et al. 2013/2014)**: scores "mashability" phrase by phrase from beat-synchronous chroma similarity over allowed key shifts, plus rhythm and spectral balance. The point that matters for us: **compatibility is a property of two sections, not of two songs.**

## 6. Practical technique when keys do not fit

From practitioner guides, which agree with each other:

1. Mix during **percussive** passages (drum intros and outros, breaks).
2. **EQ, bass first**: never two basslines (bass swap). Then **mids**: melodies and vocals live there, so drop the outgoing track's mids before the incoming melody enters.
3. **Shorter overlap** for a clash: a 4-bar blend hides what a 16-bar one exposes.
4. **Never stack two vocals.**
5. Exit with an **echo / filter** tail instead of a long blend.
6. A **one-semitone** shift of one deck, when it is needed, is what the professionals do. More than that sounds processed.

## 7. Bagracho DJ today

- `KeyEstimator`: a global key from tuning-corrected chroma (Krumhansl-Kessler + Temperley profiles, phrase starts weighted). It is trusted from `Trust.KEY` = 0.45, which 8 % of the owner's tracks reach.
- Planner: a Camelot distance ≤ 1 counts as compatible. Otherwise a pitch shift up to ±2 st is tried (off in simple mode), or the pair is marked a clash with a "short EQ-heavy overlap" (high cuts). Untrusted keys are ignored.
- Bass swap: back on by default since build ai.
- **Already stored but unused for harmony**: `structureFrames`, i.e. 12 chroma values per 500 ms, on every analysis made since build ae (70 of 166 in the export, growing).

## 8. Measured on the owner's tracks (`dj/ml/tools/tiv_compat.py`)

TIV dissonance of the mixed chroma (outgoing 62-95 % window vs incoming 2-35 % window, then four 16 s exit windows):

- **Self-test**: a track's end against its own start prefers no transposition in **68 of 69** tracks, so the measure behaves.
- Where both keys are trusted (5 tracks with frames): the TIV best shift equals the key-based shift (or a fifth from it) in **19 of 20** pairs.
- Over 4,692 ordered pairs, the best transposition is spread evenly over the 12 shifts (0 in 8 %, i.e. 1/12). That is expected for unrelated songs.
- **40 %** of pairs are within 0.03 of their best transposition as they are, i.e. harmonically fine untouched. The 0.03 threshold is arbitrary and was not checked by ear.
- For the same pair, moving the exit among four 16 s windows changes the dissonance by a median **0.022**, about the size of that threshold. **Where** the mix happens matters about as much as **which** song comes next.

## 9. Applied in build aj (2026-10-08)

- **`Harmony`** (`dj/brain`, `analysis/`): TIV excess dissonance of the actual overlap, from `structureFrames` (energy-weighted chroma, prefix sums, LRU of 64 analyses). `COMPATIBLE` = 0.025 (about a fifth apart on the owner's tracks), `CLASH` = 0.07 (about three fifths and beyond). A flat window gives exactly 0, so the tonalness gate (item 2 below) needs no code of its own. `Fit.bestShift` is computed and logged; nothing transposes with it yet.
- **Planner**: every exit/entry pair in `planAnywhere` pays `W_HARMONY` (0.35) × penalty. `finishBeat` takes the clash decision from the local fit, and from the global key only without frames.
- **Clash treatment rewritten** (item 3): one tonal layer at a time. The incoming deck enters high-passed at 900 Hz (hats and top only), both decks swap on the middle of the overlap, then the outgoing deck keeps only its top and fades its highs to 4 kHz. The old lanes LOW-passed the incoming at 2.5 kHz, i.e. brought in exactly its mids, where melodies and vocals clash. Overlap still capped at 9 s.
- **Look-ahead**: a queued next that beat-matches but clashes is now treated like one that does not beat-match. The other candidates are analysed, and one that beat-matches without a clash is moved up.
- **Measured** (`HarmonyReport`, the 70 analyses with frames, 4 830 ordered pairs, simple-mode settings, Perfect first): beat-matched pairs 1 717 → 1 722. Exit or entry changed on 808 pairs. Overlaps that clash 36.5 % → 26.3 %, mean excess 0.058 → 0.053. `W_HARMONY` 0.20 gave 28.6 % and 0.50 gave 25.0 %. Most of the remainder are pairs that clash everywhere, which is the look-ahead's and the clash treatment's job. **Not judged by ear.**

## 10. Recommendations, in order

1. **Local harmonic score in the planner (cheap, data already stored).** Compute the TIV dissonance of the actual overlap (exit window of A, entry window of B, at shift 0) from `structureFrames`, and add it as a scoring term so the planner prefers consonant exit/entry pairs. Use it in the look-ahead/recommender ranking too. Camelot stays for display only. It falls back to the global key when frames are missing.
2. **Tonalness gate.** A window with a flat chroma (low TIV magnitude, i.e. drums or noise) is harmonically free. Prefer such windows when the tonal score of the pair is bad, the way DJs mix on percussive intros.
3. **Dissonant pair, no better point:**
   - shorten the overlap to 4 bars;
   - bring the bass swap forward;
   - cut the outgoing mids before the incoming tonal content;
   - or echo out.
   The planner already has the lanes.
4. **Vocal activity** (cheap model, see `ai-landscape.md`): no vocal over vocal. Complementary to harmony, and the biggest audible clash after the bass.
5. **Optional ±1 semitone** on the incoming deck outside simple mode, only when TIV says it removes a clear clash and the incoming window has no vocals. That matches the 2.5 % / one-semitone practice.
6. **Tuning offset**: store the tuning the key estimator already corrects for, and treat a > 30 cent difference as a clash (or correct a few cents, which is inaudible to stretch).
7. Energy direction for Auto DJ: +1 clockwise (or +2) as the "lift" move, the Mixed In Key convention. Low priority.

## Sources

- Gebhardt, Davies, Seeber. *Psychoacoustic Approaches for Harmonic Music Mixing*, Applied Sciences 6(5):123, 2016 ([PDF](https://mediatum.ub.tum.de/doc/1304759/document.pdf)); *Harmonic Mixing Based on Roughness and Pitch Commonality*, DAFx-15 ([PDF](https://mediatum.ub.tum.de/doc/1292551/369694.pdf)).
- Kim, Choi, Nam et al. *A Computational Analysis of Real-World DJ Mixes using Mix-To-Track Subsequence Alignment*, ISMIR 2020 ([arXiv](https://arxiv.org/abs/2008.10267), [summary](https://mir-aidj.github.io/djmix-analysis/)).
- Vande Veire, De Bie. *From raw audio to a seamless mix: creating an automated DJ system for Drum and Bass*, EURASIP JASMP 2018 ([article](https://asmp-eurasipjournals.springeropen.com/articles/10.1186/s13636-018-0134-8)).
- Ramires, Bernardes, Davies, Serra. *TIV.lib: an open-source library for the tonal description of musical audio*, DAFx-20 ([arXiv](https://arxiv.org/abs/2008.11529)); Bernardes et al. *A multi-level tonal interval space for modelling pitch relatedness and musical consonance*, JNMR 2016 ([record](https://repositorio.inesctec.pt/handle/123456789/3897)).
- *Towards a new compatibility measure for harmonic EDM mixing*, master's thesis, UPF ([Zenodo](https://zenodo.org/record/5554688), [code](https://github.com/gbibbo/harmonic_mix)).
- Davies et al. *AutoMashUpper*, ISMIR 2013 ([PDF](https://staff.aist.go.jp/m.goto/PAPER/ISMIR2013davies.pdf)), IEEE TASLP 2014 ([PDF](https://staff.aist.go.jp/m.goto/PAPER/IEEETASLP201412davies.pdf)).
- Faraldo et al. / Knees et al. on EDM key estimation and the GiantSteps datasets ([UPF](https://repositori.upf.edu/server/api/core/bitstreams/7a5f186a-b66f-47fe-8ae4-8fc2a8f9a395/content), [ISMIR 2015](https://archives.ismir.net/ismir2015/paper/000246.pdf)).
- Practitioner guides: [Mixed In Key: Camelot wheel](https://mixedinkey.com/workflows/how-to-use-the-camelot-wheel/), [energy boost](https://mixedinkey.com/workflows/change-energy-with-camelot-wheel/), [DJ.Studio harmonic mixing](https://dj.studio/blog/harmonic-mixing), [Digital DJ Tips](https://www.digitaldjtips.com/beginner-1-2-3-of-mixing-in-key/), [Mixgraph pitch/tempo](https://www.mixgraph.io/tools/pitch-tempo).
