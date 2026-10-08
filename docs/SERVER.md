# MageLite online (fly.io)

Betrieb der Engine als Web-Server, damit Freunde **ohne Installation im Browser** spielen. Stand 2026-10-05:
Server-Modus mit Konten und Einladungscodes (E1), das fly-Setup (E2), mehrere Menschen an einem Tisch (E3) und
die Lobby mit Tischen (E4) sind gebaut; Feinschliff (E5) steht aus, siehe `ONLINE-PLAN.md`. Jeder Freund hat
eigene Deckbibliothek, eigenen Helden und eigene Statistik. Es läuft **ein Spiel gleichzeitig** (4 GB RAM).

**So spielt man zusammen:** Unter „Spielen“ ist online die **Lobby**. Einer klickt „Eröffnen“ und ist Gastgeber,
die anderen treten in der Lobby bei oder über den **Einladungslink** des Tisches (`…/#table=XXXXXX`). Jeder wählt
sein Deck, der Gastgeber setzt auf freie Plätze Bots (oder lässt sie frei, dann fallen sie weg), stellt das
Bot-Tempo ein und startet. Nach dem Spiel führt „Zurück zum Tisch“ zur Revanche mit denselben Plätzen.
„Schnellspiel gegen Bots“ in der Lobby ist das alte Solo-Setup.

## Wie es funktioniert

```
Browser ──HTTPS/WSS──▶ fly-proxy (TLS, Auto-Start/Stop) ──▶ Maschine fra, performance-2x/4 GB
                                                             java dev.magelite.Main --server --idle-exit-min=10
                                                             /data (Volume): db/cards.h2, magelite.db, cache/images, logs
```

- Dieselbe Engine wie lokal, Flag `--server`: bindet `0.0.0.0`, kein Zufallstoken, kein Parent-Watchdog.
- **Anmeldung per Einladungscode oder E-Mail + Passwort.** Jeder Login erzeugt eine Session (Tabelle `sessions`,
  Cookie `ml_sess` mit Zufallstoken, HttpOnly, 1 Jahr; in der DB nur der SHA-256). Ein eingeladenes Konto ist zunächst
  „Gast“ (nur Code); unter **Konto** kann der Nutzer E-Mail + Passwort setzen (PBKDF2) und sich danach auch damit
  anmelden. Optional (Schalter `MAGELITE_SIGNUP=open`) kann sich jeder selbst per E-Mail **registrieren** – siehe
  „Öffentliche Registrierung“. Ohne Cookie sind nur Startseite, Health, Login/Registrierung und die Download-Info
  erreichbar (`Auth.isPublicPath`).
- **Zwei Konto-Arten** (`users.tier`): `friend` = eingeladen oder Owner (alles erlaubt) und `public` = selbst
  registriert (keine Spiele, die der Server rechnet – nur Tische auf dem eigenen Rechner bzw. Beitritt zu Tischen
  anderer; fällt unter das Monatsbudget). Der Admin kann ein Konto unter „Nutzer“ zum Freund machen und zurück.
- **Konten:** Nutzer 1 = der lokale Held (hat keinen Code), Nutzer 2 = Owner (Admin) aus den fly-Secrets, weitere
  per „Einladungen“ in der UI. `decks`, `games`, `xp_ledger`, `profile` sind pro Nutzer getrennt.
