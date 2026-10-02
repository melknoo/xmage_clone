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
    public String set;
    public String num;
    /** XMage-Bilddateiname (Tokens) */
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
    /** z.B. "ability" fuer Stack-Faehigkeiten */
    public String kind;
    public UUID sourceId;
    public UUID controllerId;
}
