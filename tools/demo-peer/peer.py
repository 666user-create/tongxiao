"""
A real protocol peer for the 同晓 (Tongxiao) group feature.

Why this exists: the LAN group needs two devices, and the course demo needs to show
remote mute / delegated alarm / leaderboard live. The emulator's Wi-Fi is NATed, so a
second emulator cannot discover the first over mDNS. Instead this script speaks the
app's own wire protocol (newline-delimited JSON over TCP) and connects to the app's
PeerServer through `adb forward`, so the app sees a genuine member - not a mock:
it handshakes with the 6-digit group code, answers heartbeats, and can push the same
messages the real app pushes.

Commands are read from a control file (one command per line, appended by the driver):

    ringing       SYNC_PUSH with a ringing alarm (so the owner can mute it)
    calm          SYNC_PUSH with no ringing alarm
    setreq        SET_REQUEST "please add 06:40 alarm" (needs 可代设 granted)
    stats         STATS_PUSH with a wake-up leaderboard snapshot
    ping          send one PING
    quit          BYE + exit

Usage:
    python peer.py --port 19000 --group 123456 --dev peer-demo-1 --nick 小李
"""

import argparse
import json
import os
import socket
import sys
import threading
import time

FIELD_TYPE = "type"


class Peer:
    def __init__(self, host, port, group, dev, nick):
        self.sock = socket.create_connection((host, port), timeout=10)
        self.sock.settimeout(None)
        self.group = group
        self.dev = dev
        self.nick = nick
        self.buf = b""
        self.running = True
        self.alarm_uid = "peer-alarm-0640"

    # ---------- framing ----------
    def send(self, obj):
        line = json.dumps(obj, ensure_ascii=False, separators=(",", ":")) + "\n"
        self.sock.sendall(line.encode("utf-8"))
        print("[peer] >> " + line.strip(), flush=True)

    def reader(self):
        while self.running:
            try:
                chunk = self.sock.recv(4096)
            except OSError:
                break
            if not chunk:
                break
            self.buf += chunk
            while b"\n" in self.buf:
                line, self.buf = self.buf.split(b"\n", 1)
                text = line.decode("utf-8", "replace").strip()
                if not text:
                    continue
                print("[peer] << " + text, flush=True)
                try:
                    msg = json.loads(text)
                except ValueError:
                    continue
                kind = msg.get(FIELD_TYPE)
                if kind == "PING":
                    self.send({"type": "PING", "ts": int(time.time() * 1000)})
                elif kind == "CMD_MUTE":
                    # The owner asked us to mute the alarm that is ringing on our side.
                    self.send({"type": "CMD_ACK", "ok": True, "reason": "",
                               "what": "MUTE:" + str(msg.get("alarmUid", ""))})
                elif kind == "SET_RESPONSE":
                    print("[peer] owner answered our request: accept=%s" % msg.get("accept"),
                          flush=True)

    # ---------- messages ----------
    def hello(self):
        self.send({"type": "HELLO", "dev": self.dev, "nick": self.nick,
                   "group": self.group})

    def sync_push(self, ringing):
        alarms = [{
            "uid": self.alarm_uid,
            "label": "早八（小李的）",
            "time": "06:40",
            "repeat": "WORKDAY_SMART",
            "enabled": True,
            "ringing": bool(ringing),
        }]
        self.send({"type": "SYNC_PUSH", "alarms": alarms})

    def set_request(self):
        payload = {
            "hour": 7,
            "minute": 10,
            "label": "小李帮你设的",
            "repeatMode": "DAILY",
            "snoozeMin": 10,
            "vibrate": True,
        }
        self.send({"type": "SET_REQUEST", "reqId": "req-" + str(int(time.time())),
                   "kind": "ADD_ALARM", "payload": payload})

    def stats(self):
        payload = {
            "deviceId": self.dev,
            "days": 12,
            "medianStopMinute": 3,
            "snoozeCount": 2,
            "mutedCount": 1,
            "sentAt": int(time.time() * 1000),
        }
        self.send({"type": "STATS_PUSH", "payload": payload})

    def bye(self):
        try:
            self.send({"type": "BYE"})
        finally:
            self.running = False


def main():
    # The control file and console may carry a BOM / use a legacy codepage on Windows;
    # neither must be able to kill a running demo.
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("--group", required=True)
    ap.add_argument("--dev", default="peer-demo-1")
    ap.add_argument("--nick", default="小李")
    ap.add_argument("--control", required=True)
    ap.add_argument("--offset", type=int, default=0,
                    help="byte offset to start reading the control file from")
    args = ap.parse_args()

    peer = Peer(args.host, args.port, args.group, args.dev, args.nick)
    threading.Thread(target=peer.reader, daemon=True).start()
    time.sleep(0.2)
    peer.hello()

    pos = args.offset
    last_ping = time.time()
    # Keep the link fresh the same way the app does (heartbeat ~60s; we are faster so
    # a short demo never shows a stale member).
    while peer.running:
        time.sleep(0.1)
        if time.time() - last_ping > 20:
            peer.send({"type": "PING", "ts": int(time.time() * 1000)})
            last_ping = time.time()
        if not os.path.exists(args.control):
            continue
        with open(args.control, "r", encoding="utf-8") as handle:
            data = handle.read()
        if len(data) <= pos:
            continue
        new = data[pos:]
        pos = len(data)
        for raw in new.splitlines():
            cmd = raw.replace("\ufeff", "").strip()
            if not cmd:
                continue
            print("[peer] cmd: " + cmd, flush=True)
            if cmd == "ringing":
                peer.sync_push(True)
            elif cmd == "calm":
                peer.sync_push(False)
            elif cmd == "setreq":
                peer.set_request()
            elif cmd == "stats":
                peer.stats()
            elif cmd == "ping":
                peer.send({"type": "PING", "ts": int(time.time() * 1000)})
            elif cmd == "quit":
                peer.bye()
                break
            else:
                print("[peer] unknown command: " + cmd, flush=True)

    print("[peer] done", flush=True)
    sys.exit(0)


if __name__ == "__main__":
    main()
