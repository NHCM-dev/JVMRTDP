package nhcm.jvmrtdp.api.bytecode;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.util.Printer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Text assembler used by CLI/TUI bytecode patches. Instructions are separated by {@code ;;} or newlines. */
public final class JvmBytecodeAssembler {
    private static final Map<String, Integer> OPCODES = opcodeMap();

    public InsnList assemble(String source) {
        return assemble(source, Collections.<String, LabelNode>emptyMap());
    }

    InsnList assemble(String source, Map<String, LabelNode> externalLabels) {
        if (source == null || source.trim().isEmpty()) {
            throw new IllegalArgumentException("Assembly must not be empty");
        }
        List<List<Token>> statements = statements(source);
        final Map<String, LabelNode> labels = new LinkedHashMap<String, LabelNode>();
        labels.putAll(externalLabels);
        for (List<Token> statement : statements) {
            if (statement.size() >= 2 && "LABEL".equals(upper(statement.get(0)))) {
                label(labels, statement.get(1).value);
            }
        }
        InsnList result = new InsnList();
        for (List<Token> statement : statements) {
            if (statement.isEmpty()) continue;
            String name = upper(statement.get(0));
            if ("LABEL".equals(name)) {
                requireCount(statement, 2, 2, name);
                if (externalLabels.containsKey(statement.get(1).value)) {
                    throw new IllegalArgumentException("Existing @BCI labels can be jump targets, "
                            + "but cannot be declared again: " + statement.get(1).value);
                }
                result.add(label(labels, statement.get(1).value));
                continue;
            }
            if ("LINE".equals(name)) {
                requireCount(statement, 2, 3, name);
                LabelNode at = statement.size() == 3
                        ? label(labels, statement.get(2).value) : new LabelNode();
                if (statement.size() == 2) result.add(at);
                result.add(new LineNumberNode(integer(statement.get(1).value), at));
                continue;
            }
            Integer opcodeValue = OPCODES.get(name);
            if (opcodeValue == null) throw new IllegalArgumentException("Unknown JVM opcode: " + name);
            int opcode = opcodeValue.intValue();
            result.add(instruction(opcode, name, statement, labels));
        }
        if (result.size() == 0) throw new IllegalArgumentException(
                "Assembly must contain at least one instruction, label, or line marker");
        return result;
    }