- **Kostenbremsen** (fly hat kein hartes Ausgabenlimit, Stand 2026 – die Bremsen sitzen in der Engine):
  - fly stoppt die Maschine ohne Verbindungen; zusätzlich beendet sich die Engine selbst, wenn 10 Minuten kein Spiel
    läuft und keine Anfrage eines **angemeldeten** Nutzers kam (`--idle-exit-min`). Anonyme Anfragen (Startseite,
    Crawler, Scanner) zählen nicht: hat seit dem Start nur Anonymes die Maschine geweckt, endet sie nach 3 Minuten
    (`--anon-exit-min`). `robots.txt` sperrt Suchmaschinen aus.
  - Vergessene Tabs halten sie nicht wach: Startseite, Lobby, Tisch und Admin pollen nur bei sichtbarem Tab und bis
    15 min nach der letzten Eingabe (`pollPaused()`); ein **verwaistes Spiel** (Tab zu, Spiel wartet auf den Menschen)
    wird nach 10 Minuten ohne verbundenen Client abgebrochen.
  - **Monatsbudget** (`MAGELITE_BUDGET_HOURS`, Standard 100 h ≈ 8,60 $): die Engine zählt jede Laufzeit-Minute in
    `uptime_month`. Ist das Budget erreicht, sind **öffentliche Konten bis Monatsende gesperrt** (503 `budget`, UI
    zeigt „Kontingent aufgebraucht“, ihre Anfragen halten die Maschine nicht wach, Registrierung zu); Owner und
    eingeladene Freunde spielen normal weiter. Bei 80 % und 100 % bekommt der Owner eine Mail (wenn er unter Konto
    eine E-Mail hinterlegt hat und Mailversand eingerichtet ist). Stand: Admin → Server, Kachel „Laufzeit <Monat>“.
  - Das Setup liegt nicht auf fly, sondern als GitHub-Release (kein Egress, kein Volume-Platz).
  - Der nächste Aufruf startet die Maschine (5–10 s, die UI zeigt „Server wird gestartet …“).

## Einmal-Setup

Voraussetzung: `winget install Fly-io.flyctl`, Terminal neu öffnen, `fly auth login` (Browser).

```powershell
cd D:\xmage_clone
fly apps create magelite                 # Name ist global eindeutig; ist er vergeben, z. B. magelite-<kuerzel>
                                         # -> dann auch "app = ..." in fly.toml anpassen
fly volumes create magelite_data -r fra -s 3 -a magelite --yes
fly secrets set -a magelite --stage MAGELITE_OWNER_CODE=XXXX-XXXX-XXXX-XXXX MAGELITE_OWNER_NAME=Melvin
powershell -ExecutionPolicy Bypass -File scripts\deploy-fly.ps1      # = fly deploy --ha=false
```

So wurde die App am 05.10.2026 angelegt: App `magelite` (personal org), Volume `magelite_data` in fra (3 GB,
verschlüsselt, tägliche Snapshots), Maschine `performance-2x`, Adresse **https://magelite.fly.dev**.

```powershell
```

Owner-Code erzeugen (16 Zeichen A–Z/2–7; der Server normalisiert Groß/klein, Bindestriche, 0→O, 1→I, 8→B):

```powershell
cd engine; .\gradlew.bat -q compileJava; java -cp build\classes\java\main dev.magelite.auth.InviteCodes
```

Den Code sicher aufbewahren (Passwort-Manager); er wird beim Login eingegeben und steht sonst nur in den fly-Secrets.

Der **erste Start** auf fly baut die Karten-DB auf dem Volume (gemessen 05.10.2026: 43 s Scan, Engine bereit nach
45 s; Health-Check hat 420 s Toleranz). Danach startet die Engine in 2–6 s; der Container braucht im Leerlauf
≈ 1,4 GB RAM. `fly deploy` immer mit `--ha=false` (eine Maschine; das Deploy-Skript macht das).

## Deploy

```powershell
powershell -ExecutionPolicy Bypass -File scripts\deploy-fly.ps1        # bricht ab, wenn gerade ein Spiel laeuft
powershell -ExecutionPolicy Bypass -File scripts\deploy-fly.ps1 -Force # trotzdem
powershell -ExecutionPolicy Bypass -File scripts
elease.ps1 -Fly      # neue Version: bauen, installieren, deployen
```

Das Skript findet `flyctl` selbst (PATH, winget-Paket, `~\.flyin`), bricht bei uncommitteten Änderungen ab
(`-AllowDirty` erlaubt es), prüft `GET /api/health` (`games` = laufende Spiele), ruft `fly deploy` auf und wartet,
bis Health die neue Version meldet (`desktop/package.json`). Gebaut wird lokal
mit Docker (`Dockerfile`, 3 Stufen) oder ohne lokales Docker mit `fly deploy --remote-only`.

## Freunde einladen

