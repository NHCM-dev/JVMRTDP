package nhcm.jvmrtdp.api.bytecode;

import nhcm.jvmrtdp.controllerside.analysis.BytecodeInstruction;
import nhcm.jvmrtdp.controllerside.analysis.ClassFileMethod;
import nhcm.jvmrtdp.controllerside.analysis.ClassFileView;
import nhcm.jvmrtdp.controllerside.analysis.JvmClassFileParser;
import nhcm.jvmrtdp.handles.java.RemoteClassInfo;
import nhcm.jvmrtdp.handles.jvm.RemoteJNIEnv;
import nhcm.jvmrtdp.handles.jvm.RemoteJVMTIEnv;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * ASM-backed live bytecode editor. Each patch rewrites one complete class and installs it
 * through JVMTI RedefineClasses only after every requested operation has succeeded.
 */
public final class JvmBytecodeEditor {
    private static final int HISTORY_LIMIT = 16;
    private final RemoteJVMTIEnv jvmti;
    private final RemoteJNIEnv jni;
    private final JvmBytecodeAssembler assembler = new JvmBytecodeAssembler();
    private final Map<String, Deque<HistoryEntry>> undo =
            new LinkedHashMap<String, Deque<HistoryEntry>>();
    private final Map<String, Deque<HistoryEntry>> redo =
            new LinkedHashMap<String, Deque<HistoryEntry>>();

    public JvmBytecodeEditor(RemoteJVMTIEnv jvmti) {
        if (jvmti == null) throw new IllegalArgumentException("jvmti must not be null");
        this.jvmti = jvmti;
        this.jni = jvmti.server().javaVM().jniEnv();
    }

    /** Validates and emits class bytes without modifying the target JVM. */
    public synchronized JvmBytecodePatchResult preview(JvmBytecodePatch patch) {
        if (patch == null) throw new IllegalArgumentException("patch must not be null");
        byte[] original = jvmti.getClassBytes(patch.className());
        return rewrite(original, patch, false);
    }

    /** Applies all operations as one class redefinition and records one undo entry. */
    public synchronized JvmBytecodePatchResult apply(JvmBytecodePatch patch) {
        if (patch == null) throw new IllegalArgumentException("patch must not be null");
        byte[] original = jvmti.getClassBytes(patch.className());
        JvmBytecodePatchResult generated = rewrite(original, patch, false);
        jvmti.redefineClass(patch.className(), generated.patchedBytes(), generated.relocations());
        remember(undo, patch.className(), new HistoryEntry(original,
                generated.patchedBytes(), generated.relocations()));
        deque(redo, patch.className()).clear();
        return installed(generated);
    }

    /**
     * Applies multiple class transactions. All classes are generated first; a later redefine
     * failure triggers best-effort rollback of classes already installed.
     */
    public synchronized List<JvmBytecodePatchResult> applyBatch(List<JvmBytecodePatch> patches) {
        if (patches == null || patches.isEmpty()) {
            throw new IllegalArgumentException("patches must not be empty");
        }
        List<JvmBytecodePatchResult> generated = new ArrayList<JvmBytecodePatchResult>();
        Set<String> classes = new LinkedHashSet<String>();
        for (JvmBytecodePatch patch : patches) {
            if (!classes.add(patch.className())) throw new IllegalArgumentException(
                    "A batch may contain only one transaction per class: " + patch.className());
            generated.add(preview(patch));
        }
        int installed = 0;
        try {
            for (JvmBytecodePatchResult result : generated) {
                jvmti.redefineClass(result.className(), result.patchedBytes(), result.relocations());
                installed++;
            }
        } catch (RuntimeException failure) {
            for (int index = installed - 1; index >= 0; index--) {
                JvmBytecodePatchResult result = generated.get(index);
                try { jvmti.redefineClass(result.className(), result.originalBytes(),
                        inverse(result.relocations())); }
                catch (RuntimeException rollback) { failure.addSuppressed(rollback); }
            }
            throw failure;
        }
        List<JvmBytecodePatchResult> result = new ArrayList<JvmBytecodePatchResult>();
        for (JvmBytecodePatchResult value : generated) {
            remember(undo, value.className(), new HistoryEntry(value.originalBytes(),
                    value.patchedBytes(), value.relocations()));
            deque(redo, value.className()).clear();
            result.add(installed(value));
        }
        return Collections.unmodifiableList(result);
    }

