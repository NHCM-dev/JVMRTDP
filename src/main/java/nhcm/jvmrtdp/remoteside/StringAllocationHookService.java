package nhcm.jvmrtdp.remoteside;

import nhcm.jvmrtdp.agent.NativeAgent;
import nhcm.jvmrtdp.api.jvmti.JvmtiCapability;
import nhcm.jvmrtdp.api.jvmti.JvmtiCapabilityStatus;
import nhcm.jvmrtdp.api.jvmti.JvmtiEventType;
import nhcm.jvmrtdp.api.hook.JvmStringAllocationMode;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Target-side ownership bridge for native String allocation filters and JVMTI events. */
public final class StringAllocationHookService {
    private static final Map<String, JvmStringAllocationMode> REGISTRATIONS =
            new HashMap<String, JvmStringAllocationMode>();
    private static final Map<String, String> CONTENT_PATTERNS = new HashMap<String, String>();
    private static final Map<String, Boolean> CASE_SENSITIVITY = new HashMap<String, Boolean>();
    private static final Map<String, Boolean> LDC_ENABLED = new HashMap<String, Boolean>();
    private static final Map<String, String> CREATOR_CLASSES = new HashMap<String, String>();
    private static final Map<String, String> CREATOR_METHODS = new HashMap<String, String>();
    private static final Map<String, String> CREATOR_DESCRIPTORS = new HashMap<String, String>();
    private static final Map<String, Long> MAXIMUM_HITS = new HashMap<String, Long>();
    private static final Map<String, Integer> SAMPLE_INTERVALS = new HashMap<String, Integer>();
    private static final String BRIDGE_CLASS = "nhcm.jvmrtdp.bootstrap.StringHookBridge";
    private static final String BRIDGE_INTERNAL = "nhcm/jvmrtdp/bootstrap/StringHookBridge";
    private static final String BRIDGE_METHOD = "observed";
    private static final String BRIDGE_DESCRIPTOR = "(Ljava/lang/String;)V";
    private static boolean probeInstalled;
    private static Class<?> probeBridge;
    private static final StringLdcProbeTransformer LDC_TRANSFORMER =
            new StringLdcProbeTransformer();
    private static boolean ldcTransformerRegistered;

    private StringAllocationHookService() { }

    public static synchronized void set(String id, String contentPattern,
            String creatorClassPattern, String creatorMethodPattern,
            String creatorDescriptorPattern, boolean caseSensitive) {
        set(id, contentPattern, creatorClassPattern, creatorMethodPattern,
                creatorDescriptorPattern, caseSensitive,
                JvmStringAllocationMode.FAST, 0L, 1, false);
    }

    public static synchronized void set(String id, String contentPattern,
            String creatorClassPattern, String creatorMethodPattern,
            String creatorDescriptorPattern, boolean caseSensitive,
            JvmStringAllocationMode mode, long maximumHits, int sampleEvery) {
        set(id, contentPattern, creatorClassPattern, creatorMethodPattern,
                creatorDescriptorPattern, caseSensitive, mode, maximumHits,
                sampleEvery, false);
    }

    public static synchronized void set(String id, String contentPattern,
            String creatorClassPattern, String creatorMethodPattern,
            String creatorDescriptorPattern, boolean caseSensitive,
            JvmStringAllocationMode mode, long maximumHits, int sampleEvery,
            boolean includeLdc) {
        NativeAgent.enterStringHookSuppression();
        try {
            setSuppressed(id, contentPattern, creatorClassPattern, creatorMethodPattern,
                    creatorDescriptorPattern, caseSensitive, mode, maximumHits,
                    sampleEvery, includeLdc);
        } finally {
            NativeAgent.exitStringHookSuppression();
        }
    }

