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

/** Target-side ownership bridge for native String allocation filters and JVMTI events. */
public final class StringAllocationHookService {
    private static final Map<String, JvmStringAllocationMode> REGISTRATIONS =
            new HashMap<String, JvmStringAllocationMode>();
    private static final Map<String, String> CONTENT_PATTERNS = new HashMap<String, String>();
    private static final Map<String, Boolean> CASE_SENSITIVITY = new HashMap<String, Boolean>();
    private static final String BRIDGE_CLASS = "nhcm.jvmrtdp.bootstrap.StringHookBridge";
    private static final String BRIDGE_INTERNAL = "nhcm/jvmrtdp/bootstrap/StringHookBridge";
    private static final String BRIDGE_METHOD = "observed";
    private static final String BRIDGE_DESCRIPTOR = "(Ljava/lang/String;)V";
    private static boolean probeInstalled;
    private static Class<?> probeBridge;

    private StringAllocationHookService() { }

    public static synchronized void set(String id, String contentPattern,
            String creatorClassPattern, String creatorMethodPattern,
            String creatorDescriptorPattern, boolean caseSensitive) {
        set(id, contentPattern, creatorClassPattern, creatorMethodPattern,
                creatorDescriptorPattern, caseSensitive,
                JvmStringAllocationMode.FAST, 0L, 1);
    }

    public static synchronized void set(String id, String contentPattern,
            String creatorClassPattern, String creatorMethodPattern,
            String creatorDescriptorPattern, boolean caseSensitive,
            JvmStringAllocationMode mode, long maximumHits, int sampleEvery) {
        if (mode == null) mode = JvmStringAllocationMode.FAST;
        JvmStringAllocationMode previous = REGISTRATIONS.get(id);
        String previousContent = CONTENT_PATTERNS.get(id);
        Boolean previousSensitivity = CASE_SENSITIVITY.get(id);
        boolean added = previous == null;
        boolean addedCompleteLease = false;
        boolean installedProbe = false;
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
            configureProbeBridge(false);
            NativeAgent.setStringAllocationHook(id, contentPattern,
                    creatorClassPattern, creatorMethodPattern, creatorDescriptorPattern,
                    caseSensitive, mode.ordinal(), maximumHits, sampleEvery, true);
            if (previous == JvmStringAllocationMode.COMPLETE
                    && mode != JvmStringAllocationMode.COMPLETE) {
                JvmtiCallbackDispatcher.releaseInfrastructureEvent(JvmtiEventType.VM_OBJECT_ALLOC);
            }
        } catch (RuntimeException failure) {
            if (previous == null) {
                REGISTRATIONS.remove(id);
                CONTENT_PATTERNS.remove(id);
                CASE_SENSITIVITY.remove(id);
            } else {
                REGISTRATIONS.put(id, previous);
                CONTENT_PATTERNS.put(id, previousContent);
                CASE_SENSITIVITY.put(id, previousSensitivity);
            }
            try { configureProbeBridge(!REGISTRATIONS.isEmpty()); }
            catch (RuntimeException restoreFailure) { failure.addSuppressed(restoreFailure); }
            if (addedCompleteLease) JvmtiCallbackDispatcher.releaseInfrastructureEvent(
                    JvmtiEventType.VM_OBJECT_ALLOC);
            if (installedProbe) uninstallProbeQuietly();
            throw failure;
        }
    }

    public static synchronized boolean remove(String id) {
        JvmStringAllocationMode mode = REGISTRATIONS.remove(id);
        if (mode == null) return false;
        CONTENT_PATTERNS.remove(id);
        CASE_SENSITIVITY.remove(id);
        try {
            configureProbeBridge(false);
            NativeAgent.setStringAllocationHook(id, "*", "*", "*", "*", true,
                    mode.ordinal(), 0L, 1, false);
        } finally {
            if (mode == JvmStringAllocationMode.COMPLETE) {
                JvmtiCallbackDispatcher.releaseInfrastructureEvent(JvmtiEventType.VM_OBJECT_ALLOC);
            }
            if (REGISTRATIONS.isEmpty()) uninstallProbe();
        }
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