    /** Advanced Library API: directly edits an ASM MethodNode, then recomputes frames and installs it. */
    public synchronized JvmBytecodePatchResult editMethod(String className, String methodName,
            String descriptor, Consumer<MethodNode> editor) {
        if (editor == null) throw new IllegalArgumentException("editor must not be null");
        byte[] original = jvmti.getClassBytes(className);
        ClassReader reader = new ClassReader(original);
        ClassNode type = new ClassNode();
        reader.accept(type, ClassReader.EXPAND_FRAMES);
        MethodNode method = method(type, methodName, descriptor);
        RewriteContext context = new RewriteContext(original, type, methodName, descriptor);
        editor.accept(method);
        JvmBytecodePatchResult generated = emit(className, original, reader, type,
                1, Collections.singletonList(methodName + descriptor),
                Collections.singletonList(context), false);
        jvmti.redefineClass(className, generated.patchedBytes(), generated.relocations());
        remember(undo, className, new HistoryEntry(original,
                generated.patchedBytes(), generated.relocations()));
        deque(redo, className).clear();
        return installed(generated);
    }

    /**
     * Routes every normal return through a visible static hook. For a method returning T the
     * hook signature is {@code (T)T}; for void it is {@code ()V}. The hook can log or replace T.
     */
    public JvmBytecodePatchResult interceptReturns(String className, String methodName,
            String descriptor, final String hookClass, final String hookMethod) {
        final Type returnType = Type.getReturnType(descriptor);
        final String hookDescriptor = returnType.getSort() == Type.VOID
                ? "()V" : "(" + returnType.getDescriptor() + ")" + returnType.getDescriptor();
        return editMethod(className, methodName, descriptor, new Consumer<MethodNode>() {
            @Override public void accept(MethodNode method) {
                List<AbstractInsnNode> returns = returnInstructions(method);
                for (AbstractInsnNode instruction : returns) {
                    method.instructions.insertBefore(instruction, new MethodInsnNode(
                            Opcodes.INVOKESTATIC, hookClass.replace('.', '/'), hookMethod,
                            hookDescriptor, false));
                }
            }
        });
    }

    public synchronized boolean canUndo(String className) { return !deque(undo, className).isEmpty(); }
    public synchronized boolean canRedo(String className) { return !deque(redo, className).isEmpty(); }

    public synchronized void undo(String className) {
        HistoryEntry entry = deque(undo, className).pollFirst();
        if (entry == null) throw new IllegalStateException("No bytecode edit to undo for " + className);
        try { jvmti.redefineClass(className, entry.previous, inverse(entry.forward)); }
        catch (RuntimeException failure) { deque(undo, className).addFirst(entry); throw failure; }
        remember(redo, className, entry);
    }

    public synchronized void redo(String className) {
        HistoryEntry entry = deque(redo, className).pollFirst();
        if (entry == null) throw new IllegalStateException("No bytecode edit to redo for " + className);
        try { jvmti.redefineClass(className, entry.next, entry.forward); }
        catch (RuntimeException failure) { deque(redo, className).addFirst(entry); throw failure; }
        remember(undo, className, entry);
    }

    private JvmBytecodePatchResult rewrite(byte[] original, JvmBytecodePatch patch, boolean installed) {
        ClassReader reader = new ClassReader(original);
        ClassNode type = new ClassNode();
        reader.accept(type, ClassReader.EXPAND_FRAMES);
        if (!type.name.replace('/', '.').equals(patch.className())) {
            throw new IllegalArgumentException("Patch class " + patch.className()
                    + " does not match class bytes " + type.name.replace('/', '.'));
        }
        Map<String, RewriteContext> contexts = new LinkedHashMap<String, RewriteContext>();
        Set<String> changedMethods = new LinkedHashSet<String>();
        for (JvmBytecodePatch.Operation operation : patch.operations()) {
            String key = JvmBytecodePatchResult.methodKey(
                    operation.methodName(), operation.descriptor());
            RewriteContext context = contexts.get(key);
            if (context == null) {
                MethodNode method = method(type, operation.methodName(), operation.descriptor());
                context = new RewriteContext(original, type,
                        operation.methodName(), operation.descriptor());
                contexts.put(key, context);
            }
            apply(context, operation);
            changedMethods.add(operation.methodName() + operation.descriptor());
        }
        return emit(patch.className(), original, reader, type, patch.operations().size(),
                new ArrayList<String>(changedMethods),
                new ArrayList<RewriteContext>(contexts.values()), installed);
    }

