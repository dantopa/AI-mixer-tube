import os
from onnxruntime.quantization import quantize_dynamic, QuantType

HERE = os.path.dirname(os.path.abspath(__file__)) + "/model/"
src = HERE + "beat_this_fp32.onnx"
# MatMul-only: keep the (tiny) convolutions in fp32. ConvInteger has no kernel in older ORT builds
# (onnxruntime-java 1.22 raised ORT_NOT_IMPLEMENTED) and is slow on ARM anyway.
quantize_dynamic(src, HERE + "beat_this_int8mm.onnx", weight_type=QuantType.QInt8, op_types_to_quantize=["MatMul"])
quantize_dynamic(src, HERE + "beat_this_int8mm_u8.onnx", weight_type=QuantType.QUInt8, op_types_to_quantize=["MatMul"])
for f in ("beat_this_int8mm.onnx", "beat_this_int8mm_u8.onnx"):
    print(f, round(os.path.getsize(HERE + f) / 1e6, 1), "MB")
