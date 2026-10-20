#!/usr/bin/env python3
"""Gandalf voice: text -> WAV bytes. Kokoro (default, in-process) or a voice-clone engine
(projects.json "voice_engine": "kokoro" | "openvoice" | "chatterbox" | "rvc"). Clone engines run in a
worker process (voice/worker.py in voice/.venv; "rvc" = voice/rvc_worker.py in voice/work/rvc/.venv, Kokoro render -> RVC)
and fall back to Kokoro on any error or timeout."""
import io, os, sys, json, time, subprocess, tempfile, threading
import numpy as np, soundfile as sf
from kokoro_onnx import Kokoro
HERE = os.path.dirname(os.path.abspath(__file__))
VOICE_PY = os.path.join(HERE, "voice/.venv/bin/python")
RVC_PY = os.path.join(HERE, "voice/work/rvc/.venv/bin/python")
# semitone shift to land each Kokoro voice in McKellen's speaking range (median F0 ~108 Hz)
RVC_PITCH = {"bm_george": -4, "bm_daniel": -2, "bm_lewis": 2, "bm_fable": 0}
WORKERS = {"main": (VOICE_PY, "voice/worker.py"), "rvc": (RVC_PY, "voice/rvc_worker.py")}
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

# ---- clone workers (one subprocess per kind, restarted on death/timeout) -------------------
_w = {}; _lock = threading.Lock()
def _worker(kind):
    w = _w.get(kind)
    if w is None or w.poll() is not None:
        py, script = WORKERS[kind]
        w = _w[kind] = subprocess.Popen([py, os.path.join(HERE, script)], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=open(os.path.join(HERE, "voice/worker.log"), "ab"), text=True)
        json.loads(w.stdout.readline())  # ready line
    return w
def _kill(kind):
    w = _w.pop(kind, None)
    if w is not None:
        try: w.kill()
        except Exception: pass
def _call(req, timeout):
    """One request to the worker; raises on error/timeout (and drops the worker so the next call restarts it)."""
    kind = "rvc" if req["engine"] == "rvc" else "main"
    with _lock:
        w = _worker(kind); w.stdin.write(json.dumps(req) + "\n"); w.stdin.flush()
        done = threading.Event(); res = []
        def rd(): res.append(w.stdout.readline()); done.set()
        threading.Thread(target=rd, daemon=True).start()
        if not done.wait(timeout): _kill(kind); raise TimeoutError(f"{req['engine']} > {timeout}s")
        r = json.loads(res[0] or "{}")
        if not r.get("ok"): raise RuntimeError(r.get("err", "worker died"))
        return r

def clone_wav(eng, text, voice, speed, timeout):
    c = cfg()
    with tempfile.TemporaryDirectory(prefix="walkvoice") as d:
        out = os.path.join(d, "out.wav"); req = {"engine": eng, "text": text, "out": out}
        if eng in ("openvoice", "rvc"):
            if eng == "rvc": voice = c.get("rvc_base_voice", voice)
            src = os.path.join(d, "src.wav"); open(src, "wb").write(kokoro_wav(text, voice, speed)); req["src"] = src
            if eng == "rvc":
                req["pitch"] = RVC_PITCH.get(voice, 0)
                for k in ("pitch", "index_rate", "protect"):
                    if "rvc_" + k in c: req[k] = c["rvc_" + k]
        _call(req, timeout)
        return open(out, "rb").read()

def wav_bytes(text, voice="bm_george", speed=0.9):
    c = cfg(); eng = os.environ.get("WALK_VOICE_ENGINE") or c.get("voice_engine", "kokoro")
    if eng in ("openvoice", "chatterbox", "rvc"):
        try: return clone_wav(eng, text, voice, speed, float(c.get("voice_timeout", 60)))
        except Exception as e: print(f"tts {eng} failed, falling back to kokoro: {e!r}", file=sys.stderr, flush=True)
    return kokoro_wav(text, voice, speed)

if __name__ == "__main__":
    text = sys.argv[1]; out = sys.argv[2]; voice = sys.argv[3] if len(sys.argv) > 3 else "bm_george"
    open(out, "wb").write(wav_bytes(text, voice)); print(out)