    private void apply(RewriteContext context, JvmBytecodePatch.Operation operation) {
        MethodNode method = context.method;
        switch (operation.kind()) {
        case INSERT_BEFORE: {
            AbstractInsnNode anchor = context.anchor(operation.fromBci());
            method.instructions.insertBefore(anchor,
                    assembler.assemble(operation.assembly(), context.labels));
            break;
        }
        case INSERT_AFTER: {
            AbstractInsnNode anchor = context.anchor(operation.fromBci());
            InsnList addition = assembler.assemble(operation.assembly(), context.labels);
            AbstractInsnNode tail = addition.getLast();
            AbstractInsnNode insertionPoint = context.afterTails.get(
                    Integer.valueOf(operation.fromBci()));
            method.instructions.insert(insertionPoint == null ? anchor : insertionPoint, addition);
            context.afterTails.put(Integer.valueOf(operation.fromBci()), tail);
            break;
        }
        case REPLACE: {
            AbstractInsnNode anchor = context.anchor(operation.fromBci());
            requireAttached(method, anchor, operation);
            method.instructions.insertBefore(anchor,
                    assembler.assemble(operation.assembly(), context.labels));
            method.instructions.remove(anchor);
            break;
        }
        case DELETE:
            boolean removed = false;
            for (Map.Entry<Integer, AbstractInsnNode> entry : context.originalByBci.entrySet()) {
                if (entry.getKey().intValue() < operation.fromBci()
                        || entry.getKey().intValue() > operation.toBci()) continue;
                if (method.instructions.indexOf(entry.getValue()) >= 0) {
                    method.instructions.remove(entry.getValue());
                    removed = true;
                }
            }
            if (!removed) throw new IllegalArgumentException("No attached instruction in BCI range "
                    + operation.fromBci() + ".." + operation.toBci());
            break;
        case INSERT_BEFORE_RETURNS:
            for (AbstractInsnNode instruction : returnInstructions(method)) {
                method.instructions.insertBefore(instruction,
                        assembler.assemble(operation.assembly(), context.labels));
            }
            break;
        case REPLACE_RETURNS:
            for (AbstractInsnNode instruction : returnInstructions(method)) {
                method.instructions.insertBefore(instruction,
                        assembler.assemble(operation.assembly(), context.labels));
                method.instructions.remove(instruction);
            }
            break;
        default:
            throw new IllegalArgumentException("Unsupported patch operation " + operation.kind());
        }
    }

    private JvmBytecodePatchResult emit(String className, byte[] original, ClassReader reader,
            ClassNode type, int operationCount, List<String> changedMethods,
            List<RewriteContext> contexts, boolean installed) {
        TargetClassWriter writer = new TargetClassWriter(reader,
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, jni);
        type.accept(writer);
        byte[] patched = writer.toByteArray();
        // A second parse validates bounds and provides actual post-write BCI values.
        ClassFileView output = new JvmClassFileParser().parse(patched);
        Map<String, Map<Long, Long>> relocations = new LinkedHashMap<String, Map<Long, Long>>();
        for (RewriteContext context : contexts) {
            ClassFileMethod outputMethod = output.method(context.method.name, context.method.desc);
            relocations.put(JvmBytecodePatchResult.methodKey(context.method.name, context.method.desc),
                    context.relocations(outputMethod.instructions()));
        }
        return new JvmBytecodePatchResult(className, original, patched, operationCount,
                changedMethods, relocations, installed);
    }

    private static JvmBytecodePatchResult installed(JvmBytecodePatchResult value) {
        return new JvmBytecodePatchResult(value.className(), value.originalBytes(), value.patchedBytes(),
                value.operationCount(), value.changedMethods(), value.relocations(), true);
    }

