package nhcm.jvmrtdp.agent;

import nhcm.jvmrtdp.tools.DLLSupport;
import nhcm.jvmrtdp.api.jvmti.JvmtiCapability;
import nhcm.jvmrtdp.api.jvmti.JvmtiCapabilityStatus;
import nhcm.jvmrtdp.api.jvmti.JvmtiLocationFormat;
import nhcm.jvmrtdp.api.jvmti.JvmtiPhase;
import nhcm.jvmrtdp.agent.nativebridge.NativeJniBridge;
import nhcm.jvmrtdp.agent.nativebridge.NativeJvmtiBridge;
import nhcm.jvmrtdp.agent.nativebridge.NativeRuntimeBridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NativeAgent {
    public static final String BINDING_CLASS = "nhcm/jvmrtdp/agent/NativeAgent";

    private static final RuntimeInfo RUNTIME_INFO = initialize();

    private NativeAgent() {
    }

    public static RuntimeInfo runtimeInfo() {
        return RUNTIME_INFO;
    }

    public static byte[] getClassBytes(String className) {
        requireAvailable();
        return NativeJvmtiBridge.getClassBytes(className);
    }

    public static String readStaticFields(String className) {
        requireAvailable();
        return NativeJniBridge.readStaticFields(className);
    }

    public static String readStaticField(String className, String fieldName) {
        requireAvailable();
        return NativeJniBridge.readStaticField(className, fieldName);
    }

    public static String callStaticMethod(
            String className, String methodName, String descriptor, String[] arguments) {
        requireAvailable();
        return NativeJniBridge.callStaticMethod(className, methodName, descriptor, arguments);
    }

    public static Class<?> findLoadedClass(String className) {
        requireAvailable();
        return NativeJniBridge.findLoadedClass(className);
    }

    public static String[] listLoadedClassNames() {
        requireAvailable();
        return NativeJniBridge.listLoadedClassNames();
    }

    public static Class<?>[] listLoadedClasses() {
        requireAvailable();
        return NativeJniBridge.listLoadedClasses();
    }

    public static Class<?> defineClass(String className, byte[] classBytes, ClassLoader loader) {
        requireAvailable();
        if (className == null || className.trim().isEmpty()) {
            throw new IllegalArgumentException("className must not be empty");
        }
        if (classBytes == null || classBytes.length == 0) {
            throw new IllegalArgumentException("classBytes must not be empty");
        }
        return NativeJniBridge.defineClass(className, classBytes, loader);
    }

    public static void addToBootstrapClassLoaderSearch(String jarPath) {
        requireAvailable();
        NativeJvmtiBridge.addToClassLoaderSearch(jarPath, true);
    }

    public static void addToSystemClassLoaderSearch(String jarPath) {
        requireAvailable();
        NativeJvmtiBridge.addToClassLoaderSearch(jarPath, false);
    }

    public static void setEventNotification(String eventName, boolean enabled) {
        requireAvailable();
        NativeJvmtiBridge.setEventNotification(eventName, enabled);
    }

    public static void setBreakpoint(Class<?> type, String methodName, String descriptor,
            long location, boolean enabled) {
        if (type == null) throw new IllegalArgumentException("type must not be null");
        setBreakpoint(type, methodName, descriptor, location, enabled,
                type.getName() + '|' + methodName + '|' + descriptor + '|' + location,
                null, "", "", "");
    }

    public static void setBreakpoint(Class<?> type, String methodName, String descriptor,
            long location, boolean enabled, String registrationId, Object receiver,
            String callerClass, String callerMethod, String callerDescriptor) {
        requireAvailable();
        if (type == null) throw new IllegalArgumentException("type must not be null");
        if (methodName == null || methodName.isEmpty()) throw new IllegalArgumentException("methodName must not be empty");
        if (descriptor == null || descriptor.isEmpty()) throw new IllegalArgumentException("descriptor must not be empty");
        if (registrationId == null || registrationId.isEmpty()) {
            throw new IllegalArgumentException("registrationId must not be empty");
        }
        NativeJvmtiBridge.setBreakpoint(type, methodName, descriptor, location, enabled,
                registrationId, receiver, callerClass == null ? "" : callerClass,
                callerMethod == null ? "" : callerMethod,
                callerDescriptor == null ? "" : callerDescriptor);
    }

    public static void configureDebugger(boolean enabled) {
        requireAvailable();
        NativeJvmtiBridge.debuggerConfigure(enabled);
    }

    public static Object[] debuggerSnapshot() {
        requireAvailable();
        return NativeJvmtiBridge.debuggerSnapshot();
    }

    public static Object[][] debuggerSnapshots() {
        requireAvailable();
        return NativeJvmtiBridge.debuggerSnapshots();
    }

    public static void resumeDebugger(boolean singleStep) {
        requireAvailable();
        NativeJvmtiBridge.debuggerResume(singleStep ? 1 : 0);
    }

    public static void resumeDebugger(Thread thread, boolean singleStep) {
        requireAvailable();
        if (thread == null && singleStep) throw new IllegalArgumentException("Cannot single-step all threads");
        NativeJvmtiBridge.debuggerResumeThread(thread, singleStep ? 1 : 0);
    }

    public static void pauseDebugger(Thread thread) {
        pauseDebugger(thread, "manual_pause");
    }

    public static void pauseDebugger(Thread thread, String reason) {
        requireAvailable();
        if (thread == null) throw new IllegalArgumentException("thread must not be null");
        if (!"manual_pause".equals(reason) && !"live_sample".equals(reason)) {
            throw new IllegalArgumentException("Unsupported debugger pause reason: " + reason);
        }
        NativeJvmtiBridge.debuggerPauseThread(thread, reason);
    }

    public static Object[][] debuggerLocals(Thread thread, int depth) {
        requireAvailable();
        if (thread == null) throw new IllegalArgumentException("thread must not be null");
        if (depth < 0) throw new IllegalArgumentException("depth must not be negative");
        return NativeJvmtiBridge.debuggerLocals(thread, depth);
    }

    public static void setDebuggerLocal(Thread thread, int depth, int slot,
            String descriptor, Object value) {
        requireAvailable();
        if (thread == null) throw new IllegalArgumentException("thread must not be null");
        if (depth < 0 || slot < 0) throw new IllegalArgumentException("depth and slot must not be negative");
        if (descriptor == null || descriptor.isEmpty()) {
            throw new IllegalArgumentException("descriptor must not be empty");
        }
        NativeJvmtiBridge.debuggerSetLocal(thread, depth, slot, descriptor, value);
    }

    public static void setFieldWatch(Class<?> type, String fieldName, String descriptor,
            boolean modification, boolean enabled) {
        if (type == null) throw new IllegalArgumentException("type must not be null");
        setFieldWatch(type, fieldName, descriptor, modification, enabled,
                type.getName() + '|' + fieldName + '|' + descriptor + '|'
                        + (modification ? "write" : "read"), null);
    }

    public static void setFieldWatch(Class<?> type, String fieldName, String descriptor,
            boolean modification, boolean enabled, String registrationId, Object receiver) {
        requireAvailable();
        if (type == null) throw new IllegalArgumentException("type must not be null");
        if (fieldName == null || fieldName.isEmpty()) throw new IllegalArgumentException("fieldName must not be empty");
        if (descriptor == null || descriptor.isEmpty()) throw new IllegalArgumentException("descriptor must not be empty");
        if (registrationId == null || registrationId.isEmpty()) {
            throw new IllegalArgumentException("registrationId must not be empty");
        }
        NativeJvmtiBridge.setFieldWatch(type, fieldName, descriptor, modification, enabled,
                registrationId, receiver);
    }

    public static void notifyFramePop(Thread thread, int depth) {
        requireAvailable();
        if (thread == null) throw new IllegalArgumentException("thread must not be null");
        if (depth < 0) throw new IllegalArgumentException("depth must not be negative");
        NativeJvmtiBridge.notifyFramePop(thread, depth);
    }

    /** queued, dropped and currently pending native events, in that order. */
    public static long[] eventQueueStatistics() {
        requireAvailable();
        return NativeJvmtiBridge.eventQueueStatistics();
    }

    public static void retransformClass(Class<?> type) {
        requireAvailable();
        NativeJvmtiBridge.retransformClass(type);
    }

    public static void redefineClass(Class<?> type, byte[] classBytes) {
        requireAvailable();
        NativeJvmtiBridge.redefineClass(type, classBytes);
    }

    public static String capabilities() {
        requireAvailable();
        return NativeJvmtiBridge.capabilities();
    }

    /** Complete enabled/potential state for every JVMTI 1.2 capability bit. */
    public static List<JvmtiCapabilityStatus> capabilityStatuses() {
        requireAvailable();
        List<JvmtiCapabilityStatus> result = new ArrayList<JvmtiCapabilityStatus>();
        for (String row : NativeJvmtiBridge.capabilityStatuses()) {
            String[] fields = row.split("\\|", -1);
            if (fields.length != 3) throw new IllegalStateException("Invalid native capability row: " + row);
            result.add(new JvmtiCapabilityStatus(JvmtiCapability.parse(fields[0]),
                    "1".equals(fields[1]), "1".equals(fields[2])));
        }
        return Collections.unmodifiableList(result);
    }

    public static List<JvmtiCapabilityStatus> addCapabilities(JvmtiCapability... capabilities) {
        return changeCapabilities(true, capabilities);
    }

    public static List<JvmtiCapabilityStatus> relinquishCapabilities(JvmtiCapability... capabilities) {
        return changeCapabilities(false, capabilities);
    }

    private static List<JvmtiCapabilityStatus> changeCapabilities(
            boolean add, JvmtiCapability... capabilities) {
        requireAvailable();
        if (capabilities == null || capabilities.length == 0) {
            throw new IllegalArgumentException("At least one capability is required");
        }
        String[] names = new String[capabilities.length];
        for (int index = 0; index < capabilities.length; index++) {
            if (capabilities[index] == null) {
                throw new IllegalArgumentException("Capability at index " + index + " is null");
            }
            names[index] = capabilities[index].wireName();
        }
        NativeJvmtiBridge.changeCapabilities(names, add);
        return capabilityStatuses();
    }

    public static JvmtiPhase phase() {
        requireAvailable();
        return JvmtiPhase.fromNativeValue(NativeJvmtiBridge.phase());
    }

    public static long time() {
        requireAvailable();
        return NativeJvmtiBridge.time();
    }

    public static int availableProcessors() {
        requireAvailable();
        return NativeJvmtiBridge.availableProcessors();
    }

    public static JvmtiLocationFormat locationFormat() {
        requireAvailable();
        return JvmtiLocationFormat.fromNativeValue(NativeJvmtiBridge.locationFormat());
    }

    public static String[] classInfo(Class<?> type) {
        requireAvailable();
        return NativeJvmtiBridge.classInfo(requireType(type));
    }

    public static Class<?>[] implementedInterfaces(Class<?> type) {
        requireAvailable();
        return NativeJvmtiBridge.implementedInterfaces(requireType(type));
    }

    public static ClassLoader classLoader(Class<?> type) {
        requireAvailable();
        return NativeJvmtiBridge.classLoader(requireType(type));
    }

    public static Class<?>[] classLoaderClasses(ClassLoader loader) {
        requireAvailable();
        return NativeJvmtiBridge.classLoaderClasses(loader);
    }

    public static String[] methodInfo(Class<?> type, String methodName, String descriptor) {
        requireAvailable();
        requireMember(methodName, descriptor);
        return NativeJvmtiBridge.methodInfo(requireType(type), methodName, descriptor);
    }

    public static byte[] methodBytecodes(Class<?> type, String methodName, String descriptor) {
        requireAvailable();
        requireMember(methodName, descriptor);
        return NativeJvmtiBridge.methodBytecodes(requireType(type), methodName, descriptor);
    }

    public static String[] lineNumberTable(Class<?> type, String methodName, String descriptor) {
        requireAvailable();
        requireMember(methodName, descriptor);
        return NativeJvmtiBridge.lineNumberTable(requireType(type), methodName, descriptor);
    }

    public static String[] fieldInfo(Class<?> type, String fieldName, String descriptor) {
        requireAvailable();
        requireMember(fieldName, descriptor);
        return NativeJvmtiBridge.fieldInfo(requireType(type), fieldName, descriptor);
    }

    public static String sourceDebugExtension(Class<?> type) {
        requireAvailable();
        return NativeJvmtiBridge.sourceDebugExtension(requireType(type));
    }

    public static byte[] constantPool(Class<?> type) {
        requireAvailable();
        return NativeJvmtiBridge.constantPool(requireType(type));
    }

    public static Thread[] getAllThreads() {
        requireAvailable();
        return NativeJvmtiBridge.getAllThreads();
    }

    public static int getThreadState(Thread thread) {
        requireAvailable();
        return NativeJvmtiBridge.getThreadState(thread);
    }

    public static String[] getStackTrace(Thread thread, int maxFrames) {
        requireAvailable();
        if (maxFrames < 1 || maxFrames > 100_000) {
            throw new IllegalArgumentException("maxFrames must be between 1 and 100000");
        }
        return NativeJvmtiBridge.getStackTrace(thread, maxFrames);
    }

    public static void suspendThread(Thread thread) {
        requireAvailable();
        NativeJvmtiBridge.threadControl(thread, 1);
    }

    public static void resumeThread(Thread thread) {
        requireAvailable();
        NativeJvmtiBridge.threadControl(thread, 2);
    }

    public static void interruptThread(Thread thread) {
        requireAvailable();
        NativeJvmtiBridge.threadControl(thread, 3);
    }

    public static String[] threadInfo(Thread thread) {
        requireAvailable();
        if (thread == null) throw new IllegalArgumentException("thread must not be null");
        return NativeJvmtiBridge.threadInfo(thread);
    }

    public static int frameCount(Thread thread) {
        requireAvailable();
        if (thread == null) throw new IllegalArgumentException("thread must not be null");
        return NativeJvmtiBridge.frameCount(thread);
    }

    public static long threadCpuTime(Thread thread) {
        requireAvailable();
        if (thread == null) throw new IllegalArgumentException("thread must not be null");
        return NativeJvmtiBridge.threadCpuTime(thread);
    }

    public static Object[] ownedMonitors(Thread thread) {
        requireAvailable();
        if (thread == null) throw new IllegalArgumentException("thread must not be null");
        return NativeJvmtiBridge.ownedMonitors(thread);
    }

    public static Object currentContendedMonitor(Thread thread) {
        requireAvailable();
        if (thread == null) throw new IllegalArgumentException("thread must not be null");
        return NativeJvmtiBridge.currentContendedMonitor(thread);
    }

    public static long getObjectSize(Object object) {
        requireAvailable();
        return NativeJvmtiBridge.getObjectSize(object);
    }

    public static int getObjectHashCode(Object object) {
        requireAvailable();
        if (object == null) throw new IllegalArgumentException("object must not be null");
        return NativeJvmtiBridge.getObjectHashCode(object);
    }

    public static String[] objectMonitorUsage(Object object) {
        requireAvailable();
        if (object == null) throw new IllegalArgumentException("object must not be null");
        return NativeJvmtiBridge.objectMonitorUsage(object);
    }

    public static Object[] objectsWithTag(long tag) {
        requireAvailable();
        return NativeJvmtiBridge.objectsWithTag(tag);
    }

    public static long getTag(Object object) {
        requireAvailable();
        return NativeJvmtiBridge.getTag(object);
    }

    public static void setTag(Object object, long tag) {
        requireAvailable();
        NativeJvmtiBridge.setTag(object, tag);
    }

    public static void forceGarbageCollection() {
        requireAvailable();
        NativeJvmtiBridge.forceGarbageCollection();
    }

    public static String[] systemProperties() {
        requireAvailable();
        return NativeJvmtiBridge.systemProperties();
    }

    public static String getSystemProperty(String name) {
        requireAvailable();
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Property name must not be empty");
        }
        return NativeJvmtiBridge.getSystemProperty(name);
    }

    public static void setSystemProperty(String name, String value) {
        requireAvailable();
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Property name must not be empty");
        }
        if (value == null) throw new IllegalArgumentException("Property value must not be null");
        NativeJvmtiBridge.setSystemProperty(name, value);
    }

    public static long currentThreadCpuTime() {
        requireAvailable();
        return NativeJvmtiBridge.currentThreadCpuTime();
    }

    public static String[] timerInfo() {
        requireAvailable();
        return NativeJvmtiBridge.timerInfo();
    }

    public static void generateEvents(String eventName) {
        requireAvailable();
        if (eventName == null || eventName.trim().isEmpty()) {
            throw new IllegalArgumentException("eventName must not be empty");
        }
        NativeJvmtiBridge.generateEvents(eventName);
    }

    public static void setVerboseFlag(String flagName, boolean enabled) {
        requireAvailable();
        if (flagName == null || flagName.trim().isEmpty()) {
            throw new IllegalArgumentException("flagName must not be empty");
        }
        NativeJvmtiBridge.setVerboseFlag(flagName, enabled);
    }

    private static RuntimeInfo initialize() {
        try {
            if (!Boolean.getBoolean("jvmrtdp.native.preloaded")) {
                DLLSupport.loadDllFromJar(DLLSupport.AGENT_RESOURCE);
            }
            return new RuntimeInfo(true, NativeRuntimeBridge.version(), NativeRuntimeBridge.jvmtiVersion(), "");
        } catch (RuntimeException exception) {
            return new RuntimeInfo(false, "unavailable", 0, exception.toString());
        } catch (LinkageError error) {
            return new RuntimeInfo(false, "unavailable", 0, error.toString());
        }
    }

    private static void requireAvailable() {
        if (!RUNTIME_INFO.available()) {
            throw new IllegalStateException("JNI/JVMTI bridge is unavailable: " + RUNTIME_INFO.error());
        }
    }

    private static Class<?> requireType(Class<?> type) {
        if (type == null) throw new IllegalArgumentException("type must not be null");
        return type;
    }

    private static void requireMember(String name, String descriptor) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("member name must not be empty");
        if (descriptor == null || descriptor.isEmpty()) {
            throw new IllegalArgumentException("member descriptor must not be empty");
        }
    }

    public static class RuntimeInfo {
        private final boolean available;
        private final String version;
        private final int jvmtiVersion;
        private final String error;

        public RuntimeInfo(boolean available, String version, int jvmtiVersion, String error) {
            this.available = available;
            this.version = version;
            this.jvmtiVersion = jvmtiVersion;
            this.error = error;
        }

        public boolean available() {
            return available;
        }

        public String version() {
            return version;
        }

        public int jvmtiVersion() {
            return jvmtiVersion;
        }

        public String error() {
            return error;
        }

        public String describe() {
            if (!available) {
                return "unavailable: " + error;
            }
            return version + ", JVMTI=0x" + Integer.toHexString(jvmtiVersion);
        }
    }
}
