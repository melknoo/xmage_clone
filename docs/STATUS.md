# Projektstand

Stand: 2026-10-06. Bitte nach jeder größeren Änderung aktualisieren.

## Phasen (aus dem ursprünglichen Plan)

| Phase | Inhalt | Stand |
|---|---|---|
| P0a | Headless-Spike: 4 Bots spielen Commander FFA | ✅ fertig (`gradlew spike`) |
| P0b | Mensch über Prompt-API (ohne UI) | ✅ fertig (`gradlew humanSpike`, automatischer Test-Spieler) |
| P0c | GameView → schlanke DTOs, Rich-Text | ✅ fertig |
| P1 | REST + WebSocket, Spieltisch-UI | ✅ fertig |
| P2 | Alle Prompt-Arten, Hotkeys, Kampfpfeile, Auto-Passen, Tempo, Electron-Shell | ✅ fertig, siehe offene Punkte |
| P3 | Deck-Import (Text, Archidekt, Moxfield), Deck-Bibliothek, Scryfall-Bilder | ✅ fertig (Moxfield nur teilweise, s. u.) |
| P4 | Statistik, Held/XP/Titel, Deck-Meisterschaft, Spielende-Screen | ✅ fertig |
| P5 | Installer, gebündelte Java-Laufzeit, Startoptimierung, Feinschliff | 🟡 Installer fertig (`scripts\package.ps1`, auf Windows gebaut/installiert/gestartet); Startoptimierung, Feinschliff offen |

Zusätzlich umgesetzt (nicht im Plan): **Auto-Mana** (`AutoPayer`): automatisches Bezahlen mit passenden Quellen,
Fallback auf manuelles Klicken.

## Gemessen / getestet

- Engine-Start: 2–6 s mit vorhandener Karten-DB; **erster Start** baut die DB auf: ca. 40–45 s.
- 4-Bot-Spiele (Tempo Blitz): 3/3 ohne Fehler, Ø ca. 5 s pro Spielerzug, einzelne Züge bis ca. 50 s bei vollen Boards,
  Heap-Spitze ca. 2 GB (`-Xmx3g`).
- `humanSpike`: insgesamt 9 Spiele, 0 Fehler, 0 Hänger. Mit Auto-Mana kam **kein** Mana-Prompt mehr beim Spieler an.
- `TextDeckParserTest`: 8/8 (MTGA, Archidekt-Kategorien, Leerzeilen-Commander, Kandidaten, DFC, .dck-Roundtrip, XMage-unfertig).
- Archidekt-Import live: OK (100 Karten, legal erkannt).
- End-to-End: Spiel mit importiertem Deck bis zum natürlichen Ende → XP, Meisterschaft, Kartenstatistik gespeichert.
- Frischer Klon: `scripts\build.ps1` läuft durch, App startet und baut die DB selbst auf.
- UI visuell geprüft (Screenshots): Startseite, Deck-Auswahl, Mulligan, Spieltisch mit Kampf/Stapel/Log.
- 2026-10-02 behoben: Zugfolge lief gegen die Sitzordnung (Mapper nahm `getPlayers()`, XMage spielt die `PlayerList`
  andersherum ab); Vorschau getappter Karten war gedreht; Ziele von Zaubern/Fähigkeiten auf dem Stapel wurden nicht
  angezeigt (jetzt Chips + Zielpfeil, `targetRefs`); Fähigkeiten auf dem Stapel hatten keinen Namen; Dialoge sind
  minimierbar (Tab). `humanSpike` prüft seitdem Zugfolge = Sitzordnung: 9/9 Spiele korrekt.
- 2026-10-05 neu: **Ersatzeffekt-Wahl gruppiert** (`ReplacementAssist`, `GameHost.replacement`): gleiche Effekte
  (z. B. 7× „Dredge 2“) als ein Kasten mit Karten-Chips; Chip = diesen Effekt anwenden (Folge-Frage beantwortet die
  Engine), „Keinen anwenden“ = ganze Ja/Nein-Kette ablehnen, „für dieses Spiel merken“ + Rücksetz-Knopf in der Leiste.
  Geprüft: `humanSpike --scenario=dredge` (3 Dialoge, 0 Fehler, Karte nach 1-Klick auf der Hand, danach kein Dialog
  mehr), Regression `humanSpike` 2/2, Screenshots `steps-dredge.json`. Nicht eigens getestet: „merken“ bei nur noch
  einer Dredge-Karte im Friedhof (kein Wahl-Dialog, Ablehnen über den Regeltext an der Karte).
  Grenze: „Keinen anwenden“ gilt bis zum nächsten anderen Prompt, bei „ziehe 2“ also auch für die zweite Karte.
- 2026-10-02 neu: Aktivitätsanzeige in der Prompt-Leiste (Herzschlag `activity` 1/s mit Modus + gemessener CPU-Last):
  „X rechnet …“ mit drehendem Zahnrad nur bei echter Rechenlast, Warnung bei Stillstand (> 15 s) oder ohne Verbindung.

