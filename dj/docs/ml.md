# `:ml` — Beat This! on device

Neural beat / downbeat tracking for the AI DJ. Module `dj/ml`, package `org.simpmusic.dj.ml`, depends on `:brain`
(contract) and ONNX Runtime. **The model file is never committed**; you place it yourself (below).

## Model

* **Beat This!** — Foscarin, Schlüter, Widmer, *Beat This! Accurate beat tracking without DBN postprocessing*,
  ISMIR 2024. Code and weights: MIT license. <https://github.com/CPJKU/beat_this> (`pip install beat_this`, PyPI 1.1.0).
* Checkpoint `final0` (trained on everything except GTZAN, seed 0), 81 MB PyTorch:
  `https://cloud.cp.jku.at/public.php/dav/files/7ik4RrBKTS273gp/final0.ckpt`
  (this is `CHECKPOINT_URL` in `beat_this/inference.py`; fetch it with plain `curl -L`).
* ~20 M parameters (roformer: conv stem + 3 frequency/time partial transformers + 6 transformer layers).

### Variants (exported by `dj/ml/tools`)

| file | size | how | accuracy vs PyTorch fp32 (13 tracks) |
|---|---|---|---|
| `beat_this_fp32.onnx` | 83.1 MB | `torch.onnx.export`, opset 17, dynamic time axis | max abs logit diff 5e-6 |
| `beat_this_fp16w.onnx` | 42.6 MB | weights stored fp16 + `Cast` to fp32 (compute stays fp32) | beat F 1.000 / downbeat F 1.000 (logit diff 3e-3) |
| **`beat_this_int8mm.onnx`** | **23.7 MB** | dynamic int8 quantisation of **MatMul only** (QInt8), convs stay fp32 | beat F **0.9989** (worst track 0.992), downbeat F **0.9974** |
| `beat_this_int8_qint8.onnx` | 22.9 MB | int8 incl. Conv (`ConvInteger`) | 0.9969 / 0.9952 — but **`onnxruntime` Java 1.22 has no `ConvInteger` kernel** (ORT_NOT_IMPLEMENTED); Python ORT 1.30 runs it |
| full-graph fp16 (`onnxconverter-common`) | 42.5 MB | — | fails to load in ORT (type mismatch on a `Cast` inside the rotary/attention block); not pursued: CPU has no fp16 speed benefit anyway |

F-measure is mir_eval-style, ±70 ms, computed against the fp32 PyTorch annotations (these are model outputs, not human
ground truth — see "Accuracy vs the DSP analyzer").

**Recommendation for phones: `beat_this_int8mm.onnx` (23.7 MB).** Best size/speed with no measurable loss. If a device
ever chokes on int8, `beat_this_fp16w.onnx` is the exact-accuracy fallback (same speed as fp32, half the download).

## Placing the model

The tracker takes the model through `OnnxBeatModel.fromFile(File)`, `fromBytes(ByteArray)` or `fromStream(InputStream)`.
Suggested app flow: download `beat_this_int8mm.onnx` on first use of the DJ (or ship it from the release assets of the
fork) into `filesDir/dj/beat_this_int8mm.onnx`, then `OnnxBeatModel.fromFile(...)`. Nothing in git.

To (re)produce the file (Python 3.11, CPU torch):

```
python -m venv venv && venv/bin/pip install torch torchaudio --index-url https://download.pytorch.org/whl/cpu
venv/bin/pip install beat_this onnx onnxruntime onnxscript mir_eval librosa soundfile imageio-ffmpeg
mkdir model && curl -L -o model/final0.ckpt https://cloud.cp.jku.at/public.php/dav/files/7ik4RrBKTS273gp/final0.ckpt
cp dj/ml/tools/*.py . && python export_onnx.py && python quantize.py   # -> model/beat_this_int8mm.onnx
```

(the scripts locate `model/` and `corpus/` next to themselves.) Then `BEAT_THIS_MODEL=/path/beat_this_int8mm.onnx`.

## Pipeline (all pure Kotlin except the ONNX call)

1. `Resampler` — Kaiser-windowed-sinc polyphase to 22 050 Hz mono (the reference uses soxr).
2. `MelFrontEnd` — exact replica of `beat_this.preprocessing.LogMelSpect`: n_fft 1024, hop 441 (50 fps), periodic Hann,
   reflect centre-padding, magnitude scaled by **1/sqrt(n_fft)** (torchaudio `normalized="frame_length"` — *not* the
   window energy; that was a bug found by the reference-tensor test), 128 Slaney mels 30–11000 Hz (no area norm),
   `log1p(1000·x)`. Own radix-2 FFT. Matches torchaudio to < 2e-3 (test `melMatchesTorchaudioReference`).
3. `ChunkedInference` — the repo's `split_predict_aggregate`: 1500-frame (30 s) chunks, 6-frame border, first/last chunk
   zero-padded, last chunk shifted to end at the end of the piece, earlier chunk wins on overlaps. Chunk starts/sizes
   are asserted against Python for 8 lengths. **Do not shorten the chunk to save time**: 1000 → beat F 0.978, 750 → 0.967,
   500 → 0.951 (attention cost is quadratic, but the model is trained on 30 s).
