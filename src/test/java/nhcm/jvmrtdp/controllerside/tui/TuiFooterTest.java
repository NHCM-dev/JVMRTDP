package nhcm.jvmrtdp.controllerside.tui;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
