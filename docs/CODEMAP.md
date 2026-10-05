# Code-Landkarte

Wie die Implementierung aufgebaut ist. Die ursprüngliche Analyse mit vielen XMage-Details steht in
`docs/architecture.md`; dieses Dokument beschreibt, was **tatsächlich gebaut** wurde.

## Prozesse

```
Electron (desktop/src/main.cjs)
  ├─ spawn: java -cp engine/lib/magelite-engine-*.jar;engine/lib/* dev.magelite.Main --port=0 --data=%APPDATA%\MageLite\engine --vendor=… --ui=… --parent-pid=…
  │         stdout-Zeile "MAGELITE_READY {port, token, bootMs}" → Fenster lädt http://127.0.0.1:<port>/?port=…&token=…
  └─ BrowserWindow (React-UI aus ui/dist, von der Engine ausgeliefert)
        ├─ REST  /api/*, /img/*         (Token als Query oder Header X-MageLite-Token)
        └─ WS    /ws/game/{id}?token=…  (JSON-Nachrichten mit Feld "t")
```

- Arbeitsverzeichnis der Engine = Datenordner: XMage öffnet die Karten-DB immer relativ als `./db/cards.h2`.
- Die Engine beendet sich selbst, wenn der Electron-Prozess weg ist (Parent-Watchdog in `Main`).
- Dev: `gradlew run` → Port 7317, `--dev` (kein Token, Konsolen-Log), Daten in `engine/run`.
- **Server-Modus** (fly.io, `docs/SERVER.md`): `java … dev.magelite.Main --server --host=0.0.0.0 --port=8080
  --data=/data --max-games=1 --idle-exit-min=10`. Kein Token; Login per Cookie `ml_code` (Einladungscode), die UI
  spricht dieselbe Origin an (`wss://`). Dev: `gradlew runServer` + Vite-Proxy (`http://localhost:5173/` ohne `?port=`).

## Engine (`engine/src/main/java/dev/magelite`)

