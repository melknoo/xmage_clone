# Code-Landkarte

Wie die Implementierung aufgebaut ist (Engine auf Branch `forge`, ab 0.2: **Forge**). Die ursprüngliche Analyse der XMage-Fassung steht
historisch in `docs/archive/xmage-architecture.md`; dieses Dokument beschreibt, was **tatsächlich gebaut** wurde.

## Prozesse

```
Electron (desktop/src/main.cjs)
  ├─ spawn (cwd = Datenordner): java -Xmx3g -cp engine/lib/magelite-engine.jar;engine/lib/* dev.magelite.Main --port=0 --data=%APPDATA%\MageLite\engine --forge=<resources/forge> --ui=… --parent-pid=…
  │         stdout-Zeile "MAGELITE_READY {port, token, bootMs}" → Fenster lädt http://127.0.0.1:<port>/?port=…&token=…
  └─ BrowserWindow (React-UI aus ui/dist, von der Engine ausgeliefert)
        ├─ REST  /api/*, /img/*         (Token als Query oder Header X-MageLite-Token)
        └─ WS    /ws/game/{id}?token=…  (JSON-Nachrichten mit Feld "t")
```

- Arbeitsverzeichnis der Engine = Datenordner (Electron `cwd: dataDir`, Gradle-Tasks `engine/run`, Docker `WORKDIR /data`):
  Das Forge-Profil (`vendor/forge/forge.profile.properties`) hat relative Pfade `forge-data/…`; `ForgeBoot` meldet sonst
  einen Fehler. `--forge=<dir>` (bzw. `-Dmagelite.forge`, Dev-Standard `vendor/forge`) ist das Forge-Home mit `res/`,
  `forge.profile.properties`, `manifest.json`; es entsteht nur durch `scripts/import-forge.ps1`. Es gibt keine
  Karten-DB: Forge lädt die Kartenskripte bei jedem Start (wenige Sekunden).
- Die Engine beendet sich selbst, wenn der Electron-Prozess weg ist (Parent-Watchdog in `Main`).
- Dev: `gradlew run` → Port 7317, `--dev` (kein Token, Konsolen-Log), Daten in `engine/run`.
- **Server-Modus** (fly.io, `docs/SERVER.md`): `java … dev.magelite.Main --server --host=0.0.0.0 --port=8080
  --data=/data --forge=/app/forge --max-games=1 --idle-exit-min=10`. Kein Token; Login per Session-Cookie `ml_sess` (Einladungscode
  oder E-Mail + Passwort; Legacy-Cookie `ml_code` gilt übergangsweise weiter), die UI spricht dieselbe Origin an (`wss://`). Dev: `gradlew runServer` + Vite-Proxy (`http://localhost:5173/` ohne `?port=`).

## Engine (`engine/src/main/java/dev/magelite`)

