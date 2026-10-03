"""build_models.py - erzeugt die statischen Start-Modelle als .glb.

    python models/build_models.py

Ausgabe in assets/models/:
    human_base.glb    Basis-Mensch, neutraler Lendenschurz
    human_team_a.glb  Basis-Mensch, Lendenschurz in Team-A-Farbe
    human_team_b.glb  Basis-Mensch, Lendenschurz in Team-B-Farbe
    arena_tile.glb    sandfarbene Bodenkachel

Teamfarben kommen aus data/state.json, damit Modelle und Website dieselben Werte nutzen.
"""

import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))

from lowpoly import Mesh, hex_rgb, seeded, shade, transform  # noqa: E402

OUT_DIR = os.path.join(ROOT, "assets", "models")

SKIN = hex_rgb("#c99a74")
HAIR = hex_rgb("#3b2a20")
EYES = hex_rgb("#1d1714")
BELT = hex_rgb("#4a3526")
CLOTH_NEUTRAL = hex_rgb("#7d7466")
SAND = hex_rgb("#d6bc88")


def build_human(name, cloth):
    """Unbewaffneter Mensch, plump und unproportioniert: kurze Beine, breiter Rumpf,
    kleiner Kopf, Gorilla-Arme bis fast an die Knie. Statische Pose, Blick nach +Z."""
    m = Mesh(name)
    skin_dark = shade(SKIN, 0.86)

    # Fuesse und Beine (kurz und stummelig)
    for side in (-1, 1):
        m.box((side * 0.16, 0.055, 0.05), (0.20, 0.11, 0.32), skin_dark)
        m.frustum(0.10, 0.74, (0.17, 0.19), (0.23, 0.25), SKIN,
                  xform=transform(pos=(side * 0.15, 0, 0)))

    # Lendenschurz mit Guertel und Lappen vorne/hinten (Teamfarbe)
    m.frustum(0.70, 1.00, (0.56, 0.34), (0.62, 0.38), cloth)
    m.frustum(0.95, 1.03, (0.64, 0.40), (0.64, 0.40), BELT)
    m.box((0, 0.60, 0.17), (0.24, 0.24, 0.04), shade(cloth, 0.85))
    m.box((0, 0.60, -0.17), (0.24, 0.24, 0.04), shade(cloth, 0.85))

    # Rumpf: unten schmal, Schultern breit, leicht nach vorne gebeugt
    m.frustum(0.98, 1.58, (0.60, 0.36), (0.88, 0.44), SKIN, top_offset=(0, 0.05))
    m.box((0, 1.60, 0.06), (0.20, 0.10, 0.20), skin_dark)

    # Kleiner Kopf, nach vorne geschoben
    head = transform(pos=(0, 0, 0.05))
    m.frustum(1.62, 1.94, (0.30, 0.32), (0.27, 0.29), SKIN, top_offset=(0, 0.04), xform=head)
    m.frustum(1.92, 1.99, (0.29, 0.31), (0.24, 0.26), HAIR, xform=transform(pos=(0, 0, 0.09)))
    m.box((0, 1.85, 0.225), (0.27, 0.05, 0.06), skin_dark)          # Wulst-Augenbraue
    m.box((0, 1.76, 0.245), (0.06, 0.08, 0.07), skin_dark)          # Nase
    for side in (-1, 1):
        m.box((side * 0.07, 1.80, 0.228), (0.06, 0.04, 0.03), EYES)

    # Lange Arme, leicht nach aussen und vorne haengend, mit klobigen Faeusten
    for side in (-1, 1):
        shoulder = transform(pos=(side * 0.47, 1.54, 0.05), rot=(-8, 0, side * 8))
        m.frustum(-0.78, 0.0, (0.15, 0.16), (0.19, 0.20), SKIN, xform=shoulder)
        m.box((0, -0.86, 0), (0.19, 0.19, 0.19), skin_dark, xform=shoulder)
    return m


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


def main():
    with open(os.path.join(ROOT, "data", "state.json"), encoding="utf-8") as fh:
        state = json.load(fh)
    teams = state["teams"]
    os.makedirs(OUT_DIR, exist_ok=True)

    jobs = [
        (build_human("human_base", CLOTH_NEUTRAL), "human_base.glb"),
        (build_human("human_team_a", hex_rgb(teams["A"]["color"])), "human_team_a.glb"),
        (build_human("human_team_b", hex_rgb(teams["B"]["color"])), "human_team_b.glb"),
        (build_arena_tile(), "arena_tile.glb"),
    ]
    for mesh, filename in jobs:
        stats = mesh.stats()
        size = mesh.write_glb(os.path.join(OUT_DIR, filename), extras={"stats": stats})
        print("%-18s %4d Dreiecke  %6d Bytes  Groesse %s m" % (
            filename, stats["triangles"], size, stats["size_m"]))


if __name__ == "__main__":
    main()
