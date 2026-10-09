# Projektstand

Stand: 2026-10-08. Bitte nach jeder größeren Änderung aktualisieren.

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
    Server-Modus e2e (eigene Engine): `e2e-login` 46/46, `e2e-online` 34/34, `e2e-tables` 35/35.
    **Live:** Commit `b10937a` deployt (Repo = Live-Stand), neues Bundle ausgeliefert, `e2e-tables` live 35/35.

- 2026-10-06 **Lobby-Chat, Freunde, Tisch-Einladungen** (nur Server-Modus, Begründung in `DECISIONS.md`):
  - **Lobby-Chat** auf der Startseite für alle Angemeldeten (rechte Spalte): standardmäßig drin, „Verlassen“ =
    unsichtbar + keine Nachrichten (pro Konto, `users.lobby_chat`), „Beitreten“ holt zurück. Verlauf nur im Speicher
    (100), Mitgliederliste, Klick auf Namen → „Als Freund hinzufügen“, Ungelesen-Badge am „Held“-Nav.
  - **Freunde** (Migration `V5__social.sql`, `friendships`): Anfrage per exaktem Namen oder aus dem Chat, Annehmen/
    Ablehnen/Zurückziehen/Entfernen, Gegenanfrage nimmt an. Status online / am Tisch / im Spiel / offline.
  - **Einladungen:** am Tisch „Freunde einladen“ (auch von der Startseite aus, wenn man an einem Tisch sitzt);
    beim Freund unten rechts eine Karte „Beitreten / Ablehnen“ (überall außer im Spiel). Nur an Freunde, 10 min
    gültig, verschwindet bei vollem/laufendem Tisch oder nach dem Beitritt.
  - Technik: `social/SocialService`, `FriendStore`, `SocialRoutes`; UI `store/social.ts` pollt alle 3 s außerhalb des
    Spiels, pausiert bei verstecktem Tab und nach 15 min ohne Eingabe (fly-Leerlauf). Vite-Proxy-Ziel jetzt per
    `MAGELITE_ENGINE`, `shot.cjs` kennt `"show": true`.
  - Geprüft (eigene Engine 7400): `e2e-social` 47/47 (neu), `e2e-tables`, `e2e-online` grün, `test` grün, `humanSpike`
    1/1, `tsc`. Visuell (`steps-social.json`): Startseite mit Chat/Freunden/Anfrage/Einladungskarte, Namens-Popup,
    Beitritt per Einladung → Tisch mit „Freunde einladen“, Chat verlassen → „Beitreten“-Karte.
    **Nicht deployt.**

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

## Redesign „Graphit & Glut“ (2026-10-07)

- Umgesetzt nach dem Claude-Design-Handoff `design/design_handoff_magelite_redesign/` (Spec, Theme, 5 Prototypen):
  ganze UI neu (Spielbrett, Held, Spielen, Decks/Import, Statistik, Login/Konto/Einladungen, Lobby/Tisch, Social),
  Schriften Barlow Condensed + IBM Plex Sans/Mono, Icons lucide, keine Emojis, Toast-System (`store/ui.ts`, `Toaster`),
  Spiel-Dialoge über `BoardModal` (Pille/Tab, Space/Esc). Kampf: Bedienung wie bisher, statt Pfeilen Etiketten
  („→ NAME“, „BLOCKT X“, „GEBLOCKT“, „ZIEL“, „MARKIERT“) und Pod-Chip „N ANGREIFER“.
- Neu: **Zuschauen** bei laufenden Tisch-Spielen (`/ws/game/{id}?spectate=1`, max. 8, nur wer an keinem Tisch sitzt;
  öffentliche Sicht ohne Hand/Bibliothek/verdeckte Karten), **Mitspieler entfernen** (Gastgeber, vor dem Start;
  Sperre bis zur nächsten Einladung), **Systemzeilen** im Lobby-Chat („X ist dem Lobby-Chat beigetreten“),
  Import-Vorschau mit Zeilen-Hinweisen und Namensvorschlägen, Tisch-Turn in der Lobby, Statistik-Felder
  (Spielzeit, „vor dir“, Kartenstatistik „in der Hand“), Dev-Szenario `blocker`.
