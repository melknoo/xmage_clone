package dev.magelite.view;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Forge-ids (int, je Spiel) ↔ Wire-ids ({@link UUID}, wie zu XMage-Zeiten). {@code msb} ist je Spiel zufaellig,
 * {@code lsb = kind << 56 | feistel32(id)}. Die Permutation verbirgt Forges sequenzielle Karten-ids (sonst verriete die
 * id einer verdeckten Karte ihre Position im Deck und damit ihre Identitaet).
 */
public final class IdCodec {

    /** Art des Objekts hinter einer Wire-id. */
    public enum Kind {
        /** Karte (auch Zauber auf dem Stapel, damit ein Klick darauf {@code selectCard} trifft) */
        CARD(1),
        PLAYER(2),
        /** Faehigkeit zur Auswahl ({@code SpellAbilityView}) */
        SA(3),
        /** Faehigkeit auf dem Stapel ({@code SpellAbilityStackInstance}) */
        STACK(4),
        /** synthetische Auswahl (Index) */
        OPTION(5);

        final int code;

        Kind(int code) {
            this.code = code;
        }

        static Kind of(int code) {
            for (Kind k : values()) {
                if (k.code == code) {
                    return k;
                }
            }
            return null;
        }
    }

    public record Decoded(Kind kind, int id) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final long msb;
    private final int[] roundKeys = new int[4];

    public IdCodec() {
        this.msb = RANDOM.nextLong();
        for (int i = 0; i < roundKeys.length; i++) {
            roundKeys[i] = RANDOM.nextInt();
        }
    }

    public UUID encode(Kind kind, int id) {
        long lsb = ((long) kind.code << 56) | (permute(id) & 0xffffffffL);
        return new UUID(msb, lsb);
    }

    public UUID card(int id) {
        return encode(Kind.CARD, id);
    }

    public UUID player(int id) {
        return encode(Kind.PLAYER, id);
    }

    /** null, wenn die id nicht aus diesem Spiel stammt. */
    public Decoded decode(UUID u) {
        if (u == null || u.getMostSignificantBits() != msb) {
            return null;
        }
        long lsb = u.getLeastSignificantBits();
        Kind kind = Kind.of((int) (lsb >>> 56));
        if (kind == null || ((lsb >>> 32) & 0xffffffL) != 0) {
            return null;
        }
        return new Decoded(kind, unpermute((int) lsb));
    }

    // ---- 32-bit-Feistel (4 Runden, 16-bit-Haelften)

    private int permute(int x) {
        int l = (x >>> 16) & 0xffff;
        int r = x & 0xffff;
        for (int k : roundKeys) {
            int t = l ^ round(r, k);
            l = r;
            r = t;
        }
        return (l << 16) | r;
    }

    private int unpermute(int x) {
        int l = (x >>> 16) & 0xffff;
        int r = x & 0xffff;
        for (int i = roundKeys.length - 1; i >= 0; i--) {
            int t = r ^ round(l, roundKeys[i]);
            r = l;
            l = t;
        }
        return (l << 16) | r;
    }

    private static int round(int half, int key) {
        int h = (half ^ key) * 0x9E3779B1;
        h ^= h >>> 15;
        h *= 0x85EBCA77;
        h ^= h >>> 13;
        return h & 0xffff;
    }
}
