package nhcm.jvmrtdp.controllerside;

import nhcm.jvmrtdp.BuildInfo;
import nhcm.jvmrtdp.command.CommandLine;
import nhcm.jvmrtdp.controllerside.command.ShellCommand;
import nhcm.jvmrtdp.controllerside.command.ShellCommandRegistry;
import nhcm.jvmrtdp.controllerside.script.ScriptEngine;
import nhcm.jvmrtdp.controllerside.script.ScriptCommandExecutor;
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
import java.io.PrintStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Context-oriented command prompt for one authenticated target JVM session. */
public class InteractiveCli {
    private static final int DEFAULT_EXPANSION_LIMIT = 32;

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
        try (TargetSession session = new TargetSession(server, output, error)) {
            output.println("Target prompt ready. Use 'help syntax' for the context-oriented command language.");
            while (server.isOpen() && !Thread.currentThread().isInterrupted()) {
                output.printf("target[%d|%s]> ", server.process().pid(), promptContext(session));
                output.flush();
                String rawLine;
                try {
                    rawLine = input.readLine();
                } catch (IOException exception) {
                    error.println("Cannot read command: " + exception.getMessage());
                    return false;
                }
                if (rawLine == null) return false;
                if (!execute(session, rawLine)) return !session.controllerExitRequested();
            }
            return true;
        }
    }

    /** Executes one command or an unquoted {@code ->} pipeline. */
    boolean execute(TargetSession session, String rawLine) {
        try {
            return executePipeline(session, rawLine);
        } catch (Exception exception) {
            session.error().println("Command failed: " + exception.getMessage());
            return session.server().isOpen();
        }
    }

    private boolean executePipeline(TargetSession session, String rawLine) throws Exception {
        for (String segment : splitPipeline(rawLine)) {
            if (!executeSingle(session, segment)) return false;
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
                selectResult(session, session.context().remoteObject().arrayGet(integer(arguments.get(1), "index")));
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
            selectResult(session, readVirtual(session.context().remoteClass(), session.context().remoteObject(),
                    FieldSelection.parse(arguments.get(0))));
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
                selectResult(session, readStatic(type, FieldSelection.parse(field)));
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
            super("set", "set [field] [declaring.Class::]<name> <value> | set index <n> <value>",
                    "Writes a field on the current context object, or an element of the current array.");
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) {
            if (arguments.size() != 2 && arguments.size() != 3) return InteractiveCli.usage(session, this);
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
                selectResult(session, session.context().remoteObject().arrayGet(integer(arguments.get(1), "index")));
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
            super("class", "class <info|fields [all|static|virtual] [glob]|methods [all|static|virtual] [glob]|constructors>",
                    "Lists metadata for the class represented by the current context.");
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) {
            if (arguments.isEmpty()) arguments = Collections.singletonList("info");
            RemoteClass type = session.context().remoteClass();
            String operation = lower(arguments.get(0));
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
                            + "[--static|--virtual] [--limit n]",
                    "Searches loaded packages, types and declared members with '*' and '?' wildcards.", "search");
        }

        @Override
        public boolean execute(TargetSession session, List<String> arguments) {
            if (arguments.isEmpty()) return InteractiveCli.usage(session, this);
            String subject = lower(arguments.get(0));
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
                session.output().println("  set field name string:newName | set index 2 int:9");
                session.output().println("Search (glob '*' matches any text; '?' matches one character):");
                session.output().println("  find package java.* --limit 100");
                session.output().println("  find class *Service --package com.example.** --extends *Base --implements *Api");
                session.output().println("  find interface *Listener | find extends java.util.Abstract* *Map*");
                session.output().println("  find field *cache* --class com.example.* --type java.util.Map --static");
                session.output().println("  find method get* --class com.example.* --returns byte[] --params \"java.lang.String,*\"");
                session.output().println("  class fields virtual *name* | context list methods get*");
                session.output().println("Packages and class files:");
                session.output().println("  package                 # root packages plus classes in the default package");
                session.output().println("  package com.example     # immediate children only");
                session.output().println("  dumpclass com.example.Type build/dump/Type.class");
                session.output().println("  dump package com.example build/dump --recursive --match *Service --limit 500");
                session.output().println("Literals: null, true, int:1, long:2, double:3.5, char:x, string:text, $variable, this");
                session.output().println("A quoted token may contain spaces. Unquoted -> chains commands using the resulting context.");
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
            } else if (!quoted && value == '-' && index + 1 < source.length() && source.charAt(index + 1) == '>') {
                String segment = current.toString().trim();
                if (segment.isEmpty()) throw new IllegalArgumentException("Pipeline contains an empty command");
                result.add(segment);
                current.setLength(0);
                index++;
            } else {
                current.append(value);
            }
        }
        String segment = current.toString().trim();
        if (!segment.isEmpty()) result.add(segment);
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
