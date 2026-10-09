# CLAUDE.md – MageLite

Kontext für Claude Code (und Menschen), um nach einem frischen `git clone` nahtlos weiterzuarbeiten.

If my request is ambiguous, ask one clarifying question before doing anything.

When reporting information to me, be extremely concise and sacrifice grammar for sake of concision.

## Projekt in einem Satz

Desktop-App, um eigene **Commander-Decks gegen 3 KI-Bots zu goldfishen**. Regeln, Karten und KI kommen aus
**Forge** (Card-Forge/forge, GPL-3.0) am gepinnten Commit `vendor/forge/FORGE_COMMIT`, lokal gebaut von
`scripts/import-forge.ps1`. Ein schlanker Java-Host stellt REST/WebSocket für eine React-UI in Electron bereit
(bis 0.1.x lief dieselbe App auf XMage 1.4.60; Protokoll und UI sind gleich geblieben).

## Zusammenarbeit

- Mit dem Nutzer **Deutsch** sprechen, Berichte **sehr knapp**.
- Ist eine Anfrage mehrdeutig: **eine** Rückfrage stellen, bevor du loslegst.
- Committen/pushen nur auf Anfrage. Remote: `https://github.com/melknoo/xmage_clone` (Branch `main`, öffentlich).
- Vor größeren Änderungen `docs/STATUS.md` lesen (Stand, offene Punkte, bekannte Probleme)
  und nach getaner Arbeit dort aktualisieren.

## Festgelegte Nutzerentscheidungen (nicht ohne Rückfrage ändern)

- Forge-Engine **einbetten** (GPL-3.0; MageLite steht unter GPL-3.0-or-later), nicht neu schreiben. UI: React +
  lokaler Java-Prozess in Electron.
- Bot-Decks: mitgelieferte Commander-Decks (70 Listen, Decktext v2) und eigene Decks. Kartenbilder: Scryfall mit
  lokalem Cache.
- Deck-Import: Textliste und URL (Archidekt, Moxfield).
- Extras: einstellbares Bot-Tempo, Statistiken.
- Gamification: **ein Held** mit XP/Level/Titeln + **Deck-Meisterschaft**. Rein Meta, kein Gameplay-Einfluss.
- **Nicht gewollt:** Undo/Rollback, Cheat-Werkzeuge, Achievements, kosmetische Unlocks.

Begründungen: `docs/DECISIONS.md`.

## Wichtige Befehle

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build.ps1   # alles bauen (Forge-Import bei Bedarf, UI, Engine, Electron-Deps)
MageLite.cmd                                                 # Test-App starten (Daten in %APPDATA%\MageLite-dev, siehe Regel 13)
powershell -ExecutionPolicy Bypass -File scripts\import-forge.ps1   # Forge am FORGE_COMMIT bauen -> vendor/forge/{lib,res} (Maven wird selbst geladen)
powershell -ExecutionPolicy Bypass -File scripts\package.ps1 # Windows-Installer (braucht JDK 17+; Smoke-Start der Engine)
powershell -ExecutionPolicy Bypass -File scripts\release.ps1 # Version +1, bauen, Setup, installieren [-Fly] (docs/DEVELOPMENT.md §7)

