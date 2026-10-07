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
  --data=/data --max-games=1 --idle-exit-min=10`. Kein Token; Login per Session-Cookie `ml_sess` (Einladungscode
  oder E-Mail + Passwort; Legacy-Cookie `ml_code` gilt übergangsweise weiter), die UI spricht dieselbe Origin an (`wss://`). Dev: `gradlew runServer` + Vite-Proxy (`http://localhost:5173/` ohne `?port=`).

## Engine (`engine/src/main/java/dev/magelite`)

| Paket/Datei | Aufgabe |
|---|---|
| `Main` | Argumente (`--server`, `--host`, `--max-games`, `--idle-exit-min`), Logging, Karten-DB, SQLite, Owner-Konto aus `MAGELITE_OWNER_CODE`, Module registrieren, READY-Zeile, Parent-Watchdog (lokal), Leerlauf-Exit + Abbruch verwaister Spiele (`GameHost.disconnectedForMs`, Server) |
| `api/Auth` | Before-Handler für `/api/*`, `/img/*`: lokal Token → `User.LOCAL`; Server Session-Cookie `ml_sess` (SHA-256 → `sessions`) bzw. Legacy `ml_code` → Konto; Login-Rate-Limit, Origin-Prüfung für WS |
| `auth/User`, `InviteCodes`, `Passwords`, `AccountService`, `AuthRoutes` | Nutzer-Record (lokal immer 1; `email`, `hasPassword`); Codes erzeugen/normalisieren/hashen; PBKDF2-Passwörter + Session-Tokens; Konten (anlegen, rotieren, entfernen, Owner-Bootstrap, `last_seen`, Sessions, E-Mail/Passwort setzen/ändern); `/api/me`, `/api/auth/*` (Login per Code **oder** E-Mail/Passwort, `register`, `account`), `/api/admin/invites*` |
| `boot/CardDbManager`, `mage/cards/repository/DatabaseUtils` | Karten-DB bereitstellen; **lädt beim Start alle 9 Kartennamen-Listen vor** (`warmNames`, Demonic-Consultation-Absturz); Ersatz für XMages `DatabaseUtils` (H2-URL mit `retry:`-Dateisystem – `Thread.interrupt()` während eines DB-Zugriffs zerstört den Dateikanal nicht mehr; muss vor den XMage-Jars liegen, Prüfung `checkRetryFs`) |
| `boot/CardDbManager` | Seed-DB kopieren (falls vorhanden), sonst/bei Bedarf `CardScanner.scan()`; setzt `CardScanner.scanned` |
| `boot/LogConfig` | log4j-Konfiguration (KI-Logs auf WARN, Datei `logs/engine.log`) |
| `api/HttpServer` | Javalin: Routen, Auth-Filter, `POST /api/games` (409 bei belegtem Tisch), WebSocket-Handling (Cookie/Origin, nur der Besitzer des Spiels), Sitzungen pro Nutzer schließen, Module |
| `api/Outbox` | sendet pro WS-Verbindung auf eigenem Thread; aufeinanderfolgende States werden zusammengefasst |
| `api/Json` | gemeinsamer Jackson-`ObjectMapper` |
| `game/GameHost` | **Herzstück**: ein Spiel (1–4 Menschen, Rest Bots), Spiel-Thread, CALL-Executor, Listener, Prompts, Antwort-Routing, Auto-Passen, Auto-Mana, Spielende, Belohnungs-Hook, Wachhund (XMage-Antwort-Race, Aktivität). Pro Mensch ein `HumanSeat` (Sink, eigener State, Auto-Pay-Zustand, gepasste Trigger, Aufgabe); der eine offene Prompt gehört `promptSeat`, nur der Besitzer darf antworten; `leave(seat)` = nur dieser Sitz gibt auf, ohne Menschen geben die Bots auf |
| `game/PromptMapper` | `PlayerQueryEvent` → `PromptDto` (ASK, SELECT, PICK_TARGET, …) |
| `game/ReplacementAssist` | Ersatzeffekt-Wahl: Items nach Regeltext gruppieren (`choice.groups`), „you may“ = optional, Frage aus `ContinuousEffects.replaceEvent` erkennen (StackWalker), Regeltext an einer Quelle finden. `GameHost.replacement`/`handleReplacement` nutzen das für 1-Klick, „Keinen anwenden“ und „für dieses Spiel merken“ |
| `game/AutoPayer` | Planer fürs automatische Bezahlen von Manakosten (`partial`: nur Manaquellen, Rest per Sonderbezahlung) |
| `game/SpecialPay` | Sonderbezahlung (Convoke, Delve, Improvise, Assist): Knopf-Text + einberufbare Kreaturen (`specialTargets`) im Mana-Prompt, deutsche Labels im Aktions-Picker, Convoke-Aktion finden, Farbe nach Engpass wählen. Makro dazu: `GameHost.specialPay`/`continueSpecial` |
| `game/NextStop` | Ziel von „Weiter“ im eigenen Zug (`main1`/`combat`/`main2`/`end`); mögliche Angreifer pro Gegner |
| `game/MageLiteBot` | `ComputerPlayerControllableProxy` + Tempo (`fastOpponentTurns`, `fastStack`, `reactInCombat`, Denkzeit, Pausen nur nach echten Aktionen, Hooks); Angriffe über `FfaAttack` |
| `game/FfaAttack` | Angriffe im FFA: Lethal gegen irgendeinen Gegner, „sicher“ pro Verteidiger, Ziel nach Schaden/Leben × Bedrohung, Blocker gegen Gegenschlag zurückhalten |
| `game/BotTuning` | KI-Verbesserungen pro Spieler-ID abschaltbar (`FFA_EVAL`, `FFA_ATTACK`, `REACT_IN_COMBAT`, für die Arena), Gewichte, Start-Selbstprüfung der Bewertung |
| `game/TempoSettings` | Presets BLITZ/NORMAL/BEDACHT/MAX, live änderbar (von allen Bot-Kopien geteilt) |
| `game/StackSig` | Signatur des obersten Stapelobjekts (Controller, Quellname, Regeltext, Ziele): „gleiche Trigger“ erkennen |
| `game/MageLiteMatch` | Commander-FFA-Match (40 Leben, London-Mulligan, Rollback aus) |
| `game/TrackingLondonMulligan` | zählt Mulligans; Copy kopiert private Felder per Reflection |
| `game/HumanSettings` | `UserData` für den Menschen (Stopps: eigene Mains, Endphase der Gegner; Auto-Pass nach Zauber …) |
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
| `game/FxWatcher` | Watcher ohne Felder: Zonenwechsel (stirbt/Exil/Hand/Bibliothek/abgeworfen/gemillt/verrechnet), Schaden, Leben, Marken, Neutralisieren → `GameHost.onFx` → gebündelt als WS `events` vor dem nächsten State (verdeckte Karten nur an den Besitzer, Token-Tode ×N) |
| `game/ChatText` | Chat-Regeln (Steuerzeichen raus, 300 Zeichen, 5 Nachrichten / 5 s) für Spiel-, Tisch- und Lobby-Chat |
| `social/SocialService` | Server-Modus: Lobby-Chat (≤ 100, nur im Speicher, `seq`-Cursor), Präsenz (Poll < 15 s = online), Freundes-Status `online/table/game/offline`, Tisch-Einladungen (10 min, nur an Freunde, nur sichtbar solange der Tisch in der Lobby einen freien Platz hat). Eigene Sperre, Tisch/Spiel/DB-Abfragen außerhalb davon |
| `social/FriendStore` | SQL für `friendships` (Paar `a<b`, Anfrage → `accepted_at`, Gegenanfrage = Annehmen) und `users.lobby_chat` |
| `social/SocialRoutes` | REST `/api/social`, `/api/friends`, `/api/tables/{id}/invite`; `SocialException` → 409 |
| `stats/GameRecorder` | Spiel pro menschlichem Sitz speichern (`games`/`game_card_stats` mit Schlüssel Spiel+Nutzer, `game_seats` einmal), XP/Meisterschaft an dessen Held/Deck → eigenes `Reward` im `gameOver` jedes Sitzes |
| `stats/ProfileService`, `Progression` | Held (Name, XP, Level, Titel), Level-Kurve, Meisterschaftsstufen |
| `stats/StatsRoutes` | `/api/profile`, `/api/stats/*`, `/api/history` |
| `spike/BotSpike`, `HumanSpike` | headless Tests (4 Bots / automatischer Test-Spieler; `HumanSpike` prüft auch Zugfolge = Sitzordnung, zählt `events` und prüft, dass verdeckte Karten nie an Fremde gehen; `--scenario=swarm` misst Trigger-Ketten, testet Mehrfach-Angriff und „Angriff zurücksetzen“; `--scenario=dredge` prüft Ersatzeffekt-Gruppen; `--scenario=necro` „5-mal aktivieren“ (`GameHost.repeat`); `--scenario=gemstone` Starthand-Aktion vor dem Spiel; `--scenario=convoke` Weiter-Ziel, F10-Schutz, Blaze X=2 auf dem Stapel, Einberufen per Klick nach „Länder automatisch“, Main-2-Stopp) |
| `spike/DbInterruptSpike` | `gradlew dbInterruptSpike`: `Thread.interrupt()` mitten in `CardRepository.getNames()` darf die Karten-DB nicht kaputt machen (braucht unsere `DatabaseUtils`); bewusst nicht in `test` |
| `spike/BotArena` | KI-Vergleich A vs B (je 2 Sitze, Spiegel-Spiele mit getauschten Seiten): Siege, Platzierungspunkte, ms/Zug, CSV in `run/arena/` (`gradlew botArena`) |
| `spike/Scenarios` | Test-Situationen per `game.cheat` vor dem Start (`swarm`: 16 Scute Swarm + Länder; `dredge`: 7 Dredge-Karten im Friedhof, 3 Gruppen; `necro`: Necropotence im Spiel; `gemstone`: Gemstone Caverns auf der Hand, Bot beginnt; `convoke`: 5 Länder + 6 Kreaturen, Blaze und Guardian of Vitu-Ghazi auf der Hand); in der Engine nur mit `--dev` (`POST /api/games {scenario}`) |

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
   - Läuft eine Sonderbezahlung (Einberufen), beantwortet `continueSpecial` Aktionswahl, Kreatur und Farbe.
   - Prioritäts-Prompt: Hat der Mensch auf ein gleiches Stapelobjekt (`StackSig`) schon gepasst oder hat er keine
     Nicht-Mana-Aktion (Auto-Passen; nie in den eigenen Main-Phasen), wird automatisch gepasst. Dann geht nur ein
     gedrosselter State raus. Im eigenen Zug bei leerem Stapel bekommt der Prompt `nextStop`.
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
| `state` | `StateDto`: `seq`, `turn`, `phase`, `step`, `activePlayerId`, `players[]` (beginnend mit mir, dann in Zugfolge; `topCard` = oberste Bibliothekskarte, wenn aufgedeckt oder für mich einsehbar, dann `topCardPrivate`; `skips` = laufendes F-Tasten-Passen), `hand`, `stack` (mit `targets`/`targetRefs`, `x` = angesagtes X), `combat`, `revealed`, `lookedAt`, `playable` (id → Anzahl), `actions` (ids mit Nicht-Mana-Aktion) |
| `prompt` | `PromptDto`: `id`, `kind`, `message` (Segmente), `messageText`, Buttons, je nach Art `mode`/`nextStop` (Priorität im eigenen Zug: `main1`/`combat`/`main2`/`end`)/`possibleAttackers`/`targets`/`chosen`/`cards`/`choices`/`sourceId` (CHOOSE_ABILITY: Objekt der Fähigkeiten; nicht bei Sonderbezahlung)/`choice` (mit `hint`: `card` = Kartennamen)/`min`/`max`/`items`/`pile1`/`pile2`/`mulligan`/`defenderPick`; PLAY_MANA: `specialBtn` (z. B. „Einberufen“) + `specialTargets` (einberufbare Kreaturen) |
| `promptClosed` | `id` |
| `log` | `entries[]` mit `turn`, `active` (Name des aktiven Spielers), `kind` (INFO/STATUS), `rich`; nach Reconnect kommt der Verlauf komplett neu (Client leert ihn bei `hello`) |
| `status` | `thinking` (Bot-id), `waitingFor` (Bot- oder Mitspieler-Name; null = niemand mehr) |
| `seat` | `conceded` – eigener Sitz hat aufgegeben (nach `leave` und beim Reconnect); `/api/games/current` liefert dann 404 |
| `seats` | nur bei mehreren Menschen: `seats[] {playerId, connected, disconnectedMs, conceded}` bei Verbinden/Trennen, alle 2 s solange jemand getrennt ist |
| `activity` | Herzschlag 1/s vom Wachhund: `mode` (you/bot/human/engine/idle/stuck; `human` = ein anderer Mensch ist dran), `who`, `cpu` (% eines Kerns, alle Engine-Threads), `idleMs`, `recovered` |
| `toast` | `level`, `rich` |
| `events` | `items[] {kind, objectId, name, card, from, to, playerId, ownerId, sourceId, sourceName, amount, token, combat, hidden, ts}` – Spielereignisse für Mini-Animationen/Ereignisleiste (`FxWatcher`), kommen vor dem State, der sie widerspiegelt; `hidden` nur an den Besitzer |
| `chat` | `entries[] {ts, playerId, name, text}`; live eine Zeile, nach (Re-)Connect der Verlauf (≤ 100) als ein Bündel |
| `gameOver` | `placements[]`, `winnerId`, `turns`, `durationMs`, `reward` (XP-Aufschlüsselung, Level, Meisterschaft), `error` |
| `error`, `pong` | |

