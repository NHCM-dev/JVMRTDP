package nhcm.jvmrtdp.handles.jvm;

import nhcm.jvmrtdp.command.CommandLine;
import nhcm.jvmrtdp.controllerside.JavaSourceCompiler;
import nhcm.jvmrtdp.handles.RemoteHandle;
import nhcm.jvmrtdp.handles.ServerHandle;
import nhcm.jvmrtdp.handles.java.RemoteObject;
import nhcm.jvmrtdp.protocol.CodeBundleCodec;
import nhcm.jvmrtdp.protocol.RemoteObjectDescriptor;
import nhcm.jvmrtdp.protocol.TextWireCodec;
import nhcm.jvmrtdp.api.jvmti.JvmtiCapability;
import nhcm.jvmrtdp.api.jvmti.JvmtiCapabilityStatus;
import nhcm.jvmrtdp.api.jvmti.JvmtiLocationFormat;
import nhcm.jvmrtdp.api.jvmti.JvmtiPhase;
import nhcm.jvmrtdp.api.jvmti.JvmtiClassInfo;
import nhcm.jvmrtdp.api.jvmti.JvmtiFieldInfo;
import nhcm.jvmrtdp.api.jvmti.JvmtiLineNumber;
import nhcm.jvmrtdp.api.jvmti.JvmtiMethodInfo;
import nhcm.jvmrtdp.api.jvmti.JvmtiThreadInfo;
import nhcm.jvmrtdp.api.jvmti.JvmtiMonitorUsage;
import nhcm.jvmrtdp.api.jvmti.JvmtiTimerInfo;
import nhcm.jvmrtdp.api.hook.JvmStringAllocationSpec;
import nhcm.jvmrtdp.api.jvmti.JvmtiEventType;
import nhcm.jvmrtdp.api.jvmti.JvmDebuggerState;
import nhcm.jvmrtdp.api.jvmti.JvmDebuggerLocal;
import nhcm.jvmrtdp.api.jvmti.JvmStackFrame;
import nhcm.jvmrtdp.api.jvmti.JvmBreakpointInfo;
import nhcm.jvmrtdp.api.jvmti.JvmBreakpointCondition;
import nhcm.jvmrtdp.api.jvmti.JvmFieldWatchInfo;
import nhcm.jvmrtdp.api.jvmti.JvmEventBreakpointInfo;
import nhcm.jvmrtdp.api.jvmti.JvmEventBreakpointSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Controller-side facade for JVMTI and target-JVM Java code deployment. */
public class RemoteJVMTIEnv extends RemoteHandle {
    private static final int UPLOAD_CHUNK_BYTES = 512 * 1024;
    private final Map<String, BreakpointRegistration> managedBreakpoints =
            new LinkedHashMap<String, BreakpointRegistration>();
    private final Map<String, JvmFieldWatchInfo> managedFieldWatches =
            new LinkedHashMap<String, JvmFieldWatchInfo>();
    private final Map<String, JvmEventBreakpointInfo> managedEventBreakpoints =
            new LinkedHashMap<String, JvmEventBreakpointInfo>();
    public enum DefinitionMode { CHILD, SAME_LOADER }
    public enum JarScope { CHILD, SYSTEM, BOOTSTRAP }

    public RemoteJVMTIEnv(ServerHandle server, long remoteId) {
        super(server, remoteId);
    }

    public byte[] getClassBytes(String className) {
        String encoded = executeForOutput(CommandLine.of("jvmti", "bytes", className));
        restoreBreakpointsAfterClassBytes(className);
        try {
            return Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Target returned invalid class bytes", exception);
        }
    }

    /** Always materializes the remote class bytes as a controller-side file. */
    public Path dumpClass(String className, Path outputFile) throws IOException {
        if (outputFile == null) throw new IllegalArgumentException("Output file must not be null");
        Path absolute = outputFile.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(absolute, getClassBytes(className));
        return absolute;
    }

    public List<String> capabilities() {
        return lines(executeForOutput(CommandLine.of("jvmti", "capabilities")));
    }

    public List<JvmtiCapabilityStatus> capabilityStatuses() {
        return parseCapabilityStatuses(executeForOutput(CommandLine.of("jvmti", "capability-status")));
    }

    public List<JvmtiCapabilityStatus> addCapabilities(JvmtiCapability... capabilities) {
        return changeCapabilities("capability.add", capabilities);
    }

    public List<JvmtiCapabilityStatus> relinquishCapabilities(JvmtiCapability... capabilities) {
        return changeCapabilities("capability.relinquish", capabilities);
    }

    public JvmtiPhase phase() {
        return JvmtiPhase.valueOf(executeForOutput(CommandLine.of("jvmti", "phase")));
    }

    public long time() {
        return Long.parseLong(executeForOutput(CommandLine.of("jvmti", "time")));
    }

    public int availableProcessors() {
        return Integer.parseInt(executeForOutput(CommandLine.of("jvmti", "processors")));
    }

    public JvmtiLocationFormat locationFormat() {
        return JvmtiLocationFormat.valueOf(
                executeForOutput(CommandLine.of("jvmti", "location-format")));
    }

    public String getSystemProperty(String name) {
        return executeForOutput(CommandLine.of("jvmti", "property.get", required(name, "name")));
    }

    public String setSystemProperty(String name, String value) {
        if (value == null) throw new IllegalArgumentException("value must not be null");
        return executeForOutput(CommandLine.of(
                "jvmti", "property.set", required(name, "name"), value));
    }

    public JvmtiClassInfo classInfo(String className) {
        String name = required(className, "className");
        List<String> fields = TextWireCodec.decode(
                executeForOutput(CommandLine.of("jvmti", "class.info", name)), 11);
        return new JvmtiClassInfo(fields.get(0), fields.get(1), fields.get(2), fields.get(3),
                Integer.parseInt(fields.get(4)), Integer.parseInt(fields.get(5)),
                Boolean.parseBoolean(fields.get(6)), Boolean.parseBoolean(fields.get(7)),
                Boolean.parseBoolean(fields.get(8)), Integer.parseInt(fields.get(9)),
                Integer.parseInt(fields.get(10)));
    }

