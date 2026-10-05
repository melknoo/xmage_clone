# CLAUDE.md – MageLite

Kontext für Claude Code (und Menschen), um nach einem frischen `git clone` nahtlos weiterzuarbeiten.

If my request is ambiguous, ask one clarifying question before doing anything.

When reporting information to me, be extremely concise and sacrifice grammar for sake of concision.

## Projekt in einem Satz

Desktop-App, um eigene **Commander-Decks gegen 3 KI-Bots zu goldfishen**. Regeln, Karten und KI kommen
unverändert aus den **XMage-1.4.60-Jars** (`vendor/xmage/lib`). Die App ersetzt den XMage-Server durch einen
schlanken Java-Host, der zusätzlich REST/WebSocket für eine React-UI in Electron bereitstellt.

## Zusammenarbeit

- Mit dem Nutzer **Deutsch** sprechen, Berichte **sehr knapp**.
- Ist eine Anfrage mehrdeutig: **eine** Rückfrage stellen, bevor du loslegst.
- Committen/pushen nur auf Anfrage. Remote: `https://github.com/melknoo/xmage_clone` (Branch `main`).
- Vor größeren Änderungen `docs/STATUS.md` lesen (Stand, offene Punkte, bekannte Probleme)
  und nach getaner Arbeit dort aktualisieren.

## Festgelegte Nutzerentscheidungen (nicht ohne Rückfrage ändern)

- XMage-Engine **einbetten**, nicht neu schreiben. UI: React + lokaler Java-Prozess in Electron.
- Bot-Decks: mitgelieferte XMage-Commander-Decks und eigene Decks. Kartenbilder: Scryfall mit lokalem Cache.
- Deck-Import: Textliste und URL (Archidekt, Moxfield).
- Extras: einstellbares Bot-Tempo, Statistiken.
- Gamification: **ein Held** mit XP/Level/Titeln + **Deck-Meisterschaft**. Rein Meta, kein Gameplay-Einfluss.
- **Nicht gewollt:** Undo/Rollback, Cheat-Werkzeuge, Achievements, kosmetische Unlocks.

Begründungen: `docs/DECISIONS.md`.

## Wichtige Befehle

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build.ps1   # alles bauen (UI, Engine, Electron-Deps)
MageLite.cmd                                                 # App starten
powershell -ExecutionPolicy Bypass -File scripts\package.ps1 # Windows-Installer (braucht JDK 17+)

cd engine; .\gradlew.bat run        # Dev-Engine: Port 7317, kein Token, Daten in engine\run
cd engine; .\gradlew.bat runServer  # Dev-Engine im Server-Modus (Cookie-Login, Owner-Code DEV-OWNER-CODE)
cd ui; npm run dev                  # Vite-UI: http://localhost:5173/?port=7317 (direkt) bzw. http://localhost:5173/ (Proxy, Cookies)
node scripts\e2e-login.mjs          # Server-Modus: Konten/Cookie/Nutzertrennung gegen runServer
node scripts\e2e-online.mjs         # Server-Modus: 2 Menschen in einem Spiel (Routing, Aufgeben, Belohnung je Nutzer)
node scripts\e2e-tables.mjs         # Server-Modus: Lobby/Tische (eroeffnen, beitreten, Start, Revanche, 409-Faelle)
cd engine; .\gradlew.bat humanSpike -PspikeArgs="--games=1 --turnCap=24 --humans=4"   # Routing-Test mit 4 Test-Menschen
powershell -ExecutionPolicy Bypass -File scripts\deploy-fly.ps1   # Deploy auf fly.io (docs/SERVER.md)
cd engine; .\gradlew.bat test       # Parser-Tests gegen die echte Karten-DB
cd engine; .\gradlew.bat humanSpike -PspikeArgs="--games=2 --turnCap=32"   # Prompt-API-Stresstest
node scripts\e2e-flow.mjs [archidekt-url]                     # REST+WS-End-to-End gegen Dev-Engine
cd ui; npx tsc -b                   # Typecheck
```

Details, Werkzeuge und Debugging: `docs/DEVELOPMENT.md`. Wo welcher Code liegt: `docs/CODEMAP.md`.

## Harte Regeln (sonst kaputt)

1. **XMage-Jars nie neu packen/shaden.** `CardRepository` vergleicht die `Build-Time` im Jar-Manifest mit der DB
   und leert sonst die Kartentabellen.
2. **Spiel-Thread-Regeln** (Details in `GameHost`):
   - `game.start()` läuft auf einem Thread mit Namen `ThreadUtils.THREAD_PREFIX_GAME + …`, sonst wirft XMage.
   - Listener laufen synchron auf dem Spiel-Thread – nur dort `GameView` bauen.
   - Antworten (`setResponse*`, `sendPlayerAction`) **nie vom Spiel-Thread**, sondern immer über den
     CALL-Executor. Vom Spiel-Thread aus blockiert XMage 30 s und verwirft die Antwort.
3. **`HumanPlayer` braucht `setUserData(...)`**, sonst NPE (`HumanSettings.defaults()`).
4. **Watcher dürfen keine Felder haben** und müssen `game.isSimulation()` ignorieren (die KI kopiert Watcher
   per Reflection). Zustand gehört in `StatsSink`.
5. **Referenz-Quellcode ist der Git-Tag `xmage_1.4.60V3`**, nicht `master` (APIs weichen ab). Rohdateien z. B.:
   `https://raw.githubusercontent.com/magefree/mage/xmage_1.4.60V3/Mage.Server/src/main/java/mage/server/game/GameController.java`.
   Signaturen gegen die Jars prüfen: `javap -cp "vendor/xmage/lib/mage-1.4.60.jar" mage.game.Game`.
