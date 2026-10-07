# Entwickeln

Alles, was man nach einem frischen Klon braucht, um am Code weiterzuarbeiten.

## 1. Einmalig einrichten

```powershell
winget install Git.Git
winget install EclipseAdoptium.Temurin.21.JDK     # jedes JDK >= 17 geht (Gradle 9.1)
winget install OpenJS.NodeJS.LTS
# Terminal neu öffnen

git clone https://github.com/melknoo/xmage_clone.git
cd xmage_clone
git config user.name "melknoo"
git config user.email "melknoo@users.noreply.github.com"   # Git-Identität gilt nur lokal pro Klon
powershell -ExecutionPolicy Bypass -File scripts\build.ps1
```

`build.ps1` installiert die npm-Pakete in `ui/` und `desktop/`, lädt beim ersten Mal Gradle und baut alles.
Die Karten-DB ist nicht im Repo; die Dev-Engine baut sie beim ersten Start in `engine/run/db` auf (ca. 40 s), die
App in `%APPDATA%\MageLite\engine\db`. Wer die vorgefertigte DB hat, legt sie als
`vendor\xmage\db\cards.h2.mv.db` ab – dann wird sie beim Erststart nur kopiert.

## 2. Entwicklungs-Schleife

Drei Terminals:

```powershell
# A: Engine (Port 7317, ohne Token, Konsolen-Log, Daten in engine\run)
cd engine; .\gradlew.bat run

# A' (statt A): Engine im Server-Modus (Cookie-Login, Konten, Owner-Code DEV-OWNER-CODE;
#     "aufgeben lassen" schon nach 5 s Trennung statt 60 s, -Dmagelite.kickAfterMs)
cd engine; .\gradlew.bat runServer

# B: UI mit Hot-Reload
cd ui; npm run dev            # http://localhost:5173/?port=7317 (direkt, Token) oder
                              # http://localhost:5173/        (Vite-Proxy, same-origin, Cookies -> Server-Modus testen)

# C (optional): Electron-Fenster mit der Vite-UI
cd desktop; Remove-Item Env:ELECTRON_RUN_AS_NODE -ErrorAction SilentlyContinue; $env:MAGELITE_UI_DEV=1; npx electron .
```

- **Engine-Änderungen** brauchen einen Neustart von A. `gradlew run` beendet die Engine beim Abbruch nicht immer;
  hängt Port 7317, den Prozess beenden:
  `Stop-Process -Id (Get-NetTCPConnection -LocalPort 7317 -State Listen).OwningProcess -Force`
- **UI-Änderungen** lädt Vite sofort neu. Nach einem Engine-Neustart ist ein laufendes Spiel weg; die UI meldet das
  und springt ins Menü.
- **Für die App** (`MageLite.cmd`) danach `scripts\build.ps1` laufen lassen (baut `ui/dist` und
  `engine/build/install`).

## 3. Testen

