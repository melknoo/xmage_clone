package dev.magelite.view.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class PlayerDto {
    public UUID id;
    public String name;
    public boolean me;
    public boolean human;
    public int life;
    public List<CounterDto> counters;
    public int library;
    public int handCount;
    public List<CardDto> graveyard;
    public List<CardDto> exile;
    public Map<String, Integer> mana;
    public List<CommandDto> command;
    public List<PermanentDto> battlefield;
    public boolean active;
    public boolean priority;
    public boolean lost;
    public boolean won;
    public boolean monarch;
    public boolean initiative;
    /** erhaltener Commander-Schaden: commanderName -> Schaden */
    public Map<String, Integer> commanderDamage;
    /** aktive Skip-Flags (F-Tasten) */
    public List<String> skips;
    /** Bot denkt gerade */
    public boolean thinking;
    public String deckName;
    /** oberste Bibliothekskarte, falls sichtbar (aufgedeckt oder fuer mich einsehbar) */
    public CardDto topCard;
    /** topCard ist nur fuer mich sichtbar (nicht aufgedeckt) */
    public boolean topCardPrivate;
}
