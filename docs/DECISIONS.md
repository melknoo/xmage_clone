# Entscheidungen

Kurze Begründungen für die wichtigsten Weichenstellungen. Neue Entscheidungen unten anhängen. Ausnahme: der
Engine-Wechsel auf Forge steht als erster Eintrag unter „Technisch“, weil er viele ältere Einträge überholt; diese sind
als „(historisch, XMage)“ gekennzeichnet und bleiben als Begründung der damaligen Lösung stehen.

## Vom Nutzer festgelegt

| Thema | Entscheidung | Warum |
|---|---|---|
| Engine (historisch, XMage, bis 0.1.x) | XMage-Jars einbetten, nicht neu schreiben | ~43.000 Karten mit Regeln + fertige KI; ein Neubau wäre jahrelange Arbeit |
| Engine (seit 2026-10-09) | **Forge** einbetten (gepinnter master-Commit, GPL-3.0), nicht neu schreiben | ~33.500 Karten mit Regeln + schnelle KI, gepflegt; Begründung im ersten Eintrag unter „Technisch“ |
| Oberfläche | React-UI + lokaler Java-Prozess in Electron | modern und schnell zu bauen; Tauri schied aus (kein Rust auf dem Rechner) |
| Bot-Decks | mitgelieferte Commander-Decks (ursprünglich XMage-Samples, jetzt als Decktext v2) + eigene Decks | sofort spielbar, eigene Decks als Gegner möglich |
| Kartenbilder | Scryfall on demand + lokaler Cache | keine riesigen Bildpakete, nach dem ersten Laden offline |
| Import | Textliste + URL (Archidekt, Moxfield) | übliche Quellen der Nutzer |
| Extras | Bot-Tempo, Statistiken | Goldfishen soll schnell gehen und auswertbar sein |
| Gamification | ein Held (XP, Level, Titel) + Deck-Meisterschaft | Motivation ohne Einfluss aufs Spiel |
| Bewusst weggelassen | Undo/Rollback, Cheats, Achievements, Kosmetik | nicht gewünscht |

## Technisch

