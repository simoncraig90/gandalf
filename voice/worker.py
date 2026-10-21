#!/usr/bin/env python3
"""Voice-clone worker. Runs in voice/.venv (py3.11, torch). Line protocol on stdin/stdout:
  in : one JSON per line {"engine": "chatterbox"|"openvoice", "text": str, "src": wav path (openvoice), "out": wav path}
  out: one JSON per line {"ok": true, "out": path, "sec": float} or {"ok": false, "err": str}
Private: reference clips live in voice/ref/ (gitignored). Engines load lazily on first use."""
import json, os, sys, re, time, types, glob
HERE = os.path.dirname(os.path.abspath(__file__))
REF = os.path.join(HERE, "ref")
_cb = _ov = None

def ref_files():
    return sorted(glob.glob(os.path.join(REF, "gandalf_*.wav")))

def split_sentences(text):
    parts = re.split(r"(?<=[.!?;])\s+", text.strip())
    return [p for p in parts if p]

def load_chatterbox():
    global _cb
    if _cb is None:
        import torch
        from chatterbox.tts import ChatterboxTTS
        dev = os.environ.get("WALK_VOICE_DEVICE") or ("mps" if torch.backends.mps.is_available() else "cpu")
        m = ChatterboxTTS.from_pretrained(device=dev)
        m.prepare_conditionals(os.environ.get("WALK_VOICE_CB_REF") or os.path.join(REF, "gandalf_c_moria_vocals.wav"))
        _cb = m
    return _cb

def chatterbox_sentence(m, s):
    # cap speech tokens (25 Hz): stops the runaway babble seen on some lines (19 s for a 5 s line)
    orig = m.t3.inference
    cap = int(len(s.split()) * 14 + 30)
    m.t3.inference = lambda *a, **k: orig(*a, **{**k, "max_new_tokens": cap})
    try:
        return m.generate(s, exaggeration=0.5, cfg_weight=0.4)
    finally:
        m.t3.inference = orig

def run_chatterbox(text, out):
    import torch, torchaudio as ta
    m = load_chatterbox()
    wavs = [chatterbox_sentence(m, s).cpu() for s in split_sentences(text)]
    gap = torch.zeros(1, int(0.15 * m.sr))
    seq = []
    for w in wavs: seq += [w, gap]
    ta.save(out, torch.cat(seq, -1), m.sr)

def load_openvoice():
    global _ov
    if _ov is None:
        src = os.environ.get("WALK_OV_SRC", os.path.join(HERE, "work/ov/src"))
        ck = os.environ.get("WALK_OV_CKPT", os.path.join(HERE, "work/ov/ckpt/converter"))
        sys.path.insert(0, src)
        sys.modules.setdefault("wavmark", types.ModuleType("wavmark"))  # watermark off; avoid the pinned dep
        from openvoice.api import ToneColorConverter
        import torch
        vc = ToneColorConverter.__new__(ToneColorConverter)
        # upstream __init__ forwards enable_watermark to the base class and crashes; build without it
        from openvoice.api import OpenVoiceBaseClass
        OpenVoiceBaseClass.__init__(vc, os.path.join(ck, "config.json"), device="cpu")
        vc.watermark_model = None
        vc.version = getattr(vc.hps, "_version_", "v1")
        vc.load_ckpt(os.path.join(ck, "checkpoint.pth"))
        tgt = vc.extract_se(ref_files())
        _ov = (vc, tgt)
    return _ov

def run_openvoice(src_wav, out):
    vc, tgt = load_openvoice()
    se = vc.extract_se([src_wav])  # source tone colour from the Kokoro render itself
    vc.convert(src_wav, se, tgt, out, tau=float(os.environ.get("WALK_OV_TAU", "0.3")), message="")

def main():
    proto = sys.stdout; sys.stdout = sys.stderr  # library chatter must not corrupt the protocol
    def reply(o): proto.write(json.dumps(o) + "\n"); proto.flush()
    reply({"ok": True, "ready": True})
    for line in sys.stdin:
        try:
            req = json.loads(line); t = time.time()
            if req["engine"] == "chatterbox": run_chatterbox(req["text"], req["out"])
            elif req["engine"] == "openvoice": run_openvoice(req["src"], req["out"])
            else: raise ValueError("unknown engine")
            reply({"ok": True, "out": req["out"], "sec": time.time() - t})
        except Exception as e:
            reply({"ok": False, "err": repr(e)})

if __name__ == "__main__":
    main()
