package nhcm.jvmrtdp.controllerside;

import nhcm.jvmrtdp.BuildInfo;
import nhcm.jvmrtdp.command.CommandLine;
import nhcm.jvmrtdp.controllerside.command.ShellCommand;
import nhcm.jvmrtdp.controllerside.command.ShellCommandRegistry;
import nhcm.jvmrtdp.controllerside.analysis.ClassFileMethod;
import nhcm.jvmrtdp.controllerside.analysis.BytecodeInstruction;
import nhcm.jvmrtdp.controllerside.analysis.DecompilationResult;
import nhcm.jvmrtdp.controllerside.analysis.DecompilerEngine;
import nhcm.jvmrtdp.controllerside.analysis.ClassDecompiler;
import nhcm.jvmrtdp.controllerside.analysis.JvmClassFileParser;
import nhcm.jvmrtdp.controllerside.analysis.JvmClassPathCatalog;
import nhcm.jvmrtdp.controllerside.tui.TargetSessionCoordinator;
import nhcm.jvmrtdp.controllerside.tui.TuiResult;
import nhcm.jvmrtdp.controllerside.script.ScriptEngine;
import nhcm.jvmrtdp.controllerside.script.ScriptCommandExecutor;
import nhcm.jvmrtdp.controllerside.debug.DebuggerAnalysisExporter;
import nhcm.jvmrtdp.controllerside.debug.DebuggerFreezeReport;
import nhcm.jvmrtdp.handles.ServerHandle;
import nhcm.jvmrtdp.handles.java.RemoteClass;
import nhcm.jvmrtdp.handles.java.RemoteClassInfo;
import nhcm.jvmrtdp.handles.java.RemoteConstructor;
import nhcm.jvmrtdp.handles.java.RemoteField;
import nhcm.jvmrtdp.handles.java.RemoteMapEntry;
import nhcm.jvmrtdp.handles.java.RemoteMethod;
import nhcm.jvmrtdp.handles.java.RemoteObject;
import nhcm.jvmrtdp.handles.java.RemoteObjectDebugInfo;
import nhcm.jvmrtdp.handles.java.RemotePackage;
import nhcm.jvmrtdp.handles.jvm.RemoteRuntimeStats;
import nhcm.jvmrtdp.handles.jvm.RemoteCodeDeployment;
import nhcm.jvmrtdp.handles.jvm.RemoteJVMTIEnv;
import nhcm.jvmrtdp.handles.jvm.RemoteJvmtiThread;
import nhcm.jvmrtdp.handles.jvm.JvmtiCallbackRegistration;
import nhcm.jvmrtdp.handles.jvm.JvmtiCallbackStatistics;
import nhcm.jvmrtdp.api.jvmti.JvmtiCapability;
import nhcm.jvmrtdp.api.jvmti.JvmtiCapabilityStatus;
import nhcm.jvmrtdp.api.jvmti.JvmtiFieldInfo;
import nhcm.jvmrtdp.api.jvmti.JvmtiLineNumber;
import nhcm.jvmrtdp.api.jvmti.JvmtiThreadInfo;
import nhcm.jvmrtdp.api.jvmti.JvmtiMonitorUsage;
import nhcm.jvmrtdp.api.jvmti.JvmDebuggerState;
import nhcm.jvmrtdp.api.jvmti.JvmDebuggerLocal;
import nhcm.jvmrtdp.api.jvmti.JvmStackFrame;
import nhcm.jvmrtdp.api.jvmti.JvmBreakpointInfo;
import nhcm.jvmrtdp.api.jvmti.JvmBreakpointCondition;
import nhcm.jvmrtdp.api.jvmti.JvmFieldWatchInfo;
import nhcm.jvmrtdp.api.jvmti.JvmEventBreakpointInfo;
import nhcm.jvmrtdp.api.jvmti.JvmEventBreakpointSpec;
import nhcm.jvmrtdp.api.bytecode.JvmBytecodePatch;
import nhcm.jvmrtdp.api.bytecode.JvmBytecodePatchResult;
import nhcm.jvmrtdp.handles.search.RemoteClassQuery;
import nhcm.jvmrtdp.handles.search.RemoteMemberQuery;
import nhcm.jvmrtdp.protocol.CommandReply;
import nhcm.jvmrtdp.protocol.Protocol;
import nhcm.jvmrtdp.utils.GlobMatcher;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Context-oriented command prompt for one authenticated target JVM session. */
public class InteractiveCli {
    private static final int DEFAULT_EXPANSION_LIMIT = 32;
    private static final PrintStream DISCARD_OUTPUT = new PrintStream(new OutputStream() {
        @Override
        public void write(int value) {
            // Intermediate -> steps resolve a temporary receiver and stay quiet.
        }
    });

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

    /** Returns true to go back to the controller prompt, false to terminate the controller. */
    public boolean run(ServerHandle server) {
        return new TargetSessionCoordinator(input, output, error).run(server, false);
    }

    public TuiResult runSession(TargetSession session) {
        output.println("Target prompt ready. Type 'tui' for the full-screen interface or 'help syntax'.");
        while (session.server().isOpen() && !Thread.currentThread().isInterrupted()) {
            output.printf("target[%d|%s]> ", session.server().process().pid(), promptContext(session));
            output.flush();
            String rawLine;
            try {
                rawLine = input.readLine();
            } catch (IOException exception) {
                error.println("Cannot read command: " + exception.getMessage());
                return TuiResult.EXIT;
            }
            if (rawLine == null) return TuiResult.EXIT;
            if (!execute(session, rawLine)) {
                if (session.consumeTuiRequest()) return TuiResult.TUI;
                return session.controllerExitRequested() ? TuiResult.EXIT : TuiResult.BACK;
            }
        }
        return TuiResult.BACK;
    }

    /** Executes one command or an unquoted {@code ->} temporary reference chain. */
    boolean execute(TargetSession session, String rawLine) {
        try {
            return executeCommand(session, rawLine);
        } catch (Exception exception) {
            session.error().println("Command failed: " + exception.getMessage());
            return session.server().isOpen();
        }
    }

    /**
     * Executes one context command without entering the interactive prompt.
     *
     * <p>This method is intended for embedded/library use. Failures are propagated to the caller,
     * while normal command output is written through the supplied {@link TargetSession}.</p>
     */
    public boolean executeCommand(TargetSession session, String rawLine) throws Exception {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(rawLine, "rawLine");
        return executePipeline(session, rawLine);
    }

    private boolean executePipeline(TargetSession session, String rawLine) throws Exception {
        List<String> segments = splitPipeline(rawLine);
        if (segments.size() == 1) return executeSingle(session, segments.get(0));

        try (RemoteContext.TemporaryScope ignored = session.context().temporaryScope()) {
            for (int index = 0; index < segments.size(); index++) {
                final String segment = segments.get(index);
                boolean keepGoing = index == segments.size() - 1
                        ? executeSingle(session, segment)
                        : session.withOutput(DISCARD_OUTPUT, new TargetSession.OutputAction<Boolean>() {
                            @Override
                            public Boolean run() throws Exception {
                                return Boolean.valueOf(executeSingle(session, segment));
                            }
                        }).booleanValue();
                if (!keepGoing) return false;
            }
        }
        return true;
    }

    private boolean executeSingle(TargetSession session, String rawLine) throws Exception {
        CommandLine line = CommandLine.parse(rawLine);
        if (line.name().isEmpty()) return true;
        ShellCommand<TargetSession> command = commands.find(line.name());
        if (command == null) {
            session.error().println("Unknown target command: " + line.name() + ". Use 'help'.");
            return true;
        }
        return command.execute(session, line.arguments());
    }

    private void registerCommands() {
        commands.register(new HelpCommand(commands));
        commands.register(new StackCommand());
        commands.register(new ContextCommand());
        commands.register(new ValueCommand());
        commands.register(new FieldCommand());
        commands.register(new ReadCommand());
        commands.register(new ResolveCommand());
        commands.register(new InvokeCommand());
        commands.register(new StaticCommand());
        commands.register(new ConstructCommand());
        commands.register(new SetCommand());
        commands.register(new ArrayCommand());
        commands.register(new ClassCommand());
        commands.register(new PackageCommand());
        commands.register(new FindCommand());
        commands.register(new DebugCommand());
        commands.register(new StatsCommand());
        commands.register(new ExportCommand());
        commands.register(new ScriptCommand());
        commands.register(new BatchFileCommand());
        commands.register(new DumpClassCommand());
        commands.register(new DecompileCommand());
        commands.register(new BytecodeCommand());
        commands.register(new DebuggerCommand());
        commands.register(new TuiCommand());
        commands.register(new DeployCodeCommand());
        commands.register(new JvmtiShellCommand());
        commands.register(new VersionCommand());
        commands.register(new ForwardCommand("ping", "ping", "Checks target responsiveness.", false));
        commands.register(new ForwardCommand("info", "info", "Shows target JVM and agent information.", false));
        commands.register(new ForwardCommand("echo", "echo [text ...]", "Tests command transport.", true));
        commands.register(new ForwardCommand("native", "native", "Shows native bridge/JVMTI status.", false));
        commands.register(new BackCommand());
        commands.register(new ExitCommand());
    }

    private class ContextCommand extends ShellCommand<TargetSession> {
        private ContextCommand() {
            super("context",
                    "context [<class>|class <class>|static field <class> <field[index]>|field <field[index]>|"
                            + "as <parent-class>|runtime|index <n>|value <literal>|list <fields|methods> [glob]|"
                            + "back [count]|history [limit]|"
                            + "peek [index]|pop [count]|dup|swap|pick <index>|clear|save <name>|use <name>]",
                    "Selects the receiver used by field, invoke, class, value and array commands.", "ctx");
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) {
            if (arguments.isEmpty()) {
                session.output().println("context = " + session.context().description());
                return true;
            }
            String operation = lower(arguments.get(0));
            if (("class".equals(operation) && arguments.size() == 2)
                    || (arguments.size() == 1 && !isContextOperation(operation))) {
                String className = arguments.get(arguments.size() - 1);
                RemoteClass type = session.findClass(className);
                type.info(); // Resolve now so a miss is reported at the context command.
                session.context().select(type);
                printContext(session);
                return true;
            }
            if ("static".equals(operation) && arguments.size() == 4
                    && "field".equalsIgnoreCase(arguments.get(1))) {
                RemoteClass type = session.findClass(arguments.get(2));
                selectResult(session, readStatic(type, FieldSelection.parse(arguments.get(3))));
                return true;
            }
            if ("field".equals(operation) && arguments.size() == 2) {
                selectResult(session, readVirtual(session.context().remoteClass(), session.context().remoteObject(),
                        FieldSelection.parse(arguments.get(1))));
                return true;
            }
            if ("as".equals(operation) && arguments.size() == 2) {
                RemoteClass viewClass = session.findClass(arguments.get(1));
                viewClass.info();
                session.context().viewAs(viewClass);
                printContext(session);
                return true;
            }
            if ("runtime".equals(operation) && arguments.size() == 1) {
                session.context().runtimeView();
                printContext(session);
                return true;
            }
            if ("index".equals(operation) && arguments.size() == 2) {
                int index = integer(arguments.get(1), "index");
                RemoteObject array = session.context().remoteObject();
                selectResult(session, array.arrayGet(index),
                        session.operations().arrayAssignment(array, index));
                return true;
            }
            if ("value".equals(operation) && (arguments.size() == 2 || arguments.size() == 3)) {
                String expression = arguments.size() == 2
                        ? arguments.get(1) : arguments.get(1) + ":" + arguments.get(2);
                try (RemoteArgumentList values = RemoteArgumentList.resolve(
                        session, Collections.singletonList(expression))) {
                    session.context().select(values.transferOnly());
                }
                printContext(session);
                return true;
            }
            if ("list".equals(operation) && (arguments.size() == 2 || arguments.size() == 3)) {
                String member = lower(arguments.get(1));
                if ("fields".equals(member)) printFields(session, session.context().remoteClass(),
                        session.context().isClass(), session.context().isObject(),
                        arguments.size() == 3 ? arguments.get(2) : "*");
                else if ("methods".equals(member)) printMethods(session, session.context().remoteClass(),
                        session.context().isClass(), session.context().isObject(),
                        arguments.size() == 3 ? arguments.get(2) : "*");
                else return InteractiveCli.usage(session, this);
                return true;
            }
            if (isStackOperation(operation)) {
                return executeStackOperation(session, arguments);
            }
            if ("clear".equals(operation) && arguments.size() == 1) {
                session.context().clear();
                printContext(session);
                return true;
            }
            if ("save".equals(operation) && arguments.size() == 2) {
                session.context().save(arguments.get(1));
                session.output().printf("saved @%s = %s%n", RemoteWorkspace.normalize(arguments.get(1)),
                        session.context().description());
                return true;
            }
            if ("use".equals(operation) && arguments.size() == 2) {
                session.context().use(arguments.get(1));
                printContext(session);
                return true;
            }
            if ("bookmarks".equals(operation) && arguments.size() == 1) {
                for (Map.Entry<String, String> entry : session.context().bookmarks().entrySet()) {
                    session.output().printf("@%s = %s%n", entry.getKey(), entry.getValue());
                }
                return true;
            }
            return InteractiveCli.usage(session, this);
        }
    }

    private static class StackCommand extends ShellCommand<TargetSession> {
        private StackCommand() {
            super("stack",
                    "stack [list [limit]|depth|pop [count]|peek [index]|dup|swap|pick <index>|clear]",
                    "Inspects and manipulates the context stack; the current context is item [0].");
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) {
            return executeStackOperation(session, arguments);
        }
    }

    private static class ValueCommand extends ShellCommand<TargetSession> {
        private ValueCommand() {
            super("value", "value [--deep [limit]]",
                    "Prints the current value; --deep expands arrays, Iterable and Map values.");
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) {
            if (arguments.isEmpty()) {
                if (session.context().isClass()) session.output().println(session.context().description());
                else printObject(session, session.context().remoteObject());
                return true;
            }
            if (!"--deep".equalsIgnoreCase(arguments.get(0)) || arguments.size() > 2) return InteractiveCli.usage(session, this);
            int limit = arguments.size() == 2 ? integer(arguments.get(1), "limit") : DEFAULT_EXPANSION_LIMIT;
            printDeep(session, session.context().remoteObject(), limit);
            return true;
        }
    }

    private static class FieldCommand extends ShellCommand<TargetSession> {
        private FieldCommand() {
            super("field", "field [declaring.Class::]<name[index]>",
                    "Reads an instance field from the current object and makes the result current.");
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) {
            if (arguments.size() != 1) return InteractiveCli.usage(session, this);
            RemoteObject receiver = session.context().remoteObject();
            FieldSelection selection = FieldSelection.parse(arguments.get(0));
            RemoteField field = selection.resolveVirtual(session.context().remoteClass());
            if (selection.index == null) {
                selectResult(session, field.read(receiver),
                        session.operations().fieldAssignment(field, receiver));
            } else {
                int index = selection.index.intValue();
                RemoteObject element;
                try (RemoteObject array = field.read(receiver)) {
                    element = array.arrayGet(index);
                }
                selectResult(session, element,
                        session.operations().fieldArrayAssignment(field, receiver, index));
            }
            return true;
        }
    }

    private static class ReadCommand extends ShellCommand<TargetSession> {
        private ReadCommand() {
            super("read", "read [field] [declaring.Class::]<name[index]> | "
                            + "read static [field] [class] <name[index]> | read index <n>",
                    "Reads and prints a field or array element without changing the current context or its stack.",
                    "peekfield");
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) {
            if (arguments.isEmpty()) return InteractiveCli.usage(session, this);
            String operation = lower(arguments.get(0));
            if ("index".equals(operation) && arguments.size() == 2) {
                try (RemoteObject value = session.context().remoteObject().arrayGet(
                        integer(arguments.get(1), "index"))) {
                    printReadObject(session, value);
                }
                return true;
            }
            if ("static".equals(operation)) return readStaticField(session, arguments.subList(1, arguments.size()));

            int fieldIndex = "field".equals(operation) ? 1 : 0;
            if (arguments.size() != fieldIndex + 1) return InteractiveCli.usage(session, this);
            RemoteClass type = session.context().remoteClass();
            RemoteObject receiver = session.context().remoteObject();
            try (RemoteObject value = readVirtual(type, receiver, FieldSelection.parse(arguments.get(fieldIndex)))) {
                printReadObject(session, value);
            }
            return true;
        }