cd engine; .\gradlew.bat run        # Dev-Engine: Port 7317, kein Token, Daten in engine\run
cd engine; .\gradlew.bat runServer  # Dev-Engine im Server-Modus (Cookie-Login, Owner-Code DEV-OWNER-CODE)
cd engine; .\gradlew.bat forgeCheck # Forge-Boot pruefen (Karten, Editionen, Token, Boot-Zeit, Heap)
cd ui; npm run dev                  # Vite-UI: http://localhost:5173/?port=7317 (direkt) bzw. http://localhost:5173/ (Proxy, Cookies)
node scripts\e2e-login.mjs          # Server-Modus: Konten/Cookie/Nutzertrennung gegen runServer
node scripts\e2e-online.mjs         # Server-Modus: 2 Menschen in einem Spiel (Routing, Aufgeben, Belohnung je Nutzer)
node scripts\e2e-tables.mjs         # Server-Modus: Lobby/Tische (eroeffnen, beitreten, Start, Revanche, 409-Faelle)
node scripts\e2e-social.mjs         # Server-Modus: Lobby-Chat, Freunde, Tisch-Einladungen
node scripts\e2e-spectate.mjs       # Server-Modus: Zuschauen (braucht MAGELITE_URL + MAGELITE_OWNER_CODE, Engine mit --max-games=2)
node scripts\e2e-admin.mjs          # Server-Modus: Admin-Bereich (Nutzer, Server-Uebersicht, Abmelden/Beenden/Schliessen)
node scripts\e2e-relay.mjs          # Host-Link: startet selbst 2 Engines (7411 Server, 7412 Host-App), Tisch auf dem eigenen Rechner
node scripts\e2e-signup.mjs         # Registrierung + Kostenbremsen: startet selbst eine Engine (7421); SIGNUP_SKIP_IDLE=1 spart ~4 min
node scripts\e2e-flow.mjs [archidekt-url]                     # REST+WS-End-to-End gegen Dev-Engine
powershell -ExecutionPolicy Bypass -File scripts\publish-setup.ps1 -Setup desktop\dist\MageLite-Setup-<v>.exe   # Setup als GitHub-Release (braucht gh)
powershell -ExecutionPolicy Bypass -File scripts\deploy-fly.ps1   # Deploy auf fly.io (docs/SERVER.md; prueft vorher vendor/forge)
cd engine; .\gradlew.bat test       # Parser-/Deck-/Boot-Tests gegen die echte Forge-Kartendatenbank
cd engine; .\gradlew.bat humanSpike -PspikeArgs="--games=1 --turnCap=24 --humans=4"   # Routing-Test mit 4 Test-Menschen
cd engine; .\gradlew.bat humanSpike -PspikeArgs="--games=1 --turnCap=16 --scenario=necro"   # Szenarien: necro|gemstone|swarm|dredge|convoke
cd engine; .\gradlew.bat humanSpike -PspikeArgs="--games=2 --turnCap=30 --humans=2 --spectate"   # Zuschauer ohne Lecks
cd engine; .\gradlew.bat humanSpike -PspikeArgs="--games=4 --leave=abortTarget"   # Abbruch waehrend offener Eingaben (prompt|bot|abort|abortTarget|abortRequiredTarget)
cd engine; .\gradlew.bat spike -PspikeArgs="--games=3 --turnCap=30 --tempo=NORMAL"   # nur Bots (s/Zug, Heap)
cd engine; .\gradlew.bat botArena -PspikeArgs="--games=30 --turnCap=80"     # KI-Profile vergleichen (CSV in run\arena)
cd ui; npx tsc -b                   # Typecheck
```

Details, Werkzeuge und Debugging: `docs/DEVELOPMENT.md`. Wo welcher Code liegt: `docs/CODEMAP.md`.

## Harte Regeln (sonst kaputt)

1. **`vendor/forge/` entsteht nur durch `scripts/import-forge.ps1`** (Commit aus `FORGE_COMMIT`); Jars und `res`
   nie von Hand, immer vom selben Commit. `ForgeBoot` vergleicht `manifest.json` mit dem eingebauten Commit.
   Getrackt ist nur `FORGE_COMMIT`. Karten-Fixes nur als `vendor/forge-overrides/cardsfolder/<x>/<name>.txt` +
   Re-Import (landen im `cardsfolder.zip`), keine Shadow-Klassen ohne Absprache. Aus `vendor/forge/lib` bleiben
   `jetty-*`, `javax.servlet-api`, `org.jupnp.support`, `slf4j-tinylog`, `slf4j-api` ausgeschlossen (Javalin/Jetty 11,
   reload4j); `org.jupnp` selbst bleibt. Kartenskripte eager laden, Sentry nie initialisieren.
2. **Spiel-Thread** (Details in `GameHost`): Jedes Spiel läuft auf einem eigenen Thread `Game-ml-<id>` (Name muss
   mit „Game“ beginnen, sonst führt Forge `GameAction.invoke` nicht inline aus); kein `HostedMatch`.
   - Client-Kommandos fassen Forge-Objekte **nie** direkt an, sondern gehen über `GameHost.inbox`; die läuft am
     Safe-Point bzw. in der Park-Schleife auf dem Spiel-Thread.
   - Menschen-Eingaben parken über `SeatGui.awaitInput` → `GameHost.park(frame)`; `PromptBridge` übersetzt
     Forge-Inputs/Dialoge in Prompts. Nur dort wird auf dem Spiel-Thread gewartet.
   - `ForgeEvents` laufen synchron auf dem Spiel-Thread: nur einreihen (FX, `StatsSink`), nie fragen oder blockieren.
   - Spielende während einer offenen Eingabe wirft `GameHost.GameEnded` (Error) – nicht fangen, `runGame` erledigt das.
3. **Spieler:** Menschen = `HumanController` (`PlayerControllerHuman`) + `SeatGui`, Bots = `ForgeBot`
   (`PlayerControllerAi`) mit KI-Profil „MageLite“ (`ForgeBoot.registerAiProfile`, ohne Zufalls-Trades beim Blocken).
   Schutz: > 200 Antworten auf eine Frage → Autopilot, > 3000 Entscheidungen in einem Zug → Remis.
4. **Protokoll bleibt:** Die Engine emuliert die bisherigen Wire-Namen (Schritte, Zonen, Typen, Zähler, F-Tasten) in
   `view/WireNames`; Objekt-ids über `view/IdCodec`. Neue Werte dort abbilden, nicht in der UI umbenennen.
   Forge-Texte für Anzeige über `view/ForgeText.clean` glätten.
5. **Referenz-Quellcode ist Forge am gepinnten Commit:** `https://raw.githubusercontent.com/Card-Forge/forge/<FORGE_COMMIT>/<pfad>`
   (nicht `master`). Signaturen gegen die Jars prüfen: `javap -cp "vendor/forge/lib/*" forge.game.Game`.
