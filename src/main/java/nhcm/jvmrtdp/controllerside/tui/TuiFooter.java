package nhcm.jvmrtdp.controllerside.tui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Width-aware footer pagination. No shortcut is silently lost off the right edge. */
final class TuiFooter {
    private TuiFooter() { }

    static String page(List<String> tokens, int width, int requestedPage) {
        if (width <= 0) return "";
        int contentWidth = Math.max(1, width - 9); // room for "[999/999] "
        List<String> pages = paginate(tokens, contentWidth);
        int index = Math.floorMod(requestedPage, pages.size());
        String prefix = pages.size() == 1 ? "" : "[" + (index + 1) + "/" + pages.size() + "] ";
        return TerminalScreen.crop(prefix + pages.get(index), width);
    }

    static List<String> paginate(List<String> tokens, int width) {
        if (tokens == null || tokens.isEmpty()) return Collections.singletonList("");
        int limit = Math.max(1, width);
        List<String> pages = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        for (String original : tokens) {
            String token = original == null ? "" : original.trim();
            if (token.isEmpty()) continue;
            int offset = 0;
            while (offset < token.length()) {
                int length = Math.min(limit, token.length() - offset);
                String part = token.substring(offset, offset + length);
                int separator = current.length() == 0 ? 0 : 2;
                if (current.length() + separator + part.length() > limit) {
                    pages.add(current.toString());
                    current.setLength(0);
                }
                if (current.length() > 0) current.append("  ");
                current.append(part);
                offset += length;
                if (offset < token.length()) {
                    pages.add(current.toString());
                    current.setLength(0);
                }
            }
        }
        if (current.length() > 0) pages.add(current.toString());
        if (pages.isEmpty()) pages.add("");
        return Collections.unmodifiableList(pages);
    }

    /** Returns the visible shortcut rows, prioritizing earlier context-specific tokens. */
    static List<String> rows(List<String> tokens, int width, int maxRows) {
        List<String> pages = paginate(tokens, width);
        int count = Math.max(0, Math.min(Math.max(1, maxRows), pages.size()));
        List<String> result = new ArrayList<String>(count);
        for (int index = 0; index < count; index++) result.add(pages.get(index));
        if (pages.size() > count && !result.isEmpty()) {
            int last = result.size() - 1;
            result.set(last, TerminalScreen.crop(result.get(last) + "  ...", width));
        }
        return Collections.unmodifiableList(result);
    }

    /** Returns every wrapped shortcut row. The normal TUI footer uses this so discoverability
     * is never replaced by an ambiguous trailing ellipsis on terminals with usable height. */
    static List<String> allRows(List<String> tokens, int width) {
        return paginate(tokens, width);
    }

    /**
     * Wraps a status/input message by terminal display cells. Unlike shortcut pagination, this
     * prefers whitespace boundaries and returns every row so the status area can grow vertically.
     */
    static List<String> statusRows(String value, int width, int maxRows, int requestedPage) {
        int availableRows = Math.max(1, maxRows);
        List<String> rows = wrapText(value, width);
        if (rows.size() <= availableRows) return rows;

        // The terminal is too short to display the complete message. Retain chunk paging as a
        // last-resort fallback, reserving room for a visible page marker without cropping text.
        int markerWidth = Math.min(12, Math.max(0, width - 1));
        int contentWidth = Math.max(1, width - markerWidth);
        rows = wrapText(value, contentWidth);
        int pageCount = Math.max(1, (rows.size() + availableRows - 1) / availableRows);
        int page = Math.floorMod(requestedPage, pageCount);
        int start = page * availableRows;
        int end = Math.min(rows.size(), start + availableRows);
        String marker = "[" + (page + 1) + "/" + pageCount + "] ";
        String continuation = spaces(marker.length());
        List<String> result = new ArrayList<String>(end - start);
        for (int index = start; index < end; index++) {
            result.add((index == start ? marker : continuation) + rows.get(index));
        }
        return Collections.unmodifiableList(result);
    }

    static List<String> wrapText(String value, int width) {
        int limit = Math.max(1, width);
        String normalized = TuiViewport.expandTabs(value == null ? "" : value)
                .replace("\r\n", "\n").replace('\r', '\n');
        List<String> result = new ArrayList<String>();
        String[] paragraphs = normalized.split("\n", -1);
        for (String paragraph : paragraphs) wrapParagraph(paragraph, limit, result);
        if (result.isEmpty()) result.add("");
        return Collections.unmodifiableList(result);
    }

    private static void wrapParagraph(String paragraph, int width, List<String> output) {
        if (paragraph.isEmpty()) {
            output.add("");
            return;
        }
        String remaining = paragraph;
        while (TerminalScreen.displayWidth(remaining) > width) {
            int hardEnd = prefixEnd(remaining, width);
            int breakAt = lastWhitespace(remaining, hardEnd);
            if (breakAt <= 0) breakAt = hardEnd;
            if (breakAt <= 0) breakAt = Character.charCount(remaining.codePointAt(0));
            String row = trimTrailingWhitespace(remaining.substring(0, breakAt));
            output.add(row);
            remaining = trimLeadingWhitespace(remaining.substring(breakAt));
        }
        if (!remaining.isEmpty()) output.add(remaining);
    }

    private static int prefixEnd(String value, int maximumWidth) {
        int width = 0;
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            int cells = TerminalScreen.displayWidth(new String(Character.toChars(codePoint)));
            if (width + cells > maximumWidth) break;
            width += cells;
            index += Character.charCount(codePoint);
        }
        return index;
    }

    private static int lastWhitespace(String value, int end) {
        int result = -1;
        for (int index = 0; index < end;) {
            int codePoint = value.codePointAt(index);
            if (Character.isWhitespace(codePoint)) result = index;
            index += Character.charCount(codePoint);
        }
        return result;
    }

    private static String trimLeadingWhitespace(String value) {
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            if (!Character.isWhitespace(codePoint)) break;
            index += Character.charCount(codePoint);
        }
        return value.substring(index);
    }

    private static String trimTrailingWhitespace(String value) {
        int index = value.length();
        while (index > 0) {
            int codePoint = value.codePointBefore(index);
            if (!Character.isWhitespace(codePoint)) break;
            index -= Character.charCount(codePoint);
        }
        return value.substring(0, index);
    }

    private static String spaces(int count) {
        StringBuilder result = new StringBuilder(Math.max(0, count));
        for (int index = 0; index < count; index++) result.append(' ');
        return result.toString();
    }
}
