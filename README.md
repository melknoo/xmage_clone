# MageLite

Commander-Decks gegen 3 Bots goldfishen – als schlanke Desktop-App.
Regeln, Karten (≈33.500) und KI kommen aus der [Forge](https://github.com/Card-Forge/forge)-Engine (GPL-3.0), die
ohne ihre eigene Oberfläche eingebettet läuft; Draft und Turniere sind weggelassen. Neue Oberfläche in React,
Kartenbilder von Scryfall. MageLite steht unter **GPL-3.0-or-later** (siehe [Lizenzen](#lizenzen)).

## Schnellstart auf einem neuen Windows-PC

Voraussetzungen: **Git**, **JDK 17 oder neuer** (zum Bauen, nicht zum Spielen) und **Node.js**. Maven brauchst du
nicht, `scripts\import-forge.ps1` lädt es bei Bedarf selbst. Eine Forge-Installation ebenso wenig: das Skript holt und
baut den im Repo gepinnten Forge-Stand (`vendor\forge\FORGE_COMMIT`).

**1. Programme installieren** (einmalig, in PowerShell oder Eingabeaufforderung):

```powershell
winget install Git.Git
winget install EclipseAdoptium.Temurin.21.JDK
winget install OpenJS.NodeJS.LTS
```

Danach das Terminal **neu öffnen**, damit `git`, `java` und `npm` gefunden werden.
Ohne winget: [Git](https://git-scm.com/download/win), [Java 21 (Temurin)](https://adoptium.net/) und
[Node.js LTS](https://nodejs.org/) einfach per Installer installieren.

**2. Klonen und bauen** (einmalig, braucht Internet):

```powershell
git clone https://github.com/melknoo/xmage_clone.git
cd xmage_clone
powershell -ExecutionPolicy Bypass -File scripts\build.ps1
```

Beim ersten Lauf importiert `build.ps1` Forge: holt den gepinnten Commit, baut ihn mit Maven und legt Jars und
Kartendaten nach `vendor\forge\` (**einige Minuten**; später nur noch, wenn sich `FORGE_COMMIT` ändert). Danach
baut es UI, Engine und Desktop-Abhängigkeiten. Eine Karten-Datenbank gibt es nicht: Forge liest die Kartenskripte bei
jedem Start neu (wenige Sekunden).

**3. Starten:** `MageLite.cmd` im Ordner `xmage_clone` doppelklicken.
`MageLite.cmd` startet die **Test-App**: Fenstertitel „MageLite (Test)“, eigene Daten unter `%APPDATA%\MageLite-dev`.
Beim ersten Start wird die Datenbank einer installierten App (`%APPDATA%\MageLite\engine\magelite.db`) nur lesend
kopiert; die installierte App bleibt unberührt.

**Aktualisieren:** im Ordner `git pull` ausführen, danach erneut `scripts\build.ps1`.

**Probleme?**

- *„java“ bzw. „npm“ nicht gefunden:* Terminal nach der Installation neu öffnen oder den PC neu starten.
- *Engine startet nicht:* Log unter `%APPDATA%\MageLite-dev\desktop.log` und
  `%APPDATA%\MageLite-dev\engine\logs\engine.log` (installierte App: `%APPDATA%\MageLite\…`); Forge schreibt
  zusätzlich `logs\forge.log`.
- *Skripte gesperrt:* `scripts\build.ps1` immer mit `powershell -ExecutionPolicy Bypass -File …` aufrufen, wie oben.
- *Forge-Import bricht ab:* Meldung des Skripts lesen (Git, JDK 17+ und Internet nötig); ein erneuter Aufruf von
  `scripts\import-forge.ps1` setzt sauber neu auf.

Benutzerdaten der installierten App liegen unter `%APPDATA%\MageLite\engine\`:
`magelite.db` (Decks, Statistik, Held), `cache\images\` (Scryfall-Bilder), `forge-data\` (Forge-Profil), `logs\`.

### Forge-Stand importieren oder aktualisieren

`powershell -ExecutionPolicy Bypass -File scripts\import-forge.ps1` erzeugt `vendor\forge\` (Jars, Kartenskripte,
Editionen, KI-Profile, `manifest.json`). Das Verzeichnis ist **nicht** im Repo; versioniert sind nur
`vendor\forge\FORGE_COMMIT` (der gepinnte Forge-Commit) und `vendor\forge-overrides\` (eigene Karten-Fixes, werden beim
Import ins Kartenskript-Archiv eingebacken). Optionen: `-Commit <sha>` (anderer Stand; nach Erfolg wird
`FORGE_COMMIT` auf ihn gesetzt), `-FullRes` (komplettes `res\`), `-KeepScratch` (Arbeitskopie unter
`%LOCALAPPDATA%\MageLite-build` behalten). Danach `scripts\build.ps1` ausführen.

## Funktionen

- **Decks importieren:** Textliste einfügen (Moxfield, Archidekt, MTGA, MTGO, Forge/XMage `.dck`) oder Archidekt-/Moxfield-Link.
  Commander wird erkannt oder per Klick gewählt; Legalitätsprüfung über Forge (inkl. Bannliste).
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

Zum Weiterentwickeln nach einem Klon:

| Datei | Inhalt |
|---|---|
| [`CLAUDE.md`](CLAUDE.md) | Kurzkontext, feste Entscheidungen, harte Regeln (auch für Claude Code) |
| [`docs/STATUS.md`](docs/STATUS.md) | Stand, Messwerte, offene Punkte, bekannte Probleme |
| [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) | Einrichten, Dev-Schleife, Tests, Debugging, typische Erweiterungen |
| [`docs/CODEMAP.md`](docs/CODEMAP.md) | Wo welcher Code liegt, Protokoll, REST, DB-Schema |
| [`docs/DECISIONS.md`](docs/DECISIONS.md) | Entscheidungen und Begründungen |
| [`docs/SERVER.md`](docs/SERVER.md) | Betrieb auf fly.io |
| [`docs/archive/xmage-architecture.md`](docs/archive/xmage-architecture.md) | historisch: Analyse der XMage-Fassung bis 0.1.x |

Kurzfassung:

```powershell
cd engine; .\gradlew.bat run          # Engine im Dev-Modus auf Port 7317 (ohne Token), Arbeitsordner engine\run
cd ui;     npm run dev                # UI auf http://localhost:5173/?port=7317
cd desktop; $env:MAGELITE_UI_DEV=1; npx electron .   # Desktop-Fenster mit Vite-UI
```

- Tests: `cd engine; .\gradlew.bat test` (Deck-Parser, Sample-Decks, Forge-Boot gegen die echten Forge-Daten;
  bootet Forge einmal pro Testlauf)
- Forge-Boot prüfen: `.\gradlew.bat forgeCheck` (Karten, Editionen, Boot-Zeit, Heap)
- Headless-Spikes: `.\gradlew.bat spike -PspikeArgs="--games=3 --tempo=BLITZ"` (4 Bots),
  `.\gradlew.bat humanSpike -PspikeArgs="--games=2 --verbose"` (Test-Mensch über die Prompt-API),
  `.\gradlew.bat botArena -PspikeArgs="--games=30 --turnCap=80"` (KI-Profile gegeneinander)
- Hinweis: VS Code setzt `ELECTRON_RUN_AS_NODE` – vor Electron-Aufrufen entfernen (macht `MageLite.cmd` automatisch).

### Aufbau

```
desktop/   Electron: startet die Engine (java), lädt die UI, Moxfield-Abruf über Chromium
engine/    Java 17: bettet Forge ein (GameHost + SeatGui/PromptBridge ersetzen Forges Oberfläche), REST + WebSocket (Javalin), SQLite
ui/        React 19 + TypeScript + Tailwind 4 + Zustand
scripts/   build.ps1, import-forge.ps1, package.ps1, release.ps1, deploy-fly.ps1, e2e-*.mjs
vendor/    forge/ = Build-Ausgabe von import-forge.ps1 (nicht versioniert, außer FORGE_COMMIT), forge-overrides/ = Karten-Fixes
docs/      STATUS, DEVELOPMENT, CODEMAP, DECISIONS, SERVER; archive/ = XMage-Zeit
LICENSE, LICENSES/   GPL-3.0, Forge-GPL-3.0, THIRD-PARTY.md, XMage-MIT.txt
```

Wichtig: `vendor/forge/` entsteht nur durch `scripts\import-forge.ps1`; Jars und Daten immer vom selben Commit, nie von
Hand. Karten-Fixes nur als Datei unter `vendor/forge-overrides/`. Referenz-Quellcode ist Forge am Commit aus
`vendor/forge/FORGE_COMMIT`.

## Grenzen

- Die Forge-KI ist heuristisch und schnell (Ø etwa 1 s pro Bot-Zug); „Blitz“ begrenzt die Denkzeit je Entscheidung
  auf 2 s.
- Einige Forge-Texte (Prompts, Spielverlauf) sind noch englisch.
- Moxfield blockt automatische Abrufe teilweise – dann in Moxfield „Export → Text“ kopieren und einfügen.
- Kein Undo/Rollback (bewusst).

## Lizenzen

MageLite steht unter der **GNU General Public License, Version 3 oder (nach Wahl) neuer** (GPL-3.0-or-later), Text in
[`LICENSE`](LICENSE). Grund: Die Regel-Engine [Forge](https://github.com/Card-Forge/forge) (GPL-3.0, Text
`LICENSES/Forge-GPL-3.0.txt`) läuft im selben Prozess.

**Quellcode:** Dieses Repo ist der vollständige Quellcode. Zu jedem Installer gehört eine `SOURCE.txt` mit dem
Repo-Commit, aus dem er gebaut wurde; den Forge-Stand nennt `vendor/forge/FORGE_COMMIT`
([Card-Forge/forge](https://github.com/Card-Forge/forge) am genannten Commit). Den Quellcode eines Setups findet man
also über diese beiden Commits.

Weitere Komponenten und ihre Lizenzen: [`LICENSES/THIRD-PARTY.md`](LICENSES/THIRD-PARTY.md). Aus XMage 1.4.60
übernommene Listen (Brackets, Sample-Decks): MIT, `LICENSES/XMage-MIT.txt`. Kartenbilder: Scryfall (nicht verändert,
nur lokal zwischengespeichert). Mana-Symbole: [Mana](https://github.com/andrewgioia/mana) (SIL OFL / MIT).
Magic: The Gathering ist eine Marke von Wizards of the Coast; MageLite steht in keiner Verbindung zu Wizards of the Coast.
