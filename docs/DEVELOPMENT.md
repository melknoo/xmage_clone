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

`build.ps1` importiert beim ersten Lauf **Forge** (`scripts\import-forge.ps1`, einige Minuten, siehe unten), installiert
die npm-Pakete in `ui/` und `desktop/`, lädt beim ersten Mal Gradle und baut alles. **Maven braucht man nicht**, das
Import-Skript lädt eine gepinnte Version nach `%LOCALAPPDATA%\MageLite-build`. Es gibt keine Karten-DB mehr: Forge liest
die Kartenskripte bei jedem Start aus `vendor\forge\res` (Boot ca. 4 s, `gradlew forgeCheck` misst es); die Dev-Engine
legt ihr Forge-Profil in `engine/run/forge-data`, die App in `%APPDATA%\MageLite\engine\forge-data` ab.

### Forge importieren (`scripts\import-forge.ps1`)

`vendor/forge/` (Jars, Kartenskripte, Editionen, KI-Profile) ist **nicht** im Repo und entsteht nur durch dieses Skript
aus dem Commit in `vendor/forge/FORGE_COMMIT`. Braucht Git, ein JDK 17+ und Internet; Maven ≥ 3.8.1 nimmt es aus dem
PATH oder lädt `apache-maven-3.9.x` (SHA-512 geprüft) selbst. Ablauf (das Skript meldet `[n/9]`):

1. Voraussetzungen (git, JDK, Maven); bricht ab, wenn ein `java.exe` mit `vendor\forge` in der Kommandozeile läuft (eine
   laufende Engine sperrt die Jars – erst beenden).
2. Commit holen: `git fetch --depth 1 --filter=blob:none` mit sparse Checkout (nur `forge-core/-game/-ai/-gui`, benötigte
   `res`-Verzeichnisse), Scratch-Kopie in `%LOCALAPPDATA%\MageLite-build\forge-src` (außerhalb des Repos).
3. Wachen: `Sentry.init` in den Forge-Modulen → Abbruch; zu wenige Kartenskripte (< 25 000) → Abbruch.
4. Reactor in der Scratch-Kopie auf 4 Module kürzen.
5. Bauen: `mvn package` + `copy-dependencies` (Checkstyle aus, Tests aus).
6. Jars nach `vendor/forge/lib` **ohne** `jetty-*`, `javax.servlet-api*`, `org.jupnp.support*`, `slf4j-tinylog*`,
   `slf4j-api*` (Javalin bringt Jetty 11, Logging läuft über reload4j; `org.jupnp` selbst bleibt, `IGuiBase` braucht den Typ).
7. `res/` nach Allow-Liste (`ai`, `blockdata`, `defaults`, `editions`, `formats`, `licenses`, `lists`, `setlookup`,
   `languages/*.properties`, `quest/commanderprecons`); `cardsfolder` und `tokenscripts` als `cardsfolder.zip` mit den
   Overrides aus `vendor/forge-overrides/` darüber.
8. Metadaten: `forge.profile.properties` (relative Pfade `forge-data/…`, Arbeitsverzeichnis = Datenordner),
   `LICENSE-Forge.txt`, `manifest.json` (Commit, Version, Jars mit SHA-256, Overrides).
9. Tausch über `vendor/forge.tmp` → `vendor/forge`, nie halbfertig.

Parameter: `-Commit <sha>` (anderer Stand; nach Erfolg wird `FORGE_COMMIT` gesetzt = Forge-Bump), `-Scratch <dir>`,
`-FullRes` (komplettes `res/`), `-KeepScratch`. Gemessen 2–4 min (Maven-Cache warm); beim allerersten Lauf länger.

- **Forge-Bump:** `import-forge.ps1 -Commit <sha>`, danach `gradlew test`, `gradlew forgeCheck`, `humanSpike` (Standard +
  Szenarien) und `botArena` laufen lassen, `FORGE_COMMIT` committen. Die Engine stempelt den Commit in
  `magelite-version.properties`; `ForgeBoot` verweigert den Start, wenn `vendor/forge/manifest.json` einen anderen
  Commit hat (Engine neu bauen oder neu importieren). `build.ps1` importiert selbst neu, wenn Pin und Manifest
  abweichen.
