package dev.magelite.game;

import com.fasterxml.jackson.databind.JsonNode;
import dev.magelite.auth.Passwords;
import dev.magelite.auth.User;
import dev.magelite.deck.DeckResolver;
import dev.magelite.deck.LoadedDeck;
import dev.magelite.relay.RemoteGameSpec;
import org.apache.log4j.Logger;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

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

    /** Falsches/fehlendes Tisch-Passwort (403). */
    public static final class PasswordException extends RuntimeException {
        public PasswordException(String message) {
            super(message);
        }
    }

    public enum SeatKind { OPEN, HUMAN, BOT }

    /** Wo das Spiel rechnet: auf diesem Server oder auf dem Rechner des Gastgebers (Host-Link). */
    public enum Hosting { SERVER, REMOTE }

    /** Anbindung an die Relay-Spiele (nur Server-Modus mit Host-Link; sonst {@link #NO_REMOTE}). */
    public interface Remote {
        /** Hat der Gastgeber gerade seine Engine angebunden? */
        boolean linked(long hostUserId);

        /** Spiel auf dem Rechner des Gastgebers starten (blockiert bis zur Antwort, max. ~30 s). @return Spiel-id */
        UUID start(RemoteGameSpec spec) throws Exception;

        /** Spielt der Nutzer gerade in einem Relay-Spiel mit? */
        boolean inGame(long userId);

        int turn(UUID gameId);

        void abort(UUID gameId);
    }

    public static final Remote NO_REMOTE = new Remote() {
        @Override
        public boolean linked(long hostUserId) {
            return false;
        }

        @Override
        public UUID start(RemoteGameSpec spec) {
            throw new TableException("Tische auf dem eigenen Rechner gibt es hier nicht");
        }

        @Override
        public boolean inGame(long userId) {
            return false;
        }

        @Override
        public int turn(UUID gameId) {
            return 0;
        }

        @Override
        public void abort(UUID gameId) {
        }
    };

    private static final int PASSWORD_MAX = 40;
    private static final int PW_LIMIT = 5;
    private static final long PW_WINDOW_MS = 60_000;

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
        public Hosting hosting = Hosting.SERVER;
        /** Tisch-Passwort (privater Tisch), nur als Hash; null = oeffentlich */
        public String passwordHash;
        /** REMOTE: Startanfrage laeuft (Antwort des Hosts steht aus) */
        public boolean starting;
        /** LOBBY | RUNNING */
        public String state = "LOBBY";
        public UUID gameId;
        /** letztes Spiel dieses Tisches (fuer "Ergebnis" nach der Rueckkehr) */
        public UUID lastGameId;
        public final long createdAt = System.currentTimeMillis();
        public long updatedAt = createdAt;
        public final Deque<ChatMsg> chat = new ArrayDeque<>();
        final Map<Long, Deque<Long>> chatTimes = new HashMap<>();
        /** vom Gastgeber entfernte Nutzer: kein erneuter Beitritt, bis er sie wieder einlaedt */
        final Set<Long> kicked = new HashSet<>();

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

    /** Unveraenderliche Sicht auf einen Platz. */
    public record SeatSnap(SeatKind kind, long userId, String name, JsonNode deck) {
    }

    /**
     * Unveraenderliche Momentaufnahme eines Tisches (unter der Sperre kopiert, ausserhalb frei lesbar).
     * {@code chat} nur fuer Sitzende, sonst leer; {@code turn} und {@code spectators} (Anzahl Zuschauer) nur bei
     * RUNNING (sonst 0).
     */
    public record TableSnap(String id, String name, long hostUserId, String hostName, TempoSettings.Preset tempo, String state,
                            UUID gameId, UUID lastGameId, long createdAt, long updatedAt, List<SeatSnap> seats,
                            List<ChatMsg> chat, int turn, int spectators, Hosting hosting, boolean locked, boolean starting) {
        public int humans() {
            return (int) seats.stream().filter(s -> s.kind() == SeatKind.HUMAN).count();
        }

        public int seatIndexOf(long userId) {
            for (int i = 0; i < seats.size(); i++) {
                if (seats.get(i).kind() == SeatKind.HUMAN && seats.get(i).userId() == userId) {
                    return i;
                }
            }
            return -1;
        }

        public boolean invitable() {
            return "LOBBY".equals(state) && seats.stream().anyMatch(s -> s.kind() == SeatKind.OPEN);
        }
    }

    private final GameRegistry games;
    private final DeckResolver decks;
    private final Map<String, Table> tables = new LinkedHashMap<>();
    /**
     * Nach dem Entfernen (Nutzer, Tisch) - wird NACH dem Freigeben der Sperre aufgerufen (z.B. Einladungen verwerfen).
     * TableManager ruft nie unter seiner Sperre in andere Dienste.
     */
    private volatile BiConsumer<Long, String> onKicked = (u, t) -> {
    };
    private volatile Remote remote = NO_REMOTE;
    /** Fehlversuche beim Tisch-Passwort je Nutzer */
    private final Map<Long, Deque<Long>> pwAttempts = new HashMap<>();

    public TableManager(GameRegistry games, DeckResolver decks) {
        this.games = games;
        this.decks = decks;
    }

    public void setOnKicked(BiConsumer<Long, String> cb) {
        this.onKicked = cb;
    }

    public void setRemote(Remote remote) {
        this.remote = remote == null ? NO_REMOTE : remote;
    }

    // ------------------------------------------------------------------ Abfragen

    /** Momentaufnahme fuer einen Betrachter (Chat nur, wenn er sitzt). */
    public synchronized Optional<TableSnap> snapshot(String id, long viewerId) {
        Table t = tables.get(normalize(id));
        return t == null ? Optional.empty() : Optional.of(snap(t, viewerId));
    }

    public synchronized List<TableSnap> snapshots(long viewerId) {
        prune();
        List<TableSnap> out = new ArrayList<>(tables.size());
        for (Table t : tables.values()) {
            out.add(snap(t, viewerId));
        }
        return out;
    }

    /** Tisch, an dem der Nutzer sitzt, als Momentaufnahme. */
    public synchronized Optional<TableSnap> mineSnapshot(long userId) {
        return tables.values().stream().filter(t -> t.seatOf(userId).isPresent()).findFirst().map(t -> snap(t, userId));
    }

    /** Anzahl Tische (Lobby-Zaehler). */
    public synchronized int count() {
        prune();
        return tables.size();
    }

    /** Name des Tisches, dessen laufendes Spiel {@code gameId} ist (fuer Zuschauer). */
    public synchronized Optional<String> runningTableName(UUID gameId) {
        if (gameId == null) {
            return Optional.empty();
        }
        return tables.values().stream().filter(t -> "RUNNING".equals(t.state) && gameId.equals(t.gameId)).findFirst().map(t -> t.name);
    }

    /** Erneute Einladung durch den Gastgeber hebt das Entfernen auf. */
    public synchronized void unkick(String id, long byUserId, long userId) {
        Table t = tables.get(normalize(id));
        if (t != null && t.hostUserId == byUserId) {
            t.kicked.remove(userId);
        }
    }

    /** Wurde der Nutzer vom Gastgeber dieses Tisches entfernt (und noch nicht wieder eingeladen)? */
    public synchronized boolean isKicked(String id, long userId) {
        Table t = tables.get(normalize(id));
        return t != null && t.kicked.contains(userId);
    }

    private TableSnap snap(Table t, long viewerId) {
        List<SeatSnap> seats = new ArrayList<>(t.seats.length);
        for (Seat s : t.seats) {
            seats.add(new SeatSnap(s.kind, s.userId, s.name, s.deck == null ? null : s.deck.deepCopy()));
        }
        List<ChatMsg> chat = t.seatOf(viewerId).isPresent() ? List.copyOf(t.chat) : List.of();
        int turn = 0;
        int spectators = 0;
        if ("RUNNING".equals(t.state) && t.gameId != null) {
            if (t.hosting == Hosting.REMOTE) {
                turn = remote.turn(t.gameId);
            } else {
                // GameRegistry.get ist sperrfrei (ConcurrentHashMap), currentTurn()/spectatorCount() ohne Sperren
                GameHost g = games.get(t.gameId).orElse(null);
                if (g != null) {
                    turn = g.currentTurn();
                    spectators = g.spectatorCount();
                }
            }
        }
        return new TableSnap(t.id, t.name, t.hostUserId, t.hostName, t.tempo, t.state, t.gameId, t.lastGameId, t.createdAt,
                t.updatedAt, List.copyOf(seats), chat, turn, spectators, t.hosting, t.passwordHash != null, t.starting);
    }

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

    /** Kann man an diesen Tisch einladen (gibt es, Lobby, freier Platz)? */
    public synchronized boolean invitable(String id) {
        Table t = tables.get(normalize(id));
        if (t == null || !"LOBBY".equals(t.state)) {
            return false;
        }
        for (Seat s : t.seats) {
            if (s.kind == SeatKind.OPEN) {
                return true;
            }
        }
        return false;
    }

    public synchronized boolean seated(String id, long userId) {
        Table t = tables.get(normalize(id));
        return t != null && t.seatOf(userId).isPresent();
    }

    // ------------------------------------------------------------------ Lobby-Aktionen

    public synchronized Table create(User host, String name, TempoSettings.Preset tempo) {
        return create(host, name, tempo, Hosting.SERVER, null);
    }

    /**
     * @param hosting  REMOTE nur, wenn der Gastgeber gerade seine Engine angebunden hat (Host-Link)
     * @param password Tisch-Passwort (privater Tisch); null/leer = oeffentlich
     */
    public synchronized Table create(User host, String name, TempoSettings.Preset tempo, Hosting hosting, String password) {
        prune();
        mine(host.id()).ifPresent(t -> {
            throw new TableException("Du sitzt schon am Tisch „" + t.name + "“");
        });
        if (tables.size() >= MAX_TABLES) {
            throw new TableException("Zu viele offene Tische");
        }
        if (hosting == Hosting.REMOTE && !remote.linked(host.id())) {
            throw new TableException("Keine Verbindung zu deinem Rechner – MageLite-App starten und dort anmelden");
        }
        String pw = password == null ? "" : password.strip();
        if (pw.length() > PASSWORD_MAX) {
            throw new IllegalArgumentException("Passwort: höchstens " + PASSWORD_MAX + " Zeichen");
        }
        String id;
        do {
            id = randomId();
        } while (tables.containsKey(id));
        Table t = new Table(id, name == null || name.isBlank() ? defaultName(host.name()) : name.strip(), host);
        if (tempo != null) {
            t.tempo = tempo;
        }
        t.hosting = hosting == null ? Hosting.SERVER : hosting;
        t.passwordHash = pw.isEmpty() ? null : pwHash(id, pw);
        tables.put(id, t);
        LOG.info("Tisch " + id + " eroeffnet von " + host.name() + (t.hosting == Hosting.REMOTE ? " (auf seinem Rechner)" : "")
                + (t.passwordHash != null ? ", privat" : ""));
        return t;
    }

    public synchronized Table join(User user, String id) {
        return join(user, id, null, false);
    }

    /**
     * @param password Tisch-Passwort (bei privatem Tisch), sonst egal
     * @param invited  offene Einladung des Gastgebers -> kein Passwort noetig
     */
    public synchronized Table join(User user, String id, String password, boolean invited) {
        Table t = require(id);
        if (t.seatOf(user.id()).isPresent()) {
            return t;
        }
        if (t.kicked.contains(user.id())) {
            throw new TableException("Der Gastgeber hat dich von diesem Tisch entfernt");
        }
        if (t.passwordHash != null && !invited) {
            Deque<Long> q = pwAttempts.computeIfAbsent(user.id(), k -> new ArrayDeque<>());
            long now = System.currentTimeMillis();
            while (!q.isEmpty() && now - q.peekFirst() > PW_WINDOW_MS) {
                q.pollFirst();
            }
            if (q.size() >= PW_LIMIT) {
                throw new PasswordException("Zu viele Versuche – kurz warten");
            }
            if (password == null || password.isBlank() || !t.passwordHash.equals(pwHash(t.id, password.strip()))) {
                q.addLast(now);
                throw new PasswordException(password == null || password.isBlank() ? "Dieser Tisch ist privat" : "Passwort falsch");
            }
        }
        Optional<Table> other = mine(user.id());
        if (other.isPresent()) {
            if (other.get().hostUserId == user.id()) {
                throw new TableException("Du hast selbst einen Tisch offen – erst schließen");
            }
            leave(user, other.get().id);
        }
        if (!"LOBBY".equals(t.state) || t.starting) {
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

    /**
     * Gastgeber: Platz n frei lassen oder mit einem Bot (Deck-Angabe, null = zufaellig) besetzen. Einen anderen Menschen
     * entfernen ({@code OPEN} auf seinem Platz) geht nur in der Lobby; er kann danach erst nach erneuter Einladung
     * wieder beitreten. Waehrend eines Spiels sind alle Platz-Aenderungen gesperrt.
     */
    public Table setSeat(User host, String id, int n, SeatKind kind, JsonNode deck) {
        long[] kickedUser = {-1};
        Table t = setSeatLocked(host, id, n, kind, deck, kickedUser);
        if (kickedUser[0] > 0) {
            try {
                onKicked.accept(kickedUser[0], t.id); // ausserhalb der Sperre
            } catch (RuntimeException e) {
                LOG.warn("onKicked: " + e);
            }
        }
        return t;
    }

    private synchronized Table setSeatLocked(User host, String id, int n, SeatKind kind, JsonNode deck, long[] kickedUser) {
        Table t = requireHost(host, id);
        if (n < 0 || n >= t.seats.length) {
            throw new IllegalArgumentException("Platz 0-3");
        }
        if (kind == SeatKind.HUMAN) {
            throw new IllegalArgumentException("Menschen setzen sich selbst");
        }
        if (!"LOBBY".equals(t.state) || t.starting) {
            throw new TableException("Während des Spiels lassen sich die Plätze nicht ändern");
        }
        Seat cur = t.seats[n];
        if (cur.kind == SeatKind.HUMAN) {
            if (cur.userId == t.hostUserId) {
                throw new TableException("Deinen eigenen Platz kannst du nicht räumen");
            }
            if (kind != SeatKind.OPEN) {
                throw new TableException("Dieser Platz ist besetzt");
            }
            t.kicked.add(cur.userId);
            kickedUser[0] = cur.userId;
            LOG.info("Tisch " + t.id + ": " + cur.name + " entfernt");
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

    /**
     * Gastgeber startet: offene Plaetze fallen weg, alle Menschen brauchen ein Deck, mindestens 2 Spieler.
     * SERVER: Spiel hier. REMOTE: Decks hier aufloesen, Start auf dem Rechner des Gastgebers abwarten (ausserhalb der
     * Sperre, der Tisch ist solange {@code starting}), dann RUNNING.
     */
    public Table start(User host, String id) throws Exception {
        RemoteGameSpec spec = startLocked(host, id);
        if (spec == null) {
            return get(id).orElseThrow(() -> new TableException("Diesen Tisch gibt es nicht mehr"));
        }
        UUID gameId;
        try {
            gameId = remote.start(spec);
        } catch (Exception e) {
            startFailed(id);
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            throw new TableException(msg);
        }
        return markRunning(id, gameId);
    }

    /** @return null = Spiel laeuft schon hier (SERVER); sonst die Spec fuer den Rechner des Gastgebers */
    private synchronized RemoteGameSpec startLocked(User host, String id) throws Exception {
        Table t = requireHost(host, id);
        if (!"LOBBY".equals(t.state)) {
            throw new TableException("Das Spiel läuft schon");
        }
        if (t.starting) {
            throw new TableException("Der Start läuft gerade");
        }
        List<GameSetup.SeatSpec> specs = new ArrayList<>();
        List<RemoteGameSpec.Seat> remoteSeats = new ArrayList<>();
        List<String> usedSamples = new ArrayList<>();
        for (Seat s : t.seats) {
            switch (s.kind) {
                case HUMAN -> {
                    if (s.deck == null) {
                        throw new TableException(s.name + " hat noch kein Deck gewählt");
                    }
                    if (remote.inGame(s.userId)) {
                        throw new TableException(s.name + " spielt gerade noch an einem anderen Tisch");
                    }
                    LoadedDeck d = decks.resolve(s.userId, s.deck, usedSamples);
                    Long deckId = DeckResolver.userDeckId(s.deck);
                    specs.add(GameSetup.SeatSpec.human(s.userId, s.name, d, deckId));
                    remoteSeats.add(new RemoteGameSpec.Seat(true, s.userId, s.name, deckId, d.name(), d.dck(), null, null));
                }
                case BOT -> {
                    LoadedDeck d = decks.resolve(host.id(), s.deck, usedSamples);
                    specs.add(GameSetup.SeatSpec.bot(d));
                    remoteSeats.add(new RemoteGameSpec.Seat(false, 0, null, null, d.name(), d.dck(), null, null));
                }
                default -> {
                    // offen -> faellt weg
                }
            }
        }
        if (specs.size() < 2) {
            throw new TableException("Mindestens zwei Spieler (Mensch oder Bot)");
        }
        String tableId = t.id;
        if (t.hosting == Hosting.REMOTE) {
            if (!remote.linked(host.id())) {
                throw new TableException("Keine Verbindung zu deinem Rechner – MageLite-App starten und dort anmelden");
            }
            for (RemoteGameSpec.Seat s : remoteSeats) {
                if (s.dck() == null) {
                    throw new TableException("Deck von " + (s.name() == null ? "Bot" : s.name()) + " lässt sich nicht übertragen");
                }
            }
            // laufende Server-Spiele der Beteiligten enden (ein Tisch pro Nutzer, wie GameRegistry.start)
            for (GameSetup.SeatSpec s : specs) {
                if (s.human()) {
                    games.abortOf(s.userId());
                }
            }
            t.starting = true;
            t.touch();
            return new RemoteGameSpec(null, tableId, t.name, t.hostUserId, t.hostName, t.tempo.name(), 0, remoteSeats);
        }
        // Tisch-Spiele sind zuschaubar: oeffentliche Sicht ab dem ersten State (vor host.start())
        GameHost game = games.start(new GameSetup(specs, t.tempo), h -> onGameFinished(tableId, h), h -> h.setSpectatable(true));
        t.state = "RUNNING";
        t.gameId = game.getId();
        t.touch();
        LOG.info("Tisch " + t.id + ": Spiel " + game.getId() + " mit " + specs.size() + " Spielern gestartet");
        return null;
    }

    private synchronized Table markRunning(String id, UUID gameId) {
        Table t = tables.get(normalize(id));
        if (t == null) {
            // Tisch waehrend des Starts geschlossen: Spiel auf dem Host beenden
            remote.abort(gameId);
            throw new TableException("Diesen Tisch gibt es nicht mehr");
        }
        t.starting = false;
        t.state = "RUNNING";
        t.gameId = gameId;
        t.touch();
        LOG.info("Tisch " + t.id + ": Spiel " + gameId + " auf dem Rechner von " + t.hostName + " gestartet");
        return t;
    }

    private synchronized void startFailed(String id) {
        Table t = tables.get(normalize(id));
        if (t != null) {
            t.starting = false;
            t.touch();
        }
    }

    /** Relay-Spiel vorbei (fly-Seite): Tisch zurueck in die Lobby. */
    public synchronized void onRemoteFinished(String tableId, UUID gameId) {
        Table t = tableId == null ? null : tables.get(normalize(tableId));
        if (t == null || !gameId.equals(t.gameId)) {
            return;
        }
        t.state = "LOBBY";
        t.lastGameId = t.gameId;
        t.gameId = null;
        t.touch();
    }

    /**
     * Admin: Tisch schliessen. Ein laufendes Tischspiel wird abgebrochen; die Sitzenden fallen beim naechsten Polling
     * (4xx) aus dem Tisch. @return false, wenn es den Tisch nicht (mehr) gibt
     */
    public boolean adminClose(String id) {
        Table t;
        List<Long> seated = new ArrayList<>();
        synchronized (this) {
            t = tables.remove(normalize(id));
            if (t == null) {
                return false;
            }
            for (Seat s : t.seats) {
                if (s.kind == SeatKind.HUMAN) {
                    seated.add(s.userId);
                }
            }
        }
        LOG.info("Tisch " + t.id + " vom Admin geschlossen");
        // ausserhalb der Sperre: onGameFinished findet den Tisch nicht mehr und tut nichts
        if (t.gameId != null) {
            if (t.hosting == Hosting.REMOTE) {
                remote.abort(t.gameId);
            } else {
                games.get(t.gameId).ifPresent(GameHost::abort);
            }
        }
        for (long userId : seated) {
            try {
                onKicked.accept(userId, t.id);
            } catch (RuntimeException e) {
                LOG.warn("onKicked: " + e);
            }
        }
        return true;
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

    /** "Annas Tisch", "Ilias' Tisch" (Genitiv: Apostroph nach s, ss, x, z, ce). */
    static String defaultName(String hostName) {
        String n = hostName == null ? "" : hostName.strip();
        if (n.isEmpty()) {
            return "Neuer Tisch";
        }
        String l = n.toLowerCase(Locale.ROOT);
        boolean apostrophe = l.endsWith("s") || l.endsWith("ß") || l.endsWith("x") || l.endsWith("z") || l.endsWith("ce");
        return n + (apostrophe ? "' Tisch" : "s Tisch");
    }

    private static String normalize(String id) {
        return id == null ? "" : id.trim().toUpperCase(Locale.ROOT);
    }

    private static String pwHash(String tableId, String password) {
        return Passwords.tokenHash(tableId + ":" + password);
    }

    private static String randomId() {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(ID_ALPHABET.charAt(RANDOM.nextInt(ID_ALPHABET.length())));
        }
        return sb.toString();
    }
}
