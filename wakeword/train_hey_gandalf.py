#!/usr/bin/env python3
"""Train the "hey gandalf" openWakeWord model (reduced recipe, all on the Mac).
Stages (run in order, each skips work already on disk):  prep | augment | train
  python train_hey_gandalf.py prep      # backgrounds -> 16 kHz, quiet/muffled variants, real-negative features
  python train_hey_gandalf.py augment   # openWakeWord augmentation (noise, RIR, pitch...) + embedding features
  python train_hey_gandalf.py train     # openWakeWord auto_train, exports out/hey_gandalf.onnx
Clips come from gen_clips.py.  Run with the .oww venv, PYTHONPATH=data/oww-repo (for openwakeword.train)."""
import os, sys, glob, random
import numpy as np, soundfile as sf, librosa, scipy.signal as ss
HERE = os.path.dirname(os.path.abspath(__file__)); D = os.path.join(HERE, "data"); O = os.path.join(HERE, "out")
REAL = os.path.join(HERE, "real")           # Simon's real clips (gitignored)
TRAIN_REAL_NEG = [0, 1, 2, 4]               # real negatives used for training; the rest (and clip_03) stay held out
CLIPS = os.path.join(D, "clips"); BG = os.path.join(D, "bg")
TOTAL = 32000

def prep():
    os.makedirs(BG, exist_ok=True)
    for f in glob.glob(D + "/esc/ESC-50-master/audio/*.wav"):
        o = os.path.join(BG, os.path.basename(f))
        if not os.path.exists(o):
            x, sr = librosa.load(f, sr=16000, mono=True); sf.write(o, x, 16000, subtype="PCM_16")
    rng = random.Random(5)
    for split, frac in (("pos_train", .67), ("neg_train", .55)):
        out = os.path.join(CLIPS, split + "_var"); os.makedirs(out, exist_ok=True)
        files = sorted(glob.glob(f"{CLIPS}/{split}/*.wav"))
        for f in rng.sample(files, int(len(files) * frac)):
            o = os.path.join(out, os.path.basename(f))
            if os.path.exists(o): continue
            x, _ = sf.read(f, dtype="float32")
            if rng.random() < .6:   # muffled: phone in a pocket / under a duvet
                fc = rng.uniform(2500, 4500); b, a = ss.butter(4, fc / 8000); x = ss.lfilter(b, a, x)
            x = x / (np.abs(x).max() + 1e-6) * 10 ** (rng.uniform(-44, -22) / 20)   # peak -44..-22 dBFS
            sf.write(o, x, 16000, subtype="PCM_16")
    print("prep ok")

def augment():
    import torch
    from openwakeword.data import augment_clips
    from openwakeword.utils import compute_features_from_generator
    os.makedirs(O, exist_ok=True)
    bg = [f.path for f in os.scandir(BG)]; rir = [f.path for f in os.scandir(D + "/mit_rir/16khz")]
    for name, dirs, rounds in (("positive_features_train", ["pos_train", "pos_train_var"], 2), ("negative_features_train", ["neg_train", "neg_train_var"], 2),
                               ("positive_features_test", ["pos_test"], 1), ("negative_features_test", ["neg_test"], 1)):
        outf = f"{O}/{name}.npy"
        if os.path.exists(outf): continue
        clips = [f for d in dirs for f in glob.glob(f"{CLIPS}/{d}/*.wav")] * rounds
        random.Random(1).shuffle(clips)
        gen = augment_clips(clips, total_length=TOTAL, batch_size=128, background_clip_paths=bg, RIR_paths=rir)
        compute_features_from_generator(gen, n_total=len(clips), clip_duration=TOTAL, output_file=outf, device="cpu", ncpu=5)
        print(name, np.load(outf, mmap_mode="r").shape)

def real_neg_features():
    from openwakeword.utils import AudioFeatures
    F = AudioFeatures(device="cpu"); rows = []
    for i in TRAIN_REAL_NEG:
        x, sr = sf.read(f"{REAL}/clip_{i:02d}.wav", dtype="float32"); assert sr == 16000
        for g in (1.0, 0.5, 2.0):       # a few gain variants of the same room/mic
            xi = (np.clip(x * g, -1, 1) * 32767).astype(np.int16)[None]
            e = F.embed_clips(xi, batch_size=1)[0]
            rows += [e[j:j + 16] for j in range(0, len(e) - 16)]
    a = np.array(rows, dtype=np.float32); np.save(f"{O}/real_negatives.npy", a); print("real neg windows", a.shape)

def train():
    import torch, yaml
    from openwakeword.train import Model
    from openwakeword.data import mmap_batch_generator
    if not os.path.exists(f"{O}/real_negatives.npy"): real_neg_features()
    fd = O
    pos_t = np.load(f"{fd}/positive_features_test.npy"); shape = pos_t.shape[1:]
    steps = int(os.environ.get("STEPS", 30000))
    oww = Model(n_classes=1, input_shape=shape, model_type="dnn", layer_dim=32, seconds_per_example=1280 * shape[0] / 16000)
    def f(x, n=shape[0]):
        if n != x.shape[1]:
            x = np.vstack(x); return np.array([x[i:i + n, :] for i in range(0, x.shape[0] - n, n)])
        return x
    files = {"ACAV100M_sample": f"{D}/acav_1M.npy", "ACAV100M_b": f"{D}/acav_2M.npy", "real_negatives": f"{O}/real_negatives.npy",
             "positive": f"{fd}/positive_features_train.npy", "adversarial_negative": f"{fd}/negative_features_train.npy"}
    dt = {k: f for k in ("ACAV100M_sample", "ACAV100M_b", "real_negatives")}
    lt = {k: ((lambda x: [1] * len(x)) if k == "positive" else (lambda x: [0] * len(x))) for k in files}
    gen = mmap_batch_generator(files, n_per_class={"ACAV100M_sample": 340, "ACAV100M_b": 684, "real_negatives": 24, "adversarial_negative": 60, "positive": 60},
                               data_transform_funcs=dt, label_transform_funcs=lt)
    class It(torch.utils.data.IterableDataset):
        def __iter__(s): return gen
    Xtr = torch.utils.data.DataLoader(It(), batch_size=None, num_workers=0)
    fp = np.load(f"{D}/val_fp.npy"); fp = np.array([fp[i:i + shape[0]] for i in range(0, fp.shape[0] - shape[0], 1)])
    Xfp = torch.utils.data.DataLoader(torch.utils.data.TensorDataset(torch.from_numpy(fp), torch.zeros(len(fp))), batch_size=len(fp))
    neg_t = np.load(f"{fd}/negative_features_test.npy")
    lab = np.hstack((np.ones(len(pos_t)), np.zeros(len(neg_t)))).astype(np.float32)
    Xv = torch.utils.data.DataLoader(torch.utils.data.TensorDataset(torch.from_numpy(np.vstack((pos_t, neg_t))), torch.from_numpy(lab)), batch_size=len(lab))
    best = oww.auto_train(X_train=Xtr, X_val=Xv, false_positive_val_data=Xfp, steps=steps,
                          max_negative_weight=int(os.environ.get("MAXNEG", 1500)), target_fp_per_hour=0.2)
    oww.export_model(model=best, model_name="hey_gandalf", output_dir=O)
    print("exported", O + "/hey_gandalf.onnx")

if __name__ == "__main__":
    {"prep": prep, "augment": augment, "train": train}[sys.argv[1]]()
