# MageLite: Kontext für das Redesign

## Was ist MageLite?
Eine Desktop-App, mit der man **eigene Magic-Commander-Decks gegen drei KI-Bots** spielt („goldfishen“):
Free-for-All mit vier Spielern und je 40 Leben. Regeln, Karten und KI stammen unverändert aus der Open-Source-Engine
XMage. MageLite ersetzt deren altmodischen Java-Client durch eine moderne React-Oberfläche.

Zwei Betriebsarten:
- **Lokal** (Electron-Desktop-App): eine Person, keine Anmeldung.
- **Server/Online** (gleiche UI im Browser oder in Electron): Anmeldung per Einladungscode oder E-Mail und Passwort,
  Lobby mit Tischen, bis zu vier Menschen an einem Tisch, freie Plätze werden mit Bots aufgefüllt, Tisch- und Spielchat.

## Zielgruppe und Nutzung
- Erfahrene Magic-Spieler, die Commander kennen und neue Decks testen wollen. Sie wissen, was Stapel, Priorität,
  Phasen und Kampf sind.
- Sie spielen lange, konzentrierte Sessions am Desktop mit Maus und Tastatur, mehrere Partien hintereinander.
- Sie spielen solo gegen Bots oder abends online mit 1–3 Freunden.
- Wichtig ist ihnen: schneller Überblick über vier Spielfelder, gut lesbare Karten (Hover-Zoom), klare Hinweise,
  was gerade erwartet wird, und kein Klick-Ballast.

## Plattform und Maße
- Standardfenster **1680×1000**, Mindestgröße **1280×760** (Electron `minWidth`/`minHeight`). Kein Mobile-Layout nötig.
- Heute nur minimal responsiv: Das Spielbrett hat feste Breiten (Seitenleiste 320 px, Infospalte 170 px,
  Handhöhe 150 px, Stapelpanel 440 px).
- Dunkler Hintergrund ist gesetzt (Kartenbilder wirken auf dunklem Grund am besten).

## Informationsarchitektur
Linke Navigationsleiste (84 px, Icon + Label): **Held**, **Spielen**, **Decks**, **Statistik**. Im Server-Modus
kommen **Einladungen** (nur Admin) und unten ein Konto-Knopf hinzu. Läuft ein Spiel, erscheint unten „Zum Tisch“.

| Screen | Inhalt | Modus |
|---|---|---|
| Splash / Engine-Start | Logo, Spinner, „Kartendatenbank wird geladen“ | lokal |
| Login | Tabs „Einladungscode“ und „E-Mail & Passwort“ | Server |
| Held (Home) | Level-Ring, Name, Titel, XP-Balken, Kacheln (Spiele, Siege, Serie), Karten „Neues Spiel“ und „Schnellstart“; Gäste sehen ein Banner „Konto sichern“ | beide |
| Spielen-Setup | eigenes Deck, Bot-Tempo (Blitz, Normal, Bedacht, Max), drei Gegner-Plätze, „Spiel starten“; Overlay **Deck-Auswahl** (Meine Decks, Vorgefertigt mit 70 Decks, Suche, Zufällig) | lokal, online „Allein üben“ |
| Lobby | Tischliste, „Tisch eröffnen“, „Allein üben“ | Server |
| Tisch | vier Plätze (offen, Mensch, Bot), Deckwahl je Platz, Tempo, Tisch-Chat, Start (Gastgeber), Einladungslink | Server |
| Decks | Kartenraster mit Commander-Art, Farben, Meisterschafts-Stufe, Spielen, Bearbeiten, Löschen; Dialog **Deck importieren/bearbeiten** (Link von Archidekt/Moxfield oder Textliste, rechts Vorschau mit Legalität und fehlenden Karten) | beide |
| Statistik | Tabs „Übersicht“ (KPI-Kacheln, Formkurve, Deck-Tabelle mit Meisterschaft, Tempo, Mulligans, häufigste Gegner, Kartenstatistik je Deck) und „Verlauf“ (Partienliste mit Platz und XP) | beide |
| Konto | Anmeldestatus, Abmelden, „Konto sichern“ (E-Mail und Passwort setzen) bzw. ändern | Server |
| Einladungen | Freunde anlegen, Code bzw. Link kopieren, rotieren, löschen | Server, Admin |
| Spielbrett | siehe unten | beide |

## Spielbrett: Anatomie
- **Kopfleiste:** Logo, Runde und wer am Zug ist, **Phasenleiste** (Enttappen, Versorgung, Ziehen, Main 1,
  Kampfbeginn, Angreifer, Blocker, Schaden, Kampfende, Main 2, Ende), Tempo-Umschalter, Toggles Auto-Mana und
  Auto-Passen, Ton, „Menü“.
