# Code-Landkarte

Wie die Implementierung aufgebaut ist. Die ursprüngliche Analyse mit vielen XMage-Details steht in
`docs/architecture.md`; dieses Dokument beschreibt, was **tatsächlich gebaut** wurde.

## Prozesse

```
Electron (desktop/src/main.cjs)
  ├─ spawn: java -cp engine/lib/* dev.magelite.Main --port=0 --data=%APPDATA%\MageLite\engine --vendor=… --ui=… --parent-pid=…
  │         stdout-Zeile "MAGELITE_READY {port, token, bootMs}" → Fenster lädt http://127.0.0.1:<port>/?port=…&token=…
  └─ BrowserWindow (React-UI aus ui/dist, von der Engine ausgeliefert)
        ├─ REST  /api/*, /img/*         (Token als Query oder Header X-MageLite-Token)
        └─ WS    /ws/game/{id}?token=…  (JSON-Nachrichten mit Feld "t")
```

- Arbeitsverzeichnis der Engine = Datenordner: XMage öffnet die Karten-DB immer relativ als `./db/cards.h2`.
- Die Engine beendet sich selbst, wenn der Electron-Prozess weg ist (Parent-Watchdog in `Main`).
- Dev: `gradlew run` → Port 7317, `--dev` (kein Token, Konsolen-Log), Daten in `engine/run`.

## Engine (`engine/src/main/java/dev/magelite`)

| Paket/Datei | Aufgabe |
|---|---|
| `Main` | Argumente, Logging, Karten-DB, SQLite, Module registrieren, READY-Zeile, Watchdog |
| `boot/CardDbManager` | Seed-DB kopieren (falls vorhanden), sonst/bei Bedarf `CardScanner.scan()`; setzt `CardScanner.scanned` |
| `boot/LogConfig` | log4j-Konfiguration (KI-Logs auf WARN, Datei `logs/engine.log`) |
| `api/HttpServer` | Javalin: Routen, Token-Check, `POST /api/games`, WebSocket-Handling, Module |
| `api/Outbox` | sendet pro WS-Verbindung auf eigenem Thread; aufeinanderfolgende States werden zusammengefasst |
| `api/Json` | gemeinsamer Jackson-`ObjectMapper` |
| `game/GameHost` | **Herzstück**: ein Spiel (1 Mensch + 3 Bots), Spiel-Thread, CALL-Executor, Listener, Prompts, Antwort-Routing, Auto-Passen, Auto-Mana, Spielende, Belohnungs-Hook, Wachhund (XMage-Antwort-Race, Aktivität) |
| `game/PromptMapper` | `PlayerQueryEvent` → `PromptDto` (ASK, SELECT, PICK_TARGET, …) |
| `game/AutoPayer` | Planer fürs automatische Bezahlen von Manakosten |
| `game/MageLiteBot` | `ComputerPlayerControllableProxy` + Tempo (`fastOpponentTurns`, Denkzeit, Pausen, Hooks) |
| `game/TempoSettings` | Presets BLITZ/NORMAL/BEDACHT/MAX, live änderbar (von allen Bot-Kopien geteilt) |
| `game/MageLiteMatch` | Commander-FFA-Match (40 Leben, London-Mulligan, Rollback aus) |
| `game/TrackingLondonMulligan` | zählt Mulligans; Copy kopiert private Felder per Reflection |
| `game/HumanSettings` | `UserData` für den Menschen (Stopps, Auto-Pass nach Zauber …) |
| `game/GameRegistry`, `GameSetup` | aktuelles Spiel verwalten; Spielkonfiguration (inkl. `humanDeckId`) |
| `view/GameViewMapper` | XMage-`GameView` + Spielzustand → `StateDto` (Sitzordnung = echte Zugfolge aus `PlayerList`, Commander-Steuer/-Schaden, spielbare Objekte, Stapel-Ziele mit Namen) |
| `view/RichText` | XMage-HTML (Log/Prompts) → sichere Segmente `{text}`/`{obj,text,color}`/`{br}` |
| `view/dto/*` | DTOs: `StateDto`, `PlayerDto`, `CardDto`, `PermanentDto`, `CommandDto`, `PromptDto`, `TargetRefDto`, `Messages` |
| `deck/TextDeckParser` | Textlisten parsen, Karten über `CardRepository` auflösen, Commander erkennen, `.dck` erzeugen |
| `deck/DeckUrlImporter` | Archidekt/Moxfield-JSON → Textliste (409 „blocked“ → UI lädt über Electron) |
| `deck/DeckRoutes` | `/api/decks/parse`, `/api/decks/url`, `POST /api/decks`, `/api/decks/{id}/text` |
| `deck/DeckStore` | SQLite-Tabelle `decks` (gespeichert wird `.dck`-Text) |
| `deck/DeckLoader`, `LoadedDeck` | XMage-Importer + Validierung (`mage.deck.Commander`); `newDeck()` pro Spiel |
| `deck/SampleDeckCatalog` | mitgelieferte Decks textuell lesen, Farben aus der Karten-DB |
| `images/ImageService` | `/img/card`, `/img/token`, `/img/named`: Scryfall-Proxy mit Disk-Cache, Drossel 110 ms, 404-Merker 7 Tage |
| `stats/Db` | SQLite-Verbindung + Migrationen (`resources/db/migrations/V*.sql`, Liste in `Db.MIGRATIONS`) |
| `stats/StatsWatcher`, `StatsSink` | Spielereignisse des Menschen erfassen (Starthand, gezogen, gewirkt, Länder, Schaden, eigene Züge) |
| `stats/GameRecorder` | Spiel einmalig speichern, XP/Meisterschaft vergeben → `Reward` im `gameOver` |
| `stats/ProfileService`, `Progression` | Held (Name, XP, Level, Titel), Level-Kurve, Meisterschaftsstufen |
| `stats/StatsRoutes` | `/api/profile`, `/api/stats/*`, `/api/history` |
| `spike/BotSpike`, `HumanSpike` | headless Tests (4 Bots / automatischer Test-Spieler; `HumanSpike` prüft auch Zugfolge = Sitzordnung) |

