package nhcm.jvmrtdp.controllerside.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Bounds-checked JVM classfile parser and complete variable-length bytecode decoder. */
public final class JvmClassFileParser {
    private static final String[] MNEMONICS = mnemonics();

    public ClassFileView parse(byte[] classBytes) {
        Reader input = new Reader(classBytes);
        if (input.u4() != 0xCAFEBABEL) throw new IllegalArgumentException("Invalid classfile magic");
        int minor = input.u2();
        int major = input.u2();
        ConstantPool pool = ConstantPool.read(input);
        input.u2();
        String className = pool.className(input.u2()).replace('/', '.');
        input.u2();
        skipTable(input, 2);
        skipMembers(input);
        int methodCount = input.u2();
        List<RawMethod> rawMethods = new ArrayList<RawMethod>(methodCount);
        for (int index = 0; index < methodCount; index++) rawMethods.add(readMethod(input, pool));
        skipAttributes(input);
        input.requireEnd();

        List<ClassFileMethod> methods = new ArrayList<ClassFileMethod>(rawMethods.size());
        for (RawMethod method : rawMethods) {
            methods.add(new ClassFileMethod(method.access, method.name, method.descriptor,
                    method.maxStack, method.maxLocals,
                    decode(method.code, method.lines, pool)));
        }
        return new ClassFileView(className, minor, major, methods, pool.describeAll());
    }

    private static RawMethod readMethod(Reader input, ConstantPool pool) {
        RawMethod method = new RawMethod();
        method.access = input.u2();
        method.name = pool.utf8(input.u2());
        method.descriptor = pool.utf8(input.u2());
        int attributes = input.u2();
        for (int index = 0; index < attributes; index++) {
            String name = pool.utf8(input.u2());
            int length = input.length();
            int end = input.position() + length;
            if ("Code".equals(name)) readCode(input, pool, method);
            input.position(end);
        }
        return method;
    }

    private static void readCode(Reader input, ConstantPool pool, RawMethod method) {
        method.maxStack = input.u2();
        method.maxLocals = input.u2();
        method.code = input.bytes(input.length());
        int handlers = input.u2();
        input.skip(handlers * 8);
        int attributes = input.u2();
        for (int index = 0; index < attributes; index++) {
            String name = pool.utf8(input.u2());
            int length = input.length();
            int end = input.position() + length;
            if ("LineNumberTable".equals(name)) {
                int count = input.u2();
                for (int line = 0; line < count; line++) {
                    method.lines.add(new Line(input.u2(), input.u2()));
                }
            }
            input.position(end);
        }
        Collections.sort(method.lines, Comparator.comparingInt(value -> value.offset));
    }

