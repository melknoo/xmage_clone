# Handoff: MageLite Redesign „Graphit & Glut“

## Überblick
Neues UI für MageLite (Electron + Browser, React 19). Commander-FFA gegen drei Bots oder online mit Freunden. Es ändern sich **nur Darstellung und UX**. Abläufe, Engine, Regeln, Hotkeys und deutsche UI-Texte bleiben. Neu gestaltet sind außerdem die zuletzt lokal gebauten Funktionen: Lobby-Chat, Freunde, Tisch-Einladungen.

## Über die Design-Dateien
Die Dateien in `prototypes/` sind **Design-Referenzen in HTML**: klickbare Prototypen, die Aussehen und Verhalten zeigen. Sie sind **kein Produktionscode**. Aufgabe ist, sie im bestehenden MageLite-Stack nachzubauen: React 19 + TypeScript + Vite, **Tailwind CSS 4** (Tokens in `@theme`, eigene Klassen per `@utility`), `zustand`, `motion`, Schriften über `@fontsource`, Icons über **`lucide-react`**. Keine schweren UI-Bibliotheken.
Die Bestandskomponenten (`HomeScreen`, `GameScreen` …) bleiben erhalten und werden Screen für Screen umgestellt. Die Prototypen öffnest du direkt im Browser. `support.js` ist nur deren Runtime. Verweise auf `_ds/…` darin sind Altlasten aus der Design-Umgebung und dürfen ins Leere laufen.

## Fidelity
**High-Fidelity.** Farben, Typo, Abstände, Zustände und Texte sind final. Bitte pixelgenau nachbauen. Kartenbilder und Commander-Art kommen live von Scryfall.

## Dateien
| Datei | Inhalt |
|---|---|
| `magelite-theme.css` | **Ersatz für `current-theme.css`**: alle Tokens (`@theme`), Keyframes, `@utility` für Buttons, Chips, Flächen, Kartenzustände |
| `prototypes/MageLite Designsystem.dc.html` | Referenzseite: Farben, Typo-Skala, Abstände, Radien, Ebenen, alle Komponenten mit Zuständen, Icon-Mapping, Kartenzustands-Regeln, Motion |
| `prototypes/MageLite Spielbrett.dc.html` | Spielbrett-Prototyp, 11 Zustände, 1680×1000 und 1280×760 |
| `prototypes/MageLite Meta.dc.html` | Held, Spielen-Setup + Deck-Auswahl, Decks + Import/Bearbeiten/Löschen, Statistik (Übersicht, Deck-Karten, Verlauf), Leerzustände |
| `prototypes/MageLite Online.dc.html` | Login, Laden, Startseite mit Lobby-Chat/Freunden, Lobby, Tisch, Konto, Einladungen (Admin), Einladungskarte, Verbindungsverlust |
| `prototypes/BoardCard.dc.html` | Referenz für `CardView`: Rahmen, Overlays, Zustände |

---

## Designprinzipien
1. **Glut-Orange `#ff7a3d` heißt immer „jetzt du“:** am Zug, spielbar, Hauptaktion, aktiver Tab, Fokus-Kontur in Inputs. Sonst nirgends.
2. **Jede Zustandsfarbe hat genau eine Bedeutung.** Gelb = Ziel/XP/Belohnung, Grün = gewählt/Erfolg/online, Karmin = Angriff/Fehler/Gefahr, Kobalt = Blocker/Info.
3. **Farbe plus Form:** Ziel, gewählt, Angreifer und Blocker tragen zusätzlich ein Icon-Abzeichen.
4. **Haarlinien statt Kästen.** Kein Glow, kein Blur, keine Verläufe. Einzige Ausnahme sind Art-Overlays über Commander-Bildern. Schatten gibt es nur für schwebende Ebenen.
5. **Sprachmix:** Versalien (Barlow Condensed) **nur** für eigene deutsche Labels und Buttons. Engine-Text, Kartennamen und englische Dialogtitel immer in IBM Plex Sans in Originalschreibung.
6. Tastenhinweise stehen **im** Button (`kbd`). Pro Ansicht gibt es höchstens einen Primär-Button.

## Design-Tokens
Die vollständige Liste steht in `magelite-theme.css`. Kurzfassung:

