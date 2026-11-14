"""Minimal NBT reader/writer (Java edition, big-endian). Values are kept as (tag_type, value) pairs
so files round-trip with their exact tag types. Compounds are dicts, lists are (element_type, [values])."""
import gzip
import struct

END, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTE_ARRAY, STRING, LIST, COMPOUND, INT_ARRAY, LONG_ARRAY = range(13)
_FMT = {BYTE: ">b", SHORT: ">h", INT: ">i", LONG: ">q", FLOAT: ">f", DOUBLE: ">d"}


class _Reader:
    def __init__(self, data):
        self.d, self.i = data, 0

    def take(self, n):
        b = self.d[self.i:self.i + n]
        self.i += n
        return b

    def num(self, fmt):
        return struct.unpack(fmt, self.take(struct.calcsize(fmt)))[0]

    def string(self):
        return self.take(self.num(">H")).decode("utf-8", "replace")

    def payload(self, t):
        if t in _FMT:
            return self.num(_FMT[t])
        if t == BYTE_ARRAY:
            return self.take(self.num(">i"))
        if t == STRING:
            return self.string()
        if t == LIST:
            et, n = self.num(">b"), self.num(">i")
            return (et, [self.payload(et) for _ in range(n)])
        if t == COMPOUND:
            out = {}
            while (ct := self.num(">b")) != END:
                name = self.string()
                out[name] = (ct, self.payload(ct))
            return out
        if t == INT_ARRAY:
            n = self.num(">i")
            return list(struct.unpack(f">{n}i", self.take(4 * n)))
        if t == LONG_ARRAY:
            n = self.num(">i")
            return list(struct.unpack(f">{n}q", self.take(8 * n)))
        raise ValueError(f"bad tag type {t} at {self.i}")


def load(path):
    """Reads a gzipped NBT file. Returns the root compound (dict)."""
    raw = open(path, "rb").read()
    if raw[:2] == b"\x1f\x8b":
        raw = gzip.decompress(raw)
    r = _Reader(raw)
    t = r.num(">b")
    if t != COMPOUND:
        raise ValueError("root is not a compound")
    r.string()
    return r.payload(COMPOUND)


def _write(out, t, v):
    if t in _FMT:
        out += struct.pack(_FMT[t], v)
    elif t == BYTE_ARRAY:
        out += struct.pack(">i", len(v)) + bytes(v)
    elif t == STRING:
        b = v.encode("utf-8")
        out += struct.pack(">H", len(b)) + b
    elif t == LIST:
        et, items = v
        out += struct.pack(">bi", et if items else END, len(items))
        for item in items:
            _write(out, et, item)
    elif t == COMPOUND:
        for name, (ct, cv) in v.items():
            out += struct.pack(">b", ct)
            _write(out, STRING, name)
            _write(out, ct, cv)
        out += b"\x00"
    elif t == INT_ARRAY:
        out += struct.pack(f">i{len(v)}i", len(v), *v)
    elif t == LONG_ARRAY:
        out += struct.pack(f">i{len(v)}q", len(v), *v)
    else:
        raise ValueError(f"bad tag type {t}")


def save(path, root):
    """Writes a root compound (dict) as a gzipped NBT file."""
    out = bytearray(b"\x0a\x00\x00")  # compound with empty name
    _write(out, COMPOUND, root)
    with open(path, "wb") as f:
        f.write(gzip.compress(bytes(out)))


def plain(v):
    """Strips tag types, for reading values conveniently."""
    t, x = v
    if t == COMPOUND:
        return {k: plain(c) for k, c in x.items()}
    if t == LIST:
        return [plain((x[0], i)) for i in x[1]]
    return x