- Fixes nebenbei: Spieler, die ein Spiel selbst verlassen, landen hinter den Überlebenden (vorher geteilter Platz 1);
  Nachzügler-Nachrichten eines alten WebSockets wurden ins neue Spiel übernommen (Mulligan-Dialog fehlte);
  FX nannten den echten Namen verdeckter Quellen (Morph/Manifest); „commander-away“ verriet die Zone (Hand/Bibliothek);
  Tailwind-Utility `table-row` kollidierte mit dem eingebauten `display: table-row` (jetzt `tbl-*`).
- Geprüft: `tsc -b`, `vite build`, Token-/Emoji-/Hex-Gates; `gradlew test`; `humanSpike --humans=4` und
  `--spectate` mit necro/dredge/gemstone/blocker (0 Fehler, keine STALLs); e2e social/tables/online/spectate/login
  (Server) und flow (lokal) grün; Screenshot-Läufe `steps-redesign-{board,meta,meta-leer,online}.json` gegen die
  Prototyp-Aufnahmen (`design/redesign-shots/`, nicht eingecheckt) verglichen; adversariale Leck- und Code-Review.
- **Live deployt** 2026-10-07 (fly v10, Commit `fbda885`): Health ok, Migration V5 angewendet, FFA-KI aktiv,
  Bundle = lokaler Build, `e2e-tables` gegen live grün. Lokale App (`build.ps1`) startet im neuen Design.
- Offen (niedrig): Zuschau-Sicht wird bei Tisch-Spielen auch ohne Zuschauer gebaut (CPU); Entfernen per Platz-Index
  kann bei gleichzeitigem Platzwechsel die falsche Person treffen; Karten-ids bleiben über Zonenwechsel gleich
  (XMage, z. B. zurückgeschickt → später verdeckt gewirkt); Status „wartet auf X“ verrät indirekt Instants;
  verdecktes Exil wird per Regeltext erkannt (Lücken möglich); entfernte Nutzer dürfen zuschauen;
  `e2e-online` „Owner spielt weiter“ ist timing-anfällig (Spiel endet mitunter regulär).

## Playtest-Fixes (2026-10-07)

- **The Mighty Thor, Jane Foster:** XMage-Bug (Filter `FilterCreaturePermanent` → nur Kreaturen statt „Artefakt oder
  Kreatur“). Ersatzklasse `engine/src/main/java/mage/cards/t/TheMightyThorJaneFoster.java` (wie `GameStateEvaluator2`,
  Engine-Jar vor XMage-Jars); Test `CardOverridesTest`. Weitere Karten-Bugs nach demselben Muster überschreibbar.
- Kartenvorschau (`ZoomPanel`) mit fester Höhe → Spielverlauf springt beim Hovern nicht mehr.
- Eigenes Feld dreigeteilt: Kreaturen / Artefakte · Verzauberungen (nur wenn vorhanden, `battlefield-others`) / Länder.
  Gegner-Pods unverändert.
- Geprüft: `gradlew test`, `humanSpike` (0 Fehler), `tsc -b`, Screenshots (Verlauf-Oberkante bei 40 Hovers konstant).

## Admin, Startseite, Lobby-Chat, Animationen, Release (2026-10-07)

- **Startseite:** Nav-Punkt „Held“ heißt „Start“ (Haus-Icon), Logo „ML“ führt immer zur Startseite; „Hauptmenü“-
  Texte im Spiel heißen „Startseite“/„Zum Start“. Inhalt unverändert. Unten in der Nav die Version (`v0.x.y`).
- **Lobby-Chat auch unter „Spielen“** (Lobby, Server-Modus): `SocialSidebar` rechts, `useMarkLobbyRead`; Badge an
  „Start“ nur außerhalb von Start/Lobby. Lobby-Spalten passen bei 1280 px neben den Chat.
- **Admin-Bereich** (Nav „Admin“, nur Owner): Tabs Nutzer (Status live, Level/Titel, Spiele/Siege, zuletzt online,
  Gast/E-Mail; Detail rechts mit Partien/Decks/Sessions, Abmelden/Code rotieren/Löschen), Einladungen (wie bisher,
  nur offene Codes), Server (Version, Laufzeit, Heap, Belegung, laufende Spiele „Beenden“, Tische „Schließen“).
  Engine `admin/AdminService` + `AdminRoutes`, `Auth.requireAdmin`, `SocialService.presence`,
  `AccountService.revokeSessions`, `TableManager.adminClose`, `GameHost.humanSeats/startedAt`.