**Flächen:** bg-0 `#0d0c0b` (Fenster, Inputs, Kartenreihen) · bg-1 `#121110` (Brett, Screens) · bg-2 `#161513` (Seitenleiste) · bg-3 `#1a1917` (Fläche, Modal, Aktionsleiste) · bg-4 `#23211e` (Hover, Toast)
**Linien:** line-1 `#22201d` (Listenzeilen) · line-2 `#2a2824` (Trenner) · line-3 `#34312c` (Konturen) · line-4 `#4a463f` (Sekundär-Button)
**Text:** fg-1 `#ece7de` · fg-2 `#c9c3b8` · fg-3 `#9b958a` · fg-4 `#6f6a62` (nur Icons/große Zahlen) · fg-5 `#5a564f` (erledigt)
**Signal:** ember `#ff7a3d`, hover `#ff8f5a`, press `#e8662b`, Schrift auf Ember `#121110`
**Zustände:** target `#ffd23f` · chosen `#7ee08a` · attack `#ef3e56` · block `#5aa9ff`
**Plätze** (nur Avatar-Kante und Verlauf): `#ff7a3d` Du · `#8fb3c9` · `#a99bc4` · `#c9a38f`
**Scrim:** `rgba(13,12,11,.72)`, kein Blur.
**Schatten:** float `0 24px 64px rgba(0,0,0,.6), 0 0 0 1px #34312c` · Toast/Popover `0 12px 32px rgba(0,0,0,.5)` · Karte `0 0 0 1px rgba(0,0,0,.8), 0 1px 3px rgba(0,0,0,.6)`
**Radien:** xs 2 (Chips, kbd) · sm 3 (Buttons, Inputs, Avatare) · md 4 (Panels, Modal, Kacheln) · Kartenbild 4,5 %. Keine Pillen, keine runden Avatare. Einzige Ausnahme: Status-Punkte (8 px, rund).
**Abstände (px):** 2 · 4 · 6 · 8 · 12 · 16 · 20 · 24 · 32 · 40 · 56. Panel-Innenabstand 16, Modal 20, Screen-Rand 56/64 (1680) bzw. 28–36 (1280).

### Typografie
| Token | Schrift | Größe/Zeile | Verwendung |
|---|---|---|---|
| life-xl | Barlow Condensed 600, tabular | 84/0.8 (1280: 58) | eigenes Leben |
| life-l | Barlow Condensed 600 | 46/0.8 (1280: 36) | Gegner-Leben |
| display | Barlow Condensed 600, Versalien .02em | 40/1 | „PLATZ 1“, Level-Zahl (Held 130–160) |
| h1 | Barlow Condensed 600, Versalien .03em | 32–36/1 | Screen-Titel |
| h2 | Barlow Condensed 600 | 20–26/1.1 | Decknamen, Tischnamen |
| label | Barlow Condensed 600, Versalien .12–.14em, fg-3 | 12–14/1 | Sektionslabels |
| button | Barlow Condensed 600, Versalien .06–.08em | 15 (sek.), 18 (primär) | |
| body-l | IBM Plex Sans 500 | 16/1.45 | Engine-Prompt |
| body | IBM Plex Sans 400 | 13.5–14/1.45 | UI, Regeltext |
| body-s | IBM Plex Sans 400 | 12–12.5/1.4 | Meta |
| kbd | IBM Plex Mono 500 | 10.5 | Tastenhinweise, Codes |
Alle Zahlen sind `tabular-nums`. Nullwerte erscheinen als „–“, nie als „0 %“ oder „+0 XP“.

## Komponenten
- **Button:** `btn-primary` (h44, px18, Ember-Fläche), `btn-secondary` (h40, inset 1px line-4), `btn-ghost` (fg-2, nur Text), `btn-danger` (Karmin-Kontur 50 %), `btn-danger-confirm` (Karmin-Fläche, zweiter Klick, z. B. „Wirklich alle?“), `btn-icon` (32×32).
  Zustände: hover (primär `#ff8f5a`, sonst bg-4), active (`translateY(1px)`, primär `#e8662b`), focus-visible (2px fg-1 outline, offset 2), disabled (opacity .4).
- **Tabs:** Text in Versalien, aktiv fg-1 + `inset 0 -2px 0 #ff7a3d`, inaktiv `#7d786f`.
  **Segmented inline** (Kopfleiste) hat denselben Stil in klein. **Segmented boxed** (Dialoge, Login): Container bg-0 + inset line-3, Padding 3, aktiv `#34312c`.
