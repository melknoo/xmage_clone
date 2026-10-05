# syntax=docker/dockerfile:1.7
# MageLite Server-Image (fly.io). Drei Stufen: UI (Vite), Engine (Gradle installDist), Laufzeit (JRE 17).
# Die XMage-Jars werden nur kopiert, nie neu gepackt (CardRepository prueft die Build-Time im Manifest).

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
COPY vendor/xmage/lib vendor/xmage/lib
COPY engine/ engine/
WORKDIR /src/engine
# gradlew hat im Repo keine Ausfuehrungsrechte -> ueber sh starten
RUN --mount=type=cache,target=/root/.gradle sh ./gradlew installDist --no-daemon -q

# ---- 3. Laufzeit
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=engine /src/engine/build/install/magelite-engine/lib /app/lib
COPY --from=ui /src/ui/dist /app/ui
COPY vendor/xmage/lib /app/vendor/xmage/lib
COPY vendor/xmage/sample-decks /app/vendor/xmage/sample-decks
COPY vendor/xmage/sounds /app/vendor/xmage/sounds
COPY vendor/xmage/manifest.json vendor/xmage/LICENSE-XMage.txt /app/vendor/xmage/
RUN mkdir -p /data
# /data ist auf fly das Volume: db/cards.h2 (beim ersten Start gebaut), magelite.db, cache/images, logs
WORKDIR /data
EXPOSE 8080
ENV MAGELITE_IDLE_EXIT_MIN=10 \
    MAGELITE_MAX_GAMES=1

# Engine-Jar explizit VOR lib/* (harte Regel 10: eigener GameStateEvaluator2 muss zuerst geladen werden)
CMD exec java -XX:MaxRAMPercentage=70 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError \
    -Djava.awt.headless=true -Dfile.encoding=UTF-8 -Dmagelite.vendor=/app/vendor/xmage \
    -cp "/app/lib/magelite-engine-0.1.0.jar:/app/lib/*" dev.magelite.Main \
    --server --host=0.0.0.0 --port=8080 --data=/data --vendor=/app/vendor/xmage --ui=/app/ui \
    --max-games=${MAGELITE_MAX_GAMES} --idle-exit-min=${MAGELITE_IDLE_EXIT_MIN}