- **Karten-Fix:** Datei `vendor/forge-overrides/cardsfolder/<x>/<name>.txt` (komplettes Kartenskript, erste Zeile bzw.
  eine Kommentarzeile `# MageLite: <Grund>` ist Pflicht), dann neu importieren. Keine losen `.txt` neben dem Zip, keine
  Shadow-Klassen (`vendor/forge-overrides/README`).

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
- **Test-App getrennt von der installierten:** `MageLite.cmd` (nicht gepackt) nutzt `%APPDATA%\MageLite-dev`
  (Fenstertitel „MageLite (Test)“). Beim ersten Start kopiert die Engine `%APPDATA%\MageLite\engine\magelite.db` nur
  lesend dorthin (`--seed-db`) und stellt die Kopie auf Forge um; die installierte App bleibt unberührt. Neu
  übernehmen: `%APPDATA%\MageLite-dev\engine\magelite.db*` löschen. Anderes Verzeichnis: `MAGELITE_USER_DATA`.

## 3. Testen

| Was | Befehl | Erwartung |
|---|---|---|
| Typecheck UI | `cd ui; npx tsc -b` | keine Ausgabe |
| Engine kompilieren | `cd engine; .\gradlew.bat compileJava` | BUILD SUCCESSFUL |
| Engine-Tests | `cd engine; .\gradlew.bat test` | alle PASSED (Deck-Parser, 70 Sample-Decks, Bracket-Analyse, Forge-Boot, Text-/Mapper-/Bild-/Lobby-Helfer; bootet Forge einmal pro Lauf, ca. 10–25 s extra) |
| Forge-Boot | `.\gradlew.bat forgeCheck` | Exit 0 und Zeile `Forge <version> (<sha>): ~33 500 Karten, ~684 Editionen … Boot ca. 4 s, Heap ca. 150 MB` (Karten > 25 000, Editionen > 500) |
| 4 Bots headless | `.\gradlew.bat spike -PspikeArgs="--games=3 --tempo=BLITZ --turnCap=40"` | keine FEHLER, Zeiten pro Zug |
| Deck-Validierung | `.\gradlew.bat spike -PspikeArgs="--validate"` | 67/70 Sample-Decks gültig (3 mit gebannten Karten) |
| Prompt-API-Stresstest | `.\gradlew.bat humanSpike -PspikeArgs="--games=2 --turnCap=32 --verbose"` | „0 fehlgeschlagen“, keine STALLs |
| REST + WS End-to-End | Dev-Engine starten, dann `node scripts\e2e-flow.mjs https://archidekt.com/decks/7031486` | Spielende mit `reward` |
| Server-Modus (Konten) | `gradlew runServer`, dann `node scripts\e2e-login.mjs` | „alles gruen“ (Login, Cookie, Nutzertrennung, 409, Rotieren, Rate-Limit) |
| Mehrere Menschen (Routing) | `.\gradlew.bat humanSpike -PspikeArgs="--games=1 --turnCap=24 --humans=2"` (auch `--humans=4`) | „0 fehlgeschlagen“, jeder Sitz bekommt Prompts und `gameOver`; Zeile „Ereignisse (events)“ ohne „verdeckte Karten an Fremde“ |
| Szenarien | `.\gradlew.bat humanSpike -PspikeArgs="--games=1 --turnCap=8 --scenario=necro"` (auch `gemstone --turnCap=4`, `swarm --turnCap=24`, `dredge`, `convoke`) | „Necro x5: OK“, „Starthand-Aktion (Gemstone): OK“, „Mehrfach-Angriff: OK“ + „Angriff zuruecksetzen: OK“ |
| Verlassen/Abbruch mitten in einer Frage | `.\gradlew.bat humanSpike -PspikeArgs="--games=1 --leave=abortTarget"` (auch `abortRequiredTarget`, `prompt`, `bot`, `abort`) | „0 fehlgeschlagen“; Spielende wenige ms nach dem Abbruch (Forge fragt Ziele sonst rekursiv neu) |
| KI-Profile vergleichen | `.\gradlew.bat botArena -PspikeArgs="--games=30 --turnCap=80"` | Siege/Platzierungspunkte je Profil (MageLite vs. Reckless), CSV in `engine\run\arena` |
| Mehrere Menschen (REST+WS) | `gradlew runServer`, dann `node scripts\e2e-online.mjs` | „alles gruen“ (2 Cookies, 2 Autopiloten, Aufgeben einzeln, reward + `games`-Zeile je Nutzer) |
| Lobby/Tische | `gradlew runServer`, dann `node scripts\e2e-tables.mjs` | „alles gruen“ (Tisch eröffnen/beitreten, Decks, Bot-Platz, Start mit 3 Spielern, Revanche, schließen, 409-Fälle) |
| Server-UI visuell | `runServer` + `npm run dev`, dann `npx electron tools\shot.cjs tools\steps-server.json` in `desktop/` | `engine/run/shot-server-*.png` (Login, Einladungen, Spiel) |
| Szenario-Screenshots | Dev-Engine + `npm run dev`, dann `npx electron tools\shot.cjs tools\steps-<necro\|attack-undo\|gemstone\|fx\|modal-hover\|convoke\|pass-ui>.json` | `engine/run/necro-*.png`, `attack-*.png`, `gemstone-*.png`, `fx-*.png`, `modal-hover-*.png`, `convoke-*.png` (Weiter-Ziel, X, Einberufen), `pass-*.png` (F9 + Stopp, Startfehler, Login-Auge) |
| Admin + Brett-FX (Server-Modus) | Engine `--server --dev` auf 7401 (Owner Anna), Vite 5174, `social-seed.mjs`, dann `tools\steps-admin.json` bzw. `tools\steps-fx-anim.json` | `admin-*.png` (Lobby-Chat, Logo → Start, Nutzer/Detail, Einladungen, Server), `fx-*.png` (Zeitlupe `window.__mlFxSlow=6`) |
| Design-Screenshots (alle Screens) | Dev-Engine + `npm run dev`: `tools\steps-design-<splash\|meta\|game\|swarm\|necro\|dredge\|gemstone\|minsize>.json`; danach `runServer` + `node tools\design-mate.mjs` (zweiter Mensch) parallel zu `tools\steps-design-server.json` | `design/claude-design/screenshots/*.png`, Übersicht in `INDEX.md`; für Vorher/Nachher-Vergleiche beim Redesign |
| UI visuell | siehe unten | Screenshots ansehen |