| Paket/Datei | Aufgabe |
|---|---|
| `Main` | Argumente (`--forge`, `--data`, `--seed-db`, `--server`, `--host`, `--max-games`, `--idle-exit-min`), Logging, `ForgeBoot.init`, SQLite, `DeckMigration.run` (vor dem HTTP-Start), Owner-Konto aus `MAGELITE_OWNER_CODE`, Module registrieren, READY-Zeile, Parent-Watchdog (lokal), Leerlauf-Exit + Abbruch verwaister Spiele (`GameHost.disconnectedForMs`, Server) |
| `api/Auth` | Before-Handler für `/api/*`, `/img/*`: lokal Token → `User.LOCAL`; Server Session-Cookie `ml_sess` (SHA-256 → `sessions`) bzw. Legacy `ml_code` → Konto; Login-Rate-Limit, Origin-Prüfung für WS |
| `auth/User`, `InviteCodes`, `Passwords`, `AccountService`, `AuthRoutes` | Nutzer-Record (lokal immer 1; `email`, `hasPassword`, `tier` friend/public, `friend()`); Codes erzeugen/normalisieren/hashen; PBKDF2-Passwörter + Session-Tokens; Konten (anlegen, rotieren, entfernen, Owner-Bootstrap, `last_seen`, Sessions, E-Mail/Passwort setzen/ändern); `/api/me`, `/api/auth/*` (Login per Code **oder** E-Mail/Passwort, `register`, `account`), `/api/admin/invites*` |
| `boot/ForgeBoot` | startet Forge einmal pro JVM (`init(forgeHome, data)`, auch `gradlew forgeCheck`): `LegacyCleanup`, Abgleich `manifest.json` ↔ eingebauter Forge-Commit (`magelite-version.properties`; Abweichung = Startabbruch), tinylog → `logs/forge.log` (ab WARN), `FModel.initialize` mit festen Prefs (Kartenskripte **eager**, Sentry/Bilder/Ton/Auto-Update aus, Forges Auto-Pass aus, kurze Prompttexte), Plausibilität (> 25 000 Karten, > 500 Editionen, Commander-Prädikat), KI-Profil „MageLite“ (`AI_PROFILE`: Forges „Default“ ohne zufällige Blocker-Trades, per Reflection eingetragen), Kennzahlen-Log `Forge <v> (<sha>): N Karten, M Editionen, … ms, Heap … MB` |
| `boot/HeadlessGui` | Forges `IGuiBase` ohne Oberfläche: EDT-Aufrufe inline (ThreadLocal), `invokeInEdtLater` von fremden Threads auf den seriellen Daemon `forge-edt`; ein Dialog, der hier landet, ist ein nicht abgebildeter Pfad (`UNMAPPED`-Log + `IllegalStateException`), blockierende Fragen gehören in `SeatGui` |
| `boot/LegacyCleanup` | löscht Reste der XMage-Zeit im Datenordner (`db/cards.h2*`, die alte Karten-DB), idempotent |
| `boot/LogConfig` | log4j-Konfiguration (Konsole WARN, im Dev-/Server-Modus INFO; Datei `logs/engine.log`, rollierend 10 MB × 3); Forge loggt über tinylog getrennt nach `logs/forge.log` |
| `api/HttpServer` | Javalin: Routen, Auth-Filter, `POST /api/games` (409 bei belegtem Tisch oder laufendem Relay-Spiel), WebSocket-Handling (Cookie/Origin, nur der Besitzer des Spiels; Relay-Spiele → `RelaySession`, Verkehr 1:1 an den Host-Link), Sitzungen pro Nutzer schließen, Module; `runningGames()` = Server- + Relay-Spiele |
| `api/GameMessages` | Client→Server-Spielnachrichten (`t`) auf einen `GameHost`-Sitz anwenden – gemeinsam für WebSocket und Host-Link |
| `api/Outbox` | sendet pro Verbindung auf eigenem Thread über ein `Transport` (WebSocket oder Host-Link-Umschlag); aufeinanderfolgende States (auch `Raw`-JSON) werden zusammengefasst |
| `api/DownloadRoutes` | Server-Modus, öffentlich: `/api/download/info` → `{available, version, url}`; `url` = `MAGELITE_DOWNLOAD_URL` mit `{v}` = Server-Version (GitHub-Release, hochgeladen von `scripts/publish-setup.ps1`) |
| `auth/SignupService`, `SignupRoutes`, `Mailer` (Brevo/Outbox), `Turnstile`, `Limits` | Selbstregistrierung (Server-Modus, Schalter `MAGELITE_SIGNUP`): Captcha, Grenzen pro IP/Tag/gesamt (DB), Bestätigungs-/Reset-Tokens (`email_tokens`), Hinweis-Mail statt Konto-Aufzählung, Aufräumen unbestätigter Konten; `Limits.requireServerGames` (403 `publicLimit`) und `BudgetExhausted` (503 `budget`) |
| `admin/UptimeBudget` | Laufzeit pro Monat (`uptime_month`, Tick aus `Main.watchIdle`), `exhausted()`, `counts(user)`; Warn-Callback bei 80/100 % (Mail an den Owner) |
| `relay/RemoteGameSpec` | Beschreibung eines Spiels auf dem Rechner eines Gastgebers (Sitze mit Decktext v2 im Feld `dck` in `start`, Spieler-ids ab `started`/`resume`) |
| `relay/HostLinks` | fly-Seite des Host-Links: `/ws/host` (Cookie + Origin wie `/ws/game`, Header `X-MageLite-Engine: forge/1` – fehlt er oder weicht ab, Close 4426 „MageLite auf diesem Rechner aktualisieren“ –, ein Link je Konto, 4000 ersetzt, 4408 ohne Ping), `start(userId, spec)` → Future, `send`/`sendIn` (Umschläge `attach/detach/in/abort`) |
| `relay/RemoteGames` | fly-Seite: Relay-Spiele ohne `GameHost` (Sitze, Spieler-Outboxes, `turn` aus den States, `conceded` aus `leave`); `onFinished` verbucht XP je fly-Nutzer über `GameRecorder.record(GameResult, SeatResult)` und sendet das `gameOver` mit `reward` selbst; Host weg → `hostLink` an die Spieler, nach `-Dmagelite.hostGraceMs` (60 s) Abbruch mit Fehler (keine Statistik); `resume` baut Spiele nach fly-Neustart ohne Tisch neu auf |
| `relay/HostLinkClient` | Host-Seite (lokale Engine): ausgehender JDK-WebSocket `wss://<server>/ws/host` mit `Cookie: ml_sess` + `Origin`, ein Sender-Thread, Fragmente puffern, Ping 20 s / Reconnect mit Backoff + `resume`; `start` lädt Decks aus dem Decktext (v2) und startet über die lokale `GameRegistry(1)` mit `RewardHook = null`; `finished` mit `GameRecorder.resultOf` (inkl. `StatsSink`-Daten); Wachen: Spiel ohne Spieler > 10 min, Link > 3 min weg → Abbruch |
| `relay/RelaySink`, `HostLinkRoutes` | `GameHost.Sink` je Sitz → `out`-Umschlag (filtert `gameOver`); lokale Steuerung `GET/POST/DELETE /api/host/link`, `POST /api/host/link/reconnect` (Test) |
| `api/Json` | gemeinsamer Jackson-`ObjectMapper` |
| `game/GameHost` | **Herzstück**: ein Forge-Spiel (1–4 Menschen, Rest Bots; für Spikes auch nur Bots). Baut `Match`/`Game` selbst (kein `HostedMatch`) und führt sie auf dem Spiel-Thread `Game-ml-<id>` aus. **inbox:** WS-/Relay-Threads prüfen nur und reihen Kommandos ein; der Spiel-Thread arbeitet sie in der Park-Schleife (`park`, solange er in einer Frage wartet) und an Safe-Points (`safePoint`, vor jeder Controller-Entscheidung) ab, nur er fasst Forge-Objekte an. Dazu: ein offener Prompt je Spiel (`promptSeat`, nur der Besitzer antwortet), State-Drossel (60 ms), Makros (Mehrfach-Angriff/-Block, `combatReset`, `repeat`, Ersatzeffekt-Gruppen), F-Tasten und Einstellungen (`action`), Spielende/Platzierungen/Belohnungs-Hook, Schleifen-Schutz (> 3000 Entscheidungen je Zug → Remis, > 200 Antworten auf eine Frage → Autopilot), Aufgeben/Kick/Abbruch (`GameEnded` rollt den Spiel-Thread aus offenen Eingaben, Sitz auf Autopilot), Zuschauer, Aktivitäts-Wachhund. Pro Mensch ein `HumanSeat` (Sink, eigener State, Auto-Pay-/Passen-Zustand, gepasste Trigger, Aufgabe); `leave(seat)` = nur dieser Sitz gibt auf, ohne Menschen geben die Bots auf |
| `game/PromptBridge` | Forge-`Input`s und blockierende Dialoge → `PromptDto` (Frames `InputFrame`/`Dialog`, jede Frage hält den Spiel-Thread in `GameHost.park`), Client-Antwort → Forge-Aufruf (`selectCard`, `selectPlayer`, `useMana`, `selectButtonOk/Cancel` …): Priorität, Angriff/Block zweistufig mit synthetischer Verteidiger-/Angreiferwahl, Mana + Auto-Bezahlen, Einberufen, Mulligan/London, Ziele, Auswahllisten, Fähigkeit, Zahl, Piles, Schaden verteilen; Schutz vor ungültigen Angriffen/Blocks; Zähler für nicht abgebildete (`UNMAPPED`) und automatisch beantwortete (`AUTO`) Aufrufe |
| `game/SeatGui` | Forge-`AbstractGuiGame` je Mensch: zeigt nichts an, merkt Text, Knöpfe und wählbare Karten und macht aus jeder blockierenden Frage (`awaitInput`, Dialoge) ein Frame; Aufrufe fremder Threads (Forges `awaitNextInput`-Timer) werden ignoriert |
| `game/HumanController` | `PlayerControllerHuman` je Mensch: hängt MageLites Politik an die Entscheidungs-Tore (Safe-Point, Autopilot nach Aufgabe, `AutoPassPolicy`, `repeat`-Makro, Kampf für die Prompts) |
| `game/ForgeBot` | `PlayerControllerAi` je Bot (Profil „MageLite“): Safe-Point vor jeder Entscheidung, „denkt …“-Status, Tempo-Pausen nach sichtbaren Aktionen und nach Angriff/Block |
| `game/AutoPassPolicy` | wann ein Mensch automatisch passt (Forges eigenes Auto-Pass ist aus): `stopReason` (Ziel auf mich, Gegner-Upkeep), F-Tasten (`SkipMode`), Passen nach eigenem Zauber, gleiche Stapelobjekte (Signatur), Stopps in eigenen Mains und der Gegner-Endphase, Auto-Passen ohne Nicht-Mana-Aktion, `nextStop` (Ziel von „Weiter“) |
| `game/ForgeEvents` | Forge-Ereignisse (`game.subscribeToEvents`) → FX (`GameHost.onFx`, gebündelt als WS `events`; verdeckte Karten nur an den Besitzer, Token-Tode ×N) und `StatsSink` (Starthand, gezogen, gewirkt, Länder, Schaden, eigene Züge, Mulligans); beobachtet nur, blockiert und fragt nie |
| `game/ReplacementAssist` | Ersatzeffekt-Wahl: gleiche Effekte (Regeltext) zu Gruppen zusammenfassen (`choice.groups`), „you may“ = optional; `GameHost.replacement` nutzt das für 1-Klick, „Keinen anwenden“ und „für dieses Spiel merken“ (Forge: `chooseSingleReplacementEffect`/`confirmReplacementEffect`) |
| `game/TempoSettings` | Presets BLITZ/NORMAL/BEDACHT/MAX: KI-Denkzeit (`Game.AI_TIMEOUT`) und Pausen nach Aktion/Kampf, live änderbar (von allen Bots geteilt) |
| `game/ManaColor` | Manafarbe der Client-Antwort (`mana.type`: WHITE … COLORLESS) ↔ Forge-`ManaAtom` |
| `game/ScenarioHooks` | Eingriffe eines Test-Szenarios in den Spielstart (Interface, nur Dev/Spikes; Implementierung `spike/Scenarios`) |
| `game/GameRegistry`, `GameSetup` | laufende Spiele (ein Sitz pro Nutzer, insgesamt `maxGames`; voll → `BusyException`/409); `GameSetup(seats, tempo)` mit `SeatSpec.human(userId, name, deck, deckId)` / `SeatSpec.bot(deck)` in Tischreihenfolge, Komfort-Konstruktor 1 Mensch + 3 Bots |
| `view/ForgeViewMapper` | lebendes Forge-`Game` + Views → `StateDto`, nur auf dem Spiel-Thread: Sicht eines Sitzes (`map`) oder öffentlich für Zuschauer (`mapPublic`, viewer = null); Sitzordnung = echte Zugfolge, Commander-Steuer/-Schaden, `playable`/`actions` nur für den Prompt-Sitz, Stapel-Ziele mit Namen, Kampf, `revealed`/`lookedAt` |
| `view/IdCodec` | Forge-int-ids ↔ Wire-`UUID` (`msb` je Spiel zufällig, `lsb` = Art << 56 \| permutierte id: Karte, Spieler, Fähigkeit, Stapelobjekt, Option, Kartenrückseite); die Permutation verbirgt Forges sequenzielle Karten-ids (sonst verriete die id einer verdeckten Karte ihre Deck-Position) |
| `view/WireNames` | Forge-Werte → die Namen der XMage-Zeit, die UI, Spikes und Werkzeuge erwarten (Step, Phase, Zonen, Kartentypen, Farben, Mana, Marken, Fähigkeitsart) |
| `view/ForgeText` | glättet Forge-Texte: Objekt-Nummern „Mountain (341)“ und Auslöser-Kontext „[Attacker: …]“ entfernen |
| `view/RichText` | Log-/Prompt-Texte mit Markup (`<br>`, Objekt-Verweise) → sichere Segmente `{text}`/`{obj,text,color}`/`{br}` |
| `view/dto/*` | DTOs: `StateDto`, `PlayerDto`, `CardDto`, `PermanentDto`, `CommandDto`, `PromptDto`, `TargetRefDto`, `Messages` |
| `deck/CardLookup` | einzige Deck-Klasse mit Forge-Kartenzugriff: Name (+ Set + Nummer) → `PaperCard`; Set-Auflösung mit eigenem Index (Scryfall-Code zuerst, dann Forge-Code/Code2/Alias; **gespeichert wird der Scryfall-Code**, groß), Kartenart, Farbidentität, Regeltext, Namensliste |
| `deck/TextDeckParser` | Textlisten parsen (Decktext v2, Moxfield, Archidekt, MTGA, MTGO, Forge-`.dck`, altes XMage-`.dck`), Karten über `CardLookup` auflösen, Commander erkennen, `toText()` = Decktext v2 (`Commander`/`Deck`, `1 Name (SET) NUM`); Issue-Arten nur noch `unknown` (kein `XmageUnfinished`) |
| `deck/DeckText` | rein textuelle Umformung gespeicherter Decktexte (bearbeitbare Ansicht für `/api/decks/{id}/text`, Legacy-Konverter) |
| `deck/DeckUrlImporter` | Archidekt/Moxfield-JSON → Textliste (409 „blocked“ → UI lädt über Electron) |
| `deck/DeckRoutes` | `/api/decks/parse`, `/api/decks/url`, `POST /api/decks`, `/api/decks/{id}/text` |
| `deck/DeckStore` | SQLite-Tabelle `decks` (gespeichert wird Decktext v2, `deck_format=2`; Ordner, Bracket manuell/Vorschlag, `sort_order`) |
| `deck/BracketAnalyzer` | Bracket-Vorschlag 2–4 (Game Changers, 2-Karten-Combos – Listen in `resources/brackets`, aus XMage übernommen –, Landzerstörung, Extra-Züge, Tutoren) |
| `deck/DeckLoader`, `LoadedDeck` | Decktext → Forge-`Deck` (Commander-Sektion) + Prüfung mit `DeckFormat.Commander.getDeckConformanceProblem` und Bannliste je Karte (`isLegalCard`; Forges Prüfung kennt sie nicht); unbekannte Karten → `valid=false`; `LoadedDeck.newDeck()` = Kopie pro Spiel |
| `deck/DeckMigration` | einmalige Umstellung gespeicherter Decks vom XMage-`.dck` auf Decktext v2 (`deck_format` 1→2): vorher `VACUUM INTO magelite.db.xmage-backup`, alter Text in `dck_legacy`, `updated_at`/Ordner/Sortierung/Meisterschaft bleiben; läuft nach `ForgeBoot` + `Db`, vor dem HTTP-Start, idempotent |
| `deck/DeckResolver`, `CardNameSuggester` | Deck-Angabe `{type:user\|sample\|random}` → `LoadedDeck`; „Meintest du …?“ für unbekannte Namen in der Import-Vorschau (Index aus Forges Karten-DB) |
| `deck/SampleDeckCatalog` | mitgelieferte Decks aus dem Classpath (`/sample-decks/INDEX` + Dateien, Index vom Gradle-Task `sampleIndex`), textuell gelesen; ids = relativer Pfad (seit der XMage-Zeit unverändert), Farben über `CardLookup` |
| `images/ImageService` | `/img/card`, `/img/token`, `/img/named`: Scryfall-Proxy mit Disk-Cache, Drossel 110 ms, 404-Merker 7 Tage |
| `stats/Db` | SQLite-Verbindung + Migrationen (`resources/db/migrations/V1`–`V9`, Liste in `Db.MIGRATIONS`) |
| `stats/StatsSink` | Spielereignisse jedes Menschen erfassen (ein Sink pro Spiel **und** Spieler; Starthand, gezogen, gewirkt, Länder, Schaden, eigene Züge); gespeist von `game/ForgeEvents` |
| `game/ChatText` | Chat-Regeln (Steuerzeichen raus, 300 Zeichen, 5 Nachrichten / 5 s) für Spiel-, Tisch- und Lobby-Chat |
| `social/SocialService` | Server-Modus: Lobby-Chat (≤ 100, nur im Speicher, `seq`-Cursor), Präsenz (Poll < 15 s = online), Freundes-Status `online/table/game/offline`, Tisch-Einladungen (10 min, nur an Freunde, nur sichtbar solange der Tisch in der Lobby einen freien Platz hat). Eigene Sperre, Tisch/Spiel/DB-Abfragen außerhalb davon |
| `social/FriendStore` | SQL für `friendships` (Paar `a<b`, Anfrage → `accepted_at`, Gegenanfrage = Annehmen) und `users.lobby_chat` |
| `social/SocialRoutes` | REST `/api/social`, `/api/friends`, `/api/tables/{id}/invite`; `SocialException` → 409 |
| `admin/AdminService`, `AdminRoutes` | Server-Modus, nur Admins (`Auth.requireAdmin`): Nutzerliste mit Kennzahlen (SQL über alle Nutzer) und Status (`SocialService.presence`), Nutzer-Detail (Partien, Decks, Sessions ohne Token), Server-Übersicht (`GameHost.humanSeats/startedAt`, Tische, Heap, Budget) und Eingriffe (inkl. `tier` setzen) (`AccountService.revokeSessions`, `GameHost.abort`, `TableManager.adminClose`) |
| `stats/GameRecorder` | Spiel pro menschlichem Sitz speichern (`games`/`game_card_stats` mit Schlüssel Spiel+Nutzer, `game_seats` einmal), XP/Meisterschaft an dessen Held/Deck → eigenes `Reward` im `gameOver` jedes Sitzes. Datenrein über `GameResult`/`SeatResult` (`resultOf(host, over)`), damit fly auch Relay-Spiele verbucht, die auf einem anderen Rechner liefen |
| `stats/ProfileService`, `Progression` | Held (Name, XP, Level, Titel), Level-Kurve, Meisterschaftsstufen |
| `stats/StatsRoutes` | `/api/profile`, `/api/stats/*`, `/api/history` |
| `spike/BotSpike`, `HumanSpike` | headless Tests (4 Bots, Argumente u. a. `--games --turnCap --tempo --parallel --validate` / automatischer Test-Spieler, `--humans=1..4`, `--spectate`, `--dumpJson`; `HumanSpike` prüft auch Zugfolge = Sitzordnung, zählt `events` und prüft, dass verdeckte Karten nie an Fremde gehen, meldet Antwort→State-Zeiten sowie `UNMAPPED`-/`AUTO`-Zähler; `--leave=prompt\|bot\|abort\|abortTarget\|abortRequiredTarget` testet Verlassen/Abbruch mitten in einer Frage; `--scenario=swarm` misst Trigger-Ketten, testet Mehrfach-Angriff und „Angriff zurücksetzen“; `--scenario=dredge` prüft Ersatzeffekt-Gruppen; `--scenario=necro` „5-mal aktivieren“ (`GameHost.repeat`); `--scenario=gemstone` Starthand-Aktion vor dem Spiel; `--scenario=convoke` Weiter-Ziel, F10-Schutz, Blaze X=2 auf dem Stapel, Einberufen per Klick nach „Länder automatisch“, Main-2-Stopp) |
| `spike/SpectatorCheck`, `PocDecks` | `HumanSpike --spectate`: In-Process-Zuschauer, der jede Nachricht auf Lecks prüft (Hand, Bibliothek, private Inhalte); Deck-Quelle der Spikes (Sample-Decks aus dem Classpath oder `--deckDir`) |
| `spike/BotArena` | KI-Vergleich zweier Forge-KI-Profile (A vs B, je 2 Sitze, Spiegel-Spiele mit getauschten Seiten; Profile: MageLite, Default, Cautious, Reckless, Experimental): Siege, Platzierungspunkte, ms/Zug, CSV in `run/arena/` (`gradlew botArena`) |
| `spike/Scenarios` | Test-Situationen als `ScenarioHooks` im Spielstart (Karten aus Forges Karten-DB per `GameAction.moveTo…`, nicht über `GameState`; `swarm`: 16 Scute Swarm + Länder; `dredge`: 7 Dredge-Karten im Friedhof, 3 Gruppen; `necro`: Necropotence im Spiel; `gemstone`: Gemstone Caverns auf der Hand, Bot beginnt; `convoke`: 5 Länder + 6 Kreaturen, Blaze und Guardian of Vitu-Ghazi auf der Hand); in der Engine nur mit `--dev` (`POST /api/games {scenario}`) |

