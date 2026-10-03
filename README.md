# Chaos Arena

Hybrider Auto-Battler und Gladiatoren-Manager nach dem Tabula-Rasa-Prinzip.
Aktueller Stand: **Schritt 4**, ein Chaos-Kampf 5 gegen 5 (60 s) mit Token-Ökosystem, wachsenden Attributen und der Evolutions-Phase (Prägungen, 3-Karten-Auswahl, 9 Basis-Karten).

## Ablauf

```bash
python models/build_models.py   # .glb-Modelle nach assets/models/
python tools/build_page.py      # Website nach site/ (inkl. glb2web-Konvertierung)
python export_project.py        # Code-Stand für den Architekten -> ~/Downloads/project_export.txt
node tools/balance.js 100 20    # Balance-Check: 100 Saisons à 20 Kämpfe inkl. Evolution
node tools/check_cards.js       # Prüft, dass jede Karte im Kampf wirkt
```

Lokale Vorschau: `python -m http.server -d site`, dann http://localhost:8000/preview.html öffnen.

## Struktur

| Pfad | Inhalt |
|---|---|
| `data/state.json` | Alle Inhalte: Navigation, Texte, Teams, Kämpfer-Roster mit Aussehen, Kampfregeln, Token, Roadmap, Logbuch |
| `tools/lowpoly.py` | Low-Poly-Bausteine, Vertex-Farben, Flat Shading, GLB-Export, Rigs mit Gelenken |
| `tools/glb2web.py` | `.glb` → `.gltf.json` mit eingebettetem Buffer |
| `tools/build_page.py` | Baut `site/index.html` aus `web/workspace.html` und `state.json` |
| `models/build_models.py` | Basis-Mensch, 10 Kämpfer (Kopf-Varianten aus dem Roster) und Arena-Bodenkachel |
| `web/js/combat.js` | Kampf-Engine: 60 s, feste Zeitschritte, Seed, blinde Zielwahl, Token, Attribute, Wachstum |
| `web/js/evolution.js` | Evolutions-Phase: Token → Prägungen, 2 Kämpfer pro Team, 3 bezahlbare Karten |
| `web/js/arena3d.js` | three.js: Arena, prozedurale Animationen, Porträts, Vorschauen |
| `web/js/workspace.js` | Editor-Oberfläche: Steuerung, Szene, Inspector, Konsole, Auswertung |
| `tools/check_cards.js` | Misst für jede Karte, ob ihr Effekt im Kampfprotokoll auftaucht |
| `tools/balance.js` | Simuliert ganze Saisons ohne Grafik: K.O.-Quote, Prägung, Gesinnung, Attribute |
| `web/workspace.html` | Seitenvorlage mit Layout und CSS |
| `export_project.py` | Architekten-Brücke: alle .py/.json/.js/.html in eine Textdatei (lokal nach ~/Downloads, auf der Website per Knopf in der Statusleiste) |

Jede Änderung bekommt einen Eintrag im Logbuch (`data/state.json` → `logbook`).
