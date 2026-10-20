#!/usr/bin/env python3
"""Feature-level validation: recall on synthetic positive_test, adversarial negative_test, and false accepts/hour on the
recipe's 11.3 h validation negatives (val_fp.npy), per threshold.  python eval_features.py [model.onnx]"""
import sys, numpy as np, onnxruntime as ort, os
H = os.path.dirname(os.path.abspath(__file__))
s = ort.InferenceSession(sys.argv[1] if len(sys.argv) > 1 else f"{H}/out/hey_gandalf.onnx")
def sc(a):  # exported model has a fixed batch of 1
    n = s.get_inputs()[0].name; return np.array([s.run(None, {n: a[i:i+1].astype(np.float32)})[0].reshape(-1)[0] for i in range(len(a))])
P = sc(np.load(f"{H}/out/positive_features_test.npy")); N = sc(np.load(f"{H}/out/negative_features_test.npy"))
v = np.load(f"{H}/data/val_fp.npy"); V = sc(np.array([v[i:i+16] for i in range(0, len(v)-16)]))
for t in (.3,.4,.5,.6,.7,.8,.9):
    # count false accepts as rising edges (a detection event) per 11.3 h
    ev = ((V[1:] >= t) & (V[:-1] < t)).sum() + (V[0] >= t)
    print(f"thr {t}: recall {np.mean(P>=t):.3f}  advneg FP {np.mean(N>=t):.3f}  val FP windows {np.sum(V>=t)}  events/h {ev/11.3:.2f}")
