package dev.magelite.deck;

import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.filter.FilterMana;
import mage.util.ManaUtil;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Katalog der mitgelieferten XMage-Commander-Decks (Bot-Decks). Liest .dck-Dateien textuell
 * (schnell, ohne Kartenobjekte zu erzeugen); Farben kommen aus der Karten-DB.
 */
public final class SampleDeckCatalog {

    private static final Logger LOG = Logger.getLogger(SampleDeckCatalog.class);
    private static final Pattern SB_LINE = Pattern.compile("^SB:\\s*(\\d+)\\s*\\[([^]:]+):([^]]+)]\\s*(.+?)\\s*$");
    private static final Pattern CARD_LINE = Pattern.compile("^(\\d+)\\s*\\[([^]:]+):([^]]+)]\\s*(.+?)\\s*$");

    public record Entry(String id, String name, String group, List<String> commanders, String colors,
                        String commanderSet, String commanderNum, int cards) {
    }

    private final Path root;
    private volatile List<Entry> entries;

    public SampleDeckCatalog(Path root) {
        this.root = root;
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

    public Path resolve(String id) {
        Path p = root.resolve(id).normalize();
        if (!p.startsWith(root.normalize())) {
            throw new IllegalArgumentException("Ungueltige Deck-ID");
        }
        return p;
    }

    private List<Entry> scan() {
        long t0 = System.currentTimeMillis();
        List<Entry> out = new ArrayList<>();
        try {
            for (Path f : DeckLoader.listDeckFiles(root)) {
                if (!f.getFileName().toString().toLowerCase().endsWith(".dck")) {
                    continue;
                }
                try {
                    out.add(parse(f));
                } catch (Exception ex) {
                    LOG.warn("Sample-Deck nicht lesbar: " + f + ": " + ex);
                }
            }
        } catch (IOException ex) {
            LOG.error("Sample-Decks nicht lesbar", ex);
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        LOG.info("Sample-Decks: " + out.size() + " in " + (System.currentTimeMillis() - t0) + " ms");
        return Collections.unmodifiableList(out);
    }

    private Entry parse(Path f) throws IOException {
        String name = null;
        List<String> commanders = new ArrayList<>();
        String cmdSet = null;
        String cmdNum = null;
        int count = 0;
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            line = line.trim();
            if (line.startsWith("NAME:")) {
                name = line.substring(5).trim();
                continue;
            }
            Matcher sb = SB_LINE.matcher(line);
            if (sb.matches()) {
                commanders.add(sb.group(4));
                if (cmdSet == null) {
                    cmdSet = sb.group(2);
                    cmdNum = sb.group(3);
                }
                count += Integer.parseInt(sb.group(1));
                continue;
            }
            Matcher m = CARD_LINE.matcher(line);
            if (m.matches()) {
                count += Integer.parseInt(m.group(1));
            }
        }
        String file = f.getFileName().toString();
        if (name == null || name.isBlank()) {
            name = file.substring(0, file.length() - 4);
        }
        Path rel = root.relativize(f);
        String group = rel.getParent() == null ? "" : rel.getParent().toString().replace('\\', '/');
        return new Entry(rel.toString().replace('\\', '/'), name, group, commanders, colorsOf(commanders), cmdSet, cmdNum, count);
    }

    static String colorsOf(List<String> commanders) {
        boolean w = false, u = false, b = false, r = false, g = false;
        for (String c : commanders) {
            CardInfo info = CardRepository.instance.findCard(c);
            if (info == null) {
                continue;
            }
            FilterMana id = ManaUtil.getColorIdentity(info.getColor(),
                    String.join("", info.getManaCosts(CardInfo.ManaCostSide.ALL)), info.getRules(), null);
            w |= id.isWhite();
            u |= id.isBlue();
            b |= id.isBlack();
            r |= id.isRed();
            g |= id.isGreen();
        }
        return (w ? "W" : "") + (u ? "U" : "") + (b ? "B" : "") + (r ? "R" : "") + (g ? "G" : "");
    }
}