6. **Jackson:** DTOs mit `@JsonInclude(NON_DEFAULT)` lassen Felder mit Default-Wert weg. Diskriminatoren wie
   `t = "prompt"` brauchen `@JsonInclude(ALWAYS)`.
7. **Tailwind 4:** eigene Klassen mit `@utility` definieren (nicht `.klasse { @apply … }`), Verläufe heißen
   `bg-linear-to-*`. Utility-Namen nie wie Tailwind-Builtins (`table-row` war `display: table-row` → `tbl-*`).
   Klassennamen nie per Template-String zusammensetzen (fehlen sonst still im Build). Nur Design-Tokens
   (`bg-0..4`, `fg-1..5`, `line-1..4`, `ember`, …), keine Emojis, Versalien per CSS statt im Text.
8. **VS Code setzt `ELECTRON_RUN_AS_NODE=1`.** Vor Electron-Aufrufen entfernen
   (`env -u ELECTRON_RUN_AS_NODE …` bzw. `Remove-Item Env:ELECTRON_RUN_AS_NODE`), sonst ist
   `require('electron')` nur ein Pfad-String.
9. **Windows PowerShell 5.1:** stderr von Programmen (z. B. `java -version 2>&1`, Maven, git) wird mit
   `ErrorActionPreference=Stop` zum Abbruch → über `cmd /c "… 2>&1"` umleiten. Skripte ASCII-only halten (ANSI-Lesart).
