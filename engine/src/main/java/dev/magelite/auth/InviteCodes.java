package dev.magelite.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Einladungscodes: 80 Bit Zufall als Base32 ({@code XXXX-XXXX-XXXX-XXXX}). Gespeichert wird nur der SHA-256 des
 * normalisierten Codes. Normalisierung: Gross/klein und Bindestriche egal, 0->O, 1->I, 8->B (Tippfehler-tolerant).
 */
public final class InviteCodes {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private InviteCodes() {
    }

    /** Gibt einen neuen Code aus (fuer den Owner-Code in den fly-Secrets). */
    public static void main(String[] args) {
        System.out.println(generate());
    }

    /** Neuer Code im Anzeigeformat {@code XXXX-XXXX-XXXX-XXXX}. */
    public static String generate() {
        byte[] b = new byte[10];
        RANDOM.nextBytes(b);
        String raw = base32(b);
        StringBuilder sb = new StringBuilder(19);
        for (int i = 0; i < 16; i++) {
            if (i > 0 && i % 4 == 0) {
                sb.append('-');
            }
            sb.append(raw.charAt(i));
        }
        return sb.toString();
    }

    /** Normalisierte Form (nur A-Z und 2-9, ohne Trenner); leer, wenn nichts Brauchbares uebrig bleibt. */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toUpperCase(Locale.ROOT).toCharArray()) {
            char c = switch (ch) {
                case '0' -> 'O';
                case '1' -> 'I';
                case '8' -> 'B';
                default -> ch;
            };
            if ((c >= 'A' && c <= 'Z') || (c >= '2' && c <= '9')) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** SHA-256 (hex) des normalisierten Codes. */
    public static String hash(String code) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(normalize(code).getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** RFC-4648-Base32 ohne Padding; 10 Bytes ergeben genau 16 Zeichen. */
    static String base32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        }
        return sb.toString();
    }
}