**Entfallen mit dem Forge-Umbau** (steht nur noch in `docs/archive/xmage-architecture.md` und als „historisch, XMage“ in
`DECISIONS.md`/`STATUS.md`): `PromptMapper`, `GameViewMapper`, `AutoPayer`, `SpecialPay`, `NextStop`, `StackSig`,
`TargetCheck`, `HumanSettings`, `MageLiteHuman`, `MageLiteBot`, `FfaAttack`, `BotTuning`, `MageLiteMatch`,
`TrackingLondonMulligan`, `FxWatcher`, `StatsWatcher`, `XmageUnfinished`, `CardDbManager` und alle **Shadow-Klassen**
(`mage/**`: `GameStateEvaluator2`, `DatabaseUtils`, „The Mighty Thor“). Es gibt keine Ersatzklassen mehr: Karten-Fixes
sind gepatchte Kartenskripte unter `vendor/forge-overrides/cardsfolder/<x>/<name>.txt` (getrackt, Kommentarzeile
`# MageLite: <Grund>`), die `import-forge.ps1` ins `cardsfolder.zip` einbackt und in `manifest.json` (`overrides`) listet.

### Lebenszyklus eines Prompts

Das Spiel ist single-threaded: höchstens ein offener Prompt, er gehört einem Sitz (`promptSeat`).

