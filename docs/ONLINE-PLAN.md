# Plan: Online-Mehrspieler für MageLite (fly.io)

> **Stand 2026-10-02: geplant, noch nicht umgesetzt.** Abgestimmt mit dem Nutzer. Umsetzung in Etappen E1–E5;
> nach jeder Etappe `docs/STATUS.md` aktualisieren und hier abhaken.
>
> Fortschritt: [ ] E1 · [ ] E2 · [ ] E3 · [ ] E4 · [ ] E5

## Kontext

MageLite ist heute ein lokales Goldfish-Programm: 1 Mensch gegen 3 Bots, Engine (Java/Javalin) auf `127.0.0.1`,
UI in Electron, ein Spiel gleichzeitig, ein Held (`profile id=1`). Ziel: **mit Freunden übers Internet an einem
Commander-Tisch spielen** (2–4 Menschen, freie Plätze mit Bots). Das soll für Freunde ohne Installation gehen, und
die Bots dürfen nicht langsamer werden.

Festgelegt (Rückfragen vom 2026-10-02):
- **Hosting: fly.io.**
- **Jeder Freund hat ein eigenes Konto** (über seinen Einladungscode): eigene Deckbibliothek, eigener Held mit
  XP/Level/Titel, eigene Deck-Meisterschaft und eigene Statistik.

## Hosting-Bewertung (Kurzfassung)

| | Laptop (Linux Mint) + Tailscale Funnel | fly.io |
|---|---|---|
| CPU | Pentium 3556U, 2 × 1,7 GHz (laut Runebound `docs/SERVER_SETUP.md`). Pro Kern etwa ⅓ so schnell wie ein aktueller PC. Teilt sich die CPU mit dem Runebound-Server. | `performance-2x`: 2 eigene vCPUs, nicht gedrosselt |
| RAM | 7,7 GiB, mit Runebound geteilt | 4 GB, bei Bedarf auf 8 GB skalierbar |
| Kosten | 0 € | ca. 0,10 $/h nur während gespielt wird (Auto-Stop), Volume ca. 0,45 $/Monat. Bei rund 20 Spielstunden im Monat sind das etwa 2–3 $ |
| Adresse | `…ts.net:8443`, weil Port 443 schon Runebound gehört | `https://<app>.fly.dev` |
| Erreichbarkeit | Nur wenn Laptop, WLAN und Starlink laufen | Immer; die Maschine startet beim ersten Aufruf (ca. 5–10 s) |
| Betrieb | systemd + Funnel, Muster von Runebound | `fly deploy` vom PC, ohne lokales Docker |

**Ergebnis: fly.io.** Die XMage-KI rechnet single-threaded mit Zeitbudget. Auf dem Pentium würde sie spürbar
langsamer und schwächer spielen. `shared-cpu`-Maschinen fallen ebenfalls aus, weil fly sie auf ca. 6 % Grundlast
drosselt. Der Server-Modus wird trotzdem plattformneutral gebaut (Docker-Image), damit ein Umzug auf den Laptop
später möglich bleibt.

## Zielbild: so kommen Freunde rein

1. Du legst in der Web-UI unter **„Einladungen“** einen Freund an. Die App zeigt einmalig einen Code
   (`XXXX-XXXX-XXXX-XXXX`, 80 Bit, gleiches Schema wie Runebound) und einen Link
   `https://<app>.fly.dev/#invite=XXXX-…`.
2. Der Freund klickt den Link. Er ist sofort angemeldet, und ein Cookie merkt sich die Anmeldung für ein Jahr.
   Einen Code von Hand eintippen geht ebenfalls.
3. Er importiert sein Deck (Archidekt-Link oder Textliste) in **seine** Bibliothek.
4. In der **Lobby** siehst du offene Tische. Einer eröffnet einen Tisch, die anderen treten bei. Jeder wählt sein
   Deck. Der Gastgeber besetzt freie Plätze mit Bots, stellt das Tempo ein und startet.
5. Gespielt wird im bekannten Spieltisch. Am Ende bekommt jeder seine XP und Meisterschaft, danach geht es
   „Zurück zum Tisch“ für eine Revanche.