| Paket/Datei | Aufgabe |
|---|---|
| `Main` | Argumente (`--server`, `--host`, `--max-games`, `--idle-exit-min`), Logging, Karten-DB, SQLite, Owner-Konto aus `MAGELITE_OWNER_CODE`, Module registrieren, READY-Zeile, Parent-Watchdog (lokal), Leerlauf-Exit + Abbruch verwaister Spiele (`GameHost.disconnectedForMs`, Server) |
| `api/Auth` | Before-Handler für `/api/*`, `/img/*`: lokal Token → `User.LOCAL`; Server Cookie → SHA-256 → Konto; Login-Rate-Limit, Origin-Prüfung für WS |
| `auth/User`, `InviteCodes`, `AccountService`, `AuthRoutes` | Nutzer-Record (lokal immer 1); Codes erzeugen/normalisieren/hashen; Konten (anlegen, rotieren, entfernen, Owner-Bootstrap, `last_seen`); `/api/me`, `/api/auth/*`, `/api/admin/invites*` |
| `boot/CardDbManager` | Seed-DB kopieren (falls vorhanden), sonst/bei Bedarf `CardScanner.scan()`; setzt `CardScanner.scanned` |
| `boot/LogConfig` | log4j-Konfiguration (KI-Logs auf WARN, Datei `logs/engine.log`) |
| `api/HttpServer` | Javalin: Routen, Auth-Filter, `POST /api/games` (409 bei belegtem Tisch), WebSocket-Handling (Cookie/Origin, nur der Besitzer des Spiels), Sitzungen pro Nutzer schließen, Module |
| `api/Outbox` | sendet pro WS-Verbindung auf eigenem Thread; aufeinanderfolgende States werden zusammengefasst |
| `api/Json` | gemeinsamer Jackson-`ObjectMapper` |
| `game/GameHost` | **Herzstück**: ein Spiel (1–4 Menschen, Rest Bots), Spiel-Thread, CALL-Executor, Listener, Prompts, Antwort-Routing, Auto-Passen, Auto-Mana, Spielende, Belohnungs-Hook, Wachhund (XMage-Antwort-Race, Aktivität). Pro Mensch ein `HumanSeat` (Sink, eigener State, Auto-Pay-Zustand, gepasste Trigger, Aufgabe); der eine offene Prompt gehört `promptSeat`, nur der Besitzer darf antworten; `leave(seat)` = nur dieser Sitz gibt auf, ohne Menschen geben die Bots auf |
| `game/PromptMapper` | `PlayerQueryEvent` → `PromptDto` (ASK, SELECT, PICK_TARGET, …) |
| `game/ReplacementAssist` | Ersatzeffekt-Wahl: Items nach Regeltext gruppieren (`choice.groups`), „you may“ = optional, Frage aus `ContinuousEffects.replaceEvent` erkennen (StackWalker), Regeltext an einer Quelle finden. `GameHost.replacement`/`handleReplacement` nutzen das für 1-Klick, „Keinen anwenden“ und „für dieses Spiel merken“ |
| `game/AutoPayer` | Planer fürs automatische Bezahlen von Manakosten |
| `game/MageLiteBot` | `ComputerPlayerControllableProxy` + Tempo (`fastOpponentTurns`, `fastStack`, `reactInCombat`, Denkzeit, Pausen nur nach echten Aktionen, Hooks); Angriffe über `FfaAttack` |
| `game/FfaAttack` | Angriffe im FFA: Lethal gegen irgendeinen Gegner, „sicher“ pro Verteidiger, Ziel nach Schaden/Leben × Bedrohung, Blocker gegen Gegenschlag zurückhalten |
| `game/BotTuning` | KI-Verbesserungen pro Spieler-ID abschaltbar (`FFA_EVAL`, `FFA_ATTACK`, `REACT_IN_COMBAT`, für die Arena), Gewichte, Start-Selbstprüfung der Bewertung |
| `game/TempoSettings` | Presets BLITZ/NORMAL/BEDACHT/MAX, live änderbar (von allen Bot-Kopien geteilt) |
| `game/StackSig` | Signatur des obersten Stapelobjekts (Controller, Quellname, Regeltext, Ziele): „gleiche Trigger“ erkennen |
| `game/MageLiteMatch` | Commander-FFA-Match (40 Leben, London-Mulligan, Rollback aus) |
| `game/TrackingLondonMulligan` | zählt Mulligans; Copy kopiert private Felder per Reflection |
| `game/HumanSettings` | `UserData` für den Menschen (Stopps, Auto-Pass nach Zauber …) |
| `game/GameRegistry`, `GameSetup` | laufende Spiele (ein Sitz pro Nutzer, insgesamt `maxGames`; voll → `BusyException`/409); `GameSetup(seats, tempo)` mit `SeatSpec.human(userId, name, deck, deckId)` / `SeatSpec.bot(deck)` in Tischreihenfolge, Komfort-Konstruktor 1 Mensch + 3 Bots |
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
| `stats/StatsWatcher`, `StatsSink` | Spielereignisse jedes Menschen erfassen (ein Sink pro Spiel **und** Spieler; Starthand, gezogen, gewirkt, Länder, Schaden, eigene Züge) |
| `stats/GameRecorder` | Spiel pro menschlichem Sitz speichern (`games`/`game_card_stats` mit Schlüssel Spiel+Nutzer, `game_seats` einmal), XP/Meisterschaft an dessen Held/Deck → eigenes `Reward` im `gameOver` jedes Sitzes |
| `stats/ProfileService`, `Progression` | Held (Name, XP, Level, Titel), Level-Kurve, Meisterschaftsstufen |
| `stats/StatsRoutes` | `/api/profile`, `/api/stats/*`, `/api/history` |
| `spike/BotSpike`, `HumanSpike` | headless Tests (4 Bots / automatischer Test-Spieler; `HumanSpike` prüft auch Zugfolge = Sitzordnung; `--scenario=swarm` misst Trigger-Ketten und testet den Mehrfach-Angriff; `--scenario=dredge` prüft Ersatzeffekt-Gruppen, 1-Klick, „Keinen anwenden“, „merken“) |
| `spike/BotArena` | KI-Vergleich A vs B (je 2 Sitze, Spiegel-Spiele mit getauschten Seiten): Siege, Platzierungspunkte, ms/Zug, CSV in `run/arena/` (`gradlew botArena`) |
| `spike/Scenarios` | Test-Situationen per `game.cheat` vor dem Start (`swarm`: 16 Scute Swarm + Länder; `dredge`: 7 Dredge-Karten im Friedhof, 3 Gruppen); in der Engine nur mit `--dev` (`POST /api/games {scenario}`) |