4. `BeatModel` (interface) ← `OnnxBeatModel` (only file touching `ai.onnxruntime`; same source compiles against
   `onnxruntime-android`, identical package).
5. `BeatPostProcessor` — the paper's "minimal" post-processor: maxima within ±3 frames with logit > 0, adjacent picks
   averaged, downbeats snapped to the nearest beat. Verified identical to Python on real logits.
6. `BeatGridBuilder` — bpm (slope of beat index vs time), beats-per-bar (modal bar length; only 3/4 accepted), downbeat
   indices, phrase starts (16 beats at ≥95 bpm else 8 for 4/4; 12/6 for 3/4; always on a downbeat) and confidences
   (network probability at picks blended with local regularity / bar consistency).

`BeatThisTracker : BeatProvider`, `CompositeAnalyzer(base, beatProvider)` overlays bpm / beatTimesMs /
downbeatBeatIndices / beatsPerBar / phraseStartsMs on any base `TrackAnalyzer` (key, energy, sections, timbre stay from
the base; the base result is returned untouched if the provider fails or finds < 4 beats), `BeatThisAnalyzer(tracker,
base?)` = the same with `analyzerId = "<base>+beat-this-onnx-1"`. Without a base it produces only a 500 ms RMS envelope
and loudness (no key/sections).

```kotlin
val model = OnnxBeatModel.fromFile(File(filesDir, "dj/beat_this_int8mm.onnx"), threads = 2)
val analyzer = BeatThisAnalyzer(BeatThisTracker(model), base = dspAnalyzer /* Dev A */)
val analysis = analyzer.analyze(videoId, PcmAudio(samples, sampleRate))
```

## Latency (this sandbox: 4 vCPU shared, x86-64, onnxruntime 1.30 Python / 1.22 Java, 236 s track, mel included)

| model | 1 thread | 2 threads | 4 threads |
|---|---|---|---|
| fp32 | 20.4 s | 11.7 s | 18–32 s (noisy, contended) |
| int8 MatMul-only | 15.5 s | **8.9 s** | 11.5–13.5 s (noisy) |
| PyTorch fp32 (reference) | 21.4 s | 14.0 s | 8 s |

Kotlin end-to-end (mel + int8mm, 2 threads, includes resampling): 6.4 s for a 146 s track, 10.2 s for 201 s
(`RealModelTest` prints it) → about 10–12 s per 4-minute track. Mel front-end alone: ~0.25 s per 4 min.
Time is dominated by the quadratic time-attention inside the frontend blocks and the 6 transformer layers (MatMul 48 %,
Softmax 14 % of ORT time); it scales linearly with track length.

**Supposition, not measured:** a 2020+ phone with 4 big cores is likely 1.5-3× slower than this box per thread, so
expect ~15-30 s for a 4-minute track in the background at 2-4 threads, not single-digit seconds. Levers that do not
lose accuracy: analyse only the parts the mix needs (a beat grid is regular — analysing the intro and the outro windows
of ~60-90 s and extrapolating is an option we did not build), run at `BACKGROUND` priority while the previous track plays,
cache the result (`TrackAnalysis` is stored), or use NNAPI/XNNPACK execution providers (not tried).

## Accuracy vs the DSP analyzer

Reference annotations (Beat This! fp32, 13 CC tracks from archive.org, with independent librosa key estimates) live in the
scratchpad corpus (`corpus/refs/*.json`, format in `corpus/refs/README.md`); Dev A's DSP analyzer can be scored against
them. Beat This! results on the corpus are clean: grid-quantised electronic tracks give integer tempi (140.0, 160.0, 115.0,
114.0, 101.0, 70.0 bpm), the 3/4 waltz is found as 3 beats/bar, and only the drum-less Canon in D gets a doubtful bar
structure (2 beats/bar). The paper reports Beat This! at or above DBN-based state of the art on beats and clearly ahead on
downbeats; classic DSP onset/comb trackers are expected to trail it, mostly on downbeats and on tempo octave errors — **that
is the literature's claim, not measured here** (I did not re-read the paper's tables, and the DSP analyzer had not
landed). Because the refs are the model's own output they cannot rank the model against a DSP tracker on accuracy —
only measure how far the DSP analyzer deviates from the model.

## Tests

`./gradlew -p dj :ml:test --console=plain` — 15 unit tests always run (mel vs torchaudio reference tensors, FFT, Slaney
scale, resampler, chunking vs Python, peak picking vs Python, grid builder, composite/analyzer with a fake base).
`RealModelTest` runs the ONNX model end-to-end against the PyTorch refs and **skips itself** without
`BEAT_THIS_MODEL` (ONNX path) and `BEAT_THIS_CORPUS` (dir with `audio/` and `refs/`); both default to the sandbox scratchpad.
Reference tensors in `src/test/resources/beatthis` are ~80 KB and generated by `tools/dump_vectors.py`.
