package dev.magelite.spike;

import dev.magelite.boot.CardDbManager;
import dev.magelite.boot.LogConfig;
import dev.magelite.deck.DeckLoader;
import dev.magelite.deck.LoadedDeck;
import dev.magelite.game.MageLiteBot;
import dev.magelite.game.MageLiteMatch;
import dev.magelite.game.TempoSettings;
import mage.cards.Card;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.GameOptions;
import mage.game.PutToBattlefieldInfo;
import mage.game.events.TableEvent;
import mage.players.Player;
import mage.util.ThreadUtils;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Regressionstest fuer "du bestimmst, welche Kreaturen blocken" (ChooseBlockersEffect) unter Bot-Kontrolle.
 * Bot A hat Odric, Master Tactician + 3 Baeren im Spiel und greift an; Bot B wird angegriffen.
 * Vor dem Fix: StackOverflowError (ComputerPlayer6 feuert DECLARING_BLOCKERS erneut -> Effekt ruft Bot wieder auf).
 * <p>
 * Args: --games=N (Standard 2: einmal ohne, einmal mit Blockern bei B) --turnCap=T
 */
public final class BlockerSpike {

    private static final AtomicInteger CHOSEN_BY_EFFECT = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        int turnCap = 6;
        int games = 2;
        for (String a : args) {
            if (a.startsWith("--turnCap=")) {
                turnCap = Integer.parseInt(a.substring(10));
            } else if (a.startsWith("--games=")) {
                games = Integer.parseInt(a.substring(8));
            }
        }
        Path vendor = Path.of(System.getProperty("magelite.vendor", "../../vendor/xmage")).toAbsolutePath().normalize();
        Path logs = Path.of("logs").toAbsolutePath();
        Files.createDirectories(logs);
        LogConfig.configure(logs, false);
        Logger.getLogger(MageLiteBot.class).addAppender(new AppenderSkeleton() {
            @Override
            protected void append(LoggingEvent event) {
                if (String.valueOf(event.getMessage()).contains("per Effekt")) {
                    CHOSEN_BY_EFFECT.incrementAndGet();
                }
            }

            @Override
            public void close() {
            }

            @Override
            public boolean requiresLayout() {
                return false;
            }
        });
        CardDbManager.ensure(vendor.resolve("db/cards.h2.mv.db"));

        List<LoadedDeck> decks = new ArrayList<>();
        for (Path f : DeckLoader.listDeckFiles(vendor.resolve("sample-decks"))) {
            LoadedDeck d = DeckLoader.loadFile(f);
            if (d.valid()) {
                decks.add(d);
            }
            if (decks.size() == 2) {
                break;
            }
        }

        int failures = 0;
        for (int g = 1; g <= games; g++) {
            boolean defenderHasCreatures = g % 2 == 0;
            if (!runGame(g, decks, turnCap, defenderHasCreatures)) {
                failures++;
            }
        }
        out("=== %d Spiele, %d fehlgeschlagen ===", games, failures);
        System.exit(failures > 0 ? 1 : 0);
    }

    private static boolean runGame(int nr, List<LoadedDeck> decks, int turnCap, boolean defenderHasCreatures) throws Exception {
        TempoSettings tempo = new TempoSettings(TempoSettings.Preset.BLITZ);
        tempo.setActionDelayMs(0);
        tempo.setCombatDelayMs(0);
        MageLiteMatch match = new MageLiteMatch(MageLiteMatch.defaultOptions("BlockerSpike " + nr));
        MageLiteBot a = new MageLiteBot("Odric-Bot", RangeOfInfluence.ALL, tempo);
        MageLiteBot b = new MageLiteBot("Verteidiger", RangeOfInfluence.ALL, tempo);
        match.addPlayer(a, decks.get(0).newDeck());
        match.addPlayer(b, decks.get(1).newDeck());
        match.startMatch();
        match.startGame();
        Game game = match.getGame();
        GameOptions go = GameOptions.getDefault().copy();
        go.rollbackTurnsAllowed = false;
        go.stopOnTurn = turnCap;
        go.stopAtStep = PhaseStep.UPKEEP;
        game.setGameOptions(go);

        game.cheat(a.getId(), List.of(), List.of(), battlefield("Odric, Master Tactician", "Grizzly Bears", "Grizzly Bears", "Grizzly Bears"),
                List.of(), List.of(), List.of());
        if (defenderHasCreatures) {
            game.cheat(b.getId(), List.of(), List.of(), battlefield("Llanowar Elves", "Llanowar Elves"), List.of(), List.of(), List.of());
        }
        game.setStartingPlayerId(a.getId());

        AtomicInteger odricLines = new AtomicInteger();
        game.addTableEventListener(event -> {
            if (event.getEventType() == TableEvent.EventType.INFO && event.getMessage() != null && event.getMessage().contains("Odric")) {
                odricLines.incrementAndGet();
            }
        });

        int chosenBefore = CHOSEN_BY_EFFECT.get();
        AtomicReference<Throwable> error = new AtomicReference<>();
        long t0 = System.currentTimeMillis();
        Thread gameThread = new Thread(() -> {
            try {
                game.start(null);
            } catch (Throwable e) {
                error.set(e);
            }
        }, ThreadUtils.THREAD_PREFIX_GAME + " " + game.getId());
        gameThread.start();
        gameThread.join(5 * 60_000L);
        if (gameThread.isAlive()) {
            for (Player p : game.getPlayers().values()) {
                game.setConcedingPlayer(p.getId());
            }
            gameThread.join(15_000);
            error.compareAndSet(null, new IllegalStateException("Zeitlimit"));
        }
        Player def = game.getPlayer(b.getId());
        int chosen = CHOSEN_BY_EFFECT.get() - chosenBefore;
        out("Spiel %d (Verteidiger %s Kreaturen): Zuege=%d | %.1f s | Leben Verteidiger=%d | Odric-Logzeilen=%d | Blocker per Effekt bestimmt=%d%s",
                nr, defenderHasCreatures ? "mit" : "ohne", game.getTurnNum(), (System.currentTimeMillis() - t0) / 1000.0,
                def == null ? -1 : def.getLife(), odricLines.get(), chosen,
                error.get() == null ? "" : " | FEHLER " + error.get());
        try {
            game.cleanUp();
            match.cleanUp();
        } catch (Exception ignored) {
            // egal im Spike
        }
        // Erfolg: kein Fehler und der Effekt wurde wirklich durchlaufen (sonst prueft der Test nichts)
        return error.get() == null && chosen > 0;
    }

    private static List<PutToBattlefieldInfo> battlefield(String... names) {
        List<PutToBattlefieldInfo> out = new ArrayList<>();
        for (String n : names) {
            CardInfo info = CardRepository.instance.findPreferredCoreExpansionCard(n);
            if (info == null) {
                throw new IllegalStateException("Karte nicht gefunden: " + n);
            }
            Card card = info.createCard();
            out.add(new PutToBattlefieldInfo(card, false));
        }
        return out;
    }

    private static void out(String fmt, Object... args) {
        System.out.println(String.format(Locale.ROOT, fmt, args));
        System.out.flush();
    }
}