- **Engine: Forge statt XMage (2026-10-09, Branch `forge`, ab 0.2).** Löst „XMage-Jars einbetten“ ab; alle Einträge
  unten, die an XMage hängen, sind „(historisch, XMage)“ gekennzeichnet. POC-Messwerte und die Go-Entscheidung:
  Eintrag „Go nach dem POC“ am Ende, Zahlen je Phase in `docs/STATUS.md`.
  - **Gründe:**
    - Gepflegter `master` mit aktuellen Karten (~33.500). XMage 1.4.60 hat keine neuen Sets und sperrt Karten der
      `unfinished`-Liste.
    - Deutlich schnellere KI: Bots Ø ~1–2 s pro Zug (gemessen 0,87–1,2 s) statt Ø ~5 s, bis 50 s bei vollen Boards.
    - Kein Heap-OOM mehr (XMage: 15–19 % der Blitz-Spiele; Forge: Heap nach GC 190–265 MB über 20 Spiele, kein Anstieg).
    - Wegfall der XMage-Sonderbehandlungen: H2-Karten-DB mit Interrupt-Bug, Antwort-Race (`waitForResponse`),
      Blocker-Rekursion, kooperativer Simulationsstopp.
  - **Lizenz:** Forge ist GPL-3.0 und läuft im selben Prozess (abgeleitetes Werk). MageLite steht deshalb unter
    **GPL-3.0-or-later** (`LICENSE`, `LICENSES/THIRD-PARTY.md`, `SOURCE.txt` im Installer) und das Repo ist öffentlich
    (Quellangebot in `README.md`). `LICENSES/XMage-MIT.txt` bleibt nur für übernommene XMage-Dateien (Brackets,
    konvertierte Sample-Decks).
  - **Gepinnter Commit, lokal gebaut:** Forge-Releases sind selten und die Schnittstelle für fremde Oberflächen
    (PR #12091) gibt es nur auf `master`. `vendor/forge/FORGE_COMMIT` pinnt einen master-Commit;
    `scripts\import-forge.ps1` holt ihn (sparse/shallow), baut ihn mit Maven (wird bei Bedarf selbst geladen) und legt
    Jars + `res` nach `vendor/forge/`. Die Build-Ausgabe wird **nicht** getrackt; `build.ps1` und `deploy-fly.ps1`
    prüfen `manifest.json` gegen den Pin, `ForgeBoot` verweigert den Start, wenn Jars und Daten nicht zusammenpassen.
    Jetty/Servlet-API/slf4j-tinylog bleiben draußen (Javalin bringt Jetty 11, Logging läuft über reload4j).
    Karten-Fixes nur als `vendor/forge-overrides/cardsfolder/…` (kommen beim Import ins `cardsfolder.zip`), keine
    Shadow-Klassen. Docker baut Forge nicht, es kopiert den lokal gebauten Stand.
  - **Decktext v2 statt XMage-`.dck`:** Gespeichert, übertragen (Host-Link) und mitgeliefert wird ein einfacher Text
    (`Commander`/`Deck`, `1 Name (SET) NUM`) mit **Scryfall**-Set + Nummer (Forge-Set-Codes weichen ab). Er ist
    engine-neutral, entspricht den Exporten von Moxfield/Archidekt und füttert die Bild-URLs direkt. Alte Formate
    (XMage-`.dck`, Forge-`.dck`) lesen wir nur noch beim Import. **Migration V9** (`deck_format`, `dck_legacy`) stellt
    vorhandene Decks beim Start um (`DeckMigration`): vorher einmal `VACUUM INTO magelite.db.xmage-backup` (einziger
    Weg zurück), alter Text bleibt in `dck_legacy`, `updated_at`, Meisterschaft, Ordner und Sortierung bleiben
    unangetastet, Wiederholung stellt nichts doppelt um. Der Host-Link trägt eine Engine-Kennung
    (`X-MageLite-Engine: forge/1`); ältere Host-Apps schließt der Server mit 4426.
  - **Eager loading:** Kartenskripte werden beim Start vollständig geladen (`LOAD_CARD_SCRIPTS_LAZILY=false`):
    vollständige Namenslisten und keine Nachlade-Races, wenn mehrere Spiele in einer JVM laufen. Preis: Der Boot kostet
    bei jedem Start einige Sekunden (gemessen 3–4 s warm), dafür gibt es keine persistente Karten-DB mehr
    (`LegacyCleanup` löscht die alte H2-Datei).
  - **Kein `HostedMatch`, eigener Spiel-Thread:** `GameHost` baut `Match`/`Game` selbst und lässt sie auf einem Thread
    `Game-ml-<id>` laufen. Je Mensch gibt es eine `SeatGui` (`AbstractGuiGame`), die den Spiel-Thread in der
    Park-Schleife hält; Client-Antworten werden in die inbox gereiht und dort auf dem Spiel-Thread ausgeführt.
    Antwort-Threads und damit die XMage-Race entfallen; `ProtocolGuiGame` wurde verworfen (für entfernte Peers mit
    eigenem Thread gebaut). Schutznetze gegen Forge-Endlosschleifen (Remis nach > 3000 Entscheidungen je Zug,
    Autopilot nach > 200 Antworten auf eine Frage): Eintrag „Go nach dem POC“ am Ende.
  - **Protokoll und UI unverändert:** Die Engine emuliert die bisherigen Wire-Werte (`view/WireNames`: Phasen, Zonen,
    Typen; `view/IdCodec`: Forge-int-ids ↔ UUIDs, Permutation je Spiel verbirgt die Deck-Reihenfolge;
    Prompt-Arten und `PlayerAction`-Namen bleiben). Die UI änderte sich nur in Strings und Hinweisen.

- **Vorgebaute Jars statt XMage aus dem Quellcode bauen (historisch, XMage).** Kein Maven nötig, keine 10-Minuten-Builds,
  exakt die Version, die der Nutzer hatte. Referenz-Quellcode zum Nachlesen: Git-Tag `xmage_1.4.60V3`.
  Unter Forge gilt das Gegenteil (Pin + lokaler Maven-Build, s. o.).
- **Jars im Repo, Karten-DB nicht (historisch, XMage).** Die DB ist 104 MB (über GitHubs 100-MB-Limit) und lässt sich
  aus den Jars in ca. 40 s neu bauen (`CardDbManager` → `CardScanner.scan()`, nur wenn die DB leer ist). `mage-sets`
  (57 MB) liegt knapp über GitHubs Empfehlung, geht aber ohne LFS.
- **Kein Scan bei jedem Start (historisch, XMage).** Der XMage-Server scannt immer (~13 s + 8 s Bootstrap). XMage
  prüft DB-Version und Build-Zeit ohnehin selbst und leert die DB bei Abweichung – dann wird gescannt.
- **Eigener `GameHost` statt XMage-`GameController` (historisch, XMage; `GameHost` gibt es weiter, jetzt auf Forge, s. o.).**
  Der Server-Controller hängt an User/Session/Managern. Der Host macht nur das Nötige: Listener → DTOs/Prompts,
  Antworten über einen CALL-Thread (Gating wie `sendMessage`).
- **`MageLiteBot` mit `fastOpponentTurns` (historisch, XMage; gelöscht).** `ComputerPlayer7` rechnet in Main- und
  Kampfschritten **jedes** Spielers eine Minimax-Suche – bei 4 Spielern bis zu 3 Suchen pro Prioritätsrunde. Passen in
  fremden Zügen bei leerem Stapel ist der größte Geschwindigkeitsgewinn; in den Presets Bedacht/Max abgeschaltet.
- **Tempo-Presets** (`TempoSettings.Preset`; unter Forge nur noch Denkzeit = `Game.AI_TIMEOUT` und Pausen, die Spalten
  `skill`, `fastOpponentTurns`, `fastStack` sind historisch, XMage):

  | Preset | skill | Denkzeit | fastOpponentTurns | fastStack | Aktions-/Kampf-Pause |
  |---|---|---|---|---|---|
  | BLITZ | 1 | 2 s | an | an | 0 / 150 ms |
  | NORMAL | 2 | 4 s | an | an | 350 / 500 ms |
  | BEDACHT | 5 | 8 s | aus | aus | 500 / 700 ms |
  | MAX | 7 | 15 s | aus | aus | 600 / 800 ms |

  Die Aktionspause gibt es nur nach echten Aktionen (Zauber, Fähigkeit, Land), nicht nach bloßem Passen.
- **`fastStack` (Blitz/Normal, vom Nutzer so festgelegt; historisch, XMage – unter Forge nicht nachgebaut):** Nach jedem aufgelösten Stapelobjekt bekommt jeder Bot
  Priorität, und `ComputerPlayer7` sucht in Main-/Kampfschritten **immer**. Bei 112 Scute-Swarm-Triggern lief jede
  Suche ins Zeitlimit: 3 Bots × 2 s pro Trigger. Darum gilt:
  - Ein Bot ohne Nicht-Mana-Aktion passt sofort. Das Ergebnis ist identisch, nur die Suche entfällt.
  - Hat er auf ein Stapelobjekt gepasst, passt er auf gleiche sofort wieder. Gleich heißt laut `StackSig`:
    gleicher Controller, Quellname, Regeltext und gleiche Ziele, nur Fähigkeiten. Die Liste gilt bis zum leeren
    Stapel bzw. Schrittwechsel.
  - In Bedacht/Max rechnen die Bots bei Ketten weiter pro Objekt.
- **Gleiche Trigger beim Menschen (Regel gilt weiter, Umsetzung unter Forge in `AutoPassPolicy`; `StackSig` historisch, XMage):** Hat der Mensch auf ein Stapelobjekt gepasst, passt die Engine auf gleiche
  (`StackSig`) automatisch weiter, in allen Tempo-Stufen und auch bei „Passen manuell“. Ein anderes Objekt hält
  wieder an. F3 und ein leerer Stapel leeren die Liste.
- **Mehrfach-Angriff/-Block (Bedienung gilt weiter; Mechanik `GameHost.continueMacro` historisch, XMage):** Shift+Klick markiert Kreaturen. Klickt man danach ein Ziel an, schickt der Client
  `{t:"combat", ids, target}`. Die Engine klickt die Kreaturen nacheinander selbst an und beantwortet die
  Zielabfragen (`GameHost.continueMacro`). Bei allem Unerwarteten (Kosten, Ziel nicht wählbar) bricht sie ab und
  zeigt den Prompt. Sie antwortet nie mit „Abbrechen“, weil XMage bei Pflicht-Zielen sonst endlos neu fragt.

- **Auto-Passen (Regeln gelten weiter, Umsetzung unter Forge in `AutoPassPolicy`; `HumanSettings`/`NextStop` historisch, XMage)**: Prioritäts-Prompt ohne Nicht-Mana-Aktion → Engine antwortet selbst „passen“. Dazu Stopps in
  eigenen Hauptphasen, in der **Endphase jedes Gegners**, bei Angriffen/Blocks und neuen Stapelobjekten, Auto-Pass
  nach eigenem Zauber (`HumanSettings`). **Eigene Main 1/Main 2 halten immer** (Nutzerwunsch 2026-10-06: Main 2
  wurde sonst still übersprungen, wenn `getPlayable` nichts fand). Die gegnerische Endphase hält nur, wenn etwas
  Spontanes spielbar ist – sonst würde das Abbrechen von F9 nichts bringen. „Weiter“ zeigt im eigenen Zug das Ziel
  (`NextStop`, Kampf nur mit möglichen Angreifern; XMages `getAvailableAttackers(game)` ist vor Kampfbeginn leer,
  deshalb pro Gegner). F-Tasten-Passen ist jederzeit abbrechbar (F3, „Stopp“-Knopf, „Passen manuell“).
- **Auto-Mana** (`AutoPayer`, historisch, XMage – unter Forge nutzt die Engine Forges eigenes Auto-Bezahlen; Schalter und Verhalten bleiben): XMage lässt jede Manaquelle einzeln anklicken. Der Planer wird pro Schritt neu
  berechnet (Restkosten aus dem Prompt-Text „Pay {…}“): zuerst die am stärksten eingeschränkte Farbe mit der
  unflexibelsten passenden Quelle, generisch zuletzt. Farbwahl- und Fähigkeits-Dialoge beantwortet er passend.
  Bei Stillstand oder Unbezahlbarkeit zeigt er den normalen Prompt.
- **Convoke & Co.** (`SpecialPay`, historisch, XMage – Bedienung bleibt, unter Forge ein eigener Einberufen-Input in `PromptBridge`): XMage bietet Sonderbezahlung nur über die Antwort „special“ an (kein Knopf in
  den Optionen). Nach dem ersten Einberufen sind Länder für diesen Zauber gesperrt – deshalb startet Auto-Mana bei
  möglicher Sonderbezahlung **nicht** von selbst; „Länder automatisch“ zahlt nur mit Manaquellen (Teilzahlung), Klick
  auf eine leuchtende Kreatur beruft sie ein (Makro: Aktion → Kreatur → Farbe nach Engpass).
- **Statistik über einen feldlosen `StatsWatcher` + statischen `StatsSink`** (historisch, XMage; unter Forge speist
  `ForgeEvents` den unveränderten `StatsSink`), weil XMage Watcher für KI-Simulationen kopiert und ersetzt.
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
- **Stärkere Bots ohne LLM (2026-10-05, Nutzerwunsch; historisch, XMage – `GameStateEvaluator2`, `FfaAttack`, `BotTuning` sind gelöscht, unter Forge vergleicht `botArena` KI-Profile; Profil „MageLite“ = Forges „Default“ ohne zufällige Blocker-Trades):** Die XMage-KI ist für 2 Spieler gebaut. Drei Hebel für
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
  - **fly.io statt Heim-Laptop (Begründung historisch, XMage; Entscheidung gilt weiter):** die XMage-KI rechnet single-threaded mit Zeitbudget; `performance-2x`/4 GB mit
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
  - **Ein `HumanSeat` pro Mensch im `GameHost`**, kein zweiter Host und keine Spielkopie: XMage ist single-threaded
    (Forge unter `GameHost` ebenso: ein Spiel-Thread), es gibt immer hoechstens einen offenen Prompt. Er bekommt einen Besitzer (`promptSeat`); nur dieser darf
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
    seines Tisches), max. 8. Die Sicht baut XMage im Watcher-Modus (`viewer = null`, historisch; unter Forge
    `ForgeViewMapper` mit `viewer = null`), dadurch sind verdeckte Karten schon leer; Hand, Bibliothek, `lookedAt`, Prompts, Toasts und Belohnung gibt es nur am Sitz.
  - **Entfernen** nur durch den Gastgeber, nur vor dem Start; die Person bleibt gesperrt, bis der Gastgeber sie neu
    einlädt oder der Tisch schließt.
  - **Login-Bild** lädt der Browser direkt von Scryfall (`art_crop`) – kein Binary im Repo.