    public List<String> implementedInterfaces(String className) {
        return decodedLines(executeForOutput(CommandLine.of(
                "jvmti", "class.interfaces", required(className, "className"))), 1);
    }

    public List<String> classLoaderClasses(String anchorClassName) {
        return decodedLines(executeForOutput(CommandLine.of("jvmti", "class.loader-classes",
                required(anchorClassName, "anchorClassName"))), 1);
    }

    public JvmtiMethodInfo methodInfo(String className, String methodName, String descriptor) {
        List<String> fields = TextWireCodec.decode(executeForOutput(CommandLine.of(
                "jvmti", "method.info", required(className, "className"),
                required(methodName, "methodName"), required(descriptor, "descriptor"))), 12);
        return new JvmtiMethodInfo(fields.get(0), fields.get(1), fields.get(2), fields.get(3),
                Integer.parseInt(fields.get(4)), Integer.parseInt(fields.get(5)),
                Integer.parseInt(fields.get(6)), Long.parseLong(fields.get(7)),
                Long.parseLong(fields.get(8)), Boolean.parseBoolean(fields.get(9)),
                Boolean.parseBoolean(fields.get(10)), Boolean.parseBoolean(fields.get(11)));
    }

    public byte[] methodBytecodes(String className, String methodName, String descriptor) {
        String encoded = executeForOutput(CommandLine.of("jvmti", "method.bytecodes",
                required(className, "className"), required(methodName, "methodName"),
                required(descriptor, "descriptor")));
        try {
            return Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Target returned invalid method bytecodes", exception);
        }
    }

    public List<JvmtiLineNumber> lineNumberTable(
            String className, String methodName, String descriptor) {
        List<JvmtiLineNumber> result = new ArrayList<JvmtiLineNumber>();
        for (String row : lines(executeForOutput(CommandLine.of("jvmti", "method.lines",
                required(className, "className"), required(methodName, "methodName"),
                required(descriptor, "descriptor"))))) {
            List<String> fields = TextWireCodec.decode(row, 2);
            result.add(new JvmtiLineNumber(Long.parseLong(fields.get(0)), Integer.parseInt(fields.get(1))));
        }
        return Collections.unmodifiableList(result);
    }

    public JvmtiFieldInfo fieldInfo(String className, String fieldName, String descriptor) {
        List<String> fields = TextWireCodec.decode(executeForOutput(CommandLine.of(
                "jvmti", "field.info", required(className, "className"),
                required(fieldName, "fieldName"), required(descriptor, "descriptor"))), 7);
        return new JvmtiFieldInfo(fields.get(0), fields.get(1), fields.get(2), fields.get(3),
                Integer.parseInt(fields.get(4)), Boolean.parseBoolean(fields.get(5)), fields.get(6));
    }

    public String sourceDebugExtension(String className) {
        return executeForOutput(CommandLine.of(
                "jvmti", "class.source-debug", required(className, "className")));
    }

    public byte[] constantPool(String className) {
        String encoded = executeForOutput(CommandLine.of(
                "jvmti", "class.constant-pool", required(className, "className")));
        try {
            return Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Target returned an invalid constant pool", exception);
        }
    }

    public JvmtiTimerInfo timerInfo() {
        List<String> fields = TextWireCodec.decode(
                executeForOutput(CommandLine.of("jvmti", "timer-info")), 4);
        return new JvmtiTimerInfo(Long.parseLong(fields.get(0)), Boolean.parseBoolean(fields.get(1)),
                Boolean.parseBoolean(fields.get(2)), Integer.parseInt(fields.get(3)));
    }

    public long currentThreadCpuTime() {
        return Long.parseLong(executeForOutput(
                CommandLine.of("jvmti", "current-thread.cpu-time")));
    }

    public void generateEvents(JvmtiEventType eventType) {
        if (eventType == null) throw new IllegalArgumentException("eventType must not be null");
        executeForOutput(CommandLine.of("jvmti", "events.generate", eventType.wireName()));
    }

    public void setVerboseFlag(String flagName, boolean enabled) {
        executeForOutput(CommandLine.of("jvmti", "verbose", required(flagName, "flagName"),
                enabled ? "enable" : "disable"));
    }

    private List<JvmtiCapabilityStatus> changeCapabilities(
            String operation, JvmtiCapability... capabilities) {
        if (capabilities == null || capabilities.length == 0) {
            throw new IllegalArgumentException("At least one capability is required");
        }
        String[] command = new String[capabilities.length + 1];
        command[0] = operation;
        for (int index = 0; index < capabilities.length; index++) {
            if (capabilities[index] == null) {
                throw new IllegalArgumentException("Capability at index " + index + " is null");
            }
            command[index + 1] = capabilities[index].wireName();
        }
        return parseCapabilityStatuses(executeForOutput(CommandLine.of("jvmti", command)));
    }

    private static List<JvmtiCapabilityStatus> parseCapabilityStatuses(String output) {
        List<JvmtiCapabilityStatus> result = new ArrayList<JvmtiCapabilityStatus>();
        for (String row : lines(output)) {
            List<String> fields = TextWireCodec.decode(row, 3);
            result.add(new JvmtiCapabilityStatus(JvmtiCapability.parse(fields.get(0)),
                    Boolean.parseBoolean(fields.get(1)), Boolean.parseBoolean(fields.get(2))));
        }
        return Collections.unmodifiableList(result);
    }

    public void retransformClass(String className) {
        executeForOutput(CommandLine.of("jvmti", "retransform", className));
        restoreBreakpointsAfterClassBytes(className);
    }

    public void redefineClass(String className, byte[] classBytes) {
        redefineClass(className, classBytes,
                Collections.<String, Map<Long, Long>>emptyMap());
    }

