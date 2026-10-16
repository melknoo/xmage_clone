package dev.magelite.account;

import com.fasterxml.jackson.databind.JsonNode;
import dev.magelite.api.Json;
import dev.magelite.deck.DeckResolver;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Eigene Decks aus dem Online-Konto fuer den Spielstart in der Desktop-App ({@code GET /api/decks/{id}} am Server:
 * Name + Decktext v2). Passiv: das Spiel laeuft danach lokal, fly muss dafuer nicht wach bleiben.
 */
public final class AccountDecks implements DeckResolver.DeckSource {

    private static final Duration TIMEOUT = Duration.ofSeconds(25);

    private final AccountLink link;

    public AccountDecks(AccountLink link) {
        this.link = link;
    }

    @Override
    public Optional<DeckResolver.Owned> load(long userId, long id) throws Exception {
        HttpResponse<String> res;
        try {
            res = link.call("GET", "/api/decks/" + id, null, true, TIMEOUT);
        } catch (IOException e) {
            throw new IllegalArgumentException("Server nicht erreichbar – dein Deck kann gerade nicht geladen werden");
        }
        if (res.statusCode() == 401) {
            link.expired();
            throw new IllegalArgumentException("Anmeldung abgelaufen – bitte neu anmelden");
        }
        if (res.statusCode() != 200) {
            return Optional.empty();
        }
        JsonNode b = Json.MAPPER.readTree(res.body());
        String text = b.path("dck").asText("");
        if (text.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new DeckResolver.Owned(b.path("deck").path("name").asText("Deck"), text));
    }
}
