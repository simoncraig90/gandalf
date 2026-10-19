#!/usr/bin/env python3
"""Gandalf voice: text -> 24 kHz WAV bytes with Kokoro (open source, on the Mac)."""
import io, os, sys
import numpy as np, soundfile as sf
from kokoro_onnx import Kokoro
HERE = os.path.dirname(os.path.abspath(__file__))
_k = None
def engine():
    global _k
    if _k is None:
        _k = Kokoro(os.path.join(HERE, "tts/kokoro-v1.0.onnx"), os.path.join(HERE, "tts/voices-v1.0.bin"))
    return _k
def wav_bytes(text, voice="bm_george", speed=0.9):
    samples, sr = engine().create(text, voice=voice, speed=speed, lang="en-gb")
    buf = io.BytesIO(); sf.write(buf, samples, sr, format="WAV", subtype="PCM_16"); return buf.getvalue()
if __name__ == "__main__":
    text = sys.argv[1]; out = sys.argv[2]; voice = sys.argv[3] if len(sys.argv) > 3 else "bm_george"
    open(out, "wb").write(wav_bytes(text, voice)); print(out)