    private static void setSuppressed(String id, String contentPattern,
            String creatorClassPattern, String creatorMethodPattern,
            String creatorDescriptorPattern, boolean caseSensitive,
            JvmStringAllocationMode mode, long maximumHits, int sampleEvery,
            boolean includeLdc) {
        if (mode == null) mode = JvmStringAllocationMode.FAST;
        JvmStringAllocationMode previous = REGISTRATIONS.get(id);
        String previousContent = CONTENT_PATTERNS.get(id);
        Boolean previousSensitivity = CASE_SENSITIVITY.get(id);
        Boolean previousLdc = LDC_ENABLED.get(id);
        String previousCreatorClass = CREATOR_CLASSES.get(id);
        String previousCreatorMethod = CREATOR_METHODS.get(id);
        String previousCreatorDescriptor = CREATOR_DESCRIPTORS.get(id);
        Long previousMaximumHits = MAXIMUM_HITS.get(id);
        Integer previousSampleInterval = SAMPLE_INTERVALS.get(id);
        boolean literalRulesChanged = Boolean.TRUE.equals(previousLdc) || includeLdc;
        boolean added = previous == null;
        boolean addedCompleteLease = false;
        boolean installedProbe = false;
        if (includeLdc && !Boolean.TRUE.equals(previousLdc)) {
            ensureCapability(JvmtiCapability.CAN_GET_CONSTANT_POOL);
            ensureCapability(JvmtiCapability.CAN_GET_BYTECODES);
            ensureCapability(JvmtiCapability.CAN_GENERATE_BREAKPOINT_EVENTS);
        }
        if (added || previous != mode) {
            ensureCapability(JvmtiCapability.CAN_RETRANSFORM_CLASSES);
            ensureCapability(JvmtiCapability.CAN_REDEFINE_CLASSES);
            if (mode == JvmStringAllocationMode.COMPLETE) {
                ensureCapability(JvmtiCapability.CAN_GENERATE_VM_OBJECT_ALLOC_EVENTS);
            }
            try {
                if (added && REGISTRATIONS.isEmpty()) {
                    installProbe();
                    installedProbe = true;
                }
                if (mode == JvmStringAllocationMode.COMPLETE
                        && previous != JvmStringAllocationMode.COMPLETE) {
                    JvmtiCallbackDispatcher.retainInfrastructureEvent(JvmtiEventType.VM_OBJECT_ALLOC);
                    addedCompleteLease = true;
                }
            } catch (RuntimeException failure) {
                if (addedCompleteLease) {
                    JvmtiCallbackDispatcher.releaseInfrastructureEvent(JvmtiEventType.VM_OBJECT_ALLOC);
                }
                if (installedProbe) uninstallProbeQuietly();
                throw failure;
            }
        }
        try {
            // Stage Java-side filters while inactive. The native update is the final activation
            // point, so an immediate one-shot hit cannot be accidentally re-enabled afterward.
            REGISTRATIONS.put(id, mode);
            CONTENT_PATTERNS.put(id, normalizedPattern(contentPattern));
            CASE_SENSITIVITY.put(id, Boolean.valueOf(caseSensitive));
            LDC_ENABLED.put(id, Boolean.valueOf(includeLdc));
            CREATOR_CLASSES.put(id, normalizedPattern(creatorClassPattern));
            CREATOR_METHODS.put(id, normalizedPattern(creatorMethodPattern));
            CREATOR_DESCRIPTORS.put(id, normalizedPattern(creatorDescriptorPattern));
            MAXIMUM_HITS.put(id, Long.valueOf(maximumHits));
            SAMPLE_INTERVALS.put(id, Integer.valueOf(sampleEvery));
            configureProbeBridge(false);
            if (literalRulesChanged) refreshLiteralProbes();
            NativeAgent.setStringAllocationHook(id, contentPattern,
                    creatorClassPattern, creatorMethodPattern, creatorDescriptorPattern,
                    caseSensitive, mode.ordinal(), maximumHits, sampleEvery, includeLdc, true);
            if (previous == JvmStringAllocationMode.COMPLETE
                    && mode != JvmStringAllocationMode.COMPLETE) {
                JvmtiCallbackDispatcher.releaseInfrastructureEvent(JvmtiEventType.VM_OBJECT_ALLOC);
            }
        } catch (RuntimeException failure) {
            if (previous == null) {
                REGISTRATIONS.remove(id);
                CONTENT_PATTERNS.remove(id);
                CASE_SENSITIVITY.remove(id);
                LDC_ENABLED.remove(id);
                CREATOR_CLASSES.remove(id);
                CREATOR_METHODS.remove(id);
                CREATOR_DESCRIPTORS.remove(id);
                MAXIMUM_HITS.remove(id);
                SAMPLE_INTERVALS.remove(id);
            } else {
                REGISTRATIONS.put(id, previous);
                CONTENT_PATTERNS.put(id, previousContent);
                CASE_SENSITIVITY.put(id, previousSensitivity);
                LDC_ENABLED.put(id, previousLdc);
                CREATOR_CLASSES.put(id, previousCreatorClass);
                CREATOR_METHODS.put(id, previousCreatorMethod);
                CREATOR_DESCRIPTORS.put(id, previousCreatorDescriptor);
                MAXIMUM_HITS.put(id, previousMaximumHits);
                SAMPLE_INTERVALS.put(id, previousSampleInterval);
            }
            if (literalRulesChanged) {
                try { refreshLiteralProbes(); }
                catch (RuntimeException restoreFailure) { failure.addSuppressed(restoreFailure); }
            }
            try { configureProbeBridge(!REGISTRATIONS.isEmpty()); }
            catch (RuntimeException restoreFailure) { failure.addSuppressed(restoreFailure); }
            try {
                if (previous == null) {
                    NativeAgent.setStringAllocationHook(id, "*", "*", "*", "*", true,
                            mode.ordinal(), 0L, 1, false, false);
                } else {
                    NativeAgent.setStringAllocationHook(id, previousContent,
                            previousCreatorClass, previousCreatorMethod,
                            previousCreatorDescriptor,
                            !Boolean.FALSE.equals(previousSensitivity), previous.ordinal(),
                            previousMaximumHits == null ? 0L : previousMaximumHits.longValue(),
                            previousSampleInterval == null ? 1 : previousSampleInterval.intValue(),
                            Boolean.TRUE.equals(previousLdc), true);
                }
            } catch (RuntimeException restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
            if (addedCompleteLease) JvmtiCallbackDispatcher.releaseInfrastructureEvent(
                    JvmtiEventType.VM_OBJECT_ALLOC);
            if (installedProbe) uninstallProbeQuietly();
            throw failure;
        }
    }

    public static synchronized boolean remove(String id) {
        NativeAgent.enterStringHookSuppression();
        try {
            return removeSuppressed(id);
        } finally {
            NativeAgent.exitStringHookSuppression();
        }
    }

    private static boolean removeSuppressed(String id) {
        JvmStringAllocationMode mode = REGISTRATIONS.get(id);
        if (mode == null) return false;
        String content = CONTENT_PATTERNS.get(id);
        Boolean sensitivity = CASE_SENSITIVITY.get(id);
        Boolean ldc = LDC_ENABLED.get(id);
        String creatorClass = CREATOR_CLASSES.get(id);
        String creatorMethod = CREATOR_METHODS.get(id);
        String creatorDescriptor = CREATOR_DESCRIPTORS.get(id);
        Long maximumHits = MAXIMUM_HITS.get(id);
        Integer sampleInterval = SAMPLE_INTERVALS.get(id);
        REGISTRATIONS.remove(id);
        CONTENT_PATTERNS.remove(id);
        CASE_SENSITIVITY.remove(id);
        boolean removedLdc = Boolean.TRUE.equals(LDC_ENABLED.remove(id));
        CREATOR_CLASSES.remove(id);
        CREATOR_METHODS.remove(id);
        CREATOR_DESCRIPTORS.remove(id);
        MAXIMUM_HITS.remove(id);
        SAMPLE_INTERVALS.remove(id);
        try {
            configureProbeBridge(false);
            if (removedLdc) refreshLiteralProbes();
            NativeAgent.setStringAllocationHook(id, "*", "*", "*", "*", true,
                    mode.ordinal(), 0L, 1, false, false);
        } catch (RuntimeException failure) {
            REGISTRATIONS.put(id, mode);
            CONTENT_PATTERNS.put(id, content);
            CASE_SENSITIVITY.put(id, sensitivity);
            LDC_ENABLED.put(id, ldc);
            CREATOR_CLASSES.put(id, creatorClass);
            CREATOR_METHODS.put(id, creatorMethod);
            CREATOR_DESCRIPTORS.put(id, creatorDescriptor);
            MAXIMUM_HITS.put(id, maximumHits);
            SAMPLE_INTERVALS.put(id, sampleInterval);
            if (removedLdc) {
                try { refreshLiteralProbes(); }
                catch (RuntimeException restoreFailure) { failure.addSuppressed(restoreFailure); }
            }
            try {
                NativeAgent.setStringAllocationHook(id, content, creatorClass, creatorMethod,
                        creatorDescriptor, !Boolean.FALSE.equals(sensitivity), mode.ordinal(),
                        maximumHits == null ? 0L : maximumHits.longValue(),
                        sampleInterval == null ? 1 : sampleInterval.intValue(),
                        Boolean.TRUE.equals(ldc), true);
            } catch (RuntimeException restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
            try { configureProbeBridge(true); }
            catch (RuntimeException restoreFailure) { failure.addSuppressed(restoreFailure); }
            throw failure;
        }
        if (mode == JvmStringAllocationMode.COMPLETE) {
            JvmtiCallbackDispatcher.releaseInfrastructureEvent(JvmtiEventType.VM_OBJECT_ALLOC);
        }
        if (REGISTRATIONS.isEmpty()) uninstallProbe();
        return true;
    }

    private static void installProbe() {
        if (probeInstalled) return;
        Class<?> bridge = findLoadedClassOrNull(BRIDGE_CLASS);
        if (bridge == null || bridge.getClassLoader() != null) {
            bridge = NativeAgent.defineClass(BRIDGE_CLASS, bridgeBytes(), null);
        }
        NativeAgent.registerStringHookBridge(bridge);
        probeBridge = bridge;
        rewriteStringConstructors(true);
        probeInstalled = true;
    }

    private static Class<?> findLoadedClassOrNull(String className) {
        try {
            return NativeAgent.findLoadedClass(className);
        } catch (IllegalArgumentException missing) {
            return null;
        }
    }

    private static void uninstallProbe() {
        if (!probeInstalled) return;
        setProbeActive(false);
        LDC_TRANSFORMER.configure(new String[0], new boolean[0]);
        if (ldcTransformerRegistered) {
            refreshLoadedLiteralProbes();
            JvmtiCallbackDispatcher.releaseInfrastructureEvent(
                    JvmtiEventType.CLASS_FILE_LOAD_HOOK);
            NativeAgent.registerStringLdcTransformer(null);
            ldcTransformerRegistered = false;
        }
        rewriteStringConstructors(false);
        probeInstalled = false;
    }

    private static void setProbeActive(boolean active) {
        if (probeBridge == null) return;
        try {
            probeBridge.getMethod("setActive", Boolean.TYPE).invoke(null,
                    Boolean.valueOf(active));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot update the bootstrap String hook bridge", failure);
        }
    }

    private static void configureProbeBridge(boolean active) {
        if (probeBridge == null) return;
        String[] patterns = new String[REGISTRATIONS.size()];
        boolean[] sensitivity = new boolean[patterns.length];
        int index = 0;
        for (String id : REGISTRATIONS.keySet()) {
            patterns[index] = normalizedPattern(CONTENT_PATTERNS.get(id));
            sensitivity[index] = !Boolean.FALSE.equals(CASE_SENSITIVITY.get(id));
            index++;
        }
        try {
            probeBridge.getMethod("configure", String[].class, boolean[].class, Boolean.TYPE)
                    .invoke(null, patterns, sensitivity, Boolean.valueOf(active));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot configure the bootstrap String hook bridge",
                    failure);
        }
    }

    private static String normalizedPattern(String pattern) {
        return pattern == null || pattern.isEmpty() ? "*" : pattern;
    }

    private static void refreshLiteralProbes() {
        int count = 0;
        for (Boolean enabled : LDC_ENABLED.values()) if (Boolean.TRUE.equals(enabled)) count++;
        String[] patterns = new String[count];
        boolean[] sensitivity = new boolean[count];
        int index = 0;
        for (String id : REGISTRATIONS.keySet()) {
            if (!Boolean.TRUE.equals(LDC_ENABLED.get(id))) continue;
            patterns[index] = normalizedPattern(CONTENT_PATTERNS.get(id));
            sensitivity[index] = !Boolean.FALSE.equals(CASE_SENSITIVITY.get(id));
            index++;
        }
        LDC_TRANSFORMER.configure(patterns, sensitivity);
        if (count != 0 && !ldcTransformerRegistered) {
            NativeAgent.registerStringLdcTransformer(LDC_TRANSFORMER);
            try {
                JvmtiCallbackDispatcher.retainInfrastructureEvent(
                        JvmtiEventType.CLASS_FILE_LOAD_HOOK);
                ldcTransformerRegistered = true;
            } catch (RuntimeException failure) {
                NativeAgent.registerStringLdcTransformer(null);
                throw failure;
            }
        }
        if (ldcTransformerRegistered) refreshLoadedLiteralProbes();
        if (count == 0 && ldcTransformerRegistered) {
            JvmtiCallbackDispatcher.releaseInfrastructureEvent(
                    JvmtiEventType.CLASS_FILE_LOAD_HOOK);
            NativeAgent.registerStringLdcTransformer(null);
            ldcTransformerRegistered = false;
        }
    }

    private static void refreshLoadedLiteralProbes() {
        NativeAgent.clearStringLdcBreakpoints();
        Set<String> previouslyInstrumented = LDC_TRANSFORMER.instrumentedClassNames();
        for (Class<?> type : NativeAgent.listLoadedClasses()) {
            if (type == null || type.isArray() || type.isPrimitive()
                    || !StringLdcProbeTransformer.eligibleClass(type.getName())) continue;
            try {
                boolean installed = previouslyInstrumented.contains(type.getName());
                if (installed) {
                    // Probes are only installed as classes are loaded after the hook. Refreshing
                    // those few classes updates/removes their probes. Never retransform an
                    // ordinary already-loaded class here: doing that makes its active frames
                    // obsolete, so a main-entry hook would miss the very LDC it was meant to see.
                    NativeAgent.retransformClass(type);
                    continue;
                }
                if (!LDC_TRANSFORMER.enabled()) continue;
                byte[] constantPool = NativeAgent.constantPool(type);
                Map<Integer, String> matchingConstants =
                        LDC_TRANSFORMER.matchingConstants(constantPool);
                if (matchingConstants.isEmpty()) continue;
                String[] methods = NativeAgent.classMethods(type);
                for (int methodIndex = 0; methodIndex + 1 < methods.length; methodIndex += 2) {
                    String methodName = methods[methodIndex];
                    String descriptor = methods[methodIndex + 1];
                    byte[] bytecodes;
                    try {
                        bytecodes = NativeAgent.methodBytecodes(type, methodName, descriptor);
                    } catch (RuntimeException noCode) {
                        // Abstract and native methods have no bytecode to scan.
                        continue;
                    }
                    for (StringLdcProbeTransformer.Site site : LDC_TRANSFORMER.matchingSites(
                            methodName, descriptor, bytecodes, matchingConstants)) {
                        NativeAgent.registerStringLdcBreakpoint(type, site.methodName(),
                                site.descriptor(), site.bci(), site.literal());
                    }
                }
            } catch (RuntimeException ignored) {
                // Hidden, unmodifiable, obsolete, and VM-internal classes are expected here.
                // Future class loads still pass through the installed transformer.
            } catch (LinkageError ignored) {
                // A concurrently unloading loader must not prevent other classes from refreshing.
            }
        }
    }

    private static void uninstallProbeQuietly() {
        try { uninstallProbe(); } catch (RuntimeException ignored) { }
    }

    private static void rewriteStringConstructors(boolean install) {
        byte[] current = NativeAgent.getClassBytes("java.lang.String");
        ClassReader reader = new ClassReader(current);
        ClassNode type = new ClassNode();
        reader.accept(type, ClassReader.EXPAND_FRAMES);
        int changed = 0;
        for (MethodNode method : type.methods) {
            if (!"<init>".equals(method.name)) continue;
            if (install) changed += installProbe(method);
            else changed += removeProbe(method);
        }
        if (install && changed == 0 && !containsProbe(type)) {
            throw new IllegalStateException("No java.lang.String constructor return could be instrumented");
        }
        if (changed == 0) return;
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        type.accept(writer);
        Class<?> stringClass = NativeAgent.findLoadedClass("java.lang.String");
        if (stringClass == null) throw new IllegalStateException("java.lang.String is not loaded");
        NativeAgent.redefineClass(stringClass, writer.toByteArray());
    }

    private static int installProbe(MethodNode method) {
        int changed = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction.getOpcode() != Opcodes.RETURN || isProbeCall(previousCode(instruction))) continue;
            InsnList probe = new InsnList();
            probe.add(new VarInsnNode(Opcodes.ALOAD, 0));
            probe.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE_INTERNAL,
                    BRIDGE_METHOD, BRIDGE_DESCRIPTOR, false));
            method.instructions.insertBefore(instruction, probe);
            changed++;
        }
        return changed;
    }

    private static int removeProbe(MethodNode method) {
        int changed = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;) {
            AbstractInsnNode next = instruction.getNext();
            if (isProbeCall(instruction)) {
                AbstractInsnNode load = previousCode(instruction);
                if (load instanceof VarInsnNode && load.getOpcode() == Opcodes.ALOAD
                        && ((VarInsnNode) load).var == 0) {
                    method.instructions.remove(instruction);
                    method.instructions.remove(load);
                    changed++;
                }
            }
            instruction = next;
        }
        return changed;
    }

    private static boolean containsProbe(ClassNode type) {
        for (MethodNode method : type.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                    instruction != null; instruction = instruction.getNext()) {
                if (isProbeCall(instruction)) return true;
            }
        }
        return false;
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static boolean isProbeCall(AbstractInsnNode instruction) {
        if (!(instruction instanceof MethodInsnNode)) return false;
        MethodInsnNode call = (MethodInsnNode) instruction;
        return call.getOpcode() == Opcodes.INVOKESTATIC && BRIDGE_INTERNAL.equals(call.owner)
                && BRIDGE_METHOD.equals(call.name) && BRIDGE_DESCRIPTOR.equals(call.desc);
    }

    private static byte[] bridgeBytes() {
        String resource = '/' + BRIDGE_INTERNAL + ".class";
        InputStream input = StringAllocationHookService.class.getResourceAsStream(resource);
        if (input == null) throw new IllegalStateException("Missing bootstrap bridge resource " + resource);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(1024);
            byte[] buffer = new byte[4096];
            for (int read; (read = input.read(buffer)) >= 0;) output.write(buffer, 0, read);
            return output.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read bootstrap String hook bridge", failure);
        } finally {
            try { input.close(); } catch (IOException ignored) { }
        }
    }

    private static void ensureCapability(JvmtiCapability capability) {
        JvmtiCapabilityStatus status = status(capability, NativeAgent.capabilityStatuses());
        if (status != null && status.enabled()) return;
        if (status != null && status.potential()) {
            status = status(capability, NativeAgent.addCapabilities(capability));
            if (status != null && status.enabled()) return;
        }
        throw new IllegalStateException("String allocation hooks require "
                + capability.wireName() + "; start the target with -agentpath if the live VM "
                + "can no longer grant it");
    }

    private static JvmtiCapabilityStatus status(JvmtiCapability capability,
            Iterable<JvmtiCapabilityStatus> statuses) {
        for (JvmtiCapabilityStatus status : statuses) {
            if (status.capability() == capability) return status;
        }
        return null;
    }
}
