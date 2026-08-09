package nhcm.jvmrtdp.agent;

import nhcm.jvmrtdp.tools.DLLSupport;
import nhcm.jvmrtdp.api.jvmti.JvmtiCapability;
import nhcm.jvmrtdp.api.jvmti.JvmtiCapabilityStatus;
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
        requireAvailable();
        if (type == null) throw new IllegalArgumentException("type must not be null");
        if (methodName == null || methodName.isEmpty()) throw new IllegalArgumentException("methodName must not be empty");
        if (descriptor == null || descriptor.isEmpty()) throw new IllegalArgumentException("descriptor must not be empty");
        NativeJvmtiBridge.setBreakpoint(type, methodName, descriptor, location, enabled);
    }

    public static void setFieldWatch(Class<?> type, String fieldName, String descriptor,
            boolean modification, boolean enabled) {
        requireAvailable();
        if (type == null) throw new IllegalArgumentException("type must not be null");
        if (fieldName == null || fieldName.isEmpty()) throw new IllegalArgumentException("fieldName must not be empty");
        if (descriptor == null || descriptor.isEmpty()) throw new IllegalArgumentException("descriptor must not be empty");
        NativeJvmtiBridge.setFieldWatch(type, fieldName, descriptor, modification, enabled);
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

    public static long getObjectSize(Object object) {
        requireAvailable();
        return NativeJvmtiBridge.getObjectSize(object);
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
