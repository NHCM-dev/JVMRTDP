package nhcm.jvmrtdp.controllerside.tui;

import org.jline.utils.WCWidth;

/** Pure text viewport helpers shared by the source and disassembly panes. */
final class TuiViewport {
    private TuiViewport() {}

    static String horizontal(String value, int requestedOffset, int width) {
        if (width <= 0) return "";
        String text = expandTabs(value == null ? "" : value);
        int offset = Math.max(0, requestedOffset);
        int totalWidth = displayWidth(text);
        if (offset >= totalWidth) return offset > 0 ? "<" : "";
        if (width == 1) {
            if (offset > 0) return "<";
            if (totalWidth > 1) return ">";
            return text;
        }
        boolean left = offset > 0;
        int contentWidth = Math.max(0, width - (left ? 1 : 0));
        int start = indexAtOrAfterColumn(text, offset);
        int startColumn = displayWidth(text.substring(0, start));
        boolean right = totalWidth - startColumn > contentWidth;
        if (right) contentWidth = Math.max(0, contentWidth - 1);
        StringBuilder result = new StringBuilder(width);
        if (left) result.append('<');
        result.append(sliceColumns(text, start, contentWidth));
        if (right) result.append('>');
        return result.toString();
    }

    static int maximumWidth(Iterable<String> lines) {
        int result = 0;
        for (String line : lines) {
            result = Math.max(result, displayWidth(expandTabs(line == null ? "" : line)));
        }
        return result;
    }

    static String expandTabs(String value) {
        StringBuilder result = new StringBuilder(value.length());
        int column = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\t') {
                int spaces = 4 - column % 4;
                for (int count = 0; count < spaces; count++) result.append(' ');
                column += spaces;
            } else if (character == '\r') {
                result.append("\\r");
                column += 2;
            } else if (character == '\n') {
                result.append("\\n");
                column += 2;
            } else if (character == '\033') {
                result.append("\\e");
                column += 2;
            } else if (character < 0x20 || (character >= 0x7f && character <= 0x9f)
                    || character == '\u2028' || character == '\u2029') {
                String escaped = String.format(java.util.Locale.ROOT, "\\u%04x", (int) character);
                result.append(escaped);
                column += escaped.length();
            } else {
                int codePoint = value.codePointAt(index);
                result.appendCodePoint(codePoint);
                column += cellWidth(codePoint);
                index += Character.charCount(codePoint) - 1;
            }
        }
        return result.toString();
    }

    static int displayWidth(String value) {
        int width = 0;
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            width += cellWidth(codePoint);
            index += Character.charCount(codePoint);
        }
        return width;
    }

    private static int indexAtOrAfterColumn(String value, int requestedColumn) {
        int column = 0;
        int index = 0;
        while (index < value.length() && column < requestedColumn) {
            int codePoint = value.codePointAt(index);
            column += cellWidth(codePoint);
            index += Character.charCount(codePoint);
        }
        return index;
    }

    private static String sliceColumns(String value, int start, int maximumWidth) {
        StringBuilder result = new StringBuilder();
        int width = 0;
        for (int index = start; index < value.length();) {
            int codePoint = value.codePointAt(index);
            int codePointWidth = cellWidth(codePoint);
            if (width + codePointWidth > maximumWidth) break;
            result.appendCodePoint(codePoint);
            width += codePointWidth;
            index += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private static int cellWidth(int codePoint) {
        return Math.max(0, WCWidth.wcwidth(codePoint));
    }
}