1. Auf dem Spiel-Thread `Game-ml-<id>` ruft Forge einen Controller (`HumanController.chooseSpellAbilityToPlay`,
   `declareAttackers`, …) oder öffnet eine Eingabe/einen Dialog der `SeatGui` (`awaitInput`, Auswahl, Ja/Nein, Zahl …).
   Vor jeder Entscheidung läuft ein Safe-Point (`GameHost.safePoint`: inbox abarbeiten, State gedrosselt senden).
2. Bei der Priorität läuft zuerst ein laufendes Makro (`repeat`), dann `AutoPassPolicy`: `stopReason` (Ziel auf mich,
   Gegner-Upkeep) hält immer an und beendet F-Tasten-Passen. Sonst wird ohne Prompt gepasst, solange das F-Tasten-Ziel
   nicht erreicht ist, einmal nach dem eigenen Zauber, bei einem schon bepassten gleichen Stapelobjekt, bei leerem
   Stapel außerhalb der Stopps (eigene Mains, Endphase der Gegner) und im Auto-Passen ohne Nicht-Mana-Aktion (nie in
   den eigenen Mains). Sonst öffnet Forge die Eingabe (`InputPassPriority`).
3. `PromptBridge` macht daraus ein Frame (`InputFrame` bzw. `Dialog`) und `GameHost.park` hält den Spiel-Thread in einer
   Schleife: inbox-Kommandos ausführen, State **mit** `playable`/`actions` und danach `PromptDto` mit neuer `id` senden
   (im eigenen Zug bei leerem Stapel mit `nextStop`), bei Änderungen den Prompt erneut senden. Verschachtelte Fragen
   (z. B. Fähigkeitswahl nach einem Klick auf die Priorität) bilden einen Frame-Stapel; nur das oberste Frame hat den
   offenen Prompt.