Solo-Goldfish geht online weiterhin („Schnellspiel“ mit 1 Mensch + 3 Bots). Die lokale Electron-App bleibt
unverändert (eigener lokaler Held, Token, nur `127.0.0.1`).

## Architektur

```
Browser (Freund) ──HTTPS/WSS──▶ fly-proxy (TLS, Auto-Start/Stop) ──▶ Maschine fra, performance-2x/4 GB
                                                                        java dev.magelite.Main --server
                                                                        /data (Volume): db/cards.h2, magelite.db, cache/images
```

- **Gleiche Engine, neuer Modus `--server`.** Lokaler Modus bleibt bitgleich im Verhalten (implizit Nutzer 1).
- Die Engine liefert die UI selbst aus (`--ui`, gibt es schon). Die UI spricht im Server-Modus **dieselbe Origin**
  an, ohne Token; die Anmeldung läuft über ein Cookie, das auch bei `<img>` und WebSocket mitgeht.
- Das XMage-Spiel ist single-threaded, es gibt also **immer höchstens einen offenen Prompt pro Spiel**. Der
  Umbau auf mehrere Menschen bedeutet deshalb vor allem: Prompt einem Sitz zuordnen, State pro Sitz bauen,
  Nachrichten pro Sitz routen.

## Umsetzung in Etappen

Reihenfolge so gewählt, dass **früh auf fly gemessen** wird (E2), bevor der große Mehrspieler-Umbau kommt.

### E1 – Server-Modus, Konten, Einladungen

Engine:
- `Main.java`: neue Argumente `--server`, `--host=` (Default `127.0.0.1`, auf fly `0.0.0.0`) und `--max-games=` (Default 1).
  Im Server-Modus: kein Zufallstoken, kein `--parent-pid`. Beim Start legt die Engine einen Admin-Nutzer aus
  `MAGELITE_OWNER_CODE` / `MAGELITE_OWNER_NAME` an (fly-Secrets), falls es ihn noch nicht gibt.
- `api/HttpServer.java`: `app.start(host, port)`. Auth als Before-Handler:
  - lokal: Token wie bisher;
  - Server: Cookie `ml_code`, daraus SHA-256, Nutzer laden, `ctx.attribute("user")` setzen.
  - Frei zugänglich sind nur Statik, `/api/health` und `/api/auth/login`.
  - CORS `anyHost` nur im lokalen Modus.
  - WS-Upgrade: Cookie und `Origin` prüfen.
- Neu `auth/InviteCodes.java`: Code erzeugen (10 Zufallsbytes → Base32) und normalisieren (Groß/klein und
  Bindestriche egal, 0→O, 1→I, 8→B). Vorlage: Runebound `tools/server/invites.sh` und
  `scripts/net/net_auth.gd` (`normalize_code`).
- Neu `auth/AccountService.java` + Routen:
  - `POST /api/auth/login {code}` setzt das Cookie (HttpOnly, SameSite=Lax, Secure hinter HTTPS über
    `X-Forwarded-Proto`, 1 Jahr). Rate-Limit 10/min pro `Fly-Client-IP`.
  - `POST /api/auth/logout`, `GET /api/me` → `{mode, user{id,name,admin}}`.
  - Admin: `GET/POST /api/admin/invites`, `POST …/{id}/rotate`, `DELETE …/{id}`. Rotieren oder Entfernen
    schließt sofort die WebSockets dieses Nutzers.
- Migration `resources/db/migrations/V2__users.sql` (in `Db.MIGRATIONS` eintragen):
  - `users(id, name, code_hash UNIQUE, is_admin, created_at, last_seen)`, dazu Nutzer 1 = „lokal“.
  - `decks`, `games` und `xp_ledger` bekommen `user_id INTEGER NOT NULL DEFAULT 1`.
  - `profile.id` wird gleich der Nutzer-id; die bestehende Zeile 1 bleibt der lokale Held.