- 2026-10-02 (Branch `fix/spieltisch-feinschliff`): oberste Bibliothekskarte sichtbar und von oben spielbar
  (aufgedeckt: `PlayerView.getTopCard()`; nur für mich: „look at the top card any time“, spielbar oder angesehen);
  Stapel größer/vorne mit Hero-Objekt; Spielverlauf nach Zügen gruppiert (alte Züge zu, Spielerfarben, Icons, ×n,
  Filter Wichtiges/Alles); aufgedeckte/angesehene Karten werden kurz eingeblendet. Bugfixes: Auto-Bezahlen-Fehlschlag
  ließ die UI ohne Prompt hängen; Verlauf nach Reconnect doppelt; veraltete States nach Reconnect; Esc/Leertaste
  im Friedhof-/Exil-Fenster gingen ans Spiel; Ziele in Friedhof/Exil ohne Auswahlfenster; Pfeile zum Commander
  zeigten auf die Kommandozone; `/api/games/current` konnte ein altes Spiel liefern; doppelte Antwort auf einen
  Prompt möglich (jetzt `compareAndSet`); Auto-Mana zahlte {C} mit „beliebige Farbe“-Quellen; Kartenbild nach
  Transformieren leer; MULTI_AMOUNT mit `max = 0` unbegrenzt.
  Geprüft: `test`, `humanSpike` 2/2 ohne STALL, WS-Probe (Reconnect-Reihenfolge, `active` im Log, `topCard` mit
  Courser-Testdeck). **Noch nicht visuell geprüft** (Stapel, Verlauf, 📚-Fenster): unter Linux ohne Display startet
  weder Electron noch Chrome headless mit HTTP-Seiten.

- 2026-10-02: Schalter „⏩ Auto-Passen / Passen manuell“ in der Spielleiste (neben Auto-Mana, gespeichert in
  `localStorage` `magelite.autoPass`, per WS `settings.autoPass` an die Engine). Aus = Prioritäts-Prompt an den
  Stopps auch ohne spielbare Aktion, damit das Tempo nichts verrät. Nur `tsc` geprüft (kein Java/Display hier).

- 2026-10-02: **Trigger-Ketten schneller.** Ursache war die KI: Nach jedem aufgelösten Stapelobjekt rechnete jeder
  Bot eine Minimax-Suche bis ins Zeitlimit. Bei 112 Scute-Swarm-Triggern waren das ~6 s pro Trigger, ein Zug dauerte
  ~13 min.
  - Neu `fastStack` (Blitz/Normal): Ohne Spielbares passen die Bots sofort; auf gleiche Stapelobjekte (`StackSig`)
    nur einmal nachdenken.
  - Die Bot-Pause kommt nur noch nach echten Aktionen.
  - Der Mensch passt automatisch weiter auf gleiche Trigger, nachdem er einmal gepasst hat (alle Stufen).
  - Auto-Passen baut keinen vollen State mehr.
  - Gemessen mit `humanSpike --scenario=swarm` (Blitz): 16 Trigger **99,7 s → 6,5 s**. Davon entfällt der Großteil
    auf das erste Nachdenken der Bots, die im Szenario Bolt/Mogg Fanatic haben. Eine Kette mit 29–30 Triggern
    braucht 0,7–1,5 s (25–50 ms/Trigger). Normal: 31 Trigger in 1,8 s.
- 2026-10-02: **Große Boards:**
  - Eigene gleiche Permanents werden gestapelt (×N), auch angreifend/blockend nach Ziel gruppiert. Pfeile finden
    zusammengefasste Karten über `data-objs`.
  - Im Stapel werden gleiche Fähigkeiten zusammengefasst („+N gleiche darunter“, ×N).
  - Im Verlauf werden gleiche Zeilen ohne Objekt-Kürzel zusammengefasst.
  - **Mehrfach-Angriff/-Block:** Shift+Klick markiert Kreaturen oder einen ganzen Stapel; ein Klick aufs Ziel
    lässt alle angreifen bzw. blocken (WS `combat`). Esc hebt die Markierung auf.
  - Bugfix: Verlaufs-Links hatten `data-obj` und konnten Pfeile auf den Verlauf umlenken (jetzt `data-ref`).
  - `&mdash;` u. a. im Regeltext wird dekodiert.
  - Geprüft: `test` 8/8, `humanSpike` 2/2 ohne STALL, `blockerSpike` 2/2, Szenario Blitz/Normal mit Mehrfach-Angriff
    16/16 bzw. 15/15, `tsc`, Screenshots (`steps-swarm.json`, `steps-autoplay.json`).
  - Nicht visuell geprüft: Mehrfach-**Block**; nur der Engine-Pfad ist identisch zum Angriff.
