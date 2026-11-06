"""Makes app/src/main/assets/sound/soundnet.bin (also Attacca Desktop's resources/soundnet.bin): the
weights of the YAMNet sound classifier that SoundNet.kt runs in pure Kotlin.

Source model: YAMNet, TFLite version, from https://www.kaggle.com/models/google/yamnet (Apache
License 2.0), saved as ~/yamnet-model/yamnet.tflite. Needs Python with ai-edge-litert, numpy,
soundfile and scipy (it was run under WSL in a virtual environment).

    python export_soundnet.py soundnet.bin half SONG.wav reference.npz

writes the weights in float16 ("half"; "full" for float32) and, from 10 s in the middle of
SONG.wav, the network's own outputs to check SoundNet.kt against (cosine 1.0 in float32,
0.999996 in float16 when it was made).

Writes YAMNet's constants (window, mel matrix and bias, conv / depthwise / dense weights and
biases, in network order) to a simple binary for Attacca's own inference, and reference outputs.
File: 'YAMN' + version int + count int, then per tensor: name (u16 len + utf-8), dims (u8 count +
i32 each), dtype u8 (0 float32, 1 float16), data little-endian.
"""
import os
import struct
import sys

import numpy as np
from ai_edge_litert.interpreter import Interpreter

out_path, half, ref_wav, ref_out = sys.argv[1], sys.argv[2] == "half", sys.argv[3], sys.argv[4]
i = Interpreter(model_path=os.path.expanduser("~/yamnet-model/yamnet.tflite"), experimental_preserve_all_tensors=True)
i.allocate_tensors()
ops = i._get_ops_details()
det = {t["index"]: t for t in i.get_tensor_details()}
tensors = [("window", 8), ("mel", 53), ("mel_bias", 69)]
layer = 0
for o in ops:
    if o["op_name"] in ("CONV_2D", "DEPTHWISE_CONV_2D"):
        layer += 1
        kind = "conv" if o["op_name"] == "CONV_2D" else "dw"
        tensors.append((f"{layer:02d}_{kind}_w", o["inputs"][1]))
        tensors.append((f"{layer:02d}_{kind}_b", o["inputs"][2]))
    elif o["op_name"] == "FULLY_CONNECTED" and o["index"] == 112:
        tensors.append(("dense_w", o["inputs"][1]))
        tensors.append(("dense_b", o["inputs"][2]))
with open(out_path, "wb") as f:
    f.write(b"YAMN" + struct.pack("<ii", 1, len(tensors)))
    for name, idx in tensors:
        a = np.array(i.get_tensor(idx), dtype=np.float32)
        enc = name.encode()
        f.write(struct.pack("<H", len(enc)) + enc + struct.pack("<B", a.ndim) + struct.pack(f"<{a.ndim}i", *a.shape))
        if half:
            f.write(struct.pack("<B", 1) + a.astype("<f2").tobytes())
        else:
            f.write(struct.pack("<B", 0) + a.astype("<f4").tobytes())
        print(name, a.shape, float(np.abs(a).max()))
print(os.path.getsize(out_path), "bytes")

# Reference: a 10 s excerpt of a song at 16 kHz → per-patch embeddings and scores, and the log-mel.
import soundfile as sf
from scipy.signal import resample_poly
x, r = sf.read(ref_wav, dtype="float32", always_2d=True)
m = x.mean(1)
mid = len(m) // 2
w = resample_poly(m[mid - 5 * r: mid + 5 * r], 16000, r).astype(np.float32)
inp = i.get_input_details()[0]["index"]
i.resize_tensor_input(inp, [len(w)], strict=False)
i.allocate_tensors()
i.set_tensor(inp, w)
i.invoke()
outs = {d["name"]: i.get_tensor(d["index"]) for d in i.get_output_details()}
np.savez(ref_out, wave=w, scores=outs["Identity"], embeddings=outs["Identity_1"], logmel=outs["Identity_2"])
print("reference:", outs["Identity_1"].shape, outs["Identity_2"].shape)
