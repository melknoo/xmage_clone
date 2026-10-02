# Projektstand

Stand: 2026-10-02. Bitte nach jeder größeren Änderung aktualisieren.

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

## Offene Punkte (priorisiert)

0. **Online-Mehrspieler (geplant, nicht begonnen):** mit Freunden übers Internet spielen, gehostet auf fly.io,
   Zugang per Einladungscode, ein Konto pro Freund. Plan mit Etappen und Prüfschritten: `docs/ONLINE-PLAN.md`.
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
   - Trigger-Reihenfolge merken (`TRIGGER_AUTO_ORDER_*`, UI fehlt).
   - Animationen (Karte fliegt aufs Feld, Schaden), Sounds aus `vendor/xmage/sounds` statt Synth-Töne.
4. **Moxfield-Import**: Server-Abruf wird von Cloudflare geblockt (HTTP 403). Der Fallback über Electron
   (`window.magelite.fetchText` → `net.fetch`) ist eingebaut, aber **nicht verifiziert**.
   Ersatz: Moxfield-Text-Export einfügen.
5. **Bilder**: Set-Code-Mapping XMage → Scryfall fehlt (Fallback per Kartenname funktioniert). Optional
   `ScryfallImageSupportCards/Tokens` aus dem XMage-Client exportieren, Vorabladen über `/cards/collection`,
   LRU-Grenze für den Cache. Token-Bilder (Scryfall-Suche) nur stichprobenhaft gesehen.
6. **KI-Tempo/-Stärke**: siehe bekannte Probleme. Idee: `fastOpponentTurns` nur greifen lassen, wenn der Bot keine
   Spontanzauber und kein offenes Mana hat.
7. **Startzeit**: AppCDS (`-XX:ArchiveClassesAtExit` / `SharedArchiveFile`), Sample-Deck-Warmup.

## Bekannte Probleme / Grenzen

- XMage-KI ist eher passiv und bei 40+ Permanents langsam (Log: „AI player thinks too long“).
- In **Bedacht/Max** ist `fastStack` aus (Nutzerwunsch): Bei langen Trigger-Ketten rechnet dort jeder Bot weiter pro
  Stapelobjekt bis zur Denkzeit. „Nichts spielbar → sofort passen“ wäre auch dort verlustfrei.
- Mehrfach-Angriff markiert immer den ganzen Stapel (×N); nur einen Teil davon zu markieren geht noch nicht
  (einzelne Karten per normalem Klick).
- In Blitz/Normal (`fastOpponentTurns`) reagieren Bots in fremden Zügen nur, wenn etwas auf dem Stapel liegt
  (keine Flash-Kreaturen/Removal am Zugende). Blocken funktioniert unabhängig davon.
- UI-Dialoge `CHOOSE_PILE`, `MULTI_AMOUNT` und die Mulligan-Unten-Auswahl sind nur über die Spikes getestet,
  nicht visuell.
- Kontrollwechsel-Karten (Mindslaver & Co.) sind nur nach dem XMage-Gating-Muster umgesetzt, nicht getestet.
- Statistik: Spalte `game_card_stats.cast` zählt auch gespielte Länder (Anzeige „gespielt“).
- Gelöschte Decks behalten ihre Statistik (`games.deck_id` ohne Fremdschlüssel).
- Es läuft immer nur **ein** Spiel; ein neues Spiel beendet das laufende (`GameRegistry`).
- **Hänger durch verlorene Antwort (XMage-Race) – umgangen 2026-10-02:** `HumanPlayer.waitForResponse` setzt
  `responseOpenedForAnswer = true` *vor* `synchronized(response) { wait() }`. Antwortet der CALL-Thread genau
  dazwischen, geht `notifyAll()` verloren und das Spiel wartet ewig. Vorher: 3 STALLs in 9 `humanSpike`-Spielen.
  Jetzt antwortet `GameHost.apply` erst, wenn der Spiel-Thread wirklich in `waitForResponse` → `wait()` steckt;
  zusätzlich stellt der Wachhund eine Antwort erneut zu, wenn XMage danach ohne neue Frage weiter wartet
  (Log-Warnung „Antwort ging verloren“, Zähler `activity.recovered`). Danach: 6/6 Spiele ohne STALL, 0 Neuzustellungen.
- **KI-Endlosrekursion beim Blocken:** `ComputerPlayer6.declareBlockers` → `replaceEvent` → `ChooseBlockersEffect`
  → `Combat.selectBlockers` → derselbe Bot → … → `StackOverflowError`, Spiel bricht ab („Spiel abgebrochen“).
  Tritt bei Karten auf, mit denen ein Spieler die Blocker eines anderen bestimmt. 1× in 6 `humanSpike`-Spielen
  (2026-10-02). Idee: Rekursionssperre in `MageLiteBot.selectBlockers`.
- `desktop/tools/shot.cjs`: Das versteckte Fenster zeichnet manchmal verzögert – bei verdächtigen Bildern
  nochmal mit längerer Wartezeit aufnehmen.
- **Neue Karten fehlen (Stand 2026-10-02):** XMage nimmt Karten aus der `unfinished`-Liste eines Sets nicht in die DB
  auf (z. B. Prepare-Karten aus Secrets of Strixhaven/SOC). Neue Sets wie Reality Fracture (FRA) sind gar nicht
  enthalten. Der Import zeigt beide Fälle getrennt an (`XmageUnfinished`). Auch 1.4.61V1 sperrt diese Karten noch;
  erst `master` hat sie freigeschaltet. Sobald ein Release (≥ 1.4.62) erscheint: `scripts\import-xmage.ps1 -XmageDir …`,
  danach `build.ps1` (die DB wird beim ersten Start neu aufgebaut).

## Ideen (nicht beauftragt)

- Deck-Editor mit Kartensuche, Vergleich zweier Deckversionen in der Statistik.
- „Goldfish ohne Bots“-Modus (nur eigenes Deck, Zugzähler) für reine Starthand/Kurven-Tests.
