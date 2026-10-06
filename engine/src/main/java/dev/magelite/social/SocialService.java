package dev.magelite.social;

import dev.magelite.auth.User;
import dev.magelite.game.ChatText;
import dev.magelite.game.GameRegistry;
import dev.magelite.game.TableManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Soziales im Server-Modus: Lobby-Chat (nur im Speicher), Praesenz (wer pollt gerade), Freundesliste mit Status und
 * Tisch-Einladungen. Die UI holt alles ueber einen Poll ({@code GET /api/social}).
 * <p>
 * Sperren: der eigene Zustand liegt unter {@code lock}; Tisch-/Spiel-/DB-Abfragen laufen ausserhalb davon, damit es
 * keine Sperr-Reihenfolge mit {@link TableManager} gibt.
 */
public final class SocialService {

    /** Fehlbedienung (409). */
    public static final class SocialException extends RuntimeException {
        public SocialException(String message) {
            super(message);
        }
    }

    public record ChatMsg(long seq, long ts, long userId, String name, String text) {
    }

    public record Member(long id, String name) {
    }

    public record Invite(long id, long fromUserId, String fromName, long toUserId, String tableId, String tableName, long ts) {
    }

    public record FriendView(long id, String name, String status, String tableId, String tableName) {
    }

    public record Snapshot(boolean chatIn, long seq, List<ChatMsg> msgs, List<Member> members, List<FriendView> friends,
                           List<FriendStore.Entry> incoming, List<FriendStore.Entry> outgoing, List<Invite> invites) {
    }

    static final int CHAT_KEEP = 100;
    static final long ONLINE_MS = 15_000;
    static final long INVITE_MS = 10 * 60_000L;

    private record Seen(String name, long ts) {
    }

    private final FriendStore friends;
    private final TableManager tables;
    private final GameRegistry games;

    private final Object lock = new Object();
    private final Deque<ChatMsg> chat = new ArrayDeque<>();
    private final Map<Long, Deque<Long>> chatTimes = new HashMap<>();
    private final Map<Long, Seen> seen = new HashMap<>();
    /** Cache der DB-Einstellung lobby_chat (spart eine Abfrage pro Mitglied und Poll). */
    private final Map<Long, Boolean> chatIn = new HashMap<>();
    private final Map<Long, Invite> invites = new LinkedHashMap<>();
    private long seq;
    private long inviteSeq;

    public SocialService(FriendStore friends, TableManager tables, GameRegistry games) {
        this.friends = friends;
        this.tables = tables;
        this.games = games;
    }

    // ------------------------------------------------------------------ Poll

    public Snapshot poll(User me, long after) {
        long now = System.currentTimeMillis();
        boolean in = isIn(me.id());
        synchronized (lock) {
            seen.put(me.id(), new Seen(me.name(), now));
        }

        List<FriendView> friendViews = new ArrayList<>();
        List<FriendStore.Entry> incoming = new ArrayList<>();
        List<FriendStore.Entry> outgoing = new ArrayList<>();
        for (FriendStore.Entry e : friends.list(me.id())) {
            switch (e.state()) {
                case "friend" -> friendViews.add(friendView(e, now));
                case "incoming" -> incoming.add(e);
                default -> outgoing.add(e);
            }
        }
        friendViews.sort(Comparator.comparingInt((FriendView f) -> statusRank(f.status())).thenComparing(f -> f.name().toLowerCase()));

        List<Invite> pending;
        synchronized (lock) {
            invites.values().removeIf(i -> now - i.ts() > INVITE_MS);
            pending = invites.values().stream().filter(i -> i.toUserId() == me.id()).toList();
        }
        List<Invite> valid = new ArrayList<>();
        for (Invite i : pending) {
            if (tables.invitable(i.tableId()) && !tables.seated(i.tableId(), me.id())) {
                valid.add(i);
            }
        }

        List<Long> online = new ArrayList<>();
        synchronized (lock) {
            seen.values().removeIf(s -> now - s.ts() > ONLINE_MS * 4);
            seen.forEach((id, s) -> {
                if (now - s.ts() <= ONLINE_MS) {
                    online.add(id);
                }
            });
        }
        List<Member> members = new ArrayList<>();
        for (long id : online) {
            if (isIn(id)) {
                synchronized (lock) {
                    Seen s = seen.get(id);
                    if (s != null) {
                        members.add(new Member(id, s.name()));
                    }
                }
            }
        }
        members.sort(Comparator.comparing(m -> m.name().toLowerCase()));

        synchronized (lock) {
            List<ChatMsg> msgs = !in ? List.of() : chat.stream().filter(m -> m.seq() > after).toList();
            return new Snapshot(in, seq, msgs, members, friendViews, incoming, outgoing, valid);
        }
    }

