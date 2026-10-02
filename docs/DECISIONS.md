# Entscheidungen

Kurze Begründungen für die wichtigsten Weichenstellungen. Neue Entscheidungen unten anhängen.

## Vom Nutzer festgelegt

| Thema | Entscheidung | Warum |
|---|---|---|
| Engine | XMage-Jars einbetten, nicht neu schreiben | ~43.000 Karten mit Regeln + fertige KI; ein Neubau wäre jahrelange Arbeit |
| Oberfläche | React-UI + lokaler Java-Prozess in Electron | modern und schnell zu bauen; Tauri schied aus (kein Rust auf dem Rechner) |
| Bot-Decks | XMage-Sample-Commander-Decks + eigene Decks | sofort spielbar, eigene Decks als Gegner möglich |
| Kartenbilder | Scryfall on demand + lokaler Cache | keine riesigen Bildpakete, nach dem ersten Laden offline |
| Import | Textliste + URL (Archidekt, Moxfield) | übliche Quellen der Nutzer |
| Extras | Bot-Tempo, Statistiken | Goldfishen soll schnell gehen und auswertbar sein |
| Gamification | ein Held (XP, Level, Titel) + Deck-Meisterschaft | Motivation ohne Einfluss aufs Spiel |
| Bewusst weggelassen | Undo/Rollback, Cheats, Achievements, Kosmetik | nicht gewünscht |

## Technisch

- **Vorgebaute Jars statt XMage aus dem Quellcode bauen.** Kein Maven nötig, keine 10-Minuten-Builds, exakt die
  Version, die der Nutzer hatte. Referenz-Quellcode zum Nachlesen: Git-Tag `xmage_1.4.60V3`.
- **Jars im Repo, Karten-DB nicht.** Die DB ist 104 MB (über GitHubs 100-MB-Limit) und lässt sich aus den Jars in
  ca. 40 s neu bauen (`CardDbManager` → `CardScanner.scan()`, nur wenn die DB leer ist). `mage-sets` (57 MB) liegt
  knapp über GitHubs Empfehlung, geht aber ohne LFS.
- **Kein Scan bei jedem Start.** Der XMage-Server scannt immer (~13 s + 8 s Bootstrap). XMage prüft DB-Version und
  Build-Zeit ohnehin selbst und leert die DB bei Abweichung – dann wird gescannt.
- **Eigener `GameHost` statt XMage-`GameController`.** Der Server-Controller hängt an User/Session/Managern. Der Host
  macht nur das Nötige: Listener → DTOs/Prompts, Antworten über einen CALL-Thread (Gating wie `sendMessage`).
- **`MageLiteBot` mit `fastOpponentTurns`.** `ComputerPlayer7` rechnet in Main- und Kampfschritten **jedes** Spielers
  eine Minimax-Suche – bei 4 Spielern bis zu 3 Suchen pro Prioritätsrunde. Passen in fremden Zügen bei leerem
  Stapel ist der größte Geschwindigkeitsgewinn; in den Presets Bedacht/Max abgeschaltet.
- **Tempo-Presets** (`TempoSettings.Preset`):

  | Preset | skill | Denkzeit | fastOpponentTurns | Aktions-/Kampf-Pause |
  |---|---|---|---|---|
  | BLITZ | 1 | 2 s | an | 0 / 150 ms |
  | NORMAL | 2 | 4 s | an | 350 / 500 ms |
  | BEDACHT | 5 | 8 s | aus | 500 / 700 ms |
  | MAX | 7 | 15 s | aus | 600 / 800 ms |

- **Auto-Passen**: Prioritäts-Prompt ohne Nicht-Mana-Aktion → Engine antwortet selbst „passen“. Dazu Stopps nur in
  eigenen Hauptphasen, bei Angriffen/Blocks und neuen Stapelobjekten, Auto-Pass nach eigenem Zauber
  (`HumanSettings`).
- **Auto-Mana** (`AutoPayer`): XMage lässt jede Manaquelle einzeln anklicken. Der Planer wird pro Schritt neu
  berechnet (Restkosten aus dem Prompt-Text „Pay {…}“): zuerst die am stärksten eingeschränkte Farbe mit der
  unflexibelsten passenden Quelle, generisch zuletzt. Farbwahl- und Fähigkeits-Dialoge beantwortet er passend.
  Bei Stillstand oder Unbezahlbarkeit zeigt er den normalen Prompt.
- **Statistik über einen feldlosen `StatsWatcher` + statischen `StatsSink`**, weil XMage Watcher für
  KI-Simulationen kopiert und ersetzt.
- **XP-Formel** (`GameRecorder`):
  `round((40 + Platz[150/70/35/0] + 3·min(eigene Züge, 25) + erster Sieg des Tages 100 + erstes Spiel mit dem Deck 50)
  × Tempo[0,9/1,0/1,15/1,3] × Serie[1 + 0,1·(Siege in Folge − 1), max 1,5])`.
  Aufgeben vor dem 3. eigenen Zug = 0 XP. Level L→L+1 braucht `round(150·L^1,35)` XP.
  Meisterschaft: Stufen bei 0/200/500/900/1500/2300/3300/4600/6200/8200 Deck-XP.
- **Lokale Sicherheit:** Die Engine lauscht nur auf `127.0.0.1`. Im Release mit Zufallstoken (Query `token` oder
  Header `X-MageLite-Token`), damit Webseiten im Browser die lokale API nicht ansprechen können. Im Dev-Modus
  (`--dev`) ohne Token.
- **Git-Identität im Repo:** `melknoo` / `melknoo@users.noreply.github.com` (lokal gesetzt), damit keine private
  Mailadresse in öffentlichen Commits landet.
