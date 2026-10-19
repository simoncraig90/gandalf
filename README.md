# Walk assistant

Talk to Claude Code through Bluetooth earphones, phone in pocket. No API key: the brain is `claude -p` on the subscription.

Mac side (done): `server.py` listens on the Tailscale address 100.98.119.35:8846, launchd agent `com.simon.walk` keeps it up and
holds the Mac awake (`caffeinate -s`, mains power only). Log: `walk.log`. Project registry + aliases: `projects.json`.
Spoken-style system prompt: `prompt.md`. Secret: `token` (send as header `X-Token`).

What it does with what you say:
- "note / idea / remember / save ... <project> ..." → appended to `<repo>/INBOX.md`, no model call, replies "Saved to the X inbox."
- "done / stop / bye" → ends the conversation (next question starts a fresh Claude session).
- anything else → Claude Code (sonnet) with the project list; it reads the handoff hot section and answers in 1–3 spoken sentences.
  Follow-ups within 30 min resume the same session.

Phone side (Android, one-off, about 15 minutes):
1. Install Tailscale on the phone and sign in, so the phone can reach 100.98.119.35.
2. Install Tasker. Android Settings → Apps → Default apps → Digital assistant app → Tasker.
   (The earbud long-press / assistant gesture now opens Tasker instead of Google.)
3. Tasker → Profiles → + → Event → Tasker → Assistant Request. Create a new task called Walk.
4. Walk task actions, in order:
   1. Alert → Say: "Yes?" (Stream: Media). Optional, confirms it heard the button.
   2. Task → Label: listen
   3. Input → Get Voice (Title: Claude, Timeout: 10 s, tick "Continue Task After Error"). Result lands in %VOICE.
   4. Task → If %VOICE !Set → Stop (nothing heard).
   5. Net → HTTP Request: Method POST, URL http://100.98.119.35:8846/say,
      Headers: X-Token:<contents of ~/walk/token>, Body: %VOICE, Timeout: 90. Result lands in %http_data.
   6. Alert → Say: %http_data (Stream: Media, "Continue Task Immediately" OFF so it finishes speaking before listening).
   7. Task → If %http_data ~ Bye* → Stop
   8. Task → Goto: listen
5. Earbuds: if the long-press does play/pause instead, set it to "voice assistant" in the earbuds' own app.

Earbuds or phone: automatic. Get Voice and Say follow Android's current audio route, so with earbuds connected they use
the earbud mic and speakers; with none connected they use the phone's mic and loudspeaker. Nothing to configure.
Without earbuds the trigger is the phone's own assistant gesture (long-press power or the corner swipe on most phones);
the "Hey Google" wake word belongs to the Google app and will not reach Tasker.

Things to test on the first walk:
- With earbuds on, that Get Voice listens through the earbud mic and not the phone in your pocket. Some phones only open the
  Bluetooth voice link for calls. If that happens, the known fix is the AutoVoice plugin's Recognize action, which has a
  Bluetooth headset option, in place of Get Voice. Check that before buying it.
- Reply length feels right; shorten by editing `prompt.md` (no restart needed).

Ops: `launchctl unload/load ~/Library/LaunchAgents/com.simon.walk.plist`; edit `projects.json` to add a project (restart needed).
Sessions: any project with an `INBOX.md` reads it at start, acts on or files each item, removes it.

## Wake word ("hey Claude")

Android gives no app a free custom wake word; only Google's "Hey Google" is always listening. Two routes:

1. Try first (10 min, no battery cost): leave Google/Gemini as the default assistant, and in Tasker add the Google Assistant
   integration for the Walk task (Tasker → task Walk → long-press → "Assistant shortcut" / or Tasker Preferences → Misc →
   Google Assistant integration) with the phrase "hey Claude". Then "Hey Google, hey Claude" starts the conversation
   hands-free. The earbud long-press then goes to Google too, so "hey Claude" after the press works the same way.
   Unknown: whether Gemini still honours Tasker phrases on this phone (older Assistant did). A one-minute test decides.
2. True "hey Claude" with no "Hey Google" first: an always-listening wake-word engine on the phone. Picovoice Porcupine
   trains a custom "hey Claude" model (free for personal use) and runs on-device, light on battery. No Tasker plugin to vouch
   for, so this is a small Android app (foreground service + Porcupine + one HTTP call to the Mac, or a Tasker intent).
   About a day's build on the Windows PC's Android toolchain. Not started; decision pending.
   Not recommended: AutoVoice "Continuous" mode (battery-heavy, unreliable outdoors).
