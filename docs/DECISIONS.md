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

- **Auto-Passen**: Prioritäts-Prompt ohne Nicht-Mana-Aktion → Engine antwortet selbst „passen“. Dazu Stopps in
  eigenen Hauptphasen, in der **Endphase jedes Gegners**, bei Angriffen/Blocks und neuen Stapelobjekten, Auto-Pass
  nach eigenem Zauber (`HumanSettings`). **Eigene Main 1/Main 2 halten immer** (Nutzerwunsch 2026-10-06: Main 2
  wurde sonst still übersprungen, wenn `getPlayable` nichts fand). Die gegnerische Endphase hält nur, wenn etwas
  Spontanes spielbar ist – sonst würde das Abbrechen von F9 nichts bringen. „Weiter“ zeigt im eigenen Zug das Ziel
  (`NextStop`, Kampf nur mit möglichen Angreifern; XMages `getAvailableAttackers(game)` ist vor Kampfbeginn leer,
  deshalb pro Gegner). F-Tasten-Passen ist jederzeit abbrechbar (F3, „Stopp“-Knopf, „Passen manuell“).
- **Auto-Mana** (`AutoPayer`): XMage lässt jede Manaquelle einzeln anklicken. Der Planer wird pro Schritt neu
  berechnet (Restkosten aus dem Prompt-Text „Pay {…}“): zuerst die am stärksten eingeschränkte Farbe mit der
  unflexibelsten passenden Quelle, generisch zuletzt. Farbwahl- und Fähigkeits-Dialoge beantwortet er passend.
  Bei Stillstand oder Unbezahlbarkeit zeigt er den normalen Prompt.
- **Convoke & Co.** (`SpecialPay`): XMage bietet Sonderbezahlung nur über die Antwort „special“ an (kein Knopf in
  den Optionen). Nach dem ersten Einberufen sind Länder für diesen Zauber gesperrt – deshalb startet Auto-Mana bei
  möglicher Sonderbezahlung **nicht** von selbst; „Länder automatisch“ zahlt nur mit Manaquellen (Teilzahlung), Klick
  auf eine leuchtende Kreatur beruft sie ein (Makro: Aktion → Kreatur → Farbe nach Engpass).
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
  - **Sperre gegen gestapelte Suchen** (`MageLiteBot.SimPool`): XMage bricht eine Suche nach der Denkzeit per
    Interrupt ab. Einzelne Schritte prüfen den Interrupt aber nicht, etwa „alle Zielkombinationen einer
    Opfer-Fähigkeit erzeugen“, und laufen weiter. In der Arena stapelten sich solche Läufe bis zum
    `OutOfMemoryError`. Darum gilt:
    - Vor jeder Suche wartet der Bot höchstens seine Denkzeit lang, bis der statische Simulations-Pool von
      `ComputerPlayer6` leer ist (per Reflection).
    - Läuft dann noch etwas, passt er ohne Suche.
    - Eine *einzelne* ausufernde Suche kann den Heap trotzdem füllen (XMage-Problem, siehe `STATUS.md`).

- **Online-Betrieb (2026-10-05, Etappen E1/E2 aus `ONLINE-PLAN.md`):**
  - **fly.io statt Heim-Laptop:** die XMage-KI rechnet single-threaded mit Zeitbudget; `performance-2x`/4 GB mit
    Auto-Stop kostet nur waehrend gespielt wird (ca. 0,10 $/h). Shared-CPU-Maschinen sind gedrosselt.
  - **Cookie statt Token:** im Server-Modus geht der Einladungscode als HttpOnly-Cookie mit (auch bei `<img>` und
    WebSocket), Bild-URLs bleiben ohne Token und damit browser-cachebar. Lokal bleibt das Zufallstoken.
  - **Code im Cookie, Hash in der DB:** Rotieren/Entfernen wirkt sofort, weil jede Anfrage neu hasht; keine
    Session-Tabelle noetig.
  - **Ein `User`-Record in beiden Modi:** lokal immer Nutzer 1. So bleibt der lokale Modus bitgleich und die
    Routen haben genau einen Pfad (`Auth.user(ctx).id()`).
  - **Leerlauf-Exit in der Engine** (`--idle-exit-min`) zusaetzlich zu flys Auto-Stop: ein vergessener Tab haelt
    per WebSocket-Ping die Verbindung offen und damit die Maschine am Laufen. Die Engine beendet sich, wenn kein
    Spiel laeuft und N Minuten keine API-Anfrage kam; die UI verbindet sich nach dem naechsten Start neu.
  - **409 statt Abbruch:** startet Nutzer B ein Spiel, waehrend A spielt, wird B abgewiesen. Vorher beendete ein
    neues Spiel immer das laufende (nur sinnvoll bei einem Nutzer).
  - **Owner aus fly-Secrets** (`MAGELITE_OWNER_CODE`): kein Admin-Passwort im Repo; `ensureOwner` aktualisiert den
    Hash des vorhandenen Admins, so laesst sich der Code ueber die Secrets rotieren.