    /**
     * Redefines a class and relocates managed breakpoints to the emitted bytecode indexes.
     * Relocation keys are {@code methodName + '\0' + descriptor}; absent methods retain their BCI.
     */
    public void redefineClass(String className, byte[] classBytes,
            Map<String, Map<Long, Long>> relocations) {
        requireClassBytes(classBytes);
        final String normalized = normalizeClassName(className);
        final List<BreakpointRegistration> breakpoints = breakpointsForClass(normalized);
        detachBreakpoints(breakpoints);
        try {
            executeForOutput(CommandLine.of("jvmti", "redefine", className,
                    Base64.getUrlEncoder().withoutPadding().encodeToString(classBytes)));
        } catch (RuntimeException failure) {
            try { restoreBreakpointRegistrations(breakpoints, Collections.<String, Map<Long, Long>>emptyMap()); }
            catch (RuntimeException restoreFailure) { failure.addSuppressed(restoreFailure); }
            throw failure;
        }
        restoreBreakpointRegistrations(breakpoints, relocations == null
                ? Collections.<String, Map<Long, Long>>emptyMap() : relocations);
    }

    /**
     * Installs or clears a persistent BCI breakpoint. {@code location} is a bytecode index, not
     * a source line or instruction ordinal. A symbolic registration can be retained until an
     * unloaded class is prepared; native and abstract methods require an event breakpoint.
     */
    public void setBreakpoint(String className, String methodName, String descriptor,
            long location, boolean enabled) {
        setBreakpoint(className, methodName, descriptor, location,
                JvmBreakpointCondition.any(), enabled);
    }

    /**
     * Installs or clears a persistent conditional BCI breakpoint. Conditions are evaluated before
     * pausing. Receiver conditions use object identity and require the receiver handle to remain
     * alive; caller components accept {@code *}/{@code ?} patterns. Prefer
     * {@link #clearBreakpoint(JvmBreakpointInfo)} for removal.
     */
    public void setBreakpoint(String className, String methodName, String descriptor,
            long location, JvmBreakpointCondition condition, boolean enabled) {
        if (condition == null) condition = JvmBreakpointCondition.any();
        BreakpointRegistration registration = new BreakpointRegistration(
                normalizeClassName(className), methodName, descriptor, location, condition);
        executeForOutput(CommandLine.of("jvmti", "breakpoint", enabled ? "set" : "clear",
                className, methodName, descriptor, Long.toString(location), registration.id(),
                condition.receiver() == null ? "0" : objectId(condition.receiver()),
                optional(condition.callerClass()), optional(condition.callerMethod()),
                optional(condition.callerDescriptor())));
        synchronized (managedBreakpoints) {
            if (enabled) managedBreakpoints.put(registration.id(), registration);
            else managedBreakpoints.remove(registration.id());
        }
    }

    public List<JvmBreakpointInfo> managedBreakpoints() {
        List<JvmBreakpointInfo> result = new ArrayList<JvmBreakpointInfo>();
        synchronized (managedBreakpoints) {
            for (BreakpointRegistration value : managedBreakpoints.values()) {
                result.add(new JvmBreakpointInfo(value.className, value.methodName,
                        value.descriptor, value.location, value.id(),
                        value.condition.receiverId(), value.condition.summary()));
            }
        }
        return Collections.unmodifiableList(result);
    }

    public void clearManagedBreakpoints() {
        List<JvmBreakpointInfo> snapshot = managedBreakpoints();
        for (JvmBreakpointInfo breakpoint : snapshot) {
            clearBreakpointRegistration(breakpoint);
        }
    }

    public void clearBreakpoint(JvmBreakpointInfo breakpoint) {
        if (breakpoint == null) throw new IllegalArgumentException("breakpoint must not be null");
        clearBreakpointRegistration(breakpoint);
    }

    private void clearBreakpointRegistration(JvmBreakpointInfo breakpoint) {
        executeForOutput(CommandLine.of("jvmti", "breakpoint", "clear",
                breakpoint.className(), breakpoint.methodName(), breakpoint.descriptor(),
                Long.toString(breakpoint.location()), breakpoint.registrationId(),
                "0", "-", "-", "-"));
        synchronized (managedBreakpoints) { managedBreakpoints.remove(breakpoint.id()); }
    }

    private List<BreakpointRegistration> breakpointsForClass(String normalizedClassName) {
        List<BreakpointRegistration> result = new ArrayList<BreakpointRegistration>();
        synchronized (managedBreakpoints) {
            for (BreakpointRegistration registration : managedBreakpoints.values()) {
                if (registration.className.equals(normalizedClassName)) result.add(registration);
            }
        }
        return result;
    }

    private void detachBreakpoints(List<BreakpointRegistration> registrations) {
        List<BreakpointRegistration> detached = new ArrayList<BreakpointRegistration>();
        try {
            for (BreakpointRegistration registration : registrations) {
                try {
                    executeForOutput(CommandLine.of("jvmti", "breakpoint", "clear",
                            registration.className, registration.methodName,
                            registration.descriptor, Long.toString(registration.location),
                            registration.id(), "0", "-", "-", "-"));
                } catch (RuntimeException failure) {
                    // A stop may already have invalidated an old agent's native breakpoint.
                    if (!isBreakpointNotFound(failure)) throw failure;
                }
                detached.add(registration);
                synchronized (managedBreakpoints) {
                    managedBreakpoints.remove(registration.id());
                }
            }
        } catch (RuntimeException failure) {
            try { restoreBreakpointRegistrations(detached,
                    Collections.<String, Map<Long, Long>>emptyMap()); }
            catch (RuntimeException restoreFailure) { failure.addSuppressed(restoreFailure); }
            throw failure;
        }
    }

    private void restoreBreakpointRegistrations(List<BreakpointRegistration> registrations,
            Map<String, Map<Long, Long>> relocations) {
        RuntimeException firstFailure = null;
        for (BreakpointRegistration previous : registrations) {
            Map<Long, Long> methodRelocations = relocations.get(
                    previous.methodName + '\u0000' + previous.descriptor);
            Long relocated = methodRelocations == null ? null
                    : methodRelocations.get(Long.valueOf(previous.location));
            long location = relocated == null ? previous.location : relocated.longValue();
            BreakpointRegistration registration = new BreakpointRegistration(
                    previous.className, previous.methodName, previous.descriptor,
                    location, previous.condition);
            try {
                installBreakpointRegistration(registration);
            } catch (RuntimeException failure) {
                if (firstFailure == null) firstFailure = failure;
                else firstFailure.addSuppressed(failure);
            }
        }
        if (firstFailure != null) throw new IllegalStateException(
                "Class was redefined, but one or more managed breakpoints could not be restored",
                firstFailure);
    }

