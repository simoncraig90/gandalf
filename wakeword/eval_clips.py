#!/usr/bin/env python3
"""Offline check that mirrors the phone: same ONNX models, same 80 ms framing as OwwDetector.kt, optionally the same
silence-skip gating as WakeService.kt (100 ms blocks, noise floor, 0.5 s pre-roll, reset after 1.5 s quiet).
  python eval_clips.py [hey_gandalf.onnx] [--ungated] [--curve clip_03] [clip.wav ...]
Run with the .oww venv.  Default clips: wakeword/real/*.wav."""
import os, sys, glob
import numpy as np, soundfile as sf, onnxruntime as ort, openwakeword
HERE = os.path.dirname(os.path.abspath(__file__))
M = os.path.join(os.path.dirname(openwakeword.__file__), "resources/models")
CH = 1280
def sess(p):
    o = ort.SessionOptions(); o.intra_op_num_threads = 1; return ort.InferenceSession(p, o, providers=["CPUExecutionProvider"])
class Det:
    def __init__(self, wake_path):
        self.mel = sess(f"{M}/melspectrogram.onnx"); self.emb = sess(f"{M}/embedding_model.onnx"); self.wake = sess(wake_path)
        self.raw = np.zeros(CH + 480, np.float32); self.pend = []; self.melb = np.ones((76, 32), np.float32); self.feat = np.zeros((16, 96), np.float32)
        rnd = np.random.RandomState(7); noise = rnd.randint(-1000, 1001, 40 * CH).astype(np.float32)
        for i in range(40): self._score(noise[i * CH:(i + 1) * CH])
        self.primed = self.feat.copy(); self.reset()
    def reset(self):
        self.raw[:] = 0; self.pend = []; self.melb[:] = 1; self.feat = self.primed.copy()
    def _score(self, x):
        self.raw = np.concatenate([self.raw[len(x):], x])
        m = self.mel.run(None, {"input": self.raw[None]})[0].reshape(-1, 32) / 10 + 2
        self.melb = np.concatenate([self.melb[len(m):], m])
        e = self.emb.run(None, {self.emb.get_inputs()[0].name: self.melb[None, :, :, None]})[0].reshape(96)
        self.feat = np.concatenate([self.feat[1:], e[None]])
        return float(self.wake.run(None, {self.wake.get_inputs()[0].name: self.feat[None]})[0].reshape(-1)[0])
    def feed(self, x):
        out = []; self.pend = list(self.pend) + list(x)
        while len(self.pend) >= CH:
            out.append(self._score(np.array(self.pend[:CH], np.float32))); self.pend = self.pend[CH:]
        return out
HANG = 15
def run(det, x16, gated=True):
    """x16: int16-scale float array. Returns list of (t_seconds, score)."""
    det.reset(); res = []; t = 0
    if not gated:
        for i in range(0, len(x16), 1600):
            for s in det.feed(x16[i:i + 1600]): t += CH; res.append((t / 16000, s))
        return res
    floor = 100.0; quiet = HANG + 1; pre = []
    for i in range(0, len(x16) - 1599, 1600):
        b = x16[i:i + 1600]; rms = float(np.sqrt(np.mean(b.astype(np.float64) ** 2)))
        loud = rms > max(floor * 2.5, 150.0)
        if not loud and quiet >= 0: floor = floor * 0.98 + rms * 0.02
        if loud:
            if quiet >= HANG:
                for p in pre: det.feed(p)   # pre-roll
            pre = []; quiet = 0
        elif quiet < HANG: quiet += 1
        else:
            if quiet == HANG: det.reset(); quiet += 1
            pre.append(b); pre = pre[-5:]; continue
        for s in det.feed(b): res.append(((i + 1600) / 16000, s))
    return res
def load(p):
    x, sr = sf.read(p, dtype="int16"); assert sr == 16000; return x.astype(np.float32)
if __name__ == "__main__":
    a = sys.argv[1:]; gated = "--ungated" not in a; a = [x for x in a if x != "--ungated"]
    curve = None
    if "--curve" in a: i = a.index("--curve"); curve = a[i + 1]; del a[i:i + 2]
    wp = a[0] if a and a[0].endswith(".onnx") else f"{HERE}/out/hey_gandalf.onnx"
    clips = [x for x in a if x.endswith(".wav")] or sorted(glob.glob(f"{HERE}/real/*.wav"))
    det = Det(wp)
    for c in clips:
        r = run(det, load(c), gated); mx = max([s for _, s in r] or [0])
        print(f"{os.path.basename(c)}  max={mx:.3f}  n>=0.5: {sum(s >= .5 for _, s in r)}  peaks: " +
              " ".join(f"{t:.1f}s:{s:.2f}" for t, s in r if s >= 0.2)[:200])
        if curve and curve in c: print("curve", " ".join(f"{t:.2f}:{s:.2f}" for t, s in r))