- **Mehrere Menschen in einem Spiel (E3, 2026-10-05):**
  - **Ein `HumanSeat` pro Mensch im `GameHost`**, kein zweiter Host und keine Spielkopie: XMage ist single-threaded,
    es gibt immer hoechstens einen offenen Prompt. Er bekommt einen Besitzer (`promptSeat`); nur dieser darf
    antworten. States werden pro Sitz gebaut (eigene Hand, eigene spielbare Objekte nur fuer den Prompt-Besitzer).
  - **Aufgeben = Zuschauen:** `leave` laesst nur den eigenen Sitz aufgeben; das Spiel laeuft fuer die anderen weiter,
    das `gameOver` mit Belohnung kommt am Ende. Erst wenn kein Mensch mehr im Spiel ist, geben die Bots auf (kein
    reines Bot-Spiel auf dem Server). Lokal (ein Mensch) bleibt es damit wie bisher: Aufgeben beendet das Spiel.
  - **Tempo nur vom Gastgeber** (erster Mensch, `hello.host`): sonst stellen sich Mitspieler gegenseitig das Tempo um.
  - **Statistik je Nutzer:** `games` und `game_card_stats` haben den Nutzer im Schluessel (V3), `game_seats` bleibt
    pro Spiel. Jeder bekommt XP auf seinen Helden und Meisterschaft auf sein Deck; ein `StatsSink` pro Spieler.
  - Weitere Menschen kommen bis zur Lobby (E4) nur ueber ein Dev-Feld in `POST /api/games`; auf dem Server ist
    der Weg geschlossen.
- **Lobby und Tische (E4, 2026-10-05):**
  - **Polling statt WebSocket** fuer die Lobby (`GET /api/tables/{id}` alle 1,5 s, Liste alle 3 s): wenige Nutzer,
    einfache Logik, kein zweiter Kanal mit Reconnect-Pflege. Der Spieltisch selbst bleibt WebSocket.
  - **Ein Tisch pro Gastgeber, ein Platz pro Nutzer**; Beitritt an einen anderen Tisch verlaesst den alten
    automatisch (ausser man ist dort Gastgeber). Schliesst der Gastgeber, ist der Tisch weg (kein Gastgeber-Wechsel).
  - **Offene Plaetze fallen beim Start weg** (2-4 Spieler, auch 1 gegen 1); der Gastgeber besetzt freie Plaetze
    bewusst mit Bots, statt dass sie automatisch aufgefuellt werden.
  - **Nach dem Spiel zurueck in die Lobby** mit denselben Plaetzen und Decks (Revanche per Klick); `lastGameId`
    bleibt fuer die Statistik.
  - Fremde Decks sieht man nur als Namen (`deckName`), die Deck-Angabe (`deck`) nur fuer den eigenen Platz bzw.
    die Bots des Gastgebers.
  - Tische nur im Server-Modus registriert (`Main`); lokal startet man weiter direkt ueber das Spiel-Setup.