- **Panel/Kachel:** `surface` (bg-3, r4) oder `outline-panel` (inset line-3). Deck-Kachel: Art 150 px hoch, Stufe-Chip oben links, Name h2 + Manasymbole, Meta, Meisterschaftsbalken 3 px gelb, Aktionen „Spielen“ + Bearbeiten/Löschen als Icon-Buttons.
- **Modal:** bg-3, r4, float-Schatten, Kopf mit Label + Titel (Engine-Titel in Plex 16/500), Fuß mit Buttons rechts. Im Spiel liegt das Modal **nur über der linken Brettspalte**, die Seitenleiste bleibt frei. **Minimiert (Tab):** Pille unten mittig über der Aktionsleiste: bg-4, Kontur Ember, 7-px-Quadrat, „DIALOG ÖFFNEN · {Label}“, kbd Tab. Der Scrim entfällt.
- **Toast:** oben mittig, bg-4, Icon in Zustandsfarbe, max. drei gleichzeitig. Info/Erfolg verschwinden nach 4 s, Fehler bleiben bis zum Klick (Fehler-Variante: bg `#2a1619` + inset Karmin 40 %).
- **Chip/Badge:** `chip-turn` (Ember gefüllt: „AM ZUG“), `chip-outline` („DENKT“, „BOT“, „MENSCH · BEREIT“), Legalität (Kontur in Zustandsfarbe 45 %), Zählbadge `×4`, entfernbarer Filterchip („DREDGE 5 · ABGELEHNT ×“). Unread-Badge: Ember, Barlow 700 12 px, min 18×18, r2, `0 0 0 2px #121110` als Freistellung.
- **Tabelle:** Header-Label + line-3, Zeilen line-1, Zahlen rechtsbündig in Barlow 17, Hover bg-4, geöffnete Zeile bg-3 + `inset 2px 0 0 #ff7a3d`.
- **Fortschritt:** XP-Balken 4 px (Bestand voll, neue XP zunächst 40 %), Meisterschaft 3 px, CPU-Balken „Bot rechnet“ 2 px fg-2. **XP-Ring:** 96–112 px, Strich 4, Track line-2, gelb.
- **Eingabe:** h40–44, bg-0, inset 1px line-3, r3. Fokus: inset Ember. Fehler: inset Karmin + Zeile mit Icon darunter. Toggle 34×20 (an: Ember + Knopf `#121110`). Checkbox 18 px.
- **LifeTotal:** ≤ 10 Leben → Karmin. Ausgeschieden → fg-5. Delta („−6“) schwebt oben rechts, 1,2 s.
- **ZoneCounter:** Icon fg-4 + Zahl Barlow 14–16. Bei Änderung 600 ms Ember. Tooltip mit Zonennamen.
- **CommanderDamage:** Zeile + 3-px-Balken Karmin. Bei ≥ 21 Chip „21 TÖDLICH“ gefüllt.
- **PhaseBar:** 1680 → alle 11 Schritte (Barlow 13, padding 0 9). Erledigt fg-5, aktuell Ember + 3-px-Unterstrich + Tönung `rgba(255,122,61,.08)`, Kampfschritte auf `#171614`, Trenner vor Kampf und Main 2. **< 1440 px:** 5 Gruppen (Anfang · Main 1 · Kampf · Main 2 · Ende), das aktive Segment zeigt den Unterschritt („KAMPF · BLOCKER“).

## Kartenzustände (`CardView`)
| Zustand | Darstellung | Rang |
|---|---|---|
| spielbar | `0 0 0 2px ember, 0 0 0 4px bg-1`; in der Hand 6 px angehoben, Hover 16 px | 4 |
| Mana-Quelle | `0 0 0 1px ember/55 %`, nur wenn Kosten offen | 5 |
| mögliches Ziel | 2px gelb + Abzeichen Fadenkreuz; Spielerziel = ganzer Pod `inset 0 0 0 2px gelb`; umlaufende Strichkontur 1,6 s (einzige Endlosschleife) | 2 |
| gewählt | 2px grün + Häkchen; Aktionsleiste zeigt „N markiert“ | 1 |
| angreifend | 2px Karmin + Schwert; Etikett „→ ZIELNAME“ unten links | 3 |
| blockend | 2px Kobalt + Schild; Etikett „BLOCKT X“ | 3 |
| getappt | rotate(90°), Wrapper wird so breit wie die Karte hoch ist | – |
| krank | Mond-Abzeichen oben rechts, Karte voll deckend | – |
| Schaden | Karmin-Chip „−N“ oben links | – |
| P/T geändert / Zähler | P/T-Chip invertiert (fg-1 Fläche, dunkle Schrift); Zähler grüner Text links | – |
Bei mehreren Zuständen gewinnt der niedrigste Rang. Abzeichen: 20×20, Zustandsfarbe, Icon dunkel, mittig oben (−9 px). Spielbar und Mana-Quelle gibt es nur auf dem eigenen Feld. Fallback ohne Bild: Name/Kosten/Typ auf `linear-gradient(160deg,#2b2723,#141210)`.
**Kampfpfeile entfallen** zugunsten von Etiketten (verdecken nichts). Der verteidigende Pod zeigt den Chip „N ANGREIFER“ in Karmin. Bei 1280 px entfallen die Etiketten an Gegnerkarten (zu klein), dort reichen Abzeichen und Kontur.