1. Als Owner anmelden: `https://<app>.fly.dev`, Owner-Code eingeben.
2. Links **Admin** → Tab „Einladungen“ → Name eintragen → „Code erzeugen“. Der Code erscheint **einmalig**; „Link kopieren“ erzeugt
   `https://<app>.fly.dev/#invite=XXXX-XXXX-XXXX-XXXX`.
3. Der Freund öffnet den Link und ist angemeldet (ein Jahr, pro Browser). Code von Hand eintippen geht auch.
   Die Startseite schlägt vor, das Konto mit E-Mail + Passwort zu sichern („Als Gast weiterspielen“ ist ok).
4. „Neuer Code“ macht den alten sofort ungültig (offene Verbindungen werden getrennt). „Entfernen“ löscht Konto,
   Held und Decks; Spiele bleiben in der Statistik-Tabelle.

5. **Freunde in der App:** Auf der Startseite und unter „Spielen“ (Lobby) gibt es den **Lobby-Chat** (alle Angemeldeten, standardmäßig drin,
   „Verlassen“ macht unsichtbar) und die **Freundesliste** (Name eintippen oder im Chat auf den Namen klicken →
   Anfrage, der andere nimmt an). Wer an einem Tisch sitzt, kann Freunde per „Einladen“ holen; die Einladung
   erscheint beim Freund als Karte oben rechts („Beitreten“), auch wenn er gerade woanders in der App ist (nicht
   im laufenden Spiel). Der Chat-Verlauf liegt nur im Speicher und ist nach einem Auto-Stop weg.

6. **Zuschauen / Entfernen:** In der Lobby zeigt ein laufender Tisch „Läuft · Zug N“ und „Zuschauen“ (nur wer an
   keinem Tisch sitzt, max. 8 pro Spiel; Zuschauer sehen keine Hände). Der Gastgeber kann vor dem Start Mitspieler
   entfernen („Wirklich entfernen?“); sie kommen erst nach einer neuen Einladung wieder an den Tisch.

7. **Admin-Bereich** (nur Owner): Tab „Nutzer“ zeigt alle angemeldeten Konten mit Status (online / am Tisch /
   im Spiel / offline), Level, Spielen, Siegen, zuletzt online und Gast/E-Mail; Klick öffnet rechts das Detail
   (letzte Partien, Decks, Sessions) mit „Abmelden“ (alle Sessions enden, Code bleibt gültig), „Code rotieren“ und
   „Löschen“. Tab „Server“: Version, Laufzeit, Speicher, Belegung x/max, laufende Spiele („Beenden“) und Tische
   („Schließen“).

Text für Freunde:

> Hier kannst du dein Commander-Deck gegen drei Bots testen, direkt im Browser am PC/Laptop (Chrome, Edge oder
> Firefox; Handy/Tablet geht nicht gut): *Link*. Der Link ist dein persönlicher Login – bitte nicht weitergeben.
> Unter „Konto“ kannst du E-Mail + Passwort setzen, dann kommst du auch ohne Link wieder rein.
> Deck importieren geht mit einem Archidekt-Link oder einer Textliste (Moxfield: Text-Export einfügen); ohne
> eigenes Deck gibt es fertige Decks. Es kann immer nur **ein Spiel gleichzeitig** laufen – wenn „Gerade spielt …“
> kommt, kurz warten. Der erste Aufruf nach einer Pause dauert ein paar Sekunden (Server startet).
> Dein Held, deine Decks und deine Statistik gehören nur dir. Bugs/Ideen gern direkt an mich.

## Tisch auf dem eigenen Rechner (Host-Link)

Der Server rechnet nur **ein Spiel** gleichzeitig. Wer mehr Tische will, hostet einen auf seinem PC – der Server
bleibt Lobby, Konto und Vermittler und reicht das Spiel nur durch. Keine Portfreigabe, keine Zusatzsoftware.

1. **Setup laden:** Die Startseite (vor dem Login) verlinkt `MageLite-Setup-<version>.exe` der Server-Version als
   GitHub-Release im öffentlichen Repo `melknoo/magelite-releases` (`MAGELITE_DOWNLOAD_URL` in `fly.toml`,
   hochgeladen von `release.ps1 -Fly` bzw. `scripts\publish-setup.ps1`; braucht `gh`, einmal `gh auth login`).
