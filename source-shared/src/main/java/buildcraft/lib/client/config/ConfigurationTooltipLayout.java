package buildcraft.lib.client.config;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/** Loader-independent, width-bounded tooltip paragraphs and labelled values. */
final class ConfigurationTooltipLayout {
    static final int LINE_HEIGHT = 11;
    static final int MUTED = 0xFFAAAAAA, BODY = 0xFFDDDDDD, HEADING = 0xFFFFFFFF;
    record Line(String prefix, int prefixColor, String text, int color, int indent, int gap) {
        int height() { return LINE_HEIGHT + gap; }
    }

    private final int width;
    private final ToIntFunction<String> measure;
    private final List<Line> lines = new ArrayList<>();

    ConfigurationTooltipLayout(int width, ToIntFunction<String> measure) {
        this.width = Math.max(1, width);
        this.measure = measure;
    }

    List<Line> lines() { return List.copyOf(lines); }
    void paragraph(String text, int color, int indent, int gap) {
        int left = Math.min(Math.max(0, indent), Math.max(0, width - 12));
        for (String paragraph : text.split("\\R", -1)) {
            String rest = paragraph.strip();
            if (rest.isEmpty()) { gap = Math.max(gap, 4); continue; }
            while (!rest.isEmpty()) {
                int end = lineEnd(rest, width - left, measure);
                lines.add(new Line("", color, rest.substring(0, end).stripTrailing(), color, left, gap));
                gap = 0;
                rest = rest.substring(end).stripLeading();
            }
        }
    }

    /** The first line has an independently colored label; continuation lines are indented. */
    void labelled(String label, int labelColor, String text, int color, int gap) {
        String prefix = label + ": ";
        String[] paragraphs = text.strip().split("\\R", 2);
        String rest = paragraphs[0];
        int available = width - measure.applyAsInt(prefix);
        int firstWord = 0;
        while (firstWord < rest.length() && !Character.isWhitespace(rest.codePointAt(firstWord))) {
            firstWord += Character.charCount(rest.codePointAt(firstWord));
        }
        if (rest.isEmpty() || available <= 0 || measure.applyAsInt(rest.substring(0, firstWord)) > available) {
            paragraph(label + ":", labelColor, 0, gap);
            paragraph(rest, color, 8, 0);
            if (paragraphs.length > 1) paragraph(paragraphs[1], color, 8, 4);
            return;
        }
        int end = lineEnd(rest, available, measure);
        lines.add(new Line(prefix, labelColor, rest.substring(0, end).stripTrailing(), color, 0, gap));
        paragraph(rest.substring(end), color, 8, 0);
        if (paragraphs.length > 1) paragraph(paragraphs[1], color, 8, 4);
    }

    /** Prefer whitespace boundaries; split by code point only for an overlong word. */
    static int lineEnd(String text, int width, ToIntFunction<String> measure) {
        int fit = 0, lastBreak = 0;
        while (fit < text.length()) {
            int cp = text.codePointAt(fit);
            if (Character.isWhitespace(cp)) lastBreak = fit;
            int next = fit + Character.charCount(cp);
            if (measure.applyAsInt(text.substring(0, next)) > width) break;
            fit = next;
        }
        if (fit == text.length()) return fit;
        if (lastBreak > 0) return lastBreak;
        return fit > 0 ? fit : Character.charCount(text.codePointAt(0));
    }

    static int height(List<Line> lines) { return lines.stream().mapToInt(Line::height).sum(); }

    record Window(List<Line> lines, int start, int maxStart) {}

    /** Modal dialogs must keep every warning reachable rather than truncate long translations. */
    static Window window(List<Line> lines, int requestedStart, int maxHeight) {
        int maxStart=lines.size(), used=0;
        while(maxStart>0 && used+lines.get(maxStart-1).height()<=maxHeight)
            used+=lines.get(--maxStart).height();
        if(lines.isEmpty()) return new Window(List.of(),0,0);
        maxStart=Math.min(maxStart,lines.size()-1);
        int start=Math.max(0,Math.min(requestedStart,maxStart)), end=start;
        used=0;
        while(end<lines.size() && used+lines.get(end).height()<=maxHeight)
            used+=lines.get(end++).height();
        return new Window(List.copyOf(lines.subList(start,end)),start,maxStart);
    }

    /** Keep the technical footer visible on smaller GUIs; truncate prose only if necessary. */
    static List<Line> fit(List<Line> header, List<Line> body, List<Line> footer, int maxHeight) {
        List<Line> result = new ArrayList<>(header);
        int remaining = maxHeight - height(header) - height(footer);
        if (height(body) <= remaining) result.addAll(body);
        else if (remaining >= LINE_HEIGHT) {
            remaining -= LINE_HEIGHT;
            for (Line line : body) {
                if (line.height() > remaining) break;
                result.add(line);
                remaining -= line.height();
            }
            result.add(new Line("", MUTED, "...", MUTED, 0, 0));
        }
        result.addAll(footer);
        // At exceptionally small GUI sizes even the header/footer may not fit.
        while (!result.isEmpty() && height(result) > maxHeight) result.remove(result.size() - 1);
        return List.copyOf(result);
    }
}
