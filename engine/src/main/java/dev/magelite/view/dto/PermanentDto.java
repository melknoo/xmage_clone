package dev.magelite.view.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class PermanentDto extends CardDto {
    public boolean tapped;
    public int damage;
    public boolean sick;
    public boolean copy;
    public boolean phasedOut;
    public boolean flipped;
    public List<UUID> attachments;
    public UUID attachedTo;
    public UUID ownerId;
    /** land | creature | other - Reihe auf dem Spielfeld */
    public String row;
    public boolean attacking;
    public boolean blocking;
    public boolean canAttack;
    public boolean canBlock;
    /** P/T weicht vom Grundwert ab (Zaehler, Boni, "wird zu X/X"); bei verdeckten Permanents immer false */
    public boolean ptModified;
}
