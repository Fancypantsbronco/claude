"""glb2web.py - wandelt .glb in .gltf.json mit eingebettetem Buffer (data-URI) um.

Neu nachgebaut fuer Chaos Arena (Vorbild: WoW-Simulator/tools/glb2web.py).
.glb darf nicht als Artifact-Datei veroeffentlicht werden; .gltf.json ist reines JSON
und kann neben der Seite ausgeliefert werden.

    python tools/glb2web.py assets/models/human_base.glb site/models
    python tools/glb2web.py assets/models site/models       # ganzer Ordner
"""

import base64
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from lowpoly import unpack_glb  # noqa: E402


def glb_to_gltf_json(glb_bytes):
    """GLB-bytes -> glTF-Dict, dessen einziger Buffer als data-URI eingebettet ist."""
    gltf, binary = unpack_glb(glb_bytes)
    buffers = gltf.get("buffers", [])
    if len(buffers) != 1 or "uri" in buffers[0]:
        raise ValueError("erwartet genau einen eingebetteten GLB-Buffer")
    if buffers[0]["byteLength"] > len(binary):
        raise ValueError("BIN-Chunk kuerzer als buffers[0].byteLength")
    payload = binary[:buffers[0]["byteLength"]]
    buffers[0]["uri"] = "data:application/octet-stream;base64," + base64.b64encode(payload).decode("ascii")
    return gltf


def stats(gltf):
    """Dreiecke und Vertices aus den POSITION-Accessoren (nicht-indizierte Dreiecke)."""
    vertices = 0
    triangles = 0
    for mesh in gltf.get("meshes", []):
        for prim in mesh["primitives"]:
            count = gltf["accessors"][prim["attributes"]["POSITION"]]["count"]
            vertices += count
            if "indices" in prim:
                triangles += gltf["accessors"][prim["indices"]]["count"] // 3
            else:
                triangles += count // 3
    return {"triangles": triangles, "vertices": vertices}


def convert(src, out_dir):
    with open(src, "rb") as fh:
        gltf = glb_to_gltf_json(fh.read())
    os.makedirs(out_dir, exist_ok=True)
    name = os.path.splitext(os.path.basename(src))[0] + ".gltf.json"
    dst = os.path.join(out_dir, name)
    with open(dst, "w", encoding="utf-8") as fh:
        json.dump(gltf, fh, separators=(",", ":"))
    return dst, gltf


def convert_all(src, out_dir):
    if os.path.isdir(src):
        files = sorted(os.path.join(src, f) for f in os.listdir(src) if f.lower().endswith(".glb"))
    else:
        files = [src]
    results = []
    for path in files:
        dst, gltf = convert(path, out_dir)
        results.append((path, dst, gltf))
    return results


def main(argv):
    if len(argv) not in (2, 3):
        print(__doc__)
        return 1
    out_dir = argv[2] if len(argv) == 3 else os.path.dirname(argv[1]) or "."
    for src, dst, gltf in convert_all(argv[1], out_dir):
        s = stats(gltf)
        print("%s -> %s  (%d Dreiecke, %d Bytes)" % (src, dst, s["triangles"], os.path.getsize(dst)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