- 2026-10-05 behoben: **Länder beim Bezahlen teils nicht anklickbar.** `GameViewMapper.playable` nutzte
  `getPlayable(game, true)` = `hideDuplicatedAbilities=true`. XMage dedupliziert dann per Regeltext über alle Objekte
  hinweg: nur das erste Land mit „{T}: Add {G}.“ war spielbar. Ein Tri-Land schluckte so z. B. Forest + Godless Shrine,
  ebenso 2. Forest, 2. gleiche Handkarte und die Auswahl von Auto-Mana. Jetzt `PlayerImpl.getPlayable(…, Zone.ALL, false)`
  wie XMages `getPlayableObjects`. Geprüft: `compileJava`, `test`, `humanSpike` 2/2 + 2/2 ohne STALL, Dump-Auswertung
  (35 Prioritäts-States: 0 ungetappte eigene Länder fehlen, 2× Mountain beide spielbar). Nicht visuell geprüft: Die
  Dev-Engine startet gerade nicht (uncommittete `V3__games_per_user.sql`: SQLite „near ','“).

- 2026-10-06 **Playtest-Runde 2** (`notes.md`):
  - **Karten-DB-Absturz (Demonic Consultation) behoben.** Ursache war kein Speichermangel: Bot-Timeout →
    `ComputerPlayer6.addActionsTimed` → `task.cancel(true)` unterbricht den Sim-Thread mitten in
    `CardRepository.getNames()` → `ClosedByInterruptException` → H2 „file length -1“, Liste leer und nicht gecacht →
    später beim Menschen „Critical error, can't find card names“. Dreifach abgesichert: Namenslisten beim Start
    vorladen (`CardDbManager.warmNames`, 9 Listen/161k Einträge, ~1,6–1,9 s), `MageLiteBot.addActionsTimed` ohne
    `Thread.interrupt` (kooperativer Stopp), H2-`retry:`-Dateisystem über unsere `DatabaseUtils` (Classpath-Vorrang).
    Geprüft: `dbInterruptSpike` 4/4 OK (**ohne** Negativkontrolle – mit warmem Page-Cache liest die Abfrage evtl. gar
    nicht aus der Datei), `humanSpike` 2/2 mit 20 Bot-Timeouts, 0 DB-Fehler im Log.
  - Kartennamen-Dialog: `choice.hint = card`, Sortierung einmalig, Filter verzögert, Hover zeigt das Kartenbild per Name.
  - UI: Kartenvorschau liegt über dem Dialog-Overlay (`z-[55]`), Dialoge zentrieren sich in der Hauptfläche
    (`--modal-inset-right`). „Alle angreifen“ braucht zwei Klicks; neu „Angriff zurücksetzen“ (`combatReset`-Makro)
    und „Abbrechen – kein Angriff“ in der Verteidiger-Wahl; Hinweis „Klick auf einen Angreifer nimmt ihn zurück“.
    Starthand-Aktionen (Gemstone Caverns, Leylines) bekommen einen eigenen Dialog (vorher nur Ja/Nein in der Leiste,
    Esc = Nein – Gemstone selbst war **kein** Engine-Fehler; sie darf regelgemäß nur rein, wenn man nicht beginnt).
  - **„N-mal aktivieren“** (Necropotence & Co.): ×N-Stepper im Fähigkeiten-Picker, WS `repeat`, Engine hält
    dazwischen die Priorität (`HOLD_PRIORITY` auf dem CALL-Thread vor der Antwort), Ziele/Fragen stoppen das Makro.
  - **Ereignis-Animationen** (`FxWatcher` → WS `events` → `FxLayer`): Geisterkarte fliegt in Friedhof/Exil/Hand,
    schwebende Zahlen bei Schaden/Leben, Ereignisleiste (~5 s) links über der Prompt-Leiste; Toggle im Pausemenü.
    Verdeckte Karten nur an den Besitzer (im `humanSpike --humans=2` geprüft: 0 Lecks).
  - **Chat:** im Spiel (WS `chat`, Tab „Verlauf | Chat“ ab 2 Menschen, Ungelesen-Badge + Toast, Replay nach
    Reconnect) und am Tisch (REST, Polling). 300 Zeichen, 5 Nachrichten / 5 s.
  - **Konto sichern:** Einladungscode = Gast; optional E-Mail + Passwort (PBKDF2), danach Login auch damit. Alle Logins
    laufen jetzt über Sessions (`sessions`, Cookie `ml_sess`; Legacy `ml_code` wird noch akzeptiert – später entfernen).
    Code bleibt gültig (Recovery ohne Mailversand), „Neuer Code“ beendet alle Sessions, Passwortwechsel die anderen.
    UI: Login-Tabs, Konto-Screen, Hinweis „Du spielst als Gast“ auf der Startseite, Admin-Spalte „Anmeldung“.
  - Geprüft: `humanSpike` Szenarien `gemstone` OK, `necro` ×5 OK (Leben −5, Exil +5), `swarm` 15/15 + Zurücksetzen 0,
    `--humans=2` 0 Fehler; `e2e-login` 46/46, `e2e-online` und `e2e-tables` grün (Chat-Fälle inklusive); `test` grün;
    Regression `humanSpike --games=2 --turnCap=32` 0 fehlgeschlagen.
    Visuell geprüft (`steps-modal-hover/-gemstone/-necro/-attack-undo/-fx.json`, `steps-server.json`): Vorschau über
    dem Mulligan-Dialog, Starthand-Dialog, ×5-Picker + Stapel mit 5 Fähigkeiten, „Wirklich alle?“ → Verteidiger-Wahl
    → Abbrechen (0 Angreifer) → alle 16 greifen an → Zurücksetzen (0), Ereignisleiste „Rakdos Carnarium → Hand“,
    Login-Tabs, Gast-Hinweis auf der Startseite, Konto-Screen. **Nicht visuell geprüft:** Spiel-Chat-Tab (nur e2e),
    Tisch-Chat (nur e2e), Geisterkarten-Animation nur schemenhaft (Shot-Werkzeug hinkt beim versteckten Fenster
    einen Schritt hinterher – `steps-server.json` loggt deshalb jetzt zuerst aus).
    **Live (fly, 2026-10-06):** deployt; Log zeigt Migration V4, Namen vorgeladen (3,4 s), retry-FS aktiv.
    `e2e-tables` live grün (Tisch-Chat inklusive), `e2e-login` live 45/46 – einzige FAIL: „WS ohne Cookie → Close
    4401“ kommt über den fly-Proxy als Timeout (−1) an, kein Regress (Server schließt mit 4403 wegen fehlendem Origin).
    `e2e-online` nicht live (erwartet die Dev-Grenze 5 s fürs „aufgeben lassen“). Achtung: nach `e2e-login` greift
    das Login-Rate-Limit (10/min/IP) eine Minute lang für alle weiteren Skripte.