4. Der Client schickt `{t:"respond", id, uuid|bool|int|str|mana}`. Der WS-Thread prüft nur die `id`
   (`compareAndSet`), sendet `promptClosed` und reiht die Antwort in die inbox; die Park-Schleife führt sie auf dem
   Spiel-Thread aus (`PromptBridge` → `selectCard`/`selectPlayer`/`useMana`/`selectButtonOk` …). Antworten kommen nie von
   einem anderen Thread an Forge, deshalb gibt es weder CALL-Thread noch verlorene Antworten.
5. Das Spiel läuft weiter; an den Safe-Points und nach Forge-Ereignissen (`ForgeEvents`) gehen gedrosselte States raus
   (max. alle 60 ms, Rest per `flushStateIfDirty`). Ein Sitz nach Aufgabe/Kick läuft auf Autopilot (sichere
   Standardantworten); nach Spielende wirft `park` `GameEnded` und rollt den Spiel-Thread aus offenen Eingaben.

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
| `activity` | Herzschlag 1/s vom Wachhund: `mode` (you/bot/human/engine/idle/stuck; `human` = ein anderer Mensch ist dran), `who`, `cpu` (% eines Kerns, alle Engine-Threads), `idleMs`, `recovered` (aus der XMage-Zeit, unter Forge immer 0) |
| `toast` | `level`, `rich` |
| `events` | `items[] {kind, objectId, name, card, from, to, playerId, ownerId, sourceId, sourceName, amount, token, combat, hidden, ts}` – Spielereignisse für Mini-Animationen/Ereignisleiste (`ForgeEvents`), kommen vor dem State, der sie widerspiegelt; `hidden` nur an den Besitzer |
| `chat` | `entries[] {ts, playerId, name, text}`; live eine Zeile, nach (Re-)Connect der Verlauf (≤ 100) als ein Bündel |
| `gameOver` | `placements[]`, `winnerId`, `turns`, `durationMs`, `reward` (XP-Aufschlüsselung, Level, Meisterschaft), `error` |
| `hostLink` | nur Relay-Spiele (Tisch auf dem Rechner des Gastgebers): `ok:false, sinceMs` = Verbindung zum Gastgeber weg (UI-Banner, Sockets bleiben offen, nach 60 s `gameOver` mit `error`), `ok:true` = wieder da (der Host spielt hello/state/prompt nach) |
| `error`, `pong` | |