**Zuschauen** (`/ws/game/{id}?spectate=1`, nur Server-Modus): `hello` mit `spectator:true`, `viewpointId`, `tableName`;
öffentlicher `state` (`GameViewMapper.mapPublic`, viewer = null: keine Hand, kein `lookedAt`/`playable`/`actions`),
Log/Chat/Status/Activity/Seats, FX ohne `hidden`, `gameOver` ohne `reward`. Eingehend nur `ping`. Close-Codes
(endgültig, kein Reconnect): 4403 lokal, 4409 sitzt am Tisch/an einem Tisch, 4404 läuft nicht, 4429 > 8 Zuschauer,
4000 ersetzt (neue Verbindung desselben Nutzers), 4408 zu langsam / kein Ping.

Client → Server:

| `t` | Wirkung |
|---|---|
| `respond` | Antwort auf `prompt.id`: genau eins von `uuid`, `bool`, `int`, `str`, `mana:{playerId,type}`; `str:"special"` beim Mana-Prompt = Sonderbezahlung (Knopf) |
| `specialPay` | `id` (PLAY_MANA-Prompt), `uuid` (Kreatur aus `specialTargets`): einberufen; Aktionswahl, Ziel und Farbe beantwortet die Engine |
| `action` | `PlayerAction` aus Whitelist (F-Tasten `PASS_PRIORITY_*`, `HOLD_PRIORITY`, `TRIGGER_AUTO_ORDER_*`, `REQUEST_AUTO_ANSWER_*`, `MANA_AUTO_PAYMENT_*`, `USE_FIRST_MANA_ABILITY_*`, `CONCEDE`) |
| `tempo` | `preset` |
| `autoPay` | offenen Mana-Prompt automatisch bezahlen |
| `combat` | Mehrfach-Angriff/-Block beim offenen Angriffs-/Block-Prompt: `ids` (markierte Kreaturen), `target` (Spieler/Planeswalker bzw. Angreifer) |
| `combatReset` | „Angriff zurücksetzen“: alle eigenen Angreifer wieder zurücknehmen (beim Angriffs-Prompt oder in der Verteidiger-Wahl nach „Alle angreifen“); Makro klickt jeden Angreifer erneut an |
| `repeat` | `id` (CHOOSE_ABILITY-Prompt), `uuid` (Fähigkeit), `times` (2–20): Fähigkeit N-mal aktivieren, dazwischen `HOLD_PRIORITY`; Ziele/Fragen/fremde Stapelobjekte beenden die Wiederholung (Toast) |
| `chat` | `text` (≤ 300 Zeichen, 5 / 5 s) an alle Menschen am Tisch |
| `settings` | `autoPay`, `autoPass` (bool); `autoPass` an→aus bricht laufendes F-Tasten-Passen ab |
| `autoPass` | `on` |
| `leave` | eigener Sitz gibt auf; sind keine Menschen mehr im Spiel, geben auch die Bots auf |
| `kick` | `playerId`: einen seit ≥ 60 s getrennten Mitspieler aufgeben lassen (jeder verbundene, nicht aufgegebene Mensch; Dev-Engine 5 s über `-Dmagelite.kickAfterMs`) |
| `tempo` | nur vom Gastgeber (`hello.host`) angenommen |
| `ping` | → `pong` |