**XMage-Ersatzklasse** (`engine/src/main/java/mage/player/ai/score/GameStateEvaluator2.java`): gleicher Name wie im
XMage-Jar, API identisch. Bewertet gegen **alle** Gegner statt nur den ersten. Greift nur, weil das Engine-Jar auf dem
Classpath vor den XMage-Jars steht (`desktop/src/engine.cjs` → `engineClasspath`, Gradle von selbst). `Main` prüft das
beim Start (`BotTuning.checkFfaEvaluator`, Log „KI-Bewertung: …“).

### Lebenszyklus eines Prompts

1. Der XMage-`HumanPlayer` braucht eine Entscheidung und feuert ein `PlayerQueryEvent`, dann blockiert er in
   `waitForResponse`.
2. `GameHost.onQueryEvent` läuft auf dem Spiel-Thread. Gehört das Event zu einem Bot (`SELECT`), wird nur
   „denkt …“ gemeldet. Gehört es dem Menschen:
   - Läuft ein Mehrfach-Angriff/-Block, beantwortet ihn `continueMacro`.
   - Dann wird versucht, ihn automatisch zu beantworten (`handleAutoPay`).
   - Prioritäts-Prompt: Hat der Mensch auf ein gleiches Stapelobjekt (`StackSig`) schon gepasst oder hat er keine
     Nicht-Mana-Aktion (Auto-Passen), wird automatisch gepasst. Dann geht nur ein gedrosselter State raus.
   - Sonst kommt ein State **mit** spielbaren Objekten und danach `PromptDto` mit neuer `id`.
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
| `hello` | `gameId`, `myPlayerId`, `seats[]` (Name, Deck, Commander, `human`), `tempo`, `host` (Gastgeber darf das Tempo stellen; Protokoll 2) |
| `state` | `StateDto`: `seq`, `turn`, `phase`, `step`, `activePlayerId`, `players[]` (beginnend mit mir, dann in Zugfolge; `topCard` = oberste Bibliothekskarte, wenn aufgedeckt oder für mich einsehbar, dann `topCardPrivate`), `hand`, `stack` (mit `targets`/`targetRefs`), `combat`, `revealed`, `lookedAt`, `playable` (id → Anzahl), `actions` (ids mit Nicht-Mana-Aktion) |
| `prompt` | `PromptDto`: `id`, `kind`, `message` (Segmente), `messageText`, Buttons, je nach Art `mode`/`possibleAttackers`/`targets`/`chosen`/`cards`/`choices`/`choice`/`min`/`max`/`items`/`pile1`/`pile2`/`mulligan`/`defenderPick` |
| `promptClosed` | `id` |
| `log` | `entries[]` mit `turn`, `active` (Name des aktiven Spielers), `kind` (INFO/STATUS), `rich`; nach Reconnect kommt der Verlauf komplett neu (Client leert ihn bei `hello`) |
| `status` | `thinking` (Bot-id), `waitingFor` (Bot- oder Mitspieler-Name; null = niemand mehr) |
| `seat` | `conceded` – eigener Sitz hat aufgegeben (nach `leave` und beim Reconnect); `/api/games/current` liefert dann 404 |
| `activity` | Herzschlag 1/s vom Wachhund: `mode` (you/bot/human/engine/idle/stuck; `human` = ein anderer Mensch ist dran), `who`, `cpu` (% eines Kerns, alle Engine-Threads), `idleMs`, `recovered` |
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
| `combat` | Mehrfach-Angriff/-Block beim offenen Angriffs-/Block-Prompt: `ids` (markierte Kreaturen), `target` (Spieler/Planeswalker bzw. Angreifer) |
| `settings` | `autoPay`, `autoPass` (bool) |
| `autoPass` | `on` |
| `leave` | eigener Sitz gibt auf; sind keine Menschen mehr im Spiel, geben auch die Bots auf |
| `tempo` | nur vom Gastgeber (`hello.host`) angenommen |
| `ping` | → `pong` |

### REST

