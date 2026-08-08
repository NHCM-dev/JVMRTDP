package nhcm.jvmrtdp.controllerside.script;

import nhcm.jvmrtdp.command.CommandLine;
import nhcm.jvmrtdp.controllerside.TargetSession;
import nhcm.jvmrtdp.handles.java.RemoteClass;
import nhcm.jvmrtdp.handles.java.RemoteObject;
import nhcm.jvmrtdp.protocol.CommandReply;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class ScriptContext {
    private final TargetSession session;
    private final ScriptProgram program;
    private final ScriptCommandExecutor commandExecutor;

    public ScriptContext(TargetSession session, ScriptProgram program) {
        this(session, program, null);
    }

    public ScriptContext(TargetSession session, ScriptProgram program, ScriptCommandExecutor commandExecutor) {
        this.session = session;
        this.program = program;
        this.commandExecutor = commandExecutor;
    }

    public TargetSession session() {
        return session;
    }

    public int next(int current) {
        return current + 1;
    }

    public int jump(String label) {
        return program.label(label);
    }

    public String printable(String token) {
        if ("context".equalsIgnoreCase(token)) {
            return session.context().isObject()
                    ? session.context().remoteObject().displayValue() : session.context().description();
        }
        if (!isReference(token)) return token;
        String name = token.substring(1);
        RemoteObject object = session.workspace().objects().get(name);
        if (object != null) return object.displayValue();
        RemoteClass remoteClass = session.workspace().classes().get(name);
        if (remoteClass != null) return remoteClass.className();
        throw new IllegalArgumentException("Unknown script variable: " + token);
    }

    public boolean isNull(String reference) {
        if ("context".equalsIgnoreCase(reference)) {
            return session.context().isObject() && session.context().remoteObject().isNull();
        }
        return session.workspace().objectValue(reference).isNull();
    }

    public boolean truthy(String reference) {
        RemoteObject value = "context".equalsIgnoreCase(reference)
                ? session.context().remoteObject() : session.workspace().objectValue(reference);
        if (value.isNull()) return false;
        String text = value.displayValue();
        return !text.isEmpty() && !"false".equalsIgnoreCase(text)
                && !"0".equals(text) && !"null".equalsIgnoreCase(text);
    }

    public void print(List<String> tokens) {
        StringBuilder line = new StringBuilder();
        for (String token : tokens) {
            if (line.length() != 0) line.append(' ');
            line.append(printable(token));
        }
        session.output().println(line);
    }

    public void exportClass(String reference, String outputFile) throws IOException {
        RemoteClass type = session.workspace().classValue(reference);
        Path written = type.dumpClass(Paths.get(outputFile));
        session.output().printf("exported class %s -> %s%n", type.className(), written);
    }

    public void exportObject(String reference, String outputFile) throws IOException {
        RemoteObject value = "context".equalsIgnoreCase(reference)
                ? session.context().remoteObject() : session.workspace().objectValue(reference);
        Path output = Paths.get(outputFile).toAbsolutePath().normalize();
        Path parent = output.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(output, value.displayValue().getBytes(StandardCharsets.UTF_8));
        session.output().printf("exported object %s -> %s%n", reference, output);
    }

    public void command(List<String> tokens) throws Exception {
        if (tokens.isEmpty()) throw new IllegalArgumentException("command requires a target command");
        if (commandExecutor != null) {
            String[] arguments = tokens.subList(1, tokens.size()).toArray(new String[0]);
            if (!commandExecutor.execute(session, CommandLine.of(tokens.get(0), arguments))) {
                throw new IllegalStateException("Context command requested the session to close");
            }
            return;
        }
        String[] arguments = tokens.subList(1, tokens.size()).toArray(new String[0]);
        CommandReply reply = session.server().execute(CommandLine.of(tokens.get(0), arguments));
        if (!reply.output().isEmpty()) {
            (reply.successful() ? session.output() : session.error()).println(reply.output());
        }
        if (!reply.successful()) throw new IllegalStateException("Target command failed");
    }

    private static boolean isReference(String token) {
        return token != null && token.length() > 1 && (token.charAt(0) == '$' || token.charAt(0) == '@');
    }
}