    private static MethodNode method(ClassNode type, String name, String descriptor) {
        for (MethodNode method : type.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) return method;
        }
        throw new IllegalArgumentException("Method not found: "
                + type.name.replace('/', '.') + "." + name + descriptor);
    }

    private static List<AbstractInsnNode> returnInstructions(MethodNode method) {
        List<AbstractInsnNode> result = new ArrayList<AbstractInsnNode>();
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            int opcode = instruction.getOpcode();
            if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) result.add(instruction);
        }
        if (result.isEmpty()) throw new IllegalArgumentException(
                "Method has no normal return instructions: " + method.name + method.desc);
        return result;
    }

    private static void requireAttached(MethodNode method, AbstractInsnNode node,
            JvmBytecodePatch.Operation operation) {
        if (method.instructions.indexOf(node) < 0) {
            throw new IllegalArgumentException("Patch anchor was removed by an earlier operation: " + operation);
        }
    }

    private static void remember(Map<String, Deque<HistoryEntry>> history,
            String className, HistoryEntry entry) {
        Deque<HistoryEntry> values = deque(history, className);
        values.addFirst(entry);
        while (values.size() > HISTORY_LIMIT) values.removeLast();
    }

    private static Deque<HistoryEntry> deque(Map<String, Deque<HistoryEntry>> history,
            String className) {
        String key = className.replace('/', '.');
        Deque<HistoryEntry> result = history.get(key);
        if (result == null) {
            result = new ArrayDeque<HistoryEntry>();
            history.put(key, result);
        }
        return result;
    }

    private static Map<String, Map<Long, Long>> inverse(
            Map<String, Map<Long, Long>> relocations) {
        Map<String, Map<Long, Long>> result = new LinkedHashMap<String, Map<Long, Long>>();
        for (Map.Entry<String, Map<Long, Long>> method : relocations.entrySet()) {
            Map<Long, Long> reversed = new LinkedHashMap<Long, Long>();
            for (Map.Entry<Long, Long> relocation : method.getValue().entrySet()) {
                // Several deleted BCIs may collapse onto one surviving BCI. Prefer the
                // earliest original location when mapping that surviving breakpoint back.
                Long previous = reversed.get(relocation.getValue());
                if (previous == null || relocation.getKey().longValue() < previous.longValue()) {
                    reversed.put(relocation.getValue(), relocation.getKey());
                }
            }
            result.put(method.getKey(), reversed);
        }
        return result;
    }

    private static final class HistoryEntry {
        private final byte[] previous;
        private final byte[] next;
        private final Map<String, Map<Long, Long>> forward;

        private HistoryEntry(byte[] previous, byte[] next,
                Map<String, Map<Long, Long>> forward) {
            this.previous = previous.clone();
            this.next = next.clone();
            this.forward = forward;
        }
    }

    private static final class RewriteContext {
        private final MethodNode method;
        private final Map<Integer, AbstractInsnNode> originalByBci =
                new LinkedHashMap<Integer, AbstractInsnNode>();
        private final Map<Integer, AbstractInsnNode> afterTails =
                new LinkedHashMap<Integer, AbstractInsnNode>();
        private final Map<String, LabelNode> labels = new LinkedHashMap<String, LabelNode>();

        private RewriteContext(byte[] original, ClassNode type, String methodName, String descriptor) {
            this.method = method(type, methodName, descriptor);
            List<AbstractInsnNode> real = realInstructions(method);
            List<BytecodeInstruction> decoded = new JvmClassFileParser().parse(original)
                    .method(methodName, descriptor).instructions();
            if (real.size() != decoded.size()) {
                throw new IllegalStateException("ASM/parser instruction mismatch for " + methodName
                        + descriptor + ": " + real.size() + " != " + decoded.size());
            }
            for (int index = 0; index < real.size(); index++) {
                AbstractInsnNode instruction = real.get(index);
                int bci = decoded.get(index).offset();
                originalByBci.put(Integer.valueOf(bci), instruction);
                labels.put("@" + bci, ensureLabelBefore(method.instructions, instruction));
            }
        }

        private AbstractInsnNode anchor(int bci) {
            AbstractInsnNode result = originalByBci.get(Integer.valueOf(bci));
            if (result == null) throw new IllegalArgumentException(
                    "BCI " + bci + " is not an instruction boundary in " + method.name + method.desc);
            if (method.instructions.indexOf(result) < 0) {
                throw new IllegalArgumentException("BCI " + bci
                        + " anchor was removed by an earlier operation in "
                        + method.name + method.desc);
            }
            return result;
        }

        private Map<Long, Long> relocations(List<BytecodeInstruction> output) {
            Map<Long, Long> result = new LinkedHashMap<Long, Long>();
            if (output.isEmpty()) return result;
            // A label was planted at every original instruction boundary before editing.
            // ASM resolves those labels after jump resizing, so this remains accurate even
            // when the emitted instruction count differs from the MethodNode instruction count.
            for (Integer oldBci : originalByBci.keySet()) {
                LabelNode marker = labels.get("@" + oldBci);
                if (marker == null) continue;
                final int emittedOffset;
                try { emittedOffset = marker.getLabel().getOffset(); }
                catch (IllegalStateException removedByAdvancedEditor) { continue; }
                BytecodeInstruction nearest = output.get(0);
                for (BytecodeInstruction candidate : output) {
                    nearest = candidate;
                    if (candidate.offset() >= emittedOffset) break;
                }
                result.put(Long.valueOf(oldBci.longValue()),
                        Long.valueOf(nearest.offset()));
            }
            return result;
        }
    }

    private static List<AbstractInsnNode> realInstructions(MethodNode method) {
        List<AbstractInsnNode> result = new ArrayList<AbstractInsnNode>();
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction.getOpcode() >= 0) result.add(instruction);
        }
        return result;
    }

    private static LabelNode ensureLabelBefore(InsnList instructions, AbstractInsnNode instruction) {
        AbstractInsnNode previous = instruction.getPrevious();
        if (previous instanceof LabelNode) return (LabelNode) previous;
        LabelNode label = new LabelNode();
        instructions.insertBefore(instruction, label);
        return label;
    }

    private static final class TargetClassWriter extends ClassWriter {
        private final RemoteJNIEnv jni;
        private final Map<String, RemoteClassInfo> types = new LinkedHashMap<String, RemoteClassInfo>();

        private TargetClassWriter(ClassReader reader, int flags, RemoteJNIEnv jni) {
            super(reader, flags);
            this.jni = jni;
        }

        @Override protected String getCommonSuperClass(String left, String right) {
            if (left.equals(right)) return left;
            if (left.startsWith("[") || right.startsWith("[")) {
                return commonArrayOrObject(left, right);
            }
            try {
                if (assignable(left, right)) return left;
                if (assignable(right, left)) return right;
                RemoteClassInfo leftInfo = info(left);
                if (leftInfo != null && leftInfo.isInterface()) return "java/lang/Object";
                String current = left;
                while (current != null && !current.isEmpty() && !"java/lang/Object".equals(current)) {
                    RemoteClassInfo value = info(current);
                    current = value == null ? null : internal(value.superclass());
                    if (current != null && assignable(current, right)) return current;
                }
            } catch (RuntimeException ignored) {
                // Referenced classes can be lazy/unloaded. Object is verifier-safe for ordinary merges.
            }
            return "java/lang/Object";
        }

        private String commonArrayOrObject(String left, String right) {
            boolean leftArray = left.startsWith("[");
            boolean rightArray = right.startsWith("[");
            if (!leftArray || !rightArray) {
                String object = leftArray ? right : left;
                return "java/lang/Object".equals(object) || "java/lang/Cloneable".equals(object)
                        || "java/io/Serializable".equals(object)
                        ? object : "java/lang/Object";
            }
            String leftComponent = left.substring(1);
            String rightComponent = right.substring(1);
            boolean leftReference = leftComponent.startsWith("L") || leftComponent.startsWith("[");
            boolean rightReference = rightComponent.startsWith("L") || rightComponent.startsWith("[");
            if (!leftReference || !rightReference) {
                return leftComponent.equals(rightComponent) ? left : "java/lang/Object";
            }
            String common = getCommonSuperClass(componentName(leftComponent),
                    componentName(rightComponent));
            return "[" + (common.startsWith("[") ? common : "L" + common + ";");
        }

        private static String componentName(String descriptor) {
            return descriptor.startsWith("L")
                    ? descriptor.substring(1, descriptor.length() - 1) : descriptor;
        }

        private boolean assignable(String target, String source) {
            if (target.equals(source) || "java/lang/Object".equals(target)) return true;
            Deque<String> pending = new ArrayDeque<String>();
            Set<String> visited = new LinkedHashSet<String>();
            pending.add(source);
            while (!pending.isEmpty()) {
                String current = pending.removeFirst();
                if (!visited.add(current)) continue;
                if (target.equals(current)) return true;
                RemoteClassInfo value = info(current);
                if (value == null) continue;
                if (value.superclass() != null && !value.superclass().isEmpty()) {
                    pending.add(internal(value.superclass()));
                }
                for (String iface : value.interfaces()) pending.add(internal(iface));
            }
            return false;
        }

        private RemoteClassInfo info(String internalName) {
            RemoteClassInfo result = types.get(internalName);
            if (result == null) {
                result = jni.findClass(internalName.replace('/', '.')).info();
                types.put(internalName, result);
            }
            return result;
        }

        private static String internal(String name) {
            return name == null ? null : name.replace('.', '/');
        }
    }
}
