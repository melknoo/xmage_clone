# Prompt für Claude Design

> Alles unterhalb der Linie kopieren und in Claude Design einfügen. Mitschicken: alle PNGs aus
> `screenshots/`, `screenshots/INDEX.md`, `KONTEXT.md` und `current-theme.css`.

---

Du bist Senior Product Designer für Games- und Tool-UIs. Gestalte die Oberfläche von **MageLite** neu: eine
Desktop-App (Electron, auch im Browser nutzbar), in der Magic-Spieler ihre **Commander-Decks gegen drei KI-Bots**
spielen. Sie können auch online mit Freunden an einem Tisch spielen. Regeln und KI kommen aus einer fertigen
Engine. Es geht **nur um Darstellung und UX**. Abläufe, Funktionen und Spielregeln bleiben gleich.

## Was dir vorliegt
- `screenshots/`: der aktuelle Stand, 1680×1000 (Standardfenster), zwei Bilder bei 1280×760 (Mindestgröße).
  `INDEX.md` sagt zu jedem Bild, welcher Screen oder Zustand zu sehen ist und was mir daran nicht gefällt.
- `KONTEXT.md`: Zielgruppe, Bildschirmgrößen, alle Screens, Dialoge und Interaktionszustände, Gamification,
  Technik und No-Gos. **Bitte vollständig lesen**, bevor du entwirfst.
- `current-theme.css`: die heutigen Design-Tokens (Tailwind 4 `@theme`), Glows und Button-Stile.

## Ziel
Die App soll sich wertig und „wie ein richtiges Spiel“ anfühlen, aber nicht verspielt. Das Spielbrett zeigt
vier Spieler mit vielen Karten gleichzeitig. Es soll auch nach zwei Stunden noch übersichtlich, ruhig und
schnell lesbar sein. Wichtigste Fragen in jedem Moment: **Wer ist am Zug? Was kann ich jetzt tun? Was ist
gerade passiert?** Menüs und Meta-Screens (Held, Decks, Statistik) sollen Lust aufs nächste Spiel machen.

## Vorgehen (bitte in diesen Schritten, nach Schritt 2 auf meine Wahl warten)

**1. UX-Audit.** Geh die Screenshots durch und liste die 10–15 wichtigsten Probleme, priorisiert:
Hierarchie, Lesbarkeit, Konsistenz, Informationsdichte auf dem Spielbrett, Feedback bei Aktionen, Leerräume,
Icons. Jeweils ein Satz Problem und ein Satz Lösungsidee.

**2. Drei Stilrichtungen.** Schlag drei deutlich verschiedene visuelle Richtungen vor, mindestens eine
davon nah an einer polierten Weiterentwicklung des heutigen Looks. Zu jeder Richtung:
- ein Style-Tile mit Farbpalette (dunkles Theme ist Pflicht für das Spielbrett), Typografie
  (Display- und Textschrift, Zahlen), Buttons, Panel- bzw. Oberflächenstil, Kartenrahmung, Icon-Stil und
  Zustandsfarben (spielbar, Ziel, gewählt, Angreifer, Blocker, Fehler);
- angewendet auf **zwei Screens**: Home („Held“) und Spielbrett mitten im Spiel (eigener Zug, Karten spielbar);
- zwei bis drei Sätze zur Stimmung und für wen sie passt.

**3. Designsystem** (nach meiner Wahl): Tokens als CSS-Variablen, Typo-Skala, Abstands- und Radius-Skala,
Komponenten (Button-Varianten, Tabs/Segmented Control, Panels und Karten, Modal mit minimiertem Zustand, Toast,
Badge/Chip, Tabelle, Fortschrittsbalken und XP-Ring, Eingabefelder, Lebenspunkt-Anzeige, Zonenzähler,
Phasenleiste). Dazu ein **konsistentes Icon-Set statt Emojis**, Glow- und Hervorhebungsregeln für alle
Kartenzustände sowie Motion-Regeln (Dauer, Easing, was animiert wird und was nicht).

**4. Hi-Fi-Screens und klickbarer Prototyp** im gewählten Stil:
- Home/Held, Spielen-Setup mit Deck-Auswahl-Overlay, Decks mit Import- und Bearbeiten-Dialog,
  Statistik (Übersicht, Deck-Karten, Verlauf);
- Spielbrett in diesen Zuständen: Mulligan, eigener Zug (Priorität), Angriff erklären, Blocker wählen,
  Ziel wählen, Stapel mit mehreren Objekten, Auswahl-Dialoge (Fähigkeit, Ersatzeffekt), Friedhof-Ansicht,
  Pausemenü, Spielende mit XP- und Meisterschafts-Belohnung;
- Online: Login (Einladungscode bzw. E-Mail), Lobby, Tisch mit Chat, Konto, Einladungen (Admin);
- Leerzustände (keine Decks, keine Statistik) und Lade- bzw. Verbindungszustände;
- Spielbrett zusätzlich bei der Mindestgröße 1280×760.

**5. Übergabe an Claude Code** als Handoff-Bundle. Zielstack: React 19 + Tailwind CSS 4 (Tokens in `@theme`,
eigene Klassen per `@utility`), Animationen mit `motion`. Keine schweren UI-Bibliotheken; eine schlanke
Icon-Bibliothek (z. B. Lucide) ist okay. Bestehende Komponentennamen aus `KONTEXT.md` beibehalten, damit
der Umbau Screen für Screen geht.

## Harte Rahmenbedingungen (Details in KONTEXT.md)
- Desktop zuerst: Standard 1680×1000, Mindestgröße 1280×760. Kein Mobile-Layout nötig.
- Spielbrett: drei Gegner und man selbst immer gleichzeitig sichtbar, plus Hand, Stapel, Aktionsleiste und
  eine Seitenleiste mit Karten-Zoom und Spielverlauf bzw. Chat.
- Kartenbilder kommen von Scryfall (63:88, Größen von 46 bis 300 px) und sind nicht gestaltbar. Rahmen,
  Overlays (Zähler, Stärke/Widerstand, getappt) und Hervorhebungen schon.
- Manasymbole kommen aus `mana-font` und bleiben.
- **UI-Texte sind Deutsch.** Kartennamen, Regeltexte und manche Engine-Meldungen kommen englisch aus der
  Engine. Das Design muss mit diesem Sprachmix gut aussehen.
- Tastatursteuerung bleibt (Leertaste = Hauptaktion, Esc, F-Tasten zum Passen, Tab minimiert Dialoge);
  Tasten-Hinweise sollen sichtbar bleiben.
- **Nicht gewollt:** Achievements, kosmetische Unlocks bzw. Shop, Undo, Cheat-Werkzeuge, Pay-to-win-Optik.
  Gamification = **ein Held** (XP, Level, Titel) + **Deck-Meisterschaft**, rein Meta.

Fang mit Schritt 1 und 2 an.
