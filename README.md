# MageLite

Commander-Decks gegen 3 Bots goldfishen – als schlanke Desktop-App.
Regeln, Karten (≈43.000) und KI kommen unverändert aus der [XMage](https://github.com/magefree/mage)-Engine (MIT);
Server, Client, Lobby, Draft und Turniere sind weggelassen. Neue Oberfläche in React, Kartenbilder von Scryfall.

## Schnellstart auf einem neuen Windows-PC

Die nötigen XMage-Dateien liegen im Repo (`vendor\xmage\`). Eine eigene XMage-Installation brauchst du nicht.

**1. Programme installieren** (einmalig, in PowerShell oder Eingabeaufforderung):

```powershell
winget install Git.Git
winget install EclipseAdoptium.Temurin.21.JDK
winget install OpenJS.NodeJS.LTS
```

Danach das Terminal **neu öffnen**, damit `git`, `java` und `npm` gefunden werden.
Ohne winget: [Git](https://git-scm.com/download/win), [Java 21 (Temurin)](https://adoptium.net/) und
[Node.js LTS](https://nodejs.org/) einfach per Installer installieren.

**2. Klonen und bauen** (einmalig, dauert ein paar Minuten, braucht Internet):

```powershell
git clone https://github.com/melknoo/xmage_clone.git
cd xmage_clone
powershell -ExecutionPolicy Bypass -File scripts\build.ps1
```

**3. Starten:** `MageLite.cmd` im Ordner `xmage_clone` doppelklicken.
Beim allerersten Start baut MageLite die Kartendatenbank auf (1–2 Minuten). Danach startet es in wenigen Sekunden.

**Aktualisieren:** im Ordner `git pull` ausführen, danach erneut `scripts\build.ps1`.

**Probleme?**

- *„java“ bzw. „npm“ nicht gefunden:* Terminal nach der Installation neu öffnen oder den PC neu starten.
- *Engine startet nicht:* Log unter `%APPDATA%\MageLite\desktop.log` und `%APPDATA%\MageLite\engine\logs\engine.log`.
- *Skripte gesperrt:* `scripts\build.ps1` immer mit `powershell -ExecutionPolicy Bypass -File …` aufrufen, wie oben.

Benutzerdaten liegen unter `%APPDATA%\MageLite\engine\`:
`magelite.db` (Decks, Statistik, Held), `db\` (Karten-DB), `cache\images\` (Scryfall-Bilder), `logs\`.

### Andere XMage-Version verwenden (optional)

`powershell -ExecutionPolicy Bypass -File scripts\import-xmage.ps1 -XmageDir <pfad zu ...\xmage>`
kopiert die nötigen Jars, die Karten-DB, die Commander-Sample-Decks und die Sounds aus einer XMage-Distribution
nach `vendor\xmage\`. Danach `scripts\build.ps1` ausführen.

## Funktionen

- **Decks importieren:** Textliste einfügen (Moxfield, Archidekt, MTGA, MTGO, XMage `.dck`) oder Archidekt-/Moxfield-Link.
  Commander wird erkannt oder per Klick gewählt; Legalitätsprüfung über XMage.
- **Spielen:** dein Deck gegen 3 Bots (eigene oder 70 mitgelieferte Commander-Precons, auch zufällig), 40 Leben, London-Mulligan.
- **Bot-Tempo:** Blitz / Normal / Bedacht / Max – auch während des Spiels umschaltbar.
- **Auto-Mana:** Kosten werden automatisch mit passenden Quellen bezahlt (abschaltbar; Fallback auf manuelles Klicken).
- **Auto-Passen:** keine Unterbrechung, wenn du nichts tun kannst.
- **Held & Meisterschaft:** XP pro Spiel (Platzierung, überlebte Züge, erster Sieg des Tages, Siegesserie, Tempo-Faktor),
  Level mit Titeln, Meisterschaftsstufe (1–10) pro Deck.
- **Statistik:** Siegquote, Ø Platz/Züge/Dauer, Mulligans, Tempo, häufigste Gegner, Spielverlauf und
  Kartenstatistik pro Deck (Starthand, gezogen, gespielt, Ø erster Zug, Siege wenn gespielt).

### Tasten

| Taste | Aktion |
|---|---|
| Leertaste / Enter / F2 | Hauptknopf (Weiter, Bestätigen, Fertig) |
| Esc | Abbrechen / Nein |
| F3 | Alle Auto-Pässe abbrechen |
| F4 | bis zum nächsten Zug passen |
| F5 | bis zum Ende dieses Zuges |
| F6 | nächster Zug (Stapel überspringen) |
| F7 | bis zur nächsten Hauptphase |
| F9 | bis zu deinem nächsten Zug |
| F10 | bis der Stapel aufgelöst ist |
| F11 | bis zum Endsegment vor deinem Zug |
| Strg+Umschalt+I | Entwicklerwerkzeuge |

Karten anklicken = spielen / als Ziel wählen / Angreifer bzw. Blocker umschalten.
Leuchtend türkis = spielbar, pulsierend gold = wählbares Ziel, grün = gewählt, rot = greift an, blau = blockt.

## Entwicklung

```powershell
cd engine; .\gradlew.bat run          # Engine im Dev-Modus auf Port 7317 (ohne Token), Arbeitsordner engine\run
cd ui;     npm run dev                # UI auf http://localhost:5173/?port=7317
cd desktop; $env:MAGELITE_UI_DEV=1; npx electron .   # Desktop-Fenster mit Vite-UI
```

- Tests: `cd engine; .\gradlew.bat test` (Decklisten-Parser gegen die echte Karten-DB)
- Headless-Spikes: `.\gradlew.bat spike -PspikeArgs="--games=3 --tempo=BLITZ"` (4 Bots),
  `.\gradlew.bat humanSpike -PspikeArgs="--games=2 --verbose"` (Test-Mensch über die Prompt-API)
- Hinweis: VS Code setzt `ELECTRON_RUN_AS_NODE` – vor Electron-Aufrufen entfernen (macht `MageLite.cmd` automatisch).

### Aufbau

```
desktop/  Electron: startet die Engine (java), lädt die UI, Moxfield-Abruf über Chromium
engine/   Java 17: bettet XMage ein (GameHost ersetzt den XMage-Server), REST + WebSocket (Javalin), SQLite
ui/       React 19 + TypeScript + Tailwind 4 + Zustand
vendor/   importierte XMage-Jars/DB/Decks (nicht versioniert)
docs/     architecture.md – Protokoll, Threading, verifizierte XMage-Details
```

Wichtig: Die XMage-Jars werden unverändert eingebunden (nie neu packen – die Karten-DB prüft die Build-Zeit im Manifest).
Referenz-Quellcode: Git-Tag `xmage_1.4.60V3`.

## Grenzen

- Die XMage-KI ist bei großen Boards (40+ Permanents) langsam – „Blitz“ begrenzt die Denkzeit auf 2 s.
  In „Blitz“/„Normal“ reagieren Bots in fremden Zügen nur auf Zauber auf dem Stapel.
- Moxfield blockt automatische Abrufe teilweise – dann in Moxfield „Export → Text“ kopieren und einfügen.
- Kein Undo/Rollback (bewusst).

## Lizenzen

XMage: MIT (`LICENSES/XMage-MIT.txt`). Kartenbilder: Scryfall (nicht verändert, nur lokal zwischengespeichert).
Mana-Symbole: [Mana](https://github.com/andrewgioia/mana) (SIL OFL / MIT).
