#!/usr/bin/env python3
"""Walk assistant: Tasker (voice) -> POST /say -> claude -p on the subscription -> spoken reply.
Notes that start with note/idea/remember/save and name a project are filed straight into that
project's INBOX.md without a model call. Everything else goes to Claude Code with project context."""
import datetime, json, os, re, subprocess, threading, time, uuid
try:
    import tts  # Gandalf voice (Kokoro); optional
except Exception as e:  # noqa
    tts = None
    print("tts unavailable:", e)
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
CFG = json.load(open(os.path.join(HERE, "projects.json")))
TOKEN = open(os.path.join(HERE, "token")).read().strip()
LOG = os.path.join(HERE, "walk.log")
IDLE = CFG.get("session_idle_min", 30) * 60
state = {"sid": None, "last": 0.0, "lock": threading.Lock()}
NOTE_RE = re.compile(r"^\s*(?:note|idea|remember|save)\b[:,]?\s*(.+)$", re.I | re.S)
END_RE = re.compile(r"^\s*(done|stop|that'?s all|bye|goodbye|thanks,? bye)[.!]?\s*$", re.I)


def log(msg):
    with open(LOG, "a") as f:
        f.write(f"{datetime.datetime.now():%Y-%m-%d %H:%M:%S} {msg}\n")


def root(p):
    return os.path.expanduser(p["path"])


def match_project(text):
    t = text.lower()
    for p in CFG["projects"]:
        for kw in [p["name"]] + p.get("aliases", []):
            if kw.lower() in t:
                return p
    return None


def append_note(p, body):
    d = root(p) if p else os.path.expanduser(CFG["default_inbox_dir"])
    path = os.path.join(d, "INBOX.md")
    new = not os.path.exists(path)
    with open(path, "a") as f:
        if new:
            f.write("# Inbox (walk notes)\n\nCaptured by voice while walking. A session reads this at start, "
                    "acts on or files each item, then removes it.\n\n")
        f.write(f"- [ ] {datetime.datetime.now():%Y-%m-%d %H:%M} — {body.strip()}\n")
    return path


def projects_block():
    lines = []
    for p in CFG["projects"]:
        h = os.path.join(root(p), p["handoff"]) if p.get("handoff") else "(no handoff file)"
        lines.append(f"- {p['name']} (aliases: {', '.join(p.get('aliases', []))}): {root(p)}; handoff: {h}")
    return "\n".join(lines)


def speakable(s):
    s = re.sub(r"```.*?```", " ", s, flags=re.S)
    s = re.sub(r"[`*_#>]+", "", s)
    s = re.sub(r"^\s*[-•]\s+", "", s, flags=re.M)
    return re.sub(r"\s+", " ", s).strip() or "I have nothing to say to that."


def ask(text):
    now = time.time()
    with state["lock"]:
        resume = state["sid"] if state["sid"] and now - state["last"] < IDLE else None
        if not resume:
            state["sid"] = str(uuid.uuid4())
        sid = state["sid"]
        state["last"] = now
    prompt = open(os.path.join(HERE, "prompt.md")).read().replace("{projects}", projects_block()) \
        .replace("{now}", f"{datetime.datetime.now():%A %d %B %Y, %H:%M}")
    cmd = ["claude", "-p", "--model", CFG.get("model", "sonnet"), "--output-format", "text",
           "--append-system-prompt", prompt,
           "--allowedTools", "Read", "Grep", "Glob", "Edit(**/INBOX.md)", "Write(**/INBOX.md)"]
    for p in CFG["projects"]:
        if os.path.isdir(root(p)):
            cmd += ["--add-dir", root(p)]
    cmd += ["--resume", sid] if resume else ["--session-id", sid]
    cmd.append(text)
    env = {k: v for k, v in os.environ.items() if k != "CLAUDECODE"}
    env["PATH"] = os.path.expanduser("~/.local/bin") + ":" + env.get("PATH", "/usr/bin:/bin")
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=120, cwd=HERE, env=env)
    except subprocess.TimeoutExpired:
        return "Sorry, that took too long. Ask again."
    if r.returncode != 0:
        log(f"claude rc={r.returncode} err={r.stderr.strip()[:300]}")
        with state["lock"]:
            state["sid"] = None
        return "Sorry, I hit an error. Try once more."
    return speakable(r.stdout)


def handle(text):
    text = text.strip()
    if not text:
        return "I didn't catch that."
    if END_RE.match(text):
        with state["lock"]:
            state["sid"] = None
        return "Bye. Enjoy the walk."
    m = NOTE_RE.match(text)
    p = match_project(text)
    if m and p:
        append_note(p, m.group(1))
        return f"Saved to the {p['name']} inbox."
    return ask(text)


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, code, body, end=False):
        data = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        if end:
            self.send_header("X-End", "1")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/apk":  # the phone downloads the app from here over Tailscale
            apk = os.path.join(HERE, "gandalf-debug.apk")
            if not os.path.exists(apk):
                return self._send(404, "no apk built")
            data = open(apk, "rb").read()
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Disposition", "attachment; filename=gandalf.apk")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            return self.wfile.write(data)
        self._send(200, "walk ok")

    def do_POST(self):
        if self.path == "/crash":  # crash reports from the phone app (no token: the app may crash before it is set)
            n = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(min(n, 65536)).decode("utf-8", "replace")
            with open(os.path.join(HERE, "crash.log"), "a") as f:
                f.write(f"\n===== {datetime.datetime.now():%Y-%m-%d %H:%M:%S} from {self.client_address[0]}\n{body}\n")
            log(f"crash report received ({n} bytes)")
            return self._send(200, "logged")
        if self.headers.get("X-Token") != TOKEN:
            return self._send(403, "no")
        n = int(self.headers.get("Content-Length") or 0)
        text = self.rfile.read(n).decode("utf-8", "replace")
        t0 = time.time()
        reply = handle(text)
        end = reply.startswith("Bye")
        want_audio = "audio/wav" in self.headers.get("Accept", "")
        wav = None
        if want_audio and tts:
            try:
                wav = tts.wav_bytes(reply, CFG.get("voice", "bm_george"), CFG.get("voice_speed", 0.9))
            except Exception as e:  # noqa
                log(f"tts error: {e}")
        log(f"{time.time()-t0:5.1f}s {'wav' if wav else 'txt'}  Q: {text[:120]!r}  A: {reply[:160]!r}")
        if wav:
            self.send_response(200)
            self.send_header("Content-Type", "audio/wav")
            self.send_header("Content-Length", str(len(wav)))
            self.send_header("X-Text", reply.encode("ascii", "ignore").decode()[:200].replace("\n", " "))
            if end:
                self.send_header("X-End", "1")
            self.end_headers()
            self.wfile.write(wav)
        else:
            self._send(200, reply, end)


if __name__ == "__main__":
    if tts:
        threading.Thread(target=tts.engine, daemon=True).start()  # warm the voice
    srv = ThreadingHTTPServer((CFG["bind"], CFG["port"]), H)
    log(f"listening on {CFG['bind']}:{CFG['port']}")
    srv.serve_forever()
