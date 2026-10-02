package dev.magelite.view.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/**
 * Objekt in der Command-Zone (Commander, Emblem, ...).
 */
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class CommandDto {
    public UUID id;
    /** commander | emblem | plane | dungeon | other */
    public String kind;
    public String name;
    public String set;
    public String num;
    public String image;
    public int imageNum;
    public List<String> rules;
    public CardDto card;
    /** Anzahl bisheriger Casts aus der Command-Zone */
    public int casts;
    /** Commander-Steuer (2 x casts) */
    public int tax;
}