## Icons (Lucide, Strich 1,5, `currentColor`, 14/16/20/24)
Held `Shield` · Spielen `Swords` · Decks `Layers` · Statistik `ChartColumn` · Einladungen `Ticket` · Konto: Initiale · Zum Tisch `LogIn` · Lobby `LayoutGrid` · Bibliothek `BookCopy` · Hand `Hand` · Friedhof `Skull` · Exil `Orbit` · Commander `Crown` · Cmd-Schaden `Swords` · krank `Moon` · Stapel `Layers2` · Auto-Mana `Zap` · Auto-Passen `FastForward` · Ton `Volume2`/`VolumeX` · Menü `Menu` · Bot `Bot` · denkt `Cpu` · Mensch `User` · getrennt `WifiOff` · Chat `MessageSquare` · Verlauf `History` · Ziel `Crosshair` · Blocker `Shield` · gewählt `Check` · Fehler `TriangleAlert` · Info `Info` · Suche `Search` · Zufällig `Shuffle` · Import `Download` · Link `Link` · Bearbeiten `Pencil` · Löschen `Trash2` · Kopieren `Copy` · Rotieren `RefreshCw` · Sieg `Trophy` · Serie `Flame` · Meisterschaft `Star` · Level-Up `ChevronsUp` · Code `KeyRound` · Minimieren `Minimize2` · Aufgeben `Flag` · Zuschauen `Eye` · Abmelden `LogOut` · Freund hinzufügen `UserPlus` · befreundet `UserCheck` · Freunde `Users` · Einladung `Mail` · Senden `SendHorizontal` · unsichtbar `EyeOff`.
**Alle Emojis entfallen.** Manasymbole bleiben `mana-font` (`ms ms-b ms-cost`).

## Motion (`motion`)
duration-1 90 ms (Hover, Press) · duration-2 160 ms (Kartenzustand, Tappen, Tabs, Toggle) · duration-3 240 ms (Modal, Toast, Minimieren: Opazität + 8 px) · duration-4 420 ms (Karte zwischen Zonen, bei Blitz 210 ms) · duration-xp 1200 ms (XP-Hochzählen, Lebens-Delta).
Easing: `cubic-bezier(.2,.8,.2,1)` für Erscheinen, `cubic-bezier(.6,0,.2,1)` für Ortswechsel. FX-Ebene: spring stiffness 520, damping 44, ohne Überschwingen.
**Nicht animiert:** Umbruch des Spielfelds, Phasenwechsel, neue Verlaufszeilen. Screenwechsel höchstens 120 ms Überblendung. Kein Pulsieren bei „spielbar“, kein Konfetti, kein Wackeln.
Bei `prefers-reduced-motion` nur Überblendungen.

---

## Screens

### GameScreen (Spielbrett) – `MageLite Spielbrett.dc.html`
Maße 1680×1000 (in Klammern 1280×760). Spaltenaufbau: links flex, rechts `Side` 336 (268) mit border-left line-2, bg-2.
- **Kopfleiste** 50 (44): „MAGELITE“ Barlow 700 19 Ember · „RUNDE 7“ · Chip „DEIN ZUG“ bzw. „KOTORI AM ZUG“ · Sitz-Avatare 20 px in Zugreihenfolge (aktiv: Opazität 1 + Ember-Kante, sonst .45) · `PhaseBar` mittig · rechts Tempo inline, Auto-Mana, Auto-Passen (Label „MANA“/„PASSEN“ bei 1280), Ton, „MENÜ Esc“.
- **OpponentPod** ×3, Höhe 272 (200), getrennt durch border-right line-2:
  - Kopf: Avatar 38 (30) r3 mit 1px Platzfarbe, Name Barlow 17, Deck Plex 12 fg-3, optional Chip („AM ZUG“ / „N ANGREIFER“), Leben life-l.
  - Zonenzeile: Icons fg-4.
  - Spielfeld auf bg `#0f0e0d`: oben Nicht-Länder, unten Länder mit gleichen Namen gestapelt (`×N`). Karten 50 (38) px breit.
  - Aktiver Pod: 3-px-Ember-Leiste oben. Beim Verteidiger-Wählen: alle Pods `inset 0 0 0 2px #ffd23f`, klickbar.
