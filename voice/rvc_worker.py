#!/usr/bin/env python3
"""RVC voice-conversion worker. Runs in voice/work/rvc/.venv (Applio, MIT). Same line protocol as worker.py:
  in : {"engine":"rvc","src": wav path (base TTS render), "out": wav path, optional "pitch","index_rate","protect"}
  out: {"ok":true,"out":path,"sec":float} | {"ok":false,"err":str}
Private: model + index live in voice/models/ (gitignored). Loaded lazily on first request, then cached."""
import json, os, sys, time
HERE = os.path.dirname(os.path.abspath(__file__))
APPLIO = os.environ.get("WALK_RVC_APPLIO", os.path.join(HERE, "work/rvc/Applio"))
PTH = os.environ.get("WALK_RVC_PTH", os.path.join(HERE, "models/gandalf.pth"))
IDX = os.environ.get("WALK_RVC_INDEX", os.path.join(HERE, "models/gandalf.index"))
_vc = None

def load():
    global _vc
    if _vc is None:
        os.chdir(APPLIO); sys.path.insert(0, APPLIO)
        os.environ.setdefault("PYTORCH_ENABLE_MPS_FALLBACK", "1")
        from rvc.infer.infer import VoiceConverter
        _vc = VoiceConverter()
    return _vc

def convert(req):
    vc = load()
    vc.convert_audio(audio_input_path=req["src"], audio_output_path=req["out"], model_path=PTH,
                     index_path=IDX if os.path.exists(IDX) else "",
                     pitch=int(req.get("pitch", os.environ.get("WALK_RVC_PITCH", 0))), f0_method="rmvpe",
                     index_rate=float(req.get("index_rate", os.environ.get("WALK_RVC_INDEX_RATE", 0.8))),
                     protect=float(req.get("protect", 0.33)), volume_envelope=1.0, embedder_model="contentvec",
                     export_format="WAV")

def main():
    proto = sys.stdout; sys.stdout = sys.stderr  # library chatter must not corrupt the protocol
    def reply(o): proto.write(json.dumps(o) + "\n"); proto.flush()
    reply({"ok": True, "ready": True})
    for line in sys.stdin:
        try:
            req = json.loads(line); t = time.time(); convert(req)
            reply({"ok": True, "out": req["out"], "sec": time.time() - t})
        except Exception as e:
            reply({"ok": False, "err": repr(e)})

if __name__ == "__main__":
    main()
