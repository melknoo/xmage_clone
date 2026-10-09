package dev.magelite.view.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/**
 * Schlanke Kartenansicht fuer die UI (aus {@code mage.view.CardView}).
 */
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class CardDto {
    public UUID id;
    public String name;
    /** Scryfall-Set (GROSS); bei Tokens das Scryfall-Token-Set (z. B. TC20), siehe {@code ForgeViewMapper.print} */
    public String set;
    /** Sammlernummer; bei Tokens die Nummer im Token-Set (null = nur Namenssuche) */
    public String num;
    /** Token-Name fuer {@code /img/token} (nur Tokens) */
    public String image;
    public int imageNum;
    public String manaCost;
    public int mv;
    public String typeLine;
    public List<String> types;
    public String colors;
    public String power;
    public String toughness;
    public String loyalty;
    public String defense;
    public String rarity;
    public List<String> rules;
    public List<CounterDto> counters;
    public boolean token;
    public boolean faceDown;
    public boolean transformable;
    public boolean transformed;
    public CardDto back;
    /** Ziele (bei Stack-Objekten) */
    public List<UUID> targets;
    /** Ziele mit Namen (nur Stack-Objekte) */
    public List<TargetRefDto> targetRefs;
    /** z.B. "ability" fuer Stack-Faehigkeiten */
    public String kind;
    /** nur Stapel-Faehigkeiten: triggered | activated | static | mana | ... (XMage AbilityType, klein) */
    public String abilityType;
    public UUID sourceId;
    public UUID controllerId;
    /** angesagtes/bezahltes X (nur Stack-Objekte); X = 0 wird mitgesendet */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Integer x;
}
