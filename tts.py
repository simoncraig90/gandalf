#!/usr/bin/env python3
"""Gandalf voice: text -> WAV bytes. Kokoro (default, in-process) or a voice-clone engine
(projects.json "voice_engine": "kokoro" | "openvoice" | "chatterbox"). Clone engines run in a
worker process (voice/worker.py, voice/.venv) and fall back to Kokoro on any error or timeout."""
import io, os, sys, json, time, subprocess, tempfile, threading
import numpy as np, soundfile as sf
from kokoro_onnx import Kokoro
HERE = os.path.dirname(os.path.abspath(__file__))
VOICE_PY = os.path.join(HERE, "voice/.venv/bin/python")
_k = None
def engine():
    global _k
    if _k is None:
        _k = Kokoro(os.path.join(HERE, "tts/kokoro-v1.0.onnx"), os.path.join(HERE, "tts/voices-v1.0.bin"))
    return _k

def cfg():
    try: return json.load(open(os.path.join(HERE, "projects.json")))
    except Exception: return {}

def kokoro_wav(text, voice="bm_george", speed=0.9):
    samples, sr = engine().create(text, voice=voice, speed=speed, lang="en-gb")
    buf = io.BytesIO(); sf.write(buf, samples, sr, format="WAV", subtype="PCM_16"); return buf.getvalue()

# ---- clone worker -------------------------------------------------------------------------
_w = None; _lock = threading.Lock()
def _worker():
    global _w
    if _w is None or _w.poll() is not None:
        _w = subprocess.Popen([VOICE_PY, os.path.join(HERE, "voice/worker.py")], stdin=subprocess.PIPE,
                              stdout=subprocess.PIPE, stderr=open(os.path.join(HERE, "voice/worker.log"), "ab"), text=True)
        json.loads(_w.stdout.readline())  # ready line
    return _w
def _kill():
    global _w
    if _w is not None:
        try: _w.kill()
        except Exception: pass
    _w = None
def _call(req, timeout):
    """One request to the worker; raises on error/timeout (and drops the worker so the next call restarts it)."""
    with _lock:
        w = _worker(); w.stdin.write(json.dumps(req) + "\n"); w.stdin.flush()
        done = threading.Event(); res = []
        def rd(): res.append(w.stdout.readline()); done.set()
        threading.Thread(target=rd, daemon=True).start()
        if not done.wait(timeout): _kill(); raise TimeoutError(f"{req['engine']} > {timeout}s")
        r = json.loads(res[0] or "{}")
        if not r.get("ok"): raise RuntimeError(r.get("err", "worker died"))
        return r

def clone_wav(eng, text, voice, speed, timeout):
    with tempfile.TemporaryDirectory(prefix="walkvoice") as d:
        out = os.path.join(d, "out.wav"); req = {"engine": eng, "text": text, "out": out}
        if eng == "openvoice":
            src = os.path.join(d, "src.wav"); open(src, "wb").write(kokoro_wav(text, voice, speed)); req["src"] = src
        _call(req, timeout)
        return open(out, "rb").read()

def wav_bytes(text, voice="bm_george", speed=0.9):
    c = cfg(); eng = os.environ.get("WALK_VOICE_ENGINE") or c.get("voice_engine", "kokoro")
    if eng in ("openvoice", "chatterbox"):
        try: return clone_wav(eng, text, voice, speed, float(c.get("voice_timeout", 60)))
        except Exception as e: print(f"tts {eng} failed, falling back to kokoro: {e!r}", file=sys.stderr, flush=True)
    return kokoro_wav(text, voice, speed)

if __name__ == "__main__":
    text = sys.argv[1]; out = sys.argv[2]; voice = sys.argv[3] if len(sys.argv) > 3 else "bm_george"
    open(out, "wb").write(wav_bytes(text, voice)); print(out)