Spike-Argumente: BotSpike `--games --turnCap --tempo=BLITZ|NORMAL|BEDACHT|MAX --seed --maxMinutes --parallel=P
--deckDir --decks=a;b --slowTurnSec --validate`; HumanSpike `--games --turnCap --tempo --seed --humans=1..4 --verbose
--scenario=swarm|dredge|gemstone|necro|convoke --spectate --decks=a;b --leave=… --dumpJson=datei.jsonl` (schreibt alle
Server-Nachrichten mit). Beide Spikes melden langsame Züge und zählen `UNMAPPED`-Aufrufe (siehe Abschnitt 4).

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

- Logs: Dev → `engine\run\logs\engine.log` (+ Konsole) und `engine\run\logs\forge.log` (Forge/tinylog, ab WARN);
  Test-App (`MageLite.cmd`) → `%APPDATA%\MageLite-dev\desktop.log` und `%APPDATA%\MageLite-dev\engine\logs\`;
  installierte App → dasselbe unter `%APPDATA%\MageLite\`.
- DevTools im App-Fenster: `Strg+Umschalt+I`.
- „AI eval thread at timeout“ (Stacktrace auf stderr) ist normal: Forges KI-Zeitlimit (`Game.AI_TIMEOUT`, vom Tempo-Preset)
  hat eine Entscheidung abgebrochen, vor allem bei großen Boards.
- Startet die Engine nicht: „Forge-Daten (…) passen nicht zur Engine (…)“ → `scripts\import-forge.ps1` bzw. Engine neu
  bauen; „Forge-Kartenskripte fehlen“ oder „Forge-Daten unvollständig“ → `scripts\import-forge.ps1`; ERROR „Arbeitsverzeichnis
  != Datenordner“ → Engine mit `cwd` = `--data` starten (sonst landet `forge-data/` an der falschen Stelle).
- Hängt ein Spiel (UI: `activity` meldet `stuck`, > 15 s ohne Änderung und ohne CPU-Last), im `engine.log` suchen:
  - `UNMAPPED <Methode> …`: ein Forge-Aufruf, den `PromptBridge`/`SeatGui` (oder `HeadlessGui`) nicht abbildet; die Engine
    beantwortet ihn automatisch oder wirft `IllegalStateException`. Die Spikes zählen sie (Zeile „Nicht abgebildet“).
  - `UNMAPPED loop …`: Client und Forge drehen sich im Kreis (> 200 Antworten auf eine Frage), der Sitz geht auf Autopilot.
  - „Endlosschleife vermutet: N Entscheidungen in Zug T“: Forge hat keine Schleifenerkennung (z. B. Marken-Trigger-Ketten);
    > 3000 Entscheidungen in einem Zug beenden das Spiel als Remis.
  - Thread-Dump (`jcmd <pid> Thread.print`): Der Spiel-Thread `Game-ml-<id>` wartet normalerweise in `GameHost.park`. Steckt
    er woanders, rechnet Forge (KI-Entscheidung) oder hängt selbst. Client-Antworten dürfen Forge nie direkt anfassen: sie
    gehören über `GameHost.inbox` auf den Spiel-Thread (siehe Klassen-Javadoc `GameHost`).
- Datenstand zurücksetzen: Dev → `engine\run` löschen; Test-App → `%APPDATA%\MageLite-dev` löschen (nächster Start
  übernimmt die echte DB nur lesend); installierte App → `%APPDATA%\MageLite` löschen (Decks/Statistik weg!).

### Forge-Interna nachschlagen

- Signaturen gegen die gebauten Jars: `javap -cp "vendor\forge\lib\*" forge.gamemodes.match.AbstractGuiGame` (für private
  Felder `-p`, für Bytecode `-c`); Beispiele: `forge.player.PlayerControllerHuman`, `forge.ai.PlayerControllerAi`,
  `forge.game.phase.PhaseHandler`, `forge.model.FModel`.
- Quellcode **am gepinnten Commit** (nie `master`, die APIs weichen ab): `https://raw.githubusercontent.com/Card-Forge/forge/<sha>/<pfad>`
  mit `<sha>` aus `vendor/forge/FORGE_COMMIT`. Wichtige Pfade:
  - `forge-gui/src/main/java/forge/gamemodes/match/AbstractGuiGame.java` und `…/match/input/Input*.java` (Vorbild für `SeatGui`/`PromptBridge`)
  - `forge-gui/src/main/java/forge/player/PlayerControllerHuman.java` (Vorbild für `HumanController`), `forge/gui/interfaces/IGuiBase.java`, `forge/gui/interfaces/IGuiGame.java`
  - `forge-ai/src/main/java/forge/ai/PlayerControllerAi.java`, `AiBlockController.java`, `AiProfileUtil.java` (KI)
  - `forge-game/src/main/java/forge/game/phase/PhaseHandler.java`, `forge/game/Match.java`, `forge/game/Game.java`, `forge/game/player/Player.java`, `forge/game/GameAction.java`
  - `forge-gui/src/main/java/forge/model/FModel.java`, `forge-core/src/main/java/forge/StaticData.java` (Karten-DB, Prefs)
  - Kartenskripte: `forge-gui/res/cardsfolder/<buchstabe>/<name>.txt`