**Host-Link** (`/ws/host`, Server-Modus; Gegenstelle `relay/HostLinkClient` in der lokalen Engine, Auth wie `/ws/game`):
Host → fly `started{spec} | error{tableId,msg} | out{g,u,m} | finished{result} | resume{games[]} | ping`,
fly → Host `start{spec} | attach{g,u} | detach{g,u} | in{g,u,m} | abort{g} | pong`. `m` ist die unveränderte Spielnachricht
(oben), `g` = Spiel-id, `u` = fly-Konto. Spieler hängen weiter an `/ws/game/{id}`; fly packt nur um. `gameOver` kommt
nie vom Host (fly baut es mit `reward` aus `finished`). Relay-Spiele zählen nicht gegen `--max-games`, aber in
`/api/health games`, Präsenz, Admin und Leerlauf-Exit.

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
| `action` | Aktion aus Whitelist, Namen wie zu XMage-Zeiten (F-Tasten `PASS_PRIORITY_*` → `AutoPassPolicy.SkipMode`, `HOLD_PRIORITY`, `MANA_AUTO_PAYMENT_*`, `RESET_AUTO_SELECT_REPLACEMENT_EFFECTS`, `CONCEDE`; `TRIGGER_AUTO_ORDER_*`, `REQUEST_AUTO_ANSWER_*`, `USE_FIRST_MANA_ABILITY_*` werden angenommen, sind aber ohne Wirkung) |
| `tempo` | `preset` |
| `autoPay` | offenen Mana-Prompt automatisch bezahlen |
| `combat` | Mehrfach-Angriff/-Block beim offenen Angriffs-/Block-Prompt: `ids` (markierte Kreaturen), `target` (Spieler/Planeswalker bzw. Angreifer) |
| `combatReset` | „Angriff zurücksetzen“: alle eigenen Angreifer wieder zurücknehmen (beim Angriffs-Prompt oder in der Verteidiger-Wahl nach „Alle angreifen“), als Makro auf dem Spiel-Thread |
| `repeat` | `id` (CHOOSE_ABILITY-Prompt), `uuid` (Fähigkeit), `times` (2–20): Fähigkeit N-mal aktivieren (Forge gibt dem Aktivierenden die Priorität zurück, `RepeatMacro` in `HumanController`); Ziele/Fragen/fremde Stapelobjekte beenden die Wiederholung (Toast) |
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
| GET | `/api/health` | Lebenszeichen `{ok, version, mode, games}` (`games` inkl. Relay-Spiele); im Server-Modus ohne Login |
| GET | `/api/me` | `{mode: local\|server, user{id,name,admin,email,hasPassword}, hostLink}` (`hostLink`: meine Engine ist angebunden → Tische auf dem eigenen Rechner) |
| GET | `/api/download/info` | Server-Modus, ohne Login: `{available, version, url}`; `url` aus `MAGELITE_DOWNLOAD_URL` (Setup liegt als GitHub-Release, nicht auf dem Server) |
| GET/POST/DELETE | `/api/host/link` | nur lokale Engine: Host-Link-Zustand (`connected`, `games[]`, dazu vom Server `userName` und eigener `table` – per Session abgefragt, 3 s Cache) / verbinden `{server, session}` (Electron meldet das `ml_sess`-Cookie) / trennen; `POST /api/host/link/reconnect` baut nur die Verbindung neu (Test); `POST /api/host/table/leave` schließt den eigenen Online-Tisch (Karte der lokalen Startseite) |
| POST | `/api/auth/login`, `/api/auth/logout` | `{code}` **oder** `{email,password}` → Session-Cookie `ml_sess` (Server-Modus; 401 falsch, 429 Rate-Limit); Logout löscht die Session |
| GET/POST | `/api/auth/options`, `/api/auth/signup\|verify\|resend\|forgot\|reset` | öffentlich: Stand der Registrierung `{signup, turnstileSiteKey, forgot}` / registrieren `{name,email,password,captcha}` / Link einlösen `{token}` (→ Session-Cookie) / Mail erneut / Reset-Link anfordern `{email,captcha}` / neues Passwort `{token,password}` (→ Session); Antworten verraten keine Konten |
| POST | `/api/admin/users/{id}/tier` | Admin: `{tier: friend\|public}` (nicht für Admins) |
| POST/PUT | `/api/auth/register`, `/api/auth/account` | Konto sichern `{email,password}` (eingeloggt, 409 wenn schon gesichert/E-Mail vergeben) / ändern `{current, email?, password?}` (Passwortwechsel beendet andere Sessions) |
| GET/POST | `/api/admin/invites` | Admin: Konten auflisten / anlegen `{name}` → `{id,name,code}` (Code nur einmal) |
| POST/DELETE | `/api/admin/invites/{id}/rotate`, `/api/admin/invites/{id}` | neuer Code / Konto entfernen (schließt dessen WebSockets, beendet sein Spiel) |
| GET | `/api/admin/users`, `/api/admin/users/{id}` | Admin: Konten mit Level/Titel, Spiele/Siege, Decks, Sessions, Status `online\|table\|game\|offline` / Detail mit letzten 15 Partien, Decks, Sessions |
| POST | `/api/admin/users/{id}/logout` | Admin: alle Sessions beenden, WebSockets schließen, Sitz aufgeben (Code bleibt gültig; nicht das eigene Konto) |
| GET | `/api/admin/server` | Admin: Version, Laufzeit, Heap, `maxGames`, online, laufende Spiele (Sitze, Bots, Zug, Zuschauer; `remoteHost` bei Relay-Spielen), Tische (`hosting`, `locked`), `hostLinks` |
| POST/DELETE | `/api/admin/games/{id}/abort`, `/api/admin/tables/{id}` | Admin: Spiel beenden / Tisch schließen (laufendes Tischspiel wird abgebrochen); 404 `{error}` wenn weg |
| GET | `/api/samples` | Sample-Decks (`id` = relativer Pfad) |
| GET/DELETE | `/api/decks`, `/api/decks/{id}` | eigene Decks |
| GET | `/api/decks/{id}/text` | Deck als bearbeitbarer Text |
| POST | `/api/decks/parse` | Vorschau `{text, name?, commanders?}` |
| POST | `/api/decks/url` | Import `{url, json?}`; 409 `{blocked:true, apiUrl}` wenn geblockt |
| POST | `/api/decks` | speichern `{id?, name, text, commanders?, source?, sourceUrl?}` |
| POST | `/api/games` | Spiel starten `{deck, bots[], tempo, humans?: [{userId, name?, deck?}]}` (weitere Menschen bis zur Lobby nur in der Dev-Engine; freie Plätze bis 4 werden mit Bots gefüllt); Deck-Spec `{type:"user",id}` / `{type:"sample",id}` / `{type:"random"}` |
| GET | `/api/games/current` | eigenes laufendes Spiel (für Reconnect); fremdes → 404 |
| PUT | `/api/tables/{id}/seats/{n}` | Gastgeber, nur LOBBY: `{kind:'BOT'\|'OPEN', deck?}`; `OPEN` auf einem Menschenplatz = **entfernen** (nie der eigene Platz; gesperrt bis zur nächsten Einladung) |
| POST | `/api/tables` | Tisch eröffnen `{name?, tempo?, hosting: SERVER\|REMOTE, password?}`; REMOTE nur mit angebundener Engine (409), Passwort = privater Tisch (Hash `tableId:pw`) |
| POST | `/api/tables/{id}/join` | beitreten `{password?}`; privater Tisch ohne/mit falschem Passwort → 403 `{needPassword:true}` (5 Versuche/min), offene Einladung des Gastgebers ersetzt das Passwort |
| POST | `/api/tables/{id}/start` | Gastgeber startet; REMOTE: Decks hier aufgelöst, Start auf dem Rechner des Gastgebers abgewartet (Tisch `starting`, max. 30 s), 409 mit Fehlertext des Hosts |
| GET | `/api/tables`, `/api/tables/{id}` | Tisch-Sicht: Plätze mit Deck-Infos, `mySeat`, `host`, `turn` (RUNNING), `spectators`, `canSpectate` (REMOTE: false), `hosting`, `locked`, `starting`, `hostLinkOk`, `chat[]` (nur Sitzende) |
| POST | `/api/tables/{id}/chat` | Tisch-Chat `{text}` (nur Sitzende, 409 sonst); die Zeilen (≤ 50) kommen in jeder Tisch-Antwort als `chat[]` mit |
| GET | `/api/social?after=<seq>` | Server-Modus, ein Poll für alles: `{chatIn, seq, msgs[] (nur > after, leer wenn draußen), members[], friends[{id,name,status,tableId?,tableName?}], incoming[], outgoing[], invites[]}`; setzt die Präsenz |
| POST/PUT | `/api/social/chat` | Lobby-Chat schreiben `{text}` (409 wenn draußen/Rate-Limit) / `{in: bool}` betreten/verlassen (pro Konto gespeichert) |
| POST/DELETE | `/api/friends`, `/api/friends/{id}/accept`, `/api/friends/{id}` | Anfrage `{name}` (exakt, Groß/klein egal) oder `{userId}` → `{state: outgoing\|friend}`; annehmen; ablehnen/zurückziehen/entfernen |
| POST/DELETE | `/api/tables/{id}/invite`, `/api/social/invites/{id}` | Freund an meinen Tisch einladen `{userId}` / Einladung ablehnen (Beitritt per `/api/tables/{id}/join` erledigt sie) |
| GET/PUT | `/api/profile` | Held; PUT `{name}` |
| GET | `/api/stats/overview`, `/api/stats/decks`, `/api/stats/decks/{id}/cards`, `/api/history?limit=` | Statistik |
| GET | `/img/card/{set}/{num}?size=&face=&name=`, `/img/token?name=&set=&n=&size=`, `/img/named?name=&size=` | Bilder |

