"""build_page.py - baut die Workspace-Website aus Vorlage, state.json und Modellen.

Neu nachgebaut fuer Chaos Arena (Vorbild: WoW-Simulator/tools/build_page.py).

    python tools/build_page.py

Schritte:
  1. assets/models/*.glb -> site/models/*.gltf.json (ueber glb2web.py)
  2. data/state.json einlesen, Modell-Kennzahlen ergaenzen, in die Seite einbetten
  3. web/workspace.html: Marker /* @inline pfad */ durch Dateiinhalt ersetzen
  4. site/index.html   -> Artifact-Seite (ohne <html>/<head>, die setzt claude.ai)
     site/project_export.txt -> kompletter Code fuer den Download-Knopf (export_project.py)
     site/preview.html -> dieselbe Seite mit Doctype fuer lokale Vorschau
        (python -m http.server -d site, dann http://localhost:8000/preview.html)
"""

import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))

import glb2web  # noqa: E402

sys.path.insert(0, ROOT)
import export_project  # noqa: E402

TEMPLATE = os.path.join(ROOT, "web", "workspace.html")
STATE = os.path.join(ROOT, "data", "state.json")
MODELS_SRC = os.path.join(ROOT, "assets", "models")
SITE = os.path.join(ROOT, "site")

INLINE_RE = re.compile(r"/\*\s*@inline\s+(\S+)\s*\*/")
STATE_MARKER = "/* @state */"


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def inline_file(match):
    rel = match.group(1)
    path = os.path.normpath(os.path.join(ROOT, rel))
    if not path.startswith(ROOT + os.sep):
        raise ValueError("Inline-Pfad ausserhalb des Projekts: %s" % rel)
    code = read(path)
    if "</script" in code.lower():
        raise ValueError("%s enthaelt '</script' und kann nicht eingebettet werden" % rel)
    return "/* ---- %s ---- */\n%s" % (rel, code)


def build():
    state = json.loads(read(STATE))

    # 1. Modelle konvertieren und Kennzahlen sammeln
    models_out = os.path.join(SITE, "models")
    asset_stats = {}
    for src, dst, gltf in glb2web.convert_all(MODELS_SRC, models_out):
        web_path = "models/" + os.path.basename(dst)
        info = glb2web.stats(gltf)
        info["glb_bytes"] = os.path.getsize(src)
        info["web_bytes"] = os.path.getsize(dst)
        extra_stats = gltf.get("extras", {}).get("stats", {})
        info["size_m"] = extra_stats.get("size_m")
        info["joints"] = extra_stats.get("joints")
        asset_stats[web_path] = info

    missing = [a["web"] for a in state["assets"] if a["web"] not in asset_stats]
    if missing:
        raise SystemExit("Modelle fehlen (erst models/build_models.py ausfuehren): %s" % ", ".join(missing))
    state["asset_stats"] = asset_stats

    # 2./3. Vorlage fuellen
    page = INLINE_RE.sub(inline_file, read(TEMPLATE))
    if page.count(STATE_MARKER) != 1:
        raise SystemExit("Vorlage braucht genau einen %s-Marker" % STATE_MARKER)
    state_json = json.dumps(state, ensure_ascii=False, indent=1).replace("</", "<\\/")
    page = page.replace(STATE_MARKER, state_json)

    # 4. Ausgabe
    os.makedirs(SITE, exist_ok=True)
    with open(os.path.join(SITE, "index.html"), "w", encoding="utf-8") as fh:
        fh.write(page)
    preview = ("<!doctype html>\n<html lang=\"de\">\n<head>\n<meta charset=\"utf-8\">\n"
               "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">\n"
               "</head>\n<body>\n" + page + "\n</body>\n</html>\n")
    with open(os.path.join(SITE, "preview.html"), "w", encoding="utf-8") as fh:
        fh.write(preview)

    parts = export_project.export(ROOT, os.path.join(SITE, "project_export.txt"))
    print("site/project_export.txt  %d Dateien" % len(parts))
    print("site/index.html  %d Bytes" % len(page.encode("utf-8")))
    for web_path, info in sorted(asset_stats.items()):
        print("site/%-28s %4d Dreiecke  %6d Bytes" % (web_path, info["triangles"], info["web_bytes"]))
    return page


if __name__ == "__main__":
    build()
