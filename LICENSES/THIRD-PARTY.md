# Fremdkomponenten

MageLite steht unter **GPL-3.0-or-later** (`LICENSE`), weil die Regel-Engine Forge (GPL-3.0) im selben Prozess läuft.
Diese Liste zeigt, was mitgeliefert oder zur Laufzeit genutzt wird. Maßgeblich sind die Lizenztexte der Komponenten
selbst. Wo keine Lizenz steht, war sie aus Jar/POM nicht eindeutig ablesbar: „siehe Projekt“ = beim Projekt nachsehen.

Genaue Versionen: `vendor/forge/manifest.json` (`jars[]`, Forge-Stand `vendor/forge/FORGE_COMMIT`),
`engine/build.gradle.kts`, `ui/package.json`, `desktop/package.json`.

## Regel-Engine und Daten

| Komponente | Herkunft / Version | Lizenz | Hinweis |
|---|---|---|---|
| Forge (`forge-core`, `-game`, `-ai`, `-gui`) | [Card-Forge/forge](https://github.com/Card-Forge/forge), gepinnter Commit in `vendor/forge/FORGE_COMMIT` (2.0.16-SNAPSHOT), von `scripts\import-forge.ps1` gebaut | GPL-3.0 | Text: `Forge-GPL-3.0.txt` |
| Forge-Daten (`vendor/forge/res`) | Kartenskripte, Token, Editionen, Formate, KI-Profile aus demselben Commit | GPL-3.0 (wie Forge) | Karten-Fixes: `vendor/forge-overrides` (MageLite) |
| Commander-Sample-Decklisten | 70 Decks in `engine/src/main/resources/sample-decks`, aus den XMage-Sample-Decks konvertiert | MIT (XMage) | `XMage-MIT.txt` |
| Bracket-Listen | `engine/src/main/resources/brackets/*`, aus XMage 1.4.60 übernommen | MIT (XMage) | `XMage-MIT.txt` |

Magic: The Gathering, Kartennamen und Regeltexte gehören Wizards of the Coast; MageLite ist ein inoffizielles
Hobbyprojekt ohne Verbindung zu Wizards of the Coast.

## Forge-Abhängigkeiten (`vendor/forge/lib`)

Gruppiert; die Einzel-Jars stehen in `vendor/forge/manifest.json`. Forge-eigene Lizenztexte einiger Teile liegen in
`vendor/forge/res/licenses` (tinylog, xstream, xpp3/mxparser, multiline-label). Bewusst **nicht** mitgeliefert:
Jetty/Servlet-API, `org.jupnp.support` (LAN-Spiel) und `slf4j-tinylog`/`slf4j-api` (Javalin bringt Jetty 11,
Logging läuft über reload4j).

| Komponente | Version | Lizenz |
|---|---|---|
| Guava (`guava`, `failureaccess`, `listenablefuture`) | 33.3.1-android | Apache-2.0 |
| Google-Annotationen (`jsr305`, `error_prone_annotations`, `j2objc-annotations`) | 3.0.2 / 2.41.0 / 3.0.0 | Apache-2.0 |
| Checker Framework (`checker-qual`) | 3.43.0 | MIT |
| Gson | 2.13.2 | Apache-2.0 |
| Apache Commons (`commons-lang3`, `commons-text`, `commons-math3`) | 3.18.0 / 1.12.0 / 3.6.1 | Apache-2.0 |
| Netty (`netty-all` und die `netty-*`-Module, inkl. Native-Transporte) | 4.1.115.Final | Apache-2.0 |
| LZ4 Java (`lz4-java`) | 1.10.2 | Apache-2.0 |
| tinylog (`tinylog-api`, `tinylog-impl`) | 2.7.0 | Apache-2.0 |
| XStream | 1.4.21 | BSD-3-Clause |
| MXParser (`mxparser`) | 1.2.2 | Indiana University Extreme! Lab Software License |
| xmlpull | 1.1.3.4a | siehe Projekt |
| JGraphT (`jgrapht-core`) | 1.5.2 | LGPL-2.1 oder EPL-2.0 (Doppellizenz laut Jar) |
| JHeaps | 0.14 | Apache-2.0 |
| jUPnP (`org.jupnp`) | 3.0.5 | CDDL-1.0 (laut Jar); nur wegen einer Typ-Signatur in Forges GUI-Schnittstelle im Classpath, wird nie gestartet |
| Sentry (`sentry`) | 8.21.1 | MIT; wird von MageLite nie initialisiert (Import-Skript bricht ab, falls Forge das ändert) |
| apfloat | 1.10.1 | siehe Projekt |
| rssreader | 3.8.2 | siehe Projekt |

## Engine (`engine/build.gradle.kts`)

| Komponente | Version | Lizenz |
|---|---|---|
| Javalin (REST/WebSocket) | 6.4.0 | Apache-2.0 |
| Jetty 11 (über Javalin) | – | Apache-2.0 oder EPL-2.0 |
| Jackson (`jackson-databind`) | 2.18.2 | Apache-2.0 |
| sqlite-jdbc (xerial) mit SQLite | 3.47.1.0 | Apache-2.0 (SQLite: Public Domain) |
| reload4j | 1.2.25 | Apache-2.0 |
| slf4j (`slf4j-reload4j`) | 2.0.17 | MIT |
| jsoup | 1.21.2 | MIT |

## Oberfläche (`ui`) und Desktop-App

| Komponente | Lizenz | Hinweis |
|---|---|---|
| React, React DOM, zustand, motion | MIT | im UI-Bundle |
| lucide-react (Icons) | ISC | im UI-Bundle |
| [Mana](https://github.com/andrewgioia/mana) (`mana-font`, Mana-Symbole) | SIL OFL 1.1 (Schrift) / MIT (CSS) | im UI-Bundle |
| Barlow Condensed, IBM Plex Sans, IBM Plex Mono (`@fontsource/*`) | SIL OFL 1.1 | im UI-Bundle |
| Vite, Tailwind CSS 4, TypeScript | MIT bzw. Apache-2.0 (TypeScript) | nur Build-Werkzeuge, nicht ausgeliefert |
| Electron (mit Chromium) | MIT; Chromium-Teile laut mitgelieferter `LICENSES.chromium.html` | Desktop-Hülle |
| electron-builder / NSIS | nur Build-Werkzeuge | erzeugen das Setup |
| Java-Laufzeit (jlink-Abbild des Build-JDK, z. B. Eclipse Temurin; Docker-Image `eclipse-temurin:17-jre`) | GPL-2.0 mit Classpath-Exception | `resources/jre` im Setup |

## Dienste (nicht mitgeliefert, nur Abruf)

| Dienst | Nutzung |
|---|---|
| [Scryfall](https://scryfall.com) | Kartenbilder und Token-Bilder, nur Abruf und lokaler Cache (`cache/images`), nicht verändert, nicht im Repo oder Installer |
| Archidekt, Moxfield | Deck-Import per URL (öffentliche API-Abfrage des Decks) |
| Brevo, Cloudflare Turnstile, fly.io, GitHub Releases | nur im Server-Betrieb (Mail, Captcha, Hosting, Setup-Download), siehe `docs/SERVER.md` |

## Quellcode

Der Quellcode jeder Version liegt im Repo [melknoo/xmage_clone](https://github.com/melknoo/xmage_clone); die genaue
Version nennt `SOURCE.txt` im Installer (Repo-Commit und Forge-Commit). Forge selbst:
[Card-Forge/forge](https://github.com/Card-Forge/forge) am Commit aus `vendor/forge/FORGE_COMMIT`.