### SQLite (`magelite.db`, Migrationen `V1__init.sql` … `V9__forge_decks.sql`)

`users` (id, name, code_hash, is_admin, last_seen, `email` (NOCASE, unique), `pw_hash` (PBKDF2), pw_set_at; 1 = lokal) ·
`sessions` (user_id, token_hash, via code|password, created_at, last_seen; Cookie `ml_sess` hält das Klartext-Token) · `profile` (id = Nutzer-id, name, xp_total) · `decks`
(`dck` = Decktext v2, commanders, colors, valid, mastery_xp, `user_id`; `folder`/`bracket`/`bracket_auto`/`bracket_info` V6, `sort_order` V7, `deck_format` (1 = XMage-`.dck`, 2 = v2) und `dck_legacy` V9) · `games` (PK `(id, user_id)`: Ergebnis, Platz, Tempo,
Mulligans, XP, end_reason je Mensch) · `game_seats` (pro Spiel einmal) · `game_card_stats` (PK `(game_id, user_id,
card_name)`: opening, drawn, cast, first_cast_turn) · `xp_ledger` (`user_id`) · `settings` (noch ungenutzt) ·
`friendships` (a < b, requested_by, accepted_at; ON DELETE CASCADE) · `users.lobby_chat` (1 = im Lobby-Chat) · V8: `users.tier`/`email_verified_at`/`created_ip`, `email_tokens`, `uptime_month`, `kv`.
Migrationsverlauf: V1 init · V2 users · V3 games_per_user · V4 accounts · V5 social · V6 deck_folder_bracket · V7 deck_order · V8 public_signup · V9 forge_decks (+ `DeckMigration`, Sicherung `magelite.db.xmage-backup` neben der DB).
Joins `game_card_stats` ↔ `games` immer über `game_id` **und** `user_id`. Neue Migration: Datei `V10__….sql` anlegen
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
| `shell/*`, `screens/home/*` | Navigation (`NavRail`: Logo/„Start“ → Startseite, Badge Lobby-Chat außerhalb von Start/Lobby, Version unten), Boot, Startseite lokal (`HomeLocal`: Held-Kopf, Schnellstart, letzte Partien, Meisterschaft) bzw. Server (`HomeServer` + `social/SocialSidebar`) |
| `screens/PlaySetupScreen.tsx`, `setup/*`, `decks/{catalog.ts,DeckPicker.tsx}` | Spiel-Setup, Deck-Katalog mit Cache, Deck-Auswahl (auswählen + Übernehmen) |
| `screens/DecksScreen.tsx`, `decks/*` | Decks in Ordner-Abschnitten, Bracket-Filter, Import/Bearbeiten mit Auto-Vorschau, Zeilen-Hinweisen, Ordner/Bracket (`DeckMetaMenu`, `FolderHeader`, `bracket.ts`), Löschen |
| `screens/StatsScreen.tsx`, `stats/*` | KPIs, Formkurve, Tempo/Mulligans/Gegner, Deck-Tabelle mit aufklappbarer Kartenstatistik, Verlauf |
| `screens/BudgetScreen.tsx`, `components/Turnstile.tsx`, `lib/localApp.ts` | „Kontingent aufgebraucht“ für gesperrte öffentliche Konten; Captcha-Widget (Skript von Cloudflare, ohne Site-Key nichts); „Allein üben“ für öffentliche Konten → lokale App bzw. Setup |
| `screens/{Login,Account,Admin,Lobby,Table}Screen.tsx` | Login (Tabs Code/E-Mail/Registrieren, Passwort vergessen, Reset per `#reset=`, Scryfall-Art), Konto, Admin (Tabs Nutzer/Einladungen/Server, `screens/admin/*`, Detail-Panel rechts), Lobby (Zuschauen, rechts `SocialSidebar`), Tisch (Plätze, Bots, Entfernen, Freunde einladen, Tisch-Chat) |
| `social/*` | Lobby-Chat (Systemzeilen), Freunde, Einladungskarte mit Countdown, Einladen-Popover |
| `game/GameScreen.tsx`, `layout.ts` | Brett-Gerüst; Größen per `useBoardLayout` (kompakt bei < 1440×900), Hotkeys, Ausgeschieden-Banner |
| `game/{TopBar,PhaseBar,PromptBar,ActivityIndicator}` | Kopf (Runde, Zug-Chip, Phasen), Prompt-Leiste mit Status-Chip, Kontext, Knöpfen; Zuschauer-Leiste |
| `game/{OpponentPod,PlayerInfo,MyArea,Battlefield,Hand,ZoneViewer,LifeTotal,ZoneCounter,CommanderDamage,CommandZone,ManaPool}` | Spielflächen; Hand ohne Fächer (beim Zuschauen verdeckt) |
| `game/boardDecor.ts`, `interaction.ts`, `promptActions.ts` | Kampf-/Ziel-Etiketten + Pod-Chips; **Klicklogik** (unverändert: Klick = Engine fragt, Shift = markieren); Knöpfe/F-Tasten pro Prompt |
| `game/{StackPanel,ZoomPanel,SidePanel,LogPanel,ChatPanel,RevealPopups,FxLayer}` | Stapel, Kartenvorschau, Verlauf (Tabs, Spielerfarben), Chat (Zuschauer nur lesend), Einblendungen, FX (`FxLayer`: Geisterkarte, Zahlen, Treffer-Funke Quelle → Ziel, Tod-Splitter; `Battlefield`: `animate-fx-enter`/`-lunge` aus `store/game.ts` `entered`/`lunging`; Dauern `lib/motion.ts` `fxTiming`, Dev-Zeitlupe `window.__mlFxSlow`) |
| `game/PromptDialogs.tsx`, `dialogs/*` | Dialoge (Starthand, Fähigkeit ×N, Ersatzeffekte, Ziele, Menge, Stapel, Karten) |
| `game/{PauseMenu,GameOverOverlay}` | Pause (Optionen, Aufgeben), Spielende (XP-Ring, Meisterschaft, Zuschauer-Variante) |

## Desktop (`desktop/`)