- **PlayerInfo** 210 (168): Avatar, „DU“, Deck, Chip „AM ZUG“, Leben life-xl, Zonen 2×2 (Friedhof klickbar → Zonen-Ansicht), Commander-Schaden mit Balken, Command Zone (Karte 44 + „Steuer +2“, nur bei 1680). Bei eigenem Zug liegt eine 3-px-Ember-Leiste über dem ganzen eigenen Bereich.
- **Battlefield** (eigen): Label „KREATUREN“, Karten 80 (60). Label „LÄNDER · ARTEFAKTE“, Karten 62 (46).
- **StackPanel:** schwebt rechts oben im eigenen Bereich (right/top 14), Breite 340 (300), bg-3 + float. Kopf „STAPEL · N“. Einträge: Thumbnail 40, Name, Besitzer in Platzfarbe, Art („AUSLÖSER · ×3 GLEICHE“), Text, Ziel-Chip gelb. Das oberste Objekt hat die Tönung `rgba(255,122,61,.06)`. Fuß: „Oberstes Objekt löst als Nächstes auf“. **Nie über einem Gegner-Pod.**
- **Hand** 186 (132): Karten 104 (78) in einer Reihe mit Gap 10, kein Fächer. Spielbar −6 px, Hover −16 px. Label „HAND 6 · 4 SPIELBAR“ oben links.
- **PromptBar** 62 (54), bg-3: Status-Chip (Ember „WARTET AUF DICH“, gelb „ZIEL WÄHLEN“/„VERTEIDIGER WÄHLEN“, Kontur „DIALOG OFFEN“/„PAUSE“) · Phase/Kontext fg-3 (nur 1680) · Engine-Text body-l mit Ellipsis · Passen-Buttons (F5/F4/F9; bei 1280 nur F5) · Primärbutton.
- **Side:** `ZoomPanel` (Karte 250 bzw. 190 breit, Name, Manakosten, Typ, Regeltext nur bei 1680), darunter `Log` mit Tabs WICHTIGES/ALLES. Zug-Gruppen beginnen mit einer 3×12-px-Leiste in Platzfarbe, eingeklappte Gruppen zeigen die Anzahl. Im Online-Spiel kommt ein dritter Tab CHAT dazu.
- **Zustände im Prototyp:** Mulligan (Modal 1000 bzw. 780 breit, 7 Karten à 120/92, „Mulligan“ / „Behalten Space“) · eigener Zug · Angriff (Kreaturen anklicken → „Angriff bestätigen“ → Verteidiger-Pod klicken → „Angriff zurücksetzen“ / „Weiter“; „Alle angreifen“ braucht zwei Klicks) · Blocker (Kotoris Zug, eigene Kreatur anklicken ordnet den nächsten freien Angreifer zu) · Ziel wählen (Terminate) · Stapel · Fähigkeit wählen (mit ×1/×3/×5/×10) · Ersatzeffekt (Gruppe „MADNESS · 2 KARTEN“, Optionen in Engine-Englisch, „Für dieses Spiel merken“) · Friedhof (Tabs FRIEDHOF/EXIL, Raster 110) · Pause (Optionen-Toggles, Bot-Tempo, Verlauf; „Aufgeben“ zeigt eine Inline-Bestätigung „Wirklich aufgeben? Die Partie zählt als Platz 4.“) · Spielende („PLATZ 1“ display 64 Ember, Platzierungen, XP-Ring + Zähler 0→190 über 1,2 s nach 0,5 s Verzögerung, ab Schwelle „LEVEL UP“, danach Meisterschaft „3 › STUFE 4“; „Tisch ansehen“ klappt zur Pille „ERGEBNIS ANZEIGEN“).
- **Tasten:** Space/Enter/F2 = Hauptaktion · Esc = Abbrechen, Markierung lösen, Pause · Tab = Dialog minimieren · F4/F5/F9 = Passen.