- **Animationen** (`FxLayer`, `Battlefield`, `store/game.ts`): Treffer-Funke Quelle → Ziel mit Einschlag/Flash (Zahl
  erst beim Einschlag), Betreten des Felds (Skalieren + Glow), Angriffsstoß Richtung Verteidiger, Tod (roter Blitz +
  Splitter, Geisterkarte danach). Alle am Schalter „Effekte“ und `prefers-reduced-motion`, Blitz kürzer.
  Dev-Zeitlupe für Aufnahmen: `window.__mlFxSlow = 6` (nur Vite-Dev).
- **Release:** `scripts/release.ps1` (Version +1, `package.ps1`, Setup still installieren, Registry-Check, optional
  `-Fly`). Eine Versionsquelle `desktop/package.json` → Gradle → `magelite-version.properties` → `/api/health`.
  Jar heißt fest `magelite-engine.jar` (Dockerfile/SERVER.md angepasst). `deploy-fly.ps1` findet flyctl selbst,
  bricht bei uncommitteten Änderungen ab und wartet auf die neue Version.
- **Auto-Bezahlen:** Nutzer meldet Hänger/Endlosschleife beim automatischen Bezahlen (Henzie-Deck, Karte unbekannt).
  Ursache noch offen; neu: INFO-Logzeilen „Auto-Bezahlen …“ (Stapelobjekt, XMage-Text, gewählte Quelle,
  Abbruchgrund) in `engine.log`.
- **Frage „mehrere Spiele?“:** Jeder Eingeloggte kann allein gegen Bots spielen, aber auf fly läuft nur **ein** Spiel
  gleichzeitig (`MAGELITE_MAX_GAMES=1`, 4 GB); der Zweite bekommt 409 „Gerade spielt …“. Entscheidung 2026-10-07:
  bleibt so; die Admin-Server-Übersicht zeigt die Belegung.
- Geprüft: `compileJava`, `e2e-admin` 44/44 (eigene Server-Engine 7401), `tsc -b`, Screenshots `steps-admin.json`
  (Lobby+Chat 1680/1280, Logo → Start, Nutzer, Detail kompakt bei 1280, Einladungen, Server) und
  `steps-fx-anim.json` (Zeitlupe: Betreten, Angriff, Funke, Einschlag, Tod-Splitter nach Treffer; „Effekte aus“
  leert alles), `gradlew test` grün, `humanSpike` 1/1 ohne Fehler (Auto-Bezahlen-Logzeilen erscheinen),
  `release.ps1` echt durchlaufen: Version 0.1.0 → **0.1.1**, Setup 308 MB, still installiert
  (`%LOCALAPPDATA%\Programs\MageLite`, Registry 0.1.1), installierte App startet mit gebündelter JRE und
  `magelite-engine.jar` („FFA aktiv“). Versionsdateien noch nicht committet. **Nicht deployt** (fly).
- Offen: Tisch, dessen Gastgeber geht, lässt ein laufendes Spiel ohne Tisch weiterlaufen (Admin kann es beenden).

## Stopps, Deck-Ordner, Brackets (2026-10-08)

- **Stopp bei Ziel auf mich** (Pausenmenü, Standard an): Auto-Passen **und** F-Tasten halten, sobald ein fremdes
  Stapelobjekt dich, deine bleibenden Karten, Stapelobjekte oder Karten anvisiert (je Stapelobjekt einmal).
  `TargetCheck` + `GameHost.stopReason`; F-Tasten über `MageLiteHuman` (Unterklasse von `HumanPlayer`, setzt die
  Pass-Flags in `priority()` auf dem Spiel-Thread zurück). Prompt zeigt „Angehalten · Quelle → Ziel“ (`stopReason`).
- **Stopp im Gegner-Upkeep** (Pausenmenü, Standard aus): XMage gibt im Gegner-Upkeep bei leerem Stapel gar keine
  Priorität (Step-Flag `opponentTurn.upkeep`); Umschalten setzt deshalb die `UserData` neu (`HumanSettings.of`).
  Hält je Gegnerzug einmal, auch während F4/F9.
