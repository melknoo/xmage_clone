package dev.magelite.game;

import forge.LobbyPlayer;
import forge.gamemodes.match.AbstractGuiGame;
import forge.game.GameEntityView;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.event.GameEvent;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.IHasIcon;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.deck.CardPool;
import forge.localinstance.skin.FSkinProp;
import forge.player.PlayerZoneUpdate;
import forge.trackable.TrackableCollection;
import forge.util.FSerializableFunction;
import forge.util.ITriggerEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Forge-GUI eines menschlichen Sitzes. Zeigt nichts an: merkt sich, was Forge dem Spieler "zeigen" will (Text, Knoepfe,
 * waehlbare Karten), und laesst jede blockierende Frage als Prompt ueber {@link GameHost#park} laufen.
 * <p>
 * Alles laeuft auf dem Spiel-Thread: {@link #awaitInput} und die Dialoge parken ihn in der inbox-Schleife des Hosts.
 * Aufrufe von fremden Threads (Forges {@code awaitNextInput}-Timer ueber {@code forge-edt}) werden ignoriert, sie
 * wuerden nur "Warten auf Gegner" in den gemerkten Text schreiben.
 */
public final class SeatGui extends AbstractGuiGame {

    private final GameHost host;
    private final GameHost.HumanSeat seat;

    // zuletzt "angezeigt" (nur Spiel-Thread)
    String message = "";
    CardView messageCard;
    String btn1 = "";
    String btn2 = "";
    boolean btn1Enabled;
    boolean btn2Enabled;
    final List<CardView> selectables = new ArrayList<>();
    /** auch bei nur einer Faehigkeit fragen ("N-mal aktivieren"), gesetzt von {@link HumanController} */
    boolean forceAbilityChoice;

    SeatGui(GameHost host, GameHost.HumanSeat seat) {
        this.host = host;
        this.seat = seat;
    }

    private boolean onGameThread() {
        return host.isGameThread();
    }

    // ---- Eingaben (Inputs) -------------------------------------------------------------------------------------------

    @Override
    public void awaitInput(CountDownLatch done) {
        host.park(host.bridge().inputFrame(seat, done));
    }

    @Override
    public void showPromptMessage(PlayerView playerView, String message, CardView card) {
        if (onGameThread()) {
            this.message = message == null ? "" : message;
            this.messageCard = card;
            host.bridge().markDirty(seat);
        }
    }

    @Override
    public void updateButtons(PlayerView owner, String label1, String label2, boolean enable1, boolean enable2, boolean focus1) {
        if (onGameThread()) {
            btn1 = label1;
            btn2 = label2;
            btn1Enabled = enable1;
            btn2Enabled = enable2;
            host.bridge().markDirty(seat);
        }
    }

    @Override
    public void setSelectables(Iterable<CardView> cards, int min, int max) {
        super.setSelectables(cards, min, max);
        if (onGameThread()) {
            for (CardView c : cards) {
                selectables.add(c);
            }
        }
    }

    @Override
    public void clearSelectables() {
        super.clearSelectables();
        if (onGameThread()) {
            selectables.clear();
        }
    }

    @Override
    public boolean isUiSetToSkipPhase(PlayerView playerTurn, PhaseType phase) {
        return false; // Passen entscheidet MageLite (AutoPassPolicy)
    }

    // ---- Blockierende Dialoge -------------------------------------------------------------------------------------------

    @Override
    public SpellAbilityView getAbilityToPlay(CardView hostCard, List<SpellAbilityView> abilities, ITriggerEvent triggerEvent) {
        if (abilities.isEmpty()) {
            return null;
        }
        if (abilities.size() == 1 && !forceAbilityChoice) {
            return abilities.get(0);
        }
        return host.bridge().chooseAbility(seat, hostCard, abilities);
    }

    @Override
    public Integer getInteger(String message, int min, int max, boolean sortDesc) {
        if (max <= min) {
            return min;
        }
        return host.bridge().amount(seat, message, min, max);
    }

    @Override
    public Integer getInteger(String message, int min, int max, int cutoff) {
        if (max <= min) {
            return min;
        }
        return host.bridge().amount(seat, message, min, max);
    }

    @Override
    public <T> List<T> getChoices(String message, int min, int max, List<T> choices, List<T> selected, FSerializableFunction<T, String> display) {
        if (choices == null || choices.isEmpty() || (min < 0 && max < 0)) {
            return new ArrayList<>(); // reveal: nur zeigen, nie fragen
        }
        return host.bridge().choices(seat, message, Math.max(0, min), max < 0 ? choices.size() : max, choices, display);
    }

    /** Forge zeigt dem Spieler Karten (aufdecken, ansehen): nie blockieren, fuer revealed/lookedAt merken. */
    @Override
    public <T> void reveal(String message, List<T> items) {
        if (!onGameThread() || items == null || items.isEmpty()) {
            return;
        }
        List<forge.game.card.Card> cards = new ArrayList<>();
        forge.game.player.Player owner = null;
        for (T t : items) {
            if (t instanceof CardView cv) {
                forge.game.card.Card c = host.game().findById(cv.getId());
                if (c != null) {
                    cards.add(c);
                    owner = owner == null ? c.getOwner() : owner;
                }
            }
        }
        host.noteReveal(seat.player(), message, cards, owner);
    }

    @Override
    public boolean confirm(CardView c, String question, boolean defaultIsYes, List<String> options) {
        String yes = options != null && options.size() > 0 ? options.get(0) : "Yes";
        String no = options != null && options.size() > 1 ? options.get(1) : "No";
        return host.bridge().ask(seat, question, yes, no, defaultIsYes);
    }

    @Override
    public boolean showConfirmDialog(String message, String title, String yesButtonText, String noButtonText, boolean defaultYes) {
        return host.bridge().ask(seat, message, yesButtonText, noButtonText, defaultYes);
    }

    @Override
    public int showOptionDialog(String message, String title, FSkinProp icon, List<String> options, int defaultOption) {
        if (options == null || options.isEmpty()) {
            return defaultOption;
        }
        if (options.size() == 2) {
            return host.bridge().ask(seat, message, options.get(0), options.get(1), defaultOption == 0) ? 0 : 1;
        }
        List<String> picked = host.bridge().choices(seat, message, 1, 1, options, null);
        return picked.isEmpty() ? defaultOption : options.indexOf(picked.get(0));
    }

    @Override
    public String showInputDialog(String message, String title, FSkinProp icon, String initialInput, List<String> inputOptions, boolean isNumeric) {
        if (inputOptions != null && !inputOptions.isEmpty()) {
            List<String> picked = host.bridge().choices(seat, message, 1, 1, inputOptions, null);
            return picked.isEmpty() ? initialInput : picked.get(0);
        }
        if (isNumeric) {
            return String.valueOf(host.bridge().amount(seat, message, 0, 99));
        }
        host.bridge().unmapped(seat, "showInputDialog", message);
        return initialInput;
    }

    @Override
    public Map<CardView, Integer> assignCombatDamage(CardView attacker, List<CardView> blockers, int damage, GameEntityView defender,
                                                     boolean overrideOrder, boolean maySkip) {
        return host.bridge().combatDamage(seat, attacker, blockers, damage, defender);
    }

    @Override
    public Map<Object, Integer> assignGenericAmount(CardView effectSource, Map<Object, Integer> target, int amount, boolean atLeastOne, String amountLabel) {
        return host.bridge().genericAmount(seat, effectSource, target, amount, atLeastOne, amountLabel);
    }

    @Override
    public GameEntityView chooseSingleEntityForEffect(String title, List<? extends GameEntityView> optionList, DelayedReveal delayedReveal, boolean isOptional) {
        List<GameEntityView> picked = host.bridge().entities(seat, title, optionList, isOptional ? 0 : 1, 1);
        return picked.isEmpty() ? null : picked.get(0);
    }

    @Override
    public List<GameEntityView> chooseEntitiesForEffect(String title, List<? extends GameEntityView> optionList, int min, int max, DelayedReveal delayedReveal) {
        return host.bridge().entities(seat, title, optionList, min, max);
    }

    @Override
    public <T> OrderResult<T> order(String title, String top, int remainingObjectsMin, int remainingObjectsMax, List<T> sourceChoices,
                                    List<T> destChoices, CardView referenceCard, boolean sideboardingMode, boolean showRememberCheckbox) {
        List<T> all = new ArrayList<>();
        if (destChoices != null) {
            all.addAll(destChoices);
        }
        all.addAll(sourceChoices);
        if (remainingObjectsMin > 0 || remainingObjectsMax > 0) {
            // Auswahl mit Reihenfolge: zwischen (n - max) und (n - min) Objekte uebernehmen
            int min = Math.max(0, all.size() - Math.max(remainingObjectsMax, remainingObjectsMin));
            int max = Math.max(min, all.size() - Math.max(0, remainingObjectsMin));
            return new OrderResult<>(host.bridge().choices(seat, title, min, max, all, null), false);
        }
        return new OrderResult<>(host.bridge().order(seat, title, top, all), false);
    }

    /** Mehrfachauswahl (Scry nach unten, Starthand ...): -1 = unbegrenzt. */
    @Override
    public <T> List<T> many(String title, String topCaption, int min, int max, List<T> sourceChoices, List<T> destChoices, CardView c) {
        if (sourceChoices == null || sourceChoices.isEmpty()) {
            return new ArrayList<>();
        }
        int lo = Math.max(0, min);
        int hi = max < 0 ? sourceChoices.size() : Math.min(max, sourceChoices.size());
        if (hi <= 0) {
            return new ArrayList<>();
        }
        return host.bridge().choices(seat, title, Math.min(lo, hi), hi, sourceChoices, null);
    }

    @Override
    public List<CardView> manipulateCardList(String title, Iterable<CardView> cards, Iterable<CardView> manipulable, boolean toTop,
                                             boolean toBottom, boolean toAnywhere) {
        List<CardView> all = new ArrayList<>();
        cards.forEach(all::add);
        List<CardView> movable = new ArrayList<>();
        manipulable.forEach(movable::add);
        if (movable.size() < 2 || movable.size() != all.size()) {
            host.bridge().auto(seat, "manipulateCardList", title);
            return all;
        }
        return host.bridge().order(seat, title, toTop ? "oben zuerst" : "", all);
    }

    @Override
    public List<PaperCard> sideboard(CardPool sideboard, CardPool main, String message) {
        return null; // Commander: kein Sideboarding
    }

    // ---- Nicht blockierende Anzeigen --------------------------------------------------------------------------------------

    @Override
    public void message(String message, String title) {
        host.toast(seat, "info", dev.magelite.view.ForgeText.german(message));
    }

    @Override
    public void showErrorDialog(String message, String title) {
        host.toast(seat, "error", dev.magelite.view.ForgeText.german(message));
    }

    @Override
    public void handleGameEvent(GameEvent event) {
        // Spiel-Ereignisse liest ForgeEvents direkt vom Bus (Phase 1)
    }

    @Override
    public void updateRevealedCards(TrackableCollection<CardView> collection) {
    }

    @Override
    public void setGameView(GameView gameView) {
        super.setGameView(gameView);
    }

    @Override
    protected void updateCurrentPlayer(PlayerView player) {
    }

    @Override
    public void openView(TrackableCollection<PlayerView> myPlayers) {
    }

    @Override
    public void showCombat() {
    }

    @Override
    public void flashIncorrectAction() {
    }

    @Override
    public void alertUser() {
    }

    @Override
    public void finishGame() {
    }

    @Override
    public void setPanelSelection(CardView hostCard) {
    }

    @Override
    public void setCard(CardView card) {
    }

    @Override
    public void setPlayerAvatar(LobbyPlayer player, IHasIcon ihi) {
    }

    @Override
    public void updateZones(Iterable<PlayerZoneUpdate> zonesToUpdate) {
    }

    @Override
    public void updateCards(Iterable<CardView> cards) {
    }

    @Override
    public void openZones(PlayerView controller, Collection<ZoneType> zones, Map<PlayerView, Object> players) {
    }

    @Override
    public forge.game.GameState getGamestate() {
        return null;
    }

    @Override
    public <T> List<T> insertInList(String title, T newItem, List<T> oldItems) {
        List<T> out = new ArrayList<>(oldItems);
        out.add(newItem);
        return out;
    }

    List<CardView> selectablesView() {
        return Collections.unmodifiableList(selectables);
    }

    @Override
    public String toString() {
        return "SeatGui[" + seat.name() + "]";
    }
}