### Meta-Screens – `MageLite Meta.dc.html`
Navigation links 84 px: Icon 21 + Label Barlow 12 .1em. Aktiver Punkt: fg-1 und 2-px-Ember-Leiste am rechten Rand. Unten „ZUM TISCH“ (Ember-Kontur), wenn ein Spiel läuft.
- **HomeScreen:** Level-Zahl (Barlow 160/0.78, zweistellig „03“), Name in Versalien 44, Titel gelb, XP-Balken 4 px, rechts Kennzahlen Spiele/Siege/Serie (Serie in Ember). Darunter zwei CTAs (1.5fr/1fr, Höhe 230/170): Schnellstart mit Commander-Art rechts (62 % Breite, Verlauf von bg-3 nach transparent) und „SPIEL STARTEN Enter“, daneben „Neues Spiel“ als outline-panel. Unten „Letzte Partien“ (Platz-Ziffer gelb/fg-2/fg-3, Art 64×40, XP gelb) und „Deck-Meisterschaft“.
- **PlaySetupScreen:** links „DEIN DECK“ (Art 300×170, Name, Farben, Stufe + Balken, „DECK WECHSELN“, „ZUFÄLLIG“), „BOT-TEMPO“ als 4 Optionskarten (aktiv: Ember-Kontur + Tönung + Häkchen), „GEGNER“ als drei `SeatCard` (Art, Chip „PLATZ N · BOT“, Deck, Commander · Set, „ÄNDERN“ + Zufall). Rechts Panel „PARTIE“ (360/290) mit Zusammenfassung und „SPIEL STARTEN“ 52 hoch.
- **DeckPicker** (Overlay, inset 64/48): Suche, „ZUFÄLLIG“, Tabs „MEINE DECKS N“ / „VORGEFERTIGT 70“, Raster `auto-fill minmax(210px,1fr)`. Kachel: Art 112, Name, Commander, Set in fg-3 auf **Vollfläche** (nicht auf der Art), Auswahl `0 0 0 2px ember` + Häkchen. Fuß: „Abbrechen Esc“ / „Übernehmen Enter“.
- **DecksScreen:** Raster `auto-fill minmax(300px,1fr)` plus Kachel „DECK IMPORTIEREN“. Die Meisterschaft ist immer sichtbar („noch nicht gespielt“).
- **ImportDialog** (Overlay): links Segmented LINK/TEXTLISTE.
  - Link: URL-Feld + „LADEN“, Hinweis auf Archidekt/Moxfield.
  - Textliste: Name + Monospace-Liste.
  - Rechts „VORSCHAU“: Leerzustand mit Erklärung; sonst Commander-Banner 120, Chips (Kartenanzahl, Legalität, unbekannt), Fehlerzeile mit Korrekturvorschlag, Kartenliste nach Typ gruppiert (COMMANDER/KREATUREN/SPONTANZAUBER/HEXEREIEN/ARTEFAKTE/LÄNDER mit Anzahl).
  - Bearbeiten-Modus: Titel = Deckname, links unten „LÖSCHEN“.
  - Löschen-Bestätigung: 440 breit, Text „Das Deck wird aus der Sammlung entfernt. Vergangene Partien bleiben im Verlauf.“
- **StatsScreen:** Tabs ÜBERSICHT/VERLAUF.
  - Übersicht: 5 KPIs (Barlow 52), Formkurve als Säulen (Höhe = (5−Platz)/4, 1. Platz in Ember), Tempo, Mulligans, häufigste Gegner, Deck-Tabelle. Ein Klick auf eine Zeile klappt die Kartenstatistik auf (Gezogen, Gespielt, Spielquote mit Minibalken, In Siegen; Commander mit Chip).
  - Verlauf: Platz, Deck + Datum, Gegner-Avatare, Züge, Dauer, Tempo, XP.
- **Leerzustände:** Icon 44 fg-4, Titel Barlow 30–32, ein Satz Erklärung, genau eine Primäraktion (+ optional eine sekundäre).

### Online – `MageLite Online.dc.html`
- **LoginScreen:** 50/50, links Formular 400 breit (Wortmarke 46, Segmented, Feld, „ANMELDEN Enter“), rechts Commander-Art mit Verlauf. Danach Lade-Screen (Wortmarke, 2-px-Balken, „VERBINDE MIT SERVER …“ → „LADE KONTO UND DECKS …“).
- **HomeScreen (Server):** Inhalt links, rechts Spalte 400 (320) bg-2 mit Tabs „LOBBY-CHAT“ / „FREUNDE {N eingehende Anfragen}“.
  - Gast-Hinweis als outline-Zeile mit „KONTO SICHERN“.
  - Am Tisch: Streifen mit Ember-Kontur „DU SITZT AM TISCH · Annas Tisch · 2/4 · Blitz“ und den Buttons „EINLADEN“ (Popover 300 breit mit Freundesliste) und „ZUM TISCH“.
  - CTAs: Lobby und Allein üben.