- Nutzerbezug einziehen (lokal immer 1):
  - `stats/ProfileService` (alle `WHERE id = 1`)
  - `deck/DeckStore` + `deck/DeckRoutes`, mit Besitzprüfung bei GET/DELETE
  - `stats/StatsRoutes`
  - `stats/GameRecorder` (`wonToday`/`winStreak` pro Nutzer)
- Request-Größenlimit setzen. Prüfen, dass `DeckUrlImporter` nur Archidekt/Moxfield-Hosts abruft (Schutz vor SSRF).

UI:
- `ui/src/api/client.ts`: ohne `window.magelite` und ohne `?port=` → `base = location.origin`, kein Token.
  `wsUrl()` wird dadurch automatisch `wss://`.
- `ui/src/App.tsx`: im Server-Modus `/api/me` abfragen; bei 401 → Login. `#invite=` aus der URL lesen,
  einloggen, dann die URL bereinigen.
- Neu `screens/LoginScreen.tsx` (Code-Eingabe) und `screens/AdminScreen.tsx` (Einladungen anlegen, Link kopieren,
  rotieren, entfernen, „zuletzt gesehen“).
- `ui/vite.config.ts`: Dev-Proxy für `/api`, `/img`, `/ws` → `7317`, damit Cookies im Dev-Modus same-origin sind.
- `engine/build.gradle.kts`: Task `runServer` (wie `run`, mit `--server --dev`, Owner-Code `DEV-OWNER-CODE` aus env).

### E2 – fly.io-Deploy und Leistungsmessung

- `Dockerfile` (drei Stufen):
  1. `node:22`: `npm ci && npm run build` in `ui/`.
  2. `eclipse-temurin:21-jdk`: `sh ./gradlew installDist` in `engine/`. `gradlew` hat im Repo keine
     Ausführungsrechte, deshalb `sh`; die LF-Zeilenenden erzwingt `.gitattributes` schon. Die XMage-Jars werden
     nur kopiert, nie neu gepackt (harte Regel 1).
  3. `eclipse-temurin:21-jre`: `/app/lib`, `/app/ui`, `/app/vendor/xmage/sample-decks`, `WORKDIR /data`. Start:
     `java -XX:MaxRAMPercentage=70 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true
     -Dfile.encoding=UTF-8 -cp "/app/lib/*" dev.magelite.Main --server --host=0.0.0.0 --port=8080 --data=/data
     --vendor=/app/vendor/xmage --ui=/app/ui`. `MaxRAMPercentage` statt `-Xmx3g`: Auf 4 GB wären 3 GB Heap plus
     Metaspace zu knapp, und die Heapgröße wächst automatisch mit, wenn die Maschine skaliert wird.
- `.dockerignore`: `node_modules`, `engine/run`, `engine/build`, `engine/.gradle`, `desktop`, `.git`, `docs`.
- `fly.toml`:
  - `primary_region = "fra"`
  - `[http_service]`: `internal_port = 8080`, `force_https`, `auto_stop_machines = "stop"`,
    `auto_start_machines = true`, `min_machines_running = 0`
  - Health-Check `/api/health` mit `grace_period = "120s"` (der erste Start baut die Karten-DB, ca. 40 s)
  - `[[vm]] size = "performance-2x", memory = "4gb"`
  - `[mounts] source = "magelite_data", destination = "/data"`
- Einmalig (in `docs/SERVER.md` dokumentieren):
  - `fly apps create <app>` (der Name ist global; falls `magelite` vergeben ist, einen Suffix anhängen)
  - `fly volumes create magelite_data -r fra -s 3`
  - `fly secrets set MAGELITE_OWNER_CODE=… MAGELITE_OWNER_NAME=…`
  - `fly deploy`
- `scripts/deploy-fly.ps1` (ASCII-only):
  1. `GET /api/health` meldet laufende Spiele; läuft eines, warnen und abbrechen, außer mit `-Force`.
  2. Dann `fly deploy`.
