package dev.magelite.api;

import com.fasterxml.jackson.databind.JsonNode;
import dev.magelite.game.GameHost;
import dev.magelite.game.TempoSettings;
import mage.constants.ManaType;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Client->Server-Spielnachrichten (Feld {@code t}) auf einen {@link GameHost}-Sitz anwenden. Gemeinsam fuer den
 * WebSocket eines Spielers und das Relay (Host-Link: fly reicht die Nachrichten eines Spielers 1:1 an die Engine des
 * Gastgebers durch).
 */
public final class GameMessages {

    private static final Logger LOG = Logger.getLogger(GameMessages.class);

    private GameMessages() {
    }

    /**
     * @param pong Antwort auf {@code ping} (am Transport vorbei, darf nicht blockieren)
     */
    public static void dispatch(GameHost host, GameHost.HumanSeat seat, JsonNode m, Runnable pong) {
        switch (m.path("t").asText()) {
            case "respond" -> host.respond(seat, m.path("id").asLong(), parseResponse(m));
            case "action" -> host.action(seat, m.path("action").asText(), m.hasNonNull("data") ? m.get("data").asText() : null);
            case "tempo" -> {
                if (seat.isHost()) {
                    host.setTempo(TempoSettings.Preset.valueOf(m.path("preset").asText("NORMAL").toUpperCase(Locale.ROOT)));
                }
            }
            case "autoPass" -> host.setAutoPass(seat, m.path("on").asBoolean(true));
            case "autoPay" -> host.autoPayNow(seat);
            case "combat" -> {
                List<UUID> ids = new ArrayList<>();
                m.path("ids").forEach(n -> ids.add(UUID.fromString(n.asText())));
                UUID target = m.hasNonNull("target") ? UUID.fromString(m.get("target").asText()) : null;
                if (!host.combat(seat, ids, target)) {
                    LOG.info("Mehrfach-Kampf abgelehnt (kein passender Prompt)");
                }
            }
            case "repeat" -> {
                UUID ability = m.hasNonNull("uuid") ? UUID.fromString(m.get("uuid").asText()) : null;
                if (!host.repeat(seat, m.path("id").asLong(), ability, m.path("times").asInt(1))) {
                    LOG.info("Mehrfach-Aktivierung abgelehnt (kein passender Prompt)");
                }
            }
            case "specialPay" -> {
                UUID perm = m.hasNonNull("uuid") ? UUID.fromString(m.get("uuid").asText()) : null;
                if (!host.specialPay(seat, m.path("id").asLong(), perm)) {
                    LOG.info("Einberufen abgelehnt (kein passender Prompt)");
                }
            }
            case "combatReset" -> {
                if (!host.combatReset(seat)) {
                    LOG.info("Angriff zuruecksetzen abgelehnt (kein passender Prompt)");
                }
            }
            case "settings" -> {
                if (m.has("autoPay")) {
                    host.setAutoPayDefault(seat, m.get("autoPay").asBoolean(true));
                }
                if (m.has("autoPass")) {
                    host.setAutoPass(seat, m.get("autoPass").asBoolean(true));
                }
                host.setStops(seat, m.has("stopOppUpkeep") ? m.get("stopOppUpkeep").asBoolean() : null,
                        m.has("stopOnTargeted") ? m.get("stopOnTargeted").asBoolean() : null);
            }
            case "replacement" -> host.replacement(seat, m.path("mode").asText(),
                    m.hasNonNull("key") ? m.get("key").asText() : null, m.path("always").asBoolean(false));
            case "replReset" -> host.resetReplacementDeclines(seat);
            case "chat" -> host.chat(seat, m.path("text").asText(""));
            case "leave" -> host.leave(seat);
            case "kick" -> {
                if (!host.kick(seat, UUID.fromString(m.path("playerId").asText()))) {
                    LOG.info("Aufgeben-lassen abgelehnt (nicht lange genug getrennt oder nicht erlaubt)");
                }
            }
            case "ping" -> pong.run();
            default -> LOG.debug("Unbekannte Nachricht: " + m);
        }
    }

    public static GameHost.Response parseResponse(JsonNode m) {
        if (m.hasNonNull("uuid")) {
            return GameHost.Response.ofUuid(UUID.fromString(m.get("uuid").asText()));
        }
        if (m.hasNonNull("bool")) {
            return GameHost.Response.ofBool(m.get("bool").asBoolean());
        }
        if (m.hasNonNull("int")) {
            return GameHost.Response.ofInt(m.get("int").asInt());
        }
        if (m.hasNonNull("str")) {
            return GameHost.Response.ofString(m.get("str").asText());
        }
        if (m.hasNonNull("mana")) {
            JsonNode mana = m.get("mana");
            UUID pid = mana.hasNonNull("playerId") ? UUID.fromString(mana.get("playerId").asText()) : null;
            return GameHost.Response.ofMana(pid, ManaType.valueOf(mana.path("type").asText().toUpperCase(Locale.ROOT)));
        }
        return GameHost.Response.ofBool(false);
    }
}
