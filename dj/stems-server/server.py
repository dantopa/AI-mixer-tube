"""
Bagracho DJ stems server: separates a track with Demucs and answers WHERE each stem plays, not the audio.

The phone POSTs a WAV (16-bit, mono 22.05 kHz is what the app sends) to /v1/stems with
`Authorization: Bearer <STEMS_TOKEN>`; the answer is JSON:
    {"hopMs": 250, "vocals": [...], "bass": [...], "drums": [...], "other": [...], "model": "htdemucs"}
one level (dBFS RMS, rounded, -90 = silence) per 250 ms frame per stem. Results are cached by the audio's SHA-256.

Run:  STEMS_TOKEN=<a long random string> uvicorn server:app --host 0.0.0.0 --port 8765
"""
import hashlib
import io
import json
import os
import threading

import numpy as np
import soundfile as sf
import torch
from demucs.apply import apply_model
from demucs.pretrained import get_model
from fastapi import FastAPI, Header, HTTPException, Request

MODEL_NAME = os.environ.get("STEMS_MODEL", "htdemucs")
TOKEN = os.environ.get("STEMS_TOKEN", "")
CACHE_DIR = os.environ.get("STEMS_CACHE", os.path.join(os.path.dirname(__file__), "cache"))
HOP_MS = 250
MAX_BYTES = 60 * 1024 * 1024

os.makedirs(CACHE_DIR, exist_ok=True)
device = "cuda" if torch.cuda.is_available() else ("mps" if torch.backends.mps.is_available() else "cpu")
model = get_model(MODEL_NAME)
model.eval().to(device)
lock = threading.Lock()  # one separation at a time: Demucs takes the whole machine
app = FastAPI(title="Bagracho DJ stems")


def levels(x: np.ndarray, sr: int) -> list:
    hop = int(sr * HOP_MS / 1000)
    n = len(x) // hop
    if n == 0:
        return []
    frames = x[: n * hop].reshape(n, hop)
    rms = np.sqrt(np.mean(frames.astype(np.float64) ** 2, axis=1))
    db = 20 * np.log10(np.maximum(rms, 10 ** (-90 / 20)))
    return [int(round(v)) for v in db]


def separate(audio: np.ndarray, sr: int) -> dict:
    if audio.ndim == 1:
        audio = np.stack([audio, audio], axis=1)
    wav = torch.tensor(audio.T, dtype=torch.float32)
    if sr != model.samplerate:
        import torchaudio

        wav = torchaudio.functional.resample(wav, sr, model.samplerate)
    if wav.shape[0] == 1:
        wav = wav.repeat(2, 1)
    ref = wav.mean(0)
    mean, std = ref.mean(), ref.std() + 1e-8
    with torch.no_grad():
        sources = apply_model(model, ((wav - mean) / std)[None].to(device), split=True, overlap=0.25, progress=False)[0]
    sources = sources * std + mean
    out = {"hopMs": HOP_MS, "model": MODEL_NAME}
    for name, s in zip(model.sources, sources):
        out[name] = levels(s.mean(0).cpu().numpy(), model.samplerate)
    for name in ("vocals", "bass", "drums", "other"):
        out.setdefault(name, [])
    return out


@app.get("/health")
def health():
    return {"ok": True, "model": MODEL_NAME, "device": device}


@app.post("/v1/stems")
async def stems(request: Request, authorization: str = Header(default="")):
    if not TOKEN or authorization != f"Bearer {TOKEN}":
        raise HTTPException(status_code=401, detail="bad token")
    body = await request.body()
    if not body or len(body) > MAX_BYTES:
        raise HTTPException(status_code=413, detail="empty or too large")
    key = hashlib.sha256(body).hexdigest()
    path = os.path.join(CACHE_DIR, key + ".json")
    if os.path.isfile(path):
        with open(path) as f:
            return json.load(f)
    try:
        audio, sr = sf.read(io.BytesIO(body), dtype="float32", always_2d=False)
    except Exception as e:  # noqa: BLE001
        raise HTTPException(status_code=400, detail=f"not readable audio: {e}")
    with lock:
        out = separate(audio, sr)
    with open(path, "w") as f:
        json.dump(out, f)
    return out