2. **In der App „Online spielen“** (Startseite der App): das Fenster lädt `https://magelite.fly.dev`, dort wie im
   Browser anmelden (Einladungscode oder E-Mail + Passwort). Die App merkt sich das Session-Cookie und bindet ihre
   lokale Engine ausgehend an den Server (`/ws/host`). In der Lobby zeigt „Tisch eröffnen“ dann **„Auf meinem
   Rechner“** (sonst ausgegraut mit Hinweis). „Zur App“ in der Navigation führt zurück zur lokalen App; Abmelden
   trennt den Link.
3. **Tisch:** Name, Ort (Server / mein Rechner), optional **privat** mit Passwort. Alle Angemeldeten sehen den Tisch
   (Schloss-Symbol bei privaten); Beitritt mit Passwort oder per Einladung (Freunde einladen ersetzt das Passwort).
   Decks kommen aus der Server-Bibliothek jedes Spielers, Bots setzt der Gastgeber wie gewohnt.
4. **Spiel:** läuft in der Engine des Gastgebers (auch die Bots – seine CPU), die Spieler spielen über die
   Server-Seite wie sonst, der Gastgeber selbst auch (sein Verkehr geht Browser → Server → eigene Engine, ~30 ms
   Umweg). Jeder sieht nur seine eigene Hand – der Server und die Engine schicken jedem Sitz nur seine Sicht; die
   lokale App des Gastgebers kann sich an das Spiel nicht anhängen (`/api/games/current` 404, WebSocket 4403).
   Statistik und XP werden auf dem Server für jedes Konto verbucht wie bei Server-Spielen.
5. **Verbindung weg:** Bricht der Link zum Gastgeber ab (App zu, Netz weg), sehen die Spieler ein Banner; kommt die
   App binnen 60 s zurück, geht es weiter, sonst endet das Spiel mit Fehler (keine Statistik). Der Admin sieht
   Relay-Spiele unter „Server“ mit „auf dem Rechner von …“ und kann sie beenden.

Grenzen: kein Zuschauen an solchen Tischen; während ein Tisch auf dem eigenen Rechner läuft, geht dort kein lokales
Solo-Spiel. Vertrauensmodell: der Gastgeber führt die Engine aus – in der UI sieht er nichts Fremdes, ein
manipulierter Engine-Prozess könnte es (wie bei jedem selbst gehosteten Spiel). Für Freundesrunden gedacht.
Relay-Spiele halten die fly-Maschine wach (Leerlauf-Exit zählt sie mit), kosten aber kaum CPU.

## Kosten (Preisliste 05.10.2026, Region fra)

| Posten | Preis |
|---|---|
| `performance-2x` / 4 GB, laufend | ≈ 76 $/Monat Dauerbetrieb ≈ **0,10 $/Stunde** |
| Maschine gestoppt | nur rootfs, ≈ 0,15 $/GB/Monat (Cents) |
| Volume 3 GB | ≈ 0,5 $/Monat |

Bei 20 Spielstunden im Monat ≈ 2–3 $. Keine Grundgebühr. Kontrolle: `fly status` (Maschine `stopped`?),
`fly dashboard` → Billing, in der App Admin → Server („Laufzeit <Monat>“). Obergrenze bei öffentlicher Registrierung:
Monatsbudget (Standard 100 h ≈ 8,60 $) plus Laufzeit durch Freunde danach plus Volume. Volume-Snapshots kosten seit
2026 extra.

## Öffentliche Registrierung

Standard ist **geschlossen** (`MAGELITE_SIGNUP = "closed"` in `fly.toml`). Vor dem Öffnen:

1. **Impressum und Datenschutz** ausfüllen: `ui/public/impressum.html`, `ui/public/datenschutz.html` (Platzhalter,
   von der Startseite verlinkt).
2. **Mailversand (Brevo, EU, kostenlos bis 300 Mails/Tag):** Konto anlegen, Absender verifizieren (eigene Domain
   empfohlen – Mails von gmx/web.de-Adressen über fremde Server landen oft im Spam), AV-Vertrag abschließen,
   API-Key erzeugen. In `fly.toml`: `MAGELITE_MAIL_FROM = "noreply@…"`; Secret: `fly secrets set
   MAGELITE_MAIL_API_KEY=…`.