### Lebenszyklus eines Prompts

1. Der XMage-`HumanPlayer` braucht eine Entscheidung und feuert ein `PlayerQueryEvent`, dann blockiert er in
   `waitForResponse`.
2. `GameHost.onQueryEvent` läuft auf dem Spiel-Thread. Gehört das Event zu einem Bot (`SELECT`), wird nur
   „denkt …“ gemeldet. Gehört es dem Menschen:
   - Erst wird versucht, es automatisch zu beantworten (`handleAutoPay`).
   - Dann wird ein State **mit** spielbaren Objekten gesendet.
   - Bei einem Prioritäts-Prompt ohne Aktion wird automatisch gepasst.
   - Sonst geht `PromptDto` mit neuer `id` an den Client.
3. Der Client schickt `{t:"respond", id, uuid|bool|int|str|mana}`. `GameHost.respond` prüft die `id`, sendet
   `promptClosed` und führt `setResponse*` auf dem CALL-Thread aus – erst, wenn der Spiel-Thread wirklich in
   `HumanPlayer.waitForResponse` → `wait()` steckt (XMage-Race, sonst geht `notifyAll()` verloren). Der Wachhund
   stellt eine Antwort erneut zu, wenn XMage danach ohne neue Frage weiter wartet.
4. XMage läuft weiter; die nächsten `UPDATE`-Events erzeugen gedrosselte States (max. alle 60 ms, Rest per
   `flushStateIfDirty`).

### WebSocket-Protokoll (aktueller Stand)

Server → Client:

| `t` | Inhalt |
|---|---|
| `hello` | `gameId`, `myPlayerId`, `seats[]` (Name, Deck, Commander), `tempo` |
| `state` | `StateDto`: `seq`, `turn`, `phase`, `step`, `activePlayerId`, `players[]` (beginnend mit mir, dann in Zugfolge; `topCard` = oberste Bibliothekskarte, wenn aufgedeckt oder für mich einsehbar, dann `topCardPrivate`), `hand`, `stack` (mit `targets`/`targetRefs`), `combat`, `revealed`, `lookedAt`, `playable` (id → Anzahl), `actions` (ids mit Nicht-Mana-Aktion) |
| `prompt` | `PromptDto`: `id`, `kind`, `message` (Segmente), `messageText`, Buttons, je nach Art `mode`/`possibleAttackers`/`targets`/`chosen`/`cards`/`choices`/`choice`/`min`/`max`/`items`/`pile1`/`pile2`/`mulligan`/`defenderPick` |
| `promptClosed` | `id` |
| `log` | `entries[]` mit `turn`, `active` (Name des aktiven Spielers), `kind` (INFO/STATUS), `rich`; nach Reconnect kommt der Verlauf komplett neu (Client leert ihn bei `hello`) |
| `status` | `thinking` (Bot-id), `waitingFor` |
| `activity` | Herzschlag 1/s vom Wachhund: `mode` (you/bot/engine/idle/stuck), `who`, `cpu` (% eines Kerns, alle Engine-Threads), `idleMs`, `recovered` |
| `toast` | `level`, `rich` |
| `gameOver` | `placements[]`, `winnerId`, `turns`, `durationMs`, `reward` (XP-Aufschlüsselung, Level, Meisterschaft), `error` |
| `error`, `pong` | |

Client → Server:

| `t` | Wirkung |
|---|---|
| `respond` | Antwort auf `prompt.id`: genau eins von `uuid`, `bool`, `int`, `str`, `mana:{playerId,type}` |
| `action` | `PlayerAction` aus Whitelist (F-Tasten `PASS_PRIORITY_*`, `HOLD_PRIORITY`, `TRIGGER_AUTO_ORDER_*`, `REQUEST_AUTO_ANSWER_*`, `MANA_AUTO_PAYMENT_*`, `USE_FIRST_MANA_ABILITY_*`, `CONCEDE`) |
| `tempo` | `preset` |
| `autoPay` | offenen Mana-Prompt automatisch bezahlen |
| `settings` | `autoPay`, `autoPass` (bool) |
| `autoPass` | `on` |
| `leave` | Spiel beenden (alle geben auf) |
| `ping` | → `pong` |

### REST

| Methode | Pfad | Zweck |
|---|---|---|
| GET | `/api/health` | Lebenszeichen |
| GET | `/api/samples` | Sample-Decks (`id` = relativer Pfad) |
| GET/DELETE | `/api/decks`, `/api/decks/{id}` | eigene Decks |
| GET | `/api/decks/{id}/text` | Deck als bearbeitbarer Text |
| POST | `/api/decks/parse` | Vorschau `{text, name?, commanders?}` |
| POST | `/api/decks/url` | Import `{url, json?}`; 409 `{blocked:true, apiUrl}` wenn geblockt |
| POST | `/api/decks` | speichern `{id?, name, text, commanders?, source?, sourceUrl?}` |
| POST | `/api/games` | Spiel starten `{deck, bots[3], tempo}`; Deck-Spec `{type:"user",id}` / `{type:"sample",id}` / `{type:"random"}` |
| GET | `/api/games/current` | laufendes Spiel (für Reconnect) |
| GET/PUT | `/api/profile` | Held; PUT `{name}` |
| GET | `/api/stats/overview`, `/api/stats/decks`, `/api/stats/decks/{id}/cards`, `/api/history?limit=` | Statistik |
| GET | `/img/card/{set}/{num}?size=&face=&name=`, `/img/token?name=&set=&n=&size=`, `/img/named?name=&size=` | Bilder |

### SQLite (`magelite.db`, Migration `V1__init.sql`)

`profile` (id=1, name, xp_total) · `decks` (dck-Text, commanders, colors, valid, mastery_xp) · `games` (Ergebnis, Platz,
Tempo, Mulligans, XP, end_reason) · `game_seats` · `game_card_stats` (pro Spiel+Karte: opening, drawn, cast,
first_cast_turn) · `xp_ledger` · `settings` (noch ungenutzt). Neue Migration: Datei `V2__….sql` anlegen **und** in
`Db.MIGRATIONS` eintragen.

## UI (`ui/src`)

