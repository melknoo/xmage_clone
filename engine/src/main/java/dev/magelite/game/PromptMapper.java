package dev.magelite.game;

import dev.magelite.view.GameViewMapper;
import dev.magelite.view.RichText;
import dev.magelite.view.dto.CardDto;
import dev.magelite.view.dto.PromptDto;
import mage.abilities.Ability;
import mage.cards.Card;
import mage.choices.Choice;
import mage.constants.Constants;
import mage.constants.PhaseStep;
import mage.game.Game;
import mage.game.events.PlayerQueryEvent;
import mage.game.permanent.Permanent;
import mage.util.MultiAmountMessage;
import mage.view.AbilityPickerView;
import mage.view.CardsView;
import mage.view.PermanentView;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Uebersetzt {@link PlayerQueryEvent}s in {@link PromptDto}s (Pendant zu GameController/GameSessionPlayer).
 * Laeuft auf dem Game-Thread.
 */
public final class PromptMapper {

    private PromptMapper() {
    }

    /**
     * @return null, wenn das Event keine Entscheidung ist (z.B. PERSONAL_MESSAGE)
     */
    public static PromptDto map(Game game, PlayerQueryEvent event, UUID humanId) {
        PromptDto p = new PromptDto();
        p.kind = event.getQueryType().name();
        p.playerId = event.getPlayerId();
        p.message = RichText.parse(event.getMessage());
        p.messageText = RichText.plain(event.getMessage());
        p.required = event.isRequired();
        Map<String, Serializable> options = event.getOptions();
        if (options != null) {
            p.leftBtn = str(options.get("UI.left.btn.text"));
            p.rightBtn = str(options.get("UI.right.btn.text"));
            Object second = options.get(Constants.Option.SECOND_MESSAGE);
            if (second != null) {
                p.secondMessage = RichText.parse(second.toString());
            }
        }

        switch (event.getQueryType()) {
            case ASK -> {
                p.mulligan = "Mulligan".equals(p.leftBtn);
                if (options != null && options.get(Constants.Option.AUTO_ANSWER_MESSAGE) != null) {
                    p.autoAnswer = options.get(Constants.Option.AUTO_ANSWER_MESSAGE).toString();
                }
            }
            case SELECT -> {
                p.mode = "priority";
                if (options != null) {
                    if (options.containsKey(Constants.Option.POSSIBLE_ATTACKERS)) {
                        p.mode = "attackers";
                        p.possibleAttackers = uuids(options.get(Constants.Option.POSSIBLE_ATTACKERS));
                    }
                    if (options.containsKey(Constants.Option.POSSIBLE_BLOCKERS)) {
                        p.mode = "blockers";
                        p.possibleBlockers = uuids(options.get(Constants.Option.POSSIBLE_BLOCKERS));
                    }
                    Object special = options.get(Constants.Option.SPECIAL_BUTTON);
                    if (special != null) {
                        p.specialBtn = special.toString();
                    }
                }
            }
            case PICK_TARGET -> mapTarget(game, event, humanId, p, options);
            case PICK_ABILITY -> {
                List<PromptDto.Item> items = new ArrayList<>();
                if (event.getAbilities() != null) {
                    for (Ability a : event.getAbilities()) {
                        Card src = game.getCard(a.getSourceId());
                        items.add(new PromptDto.Item(a.getId().toString(), RichText.plain(a.getRule(true)), a.getSourceId(),
                                src == null ? null : src.getExpansionSetCode(), src == null ? null : src.getCardNumber()));
                    }
                }
                p.choices = items;
            }
            case CHOOSE_ABILITY -> {
                String objectName = null;
                if (event.getChoices() != null && !event.getChoices().isEmpty()) {
                    objectName = event.getChoices().iterator().next();
                }
                AbilityPickerView view = new AbilityPickerView(null, objectName, event.getAbilities(), event.getMessage());
                p.choices = items(view.getChoices());
                boolean special = event.getAbilities() != null
                        && event.getAbilities().stream().anyMatch(a -> a instanceof mage.abilities.SpecialAction);
                if (special) {
                    // Sonderbezahlung nach "special" (Convoke & Co.): kein sourceId (sonst "N-mal aktivieren"),
                    // XMage schickt als Text den Spielernamen
                    if (SpecialPay.relabel(game, event.getAbilities(), p)) {
                        p.message = RichText.parse("Wie willst du bezahlen?");
                        p.messageText = "Wie willst du bezahlen?";
                    }
                } else if (event.getAbilities() != null) {
                    for (Ability a : event.getAbilities()) {
                        if (a.getSourceId() != null) {
                            p.sourceId = a.getSourceId();
                            break;
                        }
                    }
                }
            }
            case CHOOSE_MODE -> {
                AbilityPickerView view = new AbilityPickerView(null, event.getModes(), event.getMessage());
                p.choices = items(view.getChoices());
            }
            case CHOOSE_CHOICE -> {
                Choice c = event.getChoice();
                p.choice = choice(c);
                if (ReplacementAssist.isReplacementChoice(c)) {
                    p.choice.groups = ReplacementAssist.groups(p.choice);
                }
                p.message = RichText.parse(c.getMessage());
                p.messageText = RichText.plain(c.getMessage());
                p.required = c.isRequired();
            }
            case AMOUNT -> {
                p.min = event.getMin();
                p.max = event.getMax();
            }
            case MULTI_AMOUNT -> {
                p.min = event.getMin();
                p.max = event.getMax();
                List<PromptDto.AmountItem> items = new ArrayList<>();
                if (event.getMessages() != null) {
                    for (MultiAmountMessage m : event.getMessages()) {
                        items.add(new PromptDto.AmountItem(RichText.plain(m.message), m.min, m.max, m.defaultValue));
                    }
                }
                p.items = items;
                if (options != null && options.get("title") != null) {
                    p.message = RichText.parse(options.get("title").toString());
                    p.messageText = RichText.plain(options.get("title").toString());
                }
            }
            case CHOOSE_PILE -> {
                p.pile1 = cardsOf(game, event.getPile1(), humanId);
                p.pile2 = cardsOf(game, event.getPile2(), humanId);
            }
            case PLAY_MANA, PLAY_X_MANA -> {
                if (options != null && options.get(Constants.Option.SPECIAL_BUTTON) != null) {
                    p.specialBtn = options.get(Constants.Option.SPECIAL_BUTTON).toString();
                } else if (event.getQueryType() == PlayerQueryEvent.QueryType.PLAY_MANA) {
                    // XMage setzt hier keinen Knopf; Sonderbezahlung nur per Antwort "special"
                    SpecialPay.describe(game, p.playerId, p);
                }
            }
            default -> {
                return null;
            }
        }
        return p;
    }