- **Trennungen im Mehrspieler (E5, 2026-10-05):**
  - **Kein Timer, keine automatische Aufgabe:** Ein getrennter Mitspieler wird den anderen als "getrennt seit N s"
    angezeigt; nach 60 s darf jeder verbundene, nicht aufgegebene Mensch ihn "aufgeben lassen" (WS `kick`).
    Die Engine prueft die Grenze selbst (`GameHost.KICK_AFTER_MS`). Ein kurzer Reconnect (Tab-Wechsel, Funkloch)
    kostet so nichts; nur wenn das Spiel wirklich haengt, entscheidet ein Mensch.
  - Sind alle Menschen laenger als 10 min getrennt, bricht die Engine das Spiel ab (Leerlauf-Wachhund), damit
    die Maschine auf fly stoppen kann.
  - **Kompression:** Javalin komprimiert REST und Statik ab 1,5 kB per gzip von selbst (`/api/samples` 15 kB -> 3,3 kB);
    Jetty handelt `permessage-deflate` fuer den WebSocket aus, wenn der Browser es anbietet (alle gaengigen tun das).
    Nichts zu konfigurieren; gemessen statt vermutet.
- **Lobby-Chat, Freunde, Einladungen (2026-10-06):**
  - **Ein Social-Poll** (`GET /api/social`, alle 3 s) statt Lobby-WebSocket – gleiche Begründung wie bei den Tischen;
    ein Endpunkt liefert Chat-Delta (`after`-Cursor), Mitglieder, Freunde mit Status, Anfragen und Einladungen.
  - **fly-Leerlauf:** Jede API-Anfrage hält die Maschine wach. Deshalb pollt die UI nur außerhalb des Spiels, nicht bei
    verstecktem Tab und nicht nach 15 min ohne Maus/Tastatur („abwesend“). Ein offener, unbenutzter Tab kostet so
    höchstens 15 min + Idle-Exit.
  - **Lobby-Chat nur im Speicher** (letzte 100): nach Auto-Stop leer – bewusst (Nutzerwunsch), keine Moderations-/
    Löschpflichten für gespeicherte Nachrichten. Standardmäßig ist jeder drin; „Verlassen“ heißt unsichtbar (nicht in
    der Mitgliederliste, keine Nachrichten, Senden 409) und wird pro Konto gespeichert (`users.lobby_chat`).
  - **Freunde per Anfrage + Annehmen**, beidseitig; die Gegenanfrage nimmt an. Suche nur per exaktem Namen (keine
    Nutzerliste/Suche, damit man nicht alle Konten abgrasen kann); bei doppeltem Namen über den Lobby-Chat (per ID).
  - **Einladungen nur an Freunde**, im Speicher, 10 min gültig, nur sichtbar solange der Tisch in der Lobby einen
    freien Platz hat. Sie erscheinen überall außer im laufenden Spiel. Tische bleiben trotzdem per Link/Code offen
    (keine privaten Tische).
- **Redesign „Graphit & Glut“ (2026-10-07):**
  - **Kampf-Bedienung unverändert** (Klick = Engine fragt nach, Shift = markieren); nur die Darstellung wechselt von
    SVG-Pfeilen zu Etiketten an den Karten und einem „N ANGREIFER“-Chip am angegriffenen Spieler – Pfeile verdeckten
    Karten und skalierten schlecht bei 4 Spielern.
  - **Keine Bot-Füllung am Tisch:** freie Plätze bleiben leer; der Gastgeber setzt Bots selbst.
  - **Zuschauen** nur bei Tisch-Spielen im Server-Modus, nur wer an keinem Tisch sitzt (sonst verpasst er den Start
    seines Tisches), max. 8. Die Sicht baut XMage im Watcher-Modus (`viewer = null`), dadurch sind verdeckte Karten
    schon leer; Hand, Bibliothek, `lookedAt`, Prompts, Toasts und Belohnung gibt es nur am Sitz.
  - **Entfernen** nur durch den Gastgeber, nur vor dem Start; die Person bleibt gesperrt, bis der Gastgeber sie neu
    einlädt oder der Tisch schließt.
  - **Login-Bild** lädt der Browser direkt von Scryfall (`art_crop`) – kein Binary im Repo.
  - **Versalien per CSS** (`label`/`btn`/`chip`-Utilities), Text im JSX normal geschrieben; Tastenhinweise als `<kbd>`.
