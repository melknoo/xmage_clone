package dev.magelite.view.dto;

import java.util.List;
import java.util.UUID;

public record CombatDto(UUID defenderId, String defenderName, List<UUID> attackers, List<UUID> blockers, boolean blocked) {
}
