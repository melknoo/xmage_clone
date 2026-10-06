package dev.magelite.view.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Offene Entscheidung des menschlichen Spielers (aus {@code PlayerQueryEvent}).
 * kind: ASK, SELECT, PICK_TARGET, PICK_ABILITY, CHOOSE_ABILITY, CHOOSE_MODE, CHOOSE_CHOICE,
 * PLAY_MANA, PLAY_X_MANA, AMOUNT, MULTI_AMOUNT, CHOOSE_PILE.
 */
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class PromptDto {
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public final String t = "prompt";
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public long id;
    public long stateSeq;
    public String kind;
    public UUID playerId;
    public List<Map<String, Object>> message;
    public String messageText;
    public List<Map<String, Object>> secondMessage;
    public boolean required;
    public String leftBtn;
    public String rightBtn;
    public String specialBtn;
    /** PLAY_MANA: Kreaturen, die per Klick eingeberufen werden koennen (Convoke, {@code GameHost.specialPay}) */
    public List<UUID> specialTargets;

    /** SELECT: priority | attackers | blockers */
    public String mode;
    /** SELECT/priority im eigenen Zug bei leerem Stapel: wohin "Weiter" fuehrt (main1 | combat | main2 | end) */
    public String nextStop;
    public List<UUID> possibleAttackers;
    public List<UUID> possibleBlockers;

    /** ASK */
    public boolean mulligan;
    public String autoAnswer;

    /** PICK_TARGET */
    public List<UUID> targets;
    public List<UUID> chosen;
    public List<CardDto> cards;
    public boolean defenderPick;

    /** PICK_ABILITY / CHOOSE_ABILITY / CHOOSE_MODE */
    public List<Item> choices;
    /** CHOOSE_ABILITY: das Objekt, dessen Faehigkeiten zur Wahl stehen (fuer "N-mal aktivieren") */
    public UUID sourceId;

    /** CHOOSE_CHOICE */
    public ChoiceDto choice;

    /** AMOUNT / MULTI_AMOUNT */
    // immer senden: max = 0 ist ein gueltiger Wert (UI faellt sonst auf "unbegrenzt" zurueck)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public int min;
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public int max;
    public List<AmountItem> items;

    /** CHOOSE_PILE */
    public List<CardDto> pile1;
    public List<CardDto> pile2;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String id, String text, UUID sourceId, String set, String num) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AmountItem(String message, int min, int max, int value) {
    }

    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public static class ChoiceDto {
        public String message;
        public String subMessage;
        public boolean required;
        public boolean keyed;
        public boolean search;
        public boolean manaColor;
        /** XMage ChoiceHintType: "card" (Kartennamen, Vorschau per Name moeglich), "text", "game_object", "card_dungeon" */
        public String hint;
        /** key -> Anzeige (bei nicht-keyed: value -> value) */
        public List<ChoiceItem> items;
        public String specialText;
        /** nur bei der Ersatzeffekt-Wahl: Items nach Regeltext gruppiert */
        public List<ReplGroup> groups;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChoiceItem(String key, String value, Integer sort, List<String> hints) {
    }

    /** Gleiche Ersatzeffekte; {@code optional} = Regel enthaelt "you may" (Effekt fragt selbst nach). */
    public record ReplGroup(String rule, String label, boolean optional, List<ReplSource> sources) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReplSource(String key, String name, UUID objectId) {
    }
}