| Datei | Aufgabe |
|---|---|
| `main.tsx`, `App.tsx` | Einstieg, Engine-Wartebildschirm, Navigation, Reconnect zum laufenden Spiel; im Dev-Modus `window.__ml = {game, nav}` |
| `api/client.ts` | Port/Token (Preload oder URL), `api.get/post/put/del`, `cardImageUrl()` |
| `api/types.ts` | TypeScript-Typen des Protokolls (bei Änderungen an DTOs mitziehen!) |
| `store/game.ts` | Zustand-Store: WebSocket, State, Prompt, Log, Toasts, `answer()`, `action()`, Tempo, Auto-Mana |
| `store/nav.ts` | aktueller Screen, letzte Spielkonfiguration (localStorage) |
| `screens/*` | Held (`HomeScreen`), Spiel-Setup, Decks (Import-Dialog), Statistik |
| `game/GameScreen.tsx` | Tisch-Layout, TopBar, eigener Bereich, Hotkeys, „ausgeschieden“-Banner |
| `game/interaction.ts` | **Klicklogik**: aus Prompt + State → Modus (priority/attack/block/target/mana/dialog), Hervorhebung, Klickziel |
| `game/promptActions.ts` | Buttons pro Prompt-Art, F-Tasten-Belegung |
| `game/PromptBar.tsx`, `PromptDialogs.tsx` | Prompt-Leiste, Dialoge (Auswahl, Menge, Stapel, Mulligan, Kartenwahl) |
| `game/Battlefield.tsx`, `OpponentPod.tsx`, `Hand.tsx`, `StackPanel.tsx`, `PlayerInfo.tsx` | Spielflächen; Stapel mit großem obersten Objekt (einklappbar); Zonen-Knöpfe leuchten, wenn darin etwas spielbar/Ziel ist, 📚 öffnet die sichtbare oberste Bibliothekskarte |
| `game/CombatOverlay.tsx`, `TargetOverlay.tsx` | SVG-Pfeile für Kampf bzw. Stapel-Ziele (sucht Elemente über `data-obj` / `data-player` / `data-life` / `data-stack`, Hilfen in `overlayGeometry.ts`) |
| `game/Side.tsx` | Kartenvorschau, Spielverlauf (nach Zügen gruppiert, Filter Wichtiges/Alles, Icons per Stichwort-Regex), Toasts, Einblendung aufgedeckter/angesehener Karten (`RevealPopups`, Store `reveals`) |
| `game/ActivityIndicator.tsx` | Anzeige in der TopBar: arbeitet die Engine wirklich (Modus + CPU aus `activity`), Warnung bei Stillstand/ohne Verbindung |
| `game/GameOverOverlay.tsx` | Ergebnis + XP-Animation, „Nochmal“ |
| `components/CardView.tsx` | Karte (Bild mit Text-Fallback, getappt = Querformat-Feld, `upright` für die Vorschau, Marken, P/T, Glow) |
| `components/Modal.tsx` | Dialog; `minimizable` → einklappbar (Tab), Spielfeld bleibt bedienbar; `viewer` → reine Ansicht (Esc schließt nur sie, Spiel-Hotkeys gesperrt) |
| `lib/mana.tsx` | Mana-Symbole (mana-font), Regeltext/Rich-Text ohne `innerHTML` |
| `lib/sounds.ts` | kurze WebAudio-Töne, Stummschaltung |
| `index.css` | Tailwind-4-Theme (`ink`, `gold`, `arcane`, `blood`), Glows, `@utility btn*` |

## Desktop (`desktop/`)

| Datei | Aufgabe |
|---|---|
| `src/main.cjs` | Fenster, Splash, Engine starten/neu starten (max. 2×), Fehlerdialog, IPC `magelite:fetchText` (nur Moxfield/Archidekt), `MAGELITE_AUTOSHOT` |
| `src/engine.cjs` | Pfade (Dev vs. gepackt), Java finden (`resources/jre`, `JAVA_HOME`, PATH), READY-Handshake (Timeout 10 min) |
| `src/preload.cjs` | `window.magelite = {port, token, fetchText}` |
| `tools/shot.cjs`, `tools/autoplay.js`, `tools/steps-autoplay.json` | Screenshot-Automatisierung + In-Page-Autopilot für UI-Tests |

## Skripte (`scripts/`)

`build.ps1` (alles bauen, prüft Java/Node) · `import-xmage.ps1` (XMage-Distribution → `vendor/xmage`) ·
`bootstrap-gradle.ps1` (Wrapper neu erzeugen) · `e2e-flow.mjs` (REST+WS-Test).