| Was | Befehl | Erwartung |
|---|---|---|
| Typecheck UI | `cd ui; npx tsc -b` | keine Ausgabe |
| Engine kompilieren | `cd engine; .\gradlew.bat compileJava` | BUILD SUCCESSFUL |
| Parser-Tests | `cd engine; .\gradlew.bat test` | 7 PASSED (erster Lauf baut die DB, dauert länger) |
| 4 Bots headless | `.\gradlew.bat spike -PspikeArgs="--games=3 --tempo=BLITZ --turnCap=40"` | keine FEHLER, Zeiten pro Zug |
| Deck-Validierung | `.\gradlew.bat spike -PspikeArgs="--validate"` | 67/70 Sample-Decks gültig (3 mit gebannten Karten) |
| Prompt-API-Stresstest | `.\gradlew.bat humanSpike -PspikeArgs="--games=2 --turnCap=32 --verbose"` | „0 fehlgeschlagen“, keine STALLs |
| REST + WS End-to-End | Dev-Engine starten, dann `node scripts\e2e-flow.mjs https://archidekt.com/decks/7031486` | Spielende mit `reward` |
| Server-Modus (Konten) | `gradlew runServer`, dann `node scripts\e2e-login.mjs` | „alles gruen“ (Login, Cookie, Nutzertrennung, 409, Rotieren, Rate-Limit) |
| Mehrere Menschen (Routing) | `.\gradlew.bat humanSpike -PspikeArgs="--games=1 --turnCap=24 --humans=2"` (auch `--humans=4`) | „0 fehlgeschlagen“, jeder Sitz bekommt Prompts und `gameOver`; Zeile „Ereignisse (events)“ ohne „verdeckte Karten an Fremde“ |
| Szenarien | `.\gradlew.bat humanSpike -PspikeArgs="--games=1 --turnCap=8 --scenario=necro"` (auch `gemstone --turnCap=4`, `swarm --turnCap=24`, `dredge`) | „Necro x5: OK“, „Starthand-Aktion (Gemstone): OK“, „Mehrfach-Angriff: OK“ + „Angriff zuruecksetzen: OK“ |
| Karten-DB vs. Interrupt | `.\gradlew.bat dbInterruptSpike` | „retry-FS=aktiv … 0 KAPUTT -> OK“ (Demonic-Consultation-Absturz; nicht Teil von `test`) |
| Mehrere Menschen (REST+WS) | `gradlew runServer`, dann `node scripts\e2e-online.mjs` | „alles gruen“ (2 Cookies, 2 Autopiloten, Aufgeben einzeln, reward + `games`-Zeile je Nutzer) |
| Lobby/Tische | `gradlew runServer`, dann `node scripts\e2e-tables.mjs` | „alles gruen“ (Tisch eröffnen/beitreten, Decks, Bot-Platz, Start mit 3 Spielern, Revanche, schließen, 409-Fälle) |
| Server-UI visuell | `runServer` + `npm run dev`, dann `npx electron tools\shot.cjs tools\steps-server.json` in `desktop/` | `engine/run/shot-server-*.png` (Login, Einladungen, Spiel) |
| Szenario-Screenshots | Dev-Engine + `npm run dev`, dann `npx electron tools\shot.cjs tools\steps-<necro\|attack-undo\|gemstone\|fx\|modal-hover\|convoke\|pass-ui>.json` | `engine/run/necro-*.png`, `attack-*.png`, `gemstone-*.png`, `fx-*.png`, `modal-hover-*.png`, `convoke-*.png` (Weiter-Ziel, X, Einberufen), `pass-*.png` (F9 + Stopp, Startfehler, Login-Auge) |
| Admin + Brett-FX (Server-Modus) | Engine `--server --dev` auf 7401 (Owner Anna), Vite 5174, `social-seed.mjs`, dann `tools\steps-admin.json` bzw. `tools\steps-fx-anim.json` | `admin-*.png` (Lobby-Chat, Logo → Start, Nutzer/Detail, Einladungen, Server), `fx-*.png` (Zeitlupe `window.__mlFxSlow=6`) |
| Design-Screenshots (alle Screens) | Dev-Engine + `npm run dev`: `tools\steps-design-<splash\|meta\|game\|swarm\|necro\|dredge\|gemstone\|minsize>.json`; danach `runServer` + `node tools\design-mate.mjs` (zweiter Mensch) parallel zu `tools\steps-design-server.json` | `design/claude-design/screenshots/*.png`, Übersicht in `INDEX.md`; für Vorher/Nachher-Vergleiche beim Redesign |
| UI visuell | siehe unten | Screenshots ansehen |

Spike-Argumente: `--games --turnCap --tempo=BLITZ|NORMAL|BEDACHT|MAX --fastOpp=true|false --seed --verbose
--maxMinutes` (BotSpike) bzw. `--dumpJson=datei.jsonl` (HumanSpike, schreibt alle Server-Nachrichten mit).

### UI automatisiert ansehen

Dev-Engine (A) und Vite (B) müssen laufen. Dann:

```powershell
cd desktop
Remove-Item Env:ELECTRON_RUN_AS_NODE -ErrorAction SilentlyContinue
npx electron tools\shot.cjs tools\steps-autoplay.json
```

Das startet ein Zufallsspiel und spritzt `tools/autoplay.js` ein. Der Autopilot spielt über `window.__ml` mit
(Länder, Zauber, Angriffe, Dialoge, Auto-Mana). Danach entstehen Screenshots in `engine/run/shot-*.png`.
Eigene Abläufe: Schritte `wait`, `waitFor` (CSS-Selektor), `js`, `jsFile`, `shot` (siehe Kopf von `shot.cjs`).

**Redesign-Aufnahmen (isolierte Engines, nie 7317):** `shot.cjs` kennt Platzhalter `{{UI}}` (`SHOT_UI`, Standard
`http://localhost:5173`), `{{PORT}}` (`SHOT_PORT`, Standard 7400), `{{OUT}}`, `{{OWNER_CODE}}` (nur bei 127.0.0.1) und
bricht bei 7317/`*.fly.dev` ab (außer `SHOT_ALLOW_LIVE=1`); Exit ≠ 0 bei Fehlern.
- Brett/Meta: Engine auf 7400 (`--dev --data=<eigenes Verzeichnis>`), Vite 5173, dann
  `SHOT_PORT=7400 npx electron tools/shot.cjs tools/steps-redesign-board.json` (bzw. `-meta`, `-meta-leer` mit leerer DB).
