package nhcm.jvmrtdp.controllerside.script;

import nhcm.jvmrtdp.controllerside.TargetSession;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ScriptEngine {
    private static final int MAX_EXECUTED_INSTRUCTIONS = 100_000;
    private final ScriptParser parser = new ScriptParser();
    private final ScriptCommandExecutor commandExecutor;

    public ScriptEngine() {
        this(null);
    }

    public ScriptEngine(ScriptCommandExecutor commandExecutor) {
        this.commandExecutor = commandExecutor;
    }

    public void execute(Path scriptFile, TargetSession session) throws Exception {
        List<String> lines = Files.readAllLines(scriptFile.toAbsolutePath().normalize(), StandardCharsets.UTF_8);
        execute(parser.parse(lines), session);
    }

    public void execute(ScriptProgram program, TargetSession session) throws Exception {
        ScriptContext context = new ScriptContext(session, program, commandExecutor);
        int instruction = 0;
        int executed = 0;
        while (instruction >= 0 && instruction < program.instructions().size()) {
            if (++executed > MAX_EXECUTED_INSTRUCTIONS) {
                throw new IllegalStateException("Script exceeded " + MAX_EXECUTED_INSTRUCTIONS + " instructions");
            }
            instruction = program.instructions().get(instruction).execute(context, instruction);
        }
        session.output().printf("Script completed: %,d instructions%n", executed);
    }
}