- **Lobby-Chat:**
  - Kopf „N IM CHAT“ + „VERLASSEN“.
  - Mitglieder als Chips (Punkt grün, Freunde mit `Users`-Icon). Klick öffnet ein Popover 220 breit: Name, Beziehung, Aktion „Als Freund hinzufügen“ / „Anfrage gesendet“ / „Schon befreundet“ / „Anfrage annehmen“.
  - Nachrichtenliste unten ausgerichtet: Name 13/600 (eigener in Ember), Zeit Mono 10.5 fg-4, Text 13.5 fg-2. Systemzeilen fg-4.
  - Hinweis: „Verlauf: letzte 100 Nachrichten · wird bei Server-Neustart geleert“.
  - Eingabe: Enter sendet, Senden-Icon in Ember.
  - **Verlassen-Zustand:** `EyeOff`, „DU BIST UNSICHTBAR“, Erklärung, „BEITRETEN“. Die Einstellung wird pro Konto gespeichert und ist im Konto zusätzlich als Toggle schaltbar.
  - **Unread:** Zahl am Held-Knopf, solange man nicht auf der Startseite ist und im Chat ist.
- **Freunde:**
  - Feld „FREUND HINZUFÜGEN · EXAKTER NAME“ + „ANFRAGEN“.
  - Fehler: „Kein Konto mit dem Namen „X“. Groß- und Kleinschreibung beachten.“ · „X ist schon dein Freund.“ · „Anfrage an X läuft bereits.“
  - **Gegenseitige Anfrage** → sofort befreundet, Toast „X hatte dich schon angefragt. Ihr seid jetzt befreundet“.
  - Block „ANFRAGEN AN DICH“ in Ember mit Ablehnen/Annehmen.
  - Liste sortiert online → am Tisch → im Spiel → offline. Status-Punkt: online gefüllt grün, am Tisch/im Spiel Ring fg-2 + Icon, offline Ring fg-5. Gesendete Anfragen stehen ausgegraut mit „ANFRAGE GESENDET“.
- **LobbyScreen:** Header mit „ALLEIN ÜBEN“ + „TISCH ERÖFFNEN“. Tabelle: Tisch/Gastgeber, Plätze als Chips (Mensch, Bot, frei als Kontur), Tempo, Status („DU SITZT HIER“ Ember / „OFFEN · 2/4“ grün / „LÄUFT · ZUG 14“ fg-3), Aktion ÖFFNEN/SETZEN/ZUSCHAUEN. Leerzustand „GERADE KEIN OFFENER TISCH“.
- **TableScreen:**
  - Kopf: Titel + Einladungslink (Mono) mit „KOPIEREN“.
  - 4 Plätze, Art 130 (84): eigener Platz mit Ember-Kontur, Krone für Gastgeber, Status-Chip „BEREIT“ grün / „WÄHLT DECK“ gelb, offene Plätze als Kontur mit „MIT BOT FÜLLEN“.
  - Links „FREUNDE EINLADEN“: je Freund „EINLADEN“, „EINGELADEN · 9:41“ (Ember-Kontur, Restzeit), „AM TISCH“, offline mit Opazität .4. Bei „im Spiel“ steht der Zusatz „sieht die Einladung nach der Partie“. Darunter Bot-Tempo.
  - Rechts „TISCH-CHAT“.
  - Fuß: „TISCH VERLASSEN“, Hinweistext, „SPIEL STARTEN“ (nur Gastgeber).