    private void installBreakpointRegistration(BreakpointRegistration registration) {
        JvmBreakpointCondition condition = registration.condition;
        executeForOutput(CommandLine.of("jvmti", "breakpoint", "set",
                registration.className, registration.methodName, registration.descriptor,
                Long.toString(registration.location), registration.id(),
                condition.receiver() == null ? "0" : objectId(condition.receiver()),
                optional(condition.callerClass()), optional(condition.callerMethod()),
                optional(condition.callerDescriptor())));
        synchronized (managedBreakpoints) {
            managedBreakpoints.put(registration.id(), registration);
        }
    }

    private void restoreBreakpointsAfterClassBytes(String className) {
        String normalizedClassName = normalizeClassName(className);
        List<BreakpointRegistration> snapshot = new ArrayList<BreakpointRegistration>();
        synchronized (managedBreakpoints) {
            for (BreakpointRegistration registration : managedBreakpoints.values()) {
                if (registration.className.equals(normalizedClassName)) snapshot.add(registration);
            }
        }
        for (BreakpointRegistration registration : snapshot) {
            if (!registration.condition.isUnconditional()) continue;
            try {
                executeForOutput(CommandLine.of("jvmti", "breakpoint", "set",
                        registration.className, registration.methodName,
                        registration.descriptor, Long.toString(registration.location),
                        registration.id(), "0", "-", "-", "-"));
            } catch (RuntimeException failure) {
                // New agents restore natively before returning class bytes. Old agents
                // require this compatibility reinstall. DUPLICATE means either route
                // already achieved the desired persistent-breakpoint state.
                if (!isDuplicateBreakpoint(failure)) throw failure;
            }
        }
    }

