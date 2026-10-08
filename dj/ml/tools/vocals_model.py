"""Vocal activity model for the AI DJ: YAMNet (Google, Apache-2.0) as ONNX, weights stored as fp16.

Downloads the tf2onnx export of YAMNet pinned by revision and SHA-256, stores every large fp32 initializer as fp16 plus a
Cast back to fp32 (compute stays fp32, the file halves), checks the class scores against the original, and writes
dj/android/src/main/assets/yamnet_fp16w.onnx. That asset is gitignored like the Beat This! model: never commit it.

Usage: python3 dj/ml/tools/vocals_model.py   (needs numpy, onnx, onnxruntime)
"""
import hashlib
import os
import urllib.request

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

URL = "https://huggingface.co/jafet21/yamnetonnx/resolve/337f563004630f00673d4a773a0d657d0233ce56/yamnet.onnx"
SHA256 = "04e27fca08e7a3aea2630d1a63a51e6b437c803e0ff6e26399f80870ac251dda"
ROOT = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(ROOT, "model", "yamnet.onnx")
DST = os.path.join(ROOT, "..", "..", "android", "src", "main", "assets", "yamnet_fp16w.onnx")

os.makedirs(os.path.dirname(SRC), exist_ok=True)
if not os.path.exists(SRC):
    urllib.request.urlretrieve(URL, SRC)
digest = hashlib.sha256(open(SRC, "rb").read()).hexdigest()
assert digest == SHA256, f"unexpected model file {digest}"

m = onnx.load(SRC)
inits, casts = [], []
for init in m.graph.initializer:
    if init.data_type == TensorProto.FLOAT and np.prod(init.dims) >= 1024:
        h = numpy_helper.from_array(numpy_helper.to_array(init).astype(np.float16), init.name + "_fp16")
        inits.append(h)
        casts.append(helper.make_node("Cast", [h.name], [init.name], to=TensorProto.FLOAT, name="cast_" + init.name))
    else:
        inits.append(init)
del m.graph.initializer[:]
m.graph.initializer.extend(inits)
nodes = list(m.graph.node)
del m.graph.node[:]
m.graph.node.extend(casts + nodes)
onnx.save(m, DST)

import onnxruntime as ort

a = ort.InferenceSession(SRC, providers=["CPUExecutionProvider"])
b = ort.InferenceSession(DST, providers=["CPUExecutionProvider"])
x = (np.random.default_rng(1).standard_normal(16000 * 20) * 0.1).astype(np.float32)
name = a.get_inputs()[0].name
sa = a.run(["output_0"], {name: x})[0]
sb = b.run(["output_0"], {name: x})[0]
print("frames", sa.shape, "max |score diff|", float(np.abs(sa - sb).max()), "size", round(os.path.getsize(DST) / 1e6, 1), "MB")
