# Walk app (Android)

Wake word "hey Claude" (Picovoice Porcupine) or the assistant gesture / earbud long-press starts a spoken
conversation with `server.py` (POST text/plain to `<server>/say`, header `X-Token`; a reply starting "Bye" ends it).

## Build
    export JAVA_HOME=~/Library/Java/JavaVirtualMachines/jdk-21.0.12+8/Contents/Home
    cd android && ./gradlew assembleDebug --no-daemon -q
APK: `android/app/build/outputs/apk/debug/app-debug.apk`. `android/local.properties` (sdk.dir) is git-ignored; create it.

## Install
`adb install -r app-debug.apk`, or copy the APK to the phone and open it. Install Tailscale on the phone first.

## Picovoice
1. Sign up free at console.picovoice.ai, copy the AccessKey, paste it in the app.
2. Without a keyword file the built-in word "computer" is used, so you can test right away.
3. To get "hey Claude": in the console train a Porcupine wake word "hey Claude" for Android, download the `.ppn`,
   save it as `android/app/src/main/assets/hey_claude_android.ppn`, rebuild, reinstall.

## First run
Open Walk, fill in AccessKey, server URL (default http://100.98.119.35:8846) and token (contents of `~/walk/token`),
tick "Wake word on", grant microphone/notification/Bluetooth permissions, accept the battery-exemption prompt.
"Test conversation" runs one conversation without the wake word. The service restarts at boot while the switch is on.

## Set as assistant (earbud long-press / assist gesture)
Press "Set as assistant" (Settings > Default digital assistant app), choose Walk. Long-press on the earbuds
should now start the conversation; if it plays/pauses instead, set the earbud's own app to "voice assistant".

## Not verified
Built only; never run on a device. Bluetooth SCO mic routing, the assistant session, and Porcupine start-up are untested.
