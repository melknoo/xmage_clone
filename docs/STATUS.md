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
| P5 | Installer, gebündelte Java-Laufzeit, Startoptimierung, Feinschliff | ⏳ offen |

Zusätzlich umgesetzt (nicht im Plan): **Auto-Mana** (`AutoPayer`): automatisches Bezahlen mit passenden Quellen,
Fallback auf manuelles Klicken.

## Gemessen / getestet

- Engine-Start: 2–6 s mit vorhandener Karten-DB; **erster Start** baut die DB auf: ca. 40–45 s.
- 4-Bot-Spiele (Tempo Blitz): 3/3 ohne Fehler, Ø ca. 5 s pro Spielerzug, einzelne Züge bis ca. 50 s bei vollen Boards,
  Heap-Spitze ca. 2 GB (`-Xmx3g`).
- `humanSpike`: insgesamt 9 Spiele, 0 Fehler, 0 Hänger. Mit Auto-Mana kam **kein** Mana-Prompt mehr beim Spieler an.
- `TextDeckParserTest`: 7/7 (MTGA, Archidekt-Kategorien, Leerzeilen-Commander, Kandidaten, DFC, .dck-Roundtrip).
- Archidekt-Import live: OK (100 Karten, legal erkannt).
- End-to-End: Spiel mit importiertem Deck bis zum natürlichen Ende → XP, Meisterschaft, Kartenstatistik gespeichert.
- Frischer Klon: `scripts\build.ps1` läuft durch, App startet und baut die DB selbst auf.
- UI visuell geprüft (Screenshots): Startseite, Deck-Auswahl, Mulligan, Spieltisch mit Kampf/Stapel/Log.

## Offene Punkte (priorisiert)

1. **Installer / Verteilung (P5)**
   - `electron-builder` (portable EXE oder NSIS) mit `extraResources`: `engine/lib`, `ui/dist`, `vendor/xmage`
     (ohne DB), plus per `jlink` erzeugte Java-Laufzeit. Module laut jdeps mindestens: `java.base`, `java.desktop`,
     `java.sql`, `java.naming`, `java.net.http`, `jdk.crypto.ec`. Vor Abschluss mit `java --list-modules` gegenprüfen,
     Jetty/Javalin evtl. zusätzlich `java.management`.
   - `desktop/src/engine.cjs` kennt die Pfade für die gepackte App bereits (`process.resourcesPath`).
2. **Einstellungs-Screen**: Stopps pro Phase (aktuell fest in `HumanSettings`), Auto-Passen, Auto-Mana,
   Lautstärke, Bild-Cache leeren.
3. **Bedien-Komfort**
   - Angreifen per Drag & Drop auf einen Gegner, „Alle angreifen“ mit Zielwahl (geplante Makros/`AutoAnswerQueue`).
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
- In Blitz/Normal (`fastOpponentTurns`) reagieren Bots in fremden Zügen nur, wenn etwas auf dem Stapel liegt
  (keine Flash-Kreaturen/Removal am Zugende). Blocken funktioniert unabhängig davon.
- UI-Dialoge `CHOOSE_PILE`, `MULTI_AMOUNT` und die Mulligan-Unten-Auswahl sind nur über die Spikes getestet,
  nicht visuell.
- Kontrollwechsel-Karten (Mindslaver & Co.) sind nur nach dem XMage-Gating-Muster umgesetzt, nicht getestet.
- Statistik: Spalte `game_card_stats.cast` zählt auch gespielte Länder (Anzeige „gespielt“).
- Gelöschte Decks behalten ihre Statistik (`games.deck_id` ohne Fremdschlüssel).
- Es läuft immer nur **ein** Spiel; ein neues Spiel beendet das laufende (`GameRegistry`).
- `desktop/tools/shot.cjs`: Das versteckte Fenster zeichnet manchmal verzögert – bei verdächtigen Bildern
  nochmal mit längerer Wartezeit aufnehmen.

## Ideen (nicht beauftragt)

- Deck-Editor mit Kartensuche, Vergleich zweier Deckversionen in der Statistik.
- „Goldfish ohne Bots“-Modus (nur eigenes Deck, Zugzähler) für reine Starthand/Kurven-Tests.
