"""Serves tools/structure-viewer.html on localhost, with the structures, the converter's reports and the block list.

Usage (from the repo root):
  python3 tools/structure_viewer.py [--world run/world] [--port 8765]
then open http://localhost:8765 in any browser.

Structures come from <world>/datapacks/*/data/minecp/structure and <world>/generated/minecp/structure; a hand-fixed
copy in generated/ takes priority, as it does in game. Saving writes the edited file to generated/. Only listens on
127.0.0.1: anyone who can reach it can overwrite structures.
"""
import argparse
import http.server
import json
import os
import re

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PATH_RE = re.compile(r"^[a-z0-9_.-]+(/[a-z0-9_.-]+)*$")  # a structure path like medievaloak/fundamentals/tavern1


def roots(world):
    """Structure folders, highest priority first: the hand-fixed ones, then each data pack's."""
    out = [os.path.join(world, "generated", "minecp", "structure")]
    packs = os.path.join(world, "datapacks")
    if os.path.isdir(packs):
        out += [os.path.join(packs, p, "data", "minecp", "structure") for p in sorted(os.listdir(packs))]
    return [r for r in out if os.path.isdir(r)]


def structures(world):
    """Every structure path (lower case, no .nbt), and whether a hand-fixed copy is used."""
    fixed_root = os.path.join(world, "generated", "minecp", "structure")
    found = {}
    for root in roots(world):
        for d, _, fs in os.walk(root):
            for f in fs:
                if f.endswith(".nbt"):
                    path = os.path.relpath(os.path.join(d, f), root).replace(os.sep, "/")[:-4].lower()
                    found.setdefault(path, root == fixed_root)
    return [{"path": p, "fixed": found[p]} for p in sorted(found)]


def find(world, path):
    """The file the game would load for a structure path, or None."""
    for root in roots(world):
        f = os.path.join(root, path + ".nbt")
        if os.path.isfile(f):
            return f
    return None


def handler(world):
    class Handler(http.server.BaseHTTPRequestHandler):
        def send(self, code, body, kind="application/json"):
            if isinstance(body, str):
                body = body.encode()
            self.send_response(code)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def file(self, f, kind):
            with open(f, "rb") as fh:
                self.send(200, fh.read(), kind)

        def structure_path(self, prefix):
            path = self.path[len(prefix):].split("?")[0]
            return path if PATH_RE.match(path) and ".." not in path else None

        def do_GET(self):
            if self.path in ("/", "/index.html"):
                return self.file(os.path.join(REPO, "tools", "structure-viewer.html"), "text/html; charset=utf-8")
            if self.path == "/api/structures":
                return self.send(200, json.dumps(structures(world)))
            if self.path == "/api/blocks":
                return self.file(os.path.join(REPO, "tools", "blocks-26.3.json"), "application/json")
            if self.path.startswith("/api/report/"):
                style = self.structure_path("/api/report/")
                f = style and os.path.join(REPO, "docs", "structures", style + ".md")
                return self.file(f, "text/markdown; charset=utf-8") if f and os.path.isfile(f) else self.send(404, "{}")
            if self.path.startswith("/api/structure/"):
                path = self.structure_path("/api/structure/")
                f = path and find(world, path)
                return self.file(f, "application/octet-stream") if f else self.send(404, "{}")
            self.send(404, "{}")

        def do_POST(self):
            path = self.structure_path("/api/structure/") if self.path.startswith("/api/structure/") else None
            if not path:
                return self.send(404, "{}")
            data = self.rfile.read(int(self.headers.get("Content-Length", 0)))
            if data[:2] != b"\x1f\x8b":  # structure files are gzipped NBT
                return self.send(400, json.dumps({"error": "not a gzipped structure file"}))
            out = os.path.join(world, "generated", "minecp", "structure", path + ".nbt")
            os.makedirs(os.path.dirname(out), exist_ok=True)
            with open(out, "wb") as fh:
                fh.write(data)
            self.send(200, json.dumps({"saved": os.path.relpath(out, os.path.dirname(world))}))  # "world/generated/..."

        def log_message(self, *args):  # quiet: one line per request is noise here
            pass

    return Handler


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--world", default=os.path.join(REPO, "run", "world"))
    ap.add_argument("--port", type=int, default=8765)
    a = ap.parse_args()
    server = http.server.ThreadingHTTPServer(("127.0.0.1", a.port), handler(os.path.abspath(a.world)))
    print(f"Structure viewer: http://localhost:{a.port}  (world: {a.world})")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