- **Leistungsmessung (Entscheidungspunkt):**
  1. Kurz `fly scale memory 8192`.
  2. Per `fly ssh console` in `/data`: `java -Xmx2g -Dmagelite.vendor=/app/vendor/xmage -cp "/app/lib/*"
     dev.magelite.spike.BotSpike --games=2 --tempo=BLITZ --turnCap=40`.
  3. Zeiten pro Zug und Heap-Spitze mit `docs/STATUS.md` vergleichen (lokal Ø ca. 5 s/Zug, Spitze ca. 2 GB).
  4. Danach zurück auf 4 GB.
  - Ziel: höchstens ca. 1,3 × die lokalen Zeiten. Sonst `performance-4x` prüfen.
  - Mit der Heap-Spitze `--max-games` festlegen (4 GB → 1 Spiel; für 2 parallele Tische auf 8 GB skalieren).
- Ab hier können Freunde schon **solo online goldfishen**, mit eigenem Konto.

### E3 – Mehrere Menschen in einem Spiel (Engine-Kern)

- `game/GameSetup.java` → `List<SeatSpec>`, jeweils `human(userId, name, deck, deckId)` oder `bot(deck)`, dazu
  `tempo`. Der lokale Modus und `POST /api/games` bauen daraus 1 Mensch + 3 Bots wie bisher.
- `game/GameHost.java`: neue innere Klasse `HumanSeat` mit Player, `userId`, `deckId`, Sink, `lastState`,
  `autoPass`, `autoPayDefault`, dem Auto-Pay-Zustand (bisher Felder Z. 149–154), `conceded` und `connected`.
  `Map<UUID, HumanSeat> humans` nach playerId. Ersetzt die Einzelfelder `human`/`humanId`
  (Z. 118–119, 138–154).
  - `openPrompt` bekommt einen Besitzer (`ownerSeat`). `onQueryEvent` (Z. 485–535): `controller` → `HumanSeat`;
    ohne Treffer läuft der Bot-Zweig wie bisher, sonst die Prompt-Pipeline für genau diesen Sitz.
  - `sendState` (Z. 697): für jeden menschlichen Sitz `GameViewMapper.map(game, seat.id, …)`, den es schon pro
    Betrachter gibt. `withPlayable` nur für den Sitz mit Prompt (das ist der teure Teil). `lastState` pro Sitz.
  - `emit` wird aufgeteilt in `broadcast` (Log, Status, Engine-Fehler) und `send(seat, …)` (State, Prompt,
    promptClosed, persönlicher Toast, gameOver mit eigener Belohnung).
  - `respond`/`action`/`autoPayNow`/`setAutoPass`/`setAutoPayDefault`/`dispatch` nehmen einen Sitz und prüfen,
    ob der Prompt diesem Sitz gehört. `CONCEDE` und die Mana-Modi gelten nur für den eigenen Spieler.
  - `Status` nennt dazu, auf welchen Menschen gewartet wird („Warte auf Anna“).
  - **WS `leave` = nur der eigene Sitz gibt auf**, nicht mehr `abort()` (Z. 346). Sind keine Menschen mehr im
    Spiel, geben die restlichen Bots sofort auf, damit keine CPU für ein reines Bot-Spiel verbraucht wird.
  - `buildGameOver` (Z. 740): `Placement.human` pro Sitz. Der Belohnungs-Hook läuft pro menschlichem Sitz, jeder
    bekommt sein eigenes `gameOver`.
  - Namen: Doppelte Spielernamen bekommen einen Suffix (die vorhandene `usedNames`-Logik, Z. 170).
- `stats/StatsSink` wird nach `(gameId, playerId)` geschlüsselt. `stats/StatsWatcher` sucht den Sink über den
  Spieler des Ereignisses und bleibt feldlos (harte Regel 4).
- `stats/GameRecorder.record(host, seat)` schreibt pro menschlichem Sitz: `games`-Zeile mit `user_id`,
  Kartenstatistik, XP auf den Helden des Nutzers, Meisterschaft auf dessen Deck. Die XP-Formel bleibt unverändert.
- `api/HttpServer.java` WS `/ws/game/{id}`: Nutzer → Sitz (`host.seatOf(userId)`), sonst Close 4403. Lokal ist
  Nutzer 1 der einzige Mensch.
