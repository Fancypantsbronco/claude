"""export_project.py - Architekten-Bruecke fuer Chaos Arena.

Sammelt alle .py-, .json-, .js- und .html-Dateien des Projekts in einer einzigen
Textdatei, jede Datei zwischen deutlichen Trennzeilen:

    === DATEINAME: web/js/combat.js ===
    ... Inhalt ...
    === ENDE: web/js/combat.js ===

Diese Datei kann direkt an den System-Architekten (eine andere KI) weitergegeben werden.

Zwei Wege:
  1. Website: Knopf "Projekt-Export herunterladen" in der Statusleiste (die Datei
     wird von tools/build_page.py mit dieser Funktion erzeugt).
  2. Lokal im Projektordner ausfuehren:

    python export_project.py                      # -> ~/Downloads/project_export.txt
    python export_project.py -o stand.txt         # eigener Pfad
    python export_project.py --include-generated  # auch Build-Ausgaben (site/, *.gltf.json)

Standardmaessig ausgelassen: .git, __pycache__, venv-Ordner, node_modules sowie
generierte Dateien (site/ und *.gltf.json), weil sie nur Build-Ergebnisse mit
grossen Base64-Bloecken sind und aus dem Quellcode neu entstehen.
"""

import argparse
import datetime
import os
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
EXTENSIONS = (".py", ".json", ".js", ".html")
SKIP_DIRS = {".git", "__pycache__", "node_modules", ".venv", "venv", "env", ".idea", ".vscode"}
GENERATED_DIRS = {"site"}
GENERATED_SUFFIXES = (".gltf.json",)


def collect(root, include_generated=False):
    files = []
    for dirpath, dirnames, filenames in os.walk(root):
        rel_dir = os.path.relpath(dirpath, root)
        skip = set(SKIP_DIRS)
        if not include_generated and rel_dir == ".":
            skip |= GENERATED_DIRS
        dirnames[:] = sorted(d for d in dirnames if d not in skip)
        for name in sorted(filenames):
            if not name.lower().endswith(EXTENSIONS):
                continue
            if not include_generated and name.lower().endswith(GENERATED_SUFFIXES):
                continue
            rel = os.path.relpath(os.path.join(dirpath, name), root).replace(os.sep, "/")
            files.append(rel)
    return sorted(files, key=lambda p: (p.count("/"), p))


def read_text(path):
    with open(path, "rb") as fh:
        raw = fh.read()
    text = raw.decode("utf-8", errors="replace")
    return text.replace("\r\n", "\n"), len(raw)


def export(root, out_path, include_generated=False):
    files = collect(root, include_generated)
    out_rel = os.path.relpath(out_path, root).replace(os.sep, "/")
    files = [f for f in files if f != out_rel]
    stamp = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    parts = []
    total_lines = 0
    for rel in files:
        text, size = read_text(os.path.join(root, rel))
        lines = text.count("\n") + (0 if text.endswith("\n") or not text else 1)
        total_lines += lines
        parts.append((rel, text, size, lines))

    out = []
    out.append("#" * 72)
    out.append("# CHAOS ARENA - PROJEKT-EXPORT")
    out.append("# Erstellt: %s" % stamp)
    out.append("# Dateien:  %d  (%d Zeilen)" % (len(parts), total_lines))
    out.append("# Typen:    %s" % ", ".join(EXTENSIONS))
    if not include_generated:
        out.append("# Ohne generierte Dateien (site/, *.gltf.json). Mit --include-generated einschliessen.")
    out.append("#" * 72)
    out.append("")
    out.append("INHALT")
    for rel, _, size, lines in parts:
        out.append("  %-48s %6d Zeilen %9d Bytes" % (rel, lines, size))
    out.append("")
    for rel, text, _, _ in parts:
        out.append("")
        out.append("=== DATEINAME: %s ===" % rel)
        out.append(text.rstrip("\n"))
        out.append("=== ENDE: %s ===" % rel)
    out.append("")

    with open(out_path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(out))
    return parts


def main():
    parser = argparse.ArgumentParser(description="Exportiert den Code-Stand von Chaos Arena in eine Textdatei.")
    parser.add_argument("-o", "--output", default=None,
                        help="Zieldatei (Standard: ~/Downloads/project_export.txt, sonst im Projektordner)")
    parser.add_argument("--include-generated", action="store_true", help="auch site/ und *.gltf.json exportieren")
    args = parser.parse_args()

    if args.output:
        out_path = os.path.abspath(args.output)
    else:
        downloads = os.path.join(os.path.expanduser("~"), "Downloads")
        out_path = os.path.join(downloads if os.path.isdir(downloads) else ROOT, "project_export.txt")
    parts = export(ROOT, out_path, args.include_generated)
    for rel, _, size, _ in parts:
        print("  + %s (%d Bytes)" % (rel, size))
    print("%d Dateien -> %s (%d Bytes)" % (len(parts), out_path, os.path.getsize(out_path)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