        private static boolean readStaticField(TargetSession session, List<String> arguments) {
            int offset = !arguments.isEmpty() && "field".equalsIgnoreCase(arguments.get(0)) ? 1 : 0;
            int remaining = arguments.size() - offset;
            if (remaining != 1 && remaining != 2) {
                throw new IllegalArgumentException(
                        "Usage: read static [field] [class] <name[index]>");
            }
            RemoteClass type = remaining == 2
                    ? session.findClass(arguments.get(offset)) : session.context().remoteClass();
            String fieldName = arguments.get(arguments.size() - 1);
            try (RemoteObject value = readStatic(type, FieldSelection.parse(fieldName))) {
                printReadObject(session, value);
            }
            return true;
        }
    }

    private static class ResolveCommand extends ShellCommand<TargetSession> {
        private ResolveCommand() {
            super("resolve", "resolve <literal|reference|{value-expression}>",
                    "Evaluates and prints one value without changing the current context or stack.",
                    "ref", "eval");
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) {
            if (arguments.size() != 1) return InteractiveCli.usage(session, this);
            try (RemoteArgumentList value = RemoteArgumentList.resolve(session, arguments)) {
                printReadObject(session, value.only());
            }
            return true;
        }
    }

    private static class InvokeCommand extends ShellCommand<TargetSession> {
        private InvokeCommand() {
            super("invoke", "invoke [declaring.Class::]<method> <descriptor> [arguments ...]",
                    "Invokes virtually by default; a declaring-class qualifier calls that parent implementation.");
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) {
            if (arguments.size() < 2) return InteractiveCli.usage(session, this);
            MethodSelection method = MethodSelection.parse(arguments.get(0));
            try (RemoteArgumentList values = RemoteArgumentList.resolve(session, arguments.subList(2, arguments.size()))) {
                RemoteClass type = session.context().remoteClass();
                RemoteObject result;
                if (session.context().isClass()) {
                    RemoteMethod remoteMethod = method.resolveStatic(type, arguments.get(1));
                    result = remoteMethod.callStatic(values.values());
                } else {
                    RemoteMethod remoteMethod = method.resolveVirtual(type, arguments.get(1));
                    RemoteObject receiver = session.context().remoteObject();
                    result = method.declaringClass == null
                            ? remoteMethod.call(receiver, values.values())
                            : remoteMethod.callSpecial(receiver, values.values());
                }
                selectInvocationResult(session, result);
            }
            return true;
        }
    }

    private static class StaticCommand extends ShellCommand<TargetSession> {
        private StaticCommand() {
            super("static",
                    "static field [class] <field[index]> | static invoke [class] <method> <descriptor> [args ...] | "
                            + "static set [class] <field> <value>",
                    "Performs an explicit static operation; omitted class means the current class context.");
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) {
            if (arguments.isEmpty()) return InteractiveCli.usage(session, this);
            String operation = lower(arguments.get(0));
            if ("field".equals(operation) && (arguments.size() == 2 || arguments.size() == 3)) {
                RemoteClass type = arguments.size() == 3
                        ? session.findClass(arguments.get(1)) : session.context().remoteClass();
                String field = arguments.get(arguments.size() - 1);
                FieldSelection selection = FieldSelection.parse(field);
                RemoteField selected = selection.resolveStatic(type);
                if (selection.index == null) {
                    selectResult(session, selected.readStatic(),
                            session.operations().fieldAssignment(selected, null));
                } else {
                    int index = selection.index.intValue();
                    RemoteObject element;
                    try (RemoteObject array = selected.readStatic()) {
                        element = array.arrayGet(index);
                    }
                    selectResult(session, element,
                            session.operations().fieldArrayAssignment(selected, null, index));
                }
                return true;
            }
            if ("invoke".equals(operation) && arguments.size() >= 3) {
                boolean contextual = arguments.get(2).startsWith("(");
                if (!contextual && arguments.size() < 4) return InteractiveCli.usage(session, this);
                RemoteClass type = contextual ? session.context().remoteClass() : session.findClass(arguments.get(1));
                int methodIndex = contextual ? 1 : 2;
                int descriptorIndex = methodIndex + 1;
                MethodSelection method = MethodSelection.parse(arguments.get(methodIndex));
                try (RemoteArgumentList values = RemoteArgumentList.resolve(
                        session, arguments.subList(descriptorIndex + 1, arguments.size()))) {
                    selectInvocationResult(session,
                            method.resolveStatic(type, arguments.get(descriptorIndex)).callStatic(values.values()));
                }
                return true;
            }
            if ("set".equals(operation) && (arguments.size() == 3 || arguments.size() == 4)) {
                RemoteClass type = arguments.size() == 4
                        ? session.findClass(arguments.get(1)) : session.context().remoteClass();
                int fieldIndex = arguments.size() == 4 ? 2 : 1;
                FieldSelection field = FieldSelection.parse(arguments.get(fieldIndex));
                if (field.index != null) throw new IllegalArgumentException("Cannot assign through field[index]");
                try (RemoteArgumentList values = RemoteArgumentList.resolve(
                        session, Collections.singletonList(arguments.get(fieldIndex + 1)))) {
                    field.resolveStatic(type).writeStatic(values.only());
                }
                session.output().println("ok");
                return true;
            }
            return InteractiveCli.usage(session, this);
        }
    }

    private static class ConstructCommand extends ShellCommand<TargetSession> {
        private ConstructCommand() {
            super("construct", "construct [class] <descriptor|auto> [arguments ...]",
                    "Constructs a target object and makes it the current context.", "new");
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) {
            if (arguments.isEmpty()) return InteractiveCli.usage(session, this);
            boolean contextual = "auto".equalsIgnoreCase(arguments.get(0)) || arguments.get(0).startsWith("(");
            if (!contextual && arguments.size() < 2) return InteractiveCli.usage(session, this);
            RemoteClass type = contextual ? session.context().remoteClass() : session.findClass(arguments.get(0));
            int descriptorIndex = contextual ? 0 : 1;
            try (RemoteArgumentList values = RemoteArgumentList.resolve(
                    session, arguments.subList(descriptorIndex + 1, arguments.size()))) {
                selectResult(session, type.construct(arguments.get(descriptorIndex), values.values()));
            }
            return true;
        }
    }

    private static class SetCommand extends ShellCommand<TargetSession> {
        private SetCommand() {
            super("set", "set context <value> | set [field] [declaring.Class::]<name> <value> | set index <n> <value>",
                    "Writes the current writable context, a field, or an array element.");
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) {
            if (arguments.size() != 2 && arguments.size() != 3) return InteractiveCli.usage(session, this);
            if (arguments.size() == 2 && "context".equalsIgnoreCase(arguments.get(0))) {
                try (RemoteArgumentList values = RemoteArgumentList.resolve(
                        session, Collections.singletonList(arguments.get(1)))) {
                    RemoteObject replacement = values.only();
                    session.context().assign(replacement);
                    values.transferOnly();
                }
                session.output().println("context source updated: "
                        + session.context().assignmentDescription());
                return true;
            }
            boolean shorthandField = arguments.size() == 2;
            String valueExpression = arguments.get(arguments.size() - 1);
            try (RemoteArgumentList values = RemoteArgumentList.resolve(
                    session, Collections.singletonList(valueExpression))) {
                if (shorthandField || "field".equalsIgnoreCase(arguments.get(0))) {
                    RemoteObject receiver = session.context().remoteObject();
                    FieldSelection field = FieldSelection.parse(arguments.get(shorthandField ? 0 : 1));
                    if (field.index != null) throw new IllegalArgumentException("Use 'set index' for array elements");
                    field.resolveVirtual(session.context().remoteClass()).write(receiver, values.only());
                } else if ("index".equalsIgnoreCase(arguments.get(0))) {
                    session.context().remoteObject().arraySet(integer(arguments.get(1), "index"), values.only());
                } else {
                    return InteractiveCli.usage(session, this);
                }
            }
            session.output().println("ok");
            return true;
        }
    }

    private static class ArrayCommand extends ShellCommand<TargetSession> {
        private ArrayCommand() {
            super("array", "array <length|get <index>|set <index> <value>|list [limit]>",
                    "Inspects or changes the current primitive/reference array.");
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) {
            if (arguments.size() == 1 && "length".equalsIgnoreCase(arguments.get(0))) {
                session.output().println(session.context().remoteObject().arrayLength());
                return true;
            }
            if (arguments.size() == 2 && "get".equalsIgnoreCase(arguments.get(0))) {
                int index = integer(arguments.get(1), "index");
                RemoteObject array = session.context().remoteObject();
                selectResult(session, array.arrayGet(index),
                        session.operations().arrayAssignment(array, index));
                return true;
            }
            if (arguments.size() == 3 && "set".equalsIgnoreCase(arguments.get(0))) {
                try (RemoteArgumentList values = RemoteArgumentList.resolve(
                        session, Collections.singletonList(arguments.get(2)))) {
                    session.context().remoteObject().arraySet(integer(arguments.get(1), "index"), values.only());
                }
                session.output().println("ok");
                return true;
            }
            if ((arguments.size() == 1 || arguments.size() == 2) && "list".equalsIgnoreCase(arguments.get(0))) {
                printDeep(session, session.context().remoteObject(), arguments.size() == 2
                        ? integer(arguments.get(1), "limit") : DEFAULT_EXPANSION_LIMIT);
                return true;
            }
            return InteractiveCli.usage(session, this);
        }
    }

    private static class ClassCommand extends ShellCommand<TargetSession> {
        private ClassCommand() {
            super("class", "class <load <name> [--no-init]|info|fields [all|static|virtual] [glob]|methods [all|static|virtual] [glob]|constructors>",
                    "Lists metadata for the class represented by the current context.");
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) {
            if (arguments.isEmpty()) arguments = Collections.singletonList("info");
            String operation = lower(arguments.get(0));
            if ("load".equals(operation) && (arguments.size() == 2 || arguments.size() == 3)) {
                boolean noInitialization = arguments.size() == 3
                        && "--no-init".equals(lower(arguments.get(2)));
                if (arguments.size() == 3 && !noInitialization) return false;
                RemoteClass loaded = noInitialization
                        ? session.loadClassWithoutInitialization(arguments.get(1))
                        : session.forceLoadClass(arguments.get(1));
                session.context().select(loaded);
                session.output().println(noInitialization
                        ? "Loaded and linked without class initialization " + loaded.className()
                        : "Class.forName loaded and initialized " + loaded.className());
                printContext(session);
                return true;
            }
            RemoteClass type = session.context().remoteClass();
            if ("info".equals(operation) && arguments.size() == 1) {
                printClassInfo(session, type.info());
                return true;
            }
            if (("fields".equals(operation) || "methods".equals(operation)) && arguments.size() <= 3) {
                String mode = "all";
                String glob = "*";
                if (arguments.size() >= 2) {
                    String second = lower(arguments.get(1));
                    if ("all".equals(second) || "static".equals(second) || "virtual".equals(second)) mode = second;
                    else glob = arguments.get(1);
                }
                if (arguments.size() == 3) glob = arguments.get(2);
                boolean statics = "all".equals(mode) || "static".equals(mode);
                boolean virtuals = "all".equals(mode) || "virtual".equals(mode);
                if (!statics && !virtuals) return InteractiveCli.usage(session, this);
                if ("fields".equals(operation)) printFields(session, type, statics, virtuals, glob);
                else printMethods(session, type, statics, virtuals, glob);
                return true;
            }
            if ("constructors".equals(operation) && arguments.size() == 1) {
                for (RemoteConstructor constructor : type.getConstructors()) {
                    session.output().printf("%s(%s)  [%s; descriptor=%s]%n", type.className(),
                            join(constructor.parameterTypeNames()), Modifier.toString(constructor.modifiers()),
                            constructor.descriptor());
                }
                return true;
            }
            return InteractiveCli.usage(session, this);
        }
    }

    private static class PackageCommand extends ShellCommand<TargetSession> {
        private PackageCommand() {
            super("package", "package [package.name|.]",
                    "Lists loaded classes and immediate subpackages; no argument lists the root/default package.");
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) {
            if (arguments.size() > 1) return InteractiveCli.usage(session, this);
            String name = arguments.isEmpty() || ".".equals(arguments.get(0))
                    || "<default>".equalsIgnoreCase(arguments.get(0)) ? "" : arguments.get(0);
            RemotePackage value = session.jni().findPackage(name);
            for (String child : value.packages()) session.output().println("[package] " + child);
            for (String child : value.classes()) session.output().println("[class]   " + child);
            if (value.packages().isEmpty() && value.classes().isEmpty()) session.output().println("<empty>");
            return true;
        }
    }

    private static class FindCommand extends ShellCommand<TargetSession> {
        private FindCommand() {
            super("find", "find package [glob] [--limit n] | find <class|interface|enum|annotation|array> "
                            + "[name-glob] [--package glob] [--extends glob] [--implements glob] [--limit n] | "
                            + "find <extends|implements> <type-glob> [name-glob] [--limit n] | "
                            + "find field [name-glob] [--class glob] [--type glob] [--static|--virtual] [--limit n] | "
                            + "find method [name-glob] [--class glob] [--returns glob] [--params glob] "
                            + "[--static|--virtual] [--limit n] | "
                            + "find unloaded [class|field|method] [glob] [--class owner-glob] [--limit n]",
                    "Searches loaded runtime metadata or a separate unloaded class-path catalog.", "search");
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) throws IOException {
            if (arguments.isEmpty()) return InteractiveCli.usage(session, this);
            String subject = lower(arguments.get(0));
            if ("unloaded".equals(subject)) {
                return unloaded(session, arguments.subList(1, arguments.size()));
            }
            if ("package".equals(subject)) return packages(session, arguments.subList(1, arguments.size()));
            if ("field".equals(subject) || "method".equals(subject)) {
                return members(session, subject, arguments.subList(1, arguments.size()));
            }
            if (Arrays.asList("class", "interface", "enum", "annotation", "array", "extends", "implements")
                    .contains(subject)) {
                return classes(session, subject, arguments.subList(1, arguments.size()));
            }
            return InteractiveCli.usage(session, this);
        }

        private static boolean unloaded(TargetSession session, List<String> arguments)
                throws IOException {
            String kind = "class";
            int index = 0;
            if (index < arguments.size() && Arrays.asList("class", "field", "method")
                    .contains(lower(arguments.get(index)))) kind = lower(arguments.get(index++));
            String expression = "*";
            if (index < arguments.size() && !arguments.get(index).startsWith("--")) {
                expression = arguments.get(index++);
            }
            String owner = "*";
            int limit = 200;
            while (index < arguments.size()) {
                String option = lower(arguments.get(index++));
                if ("--class".equals(option) && index < arguments.size()) owner = arguments.get(index++);
                else if ("--limit".equals(option) && index < arguments.size()) {
                    limit = integer(arguments.get(index++), "limit");
                } else throw new IllegalArgumentException("Unknown/incomplete unloaded search option: " + option);
            }
            if (limit < 1 || limit > 10000) {
                throw new IllegalArgumentException("limit must be between 1 and 10000");
            }
            JvmClassPathCatalog catalog = session.refreshClassPathCatalog();
            int count;
            if ("class".equals(kind)) {
                List<JvmClassPathCatalog.ClassEntry> results = catalog.searchUnloaded(expression, limit);
                for (JvmClassPathCatalog.ClassEntry entry : results) {
                    session.output().printf("[unloaded class] %s  origin=%s%n",
                            entry.name(), entry.origin());
                }
                count = results.size();
            } else {
                JvmClassPathCatalog.MemberKind memberKind = "field".equals(kind)
                        ? JvmClassPathCatalog.MemberKind.FIELD
                        : JvmClassPathCatalog.MemberKind.METHOD;
                List<JvmClassPathCatalog.MemberMatch> results = catalog.searchUnloadedMembers(
                        owner, expression, memberKind, limit);
                for (JvmClassPathCatalog.MemberMatch match : results) {
                    JvmClassPathCatalog.Member member = match.member();
                    session.output().printf("[unloaded %-6s] %s.%s%s  [%s]%n", kind,
                            match.owner().name(), member.name(),
                            memberKind == JvmClassPathCatalog.MemberKind.METHOD
                                    ? member.descriptor() : " : " + member.descriptor(),
                            Modifier.toString(member.access()));
                }
                count = results.size();
            }
            printSearchCount(session, count, limit);
            session.output().printf("-- catalog: %,d class file(s), %,d currently unloaded%n",
                    catalog.size(), catalog.unloadedSize());
            return true;
        }

        private static boolean packages(TargetSession session, List<String> arguments) {
            String glob = "*";
            int limit = 200;
            int index = 0;
            if (index < arguments.size() && !arguments.get(index).startsWith("--")) glob = arguments.get(index++);
            while (index < arguments.size()) {
                String option = lower(arguments.get(index++));
                if ("--limit".equals(option) && index < arguments.size()) limit = integer(arguments.get(index++), "limit");
                else throw new IllegalArgumentException("Unknown/incomplete package search option: " + option);
            }
            List<String> results = session.jni().searchPackages(glob, limit);
            for (String name : results) session.output().println("[package] " + (name.isEmpty() ? "<default>" : name));
            printSearchCount(session, results.size(), limit);
            return true;
        }

