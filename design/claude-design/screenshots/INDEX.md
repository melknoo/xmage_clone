# Screenshots: aktueller Stand (2026-10-06)

Fenster 1680×1000 (Standard der Desktop-App). Ausnahme: `70-`/`71-` bei der Mindestgröße 1280×760.
Aufgenommen mit echten Spielen gegen die Engine, nichts simuliert. Kartennamen, Regeltexte und viele
Engine-Meldungen sind englisch, die UI-Texte deutsch.

> **Leere oder bräunliche Kartenflächen:** Das Kartenbild lädt noch von Scryfall. Bis dahin zeigt die App einen
> Textrahmen (Name, Kosten, Typ, Text, Farbe der Karte). Das ist ein echter Zustand, aber nicht der Normalfall.

## Start
| Datei | Zustand | Was auffällt |
|---|---|---|
| `00-splash-engine-start.png` | Electron-Splash, solange die Engine startet | anderer Stil als die App (Georgia statt Cinzel, Segoe UI statt Inter) |
| `01-ui-engine-wartet.png` | App wartet auf die Engine | sehr leer; kein Fortschritt |

## Meta-Screens (lokaler Modus)
| Datei | Zustand | Was auffällt |
|---|---|---|
| `10-home-held.png` | Held: Level-Ring, Titel, XP, Kacheln, „Neues Spiel“, „Schnellstart“ | untere Hälfte leer; Navigation mit Emojis; kaum Anreiz oder Kontext (letzte Partie, Lieblingsdeck …) |
| `11-spielen-setup.png` | Spielen-Setup: eigenes Deck, Bot-Tempo, drei Gegner | rechtes Drittel leer; Gegnerplätze ohne Bild; Start-Knopf weit weg vom Inhalt |
| `12-deckpicker-meine-decks.png` | Deck-Auswahl-Overlay, Tab „Meine Decks“ | großer Leerraum unter wenigen Decks |
| `13-deckpicker-vorgefertigt.png` | Deck-Auswahl, 70 mitgelieferte Decks | Art-Hintergründe sehr dunkel, Setname kaum lesbar |
| `14-decks-uebersicht.png` | Deck-Raster mit Commander-Art, Meisterschafts-Badge, Aktionen | Löschen-Icon nur 🗑; Meisterschafts-Balken erst nach Spielen sichtbar; untere zwei Drittel leer |
| `15-deck-import-link.png` | Import-Dialog, Tab „Link“ | Vorschau-Leerzustand wirkt wie ein Platzhalter |
| `16-deck-import-textliste-vorschau.png` | Import, Textliste mit Vorschau: Commander-Art, Badges (69 Karten, nicht legal, 1 fehlt), unbekannte Karte, Kartenliste | viele Meldungsstile nebeneinander |
| `17-deck-bearbeiten.png` | Deck bearbeiten mit gültiger Vorschau (100 Karten, Commander-legal ✓) | Kartenliste ohne Gruppierung nach Typ |
| `18-deck-loeschen-bestaetigen.png` | Bestätigungsdialog Löschen | |
| `19-statistik-uebersicht.png` | Statistik: KPI-Kacheln, Formkurve, Deck-Tabelle | Formkurve schwer lesbar; Tabellen-Lastigkeit; kaum Visualisierung |
| `20-statistik-uebersicht-unten.png` | Statistik unten: Tempo, Mulligans, häufigste Gegner | lange, gleichförmige Tabellen |
| `21-statistik-deck-karten.png` | Kartenstatistik eines Decks (nach Klick auf die Deckzeile) | wirkt wie Rohdaten |
| `22-statistik-verlauf.png` | Tab „Verlauf“: Partien mit Platz, Gegnern, XP | Platz-Zahl in Cinzel schwer lesbar; „+0 XP“ wirkt wie ein Fehler |

## Spielbrett (lokal, Bot-Tempo Blitz)
| Datei | Zustand | Was auffällt |
|---|---|---|
| `30-spiel-laden.png` | „Verbinde mit Engine …“ vor dem ersten State | |
| `31-mulligan.png` | Starthand-Dialog (Mulligan/Behalten) | Engine-Text englisch; Hand bricht in zwei Reihen um |
| `32-mulligan-hover-zoom.png` | Starthand mit Karten-Zoom rechts (Hover) | |
| `33-spiel-eigener-zug-spielbar.png` | eigener Zug, Main 1: Hand mit spielbaren Karten (türkis), Aktionsleiste „Weiter / Zu Main 2“ | Gegner-Pods mit viel Leerraum; Phasenleiste klein und gedrängt; Karten-Zoom rechts (Hover) mit Regeltext darunter |
| `35-spiel-mitte.png` | Mitte des Spiels, Bot rechnet (CPU-Balken), Ereignisleiste unten links | Ereignisleiste und Log doppeln sich teilweise |
| `36-spiel-blocker-waehlen.png` | Blocker wählen: rote Angriffspfeile auf mich, Aktionsleiste „Blocker bestätigen“ | Pfeile verdecken Karten und Lebenspunkte; eigene Kreaturen als Blocker nicht klar hervorgehoben |
| `37-spiel-spaet.png` | späteres Spiel: volle Boards, Kampfschaden, Ereignisleiste | Gegner-Boards abgeschnitten (Scrollen im Pod); Karten in Gegner-Pods sehr klein |
| `38-friedhof-ansicht.png` | Friedhof-Viewer (Modal über dem eigenen Feld) | Modal sitzt ungewöhnlich (links, über dem Brett) |
| `39-pausemenue.png` | Pausemenü (Esc): Optionen-Toggles, Bot-Tempo, Weiterspielen/Aufgeben | |
| `40-pausemenue-aufgeben-bestaetigen.png` | Aufgeben bestätigen (inline) | |
| `41-spielende-ergebnis.png` | Spielende: Platz, Platzierungen, XP-Balken, LEVEL UP, XP-Aufschlüsselung, Deck-Meisterschaft | die Belohnung (Level-Up) geht unter |
| `42-spielende-tisch-ansehen.png` | Ergebnis eingeklappt („Ergebnis anzeigen“), Brett ausgegraut | |

