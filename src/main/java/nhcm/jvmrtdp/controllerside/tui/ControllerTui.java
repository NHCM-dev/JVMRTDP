package nhcm.jvmrtdp.controllerside.tui;

import nhcm.jvmrtdp.BuildInfo;
import nhcm.jvmrtdp.JVMProcess;
import nhcm.jvmrtdp.JVMRTDP;
import nhcm.jvmrtdp.handles.ServerHandle;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Default full-screen process selector. */
public final class ControllerTui {
    private final JVMRTDP controller;
    private final BufferedReader input;
    private final PrintStream output;
    private final PrintStream error;
    private String status = "Enter attaches; C switches to CLI";

    public ControllerTui(JVMRTDP controller, BufferedReader input, PrintStream output, PrintStream error) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        this.error = Objects.requireNonNull(error, "error");
    }

    /** Returns true when the user requested the controller CLI, false when exiting. */
    public boolean run() {
        while (!Thread.currentThread().isInterrupted()) {
            Selection selection;
            try (TerminalScreen screen = TerminalScreen.open()) {
                selection = selectProcess(screen);
            } catch (IOException | RuntimeException failure) {
                error.println("TUI unavailable; using CLI: " + failure.getMessage());
                return true;
            }
            if (selection.cli) return true;
            if (selection.exit) return false;
            try (ServerHandle handle = selection.handle) {
                output.printf("Connected to JVM %d (%s), session %s%n",
                        handle.process().pid(), handle.targetDisplayName(), handle.sessionId());
                boolean back = new TargetSessionCoordinator(input, output, error).run(handle, true);
                if (!back) return false;
                status = "Detached from PID " + handle.process().pid();
            } catch (RuntimeException failure) {
                status = "Attach failed: " + rootMessage(failure);
            }
        }
        return false;
    }

    private Selection selectProcess(TerminalScreen screen) throws IOException {
        SelectorState state = new SelectorState();
        try (TuiTaskRunner tasks = new TuiTaskRunner("jvmrtdp-process-worker")) {
            requestProcessRefresh(tasks, state);
            while (true) {
                tasks.poll();
                if (state.attached != null) return Selection.handle(state.attached);

                int width = Math.max(1, screen.width() - 1);
                int height = screen.height();
                boolean columnsVisible = height >= 5;
                boolean statusVisible = height >= 3;
                boolean helpVisible = height >= 7;
                int headerRows = 1 + (columnsVisible ? 1 : 0);
                String activity = tasks.activity();
                String message = activity.isEmpty() ? status : activity;
                int helpRows = helpVisible ? 1 : 0;
                int maximumStatusRows = Math.max(1, height - headerRows - helpRows - 1);
                List<String> statusRows = statusVisible
                        ? TuiFooter.statusRows(" " + message, width, maximumStatusRows, 0)
                        : Collections.<String>emptyList();
                int footerRows = statusRows.size() + helpRows;
                int body = Math.max(0, height - headerRows - footerRows);
                state.selected = clamp(state.selected, 0, Math.max(0, state.processes.size() - 1));
                if (state.selected < state.scroll) state.scroll = state.selected;
                if (state.selected >= state.scroll + body) state.scroll = state.selected - body + 1;
                List<String> lines = new ArrayList<String>();
                lines.add(TerminalScreen.REVERSE + TerminalScreen.pad(
                        " JVMRTDP " + BuildInfo.VERSION + " | Target JVM Explorer ", width) + TerminalScreen.RESET);
                if (columnsVisible) {
                    String columns = width < 85
                            ? String.format(" %8s  %-16s %s", "PID", "PROCESS", "JAVA MAIN / WINDOW")
                            : String.format(" %8s  %-10s %-16s %-12s %s", "PID", "ARCH", "PROCESS", "UPTIME", "JAVA MAIN / WINDOW");
                    lines.add(TerminalScreen.BOLD + TerminalScreen.pad(columns, width) + TerminalScreen.RESET);
                }
                for (int row = 0; row < body; row++) {
                    int index = state.scroll + row;
                    String text = "";
                    if (index < state.processes.size()) {
                        JVMProcess process = state.processes.get(index);
                        text = width < 85
                                ? String.format(" %8d  %-16s %s", process.pid(), process.executableName(), process.displayName())
                                : String.format(" %8d  %-10s %-16s %-12s %s",
                                        process.pid(), process.architecture(), process.executableName(),
                                        duration(process.uptime()), process.displayName());
                    }
                    String rendered = TerminalScreen.pad(text, width);
                    lines.add(index == state.selected && index < state.processes.size()
                            ? TerminalScreen.REVERSE + rendered + TerminalScreen.RESET : rendered);
                }
                for (String statusRow : statusRows) {
                    lines.add(TerminalScreen.REVERSE
                            + TerminalScreen.pad(statusRow, width) + TerminalScreen.RESET);
                }
                if (helpVisible) {
                    String help = width < 65 ? "Up/Down select  Enter attach  R refresh  Q quit"
                            : "Up/Down select  Enter attach  R refresh  C/F2 CLI  Q/F10 quit";
                    lines.add(TerminalScreen.pad(help, width));
                }
                screen.draw(lines);
                int key = screen.readKey(90L);
                if (key == TuiKey.NONE) continue;
                if (key == TuiKey.EOF) return Selection.exit();
                if (key == TuiKey.UP) state.selected--;
                else if (key == TuiKey.DOWN) state.selected++;
                else if (key == TuiKey.ENTER && !state.processes.isEmpty() && !tasks.busy()) {
                    JVMProcess process = state.processes.get(state.selected);
                    status = "Attaching to PID " + process.pid() + " ...";
                    tasks.submit("Attaching to PID " + process.pid() + " ...",
                            () -> controller.inject(process.pid()),
                            handle -> state.attached = handle,
                            failure -> status = "Attach failed: " + rootMessage(failure));
                } else if ((key == 'r' || key == 'R' || key == TuiKey.F5) && !tasks.busy()) {
                    requestProcessRefresh(tasks, state);
                } else if ((key == 'c' || key == 'C' || key == TuiKey.F2) && !tasks.busy()) {
                    return Selection.cli();
                } else if ((key == 'q' || key == 'Q' || key == TuiKey.F10) && !tasks.busy()) {
                    return Selection.exit();
                }
            }
        }
    }

    private void requestProcessRefresh(TuiTaskRunner tasks, SelectorState state) {
        status = "Scanning JVM processes ...";
        tasks.submit("Scanning JVM processes ...", this::loadProcesses,
                processes -> {
                    state.processes = processes;
                    state.selected = clamp(state.selected, 0, Math.max(0, processes.size() - 1));
                    status = "Found " + processes.size() + " JVM process(es)";
                },
                failure -> status = "Process scan failed: " + rootMessage(failure));
    }

    private List<JVMProcess> loadProcesses() {
        List<JVMProcess> result = new ArrayList<JVMProcess>(controller.getProcesses());
        Collections.sort(result, Comparator.comparingLong(JVMProcess::pid));
        return result;
    }

    private static String duration(Duration value) {
        long seconds = Math.max(0, value.getSeconds());
        long hours = seconds / 3600;
        return String.format("%02d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60);
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    private static final class Selection {
        private final ServerHandle handle;
        private final boolean cli;
        private final boolean exit;
        private Selection(ServerHandle handle, boolean cli, boolean exit) {
            this.handle = handle; this.cli = cli; this.exit = exit;
        }
        private static Selection handle(ServerHandle value) { return new Selection(value, false, false); }
        private static Selection cli() { return new Selection(null, true, false); }
        private static Selection exit() { return new Selection(null, false, true); }
    }

    private static final class SelectorState {
        private List<JVMProcess> processes = Collections.emptyList();
        private int selected;
        private int scroll;
        private ServerHandle attached;
    }
}
