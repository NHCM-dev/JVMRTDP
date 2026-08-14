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
import org.objectweb.asm.tree.TryCatchBlockNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * ASM-backed live bytecode editor. CLI/TUI edits share a staged class transaction and install
 * it through JVMTI RedefineClasses only when the caller explicitly flushes it.
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
    private final Map<String, StagedClass> staged =
            new LinkedHashMap<String, StagedClass>();
    private long revision;

    public JvmBytecodeEditor(RemoteJVMTIEnv jvmti) {
        if (jvmti == null) throw new IllegalArgumentException("jvmti must not be null");
        this.jvmti = jvmti;
        this.jni = jvmti.server().javaVM().jniEnv();
    }

    /** Validates and emits class bytes without modifying the target JVM. */
    public synchronized JvmBytecodePatchResult preview(JvmBytecodePatch patch) {
        if (patch == null) throw new IllegalArgumentException("patch must not be null");
        byte[] original = classBytes(patch.className());
        return rewrite(original, patch, false, true);
    }

    /**
     * Adds a patch to the in-memory edit transaction for its class. No target code is
     * redefined until {@link #flush(String)} (or {@link #flushAll()}) is called. Staged
     * class bytes intentionally keep provisional frames so several temporarily
     * unverifiable instruction edits can be completed as one final transaction.
     */
    public synchronized JvmBytecodePatchResult stage(JvmBytecodePatch patch) {
        if (patch == null) throw new IllegalArgumentException("patch must not be null");
        String className = normalize(patch.className());
        StagedClass previous = staged.get(className);
        byte[] input = previous == null ? jvmti.getClassBytes(className) : previous.current;
        JvmBytecodePatchResult generated = rewrite(input, patch, false, false);
        recordStage(className, input, generated);
        return new JvmBytecodePatchResult(className, input, generated.patchedBytes(),
                generated.operationCount(), generated.changedMethods(), generated.relocations(), false);
    }

    /** Returns staged bytes when present, otherwise the current target class bytes. */
    public synchronized byte[] classBytes(String className) {
        StagedClass value = staged.get(normalize(className));
        return value == null ? jvmti.getClassBytes(className) : value.current.clone();
    }

    public synchronized boolean hasStaged(String className) {
        return staged.containsKey(normalize(className));
    }

    public synchronized Set<String> stagedClasses() {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(staged.keySet()));
    }

    public synchronized int stagedOperationCount(String className) {
        StagedClass value = staged.get(normalize(className));
        return value == null ? 0 : value.operationCount;
    }

    public synchronized long revision() { return revision; }

    /** Advanced Library API equivalent of {@link #stage(JvmBytecodePatch)} for ASM users. */
    public synchronized JvmBytecodePatchResult stageMethod(String className, String methodName,
            String descriptor, Consumer<MethodNode> editor) {
        if (editor == null) throw new IllegalArgumentException("editor must not be null");
        String normalized = normalize(className);
        byte[] input = classBytes(normalized);
        ClassReader reader = new ClassReader(input);
        ClassNode type = new ClassNode();
        reader.accept(type, ClassReader.EXPAND_FRAMES);
        MethodNode method = method(type, methodName, descriptor);
        RewriteContext context = new RewriteContext(input, type, methodName, descriptor);
        editor.accept(method);
        sanitizeExceptionHandlers(method);
        JvmBytecodePatchResult generated = emit(normalized, input, reader, type, 1,
                Collections.singletonList(methodName + descriptor),
                Collections.singletonList(context), false, false);
        recordStage(normalized, input, generated);
        return generated;
    }

    /** Stages a static return interceptor; call {@link #flush(String)} when the transaction is complete. */
    public JvmBytecodePatchResult stageInterceptReturns(String className, String methodName,
            String descriptor, final String hookClass, final String hookMethod) {
        final Type returnType = Type.getReturnType(descriptor);
        final String hookDescriptor = returnType.getSort() == Type.VOID
                ? "()V" : "(" + returnType.getDescriptor() + ")" + returnType.getDescriptor();
        return stageMethod(className, methodName, descriptor, new Consumer<MethodNode>() {
            @Override public void accept(MethodNode method) {
                for (AbstractInsnNode instruction : returnInstructions(method)) {
                    method.instructions.insertBefore(instruction, new MethodInsnNode(
                            Opcodes.INVOKESTATIC, hookClass.replace('.', '/'), hookMethod,
                            hookDescriptor, false));
                }
            }
        });
    }

    public synchronized List<JvmExceptionHandlerInfo> exceptionHandlers(String className,
            String methodName, String descriptor) {
        byte[] bytes = classBytes(className);
        ClassNode type = new ClassNode();
        new ClassReader(bytes).accept(type, ClassReader.EXPAND_FRAMES);
        MethodNode method = method(type, methodName, descriptor);
        List<AbstractInsnNode> real = realInstructions(method);
        List<BytecodeInstruction> decoded = new JvmClassFileParser().parse(bytes)
                .method(methodName, descriptor).instructions();
        int codeEnd = codeEnd(decoded);
        Map<AbstractInsnNode, Integer> bcis = new IdentityHashMap<AbstractInsnNode, Integer>();
        for (int index = 0; index < Math.min(real.size(), decoded.size()); index++) {
            bcis.put(real.get(index), Integer.valueOf(decoded.get(index).offset()));
        }
        List<JvmExceptionHandlerInfo> result = new ArrayList<JvmExceptionHandlerInfo>();
        for (int index = 0; index < method.tryCatchBlocks.size(); index++) {
            TryCatchBlockNode block = method.tryCatchBlocks.get(index);
            result.add(new JvmExceptionHandlerInfo(index, bciAtOrAfter(block.start, bcis, codeEnd),
                    bciAtOrAfter(block.end, bcis, codeEnd),
                    bciAtOrAfter(block.handler, bcis, codeEnd), block.type));
        }
        return Collections.unmodifiableList(result);
    }

    /** Recomputes frames once and atomically installs every staged edit for one class. */
    public synchronized JvmBytecodePatchResult flush(String className) {
        String normalized = normalize(className);
        StagedClass value = staged.get(normalized);
        if (value == null) throw new IllegalStateException("No staged bytecode edits for " + normalized);
        byte[] finalized = finalizeFrames(normalized, value.current);
        Map<String, Map<Long, Long>> finalRelocations = relocateBetween(
                value.current, finalized, value.changedMethods);
        Map<String, Map<Long, Long>> forward = compose(value.forward, finalRelocations);
        jvmti.redefineClass(normalized, finalized, forward);
        staged.remove(normalized);
        remember(undo, normalized, new HistoryEntry(value.original, finalized, forward));
        deque(redo, normalized).clear();
        revision++;
        return new JvmBytecodePatchResult(normalized, value.original, finalized,
                value.operationCount, new ArrayList<String>(value.changedMethods), forward, true);
    }

    /** Flushes all staged classes, rolling back already installed classes on failure. */
    public synchronized List<JvmBytecodePatchResult> flushAll() {
        if (staged.isEmpty()) throw new IllegalStateException("No staged bytecode edits");
        List<JvmBytecodePatchResult> generated = new ArrayList<JvmBytecodePatchResult>();
        for (Map.Entry<String, StagedClass> entry : staged.entrySet()) {
            StagedClass value = entry.getValue();
            byte[] finalized = finalizeFrames(entry.getKey(), value.current);
            Map<String, Map<Long, Long>> finalRelocations = relocateBetween(
                    value.current, finalized, value.changedMethods);
            Map<String, Map<Long, Long>> forward = compose(value.forward, finalRelocations);
            generated.add(new JvmBytecodePatchResult(entry.getKey(), value.original, finalized,
                    value.operationCount, new ArrayList<String>(value.changedMethods), forward, true));
        }
        int installedCount = 0;
        try {
            for (JvmBytecodePatchResult value : generated) {
                jvmti.redefineClass(value.className(), value.patchedBytes(), value.relocations());
                installedCount++;
            }
        } catch (RuntimeException failure) {
            for (int index = installedCount - 1; index >= 0; index--) {
                JvmBytecodePatchResult value = generated.get(index);
                try { jvmti.redefineClass(value.className(), value.originalBytes(),
                        inverse(value.relocations())); }
                catch (RuntimeException rollback) { failure.addSuppressed(rollback); }
            }
            throw failure;
        }
        for (JvmBytecodePatchResult value : generated) {
            staged.remove(value.className());
            remember(undo, value.className(), new HistoryEntry(value.originalBytes(),
                    value.patchedBytes(), value.relocations()));
            deque(redo, value.className()).clear();
        }
        revision++;
        return Collections.unmodifiableList(generated);
    }

    public synchronized void discard(String className) {
        String normalized = normalize(className);
        if (staged.remove(normalized) == null) {
            throw new IllegalStateException("No staged bytecode edits for " + normalized);
        }
        revision++;
    }

    public synchronized void discardAll() {
        if (!staged.isEmpty()) {
            staged.clear();
            revision++;
        }
    }

    /** Installs externally produced bytes while keeping staged/history/view state coherent. */
    public synchronized void redefineExternal(String className, byte[] classBytes) {
        if (classBytes == null || classBytes.length < 4) {
            throw new IllegalArgumentException("classBytes are invalid");
        }
        String normalized = normalize(className);
        requireNoStaged(normalized);
        jvmti.redefineClass(normalized, classBytes);
        deque(undo, normalized).clear();
        deque(redo, normalized).clear();
        revision++;
    }

    public synchronized void retransformExternal(String className) {
        String normalized = normalize(className);
        requireNoStaged(normalized);
        jvmti.retransformClass(normalized);
        deque(undo, normalized).clear();
        deque(redo, normalized).clear();
        revision++;
    }

    private void recordStage(String className, byte[] input, JvmBytecodePatchResult generated) {
        StagedClass previous = staged.get(className);
        if (previous == null) {
            previous = new StagedClass(input, generated.patchedBytes(),
                    generated.operationCount(), generated.changedMethods(), generated.relocations());
            staged.put(className, previous);
        } else {
            previous.current = generated.patchedBytes().clone();
            previous.operationCount += generated.operationCount();
            previous.changedMethods.addAll(generated.changedMethods());
            previous.forward = compose(previous.forward, generated.relocations());
        }
        revision++;
    }

    /** Applies all operations as one class redefinition and records one undo entry. */
    public synchronized JvmBytecodePatchResult apply(JvmBytecodePatch patch) {
        if (patch == null) throw new IllegalArgumentException("patch must not be null");
        requireNoStaged(patch.className());
        byte[] original = jvmti.getClassBytes(patch.className());
        JvmBytecodePatchResult generated = rewrite(original, patch, false, true);
        jvmti.redefineClass(patch.className(), generated.patchedBytes(), generated.relocations());
        remember(undo, patch.className(), new HistoryEntry(original,
                generated.patchedBytes(), generated.relocations()));
        deque(redo, patch.className()).clear();
        revision++;
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
            requireNoStaged(patch.className());
            if (!classes.add(patch.className())) throw new IllegalArgumentException(
                    "A batch may contain only one transaction per class: " + patch.className());
            byte[] original = jvmti.getClassBytes(patch.className());
            generated.add(rewrite(original, patch, false, true));
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
        requireNoStaged(className);
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
        revision++;
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
        requireNoStaged(className);
        HistoryEntry entry = deque(undo, className).pollFirst();
        if (entry == null) throw new IllegalStateException("No bytecode edit to undo for " + className);
        try { jvmti.redefineClass(className, entry.previous, inverse(entry.forward)); }
        catch (RuntimeException failure) { deque(undo, className).addFirst(entry); throw failure; }
        remember(redo, className, entry);
        revision++;
    }

    public synchronized void redo(String className) {
        requireNoStaged(className);
        HistoryEntry entry = deque(redo, className).pollFirst();
        if (entry == null) throw new IllegalStateException("No bytecode edit to redo for " + className);
        try { jvmti.redefineClass(className, entry.next, entry.forward); }
        catch (RuntimeException failure) { deque(redo, className).addFirst(entry); throw failure; }
        remember(undo, className, entry);
        revision++;
    }

    private JvmBytecodePatchResult rewrite(byte[] original, JvmBytecodePatch patch,
            boolean installed, boolean computeFrames) {
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
        for (RewriteContext context : contexts.values()) sanitizeExceptionHandlers(context.method);
        return emit(patch.className(), original, reader, type, patch.operations().size(),
                new ArrayList<String>(changedMethods),
                new ArrayList<RewriteContext>(contexts.values()), installed, computeFrames);
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
        case ADD_EXCEPTION_HANDLER: {
            String[] specification = operation.assembly().split("\\|", 2);
            if (specification.length != 2) throw new IllegalArgumentException(
                    "Invalid exception-handler specification: " + operation.assembly());
            final int handlerBci;
            try { handlerBci = Integer.decode(specification[0]).intValue(); }
            catch (NumberFormatException failure) { throw new IllegalArgumentException(
                    "Invalid exception handler BCI: " + specification[0], failure); }
            LabelNode start = context.labelAt(operation.fromBci(), "try start");
            LabelNode end = context.labelAt(operation.toBci(), "try end (exclusive)");
            LabelNode handler = context.labelAt(handlerBci, "handler");
            String type = specification[1].trim();
            if (type.isEmpty() || "*".equals(type) || "any".equalsIgnoreCase(type)
                    || "finally".equalsIgnoreCase(type)) type = null;
            else type = type.replace('.', '/');
            method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, type));
            break;
        }
        case DELETE_EXCEPTION_HANDLER: {
            int index = operation.fromBci();
            if (index < 0 || index >= method.tryCatchBlocks.size()) {
                throw new IllegalArgumentException("Exception handler index " + index
                        + " is outside 0.." + Math.max(-1, method.tryCatchBlocks.size() - 1)
                        + " for " + method.name + method.desc);
            }
            method.tryCatchBlocks.remove(index);
            break;
        }
        default:
            throw new IllegalArgumentException("Unsupported patch operation " + operation.kind());
        }
    }

    private JvmBytecodePatchResult emit(String className, byte[] original, ClassReader reader,
            ClassNode type, int operationCount, List<String> changedMethods,
            List<RewriteContext> contexts, boolean installed) {
        return emit(className, original, reader, type, operationCount, changedMethods,
                contexts, installed, true);
    }

    private JvmBytecodePatchResult emit(String className, byte[] original, ClassReader reader,
            ClassNode type, int operationCount, List<String> changedMethods,
            List<RewriteContext> contexts, boolean installed, boolean computeFrames) {
        TargetClassWriter writer = new TargetClassWriter(reader,
                computeFrames ? ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS : 0, jni);
        try { type.accept(writer); }
        catch (RuntimeException failure) {
            throw bytecodeFailure(className, changedMethods, failure);
        }
        byte[] patched = writer.toByteArray();
        // A second parse validates bounds and provides actual post-write BCI values.
        final ClassFileView output;
        try { output = new JvmClassFileParser().parse(patched); }
        catch (RuntimeException failure) {
            throw bytecodeFailure(className, changedMethods, failure);
        }
        Map<String, Map<Long, Long>> relocations = new LinkedHashMap<String, Map<Long, Long>>();
        try {
            for (RewriteContext context : contexts) {
                ClassFileMethod outputMethod = output.method(context.method.name, context.method.desc);
                relocations.put(JvmBytecodePatchResult.methodKey(context.method.name, context.method.desc),
                        context.relocations(outputMethod.instructions()));
            }
        } catch (RuntimeException failure) {
            throw bytecodeFailure(className, changedMethods, failure);
        }
        return new JvmBytecodePatchResult(className, original, patched, operationCount,
                changedMethods, relocations, installed);
    }

    private byte[] finalizeFrames(String className, byte[] provisional) {
        try {
            ClassReader reader = new ClassReader(provisional);
            ClassNode type = new ClassNode();
            // Provisional edits deliberately retain stale frames. Drop them before the
            // one final verifier-grade frame/max calculation.
            reader.accept(type, ClassReader.SKIP_FRAMES);
            TargetClassWriter writer = new TargetClassWriter(reader,
                    ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, jni);
            type.accept(writer);
            byte[] result = writer.toByteArray();
            new JvmClassFileParser().parse(result);
            return result;
        } catch (RuntimeException failure) {
            throw bytecodeFailure(className, Collections.singletonList("flush"), failure);
        }
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

    /** Keeps handler labels attached and removes protected ranges made empty by deletion. */
    private static void sanitizeExceptionHandlers(MethodNode method) {
        List<TryCatchBlockNode> valid = new ArrayList<TryCatchBlockNode>();
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            int start = method.instructions.indexOf(block.start);
            int end = method.instructions.indexOf(block.end);
            int handler = method.instructions.indexOf(block.handler);
            if (start < 0 || end < 0 || handler < 0 || start >= end) continue;
            boolean protectedInstruction = false;
            for (AbstractInsnNode value = block.start.getNext(); value != null && value != block.end;
                    value = value.getNext()) {
                if (value.getOpcode() >= 0) { protectedInstruction = true; break; }
            }
            if (!protectedInstruction) continue;
            valid.add(block);
        }
        if (valid.size() != method.tryCatchBlocks.size()) {
            method.tryCatchBlocks.clear();
            method.tryCatchBlocks.addAll(valid);
        }
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

    private static Map<String, Map<Long, Long>> compose(
            Map<String, Map<Long, Long>> first,
            Map<String, Map<Long, Long>> second) {
        Map<String, Map<Long, Long>> result =
                new LinkedHashMap<String, Map<Long, Long>>();
        Set<String> methods = new LinkedHashSet<String>();
        methods.addAll(first.keySet());
        methods.addAll(second.keySet());
        for (String method : methods) {
            Map<Long, Long> left = first.get(method);
            Map<Long, Long> right = second.get(method);
            Map<Long, Long> values = new LinkedHashMap<Long, Long>();
            if (left == null) {
                if (right != null) values.putAll(right);
            } else if (right == null) values.putAll(left);
            else {
                for (Map.Entry<Long, Long> value : left.entrySet()) {
                    Long target = right.get(value.getValue());
                    values.put(value.getKey(), target == null ? value.getValue() : target);
                }
            }
            result.put(method, values);
        }
        return result;
    }

    private static Map<String, Map<Long, Long>> relocateBetween(byte[] before, byte[] after,
            Collection<String> changedMethods) {
        ClassFileView left = new JvmClassFileParser().parse(before);
        ClassFileView right = new JvmClassFileParser().parse(after);
        Map<String, Map<Long, Long>> result =
                new LinkedHashMap<String, Map<Long, Long>>();
        for (ClassFileMethod leftMethod : left.methods()) {
            String display = leftMethod.name() + leftMethod.descriptor();
            if (!changedMethods.contains(display)) continue;
            ClassFileMethod rightMethod = right.method(leftMethod.name(), leftMethod.descriptor());
            List<BytecodeInstruction> leftCode = leftMethod.instructions();
            List<BytecodeInstruction> rightCode = rightMethod.instructions();
            Map<Long, Long> method = new LinkedHashMap<Long, Long>();
            int count = Math.min(leftCode.size(), rightCode.size());
            for (int index = 0; index < count; index++) {
                method.put(Long.valueOf(leftCode.get(index).offset()),
                        Long.valueOf(rightCode.get(index).offset()));
            }
            result.put(JvmBytecodePatchResult.methodKey(
                    leftMethod.name(), leftMethod.descriptor()), method);
        }
        return result;
    }

    private static IllegalArgumentException bytecodeFailure(String className,
            Collection<String> methods, RuntimeException failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        if (message == null || message.trim().isEmpty()) message = root.getClass().getSimpleName();
        if (message.contains("Index -1") || message.contains("Index -2")
                || message.contains("out of bounds")) {
            message = "the provisional operand stack/control flow is incomplete; stage the "
                    + "remaining edits and flush only after the method is verifier-valid (ASM: "
                    + message + ")";
        }
        return new IllegalArgumentException("Unable to emit bytecode for " + className + " "
                + methods + ": " + message, failure);
    }

    private static String normalize(String className) {
        if (className == null || className.trim().isEmpty()) {
            throw new IllegalArgumentException("className must not be empty");
        }
        return className.trim().replace('/', '.');
    }

    private void requireNoStaged(String className) {
        String normalized = normalize(className);
        if (staged.containsKey(normalized)) throw new IllegalStateException(
                "Class " + normalized + " has staged bytecode edits; flush or discard them "
                        + "before using an immediate apply/undo operation");
    }

    private static final class StagedClass {
        private final byte[] original;
        private byte[] current;
        private int operationCount;
        private final Set<String> changedMethods = new LinkedHashSet<String>();
        private Map<String, Map<Long, Long>> forward;

        private StagedClass(byte[] original, byte[] current, int operationCount,
                Collection<String> changedMethods,
                Map<String, Map<Long, Long>> forward) {
            this.original = original.clone();
            this.current = current.clone();
            this.operationCount = operationCount;
            this.changedMethods.addAll(changedMethods);
            this.forward = new LinkedHashMap<String, Map<Long, Long>>(forward);
        }
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
            if (!decoded.isEmpty()) {
                int end = codeEnd(decoded);
                AbstractInsnNode last = method.instructions.getLast();
                LabelNode endLabel;
                if (last instanceof LabelNode) endLabel = (LabelNode) last;
                else {
                    endLabel = new LabelNode();
                    method.instructions.add(endLabel);
                }
                labels.put("@" + end, endLabel);
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

        private LabelNode labelAt(int bci, String role) {
            LabelNode result = labels.get("@" + bci);
            if (result == null || method.instructions.indexOf(result) < 0) {
                throw new IllegalArgumentException(role + " BCI " + bci
                        + " is not an attached instruction boundary in "
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

    private static int bciAtOrAfter(AbstractInsnNode node,
            Map<AbstractInsnNode, Integer> bcis, int codeEnd) {
        for (AbstractInsnNode current = node; current != null; current = current.getNext()) {
            Integer value = bcis.get(current);
            if (value != null) return value.intValue();
        }
        return codeEnd;
    }

    private static int codeEnd(List<BytecodeInstruction> instructions) {
        if (instructions.isEmpty()) return 0;
        BytecodeInstruction last = instructions.get(instructions.size() - 1);
        return last.offset() + last.bytes().length;
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
