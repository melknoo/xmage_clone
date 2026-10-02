package dev.magelite.view.dto;

import java.util.List;

public record NamedCardsDto(String name, List<CardDto> cards) {
}
