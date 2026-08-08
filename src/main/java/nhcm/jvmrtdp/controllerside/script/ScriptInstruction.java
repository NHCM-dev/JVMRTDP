package nhcm.jvmrtdp.controllerside.script;

public interface ScriptInstruction {
    /** Returns the next instruction index. */
    int execute(ScriptContext context, int instructionIndex) throws Exception;
}
