package nhcm.jvmrtdp.controllerside.tui;

import nhcm.jvmrtdp.controllerside.InteractiveCli;
import nhcm.jvmrtdp.controllerside.TargetSession;
import nhcm.jvmrtdp.handles.ServerHandle;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Objects;

/** Preserves one target context while switching between full-screen TUI and CLI. */
public final class TargetSessionCoordinator {
    private final BufferedReader input;
    private final PrintStream output;
    private final PrintStream error;

    public TargetSessionCoordinator(BufferedReader input, PrintStream output, PrintStream error) {
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        this.error = Objects.requireNonNull(error, "error");
    }

    /** Returns true to return to the controller, false to terminate it. */
    public boolean run(ServerHandle server, boolean startInTui) {
        TuiResult mode = startInTui ? TuiResult.TUI : TuiResult.CLI;
        try (TargetSession session = new TargetSession(server, output, error);
                TargetTui tui = new TargetTui(session)) {
            while (server.isOpen()) {
                if (mode == TuiResult.TUI) {
                    try (TerminalScreen screen = TerminalScreen.open()) {
                        mode = tui.run(screen);
                    } catch (IOException | RuntimeException failure) {
                        error.println("TUI unavailable, returning to CLI: " + failure.getMessage());
                        mode = TuiResult.CLI;
                    }
                } else if (mode == TuiResult.CLI) {
                    mode = new InteractiveCli(input, output, error).runSession(session);
                } else {
                    return mode == TuiResult.BACK;
                }
            }
            return true;
        }
    }
}
