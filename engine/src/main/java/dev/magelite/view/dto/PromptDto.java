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

    /** SELECT: priority | attackers | blockers */
    public String mode;
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

    /** CHOOSE_CHOICE */
    public ChoiceDto choice;

    /** AMOUNT / MULTI_AMOUNT */
    public int min;
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
        /** key -> Anzeige (bei nicht-keyed: value -> value) */
        public List<ChoiceItem> items;
        public String specialText;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChoiceItem(String key, String value, Integer sort, List<String> hints) {
    }
}