        private static boolean classes(TargetSession session, String subject, List<String> arguments) {
            RemoteClassQuery query = new RemoteClassQuery();
            int index = 0;
            if ("extends".equals(subject) || "implements".equals(subject)) {
                if (arguments.isEmpty()) throw new IllegalArgumentException(
                        "find " + subject + " requires a relation type glob");
                if ("extends".equals(subject)) query.extending(arguments.get(index++));
                else query.implementing(arguments.get(index++));
            } else {
                query.kind(subject);
            }
            if (index < arguments.size() && !arguments.get(index).startsWith("--")) query.name(arguments.get(index++));
            while (index < arguments.size()) {
                String option = lower(arguments.get(index++));
                if ("--package".equals(option) && index < arguments.size()) query.inPackage(arguments.get(index++));
                else if ("--extends".equals(option) && index < arguments.size()) query.extending(arguments.get(index++));
                else if ("--implements".equals(option) && index < arguments.size()) query.implementing(arguments.get(index++));
                else if ("--kind".equals(option) && index < arguments.size()) query.kind(arguments.get(index++));
                else if ("--limit".equals(option) && index < arguments.size()) query.limit(integer(arguments.get(index++), "limit"));
                else throw new IllegalArgumentException("Unknown/incomplete class search option: " + option);
            }
            List<RemoteClassInfo> results = session.jni().searchClasses(query);
            for (RemoteClassInfo info : results) {
                String relation = info.superclass().isEmpty() ? "" : " extends " + info.superclass();
                if (!info.interfaces().isEmpty()) relation += " implements " + info.interfaces();
                session.output().printf("[%-10s] %s%s%n", classKind(info), info.name(), relation);
            }
            printSearchCount(session, results.size(), query.limit());
            return true;
        }

        private static boolean members(TargetSession session, String subject, List<String> arguments) {
            RemoteMemberQuery query = new RemoteMemberQuery();
            int index = 0;
            if (index < arguments.size() && !arguments.get(index).startsWith("--")) query.name(arguments.get(index++));
            while (index < arguments.size()) {
                String option = lower(arguments.get(index++));
                if ("--class".equals(option) && index < arguments.size()) query.owner(arguments.get(index++));
                else if (("--type".equals(option) || "--returns".equals(option)) && index < arguments.size()) {
                    query.type(arguments.get(index++));
                } else if ("--params".equals(option) && index < arguments.size()) query.parameters(arguments.get(index++));
                else if ("--static".equals(option)) query.mode("static");
                else if ("--virtual".equals(option)) query.mode("virtual");
                else if ("--all".equals(option)) query.mode("all");
                else if ("--limit".equals(option) && index < arguments.size()) query.limit(integer(arguments.get(index++), "limit"));
                else throw new IllegalArgumentException("Unknown/incomplete member search option: " + option);
            }
            if ("field".equals(subject)) {
                List<RemoteField> results = session.jni().searchFields(query);
                for (RemoteField field : results) printField(session, field);
                printSearchCount(session, results.size(), query.limit());
            } else {
                List<RemoteMethod> results = session.jni().searchMethods(query);
                for (RemoteMethod method : results) printMethod(session, method);
                printSearchCount(session, results.size(), query.limit());
            }
            return true;
        }

        private static String classKind(RemoteClassInfo info) {
            if ((info.modifiers() & 0x2000) != 0) return "annotation";
            if (info.isInterface()) return "interface";
            if (info.isEnum()) return "enum";
            if (info.isArray()) return "array";
            return "class";
        }

        private static void printSearchCount(TargetSession session, int count, int limit) {
            session.output().printf("-- %,d result(s)%s%n", count,
                    count == limit ? "; limit reached, use --limit to request more" : "");
        }
    }

    private static class DebugCommand extends ShellCommand<TargetSession> {
        private DebugCommand() {
            super("debug", "debug", "Prints identity, shape, size and reflection counts for the current object.");
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) {
            if (!arguments.isEmpty()) return InteractiveCli.usage(session, this);
            RemoteObjectDebugInfo info = session.context().remoteObject().debugInfo();
            session.output().printf("id=%d%nclass=%s%nshape=%s%nsize=%s%nidentityHash=0x%s%n"
                            + "declaredFields=%d%ndeclaredMethods=%d%ndisplay=%s%n",
                    info.objectId(), info.className(), info.shape(),
                    info.size().isEmpty() ? "n/a" : info.size(), info.identityHash(),
                    info.declaredFields(), info.declaredMethods(), info.displayValue());
            return true;
        }
    }

    private static class StatsCommand extends ShellCommand<TargetSession> {
        private StatsCommand() {
            super("stats", "stats", "Samples loaded classes, threads, handles, heap and uptime.");
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) {
            if (!arguments.isEmpty()) return InteractiveCli.usage(session, this);
            RemoteRuntimeStats stats = session.jni().statistics();
            session.output().printf("loadedClasses=%,d  totalLoaded=%,d%nthreads=%,d  remoteHandles=%,d%n"
                            + "heapUsed=%s  heapMax=%s%nprocessors=%d  uptime=%s%n",
                    stats.loadedClasses(), stats.totalLoadedClasses(), stats.liveThreads(), stats.retainedHandles(),
                    bytes(stats.usedHeapBytes()), bytes(stats.maxHeapBytes()), stats.processors(),
                    duration(stats.uptimeMillis()));
            return true;
        }
    }

    private class ExportCommand extends ShellCommand<TargetSession> {
        private ExportCommand() {
            super("export", "export [append] <file> [command ...]",
                    "Runs a command (default: value --deep) and writes its output to a UTF-8 file.");
        }