3. **Captcha (Cloudflare Turnstile, kostenlos):** Widget für `magelite.fly.dev` anlegen; Site-Key nach `fly.toml`
   (`MAGELITE_TURNSTILE_SITEKEY`), Secret per `fly secrets set MAGELITE_TURNSTILE_SECRET=…`.
4. `MAGELITE_SIGNUP = "open"`, deployen. Fehlt Mail, Captcha oder `MAGELITE_PUBLIC_URL`, bleibt die Registrierung
   trotzdem zu (Fehler im Log: „Registrierung bleibt geschlossen“).

Ablauf: Name, E-Mail, Passwort, Captcha → Konto `public`, unbestätigt → Mail mit Link `…/#verify=<token>` (24 h) →
Klick meldet an. Unbestätigte Konten werden nach 24 h gelöscht (beim Start und stündlich). „Passwort vergessen?“ auf
der Startseite schickt einen Reset-Link (`#reset=`, 1 h; gilt auch für eingeladene Konten mit E-Mail). Antworten
verraten nie, ob eine E-Mail registriert ist (bestehende Konten bekommen stattdessen eine Hinweis-Mail). E-Mail-
Änderung für registrierte Konten ist (noch) gesperrt.

Grenzen (alle in der DB gezählt, überleben Neustarts): höchstens 3 Konten pro IP und 24 h
(`MAGELITE_SIGNUPS_PER_IP`), 30 pro 24 h insgesamt (`MAGELITE_SIGNUPS_PER_DAY`), 200 öffentliche Konten
(`MAGELITE_MAX_PUBLIC_USERS`); dazu das Login-Rate-Limit (10/min/IP) für alle Registrierungs-Routen und höchstens
eine Mail je Konto und Zweck alle 5 Minuten. Die Startseite zeigt den Stand (`GET /api/auth/options`: open, closed,
full, daily, budget).

## Betrieb

```powershell
fly status -a magelite                 # Maschine laeuft/gestoppt
fly logs -a magelite                   # Live-Log (Engine-Log auch unter /data/logs/engine.log)
fly ssh console -a magelite            # Shell in der Maschine (startet sie)
fly machine stop <id> -a magelite      # sofort stoppen
fly secrets set -a magelite MAGELITE_OWNER_CODE=...   # Owner-Code rotieren (Neustart)
```

- **Leistungsmessung** (gemacht 05.10.2026): kurz `fly scale memory 8192`, dann per `fly ssh console` in einem
  eigenen Ordner (vermeidet den H2-Lock mit der laufenden Engine, baut die Karten-DB einmal neu, 44 s):
  `mkdir -p /data/spike && cd /data/spike && java -Xmx2g -Dmagelite.vendor=/app/vendor/xmage -cp "/app/lib/magelite-engine.jar:/app/lib/*" dev.magelite.spike.BotSpike --games=2 --tempo=BLITZ --turnCap=40`
  Ergebnis: 3,9 bzw. 1,5 s/Zug (max 14,2 s), Heap-Spitze 1,7 GB, 0 Fehler – schneller als lokal (≈ 5 s/Zug).
  Danach `rm -rf /data/spike` und `fly scale memory 4096`.
- **Verbindungsabbruch eines Mitspielers:** Die anderen sehen „getrennt seit N s“ an seinem Platz; kommt er
  zurück, läuft alles weiter. Nach 60 s erscheint „aufgeben lassen“ – damit gibt sein Sitz auf und das Spiel geht
  ohne ihn weiter (sein Ergebnis landet trotzdem in seiner Statistik). Sind alle Menschen länger als 10 min weg,
  bricht die Engine das Spiel ab.
- **Datenmenge:** REST/Statik gzip, WebSocket `permessage-deflate` (automatisch). Ein State im späten Spiel liegt
  unkomprimiert im zweistelligen kB-Bereich (siehe `STATUS.md`), also auch für Mobilfunk unkritisch.