## Spielbrett: besondere Interaktionen (Testszenarien)
| Datei | Zustand | Was auffällt |
|---|---|---|
| `50-angriff-alle-bestaetigen.png` | Angreifer wählen, „Alle angreifen“ einmal geklickt → Knopf wird zu „Wirklich alle?“ (rot) | |
| `51-angriff-verteidiger-waehlen.png` | Verteidiger wählen: alle drei Gegner-Pods gold umrandet, „Abbrechen – kein Angriff“ | Ziel-Hervorhebung nur über den Rahmen |
| `52-angriff-abgebrochen.png` | zurück beim Angreifer-Prompt, nichts markiert | |
| `53-angriff-erklaert-pfeile.png` | 15 Angreifer erklärt: Stapel-Karte rot markiert, Pfeil zum Gegner, „Angriff zurücksetzen“ | ein Pfeil für einen ×15-Stapel; Lebenszahl des Ziels vom Pfeil verdeckt |
| `54-angriff-zurueckgesetzt.png` | Angriff zurückgesetzt | |
| `55-faehigkeit-waehlen-mehrfach.png` | Fähigkeit wählen mit Wiederhol-Leiste ×1/×3/×5/×10 (Necropotence) | Titel englisch aus der Engine, in Cinzel-Versalien schwer lesbar |
| `56-stapel-mehrere-objekte.png` | Stapel-Panel mit 16 gleichen Auslösern („+15 gleiche darunter“) und Zoom | Stapel verdeckt den mittleren Gegner |
| `57-ersatzeffekt-dialog.png` | Ersatzeffekt wählen, gruppiert nach Effekt (Dredge 2/5/3), Karten-Chips, „Für dieses Spiel merken“ | |
| `58-ersatzeffekt-gemerkt.png` | gemerkte Ablehnung als Chip in der Kopfleiste („Dredge 5 +2 ✕“) | Kopfleiste wird voll |
| `59-starthand-aktion-dialog.png` | Starthand-Aktion (Gemstone Caverns) | Karten wirken ausgegraut |
| `60-starthand-aktion-ergebnis.png` | Ergebnis: Land mit Zähler auf dem Feld, Stapel mit einer Fähigkeit | |
| `61-fx-animation-ereignisleiste.png` | FX: Karten fliegen zwischen Zonen (Hand ↔ Exil), Ereignisleiste mit Abwerfen | |

## Mindestgröße 1280×760
| Datei | Zustand | Was auffällt |
|---|---|---|
| `70-mindestgroesse-home.png` | Home bei Mindestgröße | |
| `71-mindestgroesse-spielbrett.png` | Spielbrett bei Mindestgröße | Phasenleiste abgeschnitten; Hand überlappt die Infospalte; Gegner-Pods sehr eng |

## Server-/Online-Modus
Gleiche UI im Browser bzw. in Electron gegen den Server. Die Namensliste bei den Einladungen enthält Testkonten
(doppelte Namen „Anna“/„Bob“), das ist kein Designfehler.

| Datei | Zustand | Was auffällt |
|---|---|---|
| `80-login-einladungscode.png` | Login, Tab „Einladungscode“ | sehr leer; Marke nur als Schriftzug |
| `81-login-email-passwort.png` | Login, Tab „E-Mail & Passwort“ | |
| `82-server-home-owner.png` | Home des Gastgebers (Admin) mit Banner „Du spielst als Gast“ und zusätzlichem Nav-Punkt „Einladungen“ | Banner konkurriert mit dem Helden |
| `83-admin-einladungen.png` | Einladungen: neuer Code (einmalig sichtbar), Liste mit Aktionen | Tabelle wirkt technisch |
| `84-konto-owner.png` | Konto-Seite (Gast-Konto: Formular „Konto sichern“) | |
| `85-lobby-leer.png` | Lobby ohne Tische (Leerzustand) | rechte Hälfte leer |
| `86-tisch-zwei-spieler-chat.png` | Tisch: Gastgeber + Bob (Mensch) + zwei Bots, Tempo, Tisch-Chat, „Spiel starten“ | Plätze und Chat wirken nüchtern; kein „bereit“-Status sichtbar |
| `87-lobby-mit-tisch.png` | Lobby mit offenem Tisch | |
| `88-spiel-online-chat.png` | Online-Spiel mit zwei Menschen: Seitenleiste mit Tabs „Verlauf/Chat“ | |
| `89-spiel-aufgegeben-andere-spielen-weiter.png` | Pausemenü nach Aufgeben im Online-Spiel: Zuschauen / Zurück zum Tisch / Hauptmenü | |
| `90-gast-home-konto-sichern.png` | Neuer Gast (Level 1, 0 Spiele) mit Banner „Konto sichern“ | Leerzustand ohne Hilfe, was als Erstes zu tun ist |
| `91-gast-decks-leer.png` | Decks-Leerzustand („Erstes Deck importieren“) | |
| `92-gast-statistik-leer.png` | Statistik-Leerzustand | |
| `93-gast-konto-sichern-formular.png` | Konto sichern: E-Mail + Passwort | |
