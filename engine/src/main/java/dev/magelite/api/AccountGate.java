package dev.magelite.api;

import io.javalin.http.Handler;

/**
 * Huelle um die Nutzerdaten-Routen (Decks, Profil, Statistik, Verlauf). In der Desktop-App mit Online-Konto reicht sie
 * die Anfrage an den Server durch ({@code account.AccountProxy}); sonst laeuft der lokale Handler. Neue Routen mit
 * Nutzerdaten immer hierueber registrieren, sonst zeigt die App lokale statt Kontodaten.
 */
@FunctionalInterface
public interface AccountGate {

    Handler wrap(Handler local);

    /** Server-Modus und Engines ohne Konto: immer lokal. */
    AccountGate LOCAL = h -> h;
}
