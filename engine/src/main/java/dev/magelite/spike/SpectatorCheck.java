package dev.magelite.spike;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.magelite.game.GameHost;
import dev.magelite.view.GameViewMapper;
import dev.magelite.view.dto.CardDto;
import dev.magelite.view.dto.CommandDto;
import dev.magelite.view.dto.Messages;
import dev.magelite.view.dto.NamedCardsDto;
import dev.magelite.view.dto.PlayerDto;
import dev.magelite.view.dto.PromptDto;
import dev.magelite.view.dto.StateDto;
import mage.cards.Card;
import mage.game.Game;
import mage.players.Player;
import mage.util.ThreadUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HumanSpike {@code --spectate}: ein In-Process-Zuschauer, der jede Nachricht auf Lecks prueft.
 * <ul>
 *   <li>States: Hand leer; kein playable/actions/lookedAt/replDeclines/myPlayerId; genau players[0].me (= viewpointId);
 *       kein topCardPrivate; verdeckte Karten ohne Name/Set/Nummer/Rueckseite.</li>
 *   <li>Keine id aus einer Hand (alle Spieler, direkt aus dem Spiel gelesen), Bibliothek oder privatem Sitz-Inhalt
 *       (Hand, lookedAt, private oberste Karte im State eines Sitzes mit gleicher seq) ausserhalb von {@code revealed},
 *       verdeckten Zielen, aufgedeckten obersten Karten und Quellen von Stapel-Faehigkeiten.</li>
 *   <li>Nie: prompt, promptClosed, seat, toast, Ereignisse mit hidden.</li>
 * </ul>
 * Die Sitz-Sinks melden ihre States synchron ({@link #seatState}); der oeffentliche State derselben seq kommt danach auf
 * dem Game-Thread, dort wird auch der Spielzustand direkt gelesen.
 */
final class SpectatorCheck implements GameHost.Sink {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern UUID_RE = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Set<String> HIDDEN_REF_NAMES = Set.of("verdeckte Karte", "verdecktes Permanent", GameViewMapper.FACE_DOWN_SPELL);
    private static final int MAX_ERRORS = 30;

    private final Game game;
    private final Map<Long, Set<UUID>> seatPrivate = new ConcurrentHashMap<>();
    final List<String> errors = new ArrayList<>();
    int errorCount;
    int states;
    int deepChecked;
    int events;
    int logs;
    int chats;
    int faceDownSeen;
    int hiddenTargetRefs;
    boolean hello;
    UUID viewpointId;
    Messages.GameOver over;

    SpectatorCheck(Game game) {
        this.game = game;
    }

    /** Sitz-State (synchron auf dem Thread, der ihn sendet): private ids dieser seq merken. */
    void seatState(StateDto st) {
        Set<UUID> ids = new HashSet<>();
        if (st.hand != null) {
            st.hand.forEach(c -> ids.add(c.id));
        }
        if (st.lookedAt != null) {
            for (NamedCardsDto n : st.lookedAt) {
                n.cards().forEach(c -> ids.add(c.id));
            }
        }
        for (PlayerDto p : st.players) {
            if (p.topCardPrivate && p.topCard != null) {
                ids.add(p.topCard.id);
            }
        }
        seatPrivate.merge(st.seq, ids, (a, b) -> {
            Set<UUID> u = new HashSet<>(a);
            u.addAll(b);
            return u;
        });
    }

    @Override
    public synchronized void send(Object msg) {
        try {
            check(msg);
        } catch (Throwable e) {
            fail("Pruefung warf " + e);
        }
    }

    private void check(Object msg) throws Exception {
        if (msg instanceof Messages.Hello h) {
            hello = true;
            viewpointId = h.viewpointId();
            if (!Boolean.TRUE.equals(h.spectator()) || h.myPlayerId() != null || h.host() || h.viewpointId() == null) {
                fail("hello: spectator=" + h.spectator() + " myPlayerId=" + h.myPlayerId() + " host=" + h.host() + " viewpoint=" + h.viewpointId());
            }
        } else if (msg instanceof StateDto s) {
            checkState(s);
        } else if (msg instanceof PromptDto || msg instanceof Messages.PromptClosed || msg instanceof Messages.SeatStatus
                || msg instanceof Messages.Toast) {
            fail("persoenliche Nachricht an Zuschauer: " + msg.getClass().getSimpleName());
        } else if (msg instanceof Messages.Events ev) {
            for (Messages.FxEvent e : ev.items()) {
                events++;
                if (Boolean.TRUE.equals(e.hidden())) {
                    fail("verdecktes Ereignis an Zuschauer: " + e.kind() + " " + e.from() + "->" + e.to());
                }
            }
        } else if (msg instanceof Messages.Activity a && "you".equals(a.mode())) {
            fail("Activity 'you' an Zuschauer");
        } else if (msg instanceof Messages.Log) {
            logs++;
        } else if (msg instanceof Messages.Chat) {
            chats++;
        } else if (msg instanceof Messages.GameOver g) {
            over = g;
            if (g.reward() != null) {
                fail("gameOver mit Belohnung an Zuschauer");
            }
        }
    }

    private void checkState(StateDto s) throws Exception {
        states++;
        if (!hello) {
            fail("State vor hello");
        }
        if (!Boolean.TRUE.equals(s.spectator)) {
            fail("state.spectator fehlt (seq " + s.seq + ")");
        }
        if (s.myPlayerId != null || s.playable != null || s.actions != null || s.lookedAt != null || s.replDeclines != null) {
            fail("privates Feld im Zuschauer-State (seq " + s.seq + "): myPlayerId=" + s.myPlayerId + " playable=" + (s.playable != null)
                    + " actions=" + (s.actions != null) + " lookedAt=" + (s.lookedAt != null) + " replDeclines=" + (s.replDeclines != null));
        }
        if (s.hand == null || !s.hand.isEmpty()) {
            fail("Hand im Zuschauer-State nicht leer (seq " + s.seq + ")");
        }
        long me = s.players.stream().filter(p -> p.me).count();
        if (me != 1 || s.players.isEmpty() || !s.players.get(0).me || (viewpointId != null && !s.players.get(0).id.equals(viewpointId))) {
            fail("me-Flag: " + me + "x, players[0].me=" + (!s.players.isEmpty() && s.players.get(0).me));
        }
        for (PlayerDto p : s.players) {
            if (p.topCardPrivate) {
                fail("topCardPrivate bei " + p.name);
            }
            p.graveyard.forEach(this::faceDown);
            p.exile.forEach(this::faceDown);
            p.battlefield.forEach(this::faceDown);
            if (p.topCard != null) {
                faceDown(p.topCard);
            }
            for (CommandDto c : p.command) {
                if (c.card != null) {
                    faceDown(c.card);
                }
            }
        }
        s.stack.forEach(this::faceDown);

        // ids ausserhalb der erlaubten Stellen
        ObjectNode tree = JSON.valueToTree(s);
        tree.remove("revealed");
        Set<UUID> allowed = new HashSet<>();
        for (CardDto c : s.stack) {
            if (c.targetRefs != null) {
                c.targetRefs.stream().filter(t -> HIDDEN_REF_NAMES.contains(t.name)).forEach(t -> {
                    allowed.add(t.id);
                    hiddenTargetRefs++;
                });
            }
            if ("ability".equals(c.kind) && c.sourceId != null) {
                allowed.add(c.sourceId); // Aktivieren/Ausloesen aus der Hand zeigt die Karte (Ninjutsu, Zyklus-Trigger)
            }
        }
        for (PlayerDto p : s.players) {
            if (p.topCard != null) {
                allowed.add(p.topCard.id); // aufgedeckte oberste Karte (PlayerView.getTopCard)
            }
        }
        if (s.revealed != null) {
            s.revealed.forEach(n -> n.cards().forEach(c -> allowed.add(c.id)));
        }
        Set<UUID> present = new HashSet<>();
        Matcher m = UUID_RE.matcher(JSON.writeValueAsString(tree));
        while (m.find()) {
            present.add(UUID.fromString(m.group()));
        }
        present.removeAll(allowed);

        Set<UUID> secret = new HashSet<>();
        Set<UUID> seat = seatPrivate.remove(s.seq);
        if (seat != null) {
            secret.addAll(seat);
        }
        for (Iterator<Long> it = seatPrivate.keySet().iterator(); it.hasNext(); ) {
            if (it.next() < s.seq) {
                it.remove();
            }
        }
        if (ThreadUtils.isRunGameThread()) {
            // direkt aus dem Spiel: alle Haende (auch Bots) und Bibliotheken
            deepChecked++;
            for (Player pl : game.getState().getPlayers().values()) {
                for (Card c : pl.getHand().getCards(game)) {
                    secret.add(c.getId());
                }
                for (Card c : pl.getLibrary().getCards(game)) {
                    secret.add(c.getId());
                }
            }
        }
        secret.retainAll(present);
        if (!secret.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (UUID id : secret) {
                Card c = ThreadUtils.isRunGameThread() ? game.getCard(id) : null;
                names.add(c == null ? id.toString() : c.getName() + " (" + game.getState().getZone(id) + ")");
            }
            fail("verdeckte ids im Zuschauer-State (seq " + s.seq + "): " + names + " in " + where(tree, secret));
        }
    }

    /** Pfade im JSON, an denen die ids vorkommen (Diagnose). */
    private static String where(JsonNode node, Set<UUID> ids) {
        List<String> out = new ArrayList<>();
        walk(node, "", ids, out);
        return out.size() > 5 ? out.subList(0, 5) + "..." : out.toString();
    }

    private static void walk(JsonNode n, String path, Set<UUID> ids, List<String> out) {
        if (n.isObject()) {
            n.fields().forEachRemaining(e -> walk(e.getValue(), path + "." + e.getKey(), ids, out));
        } else if (n.isArray()) {
            ArrayNode a = (ArrayNode) n;
            for (int i = 0; i < a.size(); i++) {
                walk(a.get(i), path + "[" + i + "]", ids, out);
            }
        } else if (n.isTextual()) {
            String t = n.asText();
            for (UUID id : ids) {
                if (t.contains(id.toString())) {
                    out.add(path);
                }
            }
        }
    }

    private void faceDown(CardDto c) {
        if (!c.faceDown) {
            return;
        }
        faceDownSeen++;
        if ((c.name != null && !c.name.isEmpty()) || c.set != null || c.num != null || c.back != null || c.image != null) {
            fail("verdeckte Karte mit Infos: name=" + c.name + " set=" + c.set + " num=" + c.num + " back=" + (c.back != null));
        }
    }

    private void fail(String msg) {
        errorCount++;
        if (errors.size() < MAX_ERRORS) {
            errors.add(msg);
        }
    }

    boolean ok() {
        return errorCount == 0 && hello && states > 0;
    }

    String summary() {
        return String.format("hello=%s States=%d (davon gegen Spiel geprueft %d) Ereignisse=%d Log=%d Chat=%d verdeckt=%d verdeckteZiele=%d gameOver=%s Fehler=%d",
                hello, states, deepChecked, events, logs, chats, faceDownSeen, hiddenTargetRefs,
                over == null ? "FEHLT" : over.result(), errorCount);
    }
}