6. **Jackson:** DTOs mit `@JsonInclude(NON_DEFAULT)` lassen Felder mit Default-Wert weg. Diskriminatoren wie
   `t = "prompt"` brauchen `@JsonInclude(ALWAYS)`.
7. **Tailwind 4:** eigene Klassen mit `@utility` definieren (nicht `.klasse { @apply … }`), Verläufe heißen
   `bg-linear-to-*`.
8. **VS Code setzt `ELECTRON_RUN_AS_NODE=1`.** Vor Electron-Aufrufen entfernen
   (`env -u ELECTRON_RUN_AS_NODE …` bzw. `Remove-Item Env:ELECTRON_RUN_AS_NODE`), sonst ist
   `require('electron')` nur ein Pfad-String.
9. **Windows PowerShell 5.1:** stderr von Programmen (z. B. `java -version 2>&1`) wird mit
   `ErrorActionPreference=Stop` zum Abbruch → über `cmd /c "… 2>&1"` umleiten. Skripte ASCII-only halten (ANSI-Lesart).
10. **Engine-Jar vor den XMage-Jars auf dem Classpath.** `engine/src/main/java/mage/player/ai/score/GameStateEvaluator2.java`
    ersetzt die gleichnamige XMage-Klasse (FFA-Bewertung). Nie nur `lib/*` (Reihenfolge undefiniert), siehe
    `desktop/src/engine.cjs` → `engineClasspath`. Prüfung: Log „KI-Bewertung: MageLite-FFA aktiv“.
11. **Nutzerbezug:** Jede Route liest den Nutzer über `Auth.user(ctx).id()` (lokal immer 1, Server-Modus aus dem
    Cookie) und reicht ihn an `DeckStore`/`ProfileService`/Statistik durch. Neue SQL auf `decks`, `games`,
    `xp_ledger`, `profile` immer mit `user_id` filtern; ein Spiel gehört `GameSetup.userId()`.
    Öffentlich ohne Login nur `/api/health` und `/api/auth/login` (Server-Modus). Betrieb: `docs/SERVER.md`.

## Verifizieren, bevor du „fertig“ sagst

- Engine-Änderung: `gradlew compileJava`, dann `humanSpike` (0 fehlgeschlagen, keine STALLs) und ggf. `test`.
- UI-Änderung: `npx tsc -b`, dann visuell prüfen: Dev-Engine + Vite starten,
  `desktop/tools/shot.cjs` mit `desktop/tools/steps-autoplay.json` ausführen und die PNGs ansehen
  (In-Page-Autopilot über `window.__ml`, nur im Vite-Dev-Modus vorhanden).
- Danach `scripts\build.ps1`, damit `MageLite.cmd` den neuen Stand nutzt.