- 2026-10-05 **Stärkere Bots (ohne LLM)**, Begründung in `DECISIONS.md`:
  - **FFA-Bewertung:** Ersatzklasse `mage.player.ai.score.GameStateEvaluator2`. Bewertet gegen alle Gegner statt nur
    den ersten. Das Original wertete „erster Gegner auf 0 Leben“ als Partiesieg.
  - **`FfaAttack`:** Angriffsziel unter allen Gegnern, Blocker gegen Gegenschlag zurückhalten.
  - **`reactInCombat`** (Normal).
  - **`MageLiteBot.SimPool`:** keine neue Suche, solange eine abgebrochene noch läuft.
  - Engine-Jar zuerst auf dem Classpath (harte Regel 10).
  - **Messung mit `gradlew botArena`** (2 verbesserte gegen 2 Original-Bots, Spiegel-Spiele, Blitz, Zuglimit 80):
    - 42 Spiele, davon 8 abgebrochen (6× OutOfMemoryError, 1× Spiel-Thread hängt, 1× OOM im Profiling-Lauf).
    - Von 34 sauberen Spielen: Siege 18:14, Platzierungspunkte pro Sitz 1,63 : 1,37 (gleich stark = 1,50).
    - Mehr Punkte als die Gegenseite in 13 Spielen, weniger in 7; Vorzeichentest p ≈ 0,26, also Trend, nicht
      signifikant.
  - **Think-Timeouts** der verbesserten Bots ca. 1,6× so häufig: Mehr Züge sehen lohnend aus. Die Bewertung selbst
    kostet laut JFR nur 1,2 % CPU; Spielkopien kosten 24 %.
  - **MCTS** nicht gemessen.
  - Gemessen **vor** dem kooperativen Stopp in `MageLiteBot.addActionsTimed` (Playtest-Runde 2), danach nicht erneut.
  - Geprüft: `test` 8/8, `humanSpike` 2/2 ohne STALL, `blockerSpike` 2/2, API-Diff der Ersatzklasse (nur Marker
    `MAGELITE_FFA` neu), Start-Log „KI-Bewertung: MageLite-FFA aktiv“.

