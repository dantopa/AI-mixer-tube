# AI DJ: what exists today, what is worth using (as of 2026-09-30)

Scope: a personal-use fork of a GPL-3 Android/Desktop player; audio comes from decoded YouTube Music streams.
Method: web research (URLs inline) plus a few timings measured on this sandbox (4 CPU cores, 15 GB RAM, no GPU,
onnxruntime 1.30 CPU). Anything about phones that I did not measure is labelled **[supposition]**.

Licence rule of thumb for this fork: personal use makes CC-BY-NC weights *legal to run*, but they can never be
shipped in a release build and they contaminate anything trained on them. Prefer MIT/Apache/CC-BY weights.

## 0. Executive summary

* The analysis layer is a solved problem for our purposes: **Beat This! (MIT, ONNX)** is the beat/downbeat model to adopt.
  Everything else (structure, key, embeddings) is either already handled by our Kotlin DSP or is a "spike later".
* **Stems are the biggest quality unlock, and are feasible on-device only as background pre-computation of the
  transition window, never as live separation.** Measured htdemucs ONNX RTF here: 0.85 (4 threads).
* **Generative audio is a PC/cloud toy for this project, not a phone feature.** A synced drum-loop/riser layer
  built from samples is cheaper, deterministic, and should come first.
* "Thinks by itself" is best implemented as an **LLM planning layer over deterministic tools** (set arc, next-track
  shortlist, veto/explain), running online or on the user's PC, with a rule-based fallback. Not as an end-to-end model.
* **Training our own compatibility model on public DJ-mix data is technically possible but not justified** now:
  the labelled data is tiny (~20k transitions), audio must be re-acquired, the label is "a DJ did it", not "it sounded good".
  A personalised linear/GBM re-ranker on the user's own skips/likes is the right ML to train.
* Hard ceiling on a phone is not compute, it is **output latency uncertainty (Bluetooth 60-250 ms)** and
  **stream-derived audio (lossy, no true grid, no stems)**. A good beat-matched blend of two decoded streams is
  achievable; a "Traktor-controller" transient-perfect mix is not.

## 1. Beat / downbeat / tempo / structure / key

