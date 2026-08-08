package nhcm.jvmrtdp.localside;

import nhcm.jvmrtdp.JVMProcess;
import nhcm.jvmrtdp.JVMRTDP;
import nhcm.jvmrtdp.command.CommandLine;
import nhcm.jvmrtdp.handles.ServerHandle;
import nhcm.jvmrtdp.localside.command.ShellCommand;
import nhcm.jvmrtdp.localside.command.ShellCommandRegistry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** The local, cmd-like JVMRTDP controller prompt. */
public final class ControllerShell {
    private final JVMRTDP controller;
    private final BufferedReader input;
    private final PrintStream output;
    private final PrintStream error;
    private final ShellCommandRegistry<ControllerShell> commands =
            new ShellCommandRegistry<ControllerShell>();

    public ControllerShell(JVMRTDP controller, InputStream input, PrintStream output, PrintStream error) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.input = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(input, "input"), StandardCharsets.UTF_8));
        this.output = Objects.requireNonNull(output, "output");
        this.error = Objects.requireNonNull(error, "error");
        commands.register(new HelpCommand());
        commands.register(new PsCommand());
        commands.register(new AttachCommand());
        commands.register(new ExitCommand());
    }

    public void run() {
        output.println("JVMRTDP controller. Type 'help' for commands.");
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
        output.printf("%8s  %-8s  %s%n", "PID", "ARCH", "PROCESS / MAIN");
        for (JVMProcess process : processes) {
            output.printf("%8d  %-8s  %s%n",
                    process.pid(), process.architecture(), process.displayName());
        }
    }

    public void attach(long pid) {
        try (ServerHandle handle = controller.inject(pid)) {
            output.printf("Connected to JVM %d (%s), session %s%n",
                    handle.process().pid(), handle.targetDisplayName(), handle.sessionId());
            new InteractiveCli(input, output, error).run(handle);
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

    private static final class HelpCommand extends ShellCommand<ControllerShell> {
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
                    shell.output.printf("%-22s %s%n", command.usage(), command.description());
                }
            }
            return true;
        }
    }

    private static final class PsCommand extends ShellCommand<ControllerShell> {
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

    private static final class AttachCommand extends ShellCommand<ControllerShell> {
        private AttachCommand() {
            super("attach", "attach <pid>", "Injects the agent and opens a target prompt.", "inject");
        }

        @Override
        public boolean execute(ControllerShell shell, List<String> arguments) {
            if (arguments.size() != 1) {
                shell.error.println("Usage: " + usage());
            } else {
                shell.attach(parsePid(arguments.get(0)));
            }
            return true;
        }
    }

    private static final class ExitCommand extends ShellCommand<ControllerShell> {
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
}
