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
import nhcm.jvmrtdp.api.jvmti.JvmtiEventType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Controller-side facade for JVMTI and target-JVM Java code deployment. */
public class RemoteJVMTIEnv extends RemoteHandle {
    private static final int UPLOAD_CHUNK_BYTES = 512 * 1024;
    public enum DefinitionMode { CHILD, SAME_LOADER }
    public enum JarScope { CHILD, SYSTEM, BOOTSTRAP }

    public RemoteJVMTIEnv(ServerHandle server, long remoteId) {
        super(server, remoteId);
    }

    public byte[] getClassBytes(String className) {
        String encoded = executeForOutput(CommandLine.of("jvmti", "bytes", className));
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
    }

    public void redefineClass(String className, byte[] classBytes) {
        requireClassBytes(classBytes);
        executeForOutput(CommandLine.of("jvmti", "redefine", className,
                Base64.getUrlEncoder().withoutPadding().encodeToString(classBytes)));
    }

    public void setBreakpoint(String className, String methodName, String descriptor,
            long location, boolean enabled) {
        executeForOutput(CommandLine.of("jvmti", "breakpoint", enabled ? "set" : "clear",
                className, methodName, descriptor, Long.toString(location)));
    }

    public void setFieldWatch(String className, String fieldName, String descriptor,
            boolean modification, boolean enabled) {
        executeForOutput(CommandLine.of("jvmti", "watch", modification ? "modification" : "access",
                enabled ? "set" : "clear", className, fieldName, descriptor));
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

    public List<JvmtiCallbackRegistration> callbacks() {
        List<JvmtiCallbackRegistration> result = new ArrayList<JvmtiCallbackRegistration>();
        for (String row : lines(executeForOutput(CommandLine.of("code", "callback.list")))) {
            List<String> fields = TextWireCodec.decode(row, 7);
            result.add(new JvmtiCallbackRegistration(fields.get(0), fields.get(1), fields.get(2), fields.get(3),
                    Long.parseLong(fields.get(4)), Long.parseLong(fields.get(5)), fields.get(6)));
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
            List<String> fields = TextWireCodec.decode(row, 2);
            RemoteObject thread = object(RemoteObjectDescriptor.decode(fields.get(0)));
            result.add(new RemoteJvmtiThread(this, thread, Integer.parseInt(fields.get(1))));
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
