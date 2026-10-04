import os, sys
import numpy as np
import torch
from beat_this.inference import load_model

HERE = os.path.dirname(os.path.abspath(__file__))
model = load_model(HERE + "/model/final0.ckpt", "cpu").eval()


class Wrap(torch.nn.Module):
    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, spect):
        o = self.m(spect)
        return o["beat"], o["downbeat"]


w = Wrap(model).eval()
x = torch.randn(1, 700, 128)
out = HERE + "/model/beat_this_fp32.onnx"
with torch.no_grad():
    torch.onnx.export(
        w, (x,), out, input_names=["spect"], output_names=["beat", "downbeat"],
        dynamic_axes={"spect": {0: "batch", 1: "time"}, "beat": {0: "batch", 1: "time"}, "downbeat": {0: "batch", 1: "time"}},
        opset_version=17, dynamo=False,
    )
print("exported", os.path.getsize(out) / 1e6, "MB")

import onnxruntime as ort
sess = ort.InferenceSession(out, providers=["CPUExecutionProvider"])
for T in (700, 1500, 333):
    xx = torch.randn(1, T, 128)
    with torch.no_grad():
        pb, pd = w(xx)
    ob, od = sess.run(None, {"spect": xx.numpy()})
    print(T, "max abs diff beat", np.abs(ob - pb.numpy()).max(), "downbeat", np.abs(od - pd.numpy()).max())
