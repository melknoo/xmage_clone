package dev.magelite.game;

import com.fasterxml.jackson.databind.JsonNode;
import dev.magelite.auth.User;
import dev.magelite.deck.DeckResolver;
import dev.magelite.deck.LoadedDeck;
import org.apache.log4j.Logger;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Tische der Lobby (nur Server-Modus): ein Gastgeber eroeffnet einen Tisch mit 4 Plaetzen, Freunde treten bei,
 * freie Plaetze werden mit Bots besetzt oder fallen beim Start weg. Nach dem Spiel geht der Tisch zurueck in die
 * Lobby (Revanche). Ein Nutzer sitzt an hoechstens einem Tisch, ein Gastgeber hat hoechstens einen Tisch.
 * <p>
 * Synchronisation ueber die Lobby per Polling ({@code GET /api/tables/{id}}), kein eigener WebSocket-Kanal.
 */
public final class TableManager {

    private static final Logger LOG = Logger.getLogger(TableManager.class);
    private static final int MAX_TABLES = 8;
    private static final long STALE_MS = 2 * 60 * 60_000L;
    private static final String ID_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Fehlbedienung (409): belegt, voll, nicht erlaubt ... */
    public static final class TableException extends RuntimeException {
        public TableException(String message) {
            super(message);
        }
    }

    public enum SeatKind { OPEN, HUMAN, BOT }

    /** Chat-Zeile am Tisch (ueberlebt Spiele dieses Tisches, weg mit dem Tisch). */
    public record ChatMsg(long ts, long userId, String name, String text) {
    }

    private static final int CHAT_KEEP = 50;

    public static final class Seat {
        public SeatKind kind = SeatKind.OPEN;
        public long userId;
        public String name;
        /** Deck-Angabe wie in der API ({type, id}); null = noch nicht gewaehlt */
        public JsonNode deck;

        static Seat open() {
            return new Seat();
        }

        static Seat human(User u) {
            Seat s = new Seat();
            s.kind = SeatKind.HUMAN;
            s.userId = u.id();
            s.name = u.name();
            return s;
        }

        static Seat bot(JsonNode deck) {
            Seat s = new Seat();
            s.kind = SeatKind.BOT;
            s.deck = deck;
            return s;
        }
    }

    public static final class Table {
        public final String id;
        public String name;
        public final long hostUserId;
        public final String hostName;
        public final Seat[] seats = new Seat[4];
        public TempoSettings.Preset tempo = TempoSettings.Preset.NORMAL;
        /** LOBBY | RUNNING */
        public String state = "LOBBY";
        public UUID gameId;
        /** letztes Spiel dieses Tisches (fuer "Ergebnis" nach der Rueckkehr) */
        public UUID lastGameId;
        public final long createdAt = System.currentTimeMillis();
        public long updatedAt = createdAt;
        public final Deque<ChatMsg> chat = new ArrayDeque<>();
        final Map<Long, Deque<Long>> chatTimes = new HashMap<>();

        Table(String id, String name, User host) {
            this.id = id;
            this.name = name;
            this.hostUserId = host.id();
            this.hostName = host.name();
            seats[0] = Seat.human(host);
            for (int i = 1; i < 4; i++) {
                seats[i] = Seat.open();
            }
        }

        Optional<Seat> seatOf(long userId) {
            for (Seat s : seats) {
                if (s.kind == SeatKind.HUMAN && s.userId == userId) {
                    return Optional.of(s);
                }
            }
            return Optional.empty();
        }

        int seatIndexOf(long userId) {
            for (int i = 0; i < seats.length; i++) {
                if (seats[i].kind == SeatKind.HUMAN && seats[i].userId == userId) {
                    return i;
                }
            }
            return -1;
        }

        void touch() {
            updatedAt = System.currentTimeMillis();
        }
    }

    private final GameRegistry games;
    private final DeckResolver decks;
    private final Map<String, Table> tables = new LinkedHashMap<>();

    public TableManager(GameRegistry games, DeckResolver decks) {
        this.games = games;
        this.decks = decks;
    }

    // ------------------------------------------------------------------ Abfragen

    public synchronized List<Table> list() {
        prune();
        return new ArrayList<>(tables.values());
    }