### REST

| Methode | Pfad | Zweck |
|---|---|---|
| GET | `/api/health` | Lebenszeichen `{ok, version, mode, games}`; im Server-Modus ohne Login |
| GET | `/api/me` | `{mode: local\|server, user{id,name,admin,email,hasPassword}}` |
| POST | `/api/auth/login`, `/api/auth/logout` | `{code}` **oder** `{email,password}` → Session-Cookie `ml_sess` (Server-Modus; 401 falsch, 429 Rate-Limit); Logout löscht die Session |
| POST/PUT | `/api/auth/register`, `/api/auth/account` | Konto sichern `{email,password}` (eingeloggt, 409 wenn schon gesichert/E-Mail vergeben) / ändern `{current, email?, password?}` (Passwortwechsel beendet andere Sessions) |
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
| PUT | `/api/tables/{id}/seats/{n}` | Gastgeber, nur LOBBY: `{kind:'BOT'\|'OPEN', deck?}`; `OPEN` auf einem Menschenplatz = **entfernen** (nie der eigene Platz; gesperrt bis zur nächsten Einladung) |
| GET | `/api/tables`, `/api/tables/{id}` | Tisch-Sicht: Plätze mit Deck-Infos, `mySeat`, `host`, `turn` (RUNNING), `spectators`, `canSpectate`, `chat[]` (nur Sitzende) |
| POST | `/api/tables/{id}/chat` | Tisch-Chat `{text}` (nur Sitzende, 409 sonst); die Zeilen (≤ 50) kommen in jeder Tisch-Antwort als `chat[]` mit |
| GET | `/api/social?after=<seq>` | Server-Modus, ein Poll für alles: `{chatIn, seq, msgs[] (nur > after, leer wenn draußen), members[], friends[{id,name,status,tableId?,tableName?}], incoming[], outgoing[], invites[]}`; setzt die Präsenz |
| POST/PUT | `/api/social/chat` | Lobby-Chat schreiben `{text}` (409 wenn draußen/Rate-Limit) / `{in: bool}` betreten/verlassen (pro Konto gespeichert) |
| POST/DELETE | `/api/friends`, `/api/friends/{id}/accept`, `/api/friends/{id}` | Anfrage `{name}` (exakt, Groß/klein egal) oder `{userId}` → `{state: outgoing\|friend}`; annehmen; ablehnen/zurückziehen/entfernen |
| POST/DELETE | `/api/tables/{id}/invite`, `/api/social/invites/{id}` | Freund an meinen Tisch einladen `{userId}` / Einladung ablehnen (Beitritt per `/api/tables/{id}/join` erledigt sie) |
| GET/PUT | `/api/profile` | Held; PUT `{name}` |
| GET | `/api/stats/overview`, `/api/stats/decks`, `/api/stats/decks/{id}/cards`, `/api/history?limit=` | Statistik |
| GET | `/img/card/{set}/{num}?size=&face=&name=`, `/img/token?name=&set=&n=&size=`, `/img/named?name=&size=` | Bilder |

