package dev.magelite.social;

import com.fasterxml.jackson.annotation.JsonInclude;
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
 * Sperr-Reihenfolge (verbindlich): 1. eigenen Zustand unter {@code lock} kopieren; 2. {@link TableManager},
 * {@link GameRegistry} und {@link FriendStore} (DB) AUSSERHALB von {@code lock} aufrufen; 3. erneut unter {@code lock}
 * zusammensetzen. Nie unter {@code lock} in andere Dienste rufen. TableManager ruft unter seiner Sperre nie hierher
 * (Rueckrufe wie {@link #kicked} laufen nach dem Freigeben).
 */
public final class SocialService {

    /** Fehlbedienung (409). */
    public static final class SocialException extends RuntimeException {
        public SocialException(String message) {
            super(message);
        }
    }

    /** Lobby-Chat-Zeile; {@code sys} = Systemzeile (userId 0, Text vollstaendig, ohne Namensanzeige). */
    public record ChatMsg(long seq, long ts, long userId, String name, String text,
                          @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean sys) {
    }

    public record Member(long id, String name) {
    }

    /** Gespeicherte Einladung; {@code expiresAt} = ts + 10 min. */
    public record Invite(long id, long fromUserId, String fromName, long toUserId, String tableId, String tableName, long ts,
                         long expiresAt) {
    }

    /** Einladung an mich, angereichert mit dem aktuellen Tisch-Stand. */
    public record InviteView(long id, long fromUserId, String fromName, long toUserId, String tableId, String tableName, long ts,
                             long expiresAt, int humans, String tempo) {
    }

    /** Von mir verschickte, noch gueltige Einladung. */
    public record SentInvite(long id, long toUserId, String toName, String tableId, long expiresAt) {
    }

    /** Mein Tisch (fuer Home/Lobby-Leiste). {@code host} = ich bin Gastgeber. */
    public record MyTable(String id, String name, String tempo, String state, int humans, boolean host, boolean invitable) {
    }

    public record FriendView(long id, String name, String status, String tableId, String tableName) {
    }

    /**
     * Poll-Antwort. {@code now} = Serverzeit (ms, fuer den Uhrversatz), {@code online} = sichtbare Online-Mitglieder
     * (wie {@code members}), {@code tables} = Anzahl Tische, {@code myTable} oder null, {@code sent} = meine offenen
     * Einladungen.
     */
    public record Snapshot(boolean chatIn, long seq, List<ChatMsg> msgs, List<Member> members, List<FriendView> friends,
                           List<FriendStore.Entry> incoming, List<FriendStore.Entry> outgoing, List<InviteView> invites,
                           long now, int online, int tables, MyTable myTable, List<SentInvite> sent) {
    }

    static final int CHAT_KEEP = 100;
    static final long ONLINE_MS = 15_000;
    static final long INVITE_MS = 10 * 60_000L;
    /** Systemzeile "ist beigetreten" hoechstens so oft je Nutzer */
    static final long SYS_LINE_MS = 10 * 60_000L;

    private record Seen(String name, long ts) {
    }

    private final FriendStore friends;
    private final TableManager tables;
    private final GameRegistry games;

    private final Object lock = new Object();
    private final Deque<ChatMsg> chat = new ArrayDeque<>();
    private final Map<Long, Deque<Long>> chatTimes = new HashMap<>();
    private final Map<Long, Long> sysLineAt = new HashMap<>();
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

        // 1. eigenen Zustand kopieren
        List<Invite> toMe;
        List<Invite> fromMe;
        List<Long> online = new ArrayList<>();
        synchronized (lock) {
            seen.put(me.id(), new Seen(me.name(), now));
            invites.values().removeIf(i -> now > i.expiresAt());
            toMe = invites.values().stream().filter(i -> i.toUserId() == me.id()).toList();
            fromMe = invites.values().stream().filter(i -> i.fromUserId() == me.id()).toList();
            seen.values().removeIf(s -> now - s.ts() > ONLINE_MS * 4);
            seen.forEach((id, s) -> {
                if (now - s.ts() <= ONLINE_MS) {
                    online.add(id);
                }
            });
        }

        // 2. andere Dienste ausserhalb der Sperre
        List<FriendView> friendViews = new ArrayList<>();
        List<FriendStore.Entry> incoming = new ArrayList<>();
        List<FriendStore.Entry> outgoing = new ArrayList<>();
        Map<Long, String> names = new HashMap<>();
        for (FriendStore.Entry e : friends.list(me.id())) {
            names.put(e.userId(), e.name());
            switch (e.state()) {
                case "friend" -> friendViews.add(friendView(e, now));
                case "incoming" -> incoming.add(e);
                default -> outgoing.add(e);
            }
        }
        friendViews.sort(Comparator.comparingInt((FriendView f) -> statusRank(f.status())).thenComparing(f -> f.name().toLowerCase()));

        Map<String, Optional<TableManager.TableSnap>> snaps = new HashMap<>();
        List<InviteView> valid = new ArrayList<>();
        for (Invite i : toMe) {
            Optional<TableManager.TableSnap> t = snaps.computeIfAbsent(i.tableId(), id -> tables.snapshot(id, me.id()));
            if (t.isPresent() && t.get().invitable() && t.get().seatIndexOf(me.id()) < 0) {
                TableManager.TableSnap s = t.get();
                valid.add(new InviteView(i.id(), i.fromUserId(), i.fromName(), i.toUserId(), i.tableId(), s.name(), i.ts(),
                        i.expiresAt(), s.humans(), s.tempo().name()));
            }
        }
        List<SentInvite> sent = new ArrayList<>();
        for (Invite i : fromMe) {
            Optional<TableManager.TableSnap> t = snaps.computeIfAbsent(i.tableId(), id -> tables.snapshot(id, me.id()));
            if (t.isPresent() && t.get().invitable() && t.get().seatIndexOf(i.toUserId()) < 0) {
                sent.add(new SentInvite(i.id(), i.toUserId(), names.get(i.toUserId()), i.tableId(), i.expiresAt()));
            }
        }
        MyTable myTable = tables.mineSnapshot(me.id())
                .map(t -> new MyTable(t.id(), t.name(), t.tempo().name(), t.state(), t.humans(), t.hostUserId() == me.id(), t.invitable()))
                .orElse(null);
        int tableCount = tables.count();

        List<Long> visible = new ArrayList<>();
        for (long id : online) {
            if (isIn(id)) {
                visible.add(id);
            }
        }

        // 3. zusammensetzen
        synchronized (lock) {
            List<Member> members = new ArrayList<>();
            for (long id : visible) {
                Seen s = seen.get(id);
                if (s != null) {
                    members.add(new Member(id, s.name()));
                }
            }
            members.sort(Comparator.comparing(m -> m.name().toLowerCase()));
            List<ChatMsg> msgs = !in ? List.of() : chat.stream().filter(m -> m.seq() > after).toList();
            return new Snapshot(in, seq, msgs, members, friendViews, incoming, outgoing, valid,
                    now, members.size(), tableCount, myTable, sent);
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
        Optional<TableManager.TableSnap> t = tables.mineSnapshot(e.userId());
        return t.map(table -> new FriendView(e.userId(), e.name(), "table", table.id(), table.name()))
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

    /** Lobby-Chat betreten/verlassen; beim echten Wechsel aus → an eine Systemzeile (je Nutzer begrenzt). */
    public void setIn(User me, boolean in) {
        boolean before = isIn(me.id());
        friends.setChatIn(me.id(), in);
        long now = System.currentTimeMillis();
        synchronized (lock) {
            chatIn.put(me.id(), in);
            if (in && !before) {
                Long last = sysLineAt.get(me.id());
                if (last == null || now - last >= SYS_LINE_MS) {
                    sysLineAt.put(me.id(), now);
                    append(new ChatMsg(++seq, now, 0, me.name(), me.name() + " ist dem Lobby-Chat beigetreten", true));
                }
            }
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
            ChatMsg m = new ChatMsg(++seq, now, me.id(), me.name(), clean, false);
            append(m);
            return m;
        }
    }

    /** unter {@code lock} */
    private void append(ChatMsg m) {
        chat.addLast(m);
        while (chat.size() > CHAT_KEEP) {
            chat.pollFirst();
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
        TableManager.TableSnap t = tables.mineSnapshot(me.id()).orElseThrow(() -> new SocialException("Du sitzt an keinem Tisch"));
        if (!t.id().equalsIgnoreCase(tableId == null ? "" : tableId.strip())) {
            throw new SocialException("Du sitzt nicht an diesem Tisch");
        }
        if (!friends.isFriend(me.id(), friendId)) {
            throw new SocialException("Nur Freunde kannst du einladen");
        }
        if (t.seatIndexOf(friendId) >= 0) {
            throw new SocialException("Sitzt schon an deinem Tisch");
        }
        if (!t.invitable()) {
            throw new SocialException("Am Tisch ist kein Platz frei");
        }
        boolean host = t.hostUserId() == me.id();
        if (host) {
            tables.unkick(t.id(), me.id(), friendId); // erneute Einladung durch den Gastgeber hebt das Entfernen auf
        } else if (tables.isKicked(t.id(), friendId)) {
            throw new SocialException("Der Gastgeber hat diese Person vom Tisch entfernt");
        }
        long now = System.currentTimeMillis();
        synchronized (lock) {
            invites.values().removeIf(i -> i.toUserId() == friendId && i.tableId().equals(t.id()));
            Invite inv = new Invite(++inviteSeq, me.id(), me.name(), friendId, t.id(), t.name(), now, now + INVITE_MS);
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

    /** Nach dem Entfernen vom Tisch (Rueckruf ausserhalb der TableManager-Sperre): Einladungen von und an ihn verwerfen. */
    public void kicked(long userId, String tableId) {
        synchronized (lock) {
            invites.values().removeIf(i -> i.tableId().equalsIgnoreCase(tableId)
                    && (i.fromUserId() == userId || i.toUserId() == userId));
        }
    }
}