- Karten-Skripte der importierten Version liegen gezippt in `vendor/forge/res/cardsfolder/cardsfolder.zip`.

## 5. Typische Erweiterungen

- **Neues Feld für die UI:** DTO in `engine/.../view/dto` ergänzen, in `ForgeViewMapper` (State) bzw. `PromptBridge`
  (Prompt) füllen, Wire-Namen über `WireNames`, `ui/src/api/types.ts` nachziehen. Bei `@JsonInclude(NON_DEFAULT)` daran denken, dass Default-Werte (0/false)
  nicht gesendet werden.
- **Neue Client-Aktion:** `api/GameMessages.dispatch` (`case "…"`; gemeinsam für WebSocket und Host-Link), Methode in
  `GameHost` (prüft nur und reiht das Kommando in `inbox` ein; Forge-Objekte fasst nur der Spiel-Thread an),
  Store-Methode in `ui/src/store/game.ts`.
- **Neue Aktion (`action`) erlauben:** `case` in `GameHost.action`.
- **Neuer REST-Endpunkt:** als `HttpServer.Module` (Beispiele: `DeckRoutes`, `StatsRoutes`), in `Main` registrieren.
  Fehler: `IllegalArgumentException` → HTTP 400 mit `{error}`.
- **DB-Schema ändern:** neue Datei `engine/src/main/resources/db/migrations/V10__beschreibung.sql` und in
  `Db.MIGRATIONS` eintragen. Bestehende Migrationen nie ändern.