- **Drei Gegner-Pods** nebeneinander: Commander-Avatar, Name, Deckname, Leben (groß), Zonenzähler (Bibliothek,
  Hand, Friedhof, Exil), Badges „am Zug“ und „denkt…“, Verbindungsstatus. Darunter das Spielfeld des Gegners
  (Karten in kleiner Größe, gestapelt nach Typ).
- **Eigener Bereich:** Infospalte (Avatar, Leben, Zonen, Commander-Schaden, Commander in der Command Zone mit
  Steuer), eigenes Spielfeld (Länder, Kreaturen, sonstige), darunter die **Hand** (überlappend aufgefächert).
- **Stapel-Panel** (mittig, einklappbar) mit Zauber und Fähigkeiten; **Zielpfeile** vom Stapelobjekt zu den Zielen.
- **Aktionsleiste (PromptBar)** unten: links die Aufforderung der Engine (oft englisch, z. B. „Play spells and
  abilities“, „Select blockers“), rechts Knöpfe wie „Bis Zugende F5“, „Nächster Zug F4“, „Bis zu meinem Zug F9“
  und die Hauptaktion („Weiter“, „Angriff bestätigen“, „Blocker bestätigen“ …) mit Tastenhinweis.
- **Seitenleiste rechts (320 px):** oben **Karten-Zoom** (große Karte plus Text), darunter **Spielverlauf** (nach
  Zügen gruppiert, Filter „Wichtiges/Alles“) bzw. **Chat**, wenn mehrere Menschen spielen.
- **Overlays:** Kampfpfeile (Angreifer → Spieler bzw. Planeswalker, Blocker → Angreifer), FX-Ebene (fliegende
  Karten zwischen Zonen, schwebende Schadens- und Lebenszahlen), Ereignisleiste unten links, Reveal-Popups oben
  rechts (aufgedeckte Karten), Toasts oben mittig.

## Interaktionszustände (alle müssen im neuen Design klar unterscheidbar sein)
- Kartenhervorhebungen: **spielbar** (heute türkis), **Mana-Quelle**, **mögliches Ziel** (gold pulsierend),
  **gewählt** (grün), **angreifend** (rot), **blockend** (blau), **getappt** (90° gedreht), **beschworen-krank** (💤),
  Zähler, Stärke/Widerstand, Schaden.
- Shift+Klick markiert mehrere Kreaturen („N markiert…“). „Alle angreifen“ braucht einen zweiten Klick zur
  Bestätigung. „Angriff zurücksetzen“ macht die Angriffserklärung rückgängig.
- Wartezustände: „Wartet auf dich“, „Bot rechnet…“ (mit CPU-Balken), getrennter Mitspieler („getrennt seit N s“
  plus „aufgeben lassen“), keine Verbindung.
- Tastatur: Leertaste, Enter oder F2 für die Hauptaktion, Esc (Markierung lösen bzw. Abbrechen bzw. Menü),
  F3–F11 zum Passen, Tab minimiert einen Dialog zu einer Pille „Dialog öffnen“, damit man aufs Brett schauen kann.

## Dialoge im Spiel (gemeinsame Modal-Komponente, Seitenleiste bleibt frei)
| Dialog | Wann |
|---|---|
| Starthand / Mulligan | Spielbeginn: Hand groß zeigen, „Mulligan“ oder „Behalten“ |
| Starthand-Aktion | z. B. Gemstone Caverns: „Auf das Spielfeld legen?“ |
| Auswahlliste (Fähigkeit, Modus) | mehrere Fähigkeiten einer Karte; optional „×1/×3/×5/×10 wiederholen“ |
| Auswahl (Text oder Karten) | z. B. Farbe, Kartenname, Kreaturentyp; mit Suche ab 12 Einträgen |
| Ersatzeffekt | mehrere Ersatzeffekte gleichzeitig, gruppiert, „für dieses Spiel merken“ |
| Anzahl / Mehrfach-Anzahl | Schieberegler bzw. Zahleneingaben (z. B. Schaden verteilen) |
| Stapel aufteilen | zwei Stapel, einen wählen |
| Karte wählen | Ziele außerhalb des Spielfelds (Friedhof, Bibliothek) |
| Zonen-Ansicht | Friedhof, Exil, oberste Bibliothekskarte |
| Pausemenü | Optionen (Auto-Mana, Auto-Passen, Effekte, Ton, Verlauf), Bot-Tempo, Aufgeben mit Bestätigung, nach dem Ausscheiden „Zuschauen / Zurück zum Tisch / Hauptmenü“ |
| Spielende | „Sieg!“ bzw. „Platz N“, Platzierungen, animierter XP-Balken, LEVEL UP, Aufschlüsselung der XP, Deck-Meisterschaft; „Tisch ansehen“ klappt das Ergebnis ein |

