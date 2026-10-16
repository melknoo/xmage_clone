package dev.magelite.account;

import com.fasterxml.jackson.databind.JsonNode;
import dev.magelite.api.HttpServer;
import dev.magelite.api.Json;
import dev.magelite.deck.DeckStore;
import dev.magelite.relay.HostLinkClient;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.apache.log4j.Logger;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Konto-Modus der Desktop-App (lokale Engine mit {@code --account}, Token-Auth wie alle {@code /api/*}):
 * <ul>
 *   <li>{@code GET /api/account} - Zustand ({@link AccountLink.Status}, plus wartende Spielberichte)</li>
 *   <li>{@code POST /api/account/sync {server, session|null, offline?}} - von Electron (Start, Login, Abmelden)</li>
 *   <li>{@code POST /api/account/retry}, {@code POST /api/account/offline} - Gate "Erneut versuchen" / "Offline spielen"</li>
 *   <li>{@code POST /api/account/wake} - Host-Link verbinden (Lobby wird geoeffnet; sonst ruht er)</li>
 *   <li>{@code GET /api/account/local-decks}, {@code POST /api/account/import-local {ids?}},
 *       {@code POST /api/account/import-local/dismiss} - lokale Decks einmalig ins Konto uebernehmen</li>
 * </ul>
 */
public final class AccountRoutes implements HttpServer.Module {

    private static final Logger LOG = Logger.getLogger(AccountRoutes.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(25);
    /** lokale Decks gehoeren immer Nutzer 1 */
    private static final long LOCAL_USER = 1;

    private final AccountLink link;
    private final AccountStore store;
    private final AccountReporter reporter;
    private final DeckStore decks;
    private final HostLinkClient hostLink;

    public AccountRoutes(AccountLink link, AccountStore store, AccountReporter reporter, DeckStore decks, HostLinkClient hostLink) {
        this.link = link;
        this.store = store;
        this.reporter = reporter;
        this.decks = decks;
        this.hostLink = hostLink;
    }

    @Override
    public void register(Javalin app) {
        app.exception(AccountGames.Blocked.class, (e, ctx) -> AccountProxy.blocked(ctx, e.state));

        app.get("/api/account", ctx -> ctx.json(status()));
        app.post("/api/account/sync", ctx -> {
            JsonNode b = Json.MAPPER.readTree(ctx.body());
            link.sync(b.path("server").asText(null), b.hasNonNull("session") ? b.get("session").asText() : null,
                    b.path("offline").asBoolean(false));
            ctx.json(status());
        });
        app.post("/api/account/retry", ctx -> {
            link.retry();
            ctx.json(status());
        });
        app.post("/api/account/offline", ctx -> {
            link.goOffline();
            ctx.json(status());
        });
        app.post("/api/account/wake", ctx -> {
            if (link.active()) {
                hostLink.wake();
            }
            ctx.json(Map.of("ok", true));
        });
        app.get("/api/account/local-decks", ctx -> ctx.json(localDecks()));
        app.post("/api/account/import-local", this::importLocal);
        app.post("/api/account/import-local/dismiss", ctx -> {
            AccountLink.Account a = requireAccount();
            store.put(AccountStore.scope(link.server(), a.id()) + "importDismissed", "1");
            ctx.json(localDecks());
        });
    }

    private Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        AccountLink.Status st = link.status();
        m.put("state", st.state());
        m.put("server", st.server());
        m.put("user", st.user());
        m.put("error", st.error());
        m.put("resetsAt", st.resetsAt());
        AccountLink.Account last = link.lastUser();
        m.put("queued", last == null || link.server() == null ? 0 : reporter.queued(link.server(), last.id()));
        return m;
    }

    private AccountLink.Account requireAccount() {
        AccountLink.Account a = link.user();
        if (a == null) {
            throw new AccountGames.Blocked(link.state());
        }
        return a;
    }

    private Set<Long> imported(AccountLink.Account a) {
        Set<Long> ids = new LinkedHashSet<>();
        store.get(AccountStore.scope(link.server(), a.id()) + "imported").ifPresent(json -> {
            try {
                Json.MAPPER.readTree(json).forEach(n -> ids.add(n.asLong()));
            } catch (Exception e) {
                LOG.warn("Import-Merker unlesbar: " + e);
            }
        });
        return ids;
    }

    /** Lokale Decks, die noch nicht im Konto sind ({@code dismissed}: Karte ausgeblendet). */
    private Map<String, Object> localDecks() {
        AccountLink.Account a = requireAccount();
        Set<Long> done = imported(a);
        List<Map<String, Object>> pending = new ArrayList<>();
        for (DeckStore.StoredDeck d : decks.list(LOCAL_USER)) {
            if (done.contains(d.id())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.id());
            m.put("name", d.name());
            m.put("commanders", d.commanders());
            m.put("colors", d.colors());
            m.put("commanderSet", d.commanderSet());
            m.put("commanderNum", d.commanderNum());
            pending.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("decks", pending);
        out.put("dismissed", store.get(AccountStore.scope(link.server(), a.id()) + "importDismissed").isPresent());
        return out;
    }

    /** Lokale Decks als Text v2 + Ordner/Bracket an {@code POST /api/decks} des Kontos. */
    private void importLocal(Context ctx) throws Exception {
        AccountLink.Account a = requireAccount();
        JsonNode b = ctx.body().isBlank() ? Json.MAPPER.createObjectNode() : Json.MAPPER.readTree(ctx.body());
        Set<Long> done = imported(a);
        List<Long> ids = new ArrayList<>();
        if (b.path("ids").isArray()) {
            b.path("ids").forEach(n -> ids.add(n.asLong()));
        } else {
            decks.list(LOCAL_USER).forEach(d -> ids.add(d.id()));
        }
        int ok = 0;
        List<Map<String, Object>> failed = new ArrayList<>();
        String stop = null;
        for (long id : ids) {
            if (done.contains(id)) {
                continue;
            }
            DeckStore.StoredDeck d = decks.get(LOCAL_USER, id).orElse(null);
            String text = d == null ? null : decks.getDck(LOCAL_USER, id).orElse(null);
            if (d == null || text == null) {
                continue;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("text", text);
            body.put("name", d.name());
            body.put("commanders", d.commanders());
            body.put("folder", d.folder() == null ? "" : d.folder());
            if (d.bracket() != null) {
                body.put("bracket", d.bracket());
            }
            body.put("source", d.source() == null ? "text" : d.source());
            if (d.sourceUrl() != null) {
                body.put("sourceUrl", d.sourceUrl());
            }
            HttpResponse<String> res;
            try {
                res = link.call("POST", "/api/decks", Json.write(body), false, TIMEOUT);
            } catch (Exception e) {
                stop = "Server nicht erreichbar";
                break;
            }
            if (res.statusCode() == 200) {
                done.add(id);
                ok++;
            } else if (res.statusCode() == 401) {
                link.expired();
                stop = "Anmeldung abgelaufen";
                break;
            } else {
                String err;
                try {
                    err = Json.MAPPER.readTree(res.body()).path("error").asText("HTTP " + res.statusCode());
                } catch (Exception e) {
                    err = "HTTP " + res.statusCode();
                }
                failed.add(Map.of("id", id, "name", d.name(), "error", err));
            }
        }
        store.put(AccountStore.scope(link.server(), a.id()) + "imported", Json.write(done));
        LOG.info("Lokale Decks ins Konto " + a.id() + " uebernommen: " + ok + (failed.isEmpty() ? "" : ", Fehler: " + failed.size()));
        Map<String, Object> out = new LinkedHashMap<>(localDecks());
        out.put("imported", ok);
        out.put("failed", failed);
        if (stop != null) {
            out.put("error", stop);
        }
        ctx.json(out);
    }
}
