#!/usr/bin/env python3
"""Synthesize training clips for "hey gandalf" with Kokoro (random voice blends, speeds) and macOS `say` voices.
usage: gen_clips.py <split> <n> <seed> [nproc]   split in pos_train pos_test neg_train neg_test
Writes 16 kHz mono int16 wavs to wakeword/data/clips/<split>/.  Needs the ~/walk/.venv python (kokoro-onnx)."""
import os, sys, random, subprocess, tempfile, hashlib
import numpy as np, soundfile as sf, librosa
from multiprocessing import Pool
HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.dirname(HERE)
OUT = os.path.join(HERE, "data/clips")
POS = ["hey gandalf", "hey gandalf", "hey, gandalf", "hay gandalf", "heh gandalf", "hey gandalf!", "hey gan dalf", "hey, gandalf.", "hay, gandalf", "heh, gandalf"]
ADV = ["hey google", "ok google", "hey gandhi", "hey grandad", "and also", "hey candle", "a candle", "gandalf", "the gandalf", "hey",
       "hey sandra", "hey randall", "hey handel", "hey daniel", "hey alexa", "hey siri", "hey galadriel", "hey grandma", "gandalf the grey",
       "hey ganesh", "hey candy", "hey ganda", "hey gan", "hey randolph", "hey landlord", "hey handle", "hello gandalf", "hey gannet",
       "hey dalek", "hey grand dad", "hey gandolf", "i'd also", "hey candice", "hey panda", "hey sandal", "hey scandal", "hey hand off",
       "hey gran", "hey dad", "hey glenda", "okay gandalf", "yeah candle", "hey ginger", "hey gary", "hey andrew", "hey angela",
       "hey gran dad", "gan dalf", "hey mandalf", "hey sandwich", "hey hannah", "hey grandpa", "the grand old", "hey garland"]
GEN = ["what time is it", "i think we should turn left here", "can you pass me that", "it's a lovely day today", "i'll see you tomorrow",
       "did you watch the match last night", "I need to buy some milk", "where did I put my keys", "let's go for a walk", "that was really funny",
       "I'm not sure about that", "he said it would be fine", "turn the lights off please", "how are you getting on", "the bus is late again",
       "have you eaten yet", "I was just thinking about that", "play some music", "what's the weather like", "right okay then",
       "yeah I know what you mean", "the dog needs feeding", "this is taking ages", "I'll call you back later", "good morning everyone",
       "so anyway", "no way that's amazing", "I have to go to work", "open the window", "she told me about it yesterday",
       "hello there", "hey how are you", "hey what's up", "hey guys welcome back", "hey listen to this", "oh my god", "that is a good idea",
       "can we get a pizza", "I'll have a cup of tea", "they are coming over on Saturday", "hey mate", "all right then",
       "hey you", "hey babe", "hey come here", "you have to see this video", "don't forget the thing", "subscribe and hit the bell",
       "in today's video we are going to", "breaking news from the capital", "and the winner is", "let me think about it"]
VOICES = None
def voices():
    global VOICES
    if VOICES is None:
        from kokoro_onnx import Kokoro
        global K
        import onnxruntime as rt
        so = rt.SessionOptions(); so.intra_op_num_threads = 1; so.inter_op_num_threads = 1
        sess = rt.InferenceSession(os.path.join(ROOT, "tts/kokoro-v1.0.onnx"), so, providers=["CPUExecutionProvider"])
        K = Kokoro.from_session(sess, os.path.join(ROOT, "tts/voices-v1.0.bin"))
        VOICES = [v for v in K.get_voices() if v[:2] not in ("jf", "jm", "zf", "zm")]
    return VOICES
def kokoro_clip(text, rng):
    vs = voices(); a = rng.choice(vs)
    if rng.random() < 0.6:  # blend two voices -> a new speaker
        b = rng.choice(vs); w = rng.uniform(0.2, 0.8)
        v = w * K.get_voice_style(a) + (1 - w) * K.get_voice_style(b)
    else: v = a
    lang = "en-gb" if a[0] in "bi" and rng.random() < .7 else "en-us"
    x, sr = K.create(text, voice=v, speed=rng.choice([0.8, 0.9, 1.0, 1.0, 1.1, 1.2, 1.3]), lang=lang)
    return librosa.resample(x.astype(np.float32), orig_sr=sr, target_sr=16000)
SAYV = ["Daniel", "Moira", "Rishi", "Tessa", "Karen", "Samantha", "Eddy (English (UK))", "Flo (English (US))", "Reed (English (UK))", "Rocko (English (US))",
        "Sandy (English (UK))", "Shelley (English (US))", "Tara", "Aman", "Kathy", "Ralph", "Fred", "Junior", "Grandpa (English (UK))", "Grandma (English (US))"]
def say_clip(text, rng):
    with tempfile.TemporaryDirectory() as d:
        f = os.path.join(d, "a.aiff")
        subprocess.run(["say", "-v", rng.choice(SAYV), "-r", str(rng.choice([140, 170, 200, 230])), "-o", f, text], check=True, capture_output=True)
        x, sr = sf.read(f, dtype="float32")
    if x.ndim > 1: x = x.mean(1)
    return librosa.resample(x, orig_sr=sr, target_sr=16000)
def job(a):
    split, i, seed = a
    rng = random.Random(seed * 100003 + i)
    pos = split.startswith("pos")
    text = rng.choice(POS) if pos else (rng.choice(ADV) if rng.random() < .6 else rng.choice(GEN))
    if not pos and rng.random() < .15: text = rng.choice(ADV) + " " + rng.choice(GEN)  # negatives with context
    if pos and rng.random() < .25: text = rng.choice(["ok ", "um ", "so ", "right ", ""]) + text
    try:
        x = say_clip(text, rng) if rng.random() < .22 else kokoro_clip(text, rng)
    except Exception as e:
        return None
    x = x / (np.abs(x).max() + 1e-6) * rng.uniform(0.3, 0.95)
    p = os.path.join(OUT, split, hashlib.md5(f"{split}{i}{seed}".encode()).hexdigest() + ".wav")
    sf.write(p, x, 16000, subtype="PCM_16"); return p
if __name__ == "__main__":
    split, n, seed = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]); npr = int(sys.argv[4]) if len(sys.argv) > 4 else 5
    os.makedirs(os.path.join(OUT, split), exist_ok=True)
    with Pool(npr) as p:
        r = [x for x in p.imap_unordered(job, [(split, i, seed) for i in range(n)], chunksize=8) if x]
    print(split, len(r))