- Online: Engine `--server --dev` auf 7401 mit `MAGELITE_OWNER_NAME=Anna`, zweites Vite
  `MAGELITE_ENGINE=http://127.0.0.1:7401 npx vite --port 5174`, Testdaten
  `MAGELITE_URL=http://127.0.0.1:7401 node desktop/tools/social-seed.mjs` (Steuerung auf 127.0.0.1:7499), dann
  `steps-redesign-online.json`. Ausgabe nach `design/redesign-shots/app/` (nicht eingecheckt).
- Nur eine JVM gleichzeitig (RAM); Gradle mit `--no-daemon`. `lib` vor dem Start in das Datenverzeichnis kopieren,
  sonst sperrt die laufende Engine `installDist`.
- Electron-Offline-Emulation blockt `localhost` nicht – Offline-Zustände per `fetch`-Patch im Step simulieren.

Die echte App einmal starten und nach X ms abfotografieren (beendet sich danach):
`$env:MAGELITE_AUTOSHOT="C:\pfad\bild.png;9000"; .\MageLite.cmd`

## 4. Debuggen

- Logs: Dev → `engine\run\logs\engine.log` und Konsole; App → `%APPDATA%\MageLite\desktop.log` und
  `%APPDATA%\MageLite\engine\logs\engine.log`.
- DevTools im App-Fenster: `Strg+Umschalt+I`.
- „AI player thinks too long“-Warnungen sind normal (Denkzeit-Limit greift).
- Hängt ein Spiel, nach „Game frozen“/„waitResponseOpen“ im Log suchen. Meist hat jemand vom Spiel-Thread aus
  geantwortet (siehe Regel 2 in `CLAUDE.md`).
- Datenstand zurücksetzen: Dev → `engine\run` löschen; App → `%APPDATA%\MageLite` löschen (Decks/Statistik weg!).

### XMage-Interna nachschlagen

- Signaturen gegen die vorhandenen Jars: `javap -cp "vendor\xmage\lib\mage-1.4.60.jar;vendor\xmage\lib\mage-common-1.4.60.jar" mage.players.Player`
  (für private Felder `-p`, für Bytecode `-c`).
- Quellcode am passenden Tag: `https://raw.githubusercontent.com/magefree/mage/xmage_1.4.60V3/<pfad>`. Wichtige Pfade:
  - `Mage.Server/src/main/java/mage/server/game/GameController.java` (Vorbild für `GameHost`)
  - `Mage.Server/src/main/java/mage/server/game/GameSessionPlayer.java`
  - `Mage.Server.Plugins/Mage.Player.Human/src/mage/player/human/HumanPlayer.java` (wie Antworten gelesen werden)
  - `Mage.Server.Plugins/Mage.Player.AI.MA/src/mage/player/ai/ComputerPlayer6.java` / `ComputerPlayer7.java`
  - `Mage.Common/src/main/java/mage/view/GameView.java`, `CardView.java`, `PlayerView.java`
  - `Mage/src/main/java/mage/players/PlayerImpl.java`, `Mage/src/main/java/mage/players/net/UserData.java`
