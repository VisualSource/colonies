"""Serves tools/structure-viewer.html on localhost, with the structures, the converter's reports and the block list.

Usage (from the repo root):
  python3 tools/structure_viewer.py [--world run/world] [--port 8765]
then open http://localhost:8765 in any browser.

Structures come from <world>/datapacks/*/data/minecp/structure and <world>/generated/minecp/structure; a hand-fixed
copy in generated/ takes priority, as it does in game. Saving writes the edited file to generated/. Only listens on
127.0.0.1: anyone who can reach it can overwrite structures.

The 3D view shows the game's block textures, read from a Minecraft client jar: --client <jar>, else the launcher's
.minecraft/versions/<version>/<version>.jar, else downloaded once from Mojang's public version manifest into
build/client-<version>.jar. Without one the view falls back to plain colours.
"""
import argparse
import http.server
import json
import os
import re
import threading
import urllib.request
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PATH_RE = re.compile(r"^[a-z0-9_.-]+(/[a-z0-9_.-]+)*$")  # a structure path like medievaloak/fundamentals/tavern1
TEXTURE_RE = re.compile(r"^[a-z0-9_/]+$")  # a texture path like block/oak_planks
VERSION = "26.3"  # the game version the converter targets (tools/blocks-26.3.json)
MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
FACES = ("down", "up", "north", "south", "west", "east")


class Textures:
    """Block face textures from a Minecraft client jar: its blockstates and models, followed through their parents."""

    def __init__(self, jar):
        self.jar_path, self.jar, self.lock, self.faces = jar, None, threading.Lock(), {}

    def open(self):
        """The client jar, found or downloaded the first time it is needed; None if there is none."""
        with self.lock:
            if self.jar is None:
                path = self.jar_path or find_client() or download_client()
                self.jar = zipfile.ZipFile(path) if path and os.path.isfile(path) else False
            return self.jar or None

    def read(self, name):
        jar = self.open()
        if not jar:
            return None
        try:
            with self.lock:  # one reader at a time: the server answers requests on threads
                return jar.read(name)
        except KeyError:
            return None

    def json(self, name):
        data = self.read(name)
        return json.loads(data) if data else None

    def model(self, ref):
        """A model id ("minecraft:block/oak_stairs") with its parents' textures and elements merged in."""
        textures, elements, seen = {}, None, set()
        while ref and ref not in seen:
            seen.add(ref)
            m = self.json("assets/minecraft/models/" + ref.split(":")[-1] + ".json")
            if not m:
                break
            for k, v in m.get("textures", {}).items():
                textures.setdefault(k, v)  # a child's own textures win over its parent's
            if elements is None and "elements" in m:
                elements = m["elements"]
            ref = m.get("parent")
        return textures, elements or []

    def block(self, name):
        """{face: {"texture": "block/oak_log_top", "tint": bool}} for a block ("oak_stairs"), or None."""
        if name in self.faces:
            return self.faces[name]
        state = self.json(f"assets/minecraft/blockstates/{name}.json")
        ref = None
        if state and "variants" in state:
            v = next(iter(state["variants"].values()))
            ref = (v[0] if isinstance(v, list) else v).get("model")
        elif state and "multipart" in state:
            apply = state["multipart"][0]["apply"]
            ref = (apply[0] if isinstance(apply, list) else apply).get("model")
        out = None
        if ref:
            textures, elements = self.model(ref)

            def resolve(t):
                for _ in range(10):  # "#side" -> "#all" -> "block/stone"
                    if isinstance(t, dict):  # newer models: {"sprite": "block/glass", ...}
                        t = t.get("sprite")
                    if not t or not t.startswith("#"):
                        break
                    t = textures.get(t[1:])
                return t.split(":")[-1] if isinstance(t, str) else None

            faces = {}
            for el in elements:
                for f in FACES:
                    face = el.get("faces", {}).get(f)
                    if f not in faces and face:
                        faces[f] = {"texture": resolve(face.get("texture")), "tint": "tintindex" in face}
            if not faces and textures:  # no elements (a built-in model, as a chest's): its particle texture all round
                t = resolve(textures.get("particle") or "#" + next(iter(textures)))
                faces = {f: {"texture": t, "tint": False} for f in FACES}
            if "water" in name:  # the game tints water in code, not in its model
                for v in faces.values():
                    v["tint"] = True
            out = {f: v for f, v in faces.items() if v["texture"]} or None
        self.faces[name] = out
        return out


def find_client():
    """The launcher's client jar for VERSION, if installed."""
    for base in (os.path.join(os.environ.get("APPDATA", ""), ".minecraft"), os.path.join(os.path.expanduser("~"), ".minecraft")):
        jar = os.path.join(base, "versions", VERSION, VERSION + ".jar")
        if os.path.isfile(jar):
            return jar
    return None


def download_client():
    """Downloads the VERSION client jar once from Mojang's public manifest into build/; None if that fails."""
    out = os.path.join(REPO, "build", f"client-{VERSION}.jar")
    if os.path.isfile(out):
        return out
    try:
        print(f"Downloading the Minecraft {VERSION} client jar for block textures (once, into build/)...")
        manifest = json.load(urllib.request.urlopen(MANIFEST, timeout=30))
        meta_url = next(v["url"] for v in manifest["versions"] if v["id"] == VERSION)
        client = json.load(urllib.request.urlopen(meta_url, timeout=30))["downloads"]["client"]["url"]
        os.makedirs(os.path.dirname(out), exist_ok=True)
        urllib.request.urlretrieve(client, out + ".part")
        os.replace(out + ".part", out)
        return out
    except Exception as e:  # offline, or no such version: plain colours instead
        print(f"No client jar ({e}): the 3D view uses plain colours. Pass --client <jar> to use one.")
        return None


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


def handler(world, textures):
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
            if self.path.startswith("/api/faces/"):  # a block's face textures: /api/faces/oak_stairs
                name = self.path[len("/api/faces/"):].split("?")[0]
                faces = textures.block(name) if re.match(r"^[a-z0-9_]+$", name) else None
                return self.send(200, json.dumps(faces)) if faces else self.send(404, "{}")
            if self.path.startswith("/api/texture/"):  # a texture: /api/texture/block/oak_planks.png
                tex = self.path[len("/api/texture/"):].split("?")[0].removesuffix(".png")
                data = textures.read(f"assets/minecraft/textures/{tex}.png") if TEXTURE_RE.match(tex) else None
                return self.send(200, data, "image/png") if data else self.send(404, "{}")
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
    ap.add_argument("--client", default=None, help="a Minecraft client jar for the block textures")
    a = ap.parse_args()
    server = http.server.ThreadingHTTPServer(("127.0.0.1", a.port), handler(os.path.abspath(a.world), Textures(a.client)))
    print(f"Structure viewer: http://localhost:{a.port}  (world: {a.world})")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
