package nhcm.jvmrtdp.localside;

import nhcm.jvmrtdp.command.CommandLine;
import nhcm.jvmrtdp.handles.ServerHandle;
import nhcm.jvmrtdp.handles.java.RemoteClass;
import nhcm.jvmrtdp.handles.java.RemoteField;
import nhcm.jvmrtdp.handles.java.RemoteMethod;
import nhcm.jvmrtdp.localside.command.ShellCommand;
import nhcm.jvmrtdp.localside.command.ShellCommandRegistry;
import nhcm.jvmrtdp.protocol.CommandReply;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;

/** Interactive controller-side prompt for one authenticated target-JVM session. */
public final class InteractiveCli {
    private final BufferedReader input;
    private final PrintStream output;
    private final PrintStream error;
    private final ShellCommandRegistry<TargetSession> commands = new ShellCommandRegistry<TargetSession>();

    public InteractiveCli(InputStream input, PrintStream output, PrintStream error) {
        this(new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(input, "input"), StandardCharsets.UTF_8)), output, error);
    }

    public InteractiveCli(BufferedReader input, PrintStream output, PrintStream error) {
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        this.error = Objects.requireNonNull(error, "error");
        registerCommands();
    }

    public void run(ServerHandle server) {
        TargetSession session = new TargetSession(server, output, error);
        output.println("Target prompt: help lists commands; dumpclass always writes a .class file; back disconnects.");
        while (server.isOpen() && !Thread.currentThread().isInterrupted()) {
            output.printf("target[%d]> ", server.process().pid());
            output.flush();
            String rawLine;
            try {
                rawLine = input.readLine();
            } catch (IOException exception) {
                error.println("Cannot read command: " + exception.getMessage());
                return;
            }
            if (rawLine == null || !execute(session, rawLine)) {
                return;
            }
        }
    }

    private boolean execute(TargetSession session, String rawLine) {
        try {
            CommandLine line = CommandLine.parse(rawLine);
            if (line.name().isEmpty()) {
                return true;
            }
            ShellCommand<TargetSession> command = commands.find(line.name());
            if (command == null) {
                error.println("Unknown target command: " + line.name() + ". Use 'help'.");
                return true;
            }
            return command.execute(session, line.arguments());
        } catch (Exception exception) {
            error.println("Command failed: " + exception.getMessage());
            return session.server().isOpen();
        }
    }

    private void registerCommands() {
        commands.register(new HelpCommand(commands));
        commands.register(new ForwardCommand(
                "ping", "ping", "Checks that the target command loop is responsive.", false));
        commands.register(new ForwardCommand(
                "info", "info", "Shows target JVM and injected runtime information.", false));
        commands.register(new ForwardCommand(
                "echo", "echo [text ...]", "Returns text through the target transport.", true));
        commands.register(new ForwardCommand(
                "native", "native", "Shows the target JNI/JVMTI bridge status.", false));
        commands.register(new JniCommand());
        commands.register(new DumpClassCommand());
        commands.register(new BackCommand());
    }

    private static final class HelpCommand extends ShellCommand<TargetSession> {
        private final ShellCommandRegistry<TargetSession> commands;

        private HelpCommand(ShellCommandRegistry<TargetSession> commands) {
            super("help", "help [command]", "Lists controller-side target commands.", "?");
            this.commands = commands;
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) {
            if (arguments.size() > 1) {
                session.error().println("Usage: " + usage());
            } else if (arguments.size() == 1) {
                ShellCommand<TargetSession> command = commands.find(arguments.get(0));
                if (command == null) {
                    session.error().println("Unknown target command: " + arguments.get(0));
                } else {
                    session.output().println("Usage: " + command.usage());
                    session.output().println(command.description());
                }
            } else {
                for (ShellCommand<TargetSession> command : commands.commands()) {
                    session.output().printf("%-72s %s%n", command.usage(), command.description());
                }
            }
            return true;
        }
    }

    private static final class ForwardCommand extends ShellCommand<TargetSession> {
        private final boolean acceptsArguments;

        private ForwardCommand(
                String name, String usage, String description, boolean acceptsArguments) {
            super(name, usage, description);
            this.acceptsArguments = acceptsArguments;
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) {
            if (!acceptsArguments && !arguments.isEmpty()) {
                session.error().println("Usage: " + usage());
                return true;
            }
            CommandReply reply = session.server().execute(
                    CommandLine.of(name(), arguments.toArray(new String[0])));
            PrintStream destination = reply.successful() ? session.output() : session.error();
            if (!reply.output().isEmpty()) {
                destination.println(reply.output());
            }
            return true;
        }
    }

    private static final class JniCommand extends ShellCommand<TargetSession> {
        private JniCommand() {
            super("jni",
                    "jni fields <class> | jni get <class> <field> | "
                            + "jni call <class> <method> <descriptor> [args ...]",
                    "Uses RemoteClass, RemoteField and RemoteMethod handles for JNI operations.");
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) {
            if (arguments.size() == 2 && "fields".equalsIgnoreCase(arguments.get(0))) {
                List<RemoteField> fields = session.findClass(arguments.get(1)).getStaticFields();
                if (fields.isEmpty()) {
                    session.output().println("<no declared static fields>");
                } else {
                    for (RemoteField field : fields) {
                        session.output().printf("%s\t%s\t%s%n",
                                field.name(), field.descriptor(), field.snapshotValue());
                    }
                }
                return true;
            }
            if (arguments.size() == 3 && "get".equalsIgnoreCase(arguments.get(0))) {
                RemoteField field = session.findClass(arguments.get(1)).getStaticField(arguments.get(2));
                session.output().printf("%s\t%s%n", field.descriptor(), field.snapshotValue());
                return true;
            }
            if (arguments.size() >= 4 && "call".equalsIgnoreCase(arguments.get(0))) {
                RemoteClass owner = session.findClass(arguments.get(1));
                RemoteMethod method = owner.getStaticMethod(arguments.get(2), arguments.get(3));
                String[] methodArguments = arguments.subList(4, arguments.size()).toArray(new String[0]);
                session.output().println(method.invoke(methodArguments));
                return true;
            }
            session.error().println("Usage: " + usage());
            return true;
        }
    }

    private static final class DumpClassCommand extends ShellCommand<TargetSession> {
        private DumpClassCommand() {
            super("dumpclass", "dumpclass <class> <output.class>",
                    "Gets class bytes through RemoteJVMTIEnv and always writes them to a file.");
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) throws IOException {
            if (arguments.size() != 2) {
                session.error().println("Usage: " + usage());
                return true;
            }
            RemoteClass remoteClass = session.findClass(arguments.get(0));
            Path outputFile = remoteClass.dumpClass(Paths.get(arguments.get(1)));
            session.output().printf("Wrote %,d bytes to %s%n", Files.size(outputFile), outputFile);
            return true;
        }
    }

    private static final class BackCommand extends ShellCommand<TargetSession> {
        private BackCommand() {
            super("back", "back", "Disconnects from the target JVM.", "exit", "quit");
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) {
            if (!arguments.isEmpty()) {
                session.error().println("Usage: " + usage());
                return true;
            }
            return false;
        }
    }
}