- **Neuer Screen:** `ui/src/screens/…`, in `App.tsx` (`NAV` + Render) und `store/nav.ts` (`Screen`) eintragen.

## 6. Konventionen

- Java 17, Paket `dev.magelite`. Kommentare deutsch, ohne Umlaute (ae/oe/ue/ss). UI-Texte deutsch mit Umlauten.
- Forge-Texte (Kartentexte, Spielverlauf, Teile der Prompts) bleiben englisch; eigene Prompt-Texte der Engine dürfen deutsch sein.
- UI: React-Funktionskomponenten, Zustand-Stores, Tailwind-Klassen. Nie `dangerouslySetInnerHTML`; Log-/Prompt-Markup
  kommt als Segmente (`RichText` → `Rich`).
- PowerShell-/CMD-Skripte ASCII-only, Zeilenenden CRLF (regelt `.gitattributes`).
- `vendor/forge` nur über `scripts\import-forge.ps1` erzeugen/aktualisieren (Jars und Daten immer vom selben Commit, nie
  von Hand); danach Tests, `forgeCheck` und Spikes laufen lassen.

## 7. Release: Windows-Installer

```powershell
powershell -ExecutionPolicy Bypass -File scripts\release.ps1          # Version +1, bauen, Setup, still installieren
powershell -ExecutionPolicy Bypass -File scripts\release.ps1 -Fly     # dazu fly-Deploy (braucht committeten Stand)
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
- Ablauf: `build.ps1` (importiert Forge bei Bedarf) → Forge-Prüfung (`cardsfolder.zip`, `manifest.json`) →
  `desktop\out\SOURCE.txt` (Repo-Commit, Forge-Commit, GPL-Hinweis; vorher committen, sonst steht dort
  „UNCOMMITTETE AENDERUNGEN“) → Module per `jdeps -R` + Extraliste (`$extraModules`) → `jlink` nach `desktop\out\jre`
  → **Smoke-Start** der Engine mit dieser JRE (wartet auf `MAGELITE_READY`, bricht sonst mit Log ab) →
  `electron-builder` (Konfiguration im `build`-Block von `desktop/package.json`).
- Gepackte App: `resources/{engine/lib, ui, forge, jre, licenses, LICENSE.txt, SOURCE.txt}`; `forge` = `vendor/forge`
  ohne Jars (`res/**`, `manifest.json`, `forge.profile.properties`, `LICENSE-Forge.txt`), die Forge-Jars liegen in
  `engine/lib`. `engine.cjs` (`resolvePaths`) nimmt `resources/jre` zuerst. Forge liest die Kartenskripte bei jedem
  Start (wenige Sekunden), es gibt keine Karten-DB mehr.
- Installer: NSIS, pro Nutzer (kein Admin), Zielordner wählbar, Desktop-/Startmenü-Verknüpfung.
- Setup.exe ca. 205 MB (entpackt ca. 500 MB); Ziel: Windows 10/11 x64, ≥ 8 GB RAM empfohlen (Engine `-Xmx3g`).
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
