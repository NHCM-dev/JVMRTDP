package nhcm.jvmrtdp.remoteside;

import nhcm.jvmrtdp.api.jvmti.JvmtiClassFileEvent;
import nhcm.jvmrtdp.api.jvmti.JvmtiClassFileTransformer;
import nhcm.jvmrtdp.controllerside.analysis.BytecodeInstruction;
import nhcm.jvmrtdp.controllerside.analysis.ClassFileMethod;
import nhcm.jvmrtdp.controllerside.analysis.ClassFileView;
import nhcm.jvmrtdp.controllerside.analysis.JvmClassFileParser;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Adds/removes lightweight probes after matching String {@code ldc}/{@code ldc_w} sites. */
final class StringLdcProbeTransformer implements JvmtiClassFileTransformer {
    static final String BRIDGE_CLASS = "nhcm.jvmrtdp.bootstrap.StringHookBridge";
    static final String BRIDGE_INTERNAL = "nhcm/jvmrtdp/bootstrap/StringHookBridge";
    static final String BRIDGE_METHOD = "observedLiteral";
    static final String BRIDGE_DESCRIPTOR = "(Ljava/lang/String;I)V";

    private volatile Rule[] rules = new Rule[0];
    private final Set<String> instrumentedClasses =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    void configure(String[] patterns, boolean[] sensitivity) {
        if (patterns == null || patterns.length == 0) {
            rules = new Rule[0];
            return;
        }
        Rule[] replacement = new Rule[patterns.length];
        for (int index = 0; index < replacement.length; ++index) {
            String pattern = patterns[index];
            replacement[index] = new Rule(pattern == null || pattern.isEmpty() ? "*" : pattern,
                    sensitivity == null || index >= sensitivity.length || sensitivity[index]);
        }
        rules = replacement;
    }

    boolean enabled() { return rules.length != 0; }

    Set<String> instrumentedClassNames() {
        return new HashSet<String>(instrumentedClasses);
    }

    /** Fast JVMTI constant-pool pre-scan used to avoid retransformation of unrelated classes. */
    boolean mayMatchConstantPool(byte[] constantPoolBytes) {
        return !matchingConstants(constantPoolBytes).isEmpty();
    }

    /** Parses a JVMTI constant pool once and keeps only String entries accepted by current rules. */
    Map<Integer, String> matchingConstants(byte[] constantPoolBytes) {
        Rule[] snapshot = rules;
        if (snapshot.length == 0 || constantPoolBytes == null || constantPoolBytes.length == 0)
            return java.util.Collections.emptyMap();
        try {
            Map<Integer, String> result = stringConstants(constantPoolBytes);
            result.entrySet().removeIf(entry -> !matchesAny(snapshot, entry.getValue()));
            return result;
        } catch (IOException | RuntimeException malformed) {
            return java.util.Collections.emptyMap();
        }
    }

    List<Site> matchingSites(String methodName, String descriptor,
            byte[] bytecodes, byte[] constantPoolBytes) {
        return matchingSites(methodName, descriptor, bytecodes,
                matchingConstants(constantPoolBytes));
    }

    List<Site> matchingSites(String methodName, String descriptor,
            byte[] bytecodes, Map<Integer, String> matchingConstants) {
        if (bytecodes == null || matchingConstants == null || matchingConstants.isEmpty())
            return java.util.Collections.emptyList();
        try {
            List<Site> result = new ArrayList<Site>();
            for (int offset = 0; offset < bytecodes.length;) {
                int opcode = unsignedByte(bytecodes, offset);
                int constantIndex = opcode == Opcodes.LDC && offset + 1 < bytecodes.length
                        ? unsignedByte(bytecodes, offset + 1)
                        : opcode == 19 && offset + 2 < bytecodes.length
                                ? unsignedShort(bytecodes, offset + 1) : -1;
                String literal = matchingConstants.get(Integer.valueOf(constantIndex));
                if (literal != null) {
                    result.add(new Site(methodName, descriptor, offset, literal));
                }
                int next = nextInstructionOffset(bytecodes, offset, opcode);
                if (next <= offset || next > bytecodes.length) return java.util.Collections.emptyList();
                offset = next;
            }
            return result;
        } catch (RuntimeException malformed) {
            return java.util.Collections.emptyList();
        }
    }

