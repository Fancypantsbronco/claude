# FitTrack – Fitness-App fürs Pixel

Native Android-App (Kotlin + Jetpack Compose, Material You) für Gym, Laufen, Körpergewicht und Bonus-Aktivitäten.
Alle Daten bleiben auf dem Handy und lassen sich jederzeit als Text oder JSON für eine KI exportieren.

## Installation auf dem Pixel

1. Auf dem Handy die Seite **Releases** dieses Repos öffnen und beim neuesten Eintrag „FitTrack 1.0.x“ die Datei `FitTrack.apk` laden.
   Direktlink zur neuesten Version: `https://github.com/fancypantsbronco/claude/releases/latest/download/FitTrack.apk`
2. Datei öffnen → Installation aus dieser Quelle (Chrome/Dateien) erlauben → installieren.
3. Beim ersten Start Benachrichtigungen erlauben (Pausen-Timer, Erinnerungen); beim ersten Lauf den Standort.

Jede Änderung am Code baut über GitHub Actions automatisch eine neue APK (`.github/workflows/fittrack-android.yml`).
Sie ist mit demselben Schlüssel signiert (`app/fittrack.jks`), installiert sich also als Update über die alte Version. Die Daten bleiben dabei erhalten.

## Funktionen

**1) Gym (ähnlich Hevy)**
- Übungsdatenbank: Übung einmal anlegen (Name, Muskelgruppe, Art, Standard-Pause, Standard-Sätze/Wdh/Gewicht, Notiz). 15 Standard-Übungen per Knopfdruck.
- Art je Übung: **Gewicht**, **Körpergewicht** oder **Körpergewicht + Zusatzgewicht**.
- Training: Datum, Start- und Endzeit (änderbar), Titel, Notizen. Übungen aus der Datenbank einfügen oder direkt neu anlegen.
- Pro Übung beliebig viele Sätze mit Gewicht und Wiederholungen. Sätze lassen sich hinzufügen, löschen, ändern und abhaken.
- Spalte **„Vorher“** pro Satz und „Letztes Mal: …“ je Übung. Neue Trainings übernehmen die Sätze vom letzten Mal als Vorschlag.
- **Pausen-Timer** pro Übung: Standard aus der Datenbank, im Training änderbar (optional als neuer Standard). Er startet beim Abhaken eines Satzes und meldet sich per Vibration/Benachrichtigung, auch bei gesperrtem Bildschirm.
- **e1RM** (geschätztes 1-Wiederholungs-Maximum, Epley oder Brzycki) pro Satz und Übung, mit Vergleich zum letzten Mal (▲/▼). Bei Körpergewichtsübungen zählt das zuletzt eingetragene Körpergewicht (+ Zusatz).
- **Historie** je Übung: e1RM-Diagramm, Bestwert, alle Einheiten mit Sätzen und Notizen.
- Training „als neues Training wiederholen“.

**2) Laufen (ähnlich Runtastic)**
- GPS-Aufzeichnung als Vordergrund-Dienst (läuft auch bei ausgeschaltetem Bildschirm), mit Pause und Fortsetzen.
- Live: Dauer, Distanz, Ø-Pace, aktuelle Pace, GPS-Genauigkeit, Karte (OpenStreetMap).
- Danach: Strecke auf der Karte, **Pace pro Kilometer** als Balken, Ø km/h, Höhenmeter, Gefühl (Emoji), Notizen.
- Optional eine Sprachansage nach jedem Kilometer. Laufband-Läufe lassen sich manuell eintragen.

**3) Körpergewicht**: Datum, Uhrzeit, kg und Notiz, dazu Verlauf und Diagramm. Wöchentliche Erinnerung (Tag und Uhrzeit einstellbar).

**4) Bonus-Aktivitäten**: Liegestütze, Schwimmen, Radfahren, Yoga … mit Menge und Einheit. Dazu ein Wochenziel, ein Tagesvorschlag und eine abendliche Ermutigung, wenn das Ziel noch offen ist.

**5) Export** (Startseite → Teilen-Symbol):
- **Text** und **JSON** für KIs, mit Klarnamen, allen Sätzen, e1RM-Verlauf pro Übung, Läufen mit Km-Splits, Körpergewicht und Bonus-Aktivitäten.
- Kopieren (direkt in den KI-Chat einfügen), Teilen oder als Datei speichern. Zeitraum wählbar.
- **Backup** (inkl. GPS-Strecken) speichern und wieder importieren.

## Entwicklung

```
fitness-app/
  app/src/main/java/app/fittrack/
    data/      Datenmodell (kotlinx.serialization) + Repository (JSON-Datei im App-Speicher)
    domain/    reine Logik: 1RM, Zeiten/Pace, Km-Splits, Export – ohne Android, unit-getestet
    run/       GPS-Tracker + Vordergrund-Dienst
    notify/    Benachrichtigungen, Pausen-Timer (exakter Alarm), Erinnerungen (WorkManager)
    ui/        Compose-Oberflächen
  app/src/test/  Unit-Tests der Logik
```

Bauen: `./gradlew assembleRelease` (braucht ein Android SDK, Ausgabe unter `app/build/outputs/apk/release/`).
