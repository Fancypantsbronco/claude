"""lowpoly.py - Low-Poly-Bausteine mit Vertex-Farben und glTF/GLB-Export.

Neu nachgebaut fuer Chaos Arena (Vorbild: WoW-Simulator/tools/lowpoly.py).
Nur Python-Standardbibliothek, kein Blender.

Konventionen:
- Einheiten in Metern, Y zeigt nach oben, Modelle schauen nach +Z.
- Flat Shading: Jedes Dreieck bekommt eigene Vertices und eine Flaechennormale.
- Farben werden als sRGB-Hex angegeben und als lineare COLOR_0-Werte exportiert.
"""

import json
import math
import random
import struct


# ---------------------------------------------------------------- Farben

def hex_rgb(value):
    """'#a8432e' -> (r, g, b) im sRGB-Raum, 0..1."""
    value = value.lstrip("#")
    return tuple(int(value[i:i + 2], 16) / 255.0 for i in (0, 2, 4))


def srgb_to_linear(c):
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def shade(color, factor):
    """Hellt eine sRGB-Farbe auf (>1) oder dunkelt sie ab (<1)."""
    return tuple(min(1.0, max(0.0, c * factor)) for c in color)


# ---------------------------------------------------------------- Vektoren

def _sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def _cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def _normalize(v):
    length = math.sqrt(v[0] ** 2 + v[1] ** 2 + v[2] ** 2)
    if length == 0:
        return (0.0, 1.0, 0.0)
    return (v[0] / length, v[1] / length, v[2] / length)


def transform(pos=(0.0, 0.0, 0.0), rot=(0.0, 0.0, 0.0)):
    """Liefert eine Funktion p -> p' : erst Rotation (Grad, Reihenfolge X, Y, Z), dann Verschiebung."""
    rx, ry, rz = (math.radians(a) for a in rot)
    cx, sx = math.cos(rx), math.sin(rx)
    cy, sy = math.cos(ry), math.sin(ry)
    cz, sz = math.cos(rz), math.sin(rz)

    def apply(p):
        x, y, z = p
        y, z = y * cx - z * sx, y * sx + z * cx
        x, z = x * cy + z * sy, -x * sy + z * cy
        x, y = x * cz - y * sz, x * sz + y * cz
        return (x + pos[0], y + pos[1], z + pos[2])

    return apply


# ---------------------------------------------------------------- Mesh

class Mesh:
    """Sammlung flach schattierter, vertex-gefaerbter Dreiecke."""

    def __init__(self, name):
        self.name = name
        self.tris = []  # (p0, p1, p2, srgb_color)

    def tri(self, a, b, c, color):
        """Dreieck gegen den Uhrzeigersinn von aussen gesehen (glTF-Vorderseite)."""
        self.tris.append((a, b, c, color))

    def quad(self, a, b, c, d, color):
        self.tri(a, b, c, color)
        self.tri(a, c, d, color)

    def frustum(self, y0, y1, bottom, top, color, top_offset=(0.0, 0.0),
                xform=None, side_shade=0.92, cap_shade=1.0, caps=True):
        """Kantiger Quader mit Verjuengung: Grundflaeche bottom=(breite, tiefe) auf y0,
        Deckflaeche top=(breite, tiefe) auf y1, um top_offset=(dx, dz) verschoben.
        Leicht abgedunkelte Seiten betonen den klobigen Look. caps=False laesst
        Deckel und Boden weg (z.B. wenn eine eigene Oberflaeche aufgesetzt wird)."""
        assert y1 > y0, "y1 muss ueber y0 liegen"
        xform = xform or transform()
        bw, bd = bottom[0] / 2, bottom[1] / 2
        tw, td = top[0] / 2, top[1] / 2
        ox, oz = top_offset
        b = [(-bw, y0, -bd), (bw, y0, -bd), (bw, y0, bd), (-bw, y0, bd)]
        t = [(ox - tw, y1, oz - td), (ox + tw, y1, oz - td), (ox + tw, y1, oz + td), (ox - tw, y1, oz + td)]
        b = [xform(p) for p in b]
        t = [xform(p) for p in t]
        side = shade(color, side_shade)
        if caps:
            self.quad(t[0], t[3], t[2], t[1], shade(color, cap_shade))  # oben
            self.quad(b[0], b[1], b[2], b[3], shade(color, 0.7))        # unten
        self.quad(b[3], b[2], t[2], t[3], color)                    # vorne (+Z)
        self.quad(b[1], b[0], t[0], t[1], side)                     # hinten (-Z)
        self.quad(b[2], b[1], t[1], t[2], side)                     # rechts (+X)
        self.quad(b[0], b[3], t[3], t[0], side)                     # links (-X)

    def box(self, center, size, color, xform=None):
        cx, cy, cz = center
        w, h, d = size
        inner = transform(pos=(cx, cy, cz))
        outer = xform or transform()
        self.frustum(-h / 2, h / 2, (w, d), (w, d), color, xform=lambda p: outer(inner(p)))

    def merge(self, other):
        self.tris.extend(other.tris)

    # ------------------------------------------------------------ Kennzahlen

    def bounds(self):
        pts = [p for tri in self.tris for p in tri[:3]]
        lo = tuple(min(p[i] for p in pts) for i in range(3))
        hi = tuple(max(p[i] for p in pts) for i in range(3))
        return lo, hi

    def stats(self):
        lo, hi = self.bounds()
        return {
            "triangles": len(self.tris),
            "vertices": len(self.tris) * 3,
            "size_m": [round(hi[i] - lo[i], 3) for i in range(3)],
        }

    # ------------------------------------------------------------ Export

    def translated(self, offset):
        """Kopie mit verschobenen Vertices (z.B. Welt -> lokale Pivot-Koordinaten)."""
        m = Mesh(self.name)
        ox, oy, oz = offset
        m.tris = [tuple((p[0] - ox, p[1] - oy, p[2] - oz) for p in t[:3]) + (t[3],) for t in self.tris]
        return m

    def to_glb(self, extras=None):
        """Einzelnes Mesh als binaere glTF-2.0-Datei (GLB)."""
        return export_glb(self.name, [{"name": self.name, "parent": None,
                                       "translation": (0.0, 0.0, 0.0), "mesh": self}], extras)

    def write_glb(self, path, extras=None):
        return _write(path, self.to_glb(extras))


