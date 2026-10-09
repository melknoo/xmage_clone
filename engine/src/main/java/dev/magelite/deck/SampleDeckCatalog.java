package dev.magelite.deck;

import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Katalog der mitgelieferten Commander-Decks (Bot-Decks). Die Decks liegen als Decktext v2 im Classpath
 * ({@code /sample-decks/<Gruppe>/<Name>.dck}, Liste in {@code /sample-decks/INDEX}, vom Gradle-Task {@code sampleIndex}
 * erzeugt); die Katalog-Eintraege kommen textuell (schnell, ohne Kartenobjekte), nur die Farben aus Forges Karten-DB.
 * <p>
 * Die ids ({@code "Commander 2014/Peer Through Time.dck"}) sind seit der XMage-Zeit unveraendert.
 */
public final class SampleDeckCatalog {

    private static final Logger LOG = Logger.getLogger(SampleDeckCatalog.class);
    static final String ROOT = "/sample-decks/";
    private static final Pattern CARD_LINE = Pattern.compile("^(\\d+)\\s*[xX]?\\s+(.+?)(?:\\s+\\(([A-Za-z0-9_]{2,8})\\)(?:\\s+(\\S+))?)?\\s*$");

    public record Entry(String id, String name, String group, List<String> commanders, String colors,
                        String commanderSet, String commanderNum, int cards) {
    }

    private volatile List<Entry> entries;

    public SampleDeckCatalog() {
    }

    public List<Entry> list() {
        List<Entry> e = entries;
        if (e == null) {
            synchronized (this) {
                if (entries == null) {
                    entries = scan();
                }
                e = entries;
            }
        }
        return e;
    }

    public Optional<Entry> find(String id) {
        return list().stream().filter(e -> e.id().equals(id)).findFirst();
    }

    /** Decktext (v2) eines Sample-Decks. */
    public String text(String id) {
        if (id == null || id.contains("..") || id.startsWith("/") || id.contains("\\")) {
            throw new IllegalArgumentException("Ungueltige Deck-ID");
        }
        try {
            return read(id);
        } catch (IOException e) {
            throw new IllegalArgumentException("Sample-Deck nicht lesbar: " + id, e);
        }
    }

    /** Alle ids aus dem Index, in Indexreihenfolge. */
    static List<String> index() throws IOException {
        List<String> ids = new ArrayList<>();
        for (String line : read("INDEX").split("\\r?\\n")) {
            String l = line.strip();
            if (!l.isEmpty() && !l.startsWith("#")) {
                ids.add(l);
            }
        }
        return ids;
    }

    private static String read(String relative) throws IOException {
        try (InputStream in = SampleDeckCatalog.class.getResourceAsStream(ROOT + relative)) {
            if (in == null) {
                throw new IOException("Classpath-Ressource fehlt: " + ROOT + relative);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private List<Entry> scan() {
        long t0 = System.currentTimeMillis();
        List<Entry> out = new ArrayList<>();
        try {
            for (String id : index()) {
                try {
                    out.add(parse(id, read(id)));
                } catch (Exception ex) {
                    LOG.warn("Sample-Deck nicht lesbar: " + id + ": " + ex);
                }
            }
        } catch (IOException ex) {
            LOG.error("Sample-Decks nicht lesbar", ex);
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        LOG.info("Sample-Decks: " + out.size() + " in " + (System.currentTimeMillis() - t0) + " ms");
        return Collections.unmodifiableList(out);
    }

    /** Textuelles Lesen des v2-Texts: Commander-Abschnitt, Kartenzahl, Name aus {@code NAME:} oder Dateiname. */
    static Entry parse(String id, String text) {
        String name = null;
        List<String> commanders = new ArrayList<>();
        String cmdSet = null;
        String cmdNum = null;
        int count = 0;
        boolean inCommander = false;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.strip();
            if (line.startsWith("NAME:")) {
                name = line.substring(5).strip();
                continue;
            }
            if (line.equalsIgnoreCase("Commander")) {
                inCommander = true;
                continue;
            }
            if (line.equalsIgnoreCase("Deck")) {
                inCommander = false;
                continue;
            }
            Matcher m = CARD_LINE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            int n = Integer.parseInt(m.group(1));
            count += n;
            if (inCommander) {
                commanders.add(m.group(2));
                if (cmdSet == null && m.group(3) != null) {
                    cmdSet = m.group(3).toUpperCase();
                    cmdNum = m.group(4);
                }
            }
        }
        int slash = id.lastIndexOf('/');
        String file = id.substring(slash + 1);
        if (name == null || name.isBlank()) {
            name = file.toLowerCase().endsWith(".dck") ? file.substring(0, file.length() - 4) : file;
        }
        String group = slash < 0 ? "" : id.substring(0, slash);
        return new Entry(id, name, group, commanders, colorsOf(commanders), cmdSet, cmdNum, count);
    }

    /** Farbidentitaet der Commander als "WUBRG"-Teilmenge. */
    public static String colorsOf(List<String> commanderNames) {
        return CardLookup.colors(commanderNames);
    }
}