- **Zwei Tische parallel:** `fly scale memory 8192` und in `fly.toml` `MAGELITE_MAX_GAMES = "2"`.
- **Leerlauf trotz offener Tabs:** Startseite/Lobby pollen (Social alle 3 s), aber nur bei sichtbarem Tab und bis
  15 min nach der letzten Maus-/Tastatureingabe; danach greift der Idle-Exit wie gewohnt.
- **Volume voll?** `cache/images` wächst unbegrenzt (Scryfall-Bilder). Notfalls per `fly ssh console` leeren.
  Alte Setups aus der Zeit vor GitHub-Releases: `rm -rf /data/downloads`.
- Die lokale Electron-App bleibt unverändert (eigener lokaler Held, Token, nur `127.0.0.1`).

## Sicherheit

- Öffentlich ohne Code: nur Startseite, `/api/health`, `/api/auth/login`, die Registrierungs-Routen
  `/api/auth/options|signup|verify|resend|forgot|reset` (Rate-Limit 10/min pro IP, Captcha) und `/api/download/info`.
- Links in Mails kommen aus `MAGELITE_PUBLIC_URL`, nie aus dem Host-Header. Bestätigungs-/Reset-Tokens nur als
  SHA-256 in `email_tokens`, einmalig, mit Ablauf.
- Host-Link (`/ws/host`): nur mit gültigem Session-Cookie **und** passendem `Origin` (die App sendet ihn); ein
  Link je Konto, geroutet werden nur Tische dieses Gastgebers. Die Host-Engine erfährt Konto-ids, Namen und
  Decklisten der Mitspieler – nicht deren Sessions. Tisch-Passwörter nur als Hash (`tableId:pw`), 5 Versuche/min.
- Cookie `HttpOnly`, `SameSite=Lax`, `Secure` hinter HTTPS. WebSocket-Upgrade prüft Cookie **und** `Origin`.
- Owner-Code nur in den fly-Secrets; in der DB nur Hashes (Codes SHA-256, Passwörter PBKDF2-SHA256 mit 210k
  Iterationen und Salt, Session-Tokens SHA-256). Rotieren/Entfernen beendet alle Sessions sofort; Passwortwechsel
  beendet alle anderen Sessions. Logout löscht die Session serverseitig. Unbekannte E-Mail kostet beim Login dieselbe
  Zeit wie ein falsches Passwort (Dummy-Hash). `PUT /api/auth/account` unterliegt dem Login-Rate-Limit.
- Spiele gehören einem Nutzer: fremdes `/api/games/current` → 404, fremder WebSocket → Close 4403.
- Szenarien (`POST /api/games {scenario}`) nur in der Dev-Engine, nie auf dem Server.
- Request-Größe 2 MB; `DeckUrlImporter` ruft nur Archidekt-/Moxfield-API-URLs ab (kein SSRF).

## Lokal testen

```powershell
cd engine; .\gradlew.bat runServer     # Server-Modus auf 7317, Owner-Code DEV-OWNER-CODE
cd ui; npm run dev                     # http://localhost:5173/  (ohne ?port= -> Vite-Proxy, Cookies funktionieren)
node scripts\e2e-login.mjs             # Konten, Cookie, Nutzertrennung, 409, Rotieren, Rate-Limit
cd desktop; npx electron tools\shot.cjs tools\steps-server.json   # Screenshots Login/Home/Einladungen/Spiel
node scripts\e2e-relay.mjs             # Host-Link: startet selbst zwei Engines (7411 = Server, 7412 = Host-App)
node scripts\e2e-signup.mjs            # Registrierung, Limits, Budget, Weck-Schutz: startet selbst eine Engine (7421)
```

Host-Link von Hand: `RELAY_ONLY_START=1 node scripts\e2e-relay.mjs` (Engines bleiben stehen), dann
`node desktop\tools\relay-seed.mjs` (Bob hostet einen privaten Tisch), Vite `MAGELITE_ENGINE=http://127.0.0.1:7411
npx vite --port 5174` und `tools\steps-relay.json`. Electron selbst gegen eine Test-Engine:
`MAGELITE_SERVER_URL=http://127.0.0.1:7411` vor dem Start setzen (Standard `https://magelite.fly.dev`).
