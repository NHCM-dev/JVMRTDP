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
}