- `spike/HumanSpike.java`: `--humans=N` (N automatische Testspieler mit eigenem Sitz und eigenem Autopiloten in
  einem Spiel). Das wird der Haupt-Regressionstest für das Routing.
- UI:
  - `store/game.ts`: eigene Platzierung über `myPlayerId` statt `p.human` (Z. 100).
  - `api/types.ts` nachziehen.
  - `game/GameScreen.tsx`: Wartehinweis, „Aufgeben“ statt „Spiel beenden“, Tempo nur für den Gastgeber
    (`hello` bekommt `canSetTempo`).
  - `game/GameOverOverlay.tsx`: alle Menschen markieren, Button „Zurück zum Tisch“.

### E4 – Tische und Lobby

- Neu `game/TableManager.java` (nur im Server-Modus; lokal bleibt `GameRegistry` mit „ein Spiel“).
  - `Table{id, name, hostUserId, seats[4]: offen | mensch(userId, deckSpec) | bot(deckSpec), tempo, LOBBY/RUNNING, gameId}`.
  - Grenzen: `--max-games`, ein Tisch pro Gastgeber, jeder Nutzer sitzt an höchstens einem Tisch.
  - Start: Alle Menschen haben ein Deck. Offene Plätze fallen weg (2–4 Spieler, auch 1 gegen 1).
- Deckauflösung aus `HttpServer.resolveDeck` (Z. 194–224) in einen `deck/DeckResolver` auslagern. `user`-Decks
  kommen aus der Bibliothek des Sitz-Inhabers.
- REST `/api/tables`:
  - `GET` (Liste), `POST` (eröffnen)
  - `GET {id}`, `POST {id}/join`, `POST {id}/leave`
  - `PUT {id}/seat` (eigenes Deck)
  - nur Gastgeber: `PUT {id}/seats/{n}` (offen/Bot + Deck), `PUT {id}` (Name, Tempo), `POST {id}/start`
- Synchronisation in der Lobby: **Polling alle 1,5 s** auf `GET /api/tables/{id}`. Das ist einfach und robust;
  einen eigenen WS-Kanal braucht es nicht.
- UI:
  - Neu `screens/LobbyScreen.tsx`: Tischliste, „Tisch eröffnen“, Schnellspiel.
  - Neu `screens/TableScreen.tsx`: 4 Plätze. Die Deckwahl wird aus `screens/PlaySetupScreen.tsx`
    wiederverwendet. Gastgeber-Steuerung, Start, Link `#table=<id>` zum Teilen.
  - `store/nav.ts`: neue Screens. Server-Modus: Lobby statt direkt Spiel-Setup.

### E5 – Feinschliff und Doku

- **Verbindungsabbruch:** Andere sehen „getrennt“. Nach 60 s darf der Gastgeber den Spieler aufgeben lassen,
  damit das Spiel nicht ewig auf einen Prompt wartet.
- **Datenmenge:** Prüfen, ob WebSocket `permessage-deflate` und gzip für REST/Statik aktiv sind; die Größe der
  States im späten Spiel messen. Volle States mit Regeltext können groß werden.
- **Doku:**
  - neu `docs/SERVER.md`: Betrieb, Deploy, Einladungen, Text „Für Freunde“, Kosten, Logs (`fly logs`)
  - `docs/CODEMAP.md`
  - `docs/DECISIONS.md` (fly statt Laptop, Cookie statt Token, Polling für die Lobby)
  - `docs/DEVELOPMENT.md` (`runServer`, Dev-Proxy)
  - `docs/STATUS.md`
  - `CLAUDE.md` (neue Befehle; Regel „Antworten nur vom Sitz-Inhaber des Prompts“)

## Wiederverwenden statt neu bauen

- `GameViewMapper.map(game, myId, …)` und `PromptMapper.map(game, event, playerId)`: beide sind schon pro
  Betrachter parametrisiert.
- `HumanSettings.defaults()`: pro `HumanPlayer` setzen (harte Regel 3).
- `api/Outbox`: ein Outbox pro Verbindung wie bisher.
- `ImageService`: Disk-Cache auf dem Volume, `Cache-Control: immutable` gibt es schon. Ohne Token in der URL
  bleibt der Browser-Cache stabil.