- **Deck-Ordner** (eine Ebene): Migration `V6__deck_folder_bracket.sql`, `POST /api/decks/{id}/meta`
  (Ordner/Bracket ohne Neuspeichern), `POST /api/decks/folders/rename` (leer = auflösen). Deckliste in einklappbaren
  Abschnitten (Zustand im Browser), Menü an der Kachel (Bracket + Verschieben + neuer Ordner), Ordner/Bracket im
  Import-Dialog, Deck-Auswahl sortiert nach Ordner und zeigt Ordner/Bracket.
  **Drag & Drop:** Kachel auf einen Ordner-Abschnitt ziehen (auch eingeklappt, auf den Kopf); beim Ziehen erscheinen
  leere Abschnitte (z. B. „Ohne Ordner“) als Ziel. Auch **Umsortieren** innerhalb eines Ordners: Drop auf eine Kachel
  (linke/rechte Hälfte = davor/dahinter, Ember-Strich als Markierung). Migration `V7__deck_order.sql` (`sort_order`),
  `POST /api/decks/order {folder, ids}`; neue/nie sortierte Decks stehen vorne (`screens/decks/order.ts`), Verschieben
  per Menü setzt die Reihenfolge im Zielordner zurück. Deck-Auswahl nutzt dieselbe Reihenfolge.
- **Brackets:** `BracketAnalyzer` schlägt 2–4 vor (Game Changers aus `brackets/game-changers.txt` = XMage-Liste,
  2-Karten-Combos aus XMages `brackets/infinite-combos.txt`, Massen-Landzerstörung, Extra-Züge, Tutoren); 1 und 5
  nur manuell. Vorschlag bei Vorschau/Speichern, Altbestand per Hintergrund-Backfill beim Start. Import übernimmt
  die Autor-Bracket (Archidekt `edhBracket`, Moxfield `bracket`). Filter B1–B5 in der Deckliste.
- Geprüft: `gradlew test` (neu `BracketAnalyzerTest`), `humanSpike` 2/2 ohne Fehler, `tsc -b`, eigene Test-Engine
  7402: Backfill 4/4, Meta/Umbenennen/Validierung per curl, Screenshots Deckliste/Menü/Filter/Editor/Picker,
  Stopp-Lauf mit F9 über 47 Züge (jeder Gegner-Upkeep + „Curse of Inertia → dich“).
- Später: **Zweiten Tisch selbst hosten** (Desktop-App als Server für Freunde: Host-Schalter, Owner-Code,
  Erreichbarkeit per Portfreigabe/Tunnel, ggf. „Server beitreten“ in der App; `preload.cjs` injiziert
  `window.magelite` auch auf fremden Seiten → vorher beheben).
- Grenzen: Game-Changer-Liste ist XMages Stand (1.4.60), nicht automatisch aktuell. Tutor-/Extra-Zug-Erkennung per
  Regeltext (Heuristik).

## Tisch auf dem eigenen Rechner, Host-Link, Setup-Download (2026-10-08)

- **Host-Link:** lokale Engine hängt sich ausgehend an den Server (`relay/HostLinkClient` → `/ws/host`,
  `relay/HostLinks`); Electron liest nach dem Login im Fenster das `ml_sess`-Cookie und meldet es der Engine
  (`POST /api/host/link`). Tische haben `hosting` (SERVER/REMOTE) und optional ein Passwort (`locked`); REMOTE-Start
  löst die Decks auf fly auf und schickt sie als `.dck`-Text an den Host (`RemoteGameSpec`), das Spiel läuft dort
  (inkl. Bots), fly reicht nur durch (`relay/RemoteGames`, Umschläge `in/out`). Spielende: `finished` mit
  `GameRecorder.GameResult` → fly verbucht XP je Konto und sendet `gameOver` mit `reward`. Host weg → `hostLink`
  an die Spieler, 60 s Frist (`-Dmagelite.hostGraceMs`), `resume`/`attach` beim Wiederanlauf. Relay-Spiele zählen
  in Health/Präsenz/Admin/Idle-Exit, nicht gegen `--max-games`. Refactors: `api/GameMessages` (Dispatch), `Outbox`
  mit `Transport`/`Raw`, `LoadedDeck.dck`, `GameRecorder.record(GameResult, SeatResult)`.