    private static List<BytecodeInstruction> decode(byte[] code, List<Line> lines, ConstantPool pool) {
        if (code == null) return Collections.emptyList();
        List<BytecodeInstruction> result = new ArrayList<BytecodeInstruction>();
        int offset = 0;
        while (offset < code.length) {
            int start = offset;
            int opcode = u1(code, offset++);
            String mnemonic = MNEMONICS[opcode];
            String operands = "";
            switch (opcode) {
            case 16: operands = Integer.toString((byte) u1(code, offset)); offset++; break;
            case 17: operands = Integer.toString(s2(code, offset)); offset += 2; break;
            case 18: {
                int cp = u1(code, offset++); operands = constant(pool, cp); break;
            }
            case 19: case 20: case 178: case 179: case 180: case 181:
            case 182: case 183: case 184: case 187: case 189: case 192: case 193: {
                int cp = u2(code, offset); offset += 2; operands = constant(pool, cp); break;
            }
            case 21: case 22: case 23: case 24: case 25:
            case 54: case 55: case 56: case 57: case 58: case 169:
                operands = Integer.toString(u1(code, offset++)); break;
            case 132:
                operands = u1(code, offset) + ", " + (byte) u1(code, offset + 1); offset += 2; break;
            case 153: case 154: case 155: case 156: case 157: case 158:
            case 159: case 160: case 161: case 162: case 163: case 164:
            case 165: case 166: case 167: case 168: case 198: case 199:
                operands = Integer.toString(start + s2(code, offset)); offset += 2; break;
            case 170: {
                offset = align4(offset);
                int defaultTarget = start + s4(code, offset); offset += 4;
                int low = s4(code, offset); offset += 4;
                int high = s4(code, offset); offset += 4;
                StringBuilder text = new StringBuilder("{");
                long count = (long) high - low + 1L;
                if (count < 0 || count > code.length) throw invalid(start, "tableswitch range");
                for (long item = 0; item < count; item++) {
                    long key = (long) low + item;
                    if (item != 0) text.append(", ");
                    text.append(key).append(':').append(start + s4(code, offset));
                    offset += 4;
                }
                operands = text.append(", default:").append(defaultTarget).append('}').toString();
                break;
            }
            case 171: {
                offset = align4(offset);
                int defaultTarget = start + s4(code, offset); offset += 4;
                int pairs = s4(code, offset); offset += 4;
                if (pairs < 0 || pairs > code.length / 8) throw invalid(start, "lookupswitch pairs");
                StringBuilder text = new StringBuilder("{");
                for (int pair = 0; pair < pairs; pair++) {
                    if (pair != 0) text.append(", ");
                    int key = s4(code, offset); offset += 4;
                    text.append(key).append(':').append(start + s4(code, offset)); offset += 4;
                }
                operands = text.append(", default:").append(defaultTarget).append('}').toString();
                break;
            }
            case 185: case 186: {
                int cp = u2(code, offset); int third = u1(code, offset + 2);
                offset += 4; operands = constant(pool, cp) + (opcode == 185 ? ", count=" + third : ""); break;
            }
            case 188: operands = newArrayType(u1(code, offset++)); break;
            case 196: {
                int nested = u1(code, offset++);
                if (nested != 132 && nested != 169
                        && !(nested >= 21 && nested <= 25)
                        && !(nested >= 54 && nested <= 58)) {
                    throw invalid(start, "illegal wide opcode " + nested);
                }
                String nestedName = MNEMONICS[nested];
                int local = u2(code, offset); offset += 2;
                if (nested == 132) {
                    int increment = s2(code, offset); offset += 2;
                    operands = nestedName + " " + local + ", " + increment;
                } else operands = nestedName + " " + local;
                break;
            }
            case 197: {
                int cp = u2(code, offset); int dimensions = u1(code, offset + 2); offset += 3;
                operands = constant(pool, cp) + ", dimensions=" + dimensions; break;
            }
            case 200: case 201:
                operands = Integer.toString(start + s4(code, offset)); offset += 4; break;
            default:
                if (opcode > 201 && opcode < 254) throw invalid(start, "reserved opcode " + opcode);
                break;
            }
            if (offset > code.length) throw invalid(start, "truncated " + mnemonic);
            result.add(new BytecodeInstruction(start, lineAt(lines, start), opcode, mnemonic,
                    operands, Arrays.copyOfRange(code, start, offset)));
        }
        return result;
    }

    private static String constant(ConstantPool pool, int index) {
        return "#" + index + " // " + pool.describe(index);
    }

    private static int lineAt(List<Line> lines, int offset) {
        int result = -1;
        for (Line line : lines) {
            if (line.offset > offset) break;
            result = line.number;
        }
        return result;
    }

    private static String newArrayType(int type) {
        String[] names = {"", "", "", "", "boolean", "char", "float", "double",
                "byte", "short", "int", "long"};
        return type >= 4 && type <= 11 ? names[type] : "atype=" + type;
    }

    private static IllegalArgumentException invalid(int offset, String message) {
        return new IllegalArgumentException("Invalid bytecode at BCI " + offset + ": " + message);
    }

    private static int align4(int offset) { return (offset + 3) & ~3; }
    private static int u1(byte[] values, int offset) { require(values, offset, 1); return values[offset] & 0xff; }
    private static int u2(byte[] values, int offset) {
        require(values, offset, 2); return (values[offset] & 0xff) << 8 | values[offset + 1] & 0xff;
    }
    private static int s2(byte[] values, int offset) { return (short) u2(values, offset); }
    private static int s4(byte[] values, int offset) {
        require(values, offset, 4);
        return (values[offset] & 0xff) << 24 | (values[offset + 1] & 0xff) << 16
                | (values[offset + 2] & 0xff) << 8 | values[offset + 3] & 0xff;
    }
    private static void require(byte[] values, int offset, int length) {
        if (offset < 0 || length < 0 || offset > values.length - length) {
            throw new IllegalArgumentException("Truncated classfile/bytecode at offset " + offset);
        }
    }

    private static void skipMembers(Reader input) {
        int count = input.u2();
        for (int index = 0; index < count; index++) {
            input.skip(6);
            skipAttributes(input);
        }
    }

    private static void skipAttributes(Reader input) {
        int count = input.u2();
        for (int index = 0; index < count; index++) {
            input.u2();
            input.skip(input.length());
        }
    }

    private static void skipTable(Reader input, int itemSize) {
        input.skip(input.u2() * itemSize);
    }

