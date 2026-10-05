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

  | Preset | skill | Denkzeit | fastOpponentTurns | fastStack | Aktions-/Kampf-Pause |
  |---|---|---|---|---|---|
  | BLITZ | 1 | 2 s | an | an | 0 / 150 ms |
  | NORMAL | 2 | 4 s | an | an | 350 / 500 ms |
  | BEDACHT | 5 | 8 s | aus | aus | 500 / 700 ms |
  | MAX | 7 | 15 s | aus | aus | 600 / 800 ms |

  Die Aktionspause gibt es nur nach echten Aktionen (Zauber, Fähigkeit, Land), nicht nach bloßem Passen.
- **`fastStack` (Blitz/Normal, vom Nutzer so festgelegt):** Nach jedem aufgelösten Stapelobjekt bekommt jeder Bot
  Priorität, und `ComputerPlayer7` sucht in Main-/Kampfschritten **immer**. Bei 112 Scute-Swarm-Triggern lief jede
  Suche ins Zeitlimit: 3 Bots × 2 s pro Trigger. Darum gilt:
  - Ein Bot ohne Nicht-Mana-Aktion passt sofort. Das Ergebnis ist identisch, nur die Suche entfällt.
  - Hat er auf ein Stapelobjekt gepasst, passt er auf gleiche sofort wieder. Gleich heißt laut `StackSig`:
    gleicher Controller, Quellname, Regeltext und gleiche Ziele, nur Fähigkeiten. Die Liste gilt bis zum leeren
    Stapel bzw. Schrittwechsel.
  - In Bedacht/Max rechnen die Bots bei Ketten weiter pro Objekt.
- **Gleiche Trigger beim Menschen:** Hat der Mensch auf ein Stapelobjekt gepasst, passt die Engine auf gleiche
  (`StackSig`) automatisch weiter, in allen Tempo-Stufen und auch bei „Passen manuell“. Ein anderes Objekt hält
  wieder an. F3 und ein leerer Stapel leeren die Liste.
- **Mehrfach-Angriff/-Block:** Shift+Klick markiert Kreaturen. Klickt man danach ein Ziel an, schickt der Client
  `{t:"combat", ids, target}`. Die Engine klickt die Kreaturen nacheinander selbst an und beantwortet die
  Zielabfragen (`GameHost.continueMacro`). Bei allem Unerwarteten (Kosten, Ziel nicht wählbar) bricht sie ab und
  zeigt den Prompt. Sie antwortet nie mit „Abbrechen“, weil XMage bei Pflicht-Zielen sonst endlos neu fragt.

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
- **Stärkere Bots ohne LLM (2026-10-05, Nutzerwunsch):** Die XMage-KI ist für 2 Spieler gebaut. Drei Hebel für
  Commander FFA, jeder pro Bot abschaltbar (`BotTuning`, für die Arena):
  - **FFA-Bewertung** – `GameStateEvaluator2` bewertete nur gegen den *ersten* Gegner der Sitzliste. Fiel der in
    einer Simulation auf 0 Leben, galt das als Partiesieg. Neu:
    `eigener Score − (w · stärkster Gegner + (1 − w) · Σ Gegner / Gegnerzahl) + Bonus je ausgeschiedenem Gegner`
    (`w = 0,5`, Bonus 5000; 20 Leben ≈ 10000).
    - Die Klasse ist `final`, die Methode `static`, und `ComputerPlayer6/7` rufen sie direkt auf. Deshalb liegt in
      der Engine eine **gleichnamige Ersatzklasse**, die vor dem Jar geladen wird (Nutzerentscheidung). Die Jars
      bleiben unverändert.
    - Electron nennt das Engine-Jar deshalb ausdrücklich vor `lib/*`, und `Main` prüft das beim Start.
    - Mehr Suchtiefe hilft nicht: XMage begrenzt auf 5000 Knoten und wirft ab 5100 einen Fehler.
  - **FFA-Angriffe** (`FfaAttack`) – `ComputerPlayer6.declareAttackers` schickte alle Angreifer an den ersten
    Gegner und prüfte sie nur gegen dessen Blocker. Neu:
    - Lethal gegen irgendeinen Gegner.
    - Sicherheit pro Verteidiger.
    - Ziel nach „Anteil an seinem Leben × Bedrohung“.
    - Blocker gegen einen tödlichen Gegenschlag zurückhalten.
  - **`reactInCombat`** (nur Normal) – trotz `fastOpponentTurns` in fremden Kampfschritten rechnen, wenn eine
    Spontanaktion möglich ist.
  - **MCTS** (`ComputerPlayerMCTS`) nur in der Arena gemessen, nicht im Produkt (siehe `STATUS.md`).