- **UI:** Lobby-Dialog „Tisch eröffnen“ (Name, Server/mein Rechner, privat + Passwort), Schloss + „auf Xs Rechner“
  in der Zeile, Passwort-Abfrage beim Beitritt (403 `needPassword`), Tisch zeigt Ort/Privat/„App nicht verbunden“,
  Banner im Spiel bei Host-Ausfall, Admin „auf dem Rechner von …“. Startseite (Login) mit Download-Karte
  (`/api/download/info`). Lokale App: „Online spielen“ (lädt den Server im Fenster), dort „Zur App“.
  `preload.cjs` injiziert `window.magelite` nur noch auf 127.0.0.1/localhost (Später-Punkt erledigt).
- **Release:** `release.ps1 -Fly` lädt das Setup nach dem Deploy per `scripts\upload-setup.ps1` (`fly ssh sftp
  put`) auf das Volume; Engine serviert es öffentlich (`api/DownloadRoutes`).
- Geprüft: `gradlew test`, **`e2e-relay.mjs` alles grün** (zwei Engines: Link, 409 ohne Link, Passwort 403/200,
  Einladung ohne Passwort, Start auf dem Host, hello/Prompts/Chat/Reconnect über das Relay, `gameOver` mit
  Belohnung je Nutzer, Statistik nur auf X, Host-App sieht nichts (404/4403), Link-Reconnect mit `hostLink`
  false/true, Admin-Abbruch, Link weg → Abbruch nach Frist, `/ws/host` ohne Cookie 4401), `tsc -b`.
  `humanSpike --humans=4` 1/1 ohne Fehler (Dispatch-Refactor), `build.ps1`. Screenshots `tools\steps-relay.json`
  (`engine/run/relay-shots/relay-01..06`: Login mit Download-Karte, Lobby-Zeile mit Schloss/„auf Bobs Rechner“,
  Dialog „Tisch eröffnen“ offen/privat, Passwort falsch, Tisch-Kopf „auf Bobs Rechner · Privat“). **Electron echt:**
  App mit `MAGELITE_SERVER_URL=http://127.0.0.1:7411` + `MAGELITE_DEV_SESSION=<Token>` gestartet → `desktop.log`
  „Dev-Session gesetzt“ / „Host-Link … angefordert“, X meldet `hostLinks=1` und `/api/me hostLink:true` nach 10 s,
  (Link-Abbau beim App-Ende deckt `e2e-relay` ab; `MAGELITE_AUTOSHOT` lieferte wie schon am 07.10. kein Bild).
- Offen/Grenzen: kein Zuschauen an REMOTE-Tischen; Host kann während des Hostens kein lokales Solo-Spiel spielen;
  Klickweg Electron → Server-Login → Lobby nur per Dev-Session simuliert (Cookie-Lesen aus der Electron-Session ist
  damit geprüft, der Login-Klickweg nicht); Setup-Upload auf fly erst beim nächsten `release.ps1 -Fly`
  (`upload-setup.ps1` noch nie gegen fly gelaufen); Resume nach fly-Neustart ohne Tisch nur per Code, nicht getestet.
- **Release 0.1.6 (2026-10-08):** `release.ps1 -Fly` → Setup gebaut/installiert, Version committet; Deploy lief erst
  im zweiten Anlauf (ein paralleler Edit machte den Baum „dirty“). **Live-Smoke-Test:** `RELAY_X=https://magelite.fly.dev
  node scripts/e2e-relay.mjs` (lokale Engine als Host gegen fly) 59/60 grün – einzige Abweichung war der Negativtest
  `/ws/host` ohne Cookie (live ohne Origin kein Close-Code binnen 5 s; Prüfung jetzt toleranter). Installierte App
  0.1.6 mit `MAGELITE_DEV_SESSION` gegen fly: `hostLink:true` nach 12 s, nach App-Ende false.
  Setup-Upload: erster Versuch brach bei 259 MB ab („connection lost“, Engine-Leerlauf-Exit während des Uploads) →
  `upload-setup.ps1` hält die Engine per `/api/download/info` wach, lädt auf `.part`, prüft die Größe, bis zu 3 Versuche
  (fly-ssh-stderr über `cmd /c … 2>&1`, Regel 9). Zweiter Lauf: 322.591.100 Bytes vollständig, Umbenennen von Hand
  nachgeholt; **live:** `/api/download/info` = v0.1.6, `/api/download/file` liefert die ganze Datei (200).