    private static String[] mnemonics() {
        String text = "nop aconst_null iconst_m1 iconst_0 iconst_1 iconst_2 iconst_3 iconst_4 iconst_5 "
                + "lconst_0 lconst_1 fconst_0 fconst_1 fconst_2 dconst_0 dconst_1 bipush sipush ldc ldc_w ldc2_w "
                + "iload lload fload dload aload iload_0 iload_1 iload_2 iload_3 lload_0 lload_1 lload_2 lload_3 "
                + "fload_0 fload_1 fload_2 fload_3 dload_0 dload_1 dload_2 dload_3 aload_0 aload_1 aload_2 aload_3 "
                + "iaload laload faload daload aaload baload caload saload istore lstore fstore dstore astore "
                + "istore_0 istore_1 istore_2 istore_3 lstore_0 lstore_1 lstore_2 lstore_3 fstore_0 fstore_1 fstore_2 fstore_3 "
                + "dstore_0 dstore_1 dstore_2 dstore_3 astore_0 astore_1 astore_2 astore_3 iastore lastore fastore dastore "
                + "aastore bastore castore sastore pop pop2 dup dup_x1 dup_x2 dup2 dup2_x1 dup2_x2 swap iadd ladd fadd dadd "
                + "isub lsub fsub dsub imul lmul fmul dmul idiv ldiv fdiv ddiv irem lrem frem drem ineg lneg fneg dneg "
                + "ishl lshl ishr lshr iushr lushr iand land ior lor ixor lxor iinc i2l i2f i2d l2i l2f l2d f2i f2l f2d "
                + "d2i d2l d2f i2b i2c i2s lcmp fcmpl fcmpg dcmpl dcmpg ifeq ifne iflt ifge ifgt ifle if_icmpeq if_icmpne "
                + "if_icmplt if_icmpge if_icmpgt if_icmple if_acmpeq if_acmpne goto jsr ret tableswitch lookupswitch ireturn "
                + "lreturn freturn dreturn areturn return getstatic putstatic getfield putfield invokevirtual invokespecial invokestatic "
                + "invokeinterface invokedynamic new newarray anewarray arraylength athrow checkcast instanceof monitorenter monitorexit "
                + "wide multianewarray ifnull ifnonnull goto_w jsr_w";
        String[] defined = text.split(" ");
        String[] result = new String[256];
        for (int index = 0; index < result.length; index++) result[index] = "reserved_" + index;
        System.arraycopy(defined, 0, result, 0, defined.length);
        result[202] = "breakpoint";
        result[254] = "impdep1";
        result[255] = "impdep2";
        return result;
    }

    private static final class RawMethod {
        private int access;
        private String name;
        private String descriptor;
        private int maxStack;
        private int maxLocals;
        private byte[] code;
        private final List<Line> lines = new ArrayList<Line>();
    }

    private static final class Line {
        private final int offset;
        private final int number;
        private Line(int offset, int number) { this.offset = offset; this.number = number; }
    }

    private static final class ConstantPool {
        private final Entry[] entries;
        private ConstantPool(Entry[] entries) { this.entries = entries; }

        private static ConstantPool read(Reader input) {
            Entry[] entries = new Entry[input.u2()];
            for (int index = 1; index < entries.length; index++) {
                int tag = input.u1();
                Entry entry = new Entry(tag);
                entries[index] = entry;
                switch (tag) {
                case 1: entry.value = modifiedUtf8(input.bytes(input.u2())); break;
                case 3: entry.value = (int) input.u4(); break;
                case 4: entry.value = Float.intBitsToFloat((int) input.u4()); break;
                case 5: entry.value = (input.u4() << 32) | input.u4(); index++; break;
                case 6: entry.value = Double.longBitsToDouble((input.u4() << 32) | input.u4()); index++; break;
                case 7: case 8: case 16: case 19: case 20: entry.a = input.u2(); break;
                case 9: case 10: case 11: case 12: case 17: case 18:
                    entry.a = input.u2(); entry.b = input.u2(); break;
                case 15: entry.a = input.u1(); entry.b = input.u2(); break;
                default: throw new IllegalArgumentException("Unsupported constant-pool tag " + tag);
                }
            }
            return new ConstantPool(entries);
        }

        private String utf8(int index) {
            Entry entry = entry(index, 1);
            return String.valueOf(entry.value);
        }

        private String className(int index) { return utf8(entry(index, 7).a); }

