"""build_models.py - erzeugt die Kaempfer-Rigs und die Bodenkachel als .glb.

    python models/build_models.py

Ausgabe in assets/models/:
    human_base.glb       gemeinsamer Koerper, neutraler Lendenschurz
    fighter_<ID>.glb     ein Modell pro Kaempfer aus data/state.json -> roster
    arena_tile.glb       sandfarbene Bodenkachel (5 x 5 m, die Arena legt 2 x 2 aus)

Alle Kaempfer teilen denselben Koerper (alle Werte 1). Unterschiede nur am Kopf:
Hautton, Frisur, Haarfarbe, Bart, Nase, Augenbrauen, Narbe (roster[].look).

Gelenke (glTF-Knoten unter dem Wurzelknoten = Kaempfer-ID):
    hips -> torso -> head, arm_L, arm_R
    hips -> leg_L, leg_R
Modelle schauen nach +Z, "links" ist +X (aus Sicht der Figur).
"""

import json
import os
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))

from lowpoly import Mesh, Rig, hex_rgb, seeded, shade, transform  # noqa: E402

OUT_DIR = os.path.join(ROOT, "assets", "models")

BELT = hex_rgb("#4a3526")
EYE_WHITE = hex_rgb("#efe6d8")
PUPIL = hex_rgb("#1d1714")
MOUTH = hex_rgb("#5a2c26")
SCAR = hex_rgb("#d48f86")
CLOTH_NEUTRAL = hex_rgb("#7d7466")
SAND = hex_rgb("#d6bc88")

BASE_LOOK = {"skin": "#c99a74", "hair": "glatze", "hair_color": "#3b2a20", "beard": "keiner",
             "nose": "knolle", "brows": "wulst", "scar": False}

HEAD_Z = 0.04      # Kopf sitzt leicht vor dem Rumpf
FACE_Z = 0.195     # Vorderflaeche des Gesichts


def at(x, y, z):
    return transform(pos=(x, y, z))


# ---------------------------------------------------------------- Koerper

def build_body(rig, skin, cloth):
    skin_dark = shade(skin, 0.86)

    hips = rig.part("hips", (0, 0.92, 0))
    hips.frustum(0.74, 1.00, (0.50, 0.32), (0.54, 0.34), cloth)
    hips.frustum(0.96, 1.03, (0.56, 0.36), (0.56, 0.36), BELT)
    hips.box((0, 0.64, 0.165), (0.22, 0.22, 0.04), shade(cloth, 0.85))
    hips.box((0, 0.64, -0.165), (0.22, 0.22, 0.04), shade(cloth, 0.85))

    # Weicher, unsportlicher Rumpf: schmale haengende Schultern, kleiner Bauch
    torso = rig.part("torso", (0, 1.0, 0), parent="hips")
    torso.frustum(1.00, 1.28, (0.54, 0.36), (0.56, 0.38), skin)
    torso.box((0, 1.13, 0.17), (0.40, 0.22, 0.08), skin)
    torso.frustum(1.26, 1.54, (0.56, 0.36), (0.60, 0.32), skin)

    for side, name in ((1, "arm_L"), (-1, "arm_R")):
        pivot = (side * 0.34, 1.49, 0.0)
        arm = rig.part(name, pivot, parent="torso")
        hang = transform(pos=pivot, rot=(0, 0, side * 5))
        arm.frustum(-0.64, 0.02, (0.12, 0.13), (0.14, 0.15), skin, xform=hang)
        arm.box((0, -0.70, 0.01), (0.14, 0.13, 0.15), skin_dark, xform=hang)
        if name == "arm_R":  # Armbinde in Teamfarbe
            arm.frustum(-0.22, -0.13, (0.145, 0.155), (0.15, 0.16), cloth, xform=hang)

    for side, name in ((1, "leg_L"), (-1, "leg_R")):
        pivot = (side * 0.13, 0.78, 0.0)
        leg = rig.part(name, pivot, parent="hips")
        leg.frustum(-0.68, 0.02, (0.15, 0.17), (0.19, 0.21), skin, xform=at(*pivot))
        leg.box((0, -0.73, 0.05), (0.17, 0.10, 0.28), skin_dark, xform=at(*pivot))
    return torso


