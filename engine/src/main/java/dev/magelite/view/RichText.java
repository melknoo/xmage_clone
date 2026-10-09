package dev.magelite.view;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wandelt Engine-HTML (Log, Prompts) in sichere Segmente um. Die UI rendert nie rohes HTML.
 * Segment: {@code {text}} | {@code {obj, text, color}} | {@code {br:true}}. Mana-Symbole wie {G} bleiben im Text.
 */
public final class RichText {

    private RichText() {
    }

    public static List<Map<String, Object>> parse(String html) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (html == null || html.isEmpty()) {
            return out;
        }
        if (html.indexOf('<') < 0 && html.indexOf('&') < 0) {
            out.add(text(html));
            return out;
        }
        Element body = Jsoup.parseBodyFragment(html).body();
        walk(body, out, null);
        return merge(out);
    }

    public static String plain(String html) {
        if (html == null) {
            return "";
        }
        if (html.indexOf('<') < 0 && html.indexOf('&') < 0) {
            return html;
        }
        return Jsoup.parseBodyFragment(html.replace("<br>", "\n").replace("<br/>", "\n")).body().wholeText().trim();
    }

    private static void walk(Node node, List<Map<String, Object>> out, String color) {
        for (Node child : node.childNodes()) {
            if (child instanceof TextNode tn) {
                String t = tn.getWholeText();
                if (!t.isEmpty()) {
                    Map<String, Object> seg = text(t);
                    if (color != null) {
                        seg.put("color", color);
                    }
                    out.add(seg);
                }
            } else if (child instanceof Element el) {
                String tag = el.normalName();
                if ("br".equals(tag)) {
                    Map<String, Object> br = new LinkedHashMap<>();
                    br.put("br", true);
                    out.add(br);
                    continue;
                }
                String objectId = el.attr("object_id");
                String c = el.hasAttr("color") ? el.attr("color") : color;
                if (!objectId.isEmpty()) {
                    Map<String, Object> seg = new LinkedHashMap<>();
                    seg.put("obj", objectId);
                    seg.put("text", el.text());
                    if (c != null && !c.isEmpty()) {
                        seg.put("color", c);
                    }
                    out.add(seg);
                } else {
                    if ("i".equals(tag) || "em".equals(tag)) {
                        List<Map<String, Object>> inner = new ArrayList<>();
                        walk(el, inner, c);
                        inner.forEach(s -> s.put("i", true));
                        out.addAll(inner);
                    } else if ("b".equals(tag) || "strong".equals(tag)) {
                        List<Map<String, Object>> inner = new ArrayList<>();
                        walk(el, inner, c);
                        inner.forEach(s -> s.put("b", true));
                        out.addAll(inner);
                    } else {
                        walk(el, out, c);
                    }
                }
            }
        }
    }

    private static Map<String, Object> text(String t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", t);
        return m;
    }

    /**
     * Fasst aufeinanderfolgende reine Textsegmente gleichen Stils zusammen.
     */
    private static List<Map<String, Object>> merge(List<Map<String, Object>> in) {
        List<Map<String, Object>> out = new ArrayList<>(in.size());
        for (Map<String, Object> seg : in) {
            if (!out.isEmpty()) {
                Map<String, Object> last = out.get(out.size() - 1);
                if (last.size() == seg.size() && last.containsKey("text") && seg.containsKey("text")
                        && !last.containsKey("obj") && !seg.containsKey("obj")
                        && sameStyle(last, seg)) {
                    last.put("text", last.get("text") + (String) seg.get("text"));
                    continue;
                }
            }
            out.add(seg);
        }
        return out;
    }

    private static boolean sameStyle(Map<String, Object> a, Map<String, Object> b) {
        return java.util.Objects.equals(a.get("color"), b.get("color"))
                && java.util.Objects.equals(a.get("i"), b.get("i"))
                && java.util.Objects.equals(a.get("b"), b.get("b"));
    }
}