- **Nachtrag (Playtest 08.10.):** Online-Tische bleiben nach dem Spiel bestehen – in der lokalen App war das nach
  einem Neustart nicht zu sehen. Jetzt fragt die lokale Engine mit der Session den Server (`/api/me`,
  `/api/tables/mine`, 3 s Cache) und `GET /api/host/link` liefert `userName` + `table`; die Karte „Online spielen“
  der lokalen Startseite zeigt „Dein Tisch „X“ ist noch offen · n/4 · auf diesem Rechner“ mit **Zum Tisch** und
  **Tisch schließen** (`POST /api/host/table/leave`). „Tisch verlassen/schließen“ am Tisch ist jetzt ein sichtbarer
  Secondary-Button mit Tooltip. Geprüft per `steps-relay-home.json` (`relay-08/09`) gegen Test-Engines; `tsc`, Build.
  Live erst mit dem nächsten Release (0.1.7).

### Öffentliche Registrierung + Kostenbremsen (2026-10-08, live als 0.1.7)

**Live seit 2026-10-08 (0.1.7):** Registrierung offen. Mail über Brevo (Domain `schleiweb.de` authentifiziert:
DKIM `brevo1/2._domainkey`, `brevo-code`, `_dmarc` p=none bei Cloudflare-DNS; Absender `MageLite <noreply@schleiweb.de>`,
Test-Mail per API angenommen), Captcha Turnstile-Widget „MageLite“ (`magelite.fly.dev`). Secrets
`MAGELITE_MAIL_API_KEY`, `MAGELITE_TURNSTILE_SECRET` bei fly; Site-Key und Absender in `fly.toml`. Releases-Repo
`melknoo/magelite-releases` (öffentlich) mit v0.1.7 angelegt, `/data/downloads` (308 MB) gelöscht. Server-Log:
V8 angewendet, Budget 0/6000 min, „Registrierung: open“. **Offen:** Impressum/Datenschutz sind noch Platzhalter;
echte Registrierung im Browser (Captcha) durch den Nutzer.

Ziel: Jeder kann sich per E-Mail registrieren, ohne dass die fly-Kosten steigen (fly hat kein hartes
Ausgabenlimit). Details und Einrichtung: `SERVER.md` → „Kostenbremsen“, „Öffentliche Registrierung“.
- **Weck-Schutz:** nur angemeldete Anfragen zählen als Aktivität (Bump nach `auth.filter`); nur anonym geweckt →
  Exit nach `--anon-exit-min` (3). `TableScreen` (1,5 s), Lobby und Admin pollen nicht mehr bei verstecktem Tab
  bzw. nach 15 min ohne Eingabe (`pollPaused()` in `store/social.ts`) – vorher hielt ein vergessener Tisch-Tab die
  Maschine ewig wach. `robots.txt`.
- **Konto-Art** `users.tier` (V8): `friend` (eingeladen/Owner) / `public` (selbst registriert). Öffentliche Konten:
  keine Server-Spiele (`POST /api/games`, Server-Tische → 403 `publicLimit`), Beitritt zu Freundes-Tischen ok;
  UI: „Allein üben“ → App/Setup, Tisch-Dialog nur „Auf meinem Rechner“. Admin: Badge, „Zum Freund machen“.
- **Monatsbudget** `admin/UptimeBudget` (`uptime_month`, Tick im Leerlauf-Wächter): ab `MAGELITE_BUDGET_HOURS`
  (100) öffentliche Konten 503 `budget` (außer `/api/me`, `/api/auth/*`), zählen nicht als Aktivität, keine
  Registrierung; UI `BudgetScreen`; Admin-Kachel „Laufzeit <Monat>“; Owner-Mail bei 80/100 %.
- **Registrierung** (`auth/SignupService`, `SignupRoutes`, `Mailer` Brevo/Outbox, `Turnstile`): Schalter
  `MAGELITE_SIGNUP` (Standard closed; ohne Mail-Key/Captcha/`MAGELITE_PUBLIC_URL` bleibt sie zu), Bestätigungslink
  `#verify=`, „Passwort vergessen“ `#reset=`, Hinweis-Mail statt Konto-Aufzählung, Grenzen pro IP/Tag/gesamt in der
  DB, unbestätigte Konten nach 24 h weg. Login-Screen mit Tab „Registrieren“, Impressum/Datenschutz als **Platzhalter**
  (`ui/public/*.html`, vom Betreiber auszufüllen).