- `App.tsx`: Der Warte-Bildschirm mit Health-Polling deckt den Kaltstart von fly ab.
- `TextDeckParser`, `DeckUrlImporter`, `DeckLoader` und `SampleDeckCatalog` bleiben unverändert.
- Runebound: Code-Schema und Normalisierung; der Text „Für Freunde“ in `docs/SERVER_SETUP.md` als Vorlage.

## Sicherheit (öffentliche URL)

- Ohne gültigen Code erreicht man nur die Startseite, `/api/health` und den Login. Alles andere verlangt das Cookie.
- Der Owner-Code steht nur in den fly-Secrets, nie im Repo. In der DB liegen nur Hashes.
- Eine Einladung zu rotieren oder zu entfernen wirkt sofort: Das Cookie wird bei jeder Anfrage geprüft, und
  offene WebSockets werden geschlossen.
- Grenzen gegen Missbrauch: Login-Rate-Limit, Request-Größe, `--max-games`, 1 Tisch pro Nutzer, WS-Origin-Prüfung.
- Prompt-Antworten werden nur vom Sitz-Inhaber angenommen; Aktionen nur aus der bestehenden Whitelist.

## Verifikation

| Etappe | Prüfung |
|---|---|
| alle | `gradlew compileJava`, `gradlew test`, `cd ui; npx tsc -b`. Lokaler Modus unverändert: `humanSpike --games=2 --turnCap=32` (0 fehlgeschlagen, keine STALLs), `node scripts\e2e-flow.mjs`, Screenshot-Lauf `desktop/tools/shot.cjs` + `steps-autoplay.json` |
| E1 | `gradlew runServer` + `npm run dev`: Login per `#invite=`, falscher Code → 401, Rate-Limit greift, Admin legt Einladung an, zweiter Nutzer sieht nur eigene Decks und Statistik, Rotieren wirft ihn raus |
| E2 | `fly deploy` läuft durch. `curl https://<app>.fly.dev/api/health`. Erster Start baut die DB (Log), zweiter Start < 10 s. BotSpike-Messung (s. o.). Solo-Spiel im Browser bis zum Ende mit XP. Nach ca. 5–10 min ohne Verbindung zeigt `fly status` die Maschine als gestoppt, der nächste Aufruf startet sie |
| E3 | `humanSpike --humans=2` und `--humans=4`: 0 fehlgeschlagen, keine STALLs, jeder Sitz bekommt eigene Prompts. Neues `scripts/e2e-online.mjs` gegen `runServer`: legt 2 Einladungen an, loggt zwei Cookie-Sitzungen ein, startet ein Spiel, zwei WS-Autopiloten spielen. Einer gibt auf, das Spiel läuft weiter. Am Ende hat jeder Nutzer `reward` und eigene `games`-Zeilen |
| E4 | Screenshots von Lobby, Tisch und Admin. Ein Spiel mit UI-Autopilot (`window.__ml`), dazu ein zweiter menschlicher Sitz per `e2e-online.mjs`. Danach auf fly: zwei Browser (normal + privat) mit zwei Codes spielen ein Spiel |
| E5 | Trennen/Wiederverbinden mitten im Prompt; „aufgeben lassen“ durch den Gastgeber; State-Größe gemessen |

## Bewusst nicht enthalten (später, falls gewünscht)

- Zuschauer, Chat, Zug-Timer.
- Mehr Stopps in fremden Zügen (z. B. „Ende des Zuges anhalten“, für Spontanzauber gegen Menschen). Gehört zum
  geplanten Einstellungs-Screen.
- Online- und lokale Daten zusammenführen (z. B. lokale Decks hochladen). Bis dahin: Decktext kopieren und online
  importieren.
- Knopf „Online spielen“ in der Electron-App.
- Moxfield-Import im Browser: Der Electron-Fallback fehlt dort. Es bleibt der Hinweis „Text-Export einfügen“.
