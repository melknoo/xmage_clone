# syntax=docker/dockerfile:1.7
# MageLite Server-Image (fly.io). Drei Stufen: UI (Vite), Engine (Gradle installDist), Laufzeit (JRE 17).
# Forge wird NICHT im Docker gebaut: vendor/forge/{lib,res} entstehen lokal durch scripts\import-forge.ps1 und kommen
# aus dem Build-Kontext (deploy-fly.ps1 prueft vorher manifest.json/FORGE_COMMIT/cardsfolder.zip).

# ---- 1. UI
FROM node:22-bookworm-slim AS ui
WORKDIR /src/ui
COPY ui/package.json ui/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY ui/ ./
RUN npm run build

# ---- 2. Engine
FROM eclipse-temurin:17-jdk AS engine
WORKDIR /src
# Gradle braucht nur die Forge-Jars (Dependencies) und den Commit (landet in magelite-version.properties); res/ und
# manifest.json liest erst die Laufzeit. Eigene Schichten, damit ein Engine-Edit die 23 MB Jars nicht neu kopiert.
COPY vendor/forge/lib vendor/forge/lib
COPY vendor/forge/FORGE_COMMIT vendor/forge/FORGE_COMMIT
COPY engine/ engine/
# Versionsquelle (build.gradle.kts liest "version")
COPY desktop/package.json desktop/package.json
WORKDIR /src/engine
# gradlew hat im Repo keine Ausfuehrungsrechte -> ueber sh starten
RUN --mount=type=cache,target=/root/.gradle sh ./gradlew installDist --no-daemon -q

# ---- 3. Laufzeit
FROM eclipse-temurin:17-jre
WORKDIR /app
# Engine-Jar + alle Abhaengigkeiten (Forge-Jars, Javalin, Jackson, SQLite ...)
COPY --from=engine /src/engine/build/install/magelite-engine/lib /app/lib
COPY --from=ui /src/ui/dist /app/ui
# Forge-Daten (--forge=/app/forge): Kartenskripte als Zip, Editionen, KI-Profile; ForgeBoot liest manifest.json
COPY vendor/forge/res /app/forge/res
COPY vendor/forge/forge.profile.properties vendor/forge/manifest.json vendor/forge/LICENSE-Forge.txt /app/forge/
# GPL-3.0-or-later: Lizenztexte liegen im Image
COPY LICENSE /app/LICENSE
COPY LICENSES /app/LICENSES/
RUN mkdir -p /data
# /data ist auf fly das Volume: magelite.db, cache/images, forge-data/, logs. Das Arbeitsverzeichnis MUSS der
# Datenordner sein (das Forge-Profil hat relative forge-data/-Pfade, ForgeBoot meldet sonst einen Fehler).
WORKDIR /data
EXPOSE 8080
ENV MAGELITE_IDLE_EXIT_MIN=10 \
    MAGELITE_ANON_EXIT_MIN=3 \
    MAGELITE_MAX_GAMES=1

# Engine-Jar vor lib/* auf dem Classpath (Konvention, keine harte Regel mehr: kein XMage-Klassenersatz)
CMD exec java -XX:MaxRAMPercentage=70 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError \
    -Djava.awt.headless=true -Dfile.encoding=UTF-8 \
    -cp "/app/lib/magelite-engine.jar:/app/lib/*" dev.magelite.Main \
    --server --host=0.0.0.0 --port=8080 --data=/data --forge=/app/forge --ui=/app/ui \
    --max-games=${MAGELITE_MAX_GAMES} --idle-exit-min=${MAGELITE_IDLE_EXIT_MIN} \
    --anon-exit-min=${MAGELITE_ANON_EXIT_MIN}
