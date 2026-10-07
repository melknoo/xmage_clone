package dev.magelite.view.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Vollstaendiger Spielzustand aus Sicht des menschlichen Spielers.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StateDto {
    public final String t = "state";
    public long seq;
    public int turn;
    public String phase;
    public String step;
    public UUID activePlayerId;
    public UUID priorityPlayerId;
    public UUID myPlayerId;
    /** Spieler in Sitzreihenfolge, beginnend mit mir */
    public List<PlayerDto> players;
    public List<CardDto> hand;
    public List<CardDto> stack;
    public List<CombatDto> combat;
    /** offen gezeigte / angesehene Karten (Name -> Karten) */
    public List<NamedCardsDto> revealed;
    public List<NamedCardsDto> lookedAt;
    /** spielbare Objekte: id -> Anzahl spielbarer Faehigkeiten */
    public Map<UUID, Integer> playable;
    /** Objekte mit Nicht-Mana-Aktionen (Land, Zauber, Faehigkeit) */
    public List<UUID> actions;
    /** Ersatzeffekte, die dieser Spieler fuer dieses Spiel automatisch ablehnt (Kurznamen) */
    public List<String> replDeclines;
    /** Zuschauer-Sicht: hand leer, kein myPlayerId/playable/actions/lookedAt/replDeclines; players[0] = Blickwinkel (einziger me) */
    public Boolean spectator;
}