- **Einladungskarte:** unten rechts (20/20), 340 breit, bg-3 + float + 1px Ember. Oben ein 3-px-Restzeitbalken. „EINLADUNG“ + Restzeit Mono, Satz „Clara lädt dich an ihren Tisch ein.“, Tischdaten in Versalien, „ABLEHNEN“ / „BEITRETEN“. Erscheint **überall außer im laufenden Spiel**. Verschwindet nach 10 min, wenn der Tisch voll ist oder das Spiel startet.
- **AccountScreen:** links Identität + Formular („KONTO SICHERN“ für Gäste bzw. „E-MAIL UND PASSWORT ÄNDERN“), rechts Lobby-Chat-Sichtbarkeit (Toggle) und Sitzung mit „ABMELDEN“ (Danger).
- **AdminScreen (Einladungen):** Name + „CODE ERZEUGEN“. Neuer Code in gelb umrandeter Fläche „NUR JETZT SICHTBAR“ (Code Mono 24, Link, „LINK KOPIEREN“). Liste: Name, Status (Punkt gefüllt = angemeldet, Ring = unbenutzt), Datum, Icon-Buttons Kopieren/Rotieren/Löschen.
- **Verbindungsverlust:** 36-px-Leiste oben, bg `#2a1619`, Linie Karmin 40 %, `WifiOff`, „Keine Verbindung zum Server. Neuer Versuch in 4 s.“ + „JETZT VERSUCHEN“. Chat-Eingaben sind gesperrt (Opazität .5).

## Zustand (zustand-Stores, Vorschlag)
- `ui`: `screen`, `overlay` (`picker|import|edit|delete|null`), `toasts[]`, `dialogMinimized`
- `game` (besteht): ergänzt um `attackDraft { selected[], defender|'?'|null, allArmed }`, `blockDraft { [blocker]: attacker }`, `hoverCard` (für `ZoomPanel`)
- `social`: `chatJoined` (persistiert pro Konto, serverseitig), `chatMembers[]`, `chatMessages[]` (max. 100, nur im Speicher), `unreadCount` (beim Öffnen der Startseite auf 0), `friends { name: 'online'|'table'|'game'|'offline' }`, `incomingRequests[]`, `outgoingRequests[]`, `sentInvites { name: expiresAt }`, `receivedInvite { from, tableId, tableName, seats, tempo, expiresAt } | null`
- Regeln: Einladen nur an Freunde. Offline nicht einladbar (Annahme, bitte prüfen). `receivedInvite` wird im `GameScreen` nicht gerendert und verfällt bei voller Tischbesetzung, Spielstart oder Ablauf.

## Assets
- Kartenbilder und Commander-Art: Scryfall (`image_uris.normal` bzw. `art_crop`). Im Prototyp wird jeder Name einmal per `/cards/named?exact=` aufgelöst und gecacht, mit Drosselung auf etwa 9 Anfragen/s.
- Manasymbole: `mana-font`. Icons: `lucide-react`. Schriften: `@fontsource/barlow-condensed`, `@fontsource/ibm-plex-sans`, `@fontsource/ibm-plex-mono` (ersetzen Inter und Cinzel).
- Kein Logo vorhanden: Die Wortmarke ist „MAGELITE“ in Barlow Condensed 700, .08em, Ember.

## Screenshots (`screenshots/`)
Aufgenommen aus den Prototypen. Das Brett ist im Vorschaufenster verkleinert. Für exakte Maße die Prototypen öffnen.
- **Spielbrett:** 01 Mulligan · 02 eigener Zug · 03 Angreifer wählen · 04 Blocker wählen · 05 Ziel wählen · 06 Stapel · 07 Fähigkeit wählen · 08 Ersatzeffekt · 09 Friedhof · 10 Pause · 11 Spielende · 12 eigener Zug 1280×760 · 13 Blocker 1280×760
- **Meta:** 01 Held · 02 Spielen-Setup · 03 Deck-Auswahl · 04 Decks · 05 Import Textliste · 06 Import Link · 07 Deck bearbeiten · 08 Statistik mit Deck-Karten · 09 Verlauf · 10 Decks leer · 11 Statistik leer · 12 Held neuer Spieler · 13 Held 1280×760
- **Online:** 01 Login · 02 Startseite mit Lobby-Chat · 03 Mitglieder-Menü · 04 Freunde · 05 Chat verlassen · 06 Einladen-Popover · 07 Lobby · 08 Tisch · 09 Konto · 10 Einladungen mit neuem Code · 11 Einladungskarte + Verbindungsverlust · 12 Tisch 1280×760

## Umbau-Reihenfolge (Empfehlung)
1. `magelite-theme.css` einsetzen, Fonts und `lucide-react` installieren, Emojis ersetzen.
2. `CardView`-Zustände + `PhaseBar` + `PromptBar` + `OpponentPod`/`PlayerInfo`.
3. `StackPanel`-Position, `Modal` mit Minimieren, `GameOverOverlay`.
4. Meta-Screens, dann Online/Social.