| Datei | Aufgabe |
|---|---|
| `src/main.cjs` | Fenster, Splash, Datenordner (nicht gepackt `%APPDATA%\MageLite-dev`, Fenstertitel „MageLite (Test)“, `MAGELITE_USER_DATA` überschreibt; die installierte App nutzt `%APPDATA%\MageLite`), Engine starten/neu starten (max. 2×), Fehlerdialog, IPC `magelite:fetchText` (nur Moxfield/Archidekt), `MAGELITE_AUTOSHOT`; **Online im selben Fenster:** `settings.json` (`serverUrl`, Standard `https://magelite.fly.dev`, `MAGELITE_SERVER_URL`), IPC `magelite:openOnline`/`openLocal`, `will-navigate` nur lokal + Server (Rest → Browser); **Host-Link:** liest nach Login das HttpOnly-Cookie `ml_sess` der Server-URL aus der Electron-Session (`cookies.on('changed')`, `did-navigate`, alle 30 s) und meldet es der Engine (`POST/DELETE /api/host/link`); `MAGELITE_DEV_SESSION=<Token>` setzt das Cookie beim Start (Host-Link-Test ohne Klicks) |
| `src/engine.cjs` | Pfade (Dev vs. gepackt; Forge-Home `vendor/forge` bzw. `resources/forge`), Java finden (`resources/jre`, `JAVA_HOME`, PATH), Start mit `cwd` = Datenordner und `--forge=`, Engine-Jar vor `lib/*` (Konvention aus der XMage-Zeit), READY-Handshake (Timeout 10 min), `--seed-db` für die Test-App |
| `src/preload.cjs` | lokale Origin: `window.magelite = {port, token, serverUrl, fetchText, openOnline}`; Server-Origin: nur `window.mageliteDesktop = {version, openLocal}` (nie Port/Token auf fremden Seiten) |
| `tools/relay-seed.mjs`, `tools/steps-relay.json`, `tools/steps-relay-home.json` | Relay-Screenshots: Testdaten (Bob bindet Engine Y an X, privater Tisch auf seinem Rechner, Setup-Dummy) + Aufnahmen Login/Download, Lobby, Tisch-eröffnen-Dialog, Passwort-Abfrage, Tisch; lokale Startseite (`?port=7412`, `window.magelite` per Step gesetzt) mit offenem Online-Tisch und „Tisch schließen“ |
| `tools/shot.cjs`, `tools/autoplay.js`, `tools/steps-autoplay.json` | Screenshot-Automatisierung + In-Page-Autopilot für UI-Tests |
| `tools/steps-swarm.json`, `tools/swarm-pilot.js` | Szenario `swarm` (Dev-Engine): Stapel ×N, Shift-Markieren, Mehrfach-Angriff, Pfeile, Verlauf ×N |
| `tools/steps-dredge.json`, `tools/dredge-pilot.js` | Szenario `dredge` (Dev-Engine): Ersatzeffekt-Dialog mit Gruppen + Hover, „Keinen anwenden“, „merken“ + Toolbar-Knopf |
| `tools/steps-server.json` | Server-Modus über den Vite-Proxy: Login-Screen, `#invite=`-Login, Einladungen, Spiel |
| `tools/steps-social.json` | Server-Modus: Startseite mit Lobby-Chat/Freunden/Einladungs-Toast, Namens-Popup, Tisch mit „Freunde einladen“, Chat verlassen (`"show": true` – versteckt bleiben Screen-Wechsel hängen; Testdaten vorher per Skript anlegen; Vite gegen andere Engine: `MAGELITE_ENGINE=http://127.0.0.1:7400`) |
| `tools/shot.cjs` (v2), `steps-redesign-{board,meta,meta-leer,minsize,online}.json`, `social-seed.mjs`, `wait-images.js`, `proto-ready.js`, `steps-proto-*.json` | Redesign-Aufnahmen gegen isolierte Engines (Platzhalter `{{UI}}`/`{{PORT}}`/`{{OUT}}`, Sperre für 7317/fly), Testdaten für Online-Screens mit Steuerung auf 127.0.0.1:7499, Prototyp-Aufnahmen; siehe `DEVELOPMENT.md` |
| `tools/scenario-pilot.js`, `tools/steps-necro.json`, `steps-attack-undo.json`, `steps-gemstone.json`, `steps-fx.json`, `steps-modal-hover.json` | Szenario-Screenshots (Dev-Engine): ×5-Picker + Stapel, „Alle angreifen“ → Abbrechen/Zurücksetzen, Starthand-Dialog, Ereignisleiste/Geisterkarte, Vorschau bei offenem Mulligan-Dialog (`window.__hold` steuert, was der Pilot offen lässt) |

## Skripte (`scripts/`) und Server-Dateien

`build.ps1` (alles bauen, prüft Java/Node, importiert Forge, wenn `vendor/forge/manifest.json` fehlt oder nicht zu `FORGE_COMMIT` passt) · `import-forge.ps1` (Forge am gepinnten Commit holen – sparse/shallow –, mit Maven bauen (lädt Maven bei Bedarf selbst), Jars + `res` nach `vendor/forge`, `manifest.json`, Overrides ins `cardsfolder.zip`; Wachen: kein `Sentry.init`, ≥ 25 000 Kartenskripte; `-Commit`, `-FullRes`, `-KeepScratch`) · `package.ps1` (Forge-Prüfung, `SOURCE.txt`, jlink-JRE, Smoke-Start der Engine, electron-builder) · `release.ps1` (Version +1, Setup, installieren, optional `-Fly`) ·
`bootstrap-gradle.ps1` (Wrapper neu erzeugen) · `e2e-flow.mjs` (REST+WS-Test) · `e2e-login.mjs` (Server-Modus:
Konten, Sessions, E-Mail/Passwort, Nutzertrennung) · `e2e-online.mjs` (2 Menschen, Chat) · `e2e-tables.mjs` (Lobby,
Tisch-Chat) · `e2e-social.mjs` (Lobby-Chat, Freunde, Einladungen, Systemzeilen) · `e2e-spectate.mjs` (Zuschauen: Sicht, Lecks, Close-Codes) · `e2e-relay.mjs` (startet selbst zwei Engines: Host-Link, privater Tisch auf dem eigenen Rechner, Relay-Spiel mit Belohnung, Reconnect, Admin-Abbruch, Host-Ausfall; `RELAY_ONLY_START=1` nur Engines) · `deploy-fly.ps1` (Forge-Wache: `manifest.commit` = `FORGE_COMMIT`, `cardsfolder.zip` und `lib` vorhanden; Health prüfen, `fly deploy`, auf die neue Version warten) · `publish-setup.ps1` (Setup als Release-Asset nach `melknoo/magelite-releases` per `gh`, Link prüfen; von `release.ps1 -Fly` vor dem Deploy aufgerufen) · `e2e-signup.mjs` (startet selbst eine Engine: Registrierung, Limits, Reset, öffentliche Grenzen, Budget, Aufräumen, Weck-Schutz).
Repo-Root: `Dockerfile` (UI → Engine `installDist` → JRE 17; Forge wird **nicht** im Docker gebaut: `vendor/forge/{lib,res}`
kommen aus dem Build-Kontext, Start mit `--forge=/app/forge`, `WORKDIR /data`), `.dockerignore`, `fly.toml`
(performance-2x/4 GB, Auto-Stop, Volume `/data`, Health-Grace 420 s). Lizenzen: `LICENSE`, `LICENSES/`
(`THIRD-PARTY.md`). Betrieb: `docs/SERVER.md`.
