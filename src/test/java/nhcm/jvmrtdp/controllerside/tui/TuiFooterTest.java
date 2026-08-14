package nhcm.jvmrtdp.controllerside.tui;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TuiFooterTest {
    @Test void allRowsRetainsEveryShortcutWithoutEllipsis() {
        List<String> tokens = Arrays.asList("F9 Break", "F7 Step", "F8 Run",
                "Ctrl+Left/Right Tab", "Shift+Tab View", "F2 CLI", "Q Back");

        List<String> rows = TuiFooter.allRows(tokens, 24);
        String joined = String.join("\n", rows);

        for (String token : tokens) assertTrue(joined.contains(token), token);
        assertFalse(joined.contains("..."));
    }

    @Test void longStatusGrowsAndWrapsByDisplayWidth() {
        String status = "String hook: allocation <name> <内容-glob> [class method descriptor]";

        List<String> rows = TuiFooter.statusRows(status, 28, 10, 0);

        assertTrue(rows.size() > 1);
        for (String row : rows) assertTrue(TerminalScreen.displayWidth(row) <= 28, row);
        String joined = String.join(" ", rows);
        assertTrue(joined.contains("String hook:"));
        assertTrue(joined.contains("<内容-glob>"));
        assertTrue(joined.contains("descriptor"));
        assertFalse(joined.startsWith("["));
    }

    @Test void statusUsesPagedChunksOnlyWhenTerminalHeightIsInsufficient() {
        String status = "one two three four five six seven eight nine ten eleven twelve";

        List<String> first = TuiFooter.statusRows(status, 18, 2, 0);
        List<String> second = TuiFooter.statusRows(status, 18, 2, 1);

        assertEquals(2, first.size());
        assertEquals(2, second.size());
        assertTrue(first.get(0).startsWith("[1/"));
        assertTrue(second.get(0).startsWith("[2/"));
        assertFalse(first.equals(second));
    }
}
