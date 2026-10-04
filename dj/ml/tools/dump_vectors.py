"""Dump small reference tensors for the Kotlin tests into dj/ml/src/test/resources/beatthis/."""
import json, os, struct, sys
import numpy as np
import torch
import onnxruntime as ort
import soundfile as sf
from beat_this.preprocessing import LogMelSpect
from beat_this.inference import split_piece, split_predict_aggregate
from beat_this.model.postprocessor import Postprocessor

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)


def test_signal(n, sr=22050):
    i = np.arange(n, dtype=np.float64)
    x = 0.5 * np.sin(2 * np.pi * 440.0 * i / sr) + 0.25 * np.sin(2 * np.pi * 3000.5 * i / sr)
    x += 0.3 * ((i % 11025) < 50)
    # deterministic pseudo-noise (LCG) so high bins are non-trivial
    s = 12345
    noise = np.empty(n)
    for k in range(n):
        s = (s * 1103515245 + 12345) & 0x7FFFFFFF
        noise[k] = (s / 0x7FFFFFFF - 0.5) * 0.1
    return (x + noise).astype(np.float32)


sig = test_signal(44100)  # 2.0 s
mel = LogMelSpect()(torch.tensor(sig)).numpy().astype("<f4")
print("mel", mel.shape)
open(OUT + "/mel_ref.f32", "wb").write(mel.tobytes())
open(OUT + "/mel_ref.shape", "w").write(f"{mel.shape[0]} {mel.shape[1]}")

# postprocessor reference: real logits (fp32 model) from one corpus track, 3000 frames
y, sr = sf.read(HERE + "/corpus/audio/disco_con_tutti_22050.wav", dtype="float32")
sp = LogMelSpect()(torch.tensor(y))
sess = ort.InferenceSession(HERE + "/model/beat_this_fp32.onnx")


class M:
    def __call__(self, x):
        b, d = sess.run(None, {"spect": x.numpy()})
        return {"beat": torch.from_numpy(b), "downbeat": torch.from_numpy(d)}


sub = sp[1000:4200]  # 3200 frames -> 3 chunks with the default chunking
pr = split_predict_aggregate(sub, 1500, 6, "keep_first", M())
beat = pr["beat"].float()
down = pr["downbeat"].float()
np.concatenate([beat.numpy(), down.numpy()]).astype("<f4").tofile(OUT + "/logits_ref.f32")
# the mel slice too, so Kotlin can run the chunked model itself when the model file is present
sub.numpy().astype("<f4").tofile(OUT + "/mel_slice.f32")
b, d = Postprocessor("minimal")(beat, down)
json.dump({"frames": int(beat.shape[0]), "beatsSec": [float(x) for x in b], "downbeatsSec": [float(x) for x in d]},
          open(OUT + "/post_ref.json", "w"))
print("beats", len(b), "downbeats", len(d))
# chunk starts for a few lengths
cases = {}
for L in (100, 1488, 1489, 1500, 1501, 2976, 3200, 11818):
    chunks, starts = split_piece(torch.zeros(L, 128), 1500, 6, True)
    cases[str(L)] = {"starts": [int(s) for s in starts], "sizes": [int(c.shape[0]) for c in chunks]}
json.dump(cases, open(OUT + "/chunks_ref.json", "w"))