class Rig:
    """Mehrteiliges Modell mit benannten Gelenken (Knoten) fuer Animationen.

    Geometrie wird in Weltkoordinaten modelliert. Jeder Teil bekommt einen Pivot
    (Drehpunkt, z.B. Schulter); beim Export wird die Geometrie relativ zum Pivot
    gespeichert und der Knoten relativ zum Eltern-Pivot verschoben. Im Spiel dreht
    man dann einfach den Knoten (arm_R.rotation.x etc.).
    """

    def __init__(self, name):
        self.name = name
        self.parts = {}

    def part(self, name, pivot, parent=None):
        if parent is not None and parent not in self.parts:
            raise ValueError("Eltern-Teil '%s' fehlt" % parent)
        mesh = Mesh(name)
        self.parts[name] = {"mesh": mesh, "pivot": tuple(pivot), "parent": parent}
        return mesh

    def all_tris(self):
        return [t for p in self.parts.values() for t in p["mesh"].tris]

    def stats(self):
        m = Mesh(self.name)
        m.tris = self.all_tris()
        s = m.stats()
        s["joints"] = list(self.parts)
        return s

    def to_glb(self, extras=None):
        nodes = []
        for name, p in self.parts.items():
            parent_pivot = self.parts[p["parent"]]["pivot"] if p["parent"] else (0.0, 0.0, 0.0)
            nodes.append({
                "name": name,
                "parent": p["parent"],
                "translation": tuple(p["pivot"][i] - parent_pivot[i] for i in range(3)),
                "mesh": p["mesh"].translated(p["pivot"]),
            })
        return export_glb(self.name, nodes, extras)

    def write_glb(self, path, extras=None):
        return _write(path, self.to_glb(extras))


def _write(path, data):
    with open(path, "wb") as fh:
        fh.write(data)
    return len(data)