        @Override
        public boolean execute(final TargetSession session, List<String> arguments) throws Exception {
            boolean append = !arguments.isEmpty() && "append".equalsIgnoreCase(arguments.get(0));
            int fileIndex = append ? 1 : 0;
            if (arguments.size() <= fileIndex) return InteractiveCli.usage(session, this);
            Path file = Paths.get(arguments.get(fileIndex)).toAbsolutePath().normalize();
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            final String nested = arguments.size() == fileIndex + 1
                    ? "value --deep" : commandText(arguments.subList(fileIndex + 1, arguments.size()));
            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            boolean keepRunning;
            try (final PrintStream capture = new PrintStream(buffer, true, "UTF-8")) {
                keepRunning = session.withOutput(capture, new TargetSession.OutputAction<Boolean>() {
                    @Override
                    public Boolean run() {
                        return InteractiveCli.this.execute(session, nested);
                    }
                });
            }
            byte[] content = buffer.toByteArray();
            if (append) {
                Files.write(file, content, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                Files.write(file, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
            session.baseOutput().printf("Exported %,d bytes to %s%n", content.length, file);
            return keepRunning;
        }
    }

    private class ScriptCommand extends ShellCommand<TargetSession> {
        private final ScriptEngine engine = new ScriptEngine(new ScriptCommandExecutor() {
            @Override
            public boolean execute(TargetSession session, String command) throws Exception {
                return InteractiveCli.this.executePipeline(session, command);
            }
        });

        private ScriptCommand() {
            super("script", "script <file.jrd>",
                    "Runs a flow script with if, ifnull, switch, print, export and command instructions.");
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) throws Exception {
            if (arguments.size() != 1) return InteractiveCli.usage(session, this);
            engine.execute(Paths.get(arguments.get(0)), session);
            return true;
        }
    }

    private class BatchFileCommand extends ShellCommand<TargetSession> {
        private BatchFileCommand() {
            super("batch", "batch <commands.txt>",
                    "Runs context commands line by line; blank lines and # comments are ignored.");
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) throws IOException {
            if (arguments.size() != 1) return InteractiveCli.usage(session, this);
            List<String> source = Files.readAllLines(
                    Paths.get(arguments.get(0)).toAbsolutePath().normalize(), StandardCharsets.UTF_8);
            int executed = 0;
            for (int index = 0; index < source.size(); index++) {
                String line = source.get(index).trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                session.output().printf("[%d] %s%n", index + 1, line);
                executed++;
                if (!InteractiveCli.this.execute(session, line)) return false;
            }
            session.output().printf("Batch completed: %,d commands%n", executed);
            return true;
        }
    }

    private static class DeployCodeCommand extends ShellCommand<TargetSession> {
        private DeployCodeCommand() {
            super("code", "code source <name> <file|dir> [options] | code methods <name> <class> <file> "
                            + "[options] | code jar <name> <jar> [options] | code list | code close <id> | "
                            + "code run <id> <class> <method> <descriptor> <static|this|object-ref> [args ...] | "
                            + "code callback <add|remove|enable|disable|reset|list|stats> ...",
                    "Compiles and deploys Java source/method fragments, loads JARs and manages Java JVMTI handlers.");
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) throws Exception {
            if (arguments.isEmpty()) return InteractiveCli.usage(session, this);
            String operation = lower(arguments.get(0));
            if ("source".equals(operation) && arguments.size() >= 3) {
                DeploymentOptions options = DeploymentOptions.parse(arguments, 3);
                RemoteCodeDeployment deployment = session.jvmti().deploySources(arguments.get(1),
                        Paths.get(arguments.get(2)), options.classpath, options.compilerOptions,
                        options.anchorClass, options.mode);
                printDeployment(session, deployment);
                return true;
            }
            if ("methods".equals(operation) && arguments.size() >= 4) {
                DeploymentOptions options = DeploymentOptions.parse(arguments, 4);
                String methods = new String(Files.readAllBytes(Paths.get(arguments.get(3))), StandardCharsets.UTF_8);
                RemoteCodeDeployment deployment = session.jvmti().deployMethods(arguments.get(1), arguments.get(2),
                        methods, options.classpath, options.compilerOptions, options.anchorClass, options.mode);
                printDeployment(session, deployment);
                return true;
            }
            if ("jar".equals(operation) && arguments.size() >= 3) {
                DeploymentOptions options = DeploymentOptions.parse(arguments, 3);
                RemoteCodeDeployment deployment = session.jvmti().addJar(arguments.get(1),
                        Paths.get(arguments.get(2)), options.scope, options.anchorClass);
                printDeployment(session, deployment);
                return true;
            }
            if ("list".equals(operation) && arguments.size() == 1) {
                List<RemoteCodeDeployment> deployments = session.jvmti().deployments();
                if (deployments.isEmpty()) session.output().println("No code deployments.");
                for (RemoteCodeDeployment deployment : deployments) printDeployment(session, deployment);
                return true;
            }
            if ("close".equals(operation) && arguments.size() == 2) {
                RemoteCodeDeployment deployment = findDeployment(session, arguments.get(1));
                deployment.close();
                session.output().println("closed " + arguments.get(1));
                return true;
            }
            if ("run".equals(operation) && arguments.size() >= 6) {
                RemoteCodeDeployment deployment = findDeployment(session, arguments.get(1));
                boolean isStatic = "static".equalsIgnoreCase(arguments.get(5));
                int expressionStart = isStatic ? 6 : 5;
                try (RemoteArgumentList values = RemoteArgumentList.resolve(
                        session, arguments.subList(expressionStart, arguments.size()))) {
                    RemoteObject[] resolved = values.values();
                    RemoteObject receiver = isStatic ? null : resolved[0];
                    RemoteObject[] methodArguments = isStatic ? resolved
                            : Arrays.copyOfRange(resolved, 1, resolved.length);
                    selectInvocationResult(session, deployment.execute(arguments.get(2), arguments.get(3),
                            arguments.get(4), receiver, methodArguments));
                }
                return true;
            }
            if ("callback".equals(operation)) return callback(session, arguments.subList(1, arguments.size()));
            return InteractiveCli.usage(session, this);
        }

        private static boolean callback(TargetSession session, List<String> arguments) {
            if (arguments.isEmpty()) throw new IllegalArgumentException("code callback requires an operation");
            String operation = lower(arguments.get(0));
            if ("add".equals(operation) && (arguments.size() == 4 || arguments.size() == 5)) {
                RemoteCodeDeployment deployment = findDeployment(session, arguments.get(1));
                boolean sync = arguments.size() == 5 && "sync".equalsIgnoreCase(arguments.get(4));
                if (arguments.size() == 5 && !sync && !"async".equalsIgnoreCase(arguments.get(4))) {
                    throw new IllegalArgumentException("Callback delivery must be sync or async");
                }
                session.output().println("callback=" + deployment.registerCallback(
                        arguments.get(2), arguments.get(3), sync).id());
                return true;
            }
            if ("remove".equals(operation) && arguments.size() == 2) {
                session.output().println(session.jvmti().unregisterCallback(arguments.get(1)) ? "removed" : "not found");
                return true;
            }
            if (("enable".equals(operation) || "disable".equals(operation))
                    && arguments.size() == 2) {
                boolean changed = session.jvmti().setCallbackEnabled(
                        arguments.get(1), "enable".equals(operation));
                session.output().println(changed ? operation + "d" : "not found");
                return true;
            }
            if ("reset".equals(operation) && arguments.size() == 2) {
                session.output().println(session.jvmti().resetCallback(arguments.get(1))
                        ? "reset" : "not found");
                return true;
            }
            if ("list".equals(operation) && arguments.size() == 1) {
                List<JvmtiCallbackRegistration> callbacks = session.jvmti().callbacks();
                if (callbacks.isEmpty()) session.output().println("No callbacks.");
                for (JvmtiCallbackRegistration callback : callbacks) {
                    session.output().printf("%s state=%s handler=%s events=%s delivery=%s delivered=%d failed=%d%s%s%n",
                            callback.id(), callback.enabled() ? "enabled" : "disabled",
                            callback.handlerClass(), callback.events(), callback.delivery(),
                            callback.delivered(), callback.failed(), callback.lastFailure().isEmpty()
                                    ? "" : " lastFailure=" + callback.lastFailure(),
                            callback.lastEvent().isEmpty() ? "" : " lastEvent=" + callback.lastEvent()
                                    + "@" + callback.lastEventAt());
                }
                return true;
            }
            if ("stats".equals(operation) && arguments.size() == 1) {
                JvmtiCallbackStatistics stats = session.jvmti().callbackStatistics();
                session.output().printf("registrations=%d delivered=%d failed=%d nativeQueued=%d "
                                + "nativeDropped=%d nativeQueueDepth=%d%s%n",
                        stats.registrations(), stats.delivered(), stats.failed(), stats.nativeQueued(),
                        stats.nativeDropped(), stats.nativeQueueDepth(), stats.lastFailure().isEmpty()
                                ? "" : " lastFailure=" + stats.lastFailure());
                return true;
            }
            throw new IllegalArgumentException("Usage: code callback add <deployment> <handler-class> "
                    + "<event,event,...> [sync|async] | remove|enable|disable|reset <id> | list | stats");
        }

        private static RemoteCodeDeployment findDeployment(TargetSession session, String id) {
            for (RemoteCodeDeployment deployment : session.jvmti().deployments()) {
                if (deployment.id().equals(id)) return deployment;
            }
            throw new IllegalArgumentException("Unknown code deployment: " + id);
        }

        private static void printDeployment(TargetSession session, RemoteCodeDeployment deployment) {
            session.output().printf("deployment=%s name=%s mode=%s classes=%d loader=%s targetLoader=%s%n",
                    deployment.id(), deployment.name(), deployment.mode(), deployment.definedClassCount(),
                    deployment.loader(), deployment.targetLoader());
        }

        private static class DeploymentOptions {
            private String anchorClass = "";
            private RemoteJVMTIEnv.DefinitionMode mode = RemoteJVMTIEnv.DefinitionMode.CHILD;
            private RemoteJVMTIEnv.JarScope scope = RemoteJVMTIEnv.JarScope.CHILD;
            private final List<Path> classpath = new ArrayList<Path>();
            private final List<String> compilerOptions = new ArrayList<String>();

            private static DeploymentOptions parse(List<String> arguments, int start) {
                DeploymentOptions result = new DeploymentOptions();
                int index = start;
                while (index < arguments.size()) {
                    String option = lower(arguments.get(index++));
                    if ("--anchor".equals(option) && index < arguments.size()) {
                        result.anchorClass = arguments.get(index++);
                    } else if ("--same-loader".equals(option)) {
                        result.mode = RemoteJVMTIEnv.DefinitionMode.SAME_LOADER;
                    } else if ("--child".equals(option)) {
                        result.mode = RemoteJVMTIEnv.DefinitionMode.CHILD;
                        result.scope = RemoteJVMTIEnv.JarScope.CHILD;
                    } else if ("--scope".equals(option) && index < arguments.size()) {
                        result.scope = RemoteJVMTIEnv.JarScope.valueOf(
                                arguments.get(index++).toUpperCase(Locale.ROOT));
                    } else if ("--classpath".equals(option) && index < arguments.size()) {
                        String[] paths = arguments.get(index++).split(
                                java.util.regex.Pattern.quote(java.io.File.pathSeparator));
                        for (String path : paths) if (!path.isEmpty()) result.classpath.add(Paths.get(path));
                    } else if ("--javac".equals(option) && index < arguments.size()) {
                        result.compilerOptions.add(arguments.get(index++));
                    } else if (("--release".equals(option) || "-source".equals(option) || "-target".equals(option))
                            && index < arguments.size()) {
                        result.compilerOptions.add(option);
                        result.compilerOptions.add(arguments.get(index++));
                    } else {
                        throw new IllegalArgumentException("Unknown/incomplete code option: " + option);
                    }
                }
                return result;
            }
        }
    }

    private static class JvmtiShellCommand extends ShellCommand<TargetSession> {
        private JvmtiShellCommand() {
            super("jvmti", "jvmti capabilities | capability-status | capability <add|relinquish> <name...> | "
                            + "phase | time | timer-info | current-thread-cpu-time | processors | location-format | "
                            + "property <get|set> <name> [value] | verbose <other|gc|class|jni> <enable|disable> | "
                            + "class <info|interfaces|loader-classes|source-debug|constant-pool> <class> | "
                            + "method <info|bytecodes|lines> <class> <method> <descriptor> | "
                            + "field info <class> <field> <descriptor> | events [generate <event>] | retransform <class> | redefine <class> <class-file> | "
                            + "breakpoint <set|clear> <class> <method> <descriptor> <location> | "
                            + "watch <access|modification> <set|clear> <class> <field> <descriptor> | "
                            + "threads [prefix] [limit] | thread <info|state|stack|frame-count|cpu-time|owned-monitors|contended-monitor|suspend|resume|interrupt|frame-pop> <object> [depth|max] | "
                            + "size <object> | hash <object> | monitor-usage <object> | tag <object> [value] | tagged <tag> [prefix] [limit] | gc | properties",
                    "Runs JVMTI class, thread, heap/tag, GC and runtime operations.");
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) throws Exception {
            if (arguments.isEmpty()) return InteractiveCli.usage(session, this);
            String operation = lower(arguments.get(0));
            if ("capabilities".equals(operation) && arguments.size() == 1) {
                for (String capability : session.jvmti().capabilities()) session.output().println(capability);
                return true;
            }
            if ("capability-status".equals(operation) && arguments.size() == 1) {
                printCapabilities(session, session.jvmti().capabilityStatuses());
                return true;
            }
            if ("capability".equals(operation) && arguments.size() >= 3) {
                String action = lower(arguments.get(1));
                JvmtiCapability[] requested = new JvmtiCapability[arguments.size() - 2];
                for (int index = 2; index < arguments.size(); index++) {
                    requested[index - 2] = JvmtiCapability.parse(arguments.get(index));
                }
                List<JvmtiCapabilityStatus> statuses;
                if ("add".equals(action)) statuses = session.jvmti().addCapabilities(requested);
                else if ("relinquish".equals(action) || "release".equals(action)) {
                    statuses = session.jvmti().relinquishCapabilities(requested);
                } else throw new IllegalArgumentException("Capability operation must be add or relinquish");
                for (JvmtiCapability requestedCapability : requested) {
                    for (JvmtiCapabilityStatus status : statuses) {
                        if (status.capability() == requestedCapability) {
                            session.output().printf("%s enabled=%s potential=%s%n",
                                    status.capability().wireName(), status.enabled(), status.potential());
                        }
                    }
                }
                return true;
            }
            if ("phase".equals(operation) && arguments.size() == 1) {
                session.output().println(session.jvmti().phase());
                return true;
            }
            if ("time".equals(operation) && arguments.size() == 1) {
                session.output().println(session.jvmti().time());
                return true;
            }
            if ("timer-info".equals(operation) && arguments.size() == 1) {
                session.output().println(session.jvmti().timerInfo());
                return true;
            }
            if ("current-thread-cpu-time".equals(operation) && arguments.size() == 1) {
                session.output().println(session.jvmti().currentThreadCpuTime());
                return true;
            }
            if ("processors".equals(operation) && arguments.size() == 1) {
                session.output().println(session.jvmti().availableProcessors());
                return true;
            }
            if ("location-format".equals(operation) && arguments.size() == 1) {
                session.output().println(session.jvmti().locationFormat());
                return true;
            }
            if ("property".equals(operation) && arguments.size() >= 3 && arguments.size() <= 4) {
                String action = lower(arguments.get(1));
                if ("get".equals(action) && arguments.size() == 3) {
                    session.output().println(session.jvmti().getSystemProperty(arguments.get(2)));
                } else if ("set".equals(action) && arguments.size() == 4) {
                    session.output().println(session.jvmti().setSystemProperty(arguments.get(2), arguments.get(3)));
                } else return InteractiveCli.usage(session, this);
                return true;
            }
            if ("verbose".equals(operation) && arguments.size() == 3) {
                session.jvmti().setVerboseFlag(arguments.get(1), setOrClear(arguments.get(2)));
                session.output().println("ok");
                return true;
            }
            if ("class".equals(operation) && arguments.size() == 3) {
                String action = lower(arguments.get(1));
                if ("info".equals(action)) session.output().println(session.jvmti().classInfo(arguments.get(2)));
                else if ("interfaces".equals(action)) {
                    for (String type : session.jvmti().implementedInterfaces(arguments.get(2))) {
                        session.output().println(type);
                    }
                } else if ("loader-classes".equals(action)) {
                    for (String type : session.jvmti().classLoaderClasses(arguments.get(2))) {
                        session.output().println(type);
                    }
                } else if ("source-debug".equals(action)) {
                    session.output().println(session.jvmti().sourceDebugExtension(arguments.get(2)));
                } else if ("constant-pool".equals(action)) {
                    byte[] bytes = session.jvmti().constantPool(arguments.get(2));
                    session.output().printf("length=%d (use RemoteJVMTIEnv.constantPool() to read bytes)%n",
                            bytes.length);
                } else return InteractiveCli.usage(session, this);
                return true;
            }
            if ("method".equals(operation) && arguments.size() == 5) {
                String action = lower(arguments.get(1));
                if ("info".equals(action)) {
                    session.output().println(session.jvmti().methodInfo(
                            arguments.get(2), arguments.get(3), arguments.get(4)));
                } else if ("bytecodes".equals(action)) {
                    byte[] bytes = session.jvmti().methodBytecodes(
                            arguments.get(2), arguments.get(3), arguments.get(4));
                    session.output().printf("length=%d base64=%s%n", bytes.length,
                            Base64.getEncoder().encodeToString(bytes));
                } else if ("lines".equals(action)) {
                    for (JvmtiLineNumber line : session.jvmti().lineNumberTable(
                            arguments.get(2), arguments.get(3), arguments.get(4))) {
                        session.output().println(line);
                    }
                } else return InteractiveCli.usage(session, this);
                return true;
            }
            if ("field".equals(operation) && arguments.size() == 5
                    && "info".equalsIgnoreCase(arguments.get(1))) {
                JvmtiFieldInfo field = session.jvmti().fieldInfo(
                        arguments.get(2), arguments.get(3), arguments.get(4));
                session.output().printf("%s.%s %s modifiers=0x%x synthetic=%s declaring=%s generic=%s%n",
                        field.className(), field.name(), field.descriptor(), field.modifiers(),
                        field.synthetic(), field.declaringClass(), field.genericSignature());
                return true;
            }
            if ("events".equals(operation) && arguments.size() == 1) {
                for (nhcm.jvmrtdp.api.jvmti.JvmtiEventType event
                        : nhcm.jvmrtdp.api.jvmti.JvmtiEventType.values()) {
                    session.output().println(event.wireName());
                }
                return true;
            }
            if ("events".equals(operation) && arguments.size() == 3
                    && "generate".equalsIgnoreCase(arguments.get(1))) {
                session.jvmti().generateEvents(
                        nhcm.jvmrtdp.api.jvmti.JvmtiEventType.parse(arguments.get(2)));
                session.output().println("ok");
                return true;
            }
            if ("retransform".equals(operation) && arguments.size() == 2) {
                session.jvmti().retransformClass(arguments.get(1));
                session.output().println("ok");
                return true;
            }
            if ("redefine".equals(operation) && arguments.size() == 3) {
                session.jvmti().redefineClass(arguments.get(1), Files.readAllBytes(Paths.get(arguments.get(2))));
                session.output().println("ok");
                return true;
            }
            if ("breakpoint".equals(operation) && arguments.size() == 6) {
                session.jvmti().setBreakpoint(arguments.get(2), arguments.get(3), arguments.get(4),
                        Long.parseLong(arguments.get(5)), setOrClear(arguments.get(1)));
                session.output().println("ok");
                return true;
            }
            if ("watch".equals(operation) && arguments.size() == 6) {
                boolean modification;
                if ("access".equalsIgnoreCase(arguments.get(1))) modification = false;
                else if ("modification".equalsIgnoreCase(arguments.get(1))) modification = true;
                else throw new IllegalArgumentException("Watch kind must be access or modification");
                session.jvmti().setFieldWatch(arguments.get(3), arguments.get(4), arguments.get(5),
                        modification, setOrClear(arguments.get(2)));
                session.output().println("ok");
                return true;
            }
            if ("threads".equals(operation) && arguments.size() <= 3) {
                String prefix = arguments.size() >= 2 ? arguments.get(1) : "thread";
                int limit = arguments.size() == 3 ? integer(arguments.get(2), "limit") : 128;
                List<RemoteJvmtiThread> threads = session.jvmti().threads();
                int kept = Math.min(limit, threads.size());
                for (int index = 0; index < threads.size(); index++) {
                    RemoteJvmtiThread thread = threads.get(index);
                    if (index < kept) {
                        String variable = prefix + index;
                        session.workspace().defineObject(variable, thread.object());
                        session.output().printf("$%s state=0x%08x %s%n",
                                variable, thread.capturedState(), thread.object().displayValue());
                    } else {
                        thread.close();
                    }
                }
                session.output().printf("Saved %d of %d thread handle(s)%n", kept, threads.size());
                return true;
            }
            if ("thread".equals(operation) && arguments.size() >= 3 && arguments.size() <= 4) {
                String action = lower(arguments.get(1));
                try (RemoteArgumentList value = RemoteArgumentList.resolve(
                        session, Collections.singletonList(arguments.get(2)))) {
                    RemoteObject thread = value.only();
                    if ("state".equals(action) && arguments.size() == 3) {
                        session.output().printf("0x%08x%n", session.jvmti().threadState(thread));
                    } else if ("info".equals(action) && arguments.size() == 3) {
                        JvmtiThreadInfo info = session.jvmti().threadInfo(thread);
                        session.output().printf("name=%s priority=%d daemon=%s state=0x%08x "
                                        + "group=%s contextLoader=%s%n",
                                info.name(), info.priority(), info.daemon(), info.state(),
                                info.threadGroupClass(), info.contextClassLoaderClass());
                    } else if ("frame-count".equals(action) && arguments.size() == 3) {
                        session.output().println(session.jvmti().frameCount(thread));
                    } else if ("cpu-time".equals(action) && arguments.size() == 3) {
                        session.output().println(session.jvmti().threadCpuTime(thread));
                    } else if ("owned-monitors".equals(action) && arguments.size() == 3) {
                        List<RemoteObject> monitors = session.jvmti().ownedMonitors(thread);
                        try {
                            for (RemoteObject monitor : monitors) session.output().println(monitor);
                        } finally {
                            for (RemoteObject monitor : monitors) monitor.close();
                        }
                    } else if ("contended-monitor".equals(action) && arguments.size() == 3) {
                        try (RemoteObject monitor = session.jvmti().currentContendedMonitor(thread)) {
                            session.output().println(monitor);
                        }
                    } else if ("stack".equals(action)) {
                        int max = arguments.size() == 4 ? integer(arguments.get(3), "max frames") : 64;
                        for (String frame : session.jvmti().stackTrace(thread, max)) session.output().println(frame);
                    } else if ("suspend".equals(action) && arguments.size() == 3) {
                        session.jvmti().suspendThread(thread);
                        session.output().println("ok");
                    } else if ("resume".equals(action) && arguments.size() == 3) {
                        session.jvmti().resumeThread(thread);
                        session.output().println("ok");
                    } else if ("interrupt".equals(action) && arguments.size() == 3) {
                        session.jvmti().interruptThread(thread);
                        session.output().println("ok");
                    } else if ("frame-pop".equals(action) && arguments.size() == 4) {
                        session.jvmti().notifyFramePop(thread, integer(arguments.get(3), "depth"));
                        session.output().println("ok");
                    } else return InteractiveCli.usage(session, this);
                }
                return true;
            }
            if (("size".equals(operation) || "tag".equals(operation))
                    && (arguments.size() == 2 || arguments.size() == 3)) {
                try (RemoteArgumentList value = RemoteArgumentList.resolve(
                        session, Collections.singletonList(arguments.get(1)))) {
                    if ("size".equals(operation) && arguments.size() == 2) {
                        session.output().println(session.jvmti().objectSize(value.only()));
                    } else if ("tag".equals(operation) && arguments.size() == 2) {
                        session.output().println(session.jvmti().getTag(value.only()));
                    } else if ("tag".equals(operation)) {
                        session.jvmti().setTag(value.only(), Long.parseLong(arguments.get(2)));
                        session.output().println("ok");
                    } else return InteractiveCli.usage(session, this);
                }
                return true;
            }
            if ("hash".equals(operation) && arguments.size() == 2) {
                try (RemoteArgumentList value = RemoteArgumentList.resolve(
                        session, Collections.singletonList(arguments.get(1)))) {
                    session.output().println(session.jvmti().objectHashCode(value.only()));
                }
                return true;
            }
            if ("monitor-usage".equals(operation) && arguments.size() == 2) {
                try (RemoteArgumentList value = RemoteArgumentList.resolve(
                        session, Collections.singletonList(arguments.get(1)))) {
                    JvmtiMonitorUsage usage = session.jvmti().objectMonitorUsage(value.only());
                    session.output().println(usage);
                }
                return true;
            }
            if ("tagged".equals(operation) && arguments.size() >= 2 && arguments.size() <= 4) {
                long tag = Long.parseLong(arguments.get(1));
                String prefix = arguments.size() >= 3 ? arguments.get(2) : "tagged";
                int limit = arguments.size() == 4 ? integer(arguments.get(3), "limit") : 128;
                List<RemoteObject> objects = session.jvmti().objectsWithTag(tag);
                int kept = Math.min(limit, objects.size());
                for (int index = 0; index < objects.size(); index++) {
                    RemoteObject object = objects.get(index);
                    if (index < kept) {
                        session.workspace().defineObject(prefix + index, object);
                        session.output().printf("$%s%d %s%n", prefix, index, object);
                    } else object.close();
                }
                session.output().printf("Saved %d of %d tagged object handle(s)%n", kept, objects.size());
                return true;
            }
            if ("gc".equals(operation) && arguments.size() == 1) {
                session.jvmti().forceGarbageCollection();
                session.output().println("ok");
                return true;
            }
            if ("properties".equals(operation) && arguments.size() == 1) {
                for (String property : session.jvmti().systemProperties()) session.output().println(property);
                return true;
            }
            return InteractiveCli.usage(session, this);
        }

        private static void printCapabilities(
                TargetSession session, List<JvmtiCapabilityStatus> statuses) {
            for (JvmtiCapabilityStatus status : statuses) {
                session.output().printf("%s enabled=%s potential=%s%n",
                        status.capability().wireName(), status.enabled(), status.potential());
            }
        }

        private static boolean setOrClear(String value) {
            if ("set".equalsIgnoreCase(value) || "enable".equalsIgnoreCase(value)) return true;
            if ("clear".equalsIgnoreCase(value) || "disable".equalsIgnoreCase(value)) return false;
            throw new IllegalArgumentException("Operation must be set/enable or clear/disable");
        }
    }

    private static class DumpClassCommand extends ShellCommand<TargetSession> {
        private DumpClassCommand() {
            super("dumpclass", "dumpclass [class] <output.class> | dumpclass package <name|.> <dir> "
                            + "[--recursive|--no-recursive] [--match glob] [--limit n]",
                    "Writes JVMTI class bytes to files; package mode supports bounded batch dumps.", "dump");
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) throws IOException {
            if (!arguments.isEmpty() && "package".equalsIgnoreCase(arguments.get(0))) {
                return dumpPackage(session, arguments);
            }
            if (!arguments.isEmpty() && "class".equalsIgnoreCase(arguments.get(0))) {
                arguments = arguments.subList(1, arguments.size());
            }
            if (arguments.size() != 1 && arguments.size() != 2) return InteractiveCli.usage(session, this);
            RemoteClass type = arguments.size() == 2
                    ? session.findClass(arguments.get(0)) : session.context().remoteClass();
            Path outputFile = type.dumpClass(Paths.get(arguments.get(arguments.size() - 1)));
            session.output().printf("Wrote %,d bytes to %s%n", Files.size(outputFile), outputFile);
            return true;
        }

        private static boolean dumpPackage(TargetSession session, List<String> arguments) {
            if (arguments.size() < 3) return InteractiveCli.usage(session, new DumpClassCommand());
            String packageName = arguments.get(1);
            Path outputDirectory = Paths.get(arguments.get(2));
            boolean recursive = false;
            String match = "*";
            int limit = 1000;
            int index = 3;
            while (index < arguments.size()) {
                String option = lower(arguments.get(index++));
                if ("--recursive".equals(option) || "-r".equals(option)) recursive = true;
                else if ("--no-recursive".equals(option)) recursive = false;
                else if ("--match".equals(option) && index < arguments.size()) match = arguments.get(index++);
                else if ("--limit".equals(option) && index < arguments.size()) limit = integer(arguments.get(index++), "limit");
                else throw new IllegalArgumentException("Unknown/incomplete dump option: " + option);
            }
            RemoteDumpService.Report report = new RemoteDumpService(session).dumpPackage(
                    packageName, outputDirectory, recursive, match, limit);
            for (String failure : report.failures()) session.error().println("dump failed: " + failure);
            session.output().printf("Dumped %,d class file(s) to %s; %,d failed%n",
                    report.written().size(), outputDirectory.toAbsolutePath().normalize(), report.failures().size());
            if (report.written().size() == limit) {
                session.output().println("Search limit reached; use --limit to request more classes.");
            }
            return true;
        }
    }

    private static class DecompileCommand extends ShellCommand<TargetSession> {
        private DecompileCommand() {
            super("decompile", "decompile class [class] [--engine cfr|procyon] [--out file] | "
                            + "decompile method [class] <name> <descriptor> [--engine cfr|procyon] [--out file]",
                    "Decompiles target class bytes with source-built CFR or Procyon.", "decomp");
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) throws IOException {
            if (arguments.isEmpty()) return InteractiveCli.usage(session, this);
            String mode = lower(arguments.get(0));
            ParsedAnalysisOptions options = ParsedAnalysisOptions.parse(arguments.subList(1, arguments.size()));
            String text;
            if ("class".equals(mode)) {
                if (options.positionals.size() > 1) return InteractiveCli.usage(session, this);
                DecompilationResult result;
                if (!options.positionals.isEmpty()) {
                    String className = options.positionals.get(0);
                    try {
                        result = session.findClass(className).decompile(options.engine);
                    } catch (RuntimeException failure) {
                        JvmClassPathCatalog.ClassEntry unloaded = unloadedCatalogEntry(
                                session, className, failure);
                        result = new ClassDecompiler().decompile(
                                unloaded.name(), unloaded.bytes(), options.engine);
                        session.error().println("decompiler: using unloaded class-path bytes; target class remains unloaded");
                    }
                } else result = session.context().remoteClass().decompile(options.engine);
                text = result.source();
                for (String diagnostic : result.diagnostics()) session.error().println("decompiler: " + diagnostic);
            } else if ("method".equals(mode)) {
                String name;
                String descriptor;
                if (options.positionals.size() == 2) {
                    name = options.positionals.get(0);
                    descriptor = options.positionals.get(1);
                    text = session.context().remoteClass().decompileMethod(
                            name, descriptor, options.engine);
                } else if (options.positionals.size() == 3) {
                    String className = options.positionals.get(0);
                    name = options.positionals.get(1);
                    descriptor = options.positionals.get(2);
                    try {
                        text = session.findClass(className).decompileMethod(
                                name, descriptor, options.engine);
                    } catch (RuntimeException failure) {
                        JvmClassPathCatalog.ClassEntry unloaded = unloadedCatalogEntry(
                                session, className, failure);
                        text = new ClassDecompiler().decompileMethod(
                                unloaded.name(), unloaded.bytes(), name, descriptor, options.engine);
                        session.error().println("decompiler: using unloaded class-path bytes; target class remains unloaded");
                    }
                } else return InteractiveCli.usage(session, this);
            } else return InteractiveCli.usage(session, this);
            outputAnalysis(session, text, options.output);
            return true;
        }
    }

    private static class BytecodeCommand extends ShellCommand<TargetSession> {
        private BytecodeCommand() {
            super("bytecode", "bytecode [class] <method> <descriptor> [--out file] | "
                            + "bytecode <insert-before|insert-after|replace> <class> <method> <descriptor> <bci> <assembly> | "
                            + "bytecode delete <class> <method> <descriptor> <from-bci> [to-bci] | "
                            + "bytecode <returns-insert|returns-replace> <class> <method> <descriptor> <assembly> | "
                            + "bytecode intercept-return <class> <method> <descriptor> <hook-class> <hook-method> | "
                            + "bytecode patch-file <class> <file> [--preview] [--out class-file] | "
                            + "bytecode <undo|redo> <class>",
                    "Disassembles or transactionally edits live bytecode with ASM; use ';;' between instructions.",
                    "disasm", "bc");
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) throws IOException {
            if (!arguments.isEmpty()) {
                String action = arguments.get(0).toLowerCase(Locale.ROOT);
                if ("insert-before".equals(action) || "insert-after".equals(action)
                        || "replace".equals(action)) {
                    if (arguments.size() == 6) {
                        JvmBytecodePatch.Builder patch = JvmBytecodePatch.builder(arguments.get(1));
                        int bci = integer(arguments.get(4), "BCI");
                        if ("insert-before".equals(action)) patch.insertBefore(
                                arguments.get(2), arguments.get(3), bci, arguments.get(5));
                        else if ("insert-after".equals(action)) patch.insertAfter(
                                arguments.get(2), arguments.get(3), bci, arguments.get(5));
                        else patch.replace(arguments.get(2), arguments.get(3), bci, arguments.get(5));
                        printPatchResult(session, session.instrumentation().bytecode().apply(patch.build()));
                        return true;
                    }
                    if (arguments.size() > 3) return InteractiveCli.usage(session, this);
                }
                if ("delete".equals(action)) {
                    if (arguments.size() == 5 || arguments.size() == 6) {
                        int from = integer(arguments.get(4), "from BCI");
                        int to = arguments.size() == 6 ? integer(arguments.get(5), "to BCI") : from;
                        JvmBytecodePatch patch = JvmBytecodePatch.builder(arguments.get(1))
                                .delete(arguments.get(2), arguments.get(3), from, to).build();
                        printPatchResult(session, session.instrumentation().bytecode().apply(patch));
                        return true;
                    }
                    if (arguments.size() > 3) return InteractiveCli.usage(session, this);
                }
                if ("returns-insert".equals(action) || "returns-replace".equals(action)) {
                    if (arguments.size() == 5) {
                        JvmBytecodePatch.Builder patch = JvmBytecodePatch.builder(arguments.get(1));
                        if ("returns-insert".equals(action)) patch.insertBeforeReturns(
                                arguments.get(2), arguments.get(3), arguments.get(4));
                        else patch.replaceReturns(arguments.get(2), arguments.get(3), arguments.get(4));
                        printPatchResult(session, session.instrumentation().bytecode().apply(patch.build()));
                        return true;
                    }
                    if (arguments.size() > 3) return InteractiveCli.usage(session, this);
                }
                if ("intercept-return".equals(action)) {
                    if (arguments.size() == 6) {
                        printPatchResult(session, session.instrumentation().bytecode().interceptReturns(
                                arguments.get(1), arguments.get(2), arguments.get(3),
                                arguments.get(4), arguments.get(5)));
                        return true;
                    }
                    if (arguments.size() > 3) return InteractiveCli.usage(session, this);
                }
                if ("undo".equals(action) || "redo".equals(action)) {
                    if (arguments.size() == 2 && !arguments.get(1).startsWith("(")) {
                        if ("undo".equals(action)) session.instrumentation().bytecode().undo(arguments.get(1));
                        else session.instrumentation().bytecode().redo(arguments.get(1));
                        session.output().println(action + " installed for " + arguments.get(1));
                        return true;
                    }
                }
                if ("patch-file".equals(action) && arguments.size() >= 3) {
                    return applyPatchFile(session, arguments);
                }
            }
            ParsedAnalysisOptions options = ParsedAnalysisOptions.parse(arguments);
            String name;
            String descriptor;
            String ownerName;
            ClassFileMethod method;
            if (options.positionals.size() == 2) {
                RemoteClass type = session.context().remoteClass();
                ownerName = type.className();
                name = options.positionals.get(0);
                descriptor = options.positionals.get(1);
                method = type.bytecode(name, descriptor);
            } else if (options.positionals.size() == 3) {
                ownerName = options.positionals.get(0);
                name = options.positionals.get(1);
                descriptor = options.positionals.get(2);
                try {
                    method = session.findClass(ownerName).bytecode(name, descriptor);
                } catch (RuntimeException failure) {
                    JvmClassPathCatalog.ClassEntry unloaded = unloadedCatalogEntry(
                            session, ownerName, failure);
                    method = new JvmClassFileParser().parse(unloaded.bytes()).method(name, descriptor);
                    session.error().println("bytecode: using unloaded class-path bytes; target class remains unloaded");
                }
            } else return InteractiveCli.usage(session, this);
            String text = String.format("%s.%s%s  maxStack=%d maxLocals=%d%n%s",
                    ownerName, method.name(), method.descriptor(),
                    method.maxStack(), method.maxLocals(), method.disassembly());
            outputAnalysis(session, text, options.output);
            return true;
        }

        private boolean applyPatchFile(TargetSession session, List<String> arguments)
                throws IOException {
            if (arguments.size() < 3) return InteractiveCli.usage(session, this);
            boolean preview = false;
            Path output = null;
            for (int index = 3; index < arguments.size(); index++) {
                String value = arguments.get(index);
                if ("--preview".equalsIgnoreCase(value)) preview = true;
                else if ("--out".equalsIgnoreCase(value) && index + 1 < arguments.size()) {
                    output = Paths.get(arguments.get(++index));
                } else return InteractiveCli.usage(session, this);
            }
            String className = arguments.get(1);
            JvmBytecodePatch.Builder builder = JvmBytecodePatch.builder(className);
            List<String> lines = Files.readAllLines(Paths.get(arguments.get(2)), StandardCharsets.UTF_8);
            for (int lineNumber = 1; lineNumber <= lines.size(); lineNumber++) {
                String line = lines.get(lineNumber - 1).trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] fields = splitPatchLine(line);
                try { addPatchFileOperation(builder, fields); }
                catch (RuntimeException failure) {
                    throw new IllegalArgumentException("Patch file line " + lineNumber + ": "
                            + failure.getMessage(), failure);
                }
            }
            JvmBytecodePatch patch = builder.build();
            JvmBytecodePatchResult result = preview
                    ? session.instrumentation().bytecode().preview(patch)
                    : session.instrumentation().bytecode().apply(patch);
            if (output != null) {
                Path absolute = output.toAbsolutePath().normalize();
                Path parent = absolute.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.write(absolute, result.patchedBytes());
                session.output().println("patched class bytes -> " + absolute);
            }
            printPatchResult(session, result);
            return true;
        }

        private static String[] splitPatchLine(String line) {
            int separator = line.indexOf('|');
            String action = separator < 0 ? line
                    : line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            int limit = "returns-insert".equals(action) || "returns-replace".equals(action)
                    ? 4 : 5;
            return line.split("\\|", limit);
        }

        private static void addPatchFileOperation(JvmBytecodePatch.Builder builder, String[] fields) {
            if (fields.length < 3) throw new IllegalArgumentException(
                    "expected operation|method|descriptor|...");
            String action = fields[0].trim().toLowerCase(Locale.ROOT);
            String method = fields[1].trim();
            String descriptor = fields[2].trim();
            if ("insert-before".equals(action) || "insert-after".equals(action)
                    || "replace".equals(action)) {
                if (fields.length != 5) throw new IllegalArgumentException(
                        action + " expects operation|method|descriptor|bci|assembly");
                int bci = integer(fields[3].trim(), "BCI");
                if ("insert-before".equals(action)) builder.insertBefore(method, descriptor, bci, fields[4]);
                else if ("insert-after".equals(action)) builder.insertAfter(method, descriptor, bci, fields[4]);
                else builder.replace(method, descriptor, bci, fields[4]);
            } else if ("delete".equals(action)) {
                if (fields.length != 4 && fields.length != 5) throw new IllegalArgumentException(
                        "delete expects operation|method|descriptor|from-bci[|to-bci]");
                int from = integer(fields[3].trim(), "from BCI");
                builder.delete(method, descriptor, from,
                        fields.length == 5 ? integer(fields[4].trim(), "to BCI") : from);
            } else if ("returns-insert".equals(action) || "returns-replace".equals(action)) {
                if (fields.length != 4) throw new IllegalArgumentException(
                        action + " expects operation|method|descriptor|assembly");
                if ("returns-insert".equals(action)) builder.insertBeforeReturns(method, descriptor, fields[3]);
                else builder.replaceReturns(method, descriptor, fields[3]);
            } else throw new IllegalArgumentException("unknown operation " + action);
        }

        private static void printPatchResult(TargetSession session, JvmBytecodePatchResult result) {
            session.output().println(result);
            if (result.installed()) session.output().println(
                    "New invocations use the new bytecode; frames already executing may finish obsolete code.");
        }
    }

    private static class DebuggerCommand extends ShellCommand<TargetSession> {
        private DebuggerCommand() {
            super("debugger", "debugger <status|threads|pause <all-thread-index>|freeze [refresh]|thaw|freeze-status|"
                            + "pause-all|locations|frames [paused-index] [max]|"
                            + "sample <all-thread-index> [depth] [radius]|"
                            + "current [paused-index] [depth] [radius]|"
                            + "stack [max]|stack <paused-index> <max>|locals [thread-index] [depth]|"
                            + "local-context [thread-index] [depth] [local-index]|"
                            + "local-set <thread-index> <depth> <local-index> <value>|enable|disable|"
                            + "break|clear <class> <method> <descriptor> <bci>|"
                            + "break-context|clear-context <method> <descriptor> <bci> [caller-class [caller-method [caller-descriptor]]]|"
                            + "breakpoints [clear-all]|"
                            + "event-break <entry|exit> <class> <method> <descriptor> [subtypes]|"
                            + "exception-break <class-glob>|event-breakpoints [clear-all]|event-clear <index>|"
                            + "watch <read|write> <set|clear> <class> <field> <descriptor>|watches [clear-all]|"
                            + "continue [thread-index|all]|step [thread-index]|step-out [thread-index]|"
                            + "force-return <thread-index> <value>|force-return-void <thread-index>|"
                            + "snapshot <file|-> [json|jsonl] [max-frames] [locals-depth]> ...",
                    "Controls the shared multi-thread debugger, reversible analysis freeze, and structured exports.", "dbg");
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) throws IOException {
            if (!arguments.isEmpty() && "freeze".equalsIgnoreCase(arguments.get(0))
                    && (arguments.size() == 1 || (arguments.size() == 2
                    && "refresh".equalsIgnoreCase(arguments.get(1))))) {
                printFreezeReport(session, session.debugger().freeze());
                return true;
            }
            if (arguments.size() == 1 && "pause-all".equalsIgnoreCase(arguments.get(0))) {
                printFreezeReport(session, session.debugger().freeze());
                printCurrentLocations(session);
                return true;
            }
            if (arguments.size() == 1 && ("thaw".equalsIgnoreCase(arguments.get(0))
                    || "restore".equalsIgnoreCase(arguments.get(0)))) {
                printFreezeReport(session, session.debugger().restore());
                return true;
            }
            if (arguments.size() == 1 && "freeze-status".equalsIgnoreCase(arguments.get(0))) {
                printFreezeReport(session, session.debugger().status());
                return true;
            }
            if (!arguments.isEmpty() && "snapshot".equalsIgnoreCase(arguments.get(0))
                    && arguments.size() >= 2 && arguments.size() <= 5) {
                String destination = arguments.get(1);
                DebuggerAnalysisExporter.Format format = arguments.size() >= 3
                        ? DebuggerAnalysisExporter.Format.parse(arguments.get(2))
                        : DebuggerAnalysisExporter.Format.JSON;
                int maxFrames = arguments.size() >= 4
                        ? integer(arguments.get(3), "max frames") : 32;
                int localsDepth = arguments.size() >= 5
                        ? integer(arguments.get(4), "locals depth") : 0;
                if ("-".equals(destination)) {
                    session.output().print(DebuggerAnalysisExporter.capture(
                            session, format, maxFrames, localsDepth));
                } else {
                    Path output = DebuggerAnalysisExporter.write(session,
                            Paths.get(destination), format, maxFrames, localsDepth);
                    session.output().println("debug analysis exported -> " + output);
                }
                return true;
            }
            if (arguments.size() == 1 && "status".equalsIgnoreCase(arguments.get(0))) {
                printDebuggerStates(session);
                session.output().println(session.debugger().status().summary());
                return true;
            }
            if (arguments.size() == 1 && "threads".equalsIgnoreCase(arguments.get(0))) {
                printAllDebuggerThreads(session);
                return true;
            }
            if (arguments.size() == 1 && "locations".equalsIgnoreCase(arguments.get(0))) {
                printCurrentLocations(session);
                return true;
            }
            if (arguments.size() == 2 && "pause".equalsIgnoreCase(arguments.get(0))) {
                int wanted = integer(arguments.get(1), "all-thread index");
                List<RemoteJvmtiThread> threads = session.jvmti().threads();
                try {
                    if (wanted < 0 || wanted >= threads.size()) {
                        throw new IllegalArgumentException("No JVM thread at index " + wanted);
                    }
                    session.jvmti().configureDebugger(true);
                    threads.get(wanted).pauseInDebugger();
                    session.output().println("paused " + threads.get(wanted).name());
                } finally {
                    for (RemoteJvmtiThread thread : threads) thread.close();
                }
                return true;
            }
            if (!arguments.isEmpty() && "sample".equalsIgnoreCase(arguments.get(0))
                    && arguments.size() >= 2 && arguments.size() <= 4) {
                int threadIndex = integer(arguments.get(1), "all-thread index");
                int depth = arguments.size() >= 3
                        ? integer(arguments.get(2), "frame depth") : -1;
                int radius = arguments.size() == 4
                        ? integer(arguments.get(3), "instruction radius") : 5;
                sampleRunningThread(session, threadIndex, depth, radius);
                return true;
            }
            if (!arguments.isEmpty() && "stack".equalsIgnoreCase(arguments.get(0))
                    && arguments.size() <= 3) {
                int threadIndex = arguments.size() == 3 ? integer(arguments.get(1), "thread index") : 0;
                int maxFrames = arguments.size() >= 2
                        ? integer(arguments.get(arguments.size() - 1), "max frames") : 16;
                List<JvmDebuggerState> states = session.jvmti().debuggerStates();
                try {
                    JvmDebuggerState state = pausedDebuggerState(states, threadIndex);
                    for (String frame : session.jvmti().stackTrace(state.thread(), maxFrames)) {
                        session.output().println(frame);
                    }
                } finally {
                    closeDebuggerStates(states);
                }
                return true;
            }
            if (!arguments.isEmpty() && "frames".equalsIgnoreCase(arguments.get(0))
                    && arguments.size() <= 3) {
                int threadIndex = arguments.size() >= 2
                        ? integer(arguments.get(1), "thread index") : 0;
                int maxFrames = arguments.size() == 3
                        ? integer(arguments.get(2), "max frames") : 32;
                List<JvmDebuggerState> states = session.jvmti().debuggerStates();
                try {
                    JvmDebuggerState state = pausedDebuggerState(states, threadIndex);
                    for (JvmStackFrame frame : session.jvmti().stackFrames(state.thread(), maxFrames)) {
                        session.output().println(frame.display());
                    }
                } finally {
                    closeDebuggerStates(states);
                }
                return true;
            }
            if (!arguments.isEmpty() && "current".equalsIgnoreCase(arguments.get(0))
                    && arguments.size() <= 4) {
                int threadIndex = arguments.size() >= 2
                        ? integer(arguments.get(1), "thread index") : 0;
                int depth = arguments.size() >= 3
                        ? integer(arguments.get(2), "frame depth") : -1;
                int radius = arguments.size() == 4
                        ? integer(arguments.get(3), "instruction radius") : 5;
                printCurrentFrame(session, threadIndex, depth, radius);
                return true;
            }
            if (!arguments.isEmpty() && "locals".equalsIgnoreCase(arguments.get(0))
                    && arguments.size() <= 3) {
                int threadIndex = arguments.size() >= 2 ? integer(arguments.get(1), "thread index") : 0;
                int depth = arguments.size() == 3 ? integer(arguments.get(2), "frame depth") : 0;
                List<JvmDebuggerState> states = session.jvmti().debuggerStates();
                try {
                    JvmDebuggerState state = pausedDebuggerState(states, threadIndex);
                    List<JvmDebuggerLocal> locals = session.jvmti().debuggerLocals(state.thread(), depth);
                    try {
                        for (JvmDebuggerLocal local : locals) {
                            session.output().printf("slot=%d scope=%d+%d %s %s = %s%n", local.slot(),
                                    local.scopeStart(), local.scopeLength(), local.descriptor(), local.name(),
                                    local.available() ? local.value() == null ? "null"
                                            : local.value().displayValue() : "<" + local.error() + ">");
                        }
                        if (locals.isEmpty()) session.output().println("<no active local variables>");
                    } finally {
                        for (JvmDebuggerLocal local : locals) local.close();
                    }
                } finally {
                    closeDebuggerStates(states);
                }
                return true;
            }
            if (!arguments.isEmpty() && "local-context".equalsIgnoreCase(arguments.get(0))
                    && arguments.size() <= 4) {
                int threadIndex = arguments.size() >= 2
                        ? integer(arguments.get(1), "thread index") : 0;
                int depth = arguments.size() >= 3 ? integer(arguments.get(2), "frame depth") : 0;
                int localIndex = arguments.size() == 4
                        ? integer(arguments.get(3), "local index") : 0;
                selectDebuggerLocalContext(session, threadIndex, depth, localIndex);
                return true;
            }
            if (arguments.size() == 5 && "local-set".equalsIgnoreCase(arguments.get(0))) {
                setDebuggerLocal(session,
                        integer(arguments.get(1), "thread index"),
                        integer(arguments.get(2), "frame depth"),
                        integer(arguments.get(3), "local index"), arguments.get(4));
                return true;
            }
            if (arguments.size() == 1 && ("enable".equalsIgnoreCase(arguments.get(0))
                    || "disable".equalsIgnoreCase(arguments.get(0)))) {
                boolean enabled = "enable".equalsIgnoreCase(arguments.get(0));
                if (!enabled && session.debugger().active()) session.debugger().restore();
                session.jvmti().configureDebugger(enabled);
                session.output().println("ok");
                return true;
            }
            if (arguments.size() == 5 && ("break".equalsIgnoreCase(arguments.get(0))
                    || "clear".equalsIgnoreCase(arguments.get(0)))) {
                boolean enabled = "break".equalsIgnoreCase(arguments.get(0));
                if (enabled) session.jvmti().configureDebugger(true);
                session.jvmti().setBreakpoint(arguments.get(1), arguments.get(2), arguments.get(3),
                        Long.parseLong(arguments.get(4)), enabled);
                session.output().println("ok");
                return true;
            }
            if (arguments.size() >= 4 && arguments.size() <= 7
                    && ("break-context".equalsIgnoreCase(arguments.get(0))
                    || "clear-context".equalsIgnoreCase(arguments.get(0)))) {
                boolean enabled = "break-context".equalsIgnoreCase(arguments.get(0));
                RemoteClass type = session.context().remoteClass();
                String methodName = arguments.get(1);
                String descriptor = arguments.get(2);
                boolean staticMethod = "<clinit>".equals(methodName)
                        || isStaticMethod(type, methodName, descriptor);
                JvmBreakpointCondition condition = !staticMethod && session.context().isObject()
                        ? JvmBreakpointCondition.receiver(session.context().remoteObject())
                        : JvmBreakpointCondition.any();
                if (arguments.size() > 4) {
                    condition = condition.calledFrom(arguments.get(4),
                            arguments.size() > 5 ? arguments.get(5) : "",
                            arguments.size() > 6 ? arguments.get(6) : "");
                }
                if (enabled) session.jvmti().configureDebugger(true);
                session.jvmti().setBreakpoint(type.className(), methodName, descriptor,
                        Long.parseLong(arguments.get(3)), condition, enabled);
                session.output().println("ok: " + condition.summary());
                return true;
            }
            if (!arguments.isEmpty() && "breakpoints".equalsIgnoreCase(arguments.get(0))
                    && arguments.size() <= 2) {
                if (arguments.size() == 2 && "clear-all".equalsIgnoreCase(arguments.get(1))) {
                    session.jvmti().clearManagedBreakpoints();
                    session.output().println("cleared all managed breakpoints");
                } else if (arguments.size() == 1) printManagedBreakpoints(session);
                else return InteractiveCli.usage(session, this);
                return true;
            }
            if (arguments.size() >= 5 && arguments.size() <= 6
                    && "event-break".equalsIgnoreCase(arguments.get(0))) {
                JvmEventBreakpointSpec spec;
                if ("entry".equalsIgnoreCase(arguments.get(1))) {
                    spec = JvmEventBreakpointSpec.methodEntry(arguments.get(2), arguments.get(3), arguments.get(4));
                } else if ("exit".equalsIgnoreCase(arguments.get(1))) {
                    spec = JvmEventBreakpointSpec.methodExit(arguments.get(2), arguments.get(3), arguments.get(4));
                } else return InteractiveCli.usage(session, this);
                if (arguments.size() == 6 && "subtypes".equalsIgnoreCase(arguments.get(5))) {
                    spec = spec.includingSubtypes();
                }
                session.jvmti().configureDebugger(true);
                session.output().println("installed " + session.jvmti().setEventBreakpoint(spec));
                return true;
            }
            if (arguments.size() == 2 && "exception-break".equalsIgnoreCase(arguments.get(0))) {
                session.jvmti().configureDebugger(true);
                session.output().println("installed " + session.jvmti().setEventBreakpoint(
                        JvmEventBreakpointSpec.exception(arguments.get(1))));
                return true;
            }
            if (!arguments.isEmpty() && "event-breakpoints".equalsIgnoreCase(arguments.get(0))
                    && arguments.size() <= 2) {
                if (arguments.size() == 2 && "clear-all".equalsIgnoreCase(arguments.get(1))) {
                    session.jvmti().clearManagedEventBreakpoints();
                    session.output().println("cleared all managed event breakpoints");
                } else if (arguments.size() == 1) {
                    List<JvmEventBreakpointInfo> values = session.jvmti().managedEventBreakpoints();
                    for (int index = 0; index < values.size(); index++) {
                        session.output().printf("[%d] %s id=%s%n", index, values.get(index), values.get(index).id());
                    }
                    if (values.isEmpty()) session.output().println("<no managed event breakpoints>");
                } else return InteractiveCli.usage(session, this);
                return true;
            }
            if (arguments.size() == 2 && "event-clear".equalsIgnoreCase(arguments.get(0))) {
                List<JvmEventBreakpointInfo> values = session.jvmti().managedEventBreakpoints();
                int index = integer(arguments.get(1), "event breakpoint index");
                if (index < 0 || index >= values.size()) {
                    throw new IllegalArgumentException("No event breakpoint at index " + index);
                }
                session.jvmti().clearEventBreakpoint(values.get(index));
                session.output().println("cleared event breakpoint " + index);
                return true;
            }
            if (arguments.size() == 6 && "watch".equalsIgnoreCase(arguments.get(0))) {
                boolean modification;
                if ("read".equalsIgnoreCase(arguments.get(1))) modification = false;
                else if ("write".equalsIgnoreCase(arguments.get(1))) modification = true;
                else return InteractiveCli.usage(session, this);
                boolean enabled;
                if ("set".equalsIgnoreCase(arguments.get(2))) enabled = true;
                else if ("clear".equalsIgnoreCase(arguments.get(2))) enabled = false;
                else return InteractiveCli.usage(session, this);
                session.jvmti().configureDebugger(true);
                session.jvmti().setFieldWatch(arguments.get(3), arguments.get(4), arguments.get(5),
                        modification, enabled);
                session.output().println("ok");
                return true;
            }
            if (!arguments.isEmpty() && "watches".equalsIgnoreCase(arguments.get(0))
                    && arguments.size() <= 2) {
                if (arguments.size() == 2 && "clear-all".equalsIgnoreCase(arguments.get(1))) {
                    session.jvmti().clearManagedFieldWatches();
                    session.output().println("cleared all managed field watches");
                } else if (arguments.size() == 1) printManagedWatches(session);
                else return InteractiveCli.usage(session, this);
                return true;
            }
            if (!arguments.isEmpty() && arguments.size() <= 2
                    && "continue".equalsIgnoreCase(arguments.get(0))) {
                if (arguments.size() == 2 && "all".equalsIgnoreCase(arguments.get(1))) {
                    if (session.debugger().active()) {
                        throw new IllegalStateException(
                                "Analysis freeze is active; use 'debugger thaw' to preserve original stops");
                    }
                    session.jvmti().continueAllExecutions();
                } else if (arguments.size() == 2) {
                    resumeDebuggerThread(session, integer(arguments.get(1), "thread index"), false);
                } else session.jvmti().continueExecution();
                session.output().println("running");
                return true;
            }
            if (!arguments.isEmpty() && arguments.size() <= 2
                    && "step".equalsIgnoreCase(arguments.get(0))) {
                if (arguments.size() == 2) {
                    resumeDebuggerThread(session, integer(arguments.get(1), "thread index"), true);
                } else session.jvmti().stepInstruction();
                session.output().println("stepping");
                return true;
            }
            if (!arguments.isEmpty() && arguments.size() <= 2
                    && ("step-out".equalsIgnoreCase(arguments.get(0))
                    || "finish".equalsIgnoreCase(arguments.get(0)))) {
                if (arguments.size() == 2) {
                    List<JvmDebuggerState> states = session.jvmti().debuggerStates();
                    try {
                        session.jvmti().stepOut(pausedDebuggerState(states,
                                integer(arguments.get(1), "thread index")).thread());
                    } finally { closeDebuggerStates(states); }
                } else session.jvmti().stepOut();
                session.output().println("running until current frame returns");
                return true;
            }
            if (arguments.size() == 3 && "force-return".equalsIgnoreCase(arguments.get(0))) {
                List<JvmDebuggerState> states = session.jvmti().debuggerStates();
                try (RemoteArgumentList values = RemoteArgumentList.resolve(session,
                        Collections.singletonList(arguments.get(2)))) {
                    session.jvmti().forceEarlyReturn(pausedDebuggerState(states,
                            integer(arguments.get(1), "thread index")).thread(), values.only());
                } finally { closeDebuggerStates(states); }
                session.output().println("early return scheduled; continue the thread to apply it");
                return true;
            }
            if (arguments.size() == 2 && "force-return-void".equalsIgnoreCase(arguments.get(0))) {
                List<JvmDebuggerState> states = session.jvmti().debuggerStates();
                try {
                    session.jvmti().forceEarlyReturnVoid(pausedDebuggerState(states,
                            integer(arguments.get(1), "thread index")).thread());
                } finally { closeDebuggerStates(states); }
                session.output().println("void early return scheduled; continue the thread to apply it");
                return true;
            }
            return InteractiveCli.usage(session, this);
        }

        private static void printDebuggerStates(TargetSession session) {
            List<JvmDebuggerState> states = session.jvmti().debuggerStates();
            try {
                int index = 0;
                for (JvmDebuggerState state : states) {
                    if (!state.paused()) {
                        if (states.size() == 1) session.output().println(state);
                        continue;
                    }
                    session.output().printf("[%d] %s thread=%s#%d%n", index++, state,
                            state.thread().className(), state.thread().remoteId());
                }
            } finally {
                closeDebuggerStates(states);
            }
        }

        private static void printCurrentLocations(TargetSession session) {
            List<JvmDebuggerState> states = session.jvmti().debuggerStates();
            try {
                int index = 0;
                for (JvmDebuggerState state : states) {
                    if (!state.paused() || state.thread() == null) continue;
                    List<JvmStackFrame> frames = session.jvmti().stackFrames(state.thread(), 48);
                    int preferred = preferredFrameDepth(frames);
                    JvmStackFrame view = frames.isEmpty() ? null : frames.get(preferred);
                    session.output().printf("[%d] %s actual=%s.%s%s@%d reason=%s%n",
                            index++, state.thread().displayValue(), state.className(),
                            state.methodName(), state.descriptor(), state.location(), state.reason());
                    if (view != null) {
                        session.output().printf("    view=%s%s%n", view.display(),
                                view.depth() == 0 ? " (actual top)"
                                        : " (nearest inspectable Java/application caller)");
                    }
                }
                if (index == 0) {
                    session.output().println("<no paused Java threads; run 'debugger pause-all' or pause one thread>");
                }
            } finally {
                closeDebuggerStates(states);
            }
        }

        private static void printCurrentFrame(TargetSession session, int threadIndex,
                int requestedDepth, int radius) {
            if (radius < 0) throw new IllegalArgumentException("instruction radius must not be negative");
            List<JvmDebuggerState> states = session.jvmti().debuggerStates();
            try {
                JvmDebuggerState state = pausedDebuggerState(states, threadIndex);
                printCurrentFrame(session, state, requestedDepth, radius);
            } finally {
                closeDebuggerStates(states);
            }
        }

        private static void printCurrentFrame(TargetSession session, JvmDebuggerState state,
                int requestedDepth, int radius) {
            if (radius < 0) throw new IllegalArgumentException("instruction radius must not be negative");
            List<JvmDebuggerLocal> locals = new ArrayList<JvmDebuggerLocal>();
            try {
                List<JvmStackFrame> frames = session.jvmti().stackFrames(state.thread(), 64);
                if (frames.isEmpty()) throw new IllegalStateException("The paused thread has no Java stack frames");
                int depth = requestedDepth < 0 ? preferredFrameDepth(frames) : requestedDepth;
                if (depth < 0 || depth >= frames.size()) {
                    throw new IllegalArgumentException("No stack frame at depth " + depth);
                }
                JvmStackFrame frame = frames.get(depth);
                session.output().println("actual-top=" + frames.get(0).display());
                session.output().println("selected=" + frame.display());
                if (frame.depth() > 0) {
                    session.output().println("note=caller frame is inspectable but is not the currently executing top frame");
                }
                if (!frame.hasJavaLocation()) {
                    session.output().println("bytecode=<native frame; no Java Code attribute or BCI>");
                    int fallback = preferredFrameDepth(frames);
                    if (fallback != depth && frames.get(fallback).hasJavaLocation()) {
                        session.output().println("suggested=" + frames.get(fallback).display());
                    }
                    return;
                }
                RemoteClass owner = session.findClass(frame.className());
                ClassFileMethod method = owner.bytecode(frame.methodName(), frame.descriptor());
                List<BytecodeInstruction> instructions = method.instructions();
                int center = nearestInstruction(instructions, frame.location());
                int first = Math.max(0, center - radius);
                int last = Math.min(instructions.size(), center + radius + 1);
                session.output().printf("method=%s.%s%s maxStack=%d maxLocals=%d%n",
                        frame.className(), frame.methodName(), frame.descriptor(),
                        method.maxStack(), method.maxLocals());
                for (int index = first; index < last; index++) {
                    BytecodeInstruction instruction = instructions.get(index);
                    session.output().printf("%s L%-5s %s%n", index == center ? ">" : " ",
                            instruction.sourceLine() < 0 ? "-"
                                    : Integer.toString(instruction.sourceLine()),
                            instruction.format());
                }
                locals.addAll(session.jvmti().debuggerLocals(state.thread(), depth));
                session.output().println("locals:");
                if (locals.isEmpty()) session.output().println("  <no readable locals>");
                for (JvmDebuggerLocal local : locals) {
                    session.output().printf("  [%d] %s %s = %s%n", local.slot(),
                            local.name(), local.descriptor(), local.available()
                                    ? local.value() == null ? "null" : local.value().displayValue()
                                    : "<" + local.error() + ">");
                }
            } finally {
                for (JvmDebuggerLocal local : locals) local.close();
            }
        }

        private static void sampleRunningThread(TargetSession session, int threadIndex,
                int requestedDepth, int radius) {
            List<RemoteJvmtiThread> threads = session.jvmti().threads();
            List<JvmDebuggerState> states = new ArrayList<JvmDebuggerState>();
            JvmDebuggerState sample = null;
            boolean pauseCreated = false;
            try {
                if (threadIndex < 0 || threadIndex >= threads.size()) {
                    throw new IllegalArgumentException("No JVM thread at index " + threadIndex);
                }
                RemoteJvmtiThread thread = threads.get(threadIndex);
                if (thread.debuggerPaused()) {
                    throw new IllegalStateException("Thread is already debugger-paused; use debugger current");
                }
                session.jvmti().configureDebugger(true);
                session.jvmti().pauseExecution(thread.object(), "live_sample");
                pauseCreated = true;
                states.addAll(session.jvmti().debuggerStates());
                for (JvmDebuggerState candidate : states) {
                    if (!candidate.paused() || !"live_sample".equals(candidate.reason())
                            || candidate.thread() == null) continue;
                    String display = candidate.thread().displayValue();
                    if (display != null && display.contains(thread.name())) {
                        sample = candidate;
                        break;
                    }
                }
                if (sample == null) {
                    throw new IllegalStateException("Live sample state was not returned by the target JVM");
                }
                session.output().println("sample-thread=" + thread.name()
                        + " (resumed immediately after capture)");
                printCurrentFrame(session, sample, requestedDepth, radius);
            } finally {
                if (pauseCreated) {
                    try {
                        if (sample != null) session.jvmti().continueExecution(sample.thread());
                        else if (threadIndex >= 0 && threadIndex < threads.size()) {
                            session.jvmti().continueExecution(threads.get(threadIndex).object());
                        }
                    } catch (RuntimeException ignored) { }
                }
                closeDebuggerStates(states);
                for (RemoteJvmtiThread thread : threads) thread.close();
            }
        }

        private static int preferredFrameDepth(List<JvmStackFrame> frames) {
            if (frames.isEmpty() || frames.get(0).hasJavaLocation()) return 0;
            for (JvmStackFrame frame : frames) {
                if (frame.hasJavaLocation() && !frame.isPlatformFrame()) return frame.depth();
            }
            for (JvmStackFrame frame : frames) if (frame.hasJavaLocation()) return frame.depth();
            return 0;
        }

        private static int nearestInstruction(List<BytecodeInstruction> instructions, long bci) {
            if (instructions.isEmpty()) return 0;
            int closest = 0;
            long distance = Long.MAX_VALUE;
            for (int index = 0; index < instructions.size(); index++) {
                long candidate = Math.abs(instructions.get(index).offset() - bci);
                if (candidate < distance) { distance = candidate; closest = index; }
            }
            return closest;
        }

        private static void printAllDebuggerThreads(TargetSession session) {
            List<RemoteJvmtiThread> threads = session.jvmti().threads();
            try {
                for (int index = 0; index < threads.size(); index++) {
                    RemoteJvmtiThread thread = threads.get(index);
                    session.output().printf("[%d] %s  %s%s  priority=%d daemon=%s%n", index,
                            thread.name(), thread.stateSummary(),
                            thread.debuggerPaused() ? " DEBUG-PAUSED" : "",
                            thread.priority(), thread.daemon());
                }
            } finally {
                for (RemoteJvmtiThread thread : threads) thread.close();
            }
        }

        private static void printFreezeReport(TargetSession session, DebuggerFreezeReport report) {
            session.output().println(report.summary());
            for (DebuggerFreezeReport.Entry entry : report.entries()) {
                session.output().printf("%-9s %-28s %-20s %s%n",
                        entry.action().name(), entry.threadName(), entry.originalStateSummary(),
                        entry.detail());
            }
        }

        private static void printManagedBreakpoints(TargetSession session) {
            List<JvmBreakpointInfo> values = session.jvmti().managedBreakpoints();
            for (int index = 0; index < values.size(); index++) {
                JvmBreakpointInfo value = values.get(index);
                session.output().printf("[%d] %s.%s%s @%d  %s%n", index, value.className(),
                        value.methodName(), value.descriptor(), value.location(),
                        value.conditionSummary());
            }
            if (values.isEmpty()) session.output().println("<no managed breakpoints>");
        }

        private static void printManagedWatches(TargetSession session) {
            List<JvmFieldWatchInfo> values = session.jvmti().managedFieldWatches();
            for (int index = 0; index < values.size(); index++) {
                JvmFieldWatchInfo value = values.get(index);
                session.output().printf("[%d] %s %s.%s %s  %s%n", index, value.kind(),
                        value.className(), value.fieldName(), value.descriptor(),
                        value.objectSpecific() ? "receiver#" + value.receiverId() : "all instances");
            }
            if (values.isEmpty()) session.output().println("<no managed field watches>");
        }

        private static void selectDebuggerLocalContext(TargetSession session,
                int threadIndex, int depth, int localIndex) {
            List<JvmDebuggerState> states = session.jvmti().debuggerStates();
            List<JvmDebuggerLocal> locals = new ArrayList<JvmDebuggerLocal>();
            try {
                JvmDebuggerState state = pausedDebuggerState(states, threadIndex);
                locals.addAll(session.jvmti().debuggerLocals(state.thread(), depth));
                if (localIndex < 0 || localIndex >= locals.size()) {
                    throw new IllegalArgumentException("No local at index " + localIndex);
                }
                JvmDebuggerLocal selected = locals.get(localIndex);
                if (!selected.available() || selected.value() == null) {
                    throw new IllegalStateException("Local is unavailable: " + selected.error());
                }
                session.context().select(selected.value(), null,
                        session.operations().debuggerLocalAssignment(state.sequence(), depth,
                                selected.slot(), selected.descriptor()));
                locals.remove(localIndex); // Context now owns the selected remote value handle.
                session.output().println("context <- local " + selected.name()
                        + " slot=" + selected.slot() + " = " + selected.value().displayValue());
            } finally {
                for (JvmDebuggerLocal local : locals) local.close();
                closeDebuggerStates(states);
            }
        }

        private static boolean isStaticMethod(RemoteClass type, String name, String descriptor) {
            for (RemoteMethod method : type.getStaticMethods()) {
                if (method.name().equals(name) && method.descriptor().equals(descriptor)) return true;
            }
            return false;
        }

        private static void setDebuggerLocal(TargetSession session, int threadIndex,
                int depth, int localIndex, String expression) {
            List<JvmDebuggerState> states = session.jvmti().debuggerStates();
            List<JvmDebuggerLocal> locals = new ArrayList<JvmDebuggerLocal>();
            try {
                JvmDebuggerState state = pausedDebuggerState(states, threadIndex);
                locals.addAll(session.jvmti().debuggerLocals(state.thread(), depth));
                if (localIndex < 0 || localIndex >= locals.size()) {
                    throw new IllegalArgumentException("No local at index " + localIndex);
                }
                JvmDebuggerLocal selected = locals.get(localIndex);
                if (selected.descriptor() == null || selected.descriptor().isEmpty()
                        || "?".equals(selected.descriptor())) {
                    throw new IllegalStateException("Local type is unknown; select a typed local slot");
                }
                try (RemoteArgumentList values = RemoteArgumentList.resolve(
                        session, Collections.singletonList(expression))) {
                    session.jvmti().setDebuggerLocal(state.thread(), depth, selected.slot(),
                            selected.descriptor(), values.only());
                }
                session.output().printf("local updated: frame=%d slot=%d %s%n",
                        depth, selected.slot(), selected.name());
            } finally {
                for (JvmDebuggerLocal local : locals) local.close();
                closeDebuggerStates(states);
            }
        }

        private static void resumeDebuggerThread(TargetSession session, int index, boolean step) {
            List<JvmDebuggerState> states = session.jvmti().debuggerStates();
            try {
                JvmDebuggerState state = pausedDebuggerState(states, index);
                if (step) session.jvmti().stepInstruction(state.thread());
                else session.jvmti().continueExecution(state.thread());
            } finally {
                closeDebuggerStates(states);
            }
        }

        private static JvmDebuggerState pausedDebuggerState(List<JvmDebuggerState> states, int wanted) {
            int index = 0;
            for (JvmDebuggerState state : states) {
                if (!state.paused() || state.thread() == null) continue;
                if (index++ == wanted) return state;
            }
            throw new IllegalArgumentException("No paused debugger thread at index " + wanted);
        }

        private static void closeDebuggerStates(List<JvmDebuggerState> states) {
            for (JvmDebuggerState state : states) state.close();
        }
    }

    private static class TuiCommand extends ShellCommand<TargetSession> {
        private TuiCommand() {
            super("tui", "tui", "Switches from the command prompt to the full-screen TUI.");
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) {
            if (!arguments.isEmpty()) return InteractiveCli.usage(session, this);
            session.requestTui();
            return false;
        }
    }

    private static final class ParsedAnalysisOptions {
        private final List<String> positionals = new ArrayList<String>();
        private DecompilerEngine engine = DecompilerEngine.CFR;
        private Path output;

        private static ParsedAnalysisOptions parse(List<String> arguments) {
            ParsedAnalysisOptions result = new ParsedAnalysisOptions();
            for (int index = 0; index < arguments.size(); index++) {
                String value = arguments.get(index);
                if ("--engine".equalsIgnoreCase(value) && index + 1 < arguments.size()) {
                    result.engine = DecompilerEngine.parse(arguments.get(++index));
                } else if ("--out".equalsIgnoreCase(value) && index + 1 < arguments.size()) {
                    result.output = Paths.get(arguments.get(++index));
                } else if (value.startsWith("--")) {
                    throw new IllegalArgumentException("Unknown/incomplete analysis option: " + value);
                } else result.positionals.add(value);
            }
            return result;
        }
    }

    private static void outputAnalysis(TargetSession session, String text, Path output) throws IOException {
        if (output == null) {
            session.output().print(text);
            if (!text.endsWith("\n")) session.output().println();
            return;
        }
        Path absolute = output.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(absolute, text.getBytes(StandardCharsets.UTF_8));
        session.output().printf("Wrote %,d character(s) to %s%n", text.length(), absolute);
    }

    private static JvmClassPathCatalog.ClassEntry unloadedCatalogEntry(
            TargetSession session, String className, RuntimeException original) throws IOException {
        boolean notLoaded = false;
        for (Throwable current = original; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && (message.contains("Class is not loaded")
                    || message.contains("Class not loaded")
                    || message.contains("not a loaded class"))) {
                notLoaded = true;
                break;
            }
        }
        if (!notLoaded) throw original;
        JvmClassPathCatalog.ClassEntry entry = session.refreshClassPathCatalog().find(className);
        if (entry == null) throw original;
        return entry;
    }

