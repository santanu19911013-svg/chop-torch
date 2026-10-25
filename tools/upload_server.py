"""Tiny LAN server for phone testing: serves one folder read-only and accepts recording uploads.

    python -I tools/upload_server.py <serve_dir> <upload_dir> [port]

GET  /<file>                  files from serve_dir (the APK and its download page)
POST /upload?name=<file.csv>  body saved as upload_dir/<device>_<file.csv>; header X-Device names the phone

Upload names are reduced to [A-Za-z0-9._-], must end in .csv, and are capped at 300 MB. Nothing outside
the two folders is ever read or written. Stop it with Ctrl+C. Bind is 0.0.0.0, so anything on the
LAN can reach it while it runs.
"""
import http.server
import os
import re
import sys
import urllib.parse

MAX_BYTES = 300 * 1024 * 1024


def safe(name):
    name = re.sub(r"[^A-Za-z0-9._-]", "_", name or "")
    return name.strip("._")[:120]


class Handler(http.server.SimpleHTTPRequestHandler):
    upload_dir = "."

    def do_POST(self):
        url = urllib.parse.urlparse(self.path)
        if url.path != "/upload":
            self.send_error(404)
            return
        name = safe(urllib.parse.parse_qs(url.query).get("name", [""])[0])
        device = safe(self.headers.get("X-Device", "phone")) or "phone"
        length = int(self.headers.get("Content-Length") or 0)
        if not name.endswith(".csv") or length <= 0 or length > MAX_BYTES:
            self.send_error(400, "need ?name=<file>.csv and a body under 300 MB")
            return
        path = os.path.join(self.upload_dir, device + "_" + name)
        remaining = length
        with open(path, "wb") as out:
            while remaining > 0:
                chunk = self.rfile.read(min(1 << 20, remaining))
                if not chunk:
                    break
                out.write(chunk)
                remaining -= len(chunk)
        ok = remaining == 0
        self.log_message("upload %s: %d bytes%s", path, length - remaining, "" if ok else " (TRUNCATED)")
        self.send_response(200 if ok else 400)
        self.end_headers()
        self.wfile.write(b"saved\n" if ok else b"truncated\n")


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    serve_dir, upload_dir = os.path.abspath(sys.argv[1]), os.path.abspath(sys.argv[2])
    port = int(sys.argv[3]) if len(sys.argv) > 3 else 8765
    os.makedirs(upload_dir, exist_ok=True)
    Handler.upload_dir = upload_dir

    def handler(*a, **kw):
        return Handler(*a, directory=serve_dir, **kw)

    print("serving %s, uploads to %s, port %d" % (serve_dir, upload_dir, port), flush=True)
    http.server.ThreadingHTTPServer(("0.0.0.0", port), handler).serve_forever()


if __name__ == "__main__":
    main()