    private static Map<Integer, String> stringConstants(byte[] constantPoolBytes)
            throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(constantPoolBytes));
        Map<Integer, String> utf8 = new HashMap<Integer, String>();
        Map<Integer, Integer> references = new HashMap<Integer, Integer>();
        int poolIndex = 1;
        while (input.available() > 0) {
            int tag = input.readUnsignedByte();
            switch (tag) {
            case 1: utf8.put(Integer.valueOf(poolIndex), input.readUTF()); break;
            case 3: case 4: input.skipBytes(4); break;
            case 5: case 6: input.skipBytes(8); poolIndex++; break;
            case 7: case 16: case 19: case 20: input.skipBytes(2); break;
            case 8:
                references.put(Integer.valueOf(poolIndex),
                        Integer.valueOf(input.readUnsignedShort()));
                break;
            case 9: case 10: case 11: case 12: case 17: case 18:
                input.skipBytes(4); break;
            case 15: input.skipBytes(3); break;
            default: throw new IOException("Unsupported constant-pool tag " + tag);
            }
            poolIndex++;
        }
        Map<Integer, String> result = new HashMap<Integer, String>();
        for (Map.Entry<Integer, Integer> reference : references.entrySet()) {
            String literal = utf8.get(reference.getValue());
            if (literal != null) result.put(reference.getKey(), literal);
        }
        return result;
    }

    private static int nextInstructionOffset(byte[] code, int start, int opcode) {
        switch (opcode) {
        case 16: case 18: case 21: case 22: case 23: case 24: case 25:
        case 54: case 55: case 56: case 57: case 58: case 169: case 188:
            return start + 2;
        case 17: case 19: case 20: case 132:
        case 153: case 154: case 155: case 156: case 157: case 158:
        case 159: case 160: case 161: case 162: case 163: case 164:
        case 165: case 166: case 167: case 168:
        case 178: case 179: case 180: case 181: case 182: case 183: case 184:
        case 187: case 189: case 192: case 193: case 198: case 199:
            return start + 3;
        case 197: return start + 4;
        case 185: case 186: case 200: case 201: return start + 5;
        case 196:
            return start + (start + 1 < code.length
                    && unsignedByte(code, start + 1) == 132 ? 6 : 4);
        case 170: {
            int cursor = (start + 4) & ~3;
            if (cursor + 12 > code.length) return -1;
            int low = signedInt(code, cursor + 4);
            int high = signedInt(code, cursor + 8);
            long entries = (long) high - low + 1L;
            if (entries < 0L || entries > (code.length - cursor - 12L) / 4L) return -1;
            return (int) (cursor + 12L + entries * 4L);
        }
        case 171: {
            int cursor = (start + 4) & ~3;
            if (cursor + 8 > code.length) return -1;
            int pairs = signedInt(code, cursor + 4);
            if (pairs < 0 || pairs > (code.length - cursor - 8) / 8) return -1;
            return cursor + 8 + pairs * 8;
        }
        default: return start + 1;
        }
    }

    private static int unsignedByte(byte[] values, int offset) {
        return values[offset] & 0xff;
    }

    private static int unsignedShort(byte[] values, int offset) {
        return unsignedByte(values, offset) << 8 | unsignedByte(values, offset + 1);
    }

    private static int signedInt(byte[] values, int offset) {
        return unsignedByte(values, offset) << 24 | unsignedByte(values, offset + 1) << 16
                | unsignedByte(values, offset + 2) << 8 | unsignedByte(values, offset + 3);
    }

    static boolean eligibleClass(String className) {
        if (className == null || className.isEmpty()) return false;
        return !"java.lang.String".equals(className)
                && !className.startsWith("nhcm.jvmrtdp.");
    }

    @Override
    public byte[] transform(JvmtiClassFileEvent event) {
        if (event == null || !eligibleClass(event.className())) return null;
        return transformBytes(event.classBytes(), event.className(), event.classBeingRedefined());
    }

    /** Raw JNI entry used by the built-in native ClassFileLoadHook composition path. */
    public byte[] transformRaw(ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] bytes) {
        return transform(new JvmtiClassFileEvent(loader, className, classBeingRedefined,
                protectionDomain, bytes));
    }

    byte[] transformBytes(byte[] bytes) {
        return transformBytes(bytes, null, null);
    }

    private byte[] transformBytes(byte[] bytes, String className, Class<?> redefinedType) {
        Rule[] snapshot = rules;
        if (snapshot.length != 0 && !classFileMayMatch(bytes, snapshot)
                && (className == null || !instrumentedClasses.contains(className))) {
            if (className != null) instrumentedClasses.remove(className);
            return null;
        }
        Map<String, List<Integer>> originalLdcOffsets = ldcOffsets(bytes);
        ClassReader reader = new ClassReader(bytes);
        ClassNode type = new ClassNode();
        reader.accept(type, 0);
        int changed = removeProbes(type);
        int installed = snapshot.length == 0 ? 0
                : installProbes(type, snapshot, originalLdcOffsets);
        changed += installed;
        if (className != null) {
            if (installed != 0) instrumentedClasses.add(className);
            else instrumentedClasses.remove(className);
        }
        if (changed == 0) return null;
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        type.accept(writer);
        return writer.toByteArray();
    }

    /** Cheap raw class-file prefilter; avoids ASM and bytecode parsing for unrelated loads. */
    private static boolean classFileMayMatch(byte[] bytes, Rule[] rules) {
        if (bytes == null || bytes.length < 10) return false;
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
            if (input.readInt() != 0xCAFEBABE) return false;
            input.readUnsignedShort(); // minor
            input.readUnsignedShort(); // major
            int count = input.readUnsignedShort();
            Map<Integer, String> utf8 = new HashMap<Integer, String>();
            List<Integer> references = new ArrayList<Integer>();
            for (int index = 1; index < count; index++) {
                int tag = input.readUnsignedByte();
                switch (tag) {
                case 1: utf8.put(Integer.valueOf(index), input.readUTF()); break;
                case 3: case 4: input.skipBytes(4); break;
                case 5: case 6: input.skipBytes(8); index++; break;
                case 7: case 16: case 19: case 20: input.skipBytes(2); break;
                case 8: references.add(Integer.valueOf(input.readUnsignedShort())); break;
                case 9: case 10: case 11: case 12: case 17: case 18:
                    input.skipBytes(4); break;
                case 15: input.skipBytes(3); break;
                default: return true; // unknown future tag: do the safe full parse
                }
            }
            for (Integer reference : references) {
                String literal = utf8.get(reference);
                if (literal != null && matchesAny(rules, literal)) return true;
            }
            return false;
        } catch (IOException | RuntimeException malformed) {
            return true;
        }
    }

    private static int installProbes(ClassNode type, Rule[] rules,
            Map<String, List<Integer>> originalLdcOffsets) {
        int changed = 0;
        for (MethodNode method : type.methods) {
            List<Integer> offsets = originalLdcOffsets.get(method.name + method.desc);
            int ldcIndex = 0;
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                    instruction != null; instruction = instruction.getNext()) {
                if (!(instruction instanceof LdcInsnNode)) continue;
                int originalBci = offsets != null && ldcIndex < offsets.size()
                        ? offsets.get(ldcIndex).intValue() : -1;
                ldcIndex++;
                if (!(((LdcInsnNode) instruction).cst instanceof String) || originalBci < 0) continue;
                String literal = (String) ((LdcInsnNode) instruction).cst;
                if (!matchesAny(rules, literal)) continue;
                InsnList probe = new InsnList();
                probe.add(new InsnNode(Opcodes.DUP));
                // A Code attribute is at most 65535 bytes. SIPUSH the low 16 bits so adding the
                // site identifier never introduces another LDC instruction; native normalizes it.
                probe.add(new IntInsnNode(Opcodes.SIPUSH, (short) originalBci));
                probe.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE_INTERNAL,
                        BRIDGE_METHOD, BRIDGE_DESCRIPTOR, false));
                method.instructions.insert(instruction, probe);
                changed++;
            }
        }
        return changed;
    }

    private static int removeProbes(ClassNode type) {
        int changed = 0;
        for (MethodNode method : type.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;) {
                AbstractInsnNode next = instruction.getNext();
                if (isProbeCall(instruction)) {
                    AbstractInsnNode site = previousCode(instruction);
                    AbstractInsnNode duplicate = previousCode(site);
                    if (duplicate != null && duplicate.getOpcode() == Opcodes.DUP) {
                        method.instructions.remove(instruction);
                        method.instructions.remove(site);
                        method.instructions.remove(duplicate);
                        changed++;
                    }
                }
                instruction = next;
            }
        }
        return changed;
    }

    private static Map<String, List<Integer>> ldcOffsets(byte[] bytes) {
        ClassFileView view = new JvmClassFileParser().parse(bytes);
        Map<String, List<Integer>> result = new HashMap<String, List<Integer>>();
        for (ClassFileMethod method : view.methods()) {
            List<Integer> offsets = new ArrayList<Integer>();
            for (BytecodeInstruction instruction : method.instructions()) {
                int opcode = instruction.opcode();
                if (opcode == Opcodes.LDC || opcode == 19 /* LDC_W */
                        || opcode == 20 /* LDC2_W */) {
                    offsets.add(Integer.valueOf(instruction.offset()));
                }
            }
            result.put(method.name() + method.descriptor(), offsets);
        }
        return result;
    }

    private static boolean isProbeCall(AbstractInsnNode instruction) {
        if (!(instruction instanceof MethodInsnNode)) return false;
        MethodInsnNode call = (MethodInsnNode) instruction;
        return call.getOpcode() == Opcodes.INVOKESTATIC
                && BRIDGE_INTERNAL.equals(call.owner)
                && BRIDGE_METHOD.equals(call.name)
                && BRIDGE_DESCRIPTOR.equals(call.desc);
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static boolean matchesAny(Rule[] rules, String literal) {
        for (Rule rule : rules) {
            if (globMatches(rule.pattern, literal, rule.caseSensitive)) return true;
        }
        return false;
    }

    private static boolean globMatches(String pattern, String value, boolean exactCase) {
        int patternIndex = 0;
        int valueIndex = 0;
        int starIndex = -1;
        int retryValueIndex = -1;
        while (valueIndex < value.length()) {
            if (patternIndex < pattern.length()) {
                char patternCharacter = pattern.charAt(patternIndex);
                if (patternCharacter == '?' || charactersEqual(
                        patternCharacter, value.charAt(valueIndex), exactCase)) {
                    patternIndex++;
                    valueIndex++;
                    continue;
                }
                if (patternCharacter == '*') {
                    starIndex = patternIndex++;
                    retryValueIndex = valueIndex;
                    continue;
                }
            }
            if (starIndex < 0) return false;
            patternIndex = starIndex + 1;
            valueIndex = ++retryValueIndex;
        }
        while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
            patternIndex++;
        }
        return patternIndex == pattern.length();
    }

    private static boolean charactersEqual(char left, char right, boolean exactCase) {
        if (left == right) return true;
        if (exactCase) return false;
        return foldAscii(left) == foldAscii(right);
    }

    private static char foldAscii(char value) {
        return value >= 'A' && value <= 'Z' ? (char) (value + ('a' - 'A')) : value;
    }

    private static final class Rule {
        private final String pattern;
        private final boolean caseSensitive;

        private Rule(String pattern, boolean caseSensitive) {
            this.pattern = pattern;
            this.caseSensitive = caseSensitive;
        }
    }

    static final class Site {
        private final String methodName;
        private final String descriptor;
        private final int bci;
        private final String literal;

        private Site(String methodName, String descriptor, int bci, String literal) {
            this.methodName = methodName;
            this.descriptor = descriptor;
            this.bci = bci;
            this.literal = literal;
        }

        String methodName() { return methodName; }
        String descriptor() { return descriptor; }
        int bci() { return bci; }
        String literal() { return literal; }
    }
}