    private static class ForwardCommand extends ShellCommand<TargetSession> {
        private final boolean acceptsArguments;

        private ForwardCommand(String name, String usage, String description, boolean acceptsArguments) {
            super(name, usage, description);
            this.acceptsArguments = acceptsArguments;
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) {
            if (!acceptsArguments && !arguments.isEmpty()) return InteractiveCli.usage(session, this);
            CommandReply reply = session.server().execute(CommandLine.of(name(), arguments.toArray(new String[0])));
            PrintStream destination = reply.successful() ? session.output() : session.error();
            if (!reply.output().isEmpty()) destination.println(reply.output());
            return true;
        }
    }

    private static class VersionCommand extends ShellCommand<TargetSession> {
        private VersionCommand() {
            super("version", "version", "Shows controller, target Agent and protocol versions.", "ver");
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) {
            if (!arguments.isEmpty()) return InteractiveCli.usage(session, this);
            session.output().printf("controller=%s%ntarget.agent=%s%nprotocol=%d%n",
                    BuildInfo.VERSION, session.server().agentVersion(), (int) Protocol.VERSION);
            return true;
        }
    }

    private static class BackCommand extends ShellCommand<TargetSession> {
        private BackCommand() {
            super("back", "back", "Disconnects and returns to the JVMRTDP controller prompt.");
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) {
            return arguments.isEmpty() ? false : InteractiveCli.usage(session, this);
        }
    }

