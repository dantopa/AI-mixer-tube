import os, sys, time
import numpy as np
import soundfile as sf
import torch
import onnxruntime as ort
from beat_this.inference import split_predict_aggregate
from beat_this.preprocessing import LogMelSpect

HERE = os.path.dirname(os.path.abspath(__file__))
y, sr = sf.read(HERE + "/corpus/audio/disco_con_tutti_22050.wav", dtype="float32")
t = time.time()
sp = LogMelSpect()(torch.tensor(y))
print(f"track {len(y)/sr:.0f}s, torch mel {time.time()-t:.2f}s, frames {sp.shape}")


class M:
    def __init__(self, p, th):
        so = ort.SessionOptions()
        so.intra_op_num_threads = th
        so.inter_op_num_threads = 1
        self.s = ort.InferenceSession(p, so, providers=["CPUExecutionProvider"])

    def __call__(self, x):
        b, d = self.s.run(None, {"spect": x.numpy()})
        return {"beat": torch.from_numpy(b), "downbeat": torch.from_numpy(d)}


for name in sys.argv[1].split(","):
    for th in (1, 2, 4):
        m = M(HERE + f"/model/beat_this_{name}.onnx", th)
        ts = []
        for _ in range(3):
            t = time.time()
            split_predict_aggregate(sp, 1500, 6, "keep_first", m)
            ts.append(time.time() - t)
        print(f"{name:12s} threads={th} min={min(ts):.1f}s all={[round(x,1) for x in ts]}", flush=True)