    private static void mapTarget(Game game, PlayerQueryEvent event, UUID humanId, PromptDto p, Map<String, Serializable> options) {
        List<UUID> targets = new ArrayList<>();
        if (event.getTargets() != null) {
            targets.addAll(event.getTargets());
        }
        if (options != null) {
            p.chosen = uuids(options.get("chosenTargets"));
            if (targets.isEmpty() && options.get("possibleTargets") != null) {
                targets.addAll(uuids(options.get("possibleTargets")));
            }
        }
        if (event.getCards() != null) {
            CardsView cv = new CardsView(game, event.getCards().getCards(game), humanId, true);
            p.cards = GameViewMapper.cards(cv.values());
            if (targets.isEmpty()) {
                targets.addAll(cv.keySet());
            }
        } else if (event.getPerms() != null) {
            List<CardDto> perms = new ArrayList<>();
            for (Permanent perm : event.getPerms()) {
                perms.add(GameViewMapper.card(new PermanentView(perm, game.getCard(perm.getId()), humanId, game)));
            }
            p.cards = perms;
        }
        p.targets = targets;

        // Verteidiger-Wahl beim Angriff (mehrere Gegner)
        if (game.getTurnStepType() == PhaseStep.DECLARE_ATTACKERS && !targets.isEmpty() && p.cards == null) {
            boolean allDefenders = true;
            for (UUID id : targets) {
                if (game.getPlayer(id) != null) {
                    continue;
                }
                Permanent perm = game.getPermanent(id);
                if (perm == null || !(perm.isPlaneswalker(game) || perm.isBattle(game))) {
                    allDefenders = false;
                    break;
                }
            }
            p.defenderPick = allDefenders;
        }
    }

    private static List<CardDto> cardsOf(Game game, List<? extends Card> cards, UUID humanId) {
        if (cards == null) {
            return List.of();
        }
        return GameViewMapper.cards(new CardsView(game, cards, humanId).values());
    }

    private static PromptDto.ChoiceDto choice(Choice c) {
        PromptDto.ChoiceDto d = new PromptDto.ChoiceDto();
        d.message = RichText.plain(c.getMessage());
        d.subMessage = c.getSubMessage() == null ? null : RichText.plain(c.getSubMessage());
        d.required = c.isRequired();
        d.keyed = c.isKeyChoice();
        d.search = c.isSearchEnabled();
        d.manaColor = c.isManaColorChoice();
        d.hint = c.getHintType() == null ? null : c.getHintType().name().toLowerCase(java.util.Locale.ROOT);
        if (c.isSpecialEnabled()) {
            d.specialText = c.getSpecialText();
        }
        List<PromptDto.ChoiceItem> items = new ArrayList<>();
        Map<String, Integer> sort = c.getSortData();
        Map<String, List<String>> hints = c.getHintData();
        if (c.isKeyChoice()) {
            for (Map.Entry<String, String> e : c.getKeyChoices().entrySet()) {
                items.add(new PromptDto.ChoiceItem(e.getKey(), RichText.plain(e.getValue()),
                        sort == null ? null : sort.get(e.getKey()), hints == null ? null : hints.get(e.getKey())));
            }
        } else {
            for (String v : c.getChoices()) {
                items.add(new PromptDto.ChoiceItem(v, RichText.plain(v), sort == null ? null : sort.get(v), null));
            }
        }
        d.items = items;
        return d;
    }

    private static List<PromptDto.Item> items(Map<UUID, String> choices) {
        List<PromptDto.Item> out = new ArrayList<>();
        if (choices != null) {
            choices.forEach((id, text) -> out.add(new PromptDto.Item(id.toString(), RichText.plain(text), null, null, null)));
        }
        return out;
    }

    private static List<UUID> uuids(Object o) {
        List<UUID> out = new ArrayList<>();
        if (o instanceof Collection<?> c) {
            for (Object e : c) {
                if (e instanceof UUID u) {
                    out.add(u);
                } else if (e != null) {
                    try {
                        out.add(UUID.fromString(e.toString()));
                    } catch (IllegalArgumentException ignored) {
                        // kein UUID
                    }
                }
            }
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
