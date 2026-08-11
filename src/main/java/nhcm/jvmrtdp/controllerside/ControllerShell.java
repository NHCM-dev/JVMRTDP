package nhcm.jvmrtdp.controllerside;

import nhcm.jvmrtdp.JVMProcess;
import nhcm.jvmrtdp.JVMRTDP;
import nhcm.jvmrtdp.BuildInfo;
import nhcm.jvmrtdp.command.CommandLine;
import nhcm.jvmrtdp.handles.ServerHandle;
import nhcm.jvmrtdp.controllerside.command.ShellCommand;
import nhcm.jvmrtdp.controllerside.command.ShellCommandRegistry;
import nhcm.jvmrtdp.controllerside.tui.ControllerTui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** The local, cmd-like JVMRTDP controller prompt. */
public class ControllerShell {
    private final JVMRTDP controller;
    private final BufferedReader input;
    private final PrintStream output;
    private final PrintStream error;
    private final ShellCommandRegistry<ControllerShell> commands =
            new ShellCommandRegistry<ControllerShell>();

    public ControllerShell(JVMRTDP controller, InputStream input, PrintStream output, PrintStream error) {
        this(new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(input, "input"), StandardCharsets.UTF_8)),
                controller, output, error);
    }

    public ControllerShell(BufferedReader input, JVMRTDP controller, PrintStream output, PrintStream error) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        this.error = Objects.requireNonNull(error, "error");
        commands.register(new HelpCommand());
        commands.register(new PsCommand());
        commands.register(new AttachCommand());
        commands.register(new VersionCommand());
        commands.register(new TuiCommand());
        commands.register(new ExitCommand());
    }

    public void run() {
        StartupBanner.printTo(output);
        output.println("Type 'help' for commands.");
        while (!Thread.currentThread().isInterrupted()) {
            output.print("jvmrtdp> ");
            output.flush();
            String line;
            try {
                line = input.readLine();
            } catch (IOException exception) {
                error.println("Cannot read command: " + exception.getMessage());
                return;
            }
            if (line == null || !execute(line)) {
                return;
            }
        }
    }

    public void listProcesses() {
        List<JVMProcess> processes = controller.getProcesses();
        if (processes.isEmpty()) {
            output.println("No accessible JVM processes found.");
            return;
        }
        output.printf("%8s  %-8s  %-16s  %-12s  %-32s  %s%n",
                "PID", "ARCH", "TASK MANAGER", "UPTIME", "WINDOW TITLE", "JAVA MAIN / JAR");
        for (JVMProcess process : processes) {
            output.printf("%8d  %-8s  %-16s  %-12s  %-32s  %s%n",
                    process.pid(), process.architecture(), process.executableName(),
                    formatDuration(process.uptime()), empty(process.windowTitle()), process.displayName());
        }
    }

    /** Returns true to continue the controller prompt, false when the target prompt requested exit. */
    public boolean attach(long pid) {
        try (ServerHandle handle = controller.inject(pid)) {
            output.printf("Connected to JVM %d (%s), session %s%n",
                    handle.process().pid(), handle.targetDisplayName(), handle.sessionId());
            return new InteractiveCli(input, output, error).run(handle);
        }
    }

    private boolean execute(String rawLine) {
        try {
            CommandLine line = CommandLine.parse(rawLine);
            if (line.name().isEmpty()) {
                return true;
            }
            ShellCommand<ControllerShell> command = commands.find(line.name());
            if (command == null) {
                error.println("Unknown command: " + line.name() + ". Use 'help'.");
                return true;
            }
            return command.execute(this, line.arguments());
        } catch (Exception exception) {
            error.println("Command failed: " + exception.getMessage());
            return true;
        }
    }

    private static long parsePid(String value) {
        try {
            long pid = Long.parseLong(value);
            if (pid <= 0) {
                throw new NumberFormatException();
            }
            return pid;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid PID: " + value);
        }
    }

    private static String formatDuration(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3_600;
        long minutes = (seconds % 3_600) / 60;
        long remainingSeconds = seconds % 60;
        return days == 0
                ? String.format("%02d:%02d:%02d", hours, minutes, remainingSeconds)
                : String.format("%dd %02d:%02d", days, hours, minutes);
    }

    private static String empty(String value) {
        return value == null || value.trim().isEmpty() ? "<no visible window>" : value;
    }

    private static class HelpCommand extends ShellCommand<ControllerShell> {
        private HelpCommand() {
            super("help", "help [command]", "Lists local controller commands.", "?");
        }

        @Override
        public boolean execute(ControllerShell shell, List<String> arguments) {
            if (arguments.size() > 1) {
                shell.error.println("Usage: " + usage());
            } else if (arguments.size() == 1) {
                ShellCommand<ControllerShell> command = shell.commands.find(arguments.get(0));
                if (command == null) {
                    shell.error.println("Unknown command: " + arguments.get(0));
                } else {
                    shell.output.println("Usage: " + command.usage());
                    shell.output.println(command.description());
                }
            } else {
                for (ShellCommand<ControllerShell> command : shell.commands.commands()) {
                    shell.output.printf("%-14s %s%n", command.name(), command.description());
                }
            }
            return true;
        }
    }

    private static class PsCommand extends ShellCommand<ControllerShell> {
        private PsCommand() {
            super("ps", "ps", "Lists JVMs with PID, architecture and main entry point.", "list");
        }

        @Override
        public boolean execute(ControllerShell shell, List<String> arguments) {
            if (!arguments.isEmpty()) {
                shell.error.println("Usage: " + usage());
            } else {
                shell.listProcesses();
            }
            return true;
        }
    }

    private static class AttachCommand extends ShellCommand<ControllerShell> {
        private AttachCommand() {
            super("attach", "attach <pid>", "Injects the agent and opens a target prompt.", "inject");
        }

        @Override
        public boolean execute(ControllerShell shell, List<String> arguments) {
            if (arguments.size() != 1) {
                shell.error.println("Usage: " + usage());
            } else {
                return shell.attach(parsePid(arguments.get(0)));
            }
            return true;
        }
    }

    private static class ExitCommand extends ShellCommand<ControllerShell> {
        private ExitCommand() {
            super("exit", "exit", "Closes the controller.", "quit");
        }

        @Override
        public boolean execute(ControllerShell shell, List<String> arguments) {
            if (!arguments.isEmpty()) {
                shell.error.println("Usage: " + usage());
                return true;
            }
            return false;
        }
    }

    private static class VersionCommand extends ShellCommand<ControllerShell> {
        private VersionCommand() {
            super("version", "version", "Prints the JVMRTDP build version.", "ver");
        }

        @Override
        public boolean execute(ControllerShell shell, List<String> arguments) {
            if (!arguments.isEmpty()) {
                shell.error.println("Usage: " + usage());
            } else {
                shell.output.println(BuildInfo.displayVersion());
            }
            return true;
        }
    }

    private static class TuiCommand extends ShellCommand<ControllerShell> {
        private TuiCommand() {
            super("tui", "tui", "Switches to the full-screen process explorer.");
        }

        @Override
        public boolean execute(ControllerShell shell, List<String> arguments) {
            if (!arguments.isEmpty()) {
                shell.error.println("Usage: " + usage());
                return true;
            }
            return new ControllerTui(shell.controller, shell.input, shell.output, shell.error).run();
        }
    }
}
