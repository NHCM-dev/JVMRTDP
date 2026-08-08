package nhcm.jvmrtdp.controllerside.script;

import nhcm.jvmrtdp.command.CommandLine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ScriptParser {
    public ScriptProgram parse(List<String> lines) {
        List<ScriptInstruction> instructions = new ArrayList<ScriptInstruction>();
        Map<String, Integer> labels = new LinkedHashMap<String, Integer>();
        for (int index = 0; index < lines.size(); index++) {
            String source = lines.get(index).trim();
            if (source.isEmpty() || source.startsWith("#")) continue;
            if (source.startsWith(":")) {
                String label = source.substring(1).trim();
                if (label.isEmpty() || labels.put(label, instructions.size()) != null) {
                    throw error(index, "Invalid or duplicate label: " + label);
                }
                continue;
            }
            try {
                instructions.add(instruction(CommandLine.parse(source)));
            } catch (RuntimeException exception) {
                throw error(index, exception.getMessage());
            }
        }
        return new ScriptProgram(instructions, labels);
    }

    private static ScriptInstruction instruction(CommandLine line) {
        String name = line.name();
        List<String> args = line.arguments();
        if ("class".equals(name) && args.size() == 2) return new ClassInstruction(args.get(0), args.get(1));
        if ("value".equals(name) && args.size() == 3) return new ValueInstruction(args.get(0), args.get(1), args.get(2));
        if ("value".equals(name) && args.size() == 2 && "null".equalsIgnoreCase(args.get(1))) {
            return new ValueInstruction(args.get(0), args.get(1), "");
        }
        if ("construct".equals(name) && args.size() >= 3) {
            return new ConstructInstruction(args.get(0), args.get(1), args.get(2), tail(args, 3));
        }
        if ("call".equals(name) && args.size() >= 4) {
            return new CallInstruction(args.get(0), args.get(1), args.get(2), args.get(3), tail(args, 4));
        }
        if ("get".equals(name) && args.size() == 3) return new GetInstruction(args.get(0), args.get(1), args.get(2));
        if ("set".equals(name) && args.size() == 3) return new SetInstruction(args.get(0), args.get(1), args.get(2));
        if ("print".equals(name) && !args.isEmpty()) return new PrintInstruction(args);
        if ("export".equals(name) && args.size() == 3) return new ExportInstruction(args.get(0), args.get(1), args.get(2));
        if ("export".equals(name) && args.size() == 2 && "context".equalsIgnoreCase(args.get(0))) {
            return new ExportInstruction("object", "context", args.get(1));
        }
        if ("if".equals(name) && args.size() == 3 && "goto".equalsIgnoreCase(args.get(1))) {
            return new IfInstruction(args.get(0), args.get(2), false);
        }
        if ("ifnull".equals(name) && args.size() == 3 && "goto".equalsIgnoreCase(args.get(1))) {
            return new IfInstruction(args.get(0), args.get(2), true);
        }
        if ("goto".equals(name) && args.size() == 1) return new GotoInstruction(args.get(0));
        if ("switch".equals(name) && args.size() >= 2) return new SwitchInstruction(args.get(0), tail(args, 1));
        if ("command".equals(name) && !args.isEmpty()) return new CommandInstruction(args);
        if ("release".equals(name) && !args.isEmpty()) return new ReleaseInstruction(args);
        throw new IllegalArgumentException("Invalid script instruction: " + name + " " + args);
    }

    private static List<String> tail(List<String> values, int start) {
        return new ArrayList<String>(values.subList(start, values.size()));
    }

    private static IllegalArgumentException error(int zeroBasedLine, String message) {
        return new IllegalArgumentException("Script line " + (zeroBasedLine + 1) + ": " + message);
    }

    private static class ClassInstruction implements ScriptInstruction {
        private final String variable, className;
        private ClassInstruction(String variable, String className) { this.variable = variable; this.className = className; }
        public int execute(ScriptContext context, int index) {
            context.session().operations().defineClass(variable, className);
            return context.next(index);
        }
    }

    private static class ValueInstruction implements ScriptInstruction {
        private final String variable, type, value;
        private ValueInstruction(String variable, String type, String value) {
            this.variable = variable; this.type = type; this.value = value;
        }
        public int execute(ScriptContext context, int index) {
            context.session().operations().defineValue(variable, type, value);
            return context.next(index);
        }
    }

    private static class ConstructInstruction implements ScriptInstruction {
        private final String variable, type, descriptor;
        private final List<String> arguments;
        private ConstructInstruction(String variable, String type, String descriptor, List<String> arguments) {
            this.variable = variable; this.type = type; this.descriptor = descriptor; this.arguments = arguments;
        }
        public int execute(ScriptContext context, int index) {
            context.session().operations().construct(variable, type, descriptor, arguments);
            return context.next(index);
        }
    }

    private static class CallInstruction implements ScriptInstruction {
        private final String variable, receiver, method, descriptor;
        private final List<String> arguments;
        private CallInstruction(String variable, String receiver, String method, String descriptor, List<String> arguments) {
            this.variable = variable; this.receiver = receiver; this.method = method;
            this.descriptor = descriptor; this.arguments = arguments;
        }
        public int execute(ScriptContext context, int index) {
            context.session().operations().call(variable, receiver, method, descriptor, arguments);
            return context.next(index);
        }
    }

    private static class GetInstruction implements ScriptInstruction {
        private final String variable, receiver, field;
        private GetInstruction(String variable, String receiver, String field) {
            this.variable = variable; this.receiver = receiver; this.field = field;
        }
        public int execute(ScriptContext context, int index) {
            context.session().operations().get(variable, receiver, field);
            return context.next(index);
        }
    }

    private static class SetInstruction implements ScriptInstruction {
        private final String receiver, field, value;
        private SetInstruction(String receiver, String field, String value) {
            this.receiver = receiver; this.field = field; this.value = value;
        }
        public int execute(ScriptContext context, int index) {
            context.session().operations().set(receiver, field, value);
            return context.next(index);
        }
    }

    private static class PrintInstruction implements ScriptInstruction {
        private final List<String> values;
        private PrintInstruction(List<String> values) { this.values = values; }
        public int execute(ScriptContext context, int index) {
            context.print(values);
            return context.next(index);
        }
    }

    private static class ExportInstruction implements ScriptInstruction {
        private final String kind, value, file;
        private ExportInstruction(String kind, String value, String file) {
            this.kind = kind; this.value = value; this.file = file;
        }
        public int execute(ScriptContext context, int index) throws Exception {
            if ("class".equalsIgnoreCase(kind)) context.exportClass(value, file);
            else if ("value".equalsIgnoreCase(kind) || "object".equalsIgnoreCase(kind)) {
                context.exportObject(value, file);
            } else throw new IllegalArgumentException("export kind must be class or value");
            return context.next(index);
        }
    }

    private static class IfInstruction implements ScriptInstruction {
        private final String value, label;
        private final boolean nullTest;
        private IfInstruction(String value, String label, boolean nullTest) {
            this.value = value; this.label = label; this.nullTest = nullTest;
        }
        public int execute(ScriptContext context, int index) {
            boolean matches = nullTest ? context.isNull(value) : context.truthy(value);
            return matches ? context.jump(label) : context.next(index);
        }
    }

    private static class GotoInstruction implements ScriptInstruction {
        private final String label;
        private GotoInstruction(String label) { this.label = label; }
        public int execute(ScriptContext context, int index) { return context.jump(label); }
    }

    private static class SwitchInstruction implements ScriptInstruction {
        private final String value;
        private final Map<String, String> cases = new LinkedHashMap<String, String>();
        private String defaultLabel;
        private SwitchInstruction(String value, List<String> branches) {
            this.value = value;
            for (String branch : branches) {
                int separator = branch.lastIndexOf('=');
                if (separator <= 0 || separator == branch.length() - 1) {
                    throw new IllegalArgumentException("switch branches use value=label: " + branch);
                }
                String match = branch.substring(0, separator);
                String label = branch.substring(separator + 1);
                if ("default".equalsIgnoreCase(match)) defaultLabel = label;
                else cases.put(match, label);
            }
        }
        public int execute(ScriptContext context, int index) {
            String actual = context.printable(value);
            String label = cases.get(actual);
            if (label == null) label = defaultLabel;
            return label == null ? context.next(index) : context.jump(label);
        }
    }

    private static class CommandInstruction implements ScriptInstruction {
        private final List<String> command;
        private CommandInstruction(List<String> command) { this.command = command; }
        public int execute(ScriptContext context, int index) throws Exception {
            context.command(command);
            return context.next(index);
        }
    }

    private static class ReleaseInstruction implements ScriptInstruction {
        private final List<String> variables;
        private ReleaseInstruction(List<String> variables) { this.variables = variables; }
        public int execute(ScriptContext context, int index) {
            for (String variable : variables) context.session().workspace().release(variable);
            return context.next(index);
        }
    }
}