    private static AbstractInsnNode instruction(int opcode, String name,
            List<Token> values, Map<String, LabelNode> labels) {
        switch (opcode) {
        case Opcodes.BIPUSH:
        case Opcodes.SIPUSH:
            requireCount(values, 2, 2, name);
            return new IntInsnNode(opcode, integer(values.get(1).value));
        case Opcodes.NEWARRAY:
            requireCount(values, 2, 2, name);
            return new IntInsnNode(opcode, arrayType(values.get(1).value));
        case Opcodes.ILOAD: case Opcodes.LLOAD: case Opcodes.FLOAD:
        case Opcodes.DLOAD: case Opcodes.ALOAD: case Opcodes.ISTORE:
        case Opcodes.LSTORE: case Opcodes.FSTORE: case Opcodes.DSTORE:
        case Opcodes.ASTORE: case Opcodes.RET:
            requireCount(values, 2, 2, name);
            return new VarInsnNode(opcode, integer(values.get(1).value));
        case Opcodes.IINC:
            requireCount(values, 3, 3, name);
            return new IincInsnNode(integer(values.get(1).value), integer(values.get(2).value));
        case Opcodes.NEW: case Opcodes.ANEWARRAY: case Opcodes.CHECKCAST: case Opcodes.INSTANCEOF:
            requireCount(values, 2, 2, name);
            return new TypeInsnNode(opcode, internal(values.get(1).value));
        case Opcodes.GETSTATIC: case Opcodes.PUTSTATIC: case Opcodes.GETFIELD: case Opcodes.PUTFIELD:
            requireCount(values, 4, 4, name);
            return new FieldInsnNode(opcode, internal(values.get(1).value),
                    values.get(2).value, values.get(3).value);
        case Opcodes.INVOKEVIRTUAL: case Opcodes.INVOKESPECIAL:
        case Opcodes.INVOKESTATIC: case Opcodes.INVOKEINTERFACE:
            requireCount(values, 4, 5, name);
            boolean ownerInterface = opcode == Opcodes.INVOKEINTERFACE
                    || values.size() == 5 && Boolean.parseBoolean(values.get(4).value);
            return new MethodInsnNode(opcode, internal(values.get(1).value),
                    values.get(2).value, values.get(3).value, ownerInterface);
        case Opcodes.INVOKEDYNAMIC:
            throw new IllegalArgumentException(
                    "INVOKEDYNAMIC bootstrap handles require the Library ASM MethodNode API");
        case Opcodes.IFEQ: case Opcodes.IFNE: case Opcodes.IFLT: case Opcodes.IFGE:
        case Opcodes.IFGT: case Opcodes.IFLE: case Opcodes.IF_ICMPEQ: case Opcodes.IF_ICMPNE:
        case Opcodes.IF_ICMPLT: case Opcodes.IF_ICMPGE: case Opcodes.IF_ICMPGT:
        case Opcodes.IF_ICMPLE: case Opcodes.IF_ACMPEQ: case Opcodes.IF_ACMPNE:
        case Opcodes.GOTO: case Opcodes.JSR: case Opcodes.IFNULL: case Opcodes.IFNONNULL:
            requireCount(values, 2, 2, name);
            return new JumpInsnNode(opcode, requiredLabel(labels, values.get(1).value));
        case Opcodes.LDC:
            requireCount(values, 2, 2, name);
            return new LdcInsnNode(constant(values.get(1)));
        case Opcodes.MULTIANEWARRAY:
            requireCount(values, 3, 3, name);
            return new MultiANewArrayInsnNode(values.get(1).value,
                    integer(values.get(2).value));
        case Opcodes.TABLESWITCH: {
            if (values.size() < 5) throw count(name, "min max default labels...");
            int min = integer(values.get(1).value);
            int max = integer(values.get(2).value);
            if (max < min || values.size() != 4 + max - min + 1) {
                throw new IllegalArgumentException("TABLESWITCH label count does not match " + min + ".." + max);
            }
            LabelNode[] targets = new LabelNode[max - min + 1];
            for (int index = 0; index < targets.length; index++) {
                targets[index] = requiredLabel(labels, values.get(4 + index).value);
            }
            return new TableSwitchInsnNode(min, max,
                    requiredLabel(labels, values.get(3).value), targets);
        }
        case Opcodes.LOOKUPSWITCH: {
            if (values.size() < 4 || (values.size() - 2) % 2 != 0) {
                throw count(name, "default key label [key label ...]");
            }
            int pairs = (values.size() - 2) / 2;
            int[] keys = new int[pairs];
            LabelNode[] targets = new LabelNode[pairs];
            for (int index = 0; index < pairs; index++) {
                keys[index] = integer(values.get(2 + index * 2).value);
                targets[index] = requiredLabel(labels, values.get(3 + index * 2).value);
            }
            return new LookupSwitchInsnNode(requiredLabel(labels, values.get(1).value), keys, targets);
        }
        default:
            requireCount(values, 1, 1, name);
            return new InsnNode(opcode);
        }
    }

    private static Object constant(Token token) {
        String value = token.value;
        if (token.quoted) return value;
        if (value.startsWith("string:")) return value.substring(7);
        if (value.startsWith("type:")) return Type.getType(value.substring(5));
        if (value.startsWith("int:")) return Integer.valueOf(integer(value.substring(4)));
        if (value.startsWith("long:")) return Long.decode(value.substring(5));
        if (value.startsWith("float:")) return Float.valueOf(value.substring(6));
        if (value.startsWith("double:")) return Double.valueOf(value.substring(7));
        if (value.endsWith("L") || value.endsWith("l")) {
            return Long.decode(value.substring(0, value.length() - 1));
        }
        if (value.endsWith("F") || value.endsWith("f")) {
            return Float.valueOf(value.substring(0, value.length() - 1));
        }
        if (value.endsWith("D") || value.endsWith("d")) {
            return Double.valueOf(value.substring(0, value.length() - 1));
        }
        try { return Integer.valueOf(integer(value)); }
        catch (RuntimeException ignored) { return value; }
    }