        private String describe(int index) {
            Entry entry = entry(index, -1);
            switch (entry.tag) {
            case 1: return quote(String.valueOf(entry.value));
            case 3: case 4: case 5: case 6: return String.valueOf(entry.value);
            case 7: return className(index).replace('/', '.');
            case 8: return quote(utf8(entry.a));
            case 9: case 10: case 11:
                return className(entry.a).replace('/', '.') + "." + describeNameType(entry.b);
            case 12: return describeNameType(index);
            case 15: return "handle[" + entry.a + "] " + describe(entry.b);
            case 16: return utf8(entry.a);
            case 17: case 18: return "bootstrap[" + entry.a + "] " + describeNameType(entry.b);
            case 19: return "module " + utf8(entry.a);
            case 20: return "package " + utf8(entry.a).replace('/', '.');
            default: return "cp-tag-" + entry.tag;
            }
        }

        private String describeNameType(int index) {
            Entry value = entry(index, 12);
            return utf8(value.a) + ":" + utf8(value.b);
        }

        private List<String> describeAll() {
            List<String> result = new ArrayList<String>();
            for (int index = 1; index < entries.length; index++) {
                Entry entry = entries[index];
                if (entry == null) continue;
                result.add(String.format("#%04d tag=%d %s", index, entry.tag, describe(index)));
            }
            return result;
        }

        private Entry entry(int index, int expectedTag) {
            if (index <= 0 || index >= entries.length || entries[index] == null) {
                throw new IllegalArgumentException("Invalid constant-pool index " + index);
            }
            Entry entry = entries[index];
            if (expectedTag >= 0 && entry.tag != expectedTag) {
                throw new IllegalArgumentException("Constant-pool #" + index + " has tag " + entry.tag
                        + ", expected " + expectedTag);
            }
            return entry;
        }

        private static String quote(String value) {
            return '"' + value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r") + '"';
        }

        private static String modifiedUtf8(byte[] bytes) {
            char[] characters = new char[bytes.length];
            int input = 0;
            int output = 0;
            while (input < bytes.length) {
                int first = bytes[input] & 0xff;
                if ((first & 0x80) == 0) {
                    characters[output++] = (char) first;
                    input++;
                } else if ((first & 0xe0) == 0xc0) {
                    if (input + 1 >= bytes.length) throw invalidUtf8(input);
                    int second = bytes[input + 1] & 0xff;
                    if ((second & 0xc0) != 0x80) throw invalidUtf8(input);
                    characters[output++] = (char) (((first & 0x1f) << 6) | second & 0x3f);
                    input += 2;
                } else if ((first & 0xf0) == 0xe0) {
                    if (input + 2 >= bytes.length) throw invalidUtf8(input);
                    int second = bytes[input + 1] & 0xff;
                    int third = bytes[input + 2] & 0xff;
                    if ((second & 0xc0) != 0x80 || (third & 0xc0) != 0x80) throw invalidUtf8(input);
                    characters[output++] = (char) (((first & 0x0f) << 12)
                            | ((second & 0x3f) << 6) | third & 0x3f);
                    input += 3;
                } else throw invalidUtf8(input);
            }
            return new String(characters, 0, output);
        }

        private static IllegalArgumentException invalidUtf8(int offset) {
            return new IllegalArgumentException("Invalid modified UTF-8 in constant pool at byte " + offset);
        }
    }

    private static final class Entry {
        private final int tag;
        private int a;
        private int b;
        private Object value;
        private Entry(int tag) { this.tag = tag; }
    }

    private static final class Reader {
        private final byte[] values;
        private int position;
        private Reader(byte[] values) {
            if (values == null) throw new IllegalArgumentException("classBytes must not be null");
            this.values = values;
        }
        private int position() { return position; }
        private void position(int value) {
            if (value < position || value > values.length) throw new IllegalArgumentException("Invalid classfile attribute length");
            position = value;
        }
        private int u1() { return JvmClassFileParser.u1(values, position++); }
        private int u2() { int result = JvmClassFileParser.u2(values, position); position += 2; return result; }
        private long u4() { require(values, position, 4); long result = Integer.toUnsignedLong(s4(values, position)); position += 4; return result; }
        private int length() { long value = u4(); if (value > Integer.MAX_VALUE) throw new IllegalArgumentException("Classfile item is too large"); return (int) value; }
        private byte[] bytes(int count) { require(values, position, count); byte[] result = Arrays.copyOfRange(values, position, position + count); position += count; return result; }
        private void skip(int count) { require(values, position, count); position += count; }
        private void requireEnd() { if (position != values.length) throw new IllegalArgumentException("Trailing classfile bytes: " + (values.length - position)); }
    }
}
