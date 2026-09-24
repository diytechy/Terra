"""Drive a Minecraft server console from a simple script (lab tool, not shipped).

Script lines (one per line, '#' comments):
  send <console command>        write a command to the server's stdin
  wait <seconds> <regex>        block until a log line matches, fail on timeout
  sleep <seconds>
  mark                          remember the current log position; later waits only look after it

Usage:
  python mcdriver.py --cwd DIR --log FILE --script FILE -- <server command...>
Exit code: 0 when the script finishes and the server exits cleanly, 1 on timeout/failure.
"""
import argparse
import re
import subprocess
import sys
import threading
import time


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cwd", required=True)
    ap.add_argument("--log", required=True)
    ap.add_argument("--script", required=True)
    ap.add_argument("--stop-timeout", type=int, default=300)
    ap.add_argument("cmd", nargs=argparse.REMAINDER)
    a = ap.parse_args()
    cmd = a.cmd[1:] if a.cmd and a.cmd[0] == "--" else a.cmd

    lines = []
    lock = threading.Condition()
    logf = open(a.log, "w", encoding="utf-8", errors="replace")
    proc = subprocess.Popen(cmd, cwd=a.cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", bufsize=1)

    def reader():
        for line in proc.stdout:
            logf.write(line)
            logf.flush()
            with lock:
                lines.append(line.rstrip("\n"))
                lock.notify_all()
        with lock:
            lines.append("<<EOF>>")
            lock.notify_all()

    threading.Thread(target=reader, daemon=True).start()
    start = 0

    def wait(seconds, pattern):
        nonlocal start
        rx = re.compile(pattern)
        deadline = time.time() + seconds
        with lock:
            i = start
            while True:
                while i < len(lines):
                    if lines[i] == "<<EOF>>":
                        print(f"[driver] server exited while waiting for /{pattern}/", flush=True)
                        return False
                    if rx.search(lines[i]):
                        print(f"[driver] matched /{pattern}/: {lines[i][:200]}", flush=True)
                        start = i + 1
                        return True
                    i += 1
                left = deadline - time.time()
                if left <= 0:
                    print(f"[driver] TIMEOUT after {seconds}s waiting for /{pattern}/", flush=True)
                    return False
                lock.wait(min(left, 5))

    ok = True
    for raw in open(a.script, encoding="utf-8"):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        verb, _, rest = line.partition(" ")
        if verb == "send":
            print(f"[driver] > {rest}", flush=True)
            proc.stdin.write(rest + "\n")
            proc.stdin.flush()
        elif verb == "wait":
            secs, _, pattern = rest.partition(" ")
            if not wait(float(secs), pattern):
                ok = False
                break
        elif verb == "sleep":
            time.sleep(float(rest))
        elif verb == "mark":
            with lock:
                start = len(lines)
        else:
            print(f"[driver] unknown verb {verb}", flush=True)
            ok = False
            break

    if proc.poll() is None:
        try:
            proc.stdin.write("stop\n")
            proc.stdin.flush()
        except OSError:
            pass
        try:
            proc.wait(a.stop_timeout)
        except subprocess.TimeoutExpired:
            proc.kill()
            ok = False
    logf.close()
    print(f"[driver] done ok={ok} exit={proc.returncode}", flush=True)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