## Gamification (rein Meta, kein Einfluss aufs Spiel)
- **Ein Held** pro Nutzer: XP aus Partien, Level, Titel (Novize → Lehrling → …), Siegesserie.
- **Deck-Meisterschaft:** jedes eigene Deck steigt in Stufen (★ Stufe 1–10) mit seiner XP.
- Belohnung wird am Spielende gezeigt (XP-Hochzählen, Level-Up, Meisterschafts-Aufstieg).
- **Ausdrücklich nicht gewollt:** Achievements, kosmetische Unlocks bzw. Shop, Währungen, Lootboxen, Undo,
  Cheat-Werkzeuge.

## Aktuelles Designsystem (siehe `current-theme.css`)
- Schriften: **Inter Variable** (Text), **Cinzel** 600/700 (Display, Überschriften in Kapitälchen-Optik).
- Farben: Ink-Skala von `#07090f` bis `#e7eaf2` (Grundflächen); Akzente **Arcane** `#38e1c6`/`#1fc4aa`
  (spielbar, aktiv), **Gold** `#ffd98a`/`#f5b84a`/`#e09a1f` (Primäraktion, Titel, Ziel), **Blood** `#ff6b6b`/`#e5484d`
  (Gefahr, Angriff), **Sky** `#5cb8ff` (Blocker). Spielerfarben: Türkis, Gold, Blau, Violett.
- Oberflächen: `.glass` (halbtransparenter Verlauf, 10 px Blur, feine Kante), `.bg-table` (radiale Verläufe).
- Buttons: `btn-primary` (Goldverlauf), `btn-arcane`, `btn-ghost`, `btn-danger`; `kbd` für Tastenhinweise.
- Icons: **Emojis** (🛡️ ⚔️ 🃏 📊 🪦 🌀 📚 👑 💤 …). Manasymbole über `mana-font`.
- Animation: `motion` (Framer Motion); Screens blenden ein, die FX-Ebene animiert Kartenbewegungen.
- Es gibt kaum gemeinsame Komponenten (nur `Modal`, `CardView`, `PasswordInput`); vieles ist inline mit
  Tailwind-Klassen gebaut. Daher gibt es Ad-hoc-Farben (Tailwind purple, emerald, amber, orange) und
  unterschiedliche Tab-, Chip- und Panel-Stile.

## Technik für die Übergabe
- React 19, TypeScript, Vite, **Tailwind CSS 4** (Tokens in `@theme`, eigene Klassen per `@utility`,
  Verläufe heißen `bg-linear-to-*`), Zustand (zustand), Animation `motion`, Schriften über `@fontsource`.
- Komponenten, die der Umbau trifft:
  - **Screens:** `HomeScreen`, `PlaySetupScreen` (mit `DeckPicker`, `SeatCard`), `DecksScreen` (mit
    `ImportDialog`), `StatsScreen`, `LobbyScreen`, `TableScreen`, `LoginScreen`, `AccountScreen`, `AdminScreen`.
  - **Spielbrett:** `GameScreen`, `PhaseBar`, `OpponentPod`, `PlayerInfo`, `Battlefield`, `Hand`, `StackPanel`,
    `PromptBar`, `PromptDialogs`, `PauseMenu`, `GameOverOverlay`, `Side` (ZoomPanel, Log, Chat, Toasts, Reveals),
    `FxLayer`, `CombatOverlay`, `TargetOverlay`, `ActivityIndicator`.
  - **Gemeinsam:** `CardView`, `Modal`.
- Kartenbilder werden geladen; bis dahin zeigt `CardView` einen **Textrahmen** (Name, Kosten, Typ, Regeltext,
  Farbverlauf nach Kartenfarbe). Diesen Fallback siehst du auf manchen Screenshots. Er sollte im neuen Design
  ebenfalls gut aussehen.
- Engine-Texte (Aufforderungen, Log, Kartennamen) sind englisch und kommen dynamisch. Längen schwanken stark.

## Was bleibt
- Alle Funktionen, Abläufe, Hotkeys und Texte der UI (Deutsch). Layout, Hierarchie, Komponenten und Look dürfen
  sich ändern.
- Dunkles Grundthema auf dem Spielbrett.