- **Setup extern:** `/api/download/info` liefert nur den Link (`MAGELITE_DOWNLOAD_URL`, GitHub-Release im öffentlichen
  Repo `melknoo/magelite-releases`); `/api/download/file` und `upload-setup.ps1` entfernt, neu `publish-setup.ps1`
  (von `release.ps1 -Fly` vor dem Deploy). Nach dem nächsten Deploy `/data/downloads` auf dem Volume löschen.
- Geprüft: `gradlew test`, **`e2e-signup.mjs` 58/58** (Registrierung, 403 unverified, Link einmalig, Hinweis-Mail,
  Name vergeben, Reset beendet alte Sessions, 3/IP → 429, Tageslimit, öffentlich 403 für Server-Spiel/-Tisch,
  Beitritt ok, Admin-tier, Budget 0 → 503/limited/Owner normal, full, Aufräumen, **nur anonym → Exit nach 65 s,
  angemeldet nach 160 s noch wach**); Regression gegen eigene Engine: `e2e-tables`, `e2e-social`, `e2e-admin`,
  `e2e-login` (allein, sonst 429 durchs Rate-Limit der Vorläufer), `e2e-relay` grün; `humanSpike` 1/1 ohne STALL;
  `tsc -b`; `build.ps1`. Screenshots `tools\steps-signup.json` gegen `SIGNUP_ONLY_START=1 node scripts/e2e-signup.mjs`
  + Vite 5176 (`engine/run/signup/*.png`: Login-Tabs, Registrieren, „Schau in dein Postfach“, unbestätigt + „Mail
  erneut senden“, Passwort vergessen, Neues Passwort, Startseite/Tisch-Dialog als öffentliches Konto, BudgetScreen,
  Admin-Nutzer mit Badges, Detail „Zum Freund machen“, Server-Kachel „Laufzeit Oktober“). Dabei behoben: Monatsname
  kippte durch die Zeitzone auf „November“ (jetzt UTC), unbestätigte Registrierungen fehlten in „Nutzer“ und wären
  als „offene Einladung“ erschienen.
- **Vor dem Öffnen (Nutzer):** Brevo (API-Key, Absender, AV-Vertrag), Turnstile (Site-Key/Secret), öffentliches
  Releases-Repo + `gh auth login`, Impressum/Datenschutz ausfüllen, dann `MAGELITE_SIGNUP="open"` und deployen.

## Forge-Umbau (Branch `forge`, ab 2026-10-08)

Engine-Wechsel XMage 1.4.60 → Forge (Entscheidung und Plan: Memory `forge-migration.md`, Plan
`ich-habe-mich-entschieden-reflective-clover.md` §7). `main` bleibt bis zum Merge XMage.