- **Tisch auf dem eigenen Rechner (Host-Link, 2026-10-08):**
  - **Relay statt Zweitserver:** fly bleibt einzige Lobby und einziges Konto-System; die App des Gastgebers hängt
    sich ausgehend per WebSocket an (`/ws/host`), fly packt Spielnachrichten nur in Umschläge um. Kein zweiter
    Login, keine Portfreigabe, keine Zusatzsoftware – und die Spielnachrichten bleiben unverändert.
  - **Der Gastgeber spielt über die fly-Seite** (Electron lädt sie im selben Fenster), nicht über seine lokale UI:
    eine Lobby-UI, ein Konto, eine Deckbibliothek; der Umweg Browser → fly → eigene Engine kostet ~30 ms.
  - **Spielende verbucht fly:** die Host-Engine schickt ein datenreines `GameResult` (inkl. `StatsSink`-Daten),
    fly vergibt XP je Konto und sendet das `gameOver` mit `reward` selbst; der Host sendet nie `gameOver` und
    schreibt nichts in seine lokale DB (`RewardHook = null`). Die lokale App des Hosts sieht das Spiel nicht
    (Nutzer 1 hat keinen Sitz) – fremde Hände bleiben verdeckt.
  - **Verbindung weg = warten, nicht sofort abbrechen:** Spieler-Sockets bleiben offen (`hostLink`-Banner), 60 s
    Gnadenfrist, `resume` + `attach` spielen den Stand nach; danach Abbruch ohne Statistik.
  - **Öffentlich = alle fly-Konten** (wie bisher), **privat = Tisch-Passwort**; eine Einladung ersetzt das
    Passwort. Kein Gast-Zugang ohne Konto (hätte ein zweites Konto-System gebraucht).
  - **Rechenlast lässt sich nicht auf 4 Spieler verteilen:** XMage (wie jetzt Forge) ist ein autoritativer Spielprozess, die Bot-KI
    läuft dort, wo das Spiel läuft. Gewinn ist, dass Bots eines gehosteten Tisches auf dem Host-PC rechnen.
  - **Setup-Download vom fly-Volume** (`/data/downloads`, `fly ssh sftp put`; später ersetzt, s. „Öffentliche
    Registrierung“), nicht GitHub Releases: das Repo war privat. Kein Zuschauen an Relay-Tischen (v1), kein lokales Solo-Spiel während des Hostens (eine Engine).
  - **Versalien per CSS** (`label`/`btn`/`chip`-Utilities), Text im JSX normal geschrieben; Tastenhinweise als `<kbd>`.