### SQLite (`magelite.db`, Migrationen `V1__init.sql`, `V2__users.sql`, `V3__games_per_user.sql`, `V4__accounts.sql`, `V5__social.sql`)

`users` (id, name, code_hash, is_admin, last_seen, `email` (NOCASE, unique), `pw_hash` (PBKDF2), pw_set_at; 1 = lokal) ·
`sessions` (user_id, token_hash, via code|password, created_at, last_seen; Cookie `ml_sess` hält das Klartext-Token) · `profile` (id = Nutzer-id, name, xp_total) · `decks`
(dck-Text, commanders, colors, valid, mastery_xp, `user_id`) · `games` (PK `(id, user_id)`: Ergebnis, Platz, Tempo,
Mulligans, XP, end_reason je Mensch) · `game_seats` (pro Spiel einmal) · `game_card_stats` (PK `(game_id, user_id,
card_name)`: opening, drawn, cast, first_cast_turn) · `xp_ledger` (`user_id`) · `settings` (noch ungenutzt) ·
`friendships` (a < b, requested_by, accepted_at; ON DELETE CASCADE) · `users.lobby_chat` (1 = im Lobby-Chat).
Joins `game_card_stats` ↔ `games` immer über `game_id` **und** `user_id`. Neue Migration: Datei `V4__….sql` anlegen
**und** in `Db.MIGRATIONS` eintragen (Splitter `;` + Zeilenumbruch, keine `;` in Kommentaren).

