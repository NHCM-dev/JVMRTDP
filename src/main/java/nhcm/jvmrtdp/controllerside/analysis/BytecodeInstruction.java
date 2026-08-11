package nhcm.jvmrtdp.controllerside.analysis;

import java.util.Arrays;

public final class BytecodeInstruction {
    private final int offset;
    private final int sourceLine;
    private final int opcode;
    private final String mnemonic;
    private final String operands;
    private final byte[] bytes;

    BytecodeInstruction(int offset, int sourceLine, int opcode,
            String mnemonic, String operands, byte[] bytes) {
        this.offset = offset;
        this.sourceLine = sourceLine;
        this.opcode = opcode;
        this.mnemonic = mnemonic;
        this.operands = operands;
        this.bytes = bytes;
    }

    public int offset() { return offset; }
    public int sourceLine() { return sourceLine; }
    public int opcode() { return opcode; }
    public String mnemonic() { return mnemonic; }
    public String operands() { return operands; }
    public byte[] bytes() { return Arrays.copyOf(bytes, bytes.length); }

    public String format() {
        return String.format("%5d  %-15s%s%s", offset, mnemonic,
                operands.isEmpty() ? "" : " ", operands);
    }

    @Override public String toString() { return format(); }
}
