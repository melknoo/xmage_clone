package dev.magelite.admin;

import dev.magelite.auth.AccountService;
import dev.magelite.game.GameHost;
import dev.magelite.game.GameRegistry;
import dev.magelite.game.TableManager;
import dev.magelite.relay.RemoteGames;
import dev.magelite.social.SocialService;
import dev.magelite.stats.Db;
import dev.magelite.stats.Progression;

import java.lang.management.ManagementFactory;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntSupplier;

/**
 * Admin-Uebersicht im Server-Modus: Nutzer mit Kennzahlen und Status, Nutzer-Detail (Partien, Decks, Sessions),
 * Server-Zustand (laufende Spiele, Tische, Speicher) und Eingriffe (abmelden, Spiel beenden, Tisch schliessen).
 * Nur lesende SQL ueber alle Nutzer; Aenderungen laufen ueber die zustaendigen Dienste.
 */
public final class AdminService {

    /** Zeile der Nutzerliste. {@code status}: game | table | online | offline. */
    public record UserRow(long id, String name, boolean admin, long createdAt, Long lastSeen, String email, boolean hasPassword,
                          long xp, int level, String title, int games, int wins, Long lastGameAt, int decks, int sessions,
                          String status, String tableName) {
    }

    public record GameRow(String id, long startedAt, Long endedAt, Long durationMs, Integer turns, String deckName, String commander,
                          String result, Integer placement, String tempo, String endReason, int xp) {
    }

    public record DeckRow(long id, String name, String commanders, int cards, boolean valid, int masteryXp, long updatedAt) {
    }

    /** Session ohne Token (nur Metadaten). */
    public record SessionRow(long id, String via, long createdAt, Long lastSeen) {
    }

    public record UserDetail(UserRow user, List<GameRow> games, List<DeckRow> decks, List<SessionRow> sessions) {
    }

    public record SeatInfo(long userId, String name, boolean connected, boolean conceded) {
    }

    /**
     * Laufendes Spiel; {@code table} = Tischname oder null (allein gegen Bots); {@code remoteHost} = Name des Gastgebers,
     * wenn das Spiel auf dessen Rechner laeuft (Host-Link), sonst null.
     */
    public record GameInfo(String id, String table, String tempo, long startedAt, int turn, int bots, int spectators,
                           List<SeatInfo> humans, String remoteHost) {
    }

    public record TableInfo(String id, String name, String hostName, String state, int humans, int bots, int open,
                            String gameId, long createdAt, String hosting, boolean locked) {
    }

    /** {@code running} zaehlt Server- und Relay-Spiele, {@code maxGames} nur Server-Spiele; {@code hostLinks} = angebundene Engines. */
    public record ServerInfo(String version, long startedAt, long uptimeMs, long heapUsed, long heapMax, int maxGames,
                             int running, int online, int tableCount, List<GameInfo> games, List<TableInfo> tables, int hostLinks) {
    }

    private static final String USER_SQL = """
            SELECT u.id, u.name, u.is_admin, u.created_at, u.last_seen, u.email, u.pw_hash IS NOT NULL,
                   COALESCE(p.xp_total, 0),
                   (SELECT COUNT(*) FROM games g WHERE g.user_id = u.id),
                   (SELECT COUNT(*) FROM games g WHERE g.user_id = u.id AND g.placement = 1),
                   (SELECT MAX(g.ended_at) FROM games g WHERE g.user_id = u.id),
                   (SELECT COUNT(*) FROM decks d WHERE d.user_id = u.id),
                   (SELECT COUNT(*) FROM sessions s WHERE s.user_id = u.id)
            FROM users u LEFT JOIN profile p ON p.id = u.id
            WHERE u.id != 1""";

    private final Db db;
    private final AccountService accounts;
    private final GameRegistry games;
    private final TableManager tables;
    private final SocialService social;
    private final String version;
    private final RemoteGames remoteGames;
    private final IntSupplier hostLinks;

    public AdminService(Db db, AccountService accounts, GameRegistry games, TableManager tables, SocialService social, String version) {
        this(db, accounts, games, tables, social, version, null, () -> 0);
    }

    /** @param remoteGames Relay-Spiele (Host-Link), null ohne; {@code hostLinks} = Anzahl angebundener Engines */
    public AdminService(Db db, AccountService accounts, GameRegistry games, TableManager tables, SocialService social, String version,
                        RemoteGames remoteGames, IntSupplier hostLinks) {
        this.db = db;
        this.accounts = accounts;
        this.games = games;
        this.tables = tables;
        this.social = social;
        this.version = version;
        this.remoteGames = remoteGames;
        this.hostLinks = hostLinks;
    }

    // ------------------------------------------------------------------ Nutzer