- 2026-10-06 **Playtest-Runde 3:**
  - **„Weiter“ zeigt das Ziel** im eigenen Zug: „Zu Main 1“ / „Zum Kampf“ / „Zu Main 2“ / „Zug beenden“
    (`NextStop`, `PromptDto.nextStop`). Mögliche Angreifer pro Gegner – XMages `getAvailableAttackers(game)` ist vor
    Kampfbeginn immer leer.
  - **Main 2 wurde übersprungen:** Auto-Passen griff auch in den eigenen Main-Phasen, wenn `getPlayable` nichts
    fand. Jetzt halten eigene Main 1/2 immer. Dazu Doppelklick-Schutz: „Weiter“ in den ersten 250 ms eines neuen
    Prioritäts-Prompts wird ignoriert.
  - **F9 „Bis zu meinem Zug“ abbrechbar:** „⏹ Stopp“ in der Prompt-Leiste + klickbares Badge (F3), „Passen manuell“
    bricht ebenfalls ab (nur beim Umschalten). Neu: Stopp in der **Endphase jedes Gegners**, wenn Spontanes spielbar
    ist (sonst Auto-Passen). F10 bei leerem Stapel hängte das Spiel auf (Prompt zu, XMage ignoriert) – jetzt abgelehnt.
  - **Convoke ging nicht:** XMage bietet Sonderbezahlung nur per Antwort „special“, MageLite zeigte nie einen Knopf.
    Jetzt Knopf „Einberufen“ (bzw. Wühlen/Improvisieren/Beistand), einberufbare Kreaturen leuchten lila, Klick tappt
    sie (Makro `GameHost.specialPay`: Aktion → Kreatur → Farbe nach Engpass). Bei möglicher Sonderbezahlung startet
    Auto-Mana nicht von selbst (Länder sind nach dem ersten Einberufen gesperrt); „Länder automatisch“ zahlt nur mit
    Manaquellen. Bugfix: Auto-Mana-Fehlschlag wurde mitten in einer Bezahlung zurückgesetzt.
  - **X auf dem Stapel:** Badge „X = n“ (`CardDto.x` aus dem Kosten-Tag, schon während Zielwahl/Bezahlen).
  - **Passwort-Auge** (`PasswordInput`) in Login und Konto.
  - **Freunde-Fixes:** Schnellstart/„Nochmal“ zeigen Fehler (409 „Gerade spielt …“, Deck fehlt) statt still nichts
    zu tun; Tisch-Polling verlässt den Tisch nur noch bei 4xx (Netz/5xx/Neustart → „Verbindung wackelt“); fly-502
    als HTML gibt eine lesbare Meldung; `#table=`-Link überlebt den Login; Einladungstext in `SERVER.md` ergänzt.
  - Geprüft: `humanSpike --scenario=convoke` (neu: Ziel „combat“, F10-Schutz, Blaze X=2, Einberufen-Knopf + 6
    Kreaturen, kein Auto-Start, „Länder automatisch“ tappt 2 Länder, 6 Klicks ohne Rückfrage, Main-2-Stopp mit
    0 Aktionen, F9 → F3 bricht ab, F9 → „Passen manuell“ bricht ab, Stopp in gegnerischer Endphase, Zug 5 „combat“),
    `necro`/`gemstone`/`swarm`/`dredge` OK, `--games=2 --turnCap=32` 2/2 (ein früherer Lauf: bekannter KI-Heap-OOM
    bei vollem Board), `--humans=4` OK, `test` grün, `tsc`. Visuell (`steps-convoke.json`, `steps-pass-ui.json`):
    „Zum Kampf“, „X = 2“, lila Kreaturen + „Länder automatisch“/„Einberufen“, „Zu Main 2“ nach dem Einberufen,
    F9-Leiste + Stopp (Klick bricht im Gegnerzug ab), Startfehler auf der Startseite, Login mit Auge.
    Nicht visuell: Konto-Screen (gleiche Komponente), Fallback-Knopf „Einberufen“ ohne Kreatur-Klick.

## Offene Punkte (priorisiert)