| Methode | Pfad | Zweck |
|---|---|---|
| GET | `/api/health` | Lebenszeichen `{ok, version, mode, games}`; im Server-Modus ohne Login |
| GET | `/api/me` | `{mode: local\|server, user{id,name,admin}}` |
| POST | `/api/auth/login`, `/api/auth/logout` | `{code}` → Cookie `ml_code` (Server-Modus; 401 falsch, 429 Rate-Limit) |
| GET/POST | `/api/admin/invites` | Admin: Konten auflisten / anlegen `{name}` → `{id,name,code}` (Code nur einmal) |
| POST/DELETE | `/api/admin/invites/{id}/rotate`, `/api/admin/invites/{id}` | neuer Code / Konto entfernen (schließt dessen WebSockets, beendet sein Spiel) |
| GET | `/api/samples` | Sample-Decks (`id` = relativer Pfad) |
| GET/DELETE | `/api/decks`, `/api/decks/{id}` | eigene Decks |
| GET | `/api/decks/{id}/text` | Deck als bearbeitbarer Text |
| POST | `/api/decks/parse` | Vorschau `{text, name?, commanders?}` |
| POST | `/api/decks/url` | Import `{url, json?}`; 409 `{blocked:true, apiUrl}` wenn geblockt |
| POST | `/api/decks` | speichern `{id?, name, text, commanders?, source?, sourceUrl?}` |
| POST | `/api/games` | Spiel starten `{deck, bots[], tempo, humans?: [{userId, name?, deck?}]}` (weitere Menschen bis zur Lobby nur in der Dev-Engine; freie Plätze bis 4 werden mit Bots gefüllt); Deck-Spec `{type:"user",id}` / `{type:"sample",id}` / `{type:"random"}` |
| GET | `/api/games/current` | eigenes laufendes Spiel (für Reconnect); fremdes → 404 |
| GET/PUT | `/api/profile` | Held; PUT `{name}` |
| GET | `/api/stats/overview`, `/api/stats/decks`, `/api/stats/decks/{id}/cards`, `/api/history?limit=` | Statistik |
| GET | `/img/card/{set}/{num}?size=&face=&name=`, `/img/token?name=&set=&n=&size=`, `/img/named?name=&size=` | Bilder |

### SQLite (`magelite.db`, Migrationen `V1__init.sql`, `V2__users.sql`, `V3__games_per_user.sql`)

`users` (id, name, code_hash, is_admin, last_seen; 1 = lokal) · `profile` (id = Nutzer-id, name, xp_total) · `decks`
(dck-Text, commanders, colors, valid, mastery_xp, `user_id`) · `games` (PK `(id, user_id)`: Ergebnis, Platz, Tempo,
Mulligans, XP, end_reason je Mensch) · `game_seats` (pro Spiel einmal) · `game_card_stats` (PK `(game_id, user_id,
card_name)`: opening, drawn, cast, first_cast_turn) · `xp_ledger` (`user_id`) · `settings` (noch ungenutzt).
Joins `game_card_stats` ↔ `games` immer über `game_id` **und** `user_id`. Neue Migration: Datei `V4__….sql` anlegen
**und** in `Db.MIGRATIONS` eintragen (Splitter `;` + Zeilenumbruch, keine `;` in Kommentaren).

## UI (`ui/src`)

