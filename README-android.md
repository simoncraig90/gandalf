# Walk app (Android)

Wake word "hey Gandalf" / "hey Claude" (sherpa-onnx) or the assistant gesture / earbud long-press starts a spoken
conversation with `server.py` (POST text/plain to `<server>/say`, header `X-Token`; a reply starting "Bye" ends it).

## Build
    export JAVA_HOME=~/Library/Java/JavaVirtualMachines/jdk-21.0.12+8/Contents/Home
    cd android && ./gradlew assembleDebug --no-daemon -q
APK: `android/app/build/outputs/apk/debug/app-debug.apk`. `android/local.properties` (sdk.dir) is git-ignored; create it.

## Install
`adb install -r app-debug.apk`, or copy the APK to the phone and open it. Install Tailscale on the phone first.

## Wake word
Open source (sherpa-onnx keyword spotting, Apache-2.0), on-device, no account, no AccessKey. Phrases: "hey gandalf",
"gandalf", "hey claude", "hi claude", "hello claude". To change them, edit `android/app/src/main/assets/kws/keywords.txt (the `@name` at the end must contain no spaces, e.g. `@hey_gandalf`, or the engine aborts at startup)`
and rebuild. Each line is BPE tokens, boost, threshold, phrase, e.g. `▁HE Y ▁GA ND AL F :2.0 #0.25 @hey gandalf`.
Generate the token part with `pip install sherpa-onnx click pypinyin sentencepiece` then
`sherpa-onnx-cli text2token --tokens tokens.txt --tokens-type bpe --bpe-model bpe.model in.txt out.txt`
(tokens.txt and bpe.model come from the sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01 release; bpe.model is not shipped in the app).
The phrase must be made of words the model's vocabulary can spell; raise the boost (:2.0) if it misses, lower it if it false-triggers.
The model (int8) and sherpa-onnx 1.13.8 AAR (`app/libs`) are bundled; APK is arm64-v8a only.

## First run
Open Gandalf, fill in server URL (default http://100.98.119.35:8846) and token (contents of `~/walk/token`),
tick "Wake word on", grant microphone/notification/Bluetooth permissions, accept the battery-exemption prompt.
"Test conversation" runs one conversation without the wake word. After a reboot (switch on) a notification "Gandalf: tap to start listening" appears; tapping it opens the app, which starts the service. Android 14+ forbids starting a microphone service straight from BOOT_COMPLETED, so listening does not resume by itself until you tap.

## Set as assistant (earbud long-press / assist gesture)
Press "Set as assistant" (Settings > Default digital assistant app), choose Walk. Long-press on the earbuds
should now start the conversation; if it plays/pauses instead, set the earbud's own app to "voice assistant".

## Not verified
Server replies: audio/wav (played directly, X-End: 1 ends) or text/plain (Android TTS). Built only; never run on a device. Bluetooth SCO mic routing, the assistant session, and Porcupine start-up are untested.