- Gute Protokoll-Referenz: der XMage-Client speichert Spielmitschnitte unter `mage-client\gamelogsJson\` (JSON-Lines).

## 5. Typische Erweiterungen

- **Neues Feld für die UI:** DTO in `engine/.../view/dto` ergänzen, in `GameViewMapper`/`PromptMapper` füllen,
  `ui/src/api/types.ts` nachziehen. Bei `@JsonInclude(NON_DEFAULT)` daran denken, dass Default-Werte (0/false)
  nicht gesendet werden.
- **Neue Client-Aktion:** `HttpServer.onSocketMessage` (`case "…"`), Methode in `GameHost`
  (Spielzustand nur vom Spiel-Thread oder, wenn der Spiel-Thread gerade wartet, vom CALL-Thread anfassen),
  Store-Methode in `ui/src/store/game.ts`.
- **Neue PlayerAction erlauben:** `GameHost.ALLOWED_ACTIONS`.
- **Neuer REST-Endpunkt:** als `HttpServer.Module` (Beispiele: `DeckRoutes`, `StatsRoutes`), in `Main` registrieren.
  Fehler: `IllegalArgumentException` → HTTP 400 mit `{error}`.
- **DB-Schema ändern:** neue Datei `engine/src/main/resources/db/migrations/V2__beschreibung.sql` und in
  `Db.MIGRATIONS` eintragen. Bestehende Migrationen nie ändern.
- **Neuer Screen:** `ui/src/screens/…`, in `App.tsx` (`NAV` + Render) und `store/nav.ts` (`Screen`) eintragen.

## 6. Konventionen

- Java 17, Paket `dev.magelite`. Kommentare deutsch, ohne Umlaute (ae/oe/ue/ss). UI-Texte deutsch mit Umlauten.
- XMage-Texte (Prompts, Kartentexte, Log) bleiben englisch.
- UI: React-Funktionskomponenten, Zustand-Stores, Tailwind-Klassen. Nie `dangerouslySetInnerHTML`; XMage-HTML
  kommt als Segmente (`RichText` → `Rich`).
- PowerShell-/CMD-Skripte ASCII-only, Zeilenenden CRLF (regelt `.gitattributes`).
- `vendor/xmage/lib` nur über `scripts\import-xmage.ps1` aktualisieren; danach Spikes und Tests laufen lassen.

## 7. Release: Windows-Installer

```powershell
powershell -ExecutionPolicy Bypass -File scripts
elease.ps1          # Version +1, bauen, Setup, still installieren
powershell -ExecutionPolicy Bypass -File scripts
elease.ps1 -Fly     # dazu fly-Deploy (braucht committeten Stand)
powershell -ExecutionPolicy Bypass -File scripts\package.ps1          # nur Setup bauen -> desktop\dist\MageLite-Setup-<version>.exe
```

- **`release.ps1`** (ein Aufruf für alles): bricht ab, solange MageLite läuft (installierte App oder `MageLite.cmd`
  sperren die Engine-Jars) → `npm version <patch|minor|major|none>` in `desktop` (ui zieht mit) → `package.ps1`
  (enthält `build.ps1`, danach startet `MageLite.cmd` den neuen Stand) → alte Setups bis auf 2 löschen → Setup still
  installieren (`/S`, Benutzerdaten in `%APPDATA%\MageLite` bleiben) → Registry-Version prüfen → optional
  `deploy-fly.ps1`. Committet nichts; am Ende steht der Commit-Befehl für die Versionsdateien.
  Schalter: `-Bump`, `-NoInstall`, `-Fly`, `-Force` (fly trotz laufendem Spiel), `-AllowDirty`.

- Braucht zum Bauen ein **JDK 17+** (wegen `jlink`/`jdeps`, ein JRE reicht nicht) und Node.js. Auf dem Ziel-PC
  muss nichts installiert sein.
- Ablauf: `build.ps1` → Module per `jdeps` + feste Extraliste (`$extraModules` im Skript) → `jlink` nach
  `desktop\out\jre` → `electron-builder` (Konfiguration im `build`-Block von `desktop/package.json`).
- Gepackte App: `resources/{engine/lib, ui, xmage, jre}`; `engine.cjs` (`resolvePaths`) nimmt `resources/jre` zuerst.
  Die Karten-DB wird nicht mitgeliefert, sondern beim ersten Start in `%APPDATA%\MageLite\engine\db` gebaut.
- Installer: NSIS, pro Nutzer (kein Admin), Zielordner wählbar, Desktop-/Startmenü-Verknüpfung.
- Setup.exe ca. 320 MB; Ziel: Windows 10/11 x64, ≥ 8 GB RAM empfohlen (Engine `-Xmx3g`).
- EXE ist **nicht signiert** → SmartScreen: „Weitere Informationen“ → „Trotzdem ausführen“. Signieren ginge über
  Azure Trusted Signing oder ein OV-Zertifikat (kostenpflichtig), aktuell nicht geplant.
- Fehlt der Laufzeit ein Modul: `NoClassDefFoundError`/`ClassNotFoundException` in `%APPDATA%\MageLite\desktop.log`
  → Modul in `$extraModules` ergänzen.
- Bricht `electron-builder` beim Entpacken von `winCodeSign` mit „Cannot create symbolic link“ ab: Windows-
  Entwicklermodus einschalten oder das Skript einmal als Administrator ausführen.
- Version: **eine Quelle** `version` in `desktop/package.json`. `engine/build.gradle.kts` liest sie (auch im Docker-
  Build, `.dockerignore` lässt `desktop/package.json` durch) und schreibt `magelite-version.properties` →
  `Main.VERSION` → `/api/health` → Anzeige unten in der Navigation (`v0.x.y`). Engine-Jar heißt fest
  `magelite-engine.jar` (Dockerfile-Classpath).