    private static class ExitCommand extends ShellCommand<TargetSession> {
        private ExitCommand() {
            super("exit", "exit", "Disconnects and closes the JVMRTDP controller.", "quit");
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) {
            if (!arguments.isEmpty()) return InteractiveCli.usage(session, this);
            session.requestControllerExit();
            return false;
        }
    }

    private static class HelpCommand extends ShellCommand<TargetSession> {
        private final ShellCommandRegistry<TargetSession> commands;

        private HelpCommand(ShellCommandRegistry<TargetSession> commands) {
            super("help", "help [command|syntax]", "Lists commands and context-language examples.", "?");
            this.commands = commands;
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) {
            if (arguments.size() > 1) return InteractiveCli.usage(session, this);
            if (arguments.size() == 1 && "syntax".equalsIgnoreCase(arguments.get(0))) {
                session.output().println("Context navigation:");
                session.output().println("  context class java.lang.System");
                session.output().println("  context static field app.State INSTANCE -> field users -> value --deep");
                session.output().println("  context back | context save home | context use home");
                session.output().println("  stack | stack pop | stack peek 2 | stack swap | stack pick 3");
                session.output().println("  context as app.Parent | field app.Parent::value | invoke app.Parent::run ()V");
                session.output().println("Invocation and mutation:");
                session.output().println("  read name | read Parent::name | read static app.Config VALUE   # context unchanged");
                session.output().println("  set name string:newName | set Parent::count int:7               # current object");
                session.output().println("  invoke methodName (I)Ljava/lang/String; int:7");
                session.output().println("  static invoke app.Tools run ()V");
                session.output().println("  construct app.Model (Ljava/lang/String;)V string:name");
                session.output().println("  invoke accept (Lapp/Model;)V {new app.Model ()V}");
                session.output().println("  set service {static app.Services create ()Lapp/Service;}");
                session.output().println("  resolve {context -> field service -> invoke status ()Ljava/lang/String;}");
                session.output().println("  set field name string:newName | set index 2 int:9");
                session.output().println("Search (glob '*' matches any text; '?' matches one character):");
                session.output().println("  find package java.* --limit 100");
                session.output().println("  find class *Service --package com.example.** --extends *Base --implements *Api");
                session.output().println("  find interface *Listener | find extends java.util.Abstract* *Map*");
                session.output().println("  find field *cache* --class com.example.* --type java.util.Map --static");
                session.output().println("  find method get* --class com.example.* --returns byte[] --params \"java.lang.String,*\"");
                session.output().println("  find unloaded class com.example.* | find unloaded method run --class com.example.*");
                session.output().println("  class fields virtual *name* | context list methods get*");
                session.output().println("Packages and class files:");
                session.output().println("  package                 # root packages plus classes in the default package");
                session.output().println("  package com.example     # immediate children only");
                session.output().println("  dumpclass com.example.Type build/dump/Type.class");
                session.output().println("  dump package com.example build/dump --recursive --match *Service --limit 500");
                session.output().println("Literals: null, true, int:1, class:app.Type, enum:app.Mode:FAST, $variable, this");
                session.output().println("{new ...}, {invoke ...}, {static ...} and {... -> ...} are nestable value expressions.");
                session.output().println("Top-level unquoted -> is temporary: current context, stack and bookmarks are restored afterward.");
                return true;
            }
            if (arguments.size() == 1) {
                ShellCommand<TargetSession> command = commands.find(arguments.get(0));
                if (command == null) session.error().println("Unknown target command: " + arguments.get(0));
                else {
                    session.output().println("Usage: " + command.usage());
                    session.output().println(command.description());
                }
                return true;
            }
            for (ShellCommand<TargetSession> command : commands.commands()) {
                session.output().printf("%-14s %s%n", command.name(), command.description());
            }
            return true;
        }
    }

