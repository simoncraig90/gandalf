# Walk app (Android) — "hey Claude" wake word + earbud button, phone stays in pocket

## Current state
- 19 Oct 2026 evening: BUILT and merged to main (sherpa-onnx wake word, Gandalf label, audio/wav replies). Two skeptic passes, all gaps fixed except: compiled only, NEVER run on a device. First device test: say "hey gandalf", then Test conversation with earbuds on. APK: ~/walk/gandalf-debug.apk (gitignored; rebuild with `cd android && ./gradlew assembleDebug`).
- 19 Oct 2026: spec written; wake-word engine changed from Picovoice to sherpa-onnx keyword spotting (open source, no account) while the builder was mid-build. Mac side (server.py on 100.98.119.35:8846, header X-Token) is done and tested.

## Goal
One small Android app (Kotlin, Gradle, minSdk 26, targetSdk 34, package `uk.simon.walk`) in `android/` that:
1. Listens always for the wake phrases "hey/hi/hello Claude" using sherpa-onnx keyword spotting (Apache-2.0, on-device,
   English KWS zipformer 3.3M model in assets, keywords.txt generated from text, no account or key) inside a foreground
   service (type microphone).
2. Can also be chosen as the phone's default assistant app (VoiceInteractionService + VoiceInteractionSessionService + a
   minimal session). The assistant gesture / earbud long-press then starts the same conversation. Keep this minimal.
3. Conversation loop, started by either trigger:
   - stop the keyword spotter (it holds the mic); short beep (ToneGenerator);
   - if a Bluetooth headset is connected (AudioManager / BluetoothProfile.HEADSET): startBluetoothSco + setBluetoothScoOn(true),
     wait for SCO_AUDIO_STATE_CONNECTED (max 2 s), so the earbud mic is used; otherwise phone mic;
   - SpeechRecognizer (RecognizerIntent.ACTION_RECOGNIZE_SPEECH, free-form, partial results off) → text;
   - HTTP POST text/plain to `<serverUrl>/say` with header `X-Token: <token>`, timeout 100 s (OkHttp or HttpURLConnection);
   - TextToSpeech speaks the reply (UtteranceProgressListener; wait until done), audio stream MUSIC;
   - loop to listening; end when the reply starts with "Bye", or after two consecutive empty/no-match recognitions, or on a
     network error (speak the error in one short sentence);
   - then stop SCO, release recogniser, restart the keyword spotter.
4. Settings screen (single Activity, plain Views, no Compose): server URL (default
   `http://100.98.119.35:8846`), token, a "wake word on/off" switch, a "Test conversation" button, and a "Set as assistant"
   button that opens Settings.ACTION_VOICE_INPUT_SETTINGS. Stored in SharedPreferences. No secrets baked into the APK.
5. Starts the service on boot (BOOT_COMPLETED receiver) when the wake word switch is on; requests battery-optimisation
   exemption once; persistent low-priority notification "Walk: listening for hey Claude".
Permissions: RECORD_AUDIO, INTERNET, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE, BLUETOOTH_CONNECT,
POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED, MODIFY_AUDIO_SETTINGS. Cleartext HTTP allowed for the Tailscale address via
network_security_config.

## Build (Mac)
Android SDK at ~/Library/Android/sdk (cmdline-tools, build-tools, platform-tools present; install what is missing with
`sdkmanager "platforms;android-34" "build-tools;34.0.0"` and accept licences). JDK 21 at
~/Library/Java/JavaVirtualMachines/jdk-21.0.12+8/Contents/Home. Use the Gradle wrapper (gradle 8.x, AGP 8.x, Kotlin 1.9+).
Done when `./gradlew assembleDebug` succeeds and prints the APK path (android/app/build/outputs/apk/debug/app-debug.apk).
No device is available; compiling clean is the proof, plus a short README-android.md: install, how to change the wake phrase (keywords.txt), how to set as assistant.

## Rules
- Keep it small: about 6 Kotlin files. No Compose, no DI, no architecture layers.
- Don't touch server.py, prompt.md, projects.json or README.md.
- Commit on your branch with explicit paths (`git add android/ README-android.md`), message ending with the session
  attribution lines you were given.
