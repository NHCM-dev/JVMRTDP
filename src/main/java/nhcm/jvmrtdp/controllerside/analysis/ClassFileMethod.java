package nhcm.jvmrtdp.controllerside.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ClassFileMethod {
    private final int accessFlags;
    private final String name;
    private final String descriptor;
    private final int maxStack;
    private final int maxLocals;
    private final List<BytecodeInstruction> instructions;

    ClassFileMethod(int accessFlags, String name, String descriptor,
            int maxStack, int maxLocals, List<BytecodeInstruction> instructions) {
        this.accessFlags = accessFlags;
        this.name = name;
        this.descriptor = descriptor;
        this.maxStack = maxStack;
        this.maxLocals = maxLocals;
        this.instructions = Collections.unmodifiableList(new ArrayList<BytecodeInstruction>(instructions));
    }

    public int accessFlags() { return accessFlags; }
    public String name() { return name; }
    public String descriptor() { return descriptor; }
    public int maxStack() { return maxStack; }
    public int maxLocals() { return maxLocals; }
    public List<BytecodeInstruction> instructions() { return instructions; }
    public boolean isNative() { return (accessFlags & 0x0100) != 0; }
    public boolean isAbstract() { return (accessFlags & 0x0400) != 0; }
    public String implementationKind() {
        return isNative() ? "NATIVE" : isAbstract() ? "ABSTRACT" : "BYTECODE";
    }

    public String disassembly() {
        StringBuilder result = new StringBuilder();
        int previousLine = Integer.MIN_VALUE;
        for (BytecodeInstruction instruction : instructions) {
            if (instruction.sourceLine() >= 0 && instruction.sourceLine() != previousLine) {
                result.append(String.format("       ; line %d%n", instruction.sourceLine()));
                previousLine = instruction.sourceLine();
            }
            result.append(instruction.format()).append(System.lineSeparator());
        }
        return result.toString();
    }
}