| Phase | Stand |
|---|---|
| 0.0 Vorbereitung | ✅ Branch `forge`; `LICENSE` (GPL-3.0), `LICENSES/Forge-GPL-3.0.txt`; `brackets/infinite-combos.txt` aus `mage-1.4.60.jar` nach `engine/src/main/resources` (byte-identisch, MIT-Hinweis in `XMage-MIT.txt`); `gradlew test` 20/20 grün. Repo `melknoo/xmage_clone` seit 2026-10-09 öffentlich (GPL-Quellangebot; Historie auf Zugangsdaten geprüft: nur der öffentliche Turnstile-Site-Key). |
| 0.1 Forge holen | ✅ `vendor/forge/FORGE_COMMIT` = `d57b0cd` (master 2026-10-08, enthält PR #12091, Forge 2.0.16-SNAPSHOT); `scripts/import-forge.ps1` (Maven 3.9.16 wird selbst geladen, sparse Checkout, 4-Modul-Reactor, Zip mit Overrides). Lauf 120–220 s; `lib` 62 Jars/22 MB (jetty/servlet/slf4j/jupnp.support raus, `org.jupnp` bleibt – `IGuiBase`-Signatur); `res` 28 MB (34 074 Kartenskripte, 855 Token, 180 Precons). Boot-Smoke außerhalb des Repos: `FModel.initialize` 4,7 s warm, 33 533 Karten, 684 Editionen, Heap 284 MB. Befund für 0.3: `awaitNextInput`/`cancelAwaitNextInput` sind `final` (Timer + `invokeInEdtLater`). |
| 0.2 Gradle + Boot | ✅ Gradle auf `vendor/forge/lib` (+ reload4j, slf4j-reload4j, jsoup explizit), `-Dmagelite.forge`, Forge-Commit in `magelite-version.properties`; `boot/ForgeBoot` (Manifest-/Commit-Abgleich, tinylog → `logs/forge.log` ab WARN, eager Laden, Plausibilität), `boot/HeadlessGui`, `boot/LegacyCleanup` (löscht `db/cards.h2*`); `CardDbManager`, `DatabaseUtils`, Blocker-/DbInterrupt-Spike gelöscht. XMage-gebundener Code vorerst per `engine/forge-transition.excludes` aus dem Build (inkl. Main, api, game, alle Tests). **`gradlew forgeCheck`:** 33 533 Karten, 684 Editionen, 855 Token, Boot 3,9–4,2 s (Dateicache warm), Heap nach GC 148 MB. `gradlew run`/Spikes gehen auf diesem Branch erst wieder nach Phase 0.3/1. |
| 0.3 POC-Kern | ✅ `GameHost` neu (eigener Spiel-Thread `Game-ml-*`, inbox statt CALL-Executor, Park-Schleife, gleiche Außen-API; Makros/F-Tasten/Szenarien/FX/Stats folgen in Phase 1), `SeatGui` (Forge-`AbstractGuiGame` je Mensch), `HumanController`/`ForgeBot` (+ Lobby-Spieler, kein `HostedMatch`), `PromptBridge` (Priorität, Angriff/Block zweistufig mit Verteidiger-/Angreiferwahl, Mana + Auto, Ja/Nein, Mulligan/London, Ziele, Auswahllisten, Fähigkeit, Zahl, Auswahl, Objekte, Kampfschaden, Verteilen, Stapel), `ForgeViewMapper`/`IdCodec`/`WireNames`, einfache `AutoPassPolicy`, `LoadedDeck` auf Forge-`Deck`; `HumanSpike`/`BotSpike`/`SpectatorCheck` portiert (`PocDecks` liest die XMage-Samples per Kartenname). Schutz: ungültiger Angriff → Forges gültige Erklärung, ungültige Blocks → Block-KI, > 200 Antworten je Frage → Autopilot, > 3000 Entscheidungen je Zug → Remis; KI-Profil „MageLite“ (ohne Zufalls-Trades beim Blocken, sonst Minuten auf Token-Boards). |
| 0.4 Go/No-Go | Auswertung (Kriterien §4.12): **1** 20× `humanSpike` (1 Mensch) + 20× (2 Menschen) + 20× `spike`: 60/60 ohne Hänger/Fehler nach den Fixes (davor 2 Engine-Ausreißer: Minuten-KI auf Token-Board, Auslöser-Schleife → Profil + Schleifen-Schutz) · **2** UNMAPPED 1× in 40 Mensch-Spielen (Convoke-Input), sonst nur Trigger-Reihenfolge automatisch · **3** Bots Ø 0,87 s/Zug (XMage ~5 s), Antwort→State p95 33–45 ms · **4** Heap nach GC 190–265 MB über 20 Spiele, kein Anstieg · **5** Boot 3–4 s · **6** 4 Menschen: Prompts nur an Besitzer, Zuschauer 0 Lecks · **7** Verlassen (Prompt/Bot-Zug) Sitz raus < 2 ms, Abbruch → Ende ≤ 175 ms · **8** Samples 69/70 gültig (XMage 67/70), 18/18 Nutzer-Decks, 0 unbekannte Karten. 2 Spiele parallel in einer JVM ok. **Go** (Nutzer, 2026-10-09; DECISIONS.md). |

## Ideen (nicht beauftragt)

- Deck-Editor mit Kartensuche, Vergleich zweier Deckversionen in der Statistik.
- „Goldfish ohne Bots“-Modus (nur eigenes Deck, Zugzähler) für reine Starthand/Kurven-Tests.
