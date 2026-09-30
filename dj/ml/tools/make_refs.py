import glob, json, os, sys, time
import numpy as np
import soundfile as sf
import torch
import librosa
from beat_this.inference import Audio2Beats

ROOT = os.path.dirname(os.path.abspath(__file__)) + "/corpus"
CKPT = os.path.dirname(os.path.abspath(__file__)) + "/model/final0.ckpt"
torch.set_num_threads(4)
a2b = Audio2Beats(checkpoint_path=CKPT, device="cpu", dbn=False)

MAJ = np.array([6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88])
MIN = np.array([6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17])
NAMES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]


def key_est(y, sr):
    chroma = librosa.feature.chroma_cqt(y=y, sr=sr).mean(axis=1)
    best = None
    for pc in range(12):
        for mode, prof in (("major", MAJ), ("minor", MIN)):
            r = np.corrcoef(chroma, np.roll(prof, pc))[0, 1]
            if best is None or r > best[0]:
                best = (r, pc, mode)
    return {"pitchClass": best[1], "name": NAMES[best[1]], "mode": best[2], "corr": float(best[0])}


def bpm_from_beats(beats):
    if len(beats) < 4:
        return None
    ibi = np.diff(beats)
    med = float(np.median(ibi))
    # robust: linear fit of beat index vs time over the whole track
    idx = np.arange(len(beats))
    slope = np.polyfit(idx, beats, 1)[0]
    return {"median_ibi_bpm": 60.0 / med, "fit_bpm": 60.0 / slope}


for wav in sorted(glob.glob(ROOT + "/audio/*_22050.wav")):
    name = os.path.basename(wav)[:-len("_22050.wav")]
    y, sr = sf.read(wav, dtype="float32")
    t0 = time.time()
    beats, downbeats = a2b(y, sr)
    dt = time.time() - t0
    beats = [float(b) for b in beats]
    downbeats = [float(b) for b in downbeats]
    bpm = bpm_from_beats(np.array(beats))
    # beats per bar estimate: median of beats between downbeats
    bb = None
    if len(downbeats) > 2:
        bi = [int(np.argmin(np.abs(np.array(beats) - d))) for d in downbeats]
        bb = int(np.median(np.diff(bi)))
    ref = {
        "name": name,
        "durationSec": len(y) / sr,
        "model": "beat_this final0 (fp32, PyTorch, minimal postprocessor)",
        "beatsSec": beats,
        "downbeatsSec": downbeats,
        "bpm": bpm["fit_bpm"] if bpm else None,
        "bpmMedianIbi": bpm["median_ibi_bpm"] if bpm else None,
        "beatsPerBar": bb,
        "key": key_est(y, sr),
        "inferSeconds": dt,
    }
    json.dump(ref, open(f"{ROOT}/refs/{name}.json", "w"))
    print(name, f"{len(y)/sr:.0f}s", f"bpm={ref['bpm']}", f"bpb={bb}", ref["key"]["name"], ref["key"]["mode"], f"{dt:.1f}s", flush=True)