| Item | What | Licence | Size | Latency | On phone | Verdict |
|---|---|---|---|---|---|---|
| **Beat This!** (ISMIR 2024, [paper](https://arxiv.org/abs/2407.21658), [repo](https://github.com/CPJKU/beat_this)) | Beats+downbeats, no DBN post-processing, transformer over 30 s chunks | Code and weights MIT (some training data is not) | final ~78 MB fp32 (ONNX 82 MB), small ~8 MB ([HF ONNX](https://huggingface.co/aaatmy/beat-this-onnx), [others](https://huggingface.co/musetric/beat-this-onnx)); int8 ONNX ~21 MB reported | **Measured**: final fp32, 1500 frames (30 s), 1 thread 3.9 s (RTF 0.13); 4 threads 3.7 s (no scaling). 4-min track = ~30 s here | Yes via ORT-Android; spectrogram frontend (128 mel, 50 fps) must be reimplemented in Kotlin (a C++ port exists: [beat_this_cpp](https://github.com/mosynthkey/beat_this_cpp)) | **Adopt now** (final for analysis-on-add, small as fallback) |
| **All-In-One** ([repo](https://github.com/mir-aidj/all-in-one), [paper](https://arxiv.org/abs/2307.16425)) | Beats, downbeats, tempo, functional segments (intro/verse/chorus...) on *demixed* input | MIT code; weights trained on Harmonix (pop) | ~300 K params, but needs Demucs stems as input | Dominated by Demucs (see 3) | Not practical: Demucs + NATTEN | **Skip** on phone; **spike later on PC** to label sections offline. Our DSP section detector is enough for DJ cue points (need phrase boundaries, not "chorus") |
| **madmom** | RNN + DBN beat/downbeat/key | Code BSD; **models CC BY-NC-SA** | ~tens MB | Python only | No | **Skip**: superseded by Beat This on accuracy (Beat This paper, tables vs madmom); DBN even lowers F1 when bolted onto Beat This on SMC ([failure analysis](https://arxiv.org/html/2605.12287v1)) though it improves tempo-continuity metrics, worth remembering for constant-tempo EDM |
| **Essentia** TempoCNN / key ([models](https://essentia.upf.edu/models.html)) | Global tempo, key (Krumhansl-style + CNN) | Library AGPL-3 (GPL-compatible? one-way with GPL-3: fine); **models CC BY-NC-SA 4.0** | 1-20 MB each | ms-scale | Would need the C++ lib via NDK | **Skip**: our Kotlin key/tempo already exists; NC models add nothing to Beat This for tempo. Key: only spike if own key estimator is measurably wrong on user's library (keep as a "check" script on PC) |
| **MERT / MuQ / MusicFM** | SSL music embeddings, strong on key/tempo/tags probes | MuQ and MuQ-MuLan weights **CC-BY-NC 4.0**, code MIT ([MuQ](https://github.com/tencent-ailab/muq)); MERT-95M weights CC-BY-NC | 95 M-630 M params | seconds per track on CPU **[supposition, not measured]** | No (too big/slow) | **Skip for analysis**, see section 2 for embeddings |
| Neural segmentation (SALAMI/Harmonix models, "MSAF", SpecTNT) | Boundaries | mixed, mostly research code | small | fine | Possible but low value | **Skip**: DJs need 8/16/32-bar phrase boundaries; downbeats + energy novelty from our own DSP already give that |

Note on our tracker output: the biggest known failure modes of any beat tracker are half/double tempo and
downbeat-phase errors on ambient / rubato / live material. Mitigation that costs nothing: run Beat This on the
*full* track, fit a constant-tempo grid when the fit residual is small (EDM), and mark the track "grid-unreliable"
otherwise so the planner falls back to a plain equal-power crossfade.

## 2. Embeddings for recommendation, and learned transition compatibility

### 2.1 Embedding models

| Model | Licence | Size | Notes | Verdict |
|---|---|---|---|---|
| **Discogs-EffNet / MSD-MusiCNN + Essentia classifiers** (danceability, mood happy/party/relaxed/sad/aggressive, arousal-valence, voice/instrumental, 400 Discogs genre styles; [list](https://essentia.upf.edu/models.html)) | **CC BY-NC-SA 4.0** (proprietary licence on request) | EffNet ~tens of MB; MusiCNN ~3 MB | Cheap CPU; gives Spotify-like features (danceability, valence/arousal) and a 1280-d embedding. ONNX exports exist for EffNet | **Spike later (PC first)**. Personal-use fine; useful precisely because it yields *interpretable* dims. Do not ship weights |
| **LAION-CLAP** | Apache-2.0 code, weights open (check per checkpoint) | ~150-600 M | Text-audio joint: enables "dark rolling techno for 2 am" queries | **Spike later**, only if we want text-to-track search; too heavy for phone |
| **MuQ-MuLan** | CC-BY-NC weights | 630 M | Best-in-class text-music retrieval, heavy | Skip on device; optional PC batch job |
| **MERT** | CC-BY-NC | 95-330 M | Great representations, slow | Skip |
| Own cheap features: MFCC/chroma stats, our energy curve, BPM, key, spectral centroid/bass ratio | ours | 0 | Already there | **Adopt (keep)** |

Honest take: for "what mixes well after this", embeddings matter far less than **BPM proximity (or half/double
relation), Camelot key distance, and energy trajectory**. Embeddings add "vibe/genre continuity", which is the
part users notice when a track is *wrong*. The sensible design is a two-stage recommender:

1. **Hard filter** (rules): BPM within about +-6 % (or x2/x0.5), Camelot distance <= 1 (or +-2 for energy boost), not recently played.
2. **Soft score**: `w1*cos(emb_a_tail, emb_b_head) + w2*energy_delta_fit(target arc) + w3*key_score + w4*user_affinity`
   where the embedding is computed on the *last 30 s of A* and *first 30 s of B* (transition-local, not whole-track).
   The Essentia EffNet 1280-d vector is enough; weights start hand-set, later learned from skips (2.3 / 4d).

### 2.2 Published work on learned mixes and transitions

* **DJ-mix analysis dataset (ISMIR 2020)**: 1,557 mixes, 13,728 unique tracks, 20,765 transitions from 1001Tracklists,
  aligned mix-to-track with subsequence DTW; findings on tempo adjustment, transition length, cue-point agreement
  ([paper](https://arxiv.org/abs/2008.10267), [analysis page](https://mir-aidj.github.io/djmix-analysis/),
  [code/data notes](https://github.com/mir-aidj/djmix-analysis/blob/master/index.md)). Metadata came by personal
  communication with 1001Tracklists; the licence is not stated; audio is *not* redistributed (links only, many dead).
* **DJtransGAN** (Chen et al., ICASSP 2022): differentiable DJ mixing ops (EQ, fader, tempo) + GAN discriminator
  trained on that data to generate transitions ([paper](https://arxiv.org/abs/2110.06525)). Shows the differentiable-mixer
  idea works; the outputs are parameter curves for *the same kind of transition our planner already generates by rules*.
* **DJ AI** (2025, playlist alignment with generative and embedding models; [ACM](https://dl.acm.org/doi/10.1145/3771594.3771640)) and
  **Temporal considerations in DJ-mix IR and generation** (TIME 2025; [pdf](https://drops.dagstuhl.de/storage/00lipics/lipics-vol355-time2025/LIPIcs.TIME.2025.20/LIPIcs.TIME.2025.20.pdf)):
  frame track selection as sequential recommendation; agree that key/rhythm compatibility predicts transition
  smoothness. Nothing here is a released, drop-in model.
* Also: "Automatic detection of cue points for DJ mixing" ([Computer Music J. 2022](https://direct.mit.edu/comj/article/46/3/67/117159/Automatic-Detection-of-Cue-Points-for-the)), Mixed-In-Key-style tools
  are closed source. LLM-based hobby projects exist ([example](https://github.com/kckDeepak/AI-DJ-Mixing-System)); none has published evaluation.

### 2.3 Can we train a compatibility model here? Honest answer: not worth it, and here is the only version that is

* Data reality: ~20 k positive transitions (DJ-mix dataset), house/trance heavy, **positives only** ("a DJ chose this"),
  no measure of how it sounded. Negatives must be sampled (random pairs), so the model learns "looks like a 1001Tracklists
  pair", i.e. genre + BPM + key, which our rules already encode. Audio has to be re-downloaded from dead-prone links
  (legal/availability friction) and features re-extracted (about 14 k tracks x ~30 s each with Beat This at RTF 0.13 =
  ~1.5 h per pass on this box, plus embeddings).
* If someone insisted: (1) extract per track: Beat This BPM/downbeats, our key, energy curve, EffNet embedding of tail/head
  windows; (2) build (A tail, B head) positives from timestamps, sample 5-10 negatives per positive stratified by BPM to
  avoid the trivial shortcut; (3) train a **gradient-boosted tree or a 2-layer MLP on hand-built pair features** (BPM ratio,
  key distance, embedding cosine, energy delta). That is minutes of CPU. A fine-tuned audio encoder (contrastive on
  mixes) is a GPU job and would not beat the feature model with only 20 k pairs.
* Expected value: small over rules. **Verdict: skip the public-data model.** Invest in on-device
  personalisation from the user's own skips/likes (4d) instead, which is the signal that actually matches *this* listener.

## 3. Source separation

| Model | Licence | Size | Speed | Phone | Verdict |
|---|---|---|---|---|---|
| **htdemucs / htdemucs_ft** ([Demucs, MIT](https://github.com/facebookresearch/demucs), original repo archived Jan 2025, maintained fork [adefossez/demucs](https://github.com/adefossez/demucs)) | Code MIT; weights MIT per project ([issue](https://github.com/facebookresearch/demucs/issues/327)) | 316 MB fp32 / 166 MB fp16-weights ONNX ([ONNX export, MIT](https://huggingface.co/StemSplitio/htdemucs-onnx)); 7.8 s segments, STFT rewritten as Conv1d | **Measured here: 7.8 s chunk in 6.65 s on 4 threads = RTF 0.85** (fp32; a 2nd session OOM-killed while another install ran, so one run only). Publisher reports RTF 0.20 on M4 Pro | ORT works; 316 MB RAM+ activations; RTF on a flagship SoC **[supposition] 0.5-2**, thermal throttling likely | **Adopt for offline transition-window pre-compute (see below)** |
| **BS-RoFormer / MelBand-RoFormer** (MVSep/UVR community) | Mostly MIT code; weights vary, many community-trained, unclear | 100-400 M | Best SDR (vocals), ~2-3x slower than htdemucs per [benchmark blogs](https://aistemsplitter.org/blog/htdemucs-vs-bs-roformer-vs-spleeter-2026-benchmark) (not rigorously sourced) | No | **Skip** (PC-only luxury) |
| **Open-Unmix** | MIT | ~35 MB (umxl bigger) | Fast on CPU, clearly lower quality than htdemucs | ONNX possible | **Skip** unless htdemucs proves too heavy; vocal bleed is audible in a mix |
| **Spleeter** | MIT | ~20-40 MB/2-4 stems | Very fast | TFLite ports exist ([demixr](https://github.com/demixr/demixr-app)) | **Fallback only** (2-stem vocal/instrumental); quality noticeably poor on full-band pop |
| **RT-STT / real-time low-latency TFC-TDF** ([arXiv 2511.13146](https://arxiv.org/abs/2511.13146), Nov 2025) | Unknown (no code/weights found) | small | Designed for streaming | Promising | **Watch**; not adoptable today |
| Real-time separation on a low-power DSP ([arXiv 2609.12201](https://arxiv.org/pdf/2609.12201)) | Research | tiny | Real-time on DSP | n/a | Watch |
| SAM-Audio (Meta, text-prompted) | Check licence | large | GPU | No | Skip |

**Is near-real-time on-device separation feasible?** Live streaming separation (needs RTF well below 1 *with headroom*
while the CPU also decodes/timestretches/plays): no, not with quality models. **But we do not need live.** A DJ transition
touches ~16-64 s. Plan: when track B is chosen (usually >2 min before the transition), separate only *A's last N s and B's first N s*
in the background. Cost here: 2 x 32 s = ~64 s audio at RTF 0.85 is ~55 s of one-off compute per transition - fits inside one track's playtime;
on a phone **[supposition]** 30-130 s, feasible if throttled on a low-priority thread and cancelled on skip. Storage: 64 s x 4 stems
x float32 stereo 44.1k = ~90 MB in RAM, or write 16-bit temp files.

What stems buy (real, musical): kill A's vocal before B's vocal enters (removes the #1 cause of bad blends: two singers),
bass swap done on the actual bass stem rather than an EQ shelf (cleaner), "acapella of B over instrumental of A" mashups,
drum-only tails. Quality caveats: sources are already lossy AAC/Opus (artifacts amplified), separation adds a
watery/phasey texture that is audible on solo stems and mostly masked in a blend, and a time-stretched stem inherits both.
Verdict: **spike after the Beat This + planner path is solid**; do it on Desktop first (RTF headroom, stem cache on disk), then Android.

## 4. Generative and agentic layer

### 4a. LLM as DJ brain via API (Claude etc.)
Where it helps: set arc ("warm-up -> peak -> cool-down over 90 min"), turning a natural-language request into
constraints (BPM range, energy curve, key of the night), choosing among a *shortlist* of 5-10 rule-approved candidates
with a written reason, breaking rules on purpose ("hold this key clash for a 4-bar tension build"), explaining choices to
the user. Where it hurts: anything in the audio timing path (seconds of latency), needing the network mid-set, cost if called per
track (small: one ~2-4 k-token call per track is fractions of a cent to a few cents with a small model), non-determinism, and hallucinated
track ids. Design rules: give it **structured JSON features** (BPM, Camelot, energy curve, section map, embedding-derived tags, user
affinity), never audio; constrain output to a schema selecting from provided candidate ids; validate every action in the rule engine
(planner may reject); call it **one or two tracks ahead** (>= 60 s slack), with a hard timeout and rule-based fallback; cache decisions.
Offline behaviour = the existing heuristic recommender. **Adopt** as an optional planner module (cheap, high perceived "it thinks").
No published evaluation shows LLMs beat rules on transition *quality*; the win is arc coherence and explainability.

### 4b. On-device small LLM
1-3 B instruct models (Gemma/Qwen/Phi class, int4, ~1-2 GB) run on flagship phones at roughly 5-20 tokens/s **[supposition]** via
MediaPipe LLM / llama.cpp / ExecuTorch. A shortlist-choosing prompt with ~1 k tokens prefill and ~60 tokens of JSON output is a few to
~15 s: acceptable for a decision made a track ahead. Quality of choice from a pre-filtered shortlist is *good enough*; free-form
"set arc" reasoning is weak. **Spike later**; RAM (1-2 GB) and battery are the real costs, and it gives little over the rule-based scorer.

### 4c. Generative audio
* **Magenta RealTime 2** ([HF](https://huggingface.co/google/magenta-realtime-2), [repo](https://github.com/magenta/magenta-realtime)): open weights, 2.4 B (base) and 230 M
  (small) params, Apache-2.0 code, **CC-BY-4.0 weights**, ~200 ms control latency, 48 kHz stereo, text/audio-prompted. Real-time on a
  GPU/TPU-class device; the 230 M model *may* be near-real-time on a good PC CPU **[supposition, not measured]**; not phone-ready.
* **Lyria RealTime API** ([docs](https://ai.google.dev/gemini-api/docs/realtime-music-generation), [WebSocket ref](https://ai.google.dev/api/live_music)): WebSocket, instrumental, steerable tempo/density/brightness, control latency up to ~2 s, paid tier only.
  Cloud-only, unconditionable on *our* audio (prompts, not stem-conditioned). Usable for "ambient bed to bridge two incompatible tracks".
* **Stable Audio Open Small** ([Arm/Stability](https://stability.ai/news-updates/stability-ai-and-arm-release-stable-audio-open-small-enabling-real-world-deployment-for-on-device-audio-control)):
  341 M params, ~11 s of 44.1 kHz stereo in <8 s on an Arm phone via KleidiAI/LiteRT, [Stability Community Licence](https://stability.ai/community-license-agreement) (free for personal). So a **one-shot** 8-11 s riser/texture/loop *is* feasible on-device, but
  it is text-conditioned, not tempo-locked (BPM prompt is unreliable) and needs time-stretching to grid. Neat, not necessary.
* MusicGen (Meta; code MIT, **weights CC-BY-NC**), AudioLDM2, Riffusion, MusicGen-continuation / Stable-Audio inpainting: PC/GPU
  only; continuation of a *decoded stream's tail* into B is the "research" version of an AI transition (tempo/key drift, ~10-30 s per 10 s on CPU **[supposition]**).
  Not reliable enough for a personal daily driver.

**Cheap non-generative bed first (recommended):** ship a small set of royalty-free / self-made drum loops, risers, sub-swells, noise
sweeps, reverse cymbals, impacts (CC0 sample packs or synthesised in Kotlin). Time-stretch/trigger them on the master grid with
sample-accurate offsets, key-filter or pitch-shift the tonal ones (or use unpitched material), sidechain-duck under the vocal, and
fade with the same equal-power curves. Cost: 3-5 dev-days, deterministic, zero latency, works offline, and covers 80% of what a
listener perceives as "a bed under the transition" (energy bridge, masking a grid slip, covering a key clash). Generative models only add
*novel melodic material*, which is exactly what tends to clash. **This is the better first step.**

### 4d. Preference learning from skips/likes (personalisation layer)
Signals we already own locally: skip-within-N-s, completion, like, replay, and (new) *"skipped during a DJ transition"* vs *"let it play"*.
Model: per-feature logistic regression / contextual bandit (LinUCB or Thompson sampling) over the pair features of 2.1; state in Room; updates
online in microseconds; regularise toward hand-set weights so cold start = rules. Also learn **transition-style preference** (long blend vs quick cut, bass-swap on/off) from skip
timing during transitions. Full RL (policy gradient over sequences) is unjustified: sparse reward, one user, no simulator.
**Adopt** (2-3 days, low risk).

## 5. Time-stretch / pitch

| Engine | Licence | Size / CPU | Quality | Embeddable |
|---|---|---|---|---|
| **Signalsmith Stretch** ([repo](https://github.com/signalsmith-audio/signalsmith-stretch)) | MIT | header-only C++, ~few hundred lines core | Very good on music, low CPU; community reports ~= R3 on some material and ~1/3 the CPU ([forum](https://www.kvraudio.com/forum/viewtopic.php?t=623537), [comparison](https://bungee.parabolaresearch.com/compare-audio-stretch-tempo-pitch-change)) | JNI/NDK easy; also a straightforward Kotlin port (~500 LOC) |
| **Rubber Band R3 (Finer)** ([breakfastquay](https://breakfastquay.com/rubberband/), [licence](https://breakfastquay.com/rubberband/license.html)) | GPL-2+ (fits GPL-3); commercial licence otherwise | C++; Android JNI build support in repo | Best on complex mixes/vocals/bass; R2 (Faster) ~3x cheaper | JNI via NDK, real-time capable |
| WSOLA / PSOLA | public-domain algorithms | trivial | Fine for +-5 % on percussive; smears tonal/vocal | Kotlin port trivial; good for tiny corrections |
| Phase vocoder (basic) | - | trivial | Phasiness/transient smearing; avoid | - |
| Neural time-stretch / pitch (e.g. vocoder-based) | mixed | GPU-class | Research | No |

**Measured here** (pylibrb 0.1.2 bindings for Rubber Band, 60 s stereo 44.1 kHz noise+sine test signal, +6 % tempo, realtime mode, 4096-frame blocks; indicative only, not
music-representative): **R3 Finer 6.2 s (RTF 0.10)**, **R2 Faster 1.8 s (RTF 0.03)**. Phone **[supposition]**: RTF 0.1-0.3 for R3 per stream -> two
simultaneous stretched streams still fit on one big core; low-end devices should prefer R2 or Signalsmith.
Note that for DJ-typical +-3-6 % tempo moves even R2 is nearly transparent; large jumps (>10 %) are where R3/stems matter.
Recommendation: **embed Rubber Band via JNI on Android (GPL-3 fits), keep Signalsmith as the license-agnostic alternative/fallback,
and use offline renders with R3, live playback with R3 if profiling allows else R2.** Do not port to Kotlin (denormal handling,
FFT speed, and two-stream real-time need native).
A cheaper trick that removes most stretch artefacts: **stretch only the incoming track during the overlap, ramp its rate to 1.0 after
the transition** (tempo ramp), and prefer mixes with |delta BPM| < 6 % or a half/double relationship (recommender hard filter).

## 6. Other hard truths

* **Output latency is unknown per device and route.** `AudioTrack.getTimestamp()` / AAudio timestamps are specified accurate to about +-1-2 ms for wired/internal
  output ([AOSP CDD](https://android.googlesource.com/platform/compatibility/cdd/+/refs/heads/master/5_multimedia/5_6_audio-latency.md)), which is fine for *aligning two streams
  we both render into one mixer* (relative alignment needs no latency knowledge). Low-latency path needs Oboe LowLatency + Exclusive (~20 ms best case;
  a non-low-latency stream can sit near 200 ms, [Oboe docs](https://developer.android.com/games/sdk/oboe/low-latency-audio)).
* **Bluetooth A2DP adds ~60-250 ms and it is not reported reliably** ([discussion](https://github.com/google/oboe/issues/357), [guide](https://audiolab.tools/insights/android-audio-development-guide)). That is
  irrelevant to *relative* beat matching **if both tracks go through our own single mixer** (they get the same delay), but it breaks anything synced to
  external clocks, visuals, or lyrics (this repo already has a lyrics offset setting for exactly this).
* **Design consequence (most important architectural point):** sample-accurate mixing requires **one render path**: decode both streams to PCM, stretch,
  mix in our code, and feed a *single* audio sink. Two ExoPlayer instances with volume ramps (what crossfade does today) cannot be beat-accurate: ExoPlayer
  `getCurrentPosition()` is updated at buffer granularity, `setVolume` is applied on the next buffer (tens of ms), and start times of two players jitter by tens to hundreds of ms.
  So: **custom Media3 AudioProcessor / own renderer that pulls both decoders** (Media3 `AudioProcessor` chain is per-player, so
  the mix engine has to sit behind its own `AudioSink` or Oboe). On Desktop mpv the same conclusion holds (render transitions offline to a temp stream, or drive libmpv `ao` with our mix).
* **Pre-rendering is the pragmatic answer**: render the transition window (e.g. 16-64 s) offline in background into a PCM buffer, then *play that buffer* between
  the two normal streams with a gapless hand-off at sample boundaries. Sample-accurate by construction; the offline renderer already exists here.
* **Stream-derived audio ceiling**: sources are lossy (AAC 128-256 kbps / Opus) and normalised differently; decode-to-float is fine, but
  loudness must be matched per track (LUFS), tempo grids come from analysis not from a producer's grid (residual +-10-20 ms
  drift over a 32-bar overlap on live-played or drifting material), and vocals/bass overlaps cannot be un-mixed without stems. Realistic quality of a
  beat-matched blend: **"good club-DJ 80 %" on 4-on-the-floor material with steady tempo (house/techno/disco/pop-dance); mediocre on
  live bands, hip-hop with swing, tempo-drifting or half-time material; use plain crossfade there.**
* Precache/gapless: YouTube stream URLs expire and CDN may 403; the DJ pipeline needs the *next* track downloaded (or at least the overlap window) before planning. Already true for crossfade precache.
* Battery/thermal: analysis (Beat This) and separation should run on charging/idle or opportunistically on the *next* track only.

## 7. Roadmap (prioritised)

| # | Item | Effort (dev-days) | Expected quality gain | Risk |
|---|---|---|---|---|
| 1 | **Single-path mixer with pre-rendered transition window** (decode both, Rubber Band/Signalsmith stretch, mix, hand off at sample boundary), driven by the existing planner; loudness match; fallback to crossfade when grid unreliable | 8-12 | **Highest**: turns "planned" into "audibly tight" | Medium (Media3 integration, seam glitches, memory) |
| 2 | **Adopt Beat This ONNX (final, small fallback)** replacing/validating the Kotlin beat tracker: ORT-Android, Kotlin mel frontend, constant-grid fit + reliability flag; cache in Room | 3-5 | High for downbeat/phrase accuracy, fewer half-time errors | Low-Med (frontend parity; verified by comparing to reference logits) |
| 3 | **Sample/loop bed layer** (drum loop, riser, impact, noise sweep synced to grid, sidechained) as an optional transition ingredient | 3-5 | Medium-High (masks slips, adds energy) | Low |
| 4 | **LLM DJ brain (planner-over-tools)**: shortlist -> JSON choice + rationale, set arc; strict schema, timeout, rule fallback; optionally on-desktop or API | 3-5 | Medium (arc coherence, explainability, "it thinks") | Low-Med (network, prompt drift) |
| 5 | **Personalisation from skips/likes** (online logistic/bandit over pair features) + transition-style preferences | 2-3 | Medium, compounds over time | Low |

Next tier (after the five): stems for the transition window (htdemucs ONNX, desktop first): 6-10 dev-days, gain High on vocal clashes / bass swap, risk Med-High (compute, memory, artifacts);
Essentia-EffNet-based embedding features on PC (personal use) 3 days; Magenta RT/Lyria bed via PC or cloud 4-6 days, optional toy.

### Explicitly do NOT do
* Train a compatibility model on 1001Tracklists/DJ-mix data (tiny, positives-only, NC-ish and unlicensed data, no gain over rules + own-listener signal).
* Live (streaming) on-device stem separation; running two ExoPlayers and calling it beat-matched; LLM in the audio timing path.
* madmom / NC-licensed weights in any distributable build; MERT/MuQ embeddings on phone; full RL.
* Generative "continuation" of the outgoing track as the default transition (drift, latency, artefacts).
* Porting Rubber Band to Kotlin.

## 8. Verdict on the ambition ("first truly autonomous AI-assisted DJ")

* **Realistic now, on the phone:** autonomous next-track selection (rules + embeddings + personal bandit), beat/phrase-aware planning, *pre-rendered* beat-matched
  and bass-swapped transitions with a synced loop/riser bed, an LLM (online or PC) choosing the set arc and explaining itself, silent fallback to
  plain crossfade. That already exceeds every consumer streaming app's "automix" and matches djay/Serato-style automix on steady-tempo material.
  Not "first ever" (djay Pro AI, Mixxx automix, Rekordbox/Traktor-style tools and several research prototypes exist), but a first for **YouTube-Music-streamed**
  content on a phone, with an LLM planner.
* **Needs a PC or cloud:** high-quality stems for every transition (phone can do the transition window only, slowly), generative beds/melodic continuation
  (Magenta RT 2 local on GPU, Lyria RealTime API, MusicGen), embedding models with NC licences, large LLM reasoning.
* **Research, do not promise:** learned transition-quality scoring that beats hand-tuned rules; genuinely creative live mashups; sample-perfect sync over Bluetooth to external gear.
* "Thinks by itself" is honestly an LLM/bandit planner over deterministic audio tools. It will look intelligent (arc, reasons, adaptation to skips)
  but the audible quality comes from #1 and #2, not from the AI.

## 9. Measurements made for this document (reproducible)

* Machine: 4 vCPU, 15 GB, no GPU, onnxruntime 1.30.0 CPUExecutionProvider, Python 3.11. Random-noise inputs (latency depends on shapes, not content).
* Beat This final0 fp32 ONNX (82.1 MB, `aaatmy/beat-this-onnx`): input `[1, frames, 128]`; 1500 frames: 3.9 s (1 thread), 3.7 s (4 threads).
* htdemucs ONNX fp32 (316 MB, `StemSplitio/htdemucs-onnx`): 7.8 s stereo chunk: 6.65 s with 4 threads (RTF 0.85). Second config not measured (process OOM-killed by a concurrent install).
* Rubber Band via pylibrb 0.1.2, +6 % tempo, 60 s stereo: R3 Finer 6.19 s, R2 Faster 1.81 s.
* Not measured (published/claimed only): Essentia/EffNet, CLAP, MuQ, Magenta RT, Stable Audio Open Small timings, all phone figures.
* Not verified: Signalsmith Stretch timing (pip package did not install here); claims about it come from linked forum/comparison pages.