# ---------------------------------------------------------------- Kopf

def build_head(rig, look):
    skin = hex_rgb(look["skin"])
    hair = hex_rgb(look["hair_color"])
    skin_dark = shade(skin, 0.84)
    head = rig.part("head", (0, 1.56, 0.02), parent="torso")

    head.box((0, 1.60, 0.03), (0.16, 0.10, 0.16), skin_dark)                     # Hals
    head.frustum(1.63, 1.95, (0.30, 0.31), (0.28, 0.29), skin, xform=at(0, 0, HEAD_Z))
    for side in (-1, 1):
        head.box((side * 0.155, 1.78, HEAD_Z), (0.04, 0.08, 0.06), skin_dark)   # Ohren
        head.box((side * 0.07, 1.80, FACE_Z + 0.005), (0.07, 0.045, 0.02), EYE_WHITE)
        head.box((side * 0.07, 1.80, FACE_Z + 0.017), (0.035, 0.035, 0.012), PUPIL)
    head.box((0, 1.69, FACE_Z + 0.005), (0.10, 0.02, 0.02), MOUTH)

    # Nase
    nose = look["nose"]
    if nose == "klein":
        head.box((0, 1.76, FACE_Z + 0.02), (0.05, 0.05, 0.04), skin_dark)
    elif nose == "lang":
        head.frustum(1.71, 1.80, (0.06, 0.09), (0.04, 0.04), skin_dark, xform=at(0, 0, FACE_Z + 0.03))
    else:  # knolle
        head.box((0, 1.755, FACE_Z + 0.03), (0.085, 0.08, 0.07), skin_dark)

    # Augenbrauen
    if look["brows"] == "wulst":
        head.box((0, 1.85, FACE_Z + 0.01), (0.27, 0.05, 0.05), skin_dark)
    else:
        for side in (-1, 1):
            head.box((side * 0.07, 1.855, FACE_Z + 0.008), (0.08, 0.02, 0.02), shade(hair, 0.9))

    if look.get("scar"):
        head.box((0.085, 1.745, FACE_Z + 0.004), (0.016, 0.10, 0.01), SCAR)

    build_beard(head, look["beard"], hair, skin)
    build_hair(head, look["hair"], hair)


def build_beard(head, style, hair, skin):
    if style == "stoppel":
        head.box((0, 1.665, FACE_Z), (0.27, 0.07, 0.02), shade(skin, 0.7))
    elif style == "voll":
        head.box((0, 1.65, FACE_Z + 0.02), (0.29, 0.15, 0.06), hair)
        for side in (-1, 1):
            head.box((side * 0.148, 1.72, HEAD_Z + 0.05), (0.03, 0.16, 0.18), hair)
        head.box((0, 1.69, FACE_Z + 0.052), (0.10, 0.02, 0.01), MOUTH)
    elif style == "schnauzer":
        head.box((0, 1.715, FACE_Z + 0.018), (0.16, 0.035, 0.035), hair)
    elif style == "kinn":
        head.frustum(1.56, 1.67, (0.05, 0.05), (0.10, 0.06), hair, xform=at(0, 0, FACE_Z))


