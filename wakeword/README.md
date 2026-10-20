# hey gandalf wake word (openWakeWord)

Status (20 Oct): pipeline works end to end and the app runs it, but the model is NOT good enough yet: it does not fire on
Simon's real clip_03 (best 0.056 with the shipped model). Cause: synthetic-only positives (domain gap). Fix: real positives.

## Retrain
Everything below is gitignored: `wakeword/.oww` venv (python 3.12, torch/torchaudio 2.5.1, scipy 1.14.1, openwakeword,
torch-audiomentations, audiomentations, acoustics, onnxruntime), `wakeword/data` (clips, ESC-50 backgrounds, MIT RIRs, ACAV100M
feature subset acav_1M.npy/acav_2M.npy, val_fp.npy = validation_set_features, openWakeWord clone in oww-repo), `wakeword/out`.
Run training with `PYTHONPATH=wakeword/data/oww-repo`, with melspectrogram/embedding onnx copied to
`data/oww-repo/openwakeword/resources/models/`.
1. `~/walk/.venv/bin/python gen_clips.py <pos_train|pos_test|neg_train|neg_test> <n> <seed> 9` (Kokoro voice blends + macOS `say`; used 4000/300/6000/500)
2. `python train_hey_gandalf.py prep` (16 kHz backgrounds; quiet -44..-22 dBFS and 2.5-4.5 kHz muffled variants)
3. `python train_hey_gandalf.py augment`
4. `STEPS=40000 MAXNEG=500 python train_hey_gandalf.py train` -> `out/hey_gandalf.onnx`, copy to
   `android/app/src/main/assets/oww/hey_gandalf.onnx`.
Reduced recipe: 3M of the 5.6M ACAV100M negative windows (range download), Kokoro+say instead of piper, ESC-50 backgrounds.

## Check
`eval_features.py` (recall, false accepts/hour on the 11.3 h validation set); `eval_clips.py [model] [--ungated]` (real clips, same 80 ms
framing and silence-skip as the app). On-device parity: put a 16 kHz wav at `files/selftest.wav` (adb run-as) and read `selftest top=`
in logcat (clip_03: 0.056 on phone and Mac).

## Real clips as positives later
Record 30+ "hey gandalf" on the phone (ClipSaver writes files/clips), cut to ~1.5 s, put in `data/clips/pos_train/` (repeat x10, `prep`
adds quiet/muffled variants), re-run augment + train. Hold some out for testing.

## Threshold
`OwwDetector.THRESHOLD` / `PATIENCE` in android/app/src/main/java/uk/simon/walk/OwwDetector.kt (0.5 / 1). The 5 s "wake level" log line shows `score=`.