- **Öffentliche Registrierung und Kostenbremsen (2026-10-08):**
  - **Budget in der Engine statt fly-Limit:** fly kennt kein hartes Ausgabenlimit (Prepaid-Guthaben läuft einfach in
    die Rechnung über). Die Engine zählt ihre Laufzeit pro Monat (`uptime_month`); ab `MAGELITE_BUDGET_HOURS` sind
    öffentliche Konten bis Monatsende gesperrt und halten die Maschine nicht mehr wach. Freunde bleiben unbegrenzt –
    sie waren vorher schon da und ihre Nutzung ist überschaubar.
  - **Öffentlich = nur Host-Link:** selbst registrierte Konten lassen keine Spiele auf dem Server rechnen (Solo und
    eigene Tische laufen in ihrer App); Beitreten zu Tischen von Freunden ist erlaubt. So kostet ein öffentlicher
    Nutzer fast nur Lobby-Laufzeit, und der eine Server-Spielplatz bleibt den Freunden.
  - **Nur angemeldete Anfragen zählen als Aktivität:** anonyme Weckrufe (Crawler, Scanner, Startseite) beenden die
    Engine nach 3 statt 10 Minuten; Polls ruhen bei verstecktem Tab und nach 15 min ohne Eingabe.
  - **Setup als GitHub-Release in einem eigenen öffentlichen Repo** (`melknoo/magelite-releases`) statt auf dem
    fly-Volume: 308 MB pro Download wären Egress auf fly gewesen, und lange Downloads hielten die Maschine wach.
    Das Code-Repo blieb damals privat (seit 2026-10-09 öffentlich wegen GPL, s. Forge-Eintrag; das Releases-Repo
    bleibt getrennt). Ersetzt die Volume-Lösung vom Host-Link.
  - **Brevo + Turnstile:** Brevo sitzt in der EU (DSGVO) und ist bis 300 Mails/Tag kostenlos; Cloudflare Turnstile
    ist kostenlos und ohne Bilderrätsel. Ohne beides (und ohne `MAGELITE_PUBLIC_URL`) bleibt die Registrierung zu.
  - **Grenzen in der DB, nicht im Speicher:** die Maschine stoppt oft, In-Memory-Zähler wären nach jedem Kaltstart
    leer. Zählbasis ist `users.created_ip/created_at`.
  - **Keine Konto-Aufzählung:** Registrierung, „erneut senden“ und „Passwort vergessen“ antworten immer gleich;
    bestehende Konten bekommen eine Hinweis-Mail statt einer Fehlermeldung.

- **Go nach dem POC (Phase 0.4, 2026-10-09; Branch `forge`).** Entscheidung des Nutzers nach dem Forge-POC; Gründe,
  Lizenz, Pinning und Architektur stehen im ersten Eintrag dieser Liste („Engine: Forge statt XMage“).
  - **POC-Ergebnis** (Kriterien und Zahlen in `docs/STATUS.md`): 60 Spike-Spiele ohne Hänger, Bots Ø 0,87 s/Zug,
    Antwort→State p95 < 50 ms, Heap nach GC ~200 MB, Boot 3–4 s, 4 Menschen + Zuschauer ohne Lecks.
  - **Schutznetze, die Forge nicht hat:** ungültige Angriffe/Blocks werden aufgelöst statt endlos neu gefragt,
    > 3000 Entscheidungen in einem Zug beenden das Spiel als Remis (Regel 104.4b), > 200 Antworten auf eine Frage
    schalten den Sitz auf Autopilot, KI-Profil „MageLite“ ohne Zufalls-Trades beim Blocken (sonst Minuten auf
    Token-Boards).
