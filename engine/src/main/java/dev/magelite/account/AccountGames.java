package dev.magelite.account;

import dev.magelite.deck.DeckResolver;
import dev.magelite.game.GameHost;

/**
 * Spielstart in der Desktop-App mit Konto-Modus: angemeldet laufen eigene Decks aus dem Konto, der Mensch heisst wie
 * das Konto und das Ergebnis geht an den Server ({@link AccountReporter}); offline bzw. ohne Konto-Modus wie bisher
 * lokal. In den Zwischenzustaenden (pruefen, abgemeldet, Server weg) startet kein Spiel - sonst landete es still in
 * der falschen Statistik.
 */
public final class AccountGames {

    /** Konto-Spiel: Deck-Herkunft, Spielername, Reward-Hook (fuer genau dieses Konto). */
    public record Start(DeckResolver.DeckSource decks, String playerName, GameHost.RewardHook hook) {
    }

    /** Spielstart gerade nicht moeglich ({@code 409 {account: state}}). */
    public static final class Blocked extends RuntimeException {
        public final AccountLink.State state;

        public Blocked(AccountLink.State state) {
            super("Spielstart gerade nicht möglich (" + state + ")");
            this.state = state;
        }
    }

    private final AccountLink link;
    private final AccountDecks decks;
    private final AccountReporter reporter;

    public AccountGames(AccountLink link, AccountDecks decks, AccountReporter reporter) {
        this.link = link;
        this.decks = decks;
        this.reporter = reporter;
    }

    /** @return null = lokales Spiel; sonst Konto-Spiel. @throws Blocked im Zwischenzustand */
    public Start begin() {
        if (link.local()) {
            return null;
        }
        AccountLink.Account a = link.user();
        String server = link.server();
        if (!link.active() || a == null || server == null) {
            throw new Blocked(link.state());
        }
        return new Start(decks, a.name(), reporter.hookFor(server, a));
    }
}
