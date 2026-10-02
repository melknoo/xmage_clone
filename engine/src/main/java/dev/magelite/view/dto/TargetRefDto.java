package dev.magelite.view.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Ziel eines Stapelobjekts mit aufgeloestem Namen (auch fuer Ziele in Zonen, die die UI nicht kennt).
 */
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class TargetRefDto {
    public UUID id;
    public String name;
    /** player | permanent | spell | card */
    public String kind;
    /** Zone bei kind=card, z.B. GRAVEYARD */
    public String zone;
    /** Beherrscher (Permanent/Zauber) bzw. Besitzer (Karte) */
    public String owner;
}