    private FriendView friendView(FriendStore.Entry e, long now) {
        if (games.currentOf(e.userId()).isPresent()) {
            return new FriendView(e.userId(), e.name(), "game", null, null);
        }
        boolean on;
        synchronized (lock) {
            Seen s = seen.get(e.userId());
            on = s != null && now - s.ts() <= ONLINE_MS;
        }
        if (!on) {
            return new FriendView(e.userId(), e.name(), "offline", null, null);
        }
        Optional<TableManager.Table> t = tables.mine(e.userId());
        return t.map(table -> new FriendView(e.userId(), e.name(), "table", table.id, table.name))
                .orElseGet(() -> new FriendView(e.userId(), e.name(), "online", null, null));
    }

    private static int statusRank(String status) {
        return switch (status) {
            case "online" -> 0;
            case "table" -> 1;
            case "game" -> 2;
            default -> 3;
        };
    }

    // ------------------------------------------------------------------ Lobby-Chat

    public boolean isIn(long userId) {
        synchronized (lock) {
            Boolean b = chatIn.get(userId);
            if (b != null) {
                return b;
            }
        }
        boolean in = friends.chatIn(userId);
        synchronized (lock) {
            chatIn.put(userId, in);
        }
        return in;
    }

    public void setIn(User me, boolean in) {
        friends.setChatIn(me.id(), in);
        synchronized (lock) {
            chatIn.put(me.id(), in);
        }
    }

    public ChatMsg say(User me, String text) {
        if (!isIn(me.id())) {
            throw new SocialException("Du bist nicht im Lobby-Chat");
        }
        String clean = ChatText.clean(text);
        if (clean == null) {
            throw new SocialException("Leere Nachricht");
        }
        long now = System.currentTimeMillis();
        synchronized (lock) {
            Deque<Long> times = chatTimes.computeIfAbsent(me.id(), k -> new ArrayDeque<>());
            if (!ChatText.allow(times, now)) {
                throw new SocialException("Langsamer – höchstens " + ChatText.RATE_N + " Nachrichten in " + (ChatText.RATE_MS / 1000) + " s");
            }
            ChatMsg m = new ChatMsg(++seq, now, me.id(), me.name(), clean);
            chat.addLast(m);
            while (chat.size() > CHAT_KEEP) {
                chat.pollFirst();
            }
            return m;
        }
    }

    // ------------------------------------------------------------------ Freunde

    /** Anfrage per Name oder ID; @return "outgoing" oder "friend" */
    public String request(User me, String name, Long userId) {
        long other = userId != null ? userId : friends.idByName(name == null ? "" : name);
        return friends.request(me.id(), other);
    }

    public void accept(User me, long other) {
        friends.accept(me.id(), other);
    }

    public void remove(User me, long other) {
        if (!friends.remove(me.id(), other)) {
            throw new SocialException("Keine Freundschaft oder Anfrage");
        }
        synchronized (lock) {
            invites.values().removeIf(i -> (i.fromUserId() == me.id() && i.toUserId() == other)
                    || (i.fromUserId() == other && i.toUserId() == me.id()));
        }
    }

    // ------------------------------------------------------------------ Einladungen

    public Invite invite(User me, String tableId, long friendId) {
        TableManager.Table t = tables.mine(me.id()).orElseThrow(() -> new SocialException("Du sitzt an keinem Tisch"));
        if (!t.id.equalsIgnoreCase(tableId == null ? "" : tableId.strip())) {
            throw new SocialException("Du sitzt nicht an diesem Tisch");
        }
        if (!friends.isFriend(me.id(), friendId)) {
            throw new SocialException("Nur Freunde kannst du einladen");
        }
        if (tables.seated(t.id, friendId)) {
            throw new SocialException("Sitzt schon an deinem Tisch");
        }
        if (!tables.invitable(t.id)) {
            throw new SocialException("Am Tisch ist kein Platz frei");
        }
        synchronized (lock) {
            invites.values().removeIf(i -> i.toUserId() == friendId && i.tableId().equals(t.id));
            Invite inv = new Invite(++inviteSeq, me.id(), me.name(), friendId, t.id, t.name, System.currentTimeMillis());
            invites.put(inv.id(), inv);
            return inv;
        }
    }

    /** Einladung ablehnen (nur der Empfaenger). */
    public void decline(User me, long inviteId) {
        synchronized (lock) {
            Invite i = invites.get(inviteId);
            if (i == null || i.toUserId() != me.id()) {
                throw new SocialException("Diese Einladung gibt es nicht mehr");
            }
            invites.remove(inviteId);
        }
    }

    /** Nach dem Beitritt: alle Einladungen des Nutzers an diesen Tisch erledigt. */
    public void joined(long userId, String tableId) {
        synchronized (lock) {
            invites.values().removeIf(i -> i.toUserId() == userId && i.tableId().equalsIgnoreCase(tableId));
        }
    }
}