    static boolean isDuplicateBreakpoint(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.contains("SetBreakpoint")
                    && message.contains("JVMTI_ERROR_DUPLICATE")) return true;
        }
        return false;
    }

    private static boolean isBreakpointNotFound(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.contains("ClearBreakpoint")
                    && message.contains("JVMTI_ERROR_NOT_FOUND")) return true;
        }
        return false;
    }

    private static String normalizeClassName(String className) {
        return className == null ? "" : className.replace('/', '.');
    }

    private static final class BreakpointRegistration {
        private final String className;
        private final String methodName;
        private final String descriptor;
        private final long location;
        private final JvmBreakpointCondition condition;

        private BreakpointRegistration(String className, String methodName,
                String descriptor, long location, JvmBreakpointCondition condition) {
            this.className = className;
            this.methodName = methodName;
            this.descriptor = descriptor;
            this.location = location;
            this.condition = condition;
        }

        private String id() {
            String raw = className + '|' + methodName + '|' + descriptor + '|' + location
                    + '|' + condition.receiverId() + '|' + condition.callerClass()
                    + '|' + condition.callerMethod() + '|' + condition.callerDescriptor();
            return "bp-" + Base64.getUrlEncoder().withoutPadding().encodeToString(
                    raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    public void configureDebugger(boolean enabled) {
        executeForOutput(CommandLine.of("jvmti", enabled ? "debug.enable" : "debug.disable"));
    }

    /**
     * Installs a method-entry, method-exit, or exception event breakpoint. Event breakpoints do
     * not require a Code attribute, so they can stop native and abstract methods. Close object
     * values returned by the resulting debugger state when they are no longer needed.
     */
    public JvmEventBreakpointInfo setEventBreakpoint(JvmEventBreakpointSpec spec) {
        if (spec == null) throw new IllegalArgumentException("spec must not be null");
        String raw = spec.kind().wireName() + '|' + spec.classPattern() + '|'
                + spec.methodPattern() + '|' + spec.descriptorPattern() + '|'
                + spec.includeSubtypes();
        String id = "event-" + Base64.getUrlEncoder().withoutPadding().encodeToString(
                raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        executeForOutput(CommandLine.of("jvmti", "debug.event-breakpoint", "set",
                spec.kind().wireName(), spec.classPattern(), optional(spec.methodPattern()),
                optional(spec.descriptorPattern()), Boolean.toString(spec.includeSubtypes()), id));
        JvmEventBreakpointInfo info = new JvmEventBreakpointInfo(id, spec);
        synchronized (managedEventBreakpoints) { managedEventBreakpoints.put(id, info); }
        return info;
    }

    public void clearEventBreakpoint(JvmEventBreakpointInfo breakpoint) {
        if (breakpoint == null) throw new IllegalArgumentException("breakpoint must not be null");
        JvmEventBreakpointSpec spec = breakpoint.spec();
        executeForOutput(CommandLine.of("jvmti", "debug.event-breakpoint", "clear",
                spec.kind().wireName(), spec.classPattern(), optional(spec.methodPattern()),
                optional(spec.descriptorPattern()), Boolean.toString(spec.includeSubtypes()),
                breakpoint.id()));
        synchronized (managedEventBreakpoints) { managedEventBreakpoints.remove(breakpoint.id()); }
    }

    public List<JvmEventBreakpointInfo> managedEventBreakpoints() {
        synchronized (managedEventBreakpoints) {
            return Collections.unmodifiableList(new ArrayList<JvmEventBreakpointInfo>(
                    managedEventBreakpoints.values()));
        }
    }

    public void clearManagedEventBreakpoints() {
        for (JvmEventBreakpointInfo breakpoint : managedEventBreakpoints()) {
            clearEventBreakpoint(breakpoint);
        }
    }

    /** Returns the current/last debugger state. The returned state owns remote handles. */
    public JvmDebuggerState debuggerState() {
        return decodeDebuggerState(executeForOutput(CommandLine.of("jvmti", "debug.status")));
    }

    /** Returns all current/last debugger states. Close every returned state. */
    public List<JvmDebuggerState> debuggerStates() {
        String output = executeForOutput(CommandLine.of("jvmti", "debug.status-all"));
        if (output.isEmpty()) return Collections.emptyList();
        List<JvmDebuggerState> result = new ArrayList<JvmDebuggerState>();
        for (String row : output.split("\\r?\\n")) result.add(decodeDebuggerState(row));
        return Collections.unmodifiableList(result);
    }

    private JvmDebuggerState decodeDebuggerState(String row) {
        List<String> fields;
        try { fields = TextWireCodec.decode(row, 12); }
        catch (IllegalArgumentException legacy) {
            fields = new ArrayList<String>(TextWireCodec.decode(row, 10));
            fields.add("");
            fields.add("");
        }
        RemoteObject thread = fields.get(0).isEmpty()
                ? null : object(RemoteObjectDescriptor.decode(fields.get(0)));
        RemoteObject returnValue = fields.get(10).isEmpty()
                ? null : object(RemoteObjectDescriptor.decode(fields.get(10)));
        return new JvmDebuggerState(thread, Boolean.parseBoolean(fields.get(1)),
                Boolean.parseBoolean(fields.get(2)), fields.get(3), fields.get(4), fields.get(5),
                fields.get(6), Long.parseLong(fields.get(7)), Integer.parseInt(fields.get(8)),
                Long.parseLong(fields.get(9)), returnValue, fields.get(11));
    }

    public void continueExecution() {
        executeForOutput(CommandLine.of("jvmti", "debug.continue"));
    }

    public void continueExecution(RemoteObject thread) {
        if (thread == null) throw new IllegalArgumentException("thread must not be null");
        executeForOutput(CommandLine.of("jvmti", "debug.continue-thread", objectId(thread)));
    }

    public void pauseExecution(RemoteObject thread) {
        pauseExecution(thread, "manual_pause");
    }

    public void pauseExecution(RemoteObject thread, String reason) {
        if (thread == null) throw new IllegalArgumentException("thread must not be null");
        if (!"manual_pause".equals(reason) && !"live_sample".equals(reason)) {
            throw new IllegalArgumentException("Unsupported debugger pause reason: " + reason);
        }
        executeForOutput(CommandLine.of("jvmti", "debug.pause-thread", objectId(thread), reason));
    }

    public void continueAllExecutions() {
        executeForOutput(CommandLine.of("jvmti", "debug.continue-all"));
    }

    public void stepInstruction() {
        executeForOutput(CommandLine.of("jvmti", "debug.step"));
    }

    public void stepInstruction(RemoteObject thread) {
        if (thread == null) throw new IllegalArgumentException("thread must not be null");
        executeForOutput(CommandLine.of("jvmti", "debug.step-thread", objectId(thread)));
    }

    /** Continues until the selected frame returns and stops at the first caller bytecode. */
    public void stepOut() {
        executeForOutput(CommandLine.of("jvmti", "debug.step-out"));
    }

    public void stepOut(RemoteObject thread) {
        if (thread == null) throw new IllegalArgumentException("thread must not be null");
        executeForOutput(CommandLine.of("jvmti", "debug.step-out-thread", objectId(thread)));
    }

    public List<JvmDebuggerLocal> debuggerLocals(RemoteObject thread, int depth) {
        if (thread == null) throw new IllegalArgumentException("thread must not be null");
        if (depth < 0) throw new IllegalArgumentException("depth must not be negative");
        List<JvmDebuggerLocal> result = new ArrayList<JvmDebuggerLocal>();
        String output = executeForOutput(CommandLine.of("jvmti", "debug.locals", objectId(thread),
                Integer.toString(depth)));
        for (String row : lines(output)) {
            List<String> fields = TextWireCodec.decode(row, 8);
            RemoteObject value = object(RemoteObjectDescriptor.decode(fields.get(6)));
            result.add(new JvmDebuggerLocal(fields.get(0), fields.get(1), fields.get(2),
                    Integer.parseInt(fields.get(3)), Long.parseLong(fields.get(4)),
                    Long.parseLong(fields.get(5)), value, fields.get(7)));
        }
        return Collections.unmodifiableList(result);
    }

    public void setDebuggerLocal(RemoteObject thread, int depth, int slot,
            String descriptor, RemoteObject value) {
        if (thread == null || value == null) throw new IllegalArgumentException("thread and value must not be null");
        if (depth < 0 || slot < 0) throw new IllegalArgumentException("depth and slot must not be negative");
        if (descriptor == null || descriptor.isEmpty() || "?".equals(descriptor)) {
            throw new IllegalArgumentException("A concrete local descriptor is required");
        }
        executeForOutput(CommandLine.of("jvmti", "debug.set-local", objectId(thread),
                Integer.toString(depth), Integer.toString(slot), descriptor, objectId(value)));
    }

    /** Forces the current Java frame to return with the supplied target-JVM value. */
    public void forceEarlyReturn(RemoteObject thread, RemoteObject value) {
        if (thread == null || value == null) throw new IllegalArgumentException("thread and value must not be null");
        executeForOutput(CommandLine.of("jvmti", "debug.force-return",
                objectId(thread), objectId(value)));
    }

    /** Forces the current Java frame to return from a void method. */
    public void forceEarlyReturnVoid(RemoteObject thread) {
        if (thread == null) throw new IllegalArgumentException("thread must not be null");
        executeForOutput(CommandLine.of("jvmti", "debug.force-return-void", objectId(thread)));
    }

    /**
     * Installs or clears a read ({@code modification=false}) or write
     * ({@code modification=true}) watch for all receivers. Symbolic watches can remain pending
     * until an unloaded class is prepared.
     */
    public void setFieldWatch(String className, String fieldName, String descriptor,
            boolean modification, boolean enabled) {
        setFieldWatch(className, fieldName, descriptor, modification, null, enabled);
    }

    /**
     * Installs or clears a field watch. A non-null receiver limits an instance-field watch to
     * that exact object identity; pass null for all instances and for static fields. Keep a
     * receiver handle alive until the watch is cleared.
     */
    public void setFieldWatch(String className, String fieldName, String descriptor,
            boolean modification, RemoteObject receiver, boolean enabled) {
        String normalized = normalizeClassName(className);
        long receiverId = receiver == null ? 0L : receiver.remoteId();
        String raw = normalized + '|' + fieldName + '|' + descriptor + '|'
                + (modification ? "write" : "read") + '|' + receiverId;
        String registrationId = "watch-" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        JvmFieldWatchInfo registration = new JvmFieldWatchInfo(
                normalized, fieldName, descriptor, modification, registrationId, receiverId);
        executeForOutput(CommandLine.of("jvmti", "watch", modification ? "modification" : "access",
                enabled ? "set" : "clear", normalized, fieldName, descriptor, registrationId,
                receiver == null ? "0" : objectId(receiver)));
        synchronized (managedFieldWatches) {
            if (enabled) managedFieldWatches.put(registration.id(), registration);
            else managedFieldWatches.remove(registration.id());
        }
    }

    public List<JvmFieldWatchInfo> managedFieldWatches() {
        synchronized (managedFieldWatches) {
            return Collections.unmodifiableList(
                    new ArrayList<JvmFieldWatchInfo>(managedFieldWatches.values()));
        }
    }

    public void clearFieldWatch(JvmFieldWatchInfo watch) {
        if (watch == null) throw new IllegalArgumentException("watch must not be null");
        executeForOutput(CommandLine.of("jvmti", "watch",
                watch.modification() ? "modification" : "access", "clear",
                watch.className(), watch.fieldName(), watch.descriptor(),
                watch.registrationId(), "0"));
        synchronized (managedFieldWatches) { managedFieldWatches.remove(watch.id()); }
    }

    public void clearManagedFieldWatches() {
        List<JvmFieldWatchInfo> snapshot = managedFieldWatches();
        for (JvmFieldWatchInfo watch : snapshot) {
            clearFieldWatch(watch);
        }
    }

    /**
     * Installs or clears a target-side String allocation filter. Fast mode uses a lightweight
     * probe in {@code java.lang.String.<init>} methods and prefilters content before
     * entering native code; complete mode adds {@code VM_OBJECT_ALLOC}. A hit pauses the
     * allocating thread and exposes the String as {@link JvmDebuggerState#eventValue()}.
     */
    public void setStringAllocationHook(String registrationId,
            JvmStringAllocationSpec spec, boolean enabled) {
        if (registrationId == null || registrationId.isEmpty()) {
            throw new IllegalArgumentException("registrationId must not be empty");
        }
        if (spec == null) throw new IllegalArgumentException("spec must not be null");
        executeForOutput(CommandLine.of("jvmti", "string.alloc",
                enabled ? "set" : "clear", registrationId,
                spec.contentPattern(), spec.creatorClassPattern(),
                spec.creatorMethodPattern(), spec.creatorDescriptorPattern(),
                Boolean.toString(spec.caseSensitive()), spec.mode().name(),
                Long.toString(spec.maximumHits()), Integer.toString(spec.sampleEvery())));
    }

    public RemoteCodeDeployment deployClasses(String name, Map<String, byte[]> classes) {
        return deployClasses(name, classes, "", DefinitionMode.CHILD);
    }

    public RemoteCodeDeployment deployClasses(String name, Map<String, byte[]> classes,
            String anchorClass, DefinitionMode mode) {
        if (mode == null) throw new IllegalArgumentException("mode must not be null");
        String upload = upload(CodeBundleCodec.encodeBytes(classes));
        try {
            return deployment(executeForOutput(CommandLine.of("code", "deploy.upload", safeName(name),
                    mode.name(), optional(anchorClass), upload)));
        } catch (RuntimeException failure) {
            abortUpload(upload);
            throw failure;
        }
    }

    public RemoteCodeDeployment deploySources(String name, Path sourceFileOrDirectory,
            List<Path> classpath, List<String> compilerOptions, String anchorClass, DefinitionMode mode)
            throws IOException {
        Map<String, byte[]> classes = new JavaSourceCompiler().compile(
                sourceFileOrDirectory, classpath, compilerOptions);
        return deployClasses(name, classes, anchorClass, mode);
    }

    public RemoteCodeDeployment deploySources(String name, Path sourceFileOrDirectory) throws IOException {
        return deploySources(name, sourceFileOrDirectory, Collections.<Path>emptyList(),
                Collections.<String>emptyList(), "", DefinitionMode.CHILD);
    }

    public RemoteCodeDeployment deploySource(String name, String binaryClassName, String source,
            List<Path> classpath, List<String> compilerOptions, String anchorClass, DefinitionMode mode)
            throws IOException {
        Map<String, byte[]> classes = new JavaSourceCompiler().compileSource(
                binaryClassName, source, classpath, compilerOptions);
        return deployClasses(name, classes, anchorClass, mode);
    }

    public RemoteCodeDeployment deploySource(String name, String binaryClassName, String source)
            throws IOException {
        return deploySource(name, binaryClassName, source, Collections.<Path>emptyList(),
                Collections.<String>emptyList(), "", DefinitionMode.CHILD);
    }

    public RemoteCodeDeployment deployMethods(String name, String binaryClassName, String methodsSource,
            List<Path> classpath, List<String> compilerOptions, String anchorClass, DefinitionMode mode)
            throws IOException {
        Map<String, byte[]> classes = new JavaSourceCompiler().compileMethods(
                binaryClassName, methodsSource, classpath, compilerOptions);
        return deployClasses(name, classes, anchorClass, mode);
    }

    public RemoteCodeDeployment addJar(String name, Path jar, JarScope scope, String anchorClass)
            throws IOException {
        if (scope == null) throw new IllegalArgumentException("scope must not be null");
        Path absolute = jar.toAbsolutePath().normalize();
        byte[] bytes = Files.readAllBytes(absolute);
        String upload = upload(bytes);
        try {
            return deployment(executeForOutput(CommandLine.of("code", "jar.upload", safeName(name),
                    scope.name(), optional(anchorClass), upload)));
        } catch (RuntimeException failure) {
            abortUpload(upload);
            throw failure;
        }
    }

    public List<RemoteCodeDeployment> deployments() {
        List<RemoteCodeDeployment> result = new ArrayList<RemoteCodeDeployment>();
        for (String row : lines(executeForOutput(CommandLine.of("code", "deployments")))) {
            result.add(deployment(row));
        }
        return Collections.unmodifiableList(result);
    }

    RemoteObject execute(RemoteCodeDeployment deployment, String className, String methodName,
            String descriptor, RemoteObject receiver, RemoteObject... arguments) {
        if (deployment == null) throw new IllegalArgumentException("deployment must not be null");
        List<String> command = new ArrayList<String>();
        Collections.addAll(command, "execute", deployment.id(), className, methodName, descriptor,
                receiver == null ? "0" : objectId(receiver));
        if (arguments != null) {
            for (RemoteObject argument : arguments) command.add(objectId(argument));
        }
        RemoteObjectDescriptor result = RemoteObjectDescriptor.decode(executeForOutput(
                CommandLine.of("code", command.toArray(new String[0]))));
        return object(result);
    }

    RemoteJvmtiCallback registerCallback(RemoteCodeDeployment deployment, String handlerClass,
            String events, boolean synchronous) {
        String id = executeForOutput(CommandLine.of("code", "callback.register", deployment.id(),
                handlerClass, optional(events), synchronous ? "sync" : "async"));
        return new RemoteJvmtiCallback(this, id);
    }

    public boolean unregisterCallback(String callbackId) {
        return Boolean.parseBoolean(executeForOutput(
                CommandLine.of("code", "callback.unregister", callbackId)));
    }

    public boolean setCallbackEnabled(String callbackId, boolean enabled) {
        return Boolean.parseBoolean(executeForOutput(CommandLine.of("code",
                enabled ? "callback.enable" : "callback.disable", callbackId)));
    }

    public boolean resetCallback(String callbackId) {
        return Boolean.parseBoolean(executeForOutput(
                CommandLine.of("code", "callback.reset", callbackId)));
    }

    public List<JvmtiCallbackRegistration> callbacks() {
        List<JvmtiCallbackRegistration> result = new ArrayList<JvmtiCallbackRegistration>();
        for (String row : lines(executeForOutput(CommandLine.of("code", "callback.list")))) {
            List<String> fields = TextWireCodec.decode(row, 10);
            result.add(new JvmtiCallbackRegistration(fields.get(0), fields.get(1), fields.get(2), fields.get(3),
                    Boolean.parseBoolean(fields.get(4)), Long.parseLong(fields.get(5)),
                    Long.parseLong(fields.get(6)), fields.get(7), fields.get(8),
                    Long.parseLong(fields.get(9))));
        }
        return Collections.unmodifiableList(result);
    }

    public JvmtiCallbackStatistics callbackStatistics() {
        List<String> fields = TextWireCodec.decode(
                executeForOutput(CommandLine.of("code", "callback.stats")), 7);
        return new JvmtiCallbackStatistics(Integer.parseInt(fields.get(0)), Long.parseLong(fields.get(1)),
                Long.parseLong(fields.get(2)), fields.get(3), Long.parseLong(fields.get(4)),
                Long.parseLong(fields.get(5)), Long.parseLong(fields.get(6)));
    }

    public List<RemoteJvmtiThread> threads() {
        List<RemoteJvmtiThread> result = new ArrayList<RemoteJvmtiThread>();
        for (String row : lines(executeForOutput(CommandLine.of("jvmti", "threads")))) {
            List<String> fields = TextWireCodec.decode(row, 6);
            RemoteObject thread = object(RemoteObjectDescriptor.decode(fields.get(0)));
            result.add(new RemoteJvmtiThread(this, thread, Integer.parseInt(fields.get(1)),
                    fields.get(2), Integer.parseInt(fields.get(3)), Boolean.parseBoolean(fields.get(4)),
                    Boolean.parseBoolean(fields.get(5))));
        }
        return Collections.unmodifiableList(result);
    }

    public int threadState(RemoteObject thread) {
        return Integer.parseInt(executeForOutput(
                CommandLine.of("jvmti", "thread.state", objectId(thread))));
    }

    public JvmtiThreadInfo threadInfo(RemoteObject thread) {
        List<String> fields = TextWireCodec.decode(executeForOutput(
                CommandLine.of("jvmti", "thread.info", objectId(thread))), 6);
        return new JvmtiThreadInfo(fields.get(0), Integer.parseInt(fields.get(1)),
                Boolean.parseBoolean(fields.get(2)), fields.get(3), fields.get(4),
                Integer.parseInt(fields.get(5)));
    }

    public int frameCount(RemoteObject thread) {
        return Integer.parseInt(executeForOutput(
                CommandLine.of("jvmti", "thread.frame-count", objectId(thread))));
    }

    public long threadCpuTime(RemoteObject thread) {
        return Long.parseLong(executeForOutput(
                CommandLine.of("jvmti", "thread.cpu-time", objectId(thread))));
    }

    public List<RemoteObject> ownedMonitors(RemoteObject thread) {
        return remoteObjects(executeForOutput(
                CommandLine.of("jvmti", "thread.owned-monitors", objectId(thread))));
    }

    public RemoteObject currentContendedMonitor(RemoteObject thread) {
        return object(RemoteObjectDescriptor.decode(executeForOutput(
                CommandLine.of("jvmti", "thread.contended-monitor", objectId(thread)))));
    }

    public List<String> stackTrace(RemoteObject thread, int maxFrames) {
        if (maxFrames < 1) throw new IllegalArgumentException("maxFrames must be positive");
        List<String> result = new ArrayList<String>();
        for (String row : lines(executeForOutput(CommandLine.of(
                "jvmti", "thread.stack", objectId(thread), Integer.toString(maxFrames))))) {
            result.add(TextWireCodec.decode(row, 1).get(0));
        }
        return Collections.unmodifiableList(result);
    }

    public List<JvmStackFrame> stackFrames(RemoteObject thread, int maxFrames) {
        List<String> rows = stackTrace(thread, maxFrames);
        List<JvmStackFrame> result = new ArrayList<JvmStackFrame>(rows.size());
        for (int depth = 0; depth < rows.size(); depth++) {
            result.add(JvmStackFrame.parse(depth, rows.get(depth)));
        }
        return Collections.unmodifiableList(result);
    }

    public void suspendThread(RemoteObject thread) { threadControl("thread.suspend", thread); }
    public void resumeThread(RemoteObject thread) { threadControl("thread.resume", thread); }
    public void interruptThread(RemoteObject thread) { threadControl("thread.interrupt", thread); }

    public void notifyFramePop(RemoteObject thread, int depth) {
        if (depth < 0) throw new IllegalArgumentException("depth must not be negative");
        executeForOutput(CommandLine.of("jvmti", "thread.frame-pop", objectId(thread), Integer.toString(depth)));
    }

    public long objectSize(RemoteObject object) {
        return Long.parseLong(executeForOutput(CommandLine.of("jvmti", "object.size", objectId(object))));
    }

    public int objectHashCode(RemoteObject object) {
        return Integer.parseInt(executeForOutput(
                CommandLine.of("jvmti", "object.hash", objectId(object))));
    }

    public JvmtiMonitorUsage objectMonitorUsage(RemoteObject object) {
        List<String> fields = TextWireCodec.decode(executeForOutput(
                CommandLine.of("jvmti", "object.monitor-usage", objectId(object))), 4);
        return new JvmtiMonitorUsage(fields.get(0), Integer.parseInt(fields.get(1)),
                Integer.parseInt(fields.get(2)), Integer.parseInt(fields.get(3)));
    }

    public long getTag(RemoteObject object) {
        return Long.parseLong(executeForOutput(CommandLine.of("jvmti", "tag.get", objectId(object))));
    }

    public void setTag(RemoteObject object, long tag) {
        executeForOutput(CommandLine.of("jvmti", "tag.set", objectId(object), Long.toString(tag)));
    }

    public List<RemoteObject> objectsWithTag(long tag) {
        return remoteObjects(executeForOutput(
                CommandLine.of("jvmti", "tag.objects", Long.toString(tag))));
    }

    public void forceGarbageCollection() {
        executeForOutput(CommandLine.of("jvmti", "gc"));
    }

    public List<String> systemProperties() {
        List<String> result = new ArrayList<String>();
        for (String row : lines(executeForOutput(CommandLine.of("jvmti", "properties")))) {
            result.add(TextWireCodec.decode(row, 1).get(0));
        }
        return Collections.unmodifiableList(result);
    }

    boolean closeDeployment(String id) {
        return Boolean.parseBoolean(executeForOutput(CommandLine.of("code", "close", id)));
    }

    private void threadControl(String operation, RemoteObject thread) {
        executeForOutput(CommandLine.of("jvmti", operation, objectId(thread)));
    }

    private String upload(byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("Upload must not be empty");
        String id = executeForOutput(CommandLine.of("code", "upload.begin",
                Integer.toString(bytes.length), sha256(bytes)));
        try {
            int chunk = 0;
            for (int offset = 0; offset < bytes.length; offset += UPLOAD_CHUNK_BYTES) {
                int length = Math.min(UPLOAD_CHUNK_BYTES, bytes.length - offset);
                byte[] part = new byte[length];
                System.arraycopy(bytes, offset, part, 0, length);
                executeForOutput(CommandLine.of("code", "upload.chunk", id, Integer.toString(chunk++),
                        Base64.getUrlEncoder().withoutPadding().encodeToString(part)));
            }
            return id;
        } catch (RuntimeException failure) {
            abortUpload(id);
            throw failure;
        }
    }

    private void abortUpload(String id) {
        try { executeForOutput(CommandLine.of("code", "upload.abort", id)); }
        catch (RuntimeException ignored) { }
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private RemoteCodeDeployment deployment(String encoded) {
        List<String> fields = TextWireCodec.decode(encoded, 6);
        return new RemoteCodeDeployment(this, fields.get(0), fields.get(1), fields.get(2),
                Integer.parseInt(fields.get(3)), fields.get(4), fields.get(5));
    }

    private RemoteObject object(RemoteObjectDescriptor descriptor) {
        return new RemoteObject(server(), descriptor.id(), server().javaVM().jniEnv(),
                descriptor.className(), descriptor.nullValue(), descriptor.displayValue());
    }

    private List<RemoteObject> remoteObjects(String output) {
        List<RemoteObject> result = new ArrayList<RemoteObject>();
        for (String row : lines(output)) result.add(object(RemoteObjectDescriptor.decode(row)));
        return Collections.unmodifiableList(result);
    }

    private String objectId(RemoteObject object) {
        if (object == null) throw new IllegalArgumentException("Remote object must not be null");
        if (object.server() != server()) throw new IllegalArgumentException("Remote object belongs to another session");
        if (object.isReleased()) throw new IllegalStateException("Remote object is already released");
        return Long.toString(object.remoteId());
    }

    private static String optional(String value) {
        return value == null || value.trim().isEmpty() ? "-" : value.trim();
    }

    private static String safeName(String value) {
        return value == null || value.trim().isEmpty() ? "deployment" : value.trim();
    }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }

    private static List<String> lines(String output) {
        if (output == null || output.isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<String>();
        Collections.addAll(result, output.split("\\r?\\n"));
        return result;
    }

    private static List<String> decodedLines(String output, int fieldCount) {
        List<String> result = new ArrayList<String>();
        for (String row : lines(output)) result.add(TextWireCodec.decode(row, fieldCount).get(0));
        return Collections.unmodifiableList(result);
    }

    private static void requireClassBytes(byte[] bytes) {
        if (bytes == null || bytes.length < 4 || bytes[0] != (byte) 0xCA || bytes[1] != (byte) 0xFE
                || bytes[2] != (byte) 0xBA || bytes[3] != (byte) 0xBE) {
            throw new IllegalArgumentException("Invalid JVM class bytes");
        }
    }
}
