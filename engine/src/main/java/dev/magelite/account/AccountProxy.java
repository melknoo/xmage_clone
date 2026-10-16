package dev.magelite.account;

import com.fasterxml.jackson.databind.JsonNode;
import dev.magelite.api.AccountGate;
import dev.magelite.api.Json;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Durchreichen der Nutzerdaten-Routen an den Online-Server, solange die Desktop-App angemeldet ist
 * ({@link AccountLink.State#OK}). Weitergegeben werden nur Methode, Pfad, Query (ohne das lokale {@code token}),
 * Body und {@code Content-Type} - plus die Session als Cookie. Antworten gehen 1:1 zurueck, ausser:
 * <ul>
 *   <li>401 vom Server: Session ungueltig -> {@code 401 {account:"EXPIRED"}}, Konto gilt als abgemeldet</li>
 *   <li>503 mit {@code budget}: oeffentliches Konto ohne Budget -> {@code 503 {account:"LIMITED"}}</li>
 *   <li>Server weg (Netz, 502/504): {@code 503 {account:"UNREACHABLE"}} - nie still auf lokale Daten ausweichen</li>
 * </ul>
 * Lokale Daten nur ohne Konto-Modus bzw. offline; in den uebrigen Zustaenden (pruefen, abgemeldet ...) {@code 409}.
 */
public final class AccountProxy implements AccountGate {

    private static final Logger LOG = Logger.getLogger(AccountProxy.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(25);

    private final AccountLink link;

    public AccountProxy(AccountLink link) {
        this.link = link;
    }

    @Override
    public Handler wrap(Handler local) {
        return ctx -> {
            if (link.local()) {
                local.handle(ctx);
            } else if (link.active()) {
                forward(ctx);
            } else {
                blocked(ctx, link.state());
            }
        };
    }

    /** Antwort, wenn Kontodaten gerade nicht verfuegbar sind (Gate der App zeigt den Grund). */
    public static void blocked(Context ctx, AccountLink.State state) {
        String msg = switch (state) {
            case CHECKING -> "Konto wird noch geprüft";
            case LIMITED -> "Das Server-Kontingent für diesen Monat ist aufgebraucht";
            case UNREACHABLE -> "Server nicht erreichbar";
            default -> "Nicht angemeldet";
        };
        ctx.status(409).json(Map.of("error", msg, "account", state.name()));
    }

    private void forward(Context ctx) {
        String path = ctx.path() + query(ctx.queryString());
        byte[] body = ctx.bodyAsBytes();
        String method = ctx.method().name();
        HttpResponse<byte[]> res;
        try {
            res = ServerCalls.send(link.server(), link.session(), method, path, body.length == 0 ? null : body,
                    ctx.contentType(), false, TIMEOUT);
        } catch (IOException e) {
            LOG.warn("Konto-Daten " + method + " " + ctx.path() + ": " + e);
            unreachable(ctx);
            return;
        }
        int status = res.statusCode();
        String type = res.headers().firstValue("Content-Type").orElse("");
        boolean json = type.contains("json");
        if (status == 401) {
            link.expired();
            ctx.status(401).json(Map.of("error", "Anmeldung abgelaufen – bitte neu anmelden", "account", "EXPIRED"));
            return;
        }
        if (status == 503 && json) {
            JsonNode b = parse(res.body());
            if (b != null && b.path("budget").asBoolean(false)) {
                link.limited(null);
                ctx.status(503).json(Map.of("error", b.path("error").asText("Server-Kontingent aufgebraucht"), "account", "LIMITED"));
                return;
            }
        }
        if ((status == 502 || status == 503 || status == 504) && !json) {
            unreachable(ctx); // fly-Proxy (Maschine startet/weg) liefert HTML
            return;
        }
        ctx.status(status);
        if (!type.isEmpty()) {
            ctx.contentType(type);
        }
        ctx.result(res.body());
    }

    private static void unreachable(Context ctx) {
        ctx.status(503).json(Map.of("error", "Server nicht erreichbar – später nochmal versuchen", "account", "UNREACHABLE"));
    }

    private static JsonNode parse(byte[] body) {
        try {
            return Json.MAPPER.readTree(body);
        } catch (IOException e) {
            return null;
        }
    }

    /** Query ohne das lokale Zugriffs-Token ({@code token}); leer -> "". */
    static String query(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        List<String> keep = new ArrayList<>();
        for (String part : raw.split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            int eq = part.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8);
            if (!key.equals("token")) {
                keep.add(part);
            }
        }
        return keep.isEmpty() ? "" : "?" + String.join("&", keep);
    }
}
