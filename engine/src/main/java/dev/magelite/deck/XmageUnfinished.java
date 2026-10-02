package dev.magelite.deck;

import mage.cards.ExpansionSet;
import mage.cards.Sets;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Karten, die XMage zwar kennt, aber als "unfinished" aus der Karten-DB herausnimmt
 * (z. B. Prepare-Karten aus Secrets of Strixhaven in 1.4.60). Die Sets fuehren dafuer ein
 * {@code private static List<String> unfinished}, das hier per Reflection gelesen wird.
 */
final class XmageUnfinished {

    private static volatile Set<String> names;

    private XmageUnfinished() {
    }

    static boolean contains(String name) {
        return all().contains(normalize(name));
    }

    private static Set<String> all() {
        Set<String> n = names;
        if (n == null) {
            synchronized (XmageUnfinished.class) {
                n = names;
                if (n == null) {
                    n = load();
                    names = n;
                }
            }
        }
        return n;
    }

    private static Set<String> load() {
        Set<String> out = new HashSet<>();
        for (ExpansionSet set : Sets.getInstance().values()) {
            try {
                Field f = set.getClass().getDeclaredField("unfinished");
                if (!Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                f.setAccessible(true);
                if (f.get(null) instanceof Collection<?> c) {
                    for (Object o : c) {
                        out.add(normalize(String.valueOf(o)));
                    }
                }
            } catch (NoSuchFieldException ignored) {
                // Set ohne unfertige Karten
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // andere XMage-Version: dann eben ohne Unterscheidung
            }
        }
        return out;
    }

    private static String normalize(String name) {
        return name.replace('’', '\'').trim().toLowerCase(Locale.ROOT);
    }
}
