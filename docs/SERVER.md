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
  Cookie `ml_sess` mit Zufallstoken, HttpOnly, 1 Jahr; in der DB nur der SHA-256). Ein Konto ist zunächst „Gast“
  (nur Code); unter **Konto** kann der Nutzer E-Mail + Passwort setzen (PBKDF2) und sich danach auch damit anmelden.
  Der Code bleibt gültig – es gibt keinen Mailversand, „Passwort vergessen“ heißt: Gastgeber erzeugt einen neuen
  Code. Ohne Cookie sind nur Startseite, `/api/health` und `/api/auth/login` erreichbar.
- **Konten:** Nutzer 1 = der lokale Held (hat keinen Code), Nutzer 2 = Owner (Admin) aus den fly-Secrets, weitere
  per „Einladungen“ in der UI. `decks`, `games`, `xp_ledger`, `profile` sind pro Nutzer getrennt.
- **Kostenbremse:** fly stoppt die Maschine ohne Verbindungen; zusätzlich beendet sich die Engine selbst, wenn
  10 Minuten kein Spiel läuft und keine API-Anfrage kam (`--idle-exit-min`). Vergessene Tabs halten sie nicht wach,
  und ein **verwaistes Spiel** (Tab geschlossen, Spiel wartet auf den Menschen) wird nach 10 Minuten ohne
  verbundenen Client abgebrochen. Der nächste Aufruf startet die Maschine (5–10 s, die UI zeigt „Server wird
  gestartet …“).

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
```

Das Skript prüft `GET /api/health` (`games` = laufende Spiele) und ruft dann `fly deploy` auf. Gebaut wird lokal
mit Docker (`Dockerfile`, 3 Stufen) oder ohne lokales Docker mit `fly deploy --remote-only`.

## Freunde einladen

1. Als Owner anmelden: `https://<app>.fly.dev`, Owner-Code eingeben.
2. Links **Einladungen** → Name eintragen → „Anlegen“. Der Code erscheint **einmalig**; „Link kopieren“ erzeugt
   `https://<app>.fly.dev/#invite=XXXX-XXXX-XXXX-XXXX`.
3. Der Freund öffnet den Link und ist angemeldet (ein Jahr, pro Browser). Code von Hand eintippen geht auch.
   Die Startseite schlägt vor, das Konto mit E-Mail + Passwort zu sichern („Als Gast weiterspielen“ ist ok).
4. „Neuer Code“ macht den alten sofort ungültig (offene Verbindungen werden getrennt). „Entfernen“ löscht Konto,
   Held und Decks; Spiele bleiben in der Statistik-Tabelle.

Text für Freunde:

> Hier kannst du dein Commander-Deck gegen drei Bots testen, direkt im Browser am PC/Laptop (Chrome, Edge oder
> Firefox; Handy/Tablet geht nicht gut): *Link*. Der Link ist dein persönlicher Login – bitte nicht weitergeben.
> Unter „Konto“ kannst du E-Mail + Passwort setzen, dann kommst du auch ohne Link wieder rein.
> Deck importieren geht mit einem Archidekt-Link oder einer Textliste (Moxfield: Text-Export einfügen); ohne
> eigenes Deck gibt es fertige Decks. Es kann immer nur **ein Spiel gleichzeitig** laufen – wenn „Gerade spielt …“
> kommt, kurz warten. Der erste Aufruf nach einer Pause dauert ein paar Sekunden (Server startet).
> Dein Held, deine Decks und deine Statistik gehören nur dir. Bugs/Ideen gern direkt an mich.

## Kosten (Preisliste 05.10.2026, Region fra)

| Posten | Preis |
|---|---|
| `performance-2x` / 4 GB, laufend | ≈ 76 $/Monat Dauerbetrieb ≈ **0,10 $/Stunde** |
| Maschine gestoppt | nur rootfs, ≈ 0,15 $/GB/Monat (Cents) |
| Volume 3 GB | ≈ 0,5 $/Monat |

Bei 20 Spielstunden im Monat ≈ 2–3 $. Keine Grundgebühr. Kontrolle: `fly status` (Maschine `stopped`?),
`fly dashboard` → Billing.

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
  `mkdir -p /data/spike && cd /data/spike && java -Xmx2g -Dmagelite.vendor=/app/vendor/xmage -cp "/app/lib/magelite-engine-0.1.0.jar:/app/lib/*" dev.magelite.spike.BotSpike --games=2 --tempo=BLITZ --turnCap=40`
  Ergebnis: 3,9 bzw. 1,5 s/Zug (max 14,2 s), Heap-Spitze 1,7 GB, 0 Fehler – schneller als lokal (≈ 5 s/Zug).
  Danach `rm -rf /data/spike` und `fly scale memory 4096`.
- **Verbindungsabbruch eines Mitspielers:** Die anderen sehen „getrennt seit N s“ an seinem Platz; kommt er
  zurück, läuft alles weiter. Nach 60 s erscheint „aufgeben lassen“ – damit gibt sein Sitz auf und das Spiel geht
  ohne ihn weiter (sein Ergebnis landet trotzdem in seiner Statistik). Sind alle Menschen länger als 10 min weg,
  bricht die Engine das Spiel ab.
- **Datenmenge:** REST/Statik gzip, WebSocket `permessage-deflate` (automatisch). Ein State im späten Spiel liegt
  unkomprimiert im zweistelligen kB-Bereich (siehe `STATUS.md`), also auch für Mobilfunk unkritisch.
- **Zwei Tische parallel:** `fly scale memory 8192` und in `fly.toml` `MAGELITE_MAX_GAMES = "2"`.
- **Volume voll?** `cache/images` wächst unbegrenzt (Scryfall-Bilder). Notfalls per `fly ssh console` leeren.
- Die lokale Electron-App bleibt unverändert (eigener lokaler Held, Token, nur `127.0.0.1`).

## Sicherheit

- Öffentlich ohne Code: nur Startseite, `/api/health`, `/api/auth/login` (Rate-Limit 10/min pro IP).
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
```