0. **Online (fly.io)** – Plan `docs/ONLINE-PLAN.md`, Betrieb `docs/SERVER.md`.
   - 2026-10-05 **E1 fertig:** Server-Modus (`--server`, `--host`, `--max-games`), Konten + Einladungscodes
     (`auth/*`, Cookie `ml_code`, Migration `V2__users.sql`), alle Daten pro Nutzer, Login-/Admin-Screen,
     Vite-Proxy, `gradlew runServer`, `scripts/e2e-login.mjs` (27/27 grün), Screenshots `steps-server.json`.
   - 2026-10-05 **E2 vorbereitet:** `Dockerfile`, `.dockerignore`, `fly.toml` (performance-2x/4 GB, Auto-Stop,
     Volume `/data`), `scripts/deploy-fly.ps1`, Leerlauf-Exit `--idle-exit-min=10`. Lokaler `docker build`
     (493 MB) und Container-Smoke-Test mit 4 GB Limit grün: erster Start 172 s (Karten-Scan 161 s),
     Login/Cookie/401 ok, 1,35 GB RAM im Leerlauf.
   - 2026-10-05 **E2 fertig – live unter https://magelite.fly.dev** (App `magelite`, Volume `magelite_data` fra 3 GB,
     performance-2x/4 GB, eine Maschine, Auto-Stop + Idle-Exit 10 min). Erster Start auf fly: Karten-Scan 43 s,
     bereit nach 45 s. Login mit Owner-Code live geprüft (Cookie `Secure`, 401 ohne Cookie).
     **Leistungsmessung** (BotSpike auf der Maschine, kurz 8 GB): 2 Spiele Blitz bis Zug 40, 0 Fehler,
     3,9 bzw. 1,5 s/Zug (max 14,2 s), Heap-Spitze 1,7 GB → schneller als lokal (≈ 5 s/Zug); 4 GB und
     `--max-games=1` bleiben. Owner-Code liegt nur in den fly-Secrets (lokal `engine/run/owner-code.txt`, ungetrackt).
     Live-Spiel über `wss://` aus Electron geprüft (Starthand-Dialog), fly stoppt die Maschine ~6 min nach der
     letzten Verbindung, **Kaltstart per Aufruf 5,5 s** (Engine 2,8 s). Zusätzlich bricht die Engine verwaiste
     Spiele (kein Client > 10 min) ab, damit ein offener Prompt die Maschine nicht wach hält.
   - Regression lokal (2026-10-05): `test` grün, `humanSpike` 2/2 ohne STALL, `e2e-flow` (Spiel endete durch den
     bekannten KI-Heap-OOM bei 40 Permanents, Aufzeichnung ok), Autoplay-Screenshot im Lokalmodus ok.
   - 2026-10-05 **E3 (Engine-Kern) umgesetzt:** `GameHost` mit `HumanSeat` pro Mensch (1–4 Menschen + Bots),
     Prompt gehört einem Sitz, State pro Sitz, `leave` = nur eigener Sitz, Belohnung/`games`-Zeile pro Nutzer
     (Migration `V3__games_per_user.sql`: `games` PK `(id,user_id)`, `game_card_stats` mit `user_id`),
     `StatsSink` pro Spieler, `hello.host` (Tempo nur Gastgeber), Status „Warte auf <Mensch>“, Activity `human`.
     Tests: `humanSpike --humans=2` (17,8 s) und `--humans=4` (24 Züge, 27,6 s) 0 fehlgeschlagen, Standard 1 Mensch
     2/2, `scripts/e2e-online.mjs`. Weitere Menschen kommen bis zur Lobby nur über das Dev-Feld `humans` in
     `POST /api/games`.
   - 2026-10-05 **E4 fertig (live):** `TableManager`/`TableRoutes` (`/api/tables`, Polling 1,5 s), Lobby- und
     Tisch-Screen, `#table=`-Link, Bots auf freie Plätze, offene Plätze fallen beim Start weg, nach dem Spiel
     „Zurück zum Tisch“ (Revanche). `scripts/e2e-tables.mjs` lokal und gegen https://magelite.fly.dev grün.
   - 2026-10-05 **Pausemenü** (Esc / „☰ Menü“): Optionen (Auto-Mana, Auto-Passen, Ton, Verlauf, Tempo), Aufgeben
     mit Ja/Nein, danach „Zuschauen“/„Zurück zum Tisch“/„Zum Hauptmenü“. Neue WS-Nachricht `seat {conceded}`;
     `/api/games/current` liefert für aufgegebene Sitze 404 (kein Rückholen beim Neuladen).
   - 2026-10-05 **E5 fertig:** WS `seats` (Verbindungszustand der Menschen, alle 2 s solange jemand getrennt ist),
     Badge „getrennt N s“ am Platz + rote Statuszeile, nach 60 s „aufgeben lassen“ (WS `kick`, Engine prüft die
     Grenze; Dev-Engine 5 s). Kompression gemessen: REST gzip (`/api/samples` 15 kB → 3,3 kB), WebSocket
     `permessage-deflate` wird ausgehandelt. State-Größe: größter State 33 kB unkomprimiert (32 Züge, 318 States,
     7,8 MB gesamt). `e2e-online` deckt Trennung/Kick/Reconnect ab. **Online-Plan E1–E5 abgeschlossen.**
1. **Installer / Verteilung (P5)**: `scripts\package.ps1` (NSIS-Setup.exe ~320 MB + jlink-JRE, Details
   `docs/DEVELOPMENT.md` §7). 2026-10-02 auf Windows geprüft: Build, Installation, Start, Aufbau der Karten-DB.
   Noch offen: Start auf einem PC ganz ohne Java (Log muss `resources\jre\bin\java.exe` zeigen), Spiel +
   Scryfall-Bilder in der gepackten App, Deinstallation, App-Icon (`desktop/build/icon.ico`).
   Code-Signatur bewusst weggelassen (SmartScreen-Hinweis reicht für private Weitergabe).
2. **Einstellungs-Screen**: Stopps pro Phase (aktuell fest in `HumanSettings`), Auto-Passen, Auto-Mana,
   Lautstärke, Bild-Cache leeren.
