package dev.magelite.game;

import dev.magelite.boot.CardDbManager;
import mage.abilities.Ability;
import mage.cards.Card;
import mage.cards.repository.CardRepository;
import mage.filter.common.FilterCreaturePermanent;
import mage.target.Target;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Karten-Ersatzklassen in {@code engine/src/main/java/mage/cards} greifen (Engine vor den XMage-Jars). */
class CardOverridesTest {

    @BeforeAll
    static void db() throws Exception {
        CardDbManager.ensure(Path.of(System.getProperty("magelite.vendor"), "db", "cards.h2.mv.db"));
    }

    @Test
    void mightyThorTargetsArtifacts() {
        Card card = CardRepository.instance.findCards("The Mighty Thor, Jane Foster").get(0).createCard();
        assertNotNull(card);
        Target target = card.getAbilities().stream()
                .flatMap((Ability a) -> a.getTargets().stream())
                .findFirst().orElseThrow();
        assertFalse(target.getFilter() instanceof FilterCreaturePermanent, "Filter darf nicht nur Kreaturen erlauben");
    }
}
