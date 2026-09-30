import glob, json, os, sys, time
import numpy as np
import soundfile as sf
import torch
import mir_eval
import onnxruntime as ort
from beat_this.inference import split_predict_aggregate
from beat_this.preprocessing import LogMelSpect
from beat_this.model.postprocessor import Postprocessor

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = HERE + "/corpus"
MODELS = sys.argv[1].split(",") if len(sys.argv) > 1 else ["fp32", "fp16", "int8_qint8", "int8_quint8", "int8_pc"]
THREADS = int(sys.argv[2]) if len(sys.argv) > 2 else 4
CHUNK = int(sys.argv[3]) if len(sys.argv) > 3 else 1500
spect = LogMelSpect()
post = Postprocessor("minimal")


class OrtModel:
    def __init__(self, path, threads):
        so = ort.SessionOptions()
        so.intra_op_num_threads = threads
        so.inter_op_num_threads = 1
        self.s = ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])
        self.time = 0.0

    def __call__(self, x):
        t = time.time()
        b, d = self.s.run(None, {"spect": x.numpy()})
        self.time += time.time() - t
        return {"beat": torch.from_numpy(b), "downbeat": torch.from_numpy(d)}


def f1(ref, est):
    return mir_eval.beat.f_measure(np.array(ref), np.array(est), f_measure_threshold=0.07)


specs = {}
for wav in sorted(glob.glob(ROOT + "/audio/*_22050.wav")):
    name = os.path.basename(wav)[:-len("_22050.wav")]
    y, sr = sf.read(wav, dtype="float32")
    with torch.inference_mode():
        specs[name] = spect(torch.tensor(y))

for mname in MODELS:
    m = OrtModel(HERE + f"/model/beat_this_{mname}.onnx", THREADS)
    fb, fd, tot_audio = [], [], 0
    for name, sp in specs.items():
        ref = json.load(open(f"{ROOT}/refs/{name}.json"))
        pr = split_predict_aggregate(sp, CHUNK, 6, "keep_first", m)
        b, d = post(pr["beat"].float(), pr["downbeat"].float())
        fb.append(f1(ref["beatsSec"], b))
        fd.append(f1(ref["downbeatsSec"], d) if len(ref["downbeatsSec"]) and len(d) else 0.0)
        tot_audio += ref["durationSec"]
        print(f"  {mname:12s} {name:26s} beatF={fb[-1]:.3f} downF={fd[-1]:.3f}", flush=True)
    print(f"== {mname}: chunk={CHUNK} threads={THREADS}meanBeatF={np.mean(fb):.4f} minBeatF={np.min(fb):.3f} meanDownF={np.mean(fd):.4f} "
          f"ort_time={m.time:.1f}s for {tot_audio:.0f}s audio -> {m.time / tot_audio * 240:.1f}s per 4 min", flush=True)