3. **Bedien-Komfort**
   - Angreifen per Drag & Drop auf einen Gegner. Mehrere auf ein Ziel geht schon: Shift+Klick + Ziel
     (`GameHost.combat`).
   - „Immer Ja/Nein“ für wiederkehrende Fragen (`REQUEST_AUTO_ANSWER_*`, UI fehlt; Engine erlaubt die Actions).
     Für Ersatzeffekte (Dredge & Co.) gibt es das seit 2026-10-05 („Keinen anwenden“ + „für dieses Spiel merken“).
   - Trigger-Reihenfolge merken (`TRIGGER_AUTO_ORDER_*`, UI fehlt).
   - Animationen fürs **Betreten** des Spielfelds (Stapel → Feld) fehlen noch; Zonenwechsel weg vom Feld, Schaden,
     Leben gibt es seit 2026-10-06 (`FxLayer`). Sounds aus `vendor/xmage/sounds` statt Synth-Töne.
4. **Moxfield-Import**: Server-Abruf wird von Cloudflare geblockt (HTTP 403). Der Fallback über Electron
   (`window.magelite.fetchText` → `net.fetch`) ist eingebaut, aber **nicht verifiziert**.
   Ersatz: Moxfield-Text-Export einfügen.
5. **Bilder**: Set-Code-Mapping XMage → Scryfall fehlt (Fallback per Kartenname funktioniert). Optional
   `ScryfallImageSupportCards/Tokens` aus dem XMage-Client exportieren, Vorabladen über `/cards/collection`,
   LRU-Grenze für den Cache. Token-Bilder (Scryfall-Suche) nur stichprobenhaft gesehen.
6. **KI-Stärke**: FFA-Bewertung, `FfaAttack` und `reactInCombat` umgesetzt (2026-10-05, siehe oben). Offen:
   - mehr Arena-Spiele für ein signifikantes Ergebnis (je 30 Spiele ≈ 1,5 h),
     z. B. `gradlew botArena -PspikeArgs="--games=30 --turnCap=80 --tempo=BLITZ"`,
   - Gewicht `--w` und `--elim` abstimmen, einzelne Hebel über `--levers=` messen,
   - Commander-Schaden im Lethal-Check von `FfaAttack`,
   - MCTS (`--a=mcts`) messen.
7. **Startzeit**: AppCDS (`-XX:ArchiveClassesAtExit` / `SharedArchiveFile`), Sample-Deck-Warmup.

## Bekannte Probleme / Grenzen

- XMage-KI ist eher passiv und bei 40+ Permanents langsam (Log: „AI player thinks too long“).
- In **Bedacht/Max** ist `fastStack` aus (Nutzerwunsch): Bei langen Trigger-Ketten rechnet dort jeder Bot weiter pro
  Stapelobjekt bis zur Denkzeit. „Nichts spielbar → sofort passen“ wäre auch dort verlustfrei.
- Mehrfach-Angriff markiert immer den ganzen Stapel (×N); nur einen Teil davon zu markieren geht noch nicht
  (einzelne Karten per normalem Klick).
- In Blitz/Normal (`fastOpponentTurns`) reagieren Bots in fremden Zügen nur, wenn etwas auf dem Stapel liegt
  (keine Flash-Kreaturen/Removal am Zugende). Blocken funktioniert unabhängig davon. Ausnahme Normal
  (`reactInCombat`): In fremden Kampfschritten rechnen sie, wenn eine Spontanaktion möglich ist. Am Zugende handelt
  auch die Original-XMage-KI nie (`ComputerPlayer7` passt in `END_TURN`).
- **KI-Heap-OOM:** Eine einzelne XMage-Suche kann den Heap (3 GB) füllen, wenn `SimulatedPlayer2` alle
  Zielkombinationen einer Fähigkeit als Kopien erzeugt („too many possible targets?“, z. B. Opfer-Fähigkeiten im
  Vampir-Deck). In der Bot-Arena betraf das ca. 15–19 % der Blitz-Spiele, mit Original-Bots genauso. `SimPool`
  verhindert nur das Stapeln mehrerer solcher Läufe, nicht den einzelnen.
- UI-Dialoge `CHOOSE_PILE`, `MULTI_AMOUNT` und die Mulligan-Unten-Auswahl sind nur über die Spikes getestet,
  nicht visuell.
- Kontrollwechsel-Karten (Mindslaver & Co.) sind nur nach dem XMage-Gating-Muster umgesetzt, nicht getestet.
- Statistik: Spalte `game_card_stats.cast` zählt auch gespielte Länder (Anzeige „gespielt“).
- Gelöschte Decks behalten ihre Statistik (`games.deck_id` ohne Fremdschlüssel).
- Es läuft immer nur **ein** Spiel (`GameRegistry`, `--max-games`, lokal 1): ein neues eigenes Spiel beendet das
  eigene laufende; im Server-Modus bekommt ein anderer Nutzer 409 „Gerade spielt …“.