| Datei | Aufgabe |
|---|---|
| `main.tsx`, `App.tsx` | Einstieg, Engine-Wartebildschirm (Server: bis 3 min „Server wird gestartet“), `#invite=`-Login, `/api/me`-Gate → `LoginScreen`, Navigation (Server: „Einladungen“ für Admins, „Abmelden“), Reconnect zum laufenden Spiel; im Dev-Modus `window.__ml = {game, nav}` |
| `api/client.ts` | Endpoint: lokal (`window.magelite` oder `?port=`) → `http://127.0.0.1:<port>` + Token; sonst `location.origin` ohne Token (Cookie). `api.get/post/put/del`, `ApiError.status`, 401-Hook, `cardImageUrl()` |
| `store/auth.ts` | `mode` (local/server), `me`, `status` (ok/login), `login(code)`, `logout()`, `takeInviteFromUrl()` |
| `screens/LoginScreen.tsx`, `AdminScreen.tsx` | Code-Eingabe; Einladungen anlegen (Code + Link einmalig), rotieren, entfernen, zuletzt gesehen |
| `api/types.ts` | TypeScript-Typen des Protokolls (bei Änderungen an DTOs mitziehen!) |
| `store/game.ts` | Zustand-Store: WebSocket, State, Prompt, Log, Toasts, `answer()`, `action()`, Tempo, Auto-Mana |
| `store/nav.ts` | aktueller Screen, letzte Spielkonfiguration (localStorage) |
| `screens/*` | Held (`HomeScreen`), Spiel-Setup, Decks (Import-Dialog), Statistik |
| `game/GameScreen.tsx` | Tisch-Layout, TopBar, eigener Bereich, Hotkeys, „ausgeschieden“-Banner |
| `game/interaction.ts` | **Klicklogik**: aus Prompt + State → Modus (priority/attack/block/target/mana/dialog), Hervorhebung, Klickziel |
| `game/promptActions.ts` | Buttons pro Prompt-Art, F-Tasten-Belegung |
| `game/PromptBar.tsx`, `PromptDialogs.tsx` | Prompt-Leiste, Dialoge (Auswahl, Ersatzeffekte gruppiert, Menge, Stapel, Mulligan, Kartenwahl) |
| `game/Battlefield.tsx`, `OpponentPod.tsx`, `Hand.tsx`, `StackPanel.tsx`, `PlayerInfo.tsx` | Spielflächen; Stapel mit großem obersten Objekt (einklappbar); Zonen-Knöpfe leuchten, wenn darin etwas spielbar/Ziel ist, 📚 öffnet die sichtbare oberste Bibliothekskarte |
| `game/CombatOverlay.tsx`, `TargetOverlay.tsx` | SVG-Pfeile für Kampf bzw. Stapel-Ziele (sucht Elemente über `data-obj` / `data-player` / `data-life` / `data-stack`, Hilfen in `overlayGeometry.ts`) |
| `game/Side.tsx` | Kartenvorschau, Spielverlauf (nach Zügen gruppiert, Filter Wichtiges/Alles, Icons per Stichwort-Regex), Toasts, Einblendung aufgedeckter/angesehener Karten (`RevealPopups`, Store `reveals`) |
| `game/ActivityIndicator.tsx` | Anzeige in der TopBar: arbeitet die Engine wirklich (Modus + CPU aus `activity`), Warnung bei Stillstand/ohne Verbindung |
| `game/GameOverOverlay.tsx` | Ergebnis + XP-Animation, „Nochmal“ bzw. „Zurück zum Tisch“ |
| `game/PauseMenu.tsx` | Pausemenü (Esc / „☰ Menü“): Auto-Mana, Auto-Passen, Ton, Verlauf-Filter, Tempo (Gastgeber); Aufgeben mit Ja/Nein; nach Aufgabe/Ausscheiden „Zuschauen“, „Zurück zum Tisch“, „Zum Hauptmenü“ |
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
| `tools/steps-swarm.json`, `tools/swarm-pilot.js` | Szenario `swarm` (Dev-Engine): Stapel ×N, Shift-Markieren, Mehrfach-Angriff, Pfeile, Verlauf ×N |
| `tools/steps-dredge.json`, `tools/dredge-pilot.js` | Szenario `dredge` (Dev-Engine): Ersatzeffekt-Dialog mit Gruppen + Hover, „Keinen anwenden“, „merken“ + Toolbar-Knopf |
| `tools/steps-server.json` | Server-Modus über den Vite-Proxy: Login-Screen, `#invite=`-Login, Einladungen, Spiel |

## Skripte (`scripts/`) und Server-Dateien

`build.ps1` (alles bauen, prüft Java/Node) · `import-xmage.ps1` (XMage-Distribution → `vendor/xmage`) ·
`bootstrap-gradle.ps1` (Wrapper neu erzeugen) · `e2e-flow.mjs` (REST+WS-Test) · `e2e-login.mjs` (Server-Modus:
Konten, Cookie, Nutzertrennung) · `deploy-fly.ps1` (Health prüfen, `fly deploy`).
Repo-Root: `Dockerfile` (UI → Engine `installDist` → JRE 17, Engine-Jar vor `lib/*`), `.dockerignore`, `fly.toml`
(performance-2x/4 GB, Auto-Stop, Volume `/data`, Health-Grace 300 s). Betrieb: `docs/SERVER.md`.