def export_glb(name, parts, extras=None):
    """parts: Liste von {name, parent (Name oder None), translation, mesh}.
    Erzeugt einen Wurzelknoten 'name' mit allen Teilen darunter. Flache Normalen,
    COLOR_0 linear, ein gemeinsames Material."""
    binary = b""
    accessors, views, meshes, nodes = [], [], [], [{"name": name, "children": []}]
    index = {}

    def add_view(data):
        nonlocal binary
        views.append({"buffer": 0, "byteOffset": len(binary), "byteLength": len(data), "target": 34962})
        binary += data
        return len(views) - 1

    for part in parts:
        node = {"name": part["name"]}
        if any(abs(v) > 1e-9 for v in part["translation"]):
            node["translation"] = [round(v, 6) for v in part["translation"]]
        mesh = part["mesh"]
        if mesh.tris:
            positions, normals, colors = [], [], []
            for a, b, c, color in mesh.tris:
                n = _normalize(_cross(_sub(b, a), _sub(c, a)))
                lin = tuple(srgb_to_linear(ch) for ch in color)
                for p in (a, b, c):
                    positions.extend(p)
                    normals.extend(n)
                    colors.extend(lin)
            count = len(mesh.tris) * 3
            pos_bytes = struct.pack("<%df" % len(positions), *positions)
            # min/max exakt aus den float32-Werten, sonst meldet der glTF-Validator Abweichungen
            f32 = struct.unpack("<%df" % len(positions), pos_bytes)
            base = len(accessors)
            accessors.append({"bufferView": add_view(pos_bytes), "componentType": 5126, "count": count,
                              "type": "VEC3", "min": [min(f32[i::3]) for i in range(3)],
                              "max": [max(f32[i::3]) for i in range(3)]})
            accessors.append({"bufferView": add_view(struct.pack("<%df" % len(normals), *normals)),
                              "componentType": 5126, "count": count, "type": "VEC3"})
            accessors.append({"bufferView": add_view(struct.pack("<%df" % len(colors), *colors)),
                              "componentType": 5126, "count": count, "type": "VEC3"})
            meshes.append({"name": part["name"], "primitives": [{
                "attributes": {"POSITION": base, "NORMAL": base + 1, "COLOR_0": base + 2},
                "material": 0, "mode": 4}]})
            node["mesh"] = len(meshes) - 1
        nodes.append(node)
        index[part["name"]] = len(nodes) - 1

    for part in parts:
        parent = index[part["parent"]] if part["parent"] else 0
        nodes[parent].setdefault("children", []).append(index[part["name"]])

    if not meshes:
        raise ValueError("Modell '%s' ist leer" % name)
    gltf = {
        "asset": {"version": "2.0", "generator": "Chaos Arena tools/lowpoly.py"},
        "scene": 0,
        "scenes": [{"name": name, "nodes": [0]}],
        "nodes": nodes,
        "meshes": meshes,
        "materials": [{
            "name": "vertexfarbe_flach",
            "pbrMetallicRoughness": {"baseColorFactor": [1, 1, 1, 1], "metallicFactor": 0.0, "roughnessFactor": 1.0},
        }],
        "accessors": accessors,
        "bufferViews": views,
        "buffers": [{"byteLength": len(binary)}],
    }
    if extras:
        gltf["extras"] = extras
    return pack_glb(gltf, binary)


# ---------------------------------------------------------------- GLB-Container

GLB_MAGIC = 0x46546C67
CHUNK_JSON = 0x4E4F534A
CHUNK_BIN = 0x004E4942


def pack_glb(gltf, binary):
    json_bytes = json.dumps(gltf, separators=(",", ":")).encode("utf-8")
    json_bytes += b" " * (-len(json_bytes) % 4)
    binary += b"\x00" * (-len(binary) % 4)
    total = 12 + 8 + len(json_bytes) + 8 + len(binary)
    out = struct.pack("<III", GLB_MAGIC, 2, total)
    out += struct.pack("<II", len(json_bytes), CHUNK_JSON) + json_bytes
    out += struct.pack("<II", len(binary), CHUNK_BIN) + binary
    return out


def unpack_glb(data):
    """GLB-bytes -> (gltf_dict, bin_bytes). Prueft Header und Chunk-Typen."""
    magic, version, total = struct.unpack_from("<III", data, 0)
    if magic != GLB_MAGIC or version != 2:
        raise ValueError("keine GLB-2.0-Datei")
    if total != len(data):
        raise ValueError("GLB-Laenge stimmt nicht (%d != %d)" % (total, len(data)))
    json_len, json_type = struct.unpack_from("<II", data, 12)
    if json_type != CHUNK_JSON:
        raise ValueError("erster Chunk ist nicht JSON")
    gltf = json.loads(data[20:20 + json_len].decode("utf-8"))
    binary = b""
    offset = 20 + json_len
    if offset < len(data):
        bin_len, bin_type = struct.unpack_from("<II", data, offset)
        if bin_type != CHUNK_BIN:
            raise ValueError("zweiter Chunk ist nicht BIN")
        binary = data[offset + 8:offset + 8 + bin_len]
    return gltf, binary


def seeded(seed):
    """Deterministischer Zufall, damit Modelle bei jedem Build identisch sind."""
    return random.Random(seed)