- **Tischspiel-Abbruch:** Wer am Tisch aufgibt und dann solo startet („Schnellstart“/„Nochmal“), beendet das
  Tischspiel für alle (`GameRegistry.start` beendet jedes Spiel mit diesem Nutzer, auch nach Aufgabe). Für später
  geplant, zusammen mit „2 Tische parallel“ (8 GB, `MAGELITE_MAX_GAMES=2`, `SimPool.awaitIdle` pro Spiel statt global).
- Sonderbezahlung: Klick-Einberufen nur für Convoke-Kreaturen ohne eigene Manafähigkeit (sonst Mana); Delve,
  Improvise, Assist nur über den Knopf (Aktion wird automatisch gewählt, Ziel/Farbe von Hand).
- Server-Modus: ein Spiel pro Nutzer, nur der Besitzer darf sich verbinden; Moxfield-Import im Browser ohne
  Electron-Fallback (Text-Export einfügen). Kein Mailversand: „Passwort vergessen“ = Gastgeber erzeugt neuen Code.
  Legacy-Cookie `ml_code` wird noch akzeptiert – nach ein paar Wochen entfernen (`Auth.resolve`).
- `events`-Zwischenzustände: Ereignisse werden vor dem nächsten State gebündelt (bzw. nach 150 ms vom Wachhund);
  bei sehr vielen gleichzeitigen Zonenwechseln zeigt die Leiste nur die letzten 6, Token-Tode werden zu „×N“.
- **Hänger durch verlorene Antwort (XMage-Race) – umgangen 2026-10-02:** `HumanPlayer.waitForResponse` setzt
  `responseOpenedForAnswer = true` *vor* `synchronized(response) { wait() }`. Antwortet der CALL-Thread genau
  dazwischen, geht `notifyAll()` verloren und das Spiel wartet ewig. Vorher: 3 STALLs in 9 `humanSpike`-Spielen.
  Jetzt antwortet `GameHost.apply` erst, wenn der Spiel-Thread wirklich in `waitForResponse` → `wait()` steckt;
  zusätzlich stellt der Wachhund eine Antwort erneut zu, wenn XMage danach ohne neue Frage weiter wartet
  (Log-Warnung „Antwort ging verloren“, Zähler `activity.recovered`). Danach: 6/6 Spiele ohne STALL, 0 Neuzustellungen.
- **KI-Endlosrekursion beim Blocken:** `ComputerPlayer6.declareBlockers` → `replaceEvent` → `ChooseBlockersEffect`
  → `Combat.selectBlockers` → derselbe Bot → … → `StackOverflowError`, Spiel bricht ab („Spiel abgebrochen“).
  Tritt bei Karten auf, mit denen ein Spieler die Blocker eines anderen bestimmt. 1× in 6 `humanSpike`-Spielen
  (2026-10-02). **Behoben:** Rekursionssperre + eigene Logik in `MageLiteBot.selectBlockers`/`chooseBlockersByEffect`
  (Regressionstest `gradlew blockerSpike`).
- `desktop/tools/shot.cjs`: Das versteckte Fenster zeichnet manchmal verzögert – bei verdächtigen Bildern
  nochmal mit längerer Wartezeit aufnehmen.
- **Neue Karten fehlen (Stand 2026-10-02):** XMage nimmt Karten aus der `unfinished`-Liste eines Sets nicht in die DB
  auf (z. B. Prepare-Karten aus Secrets of Strixhaven/SOC). Neue Sets wie Reality Fracture (FRA) sind gar nicht
  enthalten. Der Import zeigt beide Fälle getrennt an (`XmageUnfinished`). Auch 1.4.61V1 sperrt diese Karten noch;
  erst `master` hat sie freigeschaltet. Sobald ein Release (≥ 1.4.62) erscheint: `scripts\import-xmage.ps1 -XmageDir …`,
  danach `build.ps1` (die DB wird beim ersten Start neu aufgebaut).

## Redesign (UX/UI) – in Vorbereitung

- 2026-10-06: Paket für **Claude Design** in `design/claude-design/` (nicht eingecheckt): `PROMPT.md` (fertiger Prompt,
  Deutsch), `KONTEXT.md` (Zielgruppe, Maße, Screens, Zustände, Tech, No-Gos), `current-theme.css` (Kopie von
  `ui/src/index.css`), `screenshots/` (55 PNGs aller Screens/Dialoge/Zustände, lokal + Server, plus 1280×760) mit
  `INDEX.md`. Aufnahme reproduzierbar über `desktop/tools/steps-design-*.json` (+ `design-mate.mjs` für den zweiten
  Menschen), siehe `DEVELOPMENT.md`. Nächster Schritt: Stilrichtung in Claude Design wählen, Handoff zurück nach Claude Code.

## Ideen (nicht beauftragt)

- Deck-Editor mit Kartensuche, Vergleich zweier Deckversionen in der Statistik.
- „Goldfish ohne Bots“-Modus (nur eigenes Deck, Zugzähler) für reine Starthand/Kurven-Tests.
