# Chaos Arena

Hybrider Auto-Battler und Gladiatoren-Manager nach dem Tabula-Rasa-Prinzip.
Aktueller Stand: **Schritt 1**, also Workspace, Start-Modelle und Token-Prototyp.

## Ablauf

```bash
python models/build_models.py   # .glb-Modelle nach assets/models/
python tools/build_page.py      # Website nach site/ (inkl. glb2web-Konvertierung)
python export_project.py        # Code-Stand für den Architekten -> project_export.txt
```

Lokale Vorschau: `python -m http.server -d site`, dann http://localhost:8000/preview.html öffnen.

## Struktur

| Pfad | Inhalt |
|---|---|
| `data/state.json` | Alle Inhalte der Website: Navigation, Texte, Teams, Token, Roadmap, Logbuch |
| `tools/lowpoly.py` | Low-Poly-Bausteine, Vertex-Farben, Flat Shading, GLB-Export |
| `tools/glb2web.py` | `.glb` → `.gltf.json` mit eingebettetem Buffer |
| `tools/build_page.py` | Baut `site/index.html` aus `web/workspace.html` und `state.json` |
| `models/build_models.py` | Basis-Mensch (neutral, Team A, Team B) und Arena-Bodenkachel |
| `web/` | Seitenvorlage und JavaScript (Dashboard, Token-Logik, three.js-Szene) |
| `export_project.py` | Architekten-Brücke: alle .py/.json/.js/.html in eine Textdatei |

Jede Änderung bekommt einen Eintrag im Logbuch (`data/state.json` → `logbook`).