    public synchronized Optional<Table> get(String id) {
        return Optional.ofNullable(tables.get(normalize(id)));
    }

    /** Tisch, an dem der Nutzer sitzt. */
    public synchronized Optional<Table> mine(long userId) {
        return tables.values().stream().filter(t -> t.seatOf(userId).isPresent()).findFirst();
    }

    // ------------------------------------------------------------------ Lobby-Aktionen

    public synchronized Table create(User host, String name, TempoSettings.Preset tempo) {
        prune();
        mine(host.id()).ifPresent(t -> {
            throw new TableException("Du sitzt schon am Tisch „" + t.name + "“");
        });
        if (tables.size() >= MAX_TABLES) {
            throw new TableException("Zu viele offene Tische");
        }
        String id;
        do {
            id = randomId();
        } while (tables.containsKey(id));
        Table t = new Table(id, name == null || name.isBlank() ? "Tisch von " + host.name() : name.strip(), host);
        if (tempo != null) {
            t.tempo = tempo;
        }
        tables.put(id, t);
        LOG.info("Tisch " + id + " eroeffnet von " + host.name());
        return t;
    }

    public synchronized Table join(User user, String id) {
        Table t = require(id);
        if (t.seatOf(user.id()).isPresent()) {
            return t;
        }
        Optional<Table> other = mine(user.id());
        if (other.isPresent()) {
            if (other.get().hostUserId == user.id()) {
                throw new TableException("Du hast selbst einen Tisch offen – erst schließen");
            }
            leave(user, other.get().id);
        }
        if (!"LOBBY".equals(t.state)) {
            throw new TableException("Am Tisch läuft gerade ein Spiel");
        }
        for (Seat s : t.seats) {
            if (s.kind == SeatKind.OPEN) {
                Seat h = Seat.human(user);
                replace(t, s, h);
                t.touch();
                return t;
            }
        }
        throw new TableException("Der Tisch ist voll");
    }

    /** Verlaesst den Tisch; der Gastgeber schliesst ihn damit. @return true, wenn der Tisch geschlossen wurde */
    public synchronized boolean leave(User user, String id) {
        Table t = require(id);
        if (t.hostUserId == user.id()) {
            tables.remove(t.id);
            LOG.info("Tisch " + t.id + " geschlossen");
            return true;
        }
        int i = t.seatIndexOf(user.id());
        if (i >= 0) {
            t.seats[i] = Seat.open();
            t.touch();
        }
        return false;
    }

    public synchronized Table setMyDeck(User user, String id, JsonNode deck) {
        Table t = require(id);
        Seat s = t.seatOf(user.id()).orElseThrow(() -> new TableException("Du sitzt nicht an diesem Tisch"));
        if (deck != null && !deck.isNull()) {
            decks.describe(user.id(), deck).orElseThrow(() -> new IllegalArgumentException("Deck nicht gefunden"));
        }
        s.deck = deck == null || deck.isNull() ? null : deck;
        t.touch();
        return t;
    }

    /** Gastgeber: Platz n frei lassen oder mit einem Bot (Deck-Angabe, null = zufaellig) besetzen. */
    public synchronized Table setSeat(User host, String id, int n, SeatKind kind, JsonNode deck) {
        Table t = requireHost(host, id);
        if (n < 0 || n >= t.seats.length) {
            throw new IllegalArgumentException("Platz 0-3");
        }
        if (t.seats[n].kind == SeatKind.HUMAN) {
            throw new TableException("Dieser Platz ist besetzt");
        }
        if (kind == SeatKind.HUMAN) {
            throw new IllegalArgumentException("Menschen setzen sich selbst");
        }
        t.seats[n] = kind == SeatKind.BOT ? Seat.bot(deck == null || deck.isNull() ? null : deck) : Seat.open();
        t.touch();
        return t;
    }

    public synchronized Table update(User host, String id, String name, TempoSettings.Preset tempo) {
        Table t = requireHost(host, id);
        if (name != null && !name.isBlank()) {
            t.name = name.strip();
        }
        if (tempo != null) {
            t.tempo = tempo;
        }
        t.touch();
        return t;
    }