    private static RemoteObject readStatic(RemoteClass type, FieldSelection selection) {
        return selectArrayElement(selection.resolveStatic(type).readStatic(), selection);
    }

    private static RemoteObject readVirtual(
            RemoteClass type, RemoteObject receiver, FieldSelection selection) {
        return selectArrayElement(selection.resolveVirtual(type).read(receiver), selection);
    }

    private static RemoteObject selectArrayElement(RemoteObject fieldValue, FieldSelection selection) {
        if (selection.index == null) return fieldValue;
        try {
            return fieldValue.arrayGet(selection.index.intValue());
        } finally {
            fieldValue.close();
        }
    }

    private static void selectInvocationResult(TargetSession session, RemoteObject value) {
        if ("void".equals(value.className())) {
            try {
                session.output().println("=> void (context unchanged)");
            } finally {
                value.close();
            }
        } else {
            selectResult(session, value);
        }
    }

    private static void selectResult(TargetSession session, RemoteObject value) {
        session.context().select(value);
        printContext(session);
    }

    private static void selectResult(TargetSession session, RemoteObject value,
            RemoteContext.Assignment assignment) {
        session.context().select(value, null, assignment);
        printContext(session);
    }

    private static void printContext(TargetSession session) {
        session.output().println("context = " + session.context().description());
    }

