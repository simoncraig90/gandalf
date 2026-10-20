# TICKET: custom open-source "hey gandalf" wake word (openWakeWord)

## Current state
20 Oct 01:30 — not started. Simon chose this route over tuning the sherpa-onnx KWS.

## Why
The app (~/walk/android, see README-android.md, android-SPEC.md) spots the wake word with the sherpa-onnx
3.3M gigaspeech KWS model. It hits synthetic Kokoro audio but NEVER hits Simon's real voice: in
`wakeword/real/clip_03.wav` (16 kHz mono, phone mic, ~-37 dBFS, Whisper reads "Heh Gandalf. Heh Gandalf.")
it misses both at every boost/threshold and 14 spellings; a 20M streaming zipformer ASR also fails.
Other clips in wakeword/real/ are NEGATIVES (Simon talking, TV/YouTube). These are Simon's voice: never commit them.

## Goal
Train an openWakeWord (github.com/dscripka/openWakeWord, Apache-2.0) model for "hey gandalf" and
replace sherpa-onnx in the app with it. Open source only, no cloud APIs, no Picovoice.

## Constraints
- Train on the Mac (CPU/MPS). The Windows 4070 PC's GPU is in use by something else: do not stop anything there.
- Positives: synthetic, many voices/speeds/accents. Use openWakeWord's automatic training recipe
  (piper-sample-generator, LibriTTS multi-speaker) and ALSO Kokoro voices (~/walk/.venv has kokoro-onnx;
  ~/walk/tts.py, models in ~/walk/tts/), including "heh gandalf"/"hey gandalf"/"hay gandalf" phrasings.
  Augment with noise and room impulse responses as the recipe does; add low-gain (-30 to -40 dBFS) and
  muffled (low-pass ~3–4 kHz) variants, since phone-in-pocket/bed audio is like that.
- Negatives: the recipe's standard negative features plus adversarial near-phrases ("hey google", "gandhi",
  "and also", "hey grandad", "candle") and the negative clips in wakeword/real/.
- Data and outputs go in ~/walk/wakeword/data and ~/walk/wakeword/out (gitignored). Disk: keep downloads lean.
- Android: run openWakeWord's melspectrogram.onnx + embedding_model.onnx + hey_gandalf.onnx with ONNX Runtime
  Android (com.microsoft.onnxruntime:onnxruntime-android) on 80 ms frames at 16 kHz, Kotlin, inside
  WakeService's existing loop. Keep: the silence skip (feed pre-roll on sound onset), AudioRecord retry loop,
  generation counter, diagnostics log line, and the debug ClipSaver (Simon will need it once more).
  Remove the sherpa-onnx AAR and kws assets if nothing else uses them (APK should shrink).
- Keep CPU low: measure the process's CPU ticks over 60 s on the phone, quiet room, before vs after
  (before: ~306 ticks/60 s with the silence skip).

## Done when
1. Offline (Mac): the trained model fires on BOTH utterances in wakeword/real/clip_03.wav at the chosen
   threshold, fires on none of the other real clips, and false-accepts <= 0.5 per hour on the recipe's
   validation negatives. Report the score curve on clip_03 and the chosen threshold.
2. On the phone: the APK builds (`cd ~/walk/android && JAVA_HOME=~/Library/Java/JavaVirtualMachines/jdk-21.0.12+8/Contents/Home ./gradlew assembleDebug`),
   installs over wireless adb (`~/Library/Android/sdk/platform-tools/adb -s 192.168.0.52:<port> install -r ...`;
   if not connected, say so, do not guess ports), starts, no crash in logcat for 2 min, and the CPU measurement is reported.
   Playing a Kokoro "hey gandalf" WAV through the Mac speaker is not available, so phone-side detection is
   verified by Simon; you verify offline with the same ONNX models and the same 80 ms framing.
3. A short `wakeword/README.md`: how to retrain, how to add Simon's real clips as positives later, threshold location.

## Status 20 Oct (builder): NOT DONE - criterion 1 failed
Cause (evidence): synthetic-only positives; recall on synthetic test 47% at 8 FP/h, or 24% at 0.5 FP/h (maxneg 1500); clip_03 max score 0.001 / 0.32 / 0.056 across 3 trainings.
Pipeline, app port, on-device parity (selftest 0.056 = Mac 0.056) proven. Next: real positives from the phone (ClipSaver), see wakeword/README.md.