    public List<UserRow> users() {
        record Raw(long id, String name, boolean admin, long createdAt, Long lastSeen, String email, boolean hasPassword,
                   long xp, int games, int wins, Long lastGameAt, int decks, int sessions) {
        }
        List<Raw> raws = db.with(c -> {
            List<Raw> out = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(USER_SQL + " ORDER BY u.id")) {
                while (rs.next()) {
                    out.add(new Raw(rs.getLong(1), rs.getString(2), rs.getInt(3) != 0, rs.getLong(4), nullableLong(rs, 5),
                            rs.getString(6), rs.getInt(7) != 0, rs.getLong(8), rs.getInt(9), rs.getInt(10), nullableLong(rs, 11),
                            rs.getInt(12), rs.getInt(13)));
                }
            }
            return out;
        });
        // Status ausserhalb der DB-Sperre (SocialService fragt TableManager/GameRegistry)
        List<UserRow> out = new ArrayList<>(raws.size());
        for (Raw r : raws) {
            SocialService.Presence p = social.presence(r.id());
            int level = Progression.levelOf(r.xp()).level();
            out.add(new UserRow(r.id(), r.name(), r.admin(), r.createdAt(), r.lastSeen(), r.email(), r.hasPassword(), r.xp(), level,
                    Progression.titleOf(level), r.games(), r.wins(), r.lastGameAt(), r.decks(), r.sessions(), p.status(), p.tableName()));
        }
        return out;
    }

    public Optional<UserDetail> user(long id) {
        if (id == 1) {
            return Optional.empty();
        }
        Optional<UserRow> row = users().stream().filter(u -> u.id() == id).findFirst();
        if (row.isEmpty()) {
            return Optional.empty();
        }
        List<GameRow> gameRows = db.with(c -> {
            List<GameRow> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, started_at, ended_at, duration_ms, turns, deck_name, commander, result, placement, tempo, end_reason, xp_awarded "
                            + "FROM games WHERE user_id = ? ORDER BY started_at DESC LIMIT 15")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new GameRow(rs.getString(1), rs.getLong(2), nullableLong(rs, 3), nullableLong(rs, 4), nullableInt(rs, 5),
                                rs.getString(6), rs.getString(7), rs.getString(8), nullableInt(rs, 9), rs.getString(10), rs.getString(11),
                                rs.getInt(12)));
                    }
                }
            }
            return out;
        });
        List<DeckRow> deckRows = db.with(c -> {
            List<DeckRow> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, name, commanders, card_count, valid, mastery_xp, updated_at FROM decks WHERE user_id = ? ORDER BY updated_at DESC")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new DeckRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5) != 0,
                                rs.getInt(6), rs.getLong(7)));
                    }
                }
            }
            return out;
        });
        List<SessionRow> sessionRows = db.with(c -> {
            List<SessionRow> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, via, created_at, last_seen FROM sessions WHERE user_id = ? ORDER BY COALESCE(last_seen, created_at) DESC")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new SessionRow(rs.getLong(1), rs.getString(2), rs.getLong(3), nullableLong(rs, 4)));
                    }
                }
            }
            return out;
        });
        return Optional.of(new UserDetail(row.get(), gameRows, deckRows, sessionRows));
    }

    /** Alle Sessions beenden, WebSockets schliessen, Sitz im laufenden Spiel aufgeben. */
    public boolean logout(long id) {
        return accounts.revokeSessions(id);
    }

    // ------------------------------------------------------------------ Server

    public ServerInfo server() {
        Runtime rt = Runtime.getRuntime();
        long started = ManagementFactory.getRuntimeMXBean().getStartTime();
        List<GameInfo> gameInfos = new ArrayList<>();
        for (GameHost g : games.runningGames()) {
            List<SeatInfo> seats = new ArrayList<>();
            for (GameHost.HumanSeat s : g.humanSeats()) {
                seats.add(new SeatInfo(s.userId(), s.name(), s.connected(), s.conceded()));
            }
            int bots = (int) g.getSetup().seats().stream().filter(s -> !s.human()).count();
            gameInfos.add(new GameInfo(g.getId().toString(), tables.runningTableName(g.getId()).orElse(null),
                    g.getSetup().tempo().name(), g.startedAt(), g.currentTurn(), bots, g.spectatorCount(), seats, null));
        }
        if (remoteGames != null) {
            for (RemoteGames.RemoteGame g : remoteGames.runningGames()) {
                List<SeatInfo> seats = new ArrayList<>();
                for (RemoteGames.SeatView s : g.seatViews()) {
                    seats.add(new SeatInfo(s.userId(), s.name(), s.connected(), s.conceded()));
                }
                gameInfos.add(new GameInfo(g.id.toString(), g.tableName, g.tempo(), g.startedAt, g.turn(), g.bots(), 0, seats, g.hostName));
            }
        }
        List<TableInfo> tableInfos = new ArrayList<>();
        for (TableManager.TableSnap t : tables.snapshots(0)) {
            int humans = 0;
            int bots = 0;
            int open = 0;
            for (TableManager.SeatSnap s : t.seats()) {
                switch (s.kind()) {
                    case HUMAN -> humans++;
                    case BOT -> bots++;
                    default -> open++;
                }
            }
            tableInfos.add(new TableInfo(t.id(), t.name(), t.hostName(), t.state(), humans, bots, open,
                    t.gameId() == null ? null : t.gameId().toString(), t.createdAt(), t.hosting().name(), t.locked()));
        }
        return new ServerInfo(version, started, System.currentTimeMillis() - started, rt.totalMemory() - rt.freeMemory(), rt.maxMemory(),
                games.maxGames(), gameInfos.size(), social.onlineCount(), tableInfos.size(), gameInfos, tableInfos, hostLinks.getAsInt());
    }

    /** Laufendes Spiel beenden (alle geben auf). */
    public boolean abortGame(String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return false;
        }
        Optional<GameHost> g = games.get(uuid).filter(GameHost::isRunning);
        g.ifPresent(GameHost::abort);
        if (g.isEmpty() && remoteGames != null) {
            return remoteGames.abort(uuid);
        }
        return g.isPresent();
    }

    public boolean closeTable(String id) {
        return tables.adminClose(id);
    }

    // ------------------------------------------------------------------ intern

    private static Long nullableLong(ResultSet rs, int col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Integer nullableInt(ResultSet rs, int col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }
}