## UI (`ui/src`)

Design „Graphit & Glut“ (Handoff: `design/design_handoff_magelite_redesign/`). Tokens in `index.css` (`bg-0..4`,
`line-1..4`, `fg-1..5`, `ember`, `target`, `chosen`, `attack`, `block`), eigene Utilities per `@utility`
(`label`, `btn-*`, `chip-*`, `card-*`, `tbl-*`, `toast`, …; Namen nie wie Tailwind-Builtins wählen, z. B. kein
`table-row`). Versalien per CSS, Tastenhinweise als `<kbd>`. Test-Hooks: `data-obj|objs|player|life|zone|owner|stack|ref`,
`data-testid` (z. B. `game-modal`, `modal-pill`, `attack-all`, `spectate-btn`, `seat-kick`, `conn-lost-bar`).

| Datei | Aufgabe |
|---|---|
| `main.tsx`, `App.tsx` | Einstieg, Boot (`shell/BootScreen` mit Phasen), Login-Gate, Shell mit `shell/NavRail` + `ConnectionBarSlot`, Resume (laufendes Spiel, sonst eigener Tisch), `<Toaster/>` über allem; Dev: `window.__ml = {game, nav, social, auth, table, ui, conn, api.start}` |
| `api/client.ts`, `api/types.ts` | Endpoint (lokal Token, Server Cookie), Verbindungs-Listener für `store/conn`; Protokoll-Typen (neue Felder optional) |
| `api/{tables,social,stats,decks,profile,admin}.ts` | REST-Module mit DTOs |
| `store/game.ts` | WebSocket (Nachzügler alter Sockets werden ignoriert), State, Prompt, Log, Chat, FX, `spectator`, `viewer`, `stackFocus`, `logFilter`, `connect(id, {spectate})`, `stopSpectating()` |
| `store/ui.ts`, `components/Toaster.tsx` | Toasts (info/success/error, max. 3, Info 4 s, Fehler bleiben), `dialogMinimized` |
| `store/conn.ts` | online/offline (fetch-TypeError, 502–504), Health-Probe alle 4 s, Verbindungsleiste |
| `store/table.ts` | eigener Tisch (`tableId`, `stamp`), zentrale Kick-Erkennung (`observe`, `observeList`, `verify`) |
| `store/{auth,nav,social}.ts` | Konto, Screen + letzte Konfiguration, Social-Poll |
| `components/ui/*` | Bausteine (Button, Kbd, Chip, Badge, Tabs, Segmented, Toggle, Checkbox, TextField, Table, Progress, XpRing, Avatar, StatusDot, EmptyState, Popover, Overlay, OptionRow, Wordmark, ConnectionBar) |
| `components/CardView.tsx`, `BoardModal.tsx` | Karte (Rang-Reihenfolge der Hervorhebung, Etiketten, getappt in h×h-Box, Fallback-Rahmen); Spiel-Dialog mit Pille (Tab), Space/Esc, `BoardModalRoot` als Portal-Ziel |
| `lib/{icons,motion,mana,tempo,mastery,format,useHotkey,sounds}` | Icons (lucide), Animationskonstanten, Mana/Rich-Text, Tempo-Texte, Meisterschaftskurve, Formate, Hotkeys, Töne |
| `shell/*`, `screens/home/*` | Navigation, Boot, Held lokal (`HomeLocal`: Schnellstart, letzte Partien, Meisterschaft) bzw. Server (`HomeServer` + `social/SocialSidebar`) |
| `screens/PlaySetupScreen.tsx`, `setup/*`, `decks/{catalog.ts,DeckPicker.tsx}` | Spiel-Setup, Deck-Katalog mit Cache, Deck-Auswahl (auswählen + Übernehmen) |
| `screens/DecksScreen.tsx`, `decks/*` | Decks, Import/Bearbeiten mit Auto-Vorschau und Zeilen-Hinweisen, Löschen |
| `screens/StatsScreen.tsx`, `stats/*` | KPIs, Formkurve, Tempo/Mulligans/Gegner, Deck-Tabelle mit aufklappbarer Kartenstatistik, Verlauf |
| `screens/{Login,Account,Admin,Lobby,Table}Screen.tsx` | Login (Scryfall-Art), Konto, Einladungen, Lobby (Zuschauen), Tisch (Plätze, Bots, Entfernen, Freunde einladen, Tisch-Chat) |
| `social/*` | Lobby-Chat (Systemzeilen), Freunde, Einladungskarte mit Countdown, Einladen-Popover |
| `game/GameScreen.tsx`, `layout.ts` | Brett-Gerüst; Größen per `useBoardLayout` (kompakt bei < 1440×900), Hotkeys, Ausgeschieden-Banner |
| `game/{TopBar,PhaseBar,PromptBar,ActivityIndicator}` | Kopf (Runde, Zug-Chip, Phasen), Prompt-Leiste mit Status-Chip, Kontext, Knöpfen; Zuschauer-Leiste |
| `game/{OpponentPod,PlayerInfo,MyArea,Battlefield,Hand,ZoneViewer,LifeTotal,ZoneCounter,CommanderDamage,CommandZone,ManaPool}` | Spielflächen; Hand ohne Fächer (beim Zuschauen verdeckt) |
| `game/boardDecor.ts`, `interaction.ts`, `promptActions.ts` | Kampf-/Ziel-Etiketten + Pod-Chips; **Klicklogik** (unverändert: Klick = Engine fragt, Shift = markieren); Knöpfe/F-Tasten pro Prompt |
| `game/{StackPanel,ZoomPanel,SidePanel,LogPanel,ChatPanel,RevealPopups,FxLayer}` | Stapel, Kartenvorschau, Verlauf (Tabs, Spielerfarben), Chat (Zuschauer nur lesend), Einblendungen, FX |
| `game/PromptDialogs.tsx`, `dialogs/*` | Dialoge (Starthand, Fähigkeit ×N, Ersatzeffekte, Ziele, Menge, Stapel, Karten) |
| `game/{PauseMenu,GameOverOverlay}` | Pause (Optionen, Aufgeben), Spielende (XP-Ring, Meisterschaft, Zuschauer-Variante) |

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
| `tools/steps-social.json` | Server-Modus: Startseite mit Lobby-Chat/Freunden/Einladungs-Toast, Namens-Popup, Tisch mit „Freunde einladen“, Chat verlassen (`"show": true` – versteckt bleiben Screen-Wechsel hängen; Testdaten vorher per Skript anlegen; Vite gegen andere Engine: `MAGELITE_ENGINE=http://127.0.0.1:7400`) |
| `tools/shot.cjs` (v2), `steps-redesign-{board,meta,meta-leer,minsize,online}.json`, `social-seed.mjs`, `wait-images.js`, `proto-ready.js`, `steps-proto-*.json` | Redesign-Aufnahmen gegen isolierte Engines (Platzhalter `{{UI}}`/`{{PORT}}`/`{{OUT}}`, Sperre für 7317/fly), Testdaten für Online-Screens mit Steuerung auf 127.0.0.1:7499, Prototyp-Aufnahmen; siehe `DEVELOPMENT.md` |
| `tools/scenario-pilot.js`, `tools/steps-necro.json`, `steps-attack-undo.json`, `steps-gemstone.json`, `steps-fx.json`, `steps-modal-hover.json` | Szenario-Screenshots (Dev-Engine): ×5-Picker + Stapel, „Alle angreifen“ → Abbrechen/Zurücksetzen, Starthand-Dialog, Ereignisleiste/Geisterkarte, Vorschau bei offenem Mulligan-Dialog (`window.__hold` steuert, was der Pilot offen lässt) |

## Skripte (`scripts/`) und Server-Dateien

`build.ps1` (alles bauen, prüft Java/Node) · `import-xmage.ps1` (XMage-Distribution → `vendor/xmage`) ·
`bootstrap-gradle.ps1` (Wrapper neu erzeugen) · `e2e-flow.mjs` (REST+WS-Test) · `e2e-login.mjs` (Server-Modus:
Konten, Sessions, E-Mail/Passwort, Nutzertrennung) · `e2e-online.mjs` (2 Menschen, Chat) · `e2e-tables.mjs` (Lobby,
Tisch-Chat) · `e2e-social.mjs` (Lobby-Chat, Freunde, Einladungen, Systemzeilen) · `e2e-spectate.mjs` (Zuschauen: Sicht, Lecks, Close-Codes) · `deploy-fly.ps1` (Health prüfen, `fly deploy`).
Repo-Root: `Dockerfile` (UI → Engine `installDist` → JRE 17, Engine-Jar vor `lib/*`), `.dockerignore`, `fly.toml`
(performance-2x/4 GB, Auto-Stop, Volume `/data`, Health-Grace 300 s). Betrieb: `docs/SERVER.md`.