    /** Gastgeber startet: offene Plaetze fallen weg, alle Menschen brauchen ein Deck, mindestens 2 Spieler. */
    public synchronized Table start(User host, String id) throws Exception {
        Table t = requireHost(host, id);
        if (!"LOBBY".equals(t.state)) {
            throw new TableException("Das Spiel läuft schon");
        }
        List<GameSetup.SeatSpec> specs = new ArrayList<>();
        List<String> usedSamples = new ArrayList<>();
        for (Seat s : t.seats) {
            switch (s.kind) {
                case HUMAN -> {
                    if (s.deck == null) {
                        throw new TableException(s.name + " hat noch kein Deck gewählt");
                    }
                    LoadedDeck d = decks.resolve(s.userId, s.deck, usedSamples);
                    specs.add(GameSetup.SeatSpec.human(s.userId, s.name, d, DeckResolver.userDeckId(s.deck)));
                }
                case BOT -> specs.add(GameSetup.SeatSpec.bot(decks.resolve(host.id(), s.deck, usedSamples)));
                default -> {
                    // offen -> faellt weg
                }
            }
        }
        if (specs.size() < 2) {
            throw new TableException("Mindestens zwei Spieler (Mensch oder Bot)");
        }
        String tableId = t.id;
        GameHost game = games.start(new GameSetup(specs, t.tempo), h -> onGameFinished(tableId, h));
        t.state = "RUNNING";
        t.gameId = game.getId();
        t.touch();
        LOG.info("Tisch " + t.id + ": Spiel " + game.getId() + " mit " + specs.size() + " Spielern gestartet");
        return t;
    }

    private synchronized void onGameFinished(String tableId, GameHost host) {
        Table t = tables.get(tableId);
        if (t == null || !host.getId().equals(t.gameId)) {
            return;
        }
        t.state = "LOBBY";
        t.lastGameId = t.gameId;
        t.gameId = null;
        t.touch();
    }

    /** Chat am Tisch: nur wer sitzt; Saeuberung/Laenge/Rate-Limit wie im Spiel. */
    public synchronized Table chat(User user, String id, String text) {
        Table t = require(id);
        if (t.seatOf(user.id()).isEmpty()) {
            throw new TableException("Du sitzt nicht an diesem Tisch");
        }
        String clean = ChatText.clean(text);
        if (clean == null) {
            throw new TableException("Leere Nachricht");
        }
        Deque<Long> times = t.chatTimes.computeIfAbsent(user.id(), k -> new ArrayDeque<>());
        if (!ChatText.allow(times, System.currentTimeMillis())) {
            throw new TableException("Langsamer – höchstens " + ChatText.RATE_N + " Nachrichten in " + (ChatText.RATE_MS / 1000) + " s");
        }
        t.chat.addLast(new ChatMsg(System.currentTimeMillis(), user.id(), user.name(), clean));
        while (t.chat.size() > CHAT_KEEP) {
            t.chat.pollFirst();
        }
        t.touch();
        return t;
    }

    // ------------------------------------------------------------------ intern

    private Table require(String id) {
        Table t = tables.get(normalize(id));
        if (t == null) {
            throw new TableException("Diesen Tisch gibt es nicht mehr");
        }
        return t;
    }

    private Table requireHost(User user, String id) {
        Table t = require(id);
        if (t.hostUserId != user.id()) {
            throw new TableException("Nur der Gastgeber darf das");
        }
        return t;
    }

    private static void replace(Table t, Seat old, Seat neu) {
        for (int i = 0; i < t.seats.length; i++) {
            if (t.seats[i] == old) {
                t.seats[i] = neu;
                return;
            }
        }
    }

    /** Alte Tische ohne laufendes Spiel entfernen. */
    private void prune() {
        long now = System.currentTimeMillis();
        tables.values().removeIf(t -> "LOBBY".equals(t.state) && now - t.updatedAt > STALE_MS);
    }

    private static String normalize(String id) {
        return id == null ? "" : id.trim().toUpperCase(Locale.ROOT);
    }

    private static String randomId() {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(ID_ALPHABET.charAt(RANDOM.nextInt(ID_ALPHABET.length())));
        }
        return sb.toString();
    }
}
