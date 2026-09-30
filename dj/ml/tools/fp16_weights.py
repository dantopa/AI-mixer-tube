"""Store big fp32 initializers as fp16 + a Cast back to fp32 (weight-only compression, compute stays fp32)."""
import os
import numpy as np
import onnx
from onnx import numpy_helper, helper, TensorProto

HERE = os.path.dirname(os.path.abspath(__file__)) + "/model/"
m = onnx.load(HERE + "beat_this_fp32.onnx")
new_inits, casts = [], []
n = 0
for init in m.graph.initializer:
    if init.data_type == TensorProto.FLOAT and np.prod(init.dims) >= 1024:
        arr = numpy_helper.to_array(init)
        h = numpy_helper.from_array(arr.astype(np.float16), init.name + "_fp16")
        new_inits.append(h)
        casts.append(helper.make_node("Cast", [h.name], [init.name], to=TensorProto.FLOAT, name="cast_" + init.name))
        n += 1
    else:
        new_inits.append(init)
del m.graph.initializer[:]
m.graph.initializer.extend(new_inits)
nodes = list(m.graph.node)
del m.graph.node[:]
m.graph.node.extend(casts + nodes)
onnx.save(m, HERE + "beat_this_fp16w.onnx")
print("converted", n, "tensors", round(os.path.getsize(HERE + "beat_this_fp16w.onnx") / 1e6, 1), "MB")
import onnxruntime as ort
a = ort.InferenceSession(HERE + "beat_this_fp32.onnx")
b = ort.InferenceSession(HERE + "beat_this_fp16w.onnx")
x = np.random.randn(1, 1500, 128).astype(np.float32)
ra, rb = a.run(None, {"spect": x}), b.run(None, {"spect": x})
print("max abs diff vs fp32 (logits):", max(np.abs(p - q).max() for p, q in zip(ra, rb)))