    private static boolean executeStackOperation(TargetSession session, List<String> arguments) {
        String operation = arguments.isEmpty() ? "list" : lower(arguments.get(0));
        if (("list".equals(operation) || "history".equals(operation) || "stack".equals(operation))
                && arguments.size() <= 2) {
            int limit = arguments.size() == 2 ? integer(arguments.get(1), "limit") : 64;
            printStack(session, limit);
            return true;
        }
        if ("depth".equals(operation) && arguments.size() == 1) {
            session.output().println(session.context().depth());
            return true;
        }
        if (("pop".equals(operation) || "back".equals(operation) || "drop".equals(operation))
                && arguments.size() <= 2) {
            session.context().pop(arguments.size() == 2 ? integer(arguments.get(1), "count") : 1);
            printContext(session);
            return true;
        }
        if ("peek".equals(operation) && arguments.size() <= 2) {
            int index = arguments.size() == 2 ? integer(arguments.get(1), "stack index") : 0;
            session.output().printf("[%d] %s%n", index, session.context().peek(index));
            return true;
        }
        if (("dup".equals(operation) || "push".equals(operation)) && arguments.size() == 1) {
            session.context().duplicate();
            printStack(session, 8);
            return true;
        }
        if ("swap".equals(operation) && arguments.size() == 1) {
            session.context().swap();
            printStack(session, 8);
            return true;
        }
        if ("pick".equals(operation) && arguments.size() == 2) {
            session.context().pick(integer(arguments.get(1), "stack index"));
            printContext(session);
            return true;
        }
        if ("clear".equals(operation) && arguments.size() == 1) {
            session.context().clear();
            printContext(session);
            return true;
        }
        throw new IllegalArgumentException(
                "Stack usage: stack [list [limit]|depth|pop [count]|peek [index]|dup|swap|pick <index>|clear]");
    }

    private static void printStack(TargetSession session, int limit) {
        List<String> stack = session.context().stack(limit);
        session.output().printf("context stack: depth=%d%n", session.context().depth());
        for (int index = 0; index < stack.size(); index++) {
            session.output().printf("%s[%d] %s%n", index == 0 ? "-> " : "   ", index, stack.get(index));
        }
        if (session.context().depth() > stack.size()) {
            session.output().printf("   ... %d older context(s)%n", session.context().depth() - stack.size());
        }
    }

    private static void printObject(TargetSession session, RemoteObject value) {
        session.output().printf("%s  [type=%s, id=%d]%n",
                value.displayValue(), value.className(), value.remoteId());
    }

    private static void printReadObject(TargetSession session, RemoteObject value) {
        session.output().printf("%s  [type=%s]%n", value.displayValue(), value.className());
    }

    private static void printDeep(TargetSession session, RemoteObject value, int limit) {
        if (limit < 1 || limit > 10_000) throw new IllegalArgumentException("limit must be between 1 and 10000");
        RemoteObjectDebugInfo debug = value.debugInfo();
        printObject(session, value);
        if ("array".equals(debug.shape())) {
            int length = value.arrayLength();
            for (int index = 0; index < Math.min(length, limit); index++) {
                try (RemoteObject element = value.arrayGet(index)) {
                    session.output().printf("[%d] %s  [type=%s]%n", index, element.displayValue(), element.className());
                }
            }
            if (length > limit) session.output().printf("... %,d more elements%n", length - limit);
        } else if ("map".equals(debug.shape())) {
            List<RemoteMapEntry> entries = value.mapEntries(limit);
            for (RemoteMapEntry entry : entries) {
                try {
                    session.output().printf("%s => %s%n",
                            entry.key().displayValue(), entry.value().displayValue());
                } finally {
                    entry.close();
                }
            }
            if (!debug.size().isEmpty() && Integer.parseInt(debug.size()) > entries.size()) {
                session.output().printf("... %,d more entries%n", Integer.parseInt(debug.size()) - entries.size());
            }
        } else if ("iterable".equals(debug.shape())) {
            List<RemoteObject> elements = value.iterableElements(limit);
            for (int index = 0; index < elements.size(); index++) {
                try (RemoteObject element = elements.get(index)) {
                    session.output().printf("[%d] %s  [type=%s]%n", index, element.displayValue(), element.className());
                }
            }
            if (!debug.size().isEmpty() && Integer.parseInt(debug.size()) > elements.size()) {
                session.output().printf("... %,d more elements%n", Integer.parseInt(debug.size()) - elements.size());
            }
        }
    }

    private static void printClassInfo(TargetSession session, RemoteClassInfo info) {
        String kind = (info.modifiers() & 0x2000) != 0 ? "annotation"
                : info.isInterface() ? "interface" : info.isEnum() ? "enum" : info.isArray() ? "array" : "class";
        session.output().printf("name=%s%nkind=%s%nmodifiers=%s (0x%x)%nsuper=%s%ninterfaces=%s%n",
                info.name(), kind, Modifier.toString(info.modifiers()), info.modifiers(),
                info.superclass().isEmpty() ? "<none>" : info.superclass(), info.interfaces());
    }

    private static void printFields(TargetSession session, RemoteClass type, boolean statics, boolean virtuals) {
        printFields(session, type, statics, virtuals, "*");
    }

    private static void printFields(
            TargetSession session, RemoteClass type, boolean statics, boolean virtuals, String glob) {
        GlobMatcher matcher = GlobMatcher.of(glob);
        if (statics) {
            for (RemoteField field : type.getStaticFields()) if (matcher.matches(field.name())) printField(session, field);
        }
        if (virtuals) {
            for (RemoteField field : type.getVirtualFields()) if (matcher.matches(field.name())) printField(session, field);
        }
    }

    private static void printField(TargetSession session, RemoteField field) {
        session.output().printf("%-8s %s %s.%s  [%s; descriptor=%s]%n",
                field.isStatic() ? "static" : "field", field.typeName(),
                field.declaringClass(), field.name(), Modifier.toString(field.modifiers()), field.descriptor());
    }

    private static void printMethods(TargetSession session, RemoteClass type, boolean statics, boolean virtuals) {
        printMethods(session, type, statics, virtuals, "*");
    }

    private static void printMethods(
            TargetSession session, RemoteClass type, boolean statics, boolean virtuals, String glob) {
        GlobMatcher matcher = GlobMatcher.of(glob);
        if (statics) {
            for (RemoteMethod method : type.getStaticMethods()) if (matcher.matches(method.name())) printMethod(session, method);
        }
        if (virtuals) {
            for (RemoteMethod method : type.getVirtualMethods()) if (matcher.matches(method.name())) printMethod(session, method);
        }
    }

    private static void printMethod(TargetSession session, RemoteMethod method) {
        session.output().printf("%-8s %s %s.%s(%s)  [%s; descriptor=%s]%n",
                method.isStatic() ? "static" : "virtual", method.returnTypeName(),
                method.declaringClass(), method.name(), join(method.parameterTypeNames()),
                Modifier.toString(method.modifiers()), method.descriptor());
    }

    private static boolean usage(TargetSession session, ShellCommand<?> command) {
        session.error().println("Usage: " + command.usage());
        return true;
    }

    private static String promptContext(TargetSession session) {
        String value = session.context().refreshedDescription();
        if (value.length() > 52) value = value.substring(0, 27) + "..." + value.substring(value.length() - 22);
        return value;
    }

    private static String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(", ");
            result.append(value);
        }
        return result.toString();
    }

    private static String lower(String value) {
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    private static int integer(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer: " + value);
        }
    }

    private static boolean isContextOperation(String value) {
        return Arrays.asList("static", "field", "index", "value", "list", "back", "clear", "save", "use",
                "bookmarks", "stack", "history", "pop", "peek", "dup", "push", "swap", "pick", "depth",
                "drop", "as", "runtime").contains(value);
    }

    private static boolean isStackOperation(String value) {
        return Arrays.asList("stack", "history", "back", "pop", "drop", "peek", "dup", "push", "swap",
                "pick", "depth").contains(value);
    }

    private static String commandText(List<String> tokens) {
        if (tokens.size() == 1) return tokens.get(0);
        StringBuilder result = new StringBuilder();
        for (String token : tokens) {
            if (result.length() != 0) result.append(' ');
            result.append("->".equals(token) ? token : CommandLine.quote(token));
        }
        return result.toString();
    }

    static List<String> splitPipeline(String source) {
        List<String> result = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;
        int expressionDepth = 0;
        for (int index = 0; index < source.length(); index++) {
            char value = source.charAt(index);
            if (escaped) {
                current.append(value);
                escaped = false;
            } else if (value == '\\' && quoted) {
                current.append(value);
                escaped = true;
            } else if (value == '"') {
                quoted = !quoted;
                current.append(value);
            } else if (!quoted && value == '{') {
                expressionDepth++;
                current.append(value);
            } else if (!quoted && value == '}') {
                if (expressionDepth == 0) throw new IllegalArgumentException("Unexpected } in command line");
                expressionDepth--;
                current.append(value);
            } else if (!quoted && expressionDepth == 0 && value == '-'
                    && index + 1 < source.length() && source.charAt(index + 1) == '>') {
                String segment = current.toString().trim();
                if (segment.isEmpty()) throw new IllegalArgumentException("Reference chain contains an empty command");
                result.add(segment);
                current.setLength(0);
                index++;
            } else {
                current.append(value);
            }
        }
        if (quoted) throw new IllegalArgumentException("Unclosed quote in command line");
        if (expressionDepth != 0) throw new IllegalArgumentException("Unclosed { in command line");
        String segment = current.toString().trim();
        if (!segment.isEmpty()) result.add(segment);
        else if (!result.isEmpty()) throw new IllegalArgumentException("Reference chain contains an empty command");
        if (result.isEmpty()) result.add("");
        return result;
    }

    private static String bytes(long value) {
        if (value < 0) return "unbounded";
        double scaled = value;
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        int unit = 0;
        while (scaled >= 1024 && unit < units.length - 1) {
            scaled /= 1024;
            unit++;
        }
        return String.format(java.util.Locale.ROOT, "%.1f %s", scaled, units[unit]);
    }

    private static String duration(long millis) {
        long seconds = millis / 1000;
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainder = seconds % 60;
        return String.format(java.util.Locale.ROOT, "%dd %02d:%02d:%02d", days, hours, minutes, remainder);
    }

    private static class FieldSelection {
        private final String declaringClass;
        private final String field;
        private final Integer index;

        private FieldSelection(String declaringClass, String field, Integer index) {
            this.declaringClass = declaringClass;
            this.field = field;
            this.index = index;
        }

        private static FieldSelection parse(String expression) {
            int open = expression.lastIndexOf('[');
            String member = open < 0 || !expression.endsWith("]")
                    ? expression : expression.substring(0, open);
            Integer arrayIndex = null;
            if (open >= 0 && expression.endsWith("]")) {
                String index = expression.substring(open + 1, expression.length() - 1);
                if ("x".equalsIgnoreCase(index)) {
                    throw new IllegalArgumentException("Replace [X] with a numeric array index, for example [0]");
                }
                arrayIndex = Integer.valueOf(integer(index, "array index"));
            }
            int qualifier = member.lastIndexOf("::");
            String declaringClass = qualifier < 0 ? null : member.substring(0, qualifier);
            String field = qualifier < 0 ? member : member.substring(qualifier + 2);
            if (field.isEmpty()) throw new IllegalArgumentException("Field name must not be empty");
            if (qualifier >= 0 && declaringClass.isEmpty()) {
                throw new IllegalArgumentException("Declaring class must not be empty before ::");
            }
            return new FieldSelection(declaringClass, field, arrayIndex);
        }

        private RemoteField resolveStatic(RemoteClass type) {
            return declaringClass == null ? type.getStaticField(field)
                    : type.getStaticField(declaringClass, field);
        }

        private RemoteField resolveVirtual(RemoteClass type) {
            return declaringClass == null ? type.getVirtualField(field)
                    : type.getVirtualField(declaringClass, field);
        }
    }

    private static class MethodSelection {
        private final String declaringClass;
        private final String method;

        private MethodSelection(String declaringClass, String method) {
            this.declaringClass = declaringClass;
            this.method = method;
        }

        private static MethodSelection parse(String expression) {
            int qualifier = expression.lastIndexOf("::");
            String declaringClass = qualifier < 0 ? null : expression.substring(0, qualifier);
            String method = qualifier < 0 ? expression : expression.substring(qualifier + 2);
            if (method.isEmpty()) throw new IllegalArgumentException("Method name must not be empty");
            if (qualifier >= 0 && declaringClass.isEmpty()) {
                throw new IllegalArgumentException("Declaring class must not be empty before ::");
            }
            return new MethodSelection(declaringClass, method);
        }

        private RemoteMethod resolveStatic(RemoteClass type, String descriptor) {
            return declaringClass == null ? type.getStaticMethod(method, descriptor)
                    : type.getStaticMethod(declaringClass, method, descriptor);
        }

        private RemoteMethod resolveVirtual(RemoteClass type, String descriptor) {
            return declaringClass == null ? type.getVirtualMethod(method, descriptor)
                    : type.getVirtualMethod(declaringClass, method, descriptor);
        }
    }
}