    private static int arrayType(String value) {
        String type = value.toUpperCase(Locale.ROOT);
        if ("BOOLEAN".equals(type)) return Opcodes.T_BOOLEAN;
        if ("CHAR".equals(type)) return Opcodes.T_CHAR;
        if ("FLOAT".equals(type)) return Opcodes.T_FLOAT;
        if ("DOUBLE".equals(type)) return Opcodes.T_DOUBLE;
        if ("BYTE".equals(type)) return Opcodes.T_BYTE;
        if ("SHORT".equals(type)) return Opcodes.T_SHORT;
        if ("INT".equals(type)) return Opcodes.T_INT;
        if ("LONG".equals(type)) return Opcodes.T_LONG;
        return integer(value);
    }

    private static String internal(String value) { return value.replace('.', '/'); }

    private static LabelNode label(Map<String, LabelNode> labels, String name) {
        LabelNode result = labels.get(name);
        if (result == null) {
            result = new LabelNode();
            labels.put(name, result);
        }
        return result;
    }

    private static LabelNode requiredLabel(Map<String, LabelNode> labels, String name) {
        LabelNode result = labels.get(name);
        if (result == null) throw new IllegalArgumentException(
                "Unknown label " + name + "; declare LABEL " + name + " or use an existing @BCI");
        return result;
    }

    private static int integer(String value) { return Integer.decode(value); }

    private static void requireCount(List<Token> values, int min, int max, String name) {
        if (values.size() < min || values.size() > max) {
            throw count(name, (min - 1) + (min == max ? "" : ".." + (max - 1)) + " operand(s)");
        }
    }

    private static IllegalArgumentException count(String name, String expected) {
        return new IllegalArgumentException(name + " expects " + expected);
    }

    private static String upper(Token token) { return token.value.toUpperCase(Locale.ROOT); }

    private static List<List<Token>> statements(String source) {
        List<List<Token>> result = new ArrayList<List<Token>>();
        StringBuilder statement = new StringBuilder();
        boolean quoted = false;
        boolean escaping = false;
        for (int index = 0; index < source.length(); index++) {
            char value = source.charAt(index);
            if (escaping) { statement.append(value); escaping = false; continue; }
            if (value == '\\' && quoted) { statement.append(value); escaping = true; continue; }
            if (value == '"') { quoted = !quoted; statement.append(value); continue; }
            boolean separator = !quoted && (value == '\n' || value == '\r'
                    || value == ';' && index + 1 < source.length() && source.charAt(index + 1) == ';');
            if (separator) {
                if (value == ';') index++;
                addStatement(result, statement);
            } else statement.append(value);
        }
        if (quoted) throw new IllegalArgumentException("Unterminated quoted assembly string");
        addStatement(result, statement);
        return result;
    }

    private static void addStatement(List<List<Token>> result, StringBuilder source) {
        String value = source.toString().trim();
        source.setLength(0);
        if (value.isEmpty() || value.startsWith("#")) return;
        result.add(tokens(value));
    }

    private static List<Token> tokens(String source) {
        List<Token> result = new ArrayList<Token>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        boolean tokenQuoted = false;
        boolean escaping = false;
        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            if (escaping) {
                if (character == 'n') value.append('\n');
                else if (character == 'r') value.append('\r');
                else if (character == 't') value.append('\t');
                else value.append(character);
                escaping = false;
            } else if (character == '\\' && quoted) escaping = true;
            else if (character == '"') { quoted = !quoted; tokenQuoted = true; }
            else if (Character.isWhitespace(character) && !quoted) {
                if (value.length() > 0 || tokenQuoted) {
                    result.add(new Token(value.toString(), tokenQuoted));
                    value.setLength(0);
                    tokenQuoted = false;
                }
            } else value.append(character);
        }
        if (quoted) throw new IllegalArgumentException("Unterminated quoted assembly token");
        if (value.length() > 0 || tokenQuoted) result.add(new Token(value.toString(), tokenQuoted));
        return result;
    }

    private static Map<String, Integer> opcodeMap() {
        Map<String, Integer> result = new LinkedHashMap<String, Integer>();
        for (int opcode = 0; opcode < Printer.OPCODES.length; opcode++) {
            String name = Printer.OPCODES[opcode];
            if (name != null) result.put(name.toUpperCase(Locale.ROOT), Integer.valueOf(opcode));
        }
        return result;
    }

    private static final class Token {
        private final String value;
        private final boolean quoted;
        private Token(String value, boolean quoted) { this.value = value; this.quoted = quoted; }
    }
}