10. **Arbeitsverzeichnis = Datenordner.** Das Forge-Profil hat relative `forge-data/`-Pfade; `ForgeBoot` bricht sonst
    ab. Electron (`engine.cjs`), Dockerfile (`WORKDIR /data`), e2e-Skripte und Spikes setzen das.
11. **Nutzerbezug:** Jede Route liest den Nutzer über `Auth.user(ctx).id()` (lokal immer 1, Server-Modus aus dem
    Cookie) und reicht ihn an `DeckStore`/`ProfileService`/Statistik durch. Neue SQL auf `decks`, `games`,
    `xp_ledger`, `profile` immer mit `user_id` filtern; ein Spiel gehört `GameSetup.userId()`.
    Öffentlich ohne Login nur die Pfade aus `Auth.isPublicPath` (Health, Login/Registrierung, `/api/download/info`).
    Server-Spiele nur für `User.friend()` (`Limits.requireServerGames`); selbst registrierte Konten (`tier=public`)
    fallen unters Monatsbudget (`UptimeBudget`). Nur angemeldete Anfragen zählen als Aktivität (Leerlauf-Exit) –
    neue Polls in der UI mit `pollPaused()` bremsen. Betrieb und Kosten: `docs/SERVER.md`.
12. **Host-Link (Tisch auf dem eigenen Rechner):** Spielnachrichten laufen unverändert durch Umschläge `in/out`
    (`relay/`); neue WS-Nachrichten gehören in `api/GameMessages.dispatch`, nicht nur in `HttpServer`. Der Host sendet
    nie `gameOver` (fly baut es mit `reward` aus `finished`), seine lokale DB bleibt unberührt (`RewardHook = null`).
    Der Host meldet sich mit `X-MageLite-Engine: forge/1`; ohne passenden Marker schließt der Server mit 4426.
13. **Decks und Nutzerdaten:** Gespeicherte und übertragene Decks sind immer **Decktext v2** (`1 Name (SET) NUM`,
    Scryfall-Set + Nummer), nie Forge-`.dck` oder Engine-Objekte. `DeckMigration` (V9) stellt Altbestand beim Start
    um – irreversibel bis auf die Sicherung `magelite.db.xmage-backup`. Die **nicht gepackte App** (`MageLite.cmd`)
    nutzt deshalb `%APPDATA%\MageLite-dev` (Kopie der echten DB per `--seed-db`), die installierte App
    `%APPDATA%\MageLite`; nie eine Dev-Engine auf `%APPDATA%\MageLite` starten.
14. **GPL:** Installer enthält `LICENSE`, `licenses/`, `SOURCE.txt` (Repo- und Forge-Commit); jede veröffentlichte
    Version braucht einen gepushten Commit (vor `package.ps1`/`release.ps1` committen).

## Verifizieren, bevor du „fertig“ sagst

- Engine-Änderung: `gradlew compileJava test`, dann `humanSpike` (0 fehlgeschlagen, keine STALLs, `UNMAPPED` leer);
  bei Kern-Änderungen die Szenarien (`--scenario=…`) und `--humans=2 --spectate`.
- UI-Änderung: `npx tsc -b`, dann visuell prüfen: Dev-Engine + Vite starten,
  `desktop/tools/shot.cjs` mit `desktop/tools/steps-autoplay.json` bzw. `steps-redesign-*.json` ausführen und die PNGs
  ansehen (In-Page-Autopilot über `window.__ml`, nur im Vite-Dev-Modus vorhanden; Ablauf in `docs/DEVELOPMENT.md`).
  Test-Hooks (`data-obj`, `data-player`, `data-testid` …) beim Umbau erhalten.
- e2e-Skripte verlangen `MAGELITE_URL` (kein Default) – gegen eine eigene Test-Engine laufen lassen, nie gegen
  7317/fly; vorher prüfen, ob der Port frei ist (alte Test-Engines anderer Sitzungen).
- Danach `scripts\build.ps1`, damit `MageLite.cmd` den neuen Stand nutzt.