def build_hair(head, style, hair):
    dark = shade(hair, 0.85)
    back_z = HEAD_Z - 0.165
    if style == "stoppeln":
        head.frustum(1.93, 1.975, (0.295, 0.305), (0.27, 0.28), hair, xform=at(0, 0, HEAD_Z))
        head.box((0, 1.84, back_z + 0.01), (0.29, 0.20, 0.03), dark)
    elif style == "topf":
        head.frustum(1.89, 2.01, (0.33, 0.34), (0.28, 0.29), hair, xform=at(0, 0, HEAD_Z))
        head.box((0, 1.895, FACE_Z + 0.01), (0.31, 0.06, 0.04), hair)
        for side in (-1, 1):
            head.box((side * 0.16, 1.83, HEAD_Z), (0.04, 0.14, 0.30), dark)
        head.box((0, 1.80, back_z), (0.32, 0.22, 0.04), dark)
    elif style == "irokese":
        head.frustum(1.94, 2.10, (0.07, 0.30), (0.04, 0.22), hair, xform=at(0, 0, HEAD_Z - 0.01))
    elif style == "lang":
        head.frustum(1.91, 1.995, (0.32, 0.33), (0.27, 0.28), hair, xform=at(0, 0, HEAD_Z))
        head.box((0, 1.66, back_z - 0.01), (0.31, 0.46, 0.05), dark)
        for side in (-1, 1):
            head.box((side * 0.165, 1.73, HEAD_Z - 0.03), (0.035, 0.34, 0.22), hair)
    elif style == "zopf":
        head.frustum(1.91, 1.99, (0.31, 0.32), (0.27, 0.28), hair, xform=at(0, 0, HEAD_Z))
        head.box((0, 1.84, back_z), (0.29, 0.16, 0.04), dark)
        head.box((0, 1.66, back_z - 0.04), (0.07, 0.30, 0.07), hair)
        head.box((0, 1.81, back_z - 0.035), (0.09, 0.05, 0.08), shade(hair, 0.7))
    elif style == "seiten":
        for side in (-1, 1):
            head.box((side * 0.16, 1.82, HEAD_Z - 0.03), (0.04, 0.11, 0.20), hair)
        head.box((0, 1.80, back_z), (0.28, 0.11, 0.035), hair)
    # "glatze": nichts


def build_fighter(fid, look, cloth):
    rig = Rig(fid)
    build_body(rig, hex_rgb(look["skin"]), cloth)
    build_head(rig, look)
    return rig


# ---------------------------------------------------------------- Umgebung

def build_arena_tile(name="arena_tile", size=5.0, cells=10, thickness=0.35, seed=7):
    """Rohe Sandkachel: leicht unebene Oberflaeche, Farbflecken pro Dreieck,
    schraege Sockelkanten. Oberkante liegt auf y=0."""
    m = Mesh(name)
    rng = seeded(seed)
    step = size / cells
    half = size / 2

    heights = {}
    for i in range(cells + 1):
        for j in range(cells + 1):
            edge = i in (0, cells) or j in (0, cells)
            heights[i, j] = 0.0 if edge else rng.uniform(-0.02, 0.02)

    def p(i, j):
        return (-half + i * step, heights[i, j], -half + j * step)

    for i in range(cells):
        for j in range(cells):
            a, b, c, d = p(i, j), p(i, j + 1), p(i + 1, j + 1), p(i + 1, j)
            patch = 0.9 if rng.random() < 0.08 else 1.0
            m.tri(a, b, c, shade(SAND, rng.uniform(0.94, 1.05) * patch))
            m.tri(a, c, d, shade(SAND, rng.uniform(0.94, 1.05) * patch))

    lip = 0.12
    m.frustum(-thickness, 0.0, (size + 2 * lip, size + 2 * lip), (size, size),
              shade(SAND, 0.78), side_shade=0.88, caps=False)
    return m


# ---------------------------------------------------------------- Ausgabe

def main():
    with open(os.path.join(ROOT, "data", "state.json"), encoding="utf-8") as fh:
        state = json.load(fh)
    teams = state["teams"]

    # Alte Ausgaben entfernen, damit keine verwaisten Modelle liegen bleiben
    if os.path.isdir(OUT_DIR):
        shutil.rmtree(OUT_DIR)
    os.makedirs(OUT_DIR)

    jobs = [(build_fighter("human_base", BASE_LOOK, CLOTH_NEUTRAL), "human_base.glb", {"look": BASE_LOOK})]
    for f in state["roster"]:
        rig = build_fighter(f["id"], f["look"], hex_rgb(teams[f["team"]]["color"]))
        jobs.append((rig, "fighter_%s.glb" % f["id"], {"look": f["look"], "name": f["name"]}))
    jobs.append((build_arena_tile(), "arena_tile.glb", {}))

    for model, filename, extras in jobs:
        stats = model.stats()
        extras = dict(extras, stats=stats)
        size = model.write_glb(os.path.join(OUT_DIR, filename), extras=extras)
        print("%-18s %4d Dreiecke  %6d Bytes  Groesse %s m" % (
            filename, stats["triangles"], size, stats["size_m"]))


if __name__ == "__main__":
    main()
