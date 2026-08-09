package nhcm.jvmrtdp.agent;

import nhcm.jvmrtdp.tools.DLLSupport;

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
        return nativeGetClassBytes(className);
    }

    public static String readStaticFields(String className) {
        requireAvailable();
        return nativeReadStaticFields(className);
    }

    public static String readStaticField(String className, String fieldName) {
        requireAvailable();
        return nativeReadStaticField(className, fieldName);
    }

    public static String callStaticMethod(
            String className, String methodName, String descriptor, String[] arguments) {
        requireAvailable();
        return nativeCallStaticMethod(className, methodName, descriptor, arguments);
    }

    public static Class<?> findLoadedClass(String className) {
        requireAvailable();
        return nativeFindLoadedClass(className);
    }

    public static String[] listLoadedClassNames() {
        requireAvailable();
        return nativeListLoadedClassNames();
    }

    public static Class<?>[] listLoadedClasses() {
        requireAvailable();
        return nativeListLoadedClasses();
    }

    public static Class<?> defineClass(String className, byte[] classBytes, ClassLoader loader) {
        requireAvailable();
        if (className == null || className.trim().isEmpty()) {
            throw new IllegalArgumentException("className must not be empty");
        }
        if (classBytes == null || classBytes.length == 0) {
            throw new IllegalArgumentException("classBytes must not be empty");
        }
        return nativeDefineClass(className, classBytes, loader);
    }

    public static void addToBootstrapClassLoaderSearch(String jarPath) {
        requireAvailable();
        nativeAddToClassLoaderSearch(jarPath, true);
    }

    public static void addToSystemClassLoaderSearch(String jarPath) {
        requireAvailable();
        nativeAddToClassLoaderSearch(jarPath, false);
    }

    public static void setEventNotification(String eventName, boolean enabled) {
        requireAvailable();
        nativeSetEventNotification(eventName, enabled);
    }

    public static void setBreakpoint(Class<?> type, String methodName, String descriptor,
            long location, boolean enabled) {
        requireAvailable();
        if (type == null) throw new IllegalArgumentException("type must not be null");
        if (methodName == null || methodName.isEmpty()) throw new IllegalArgumentException("methodName must not be empty");
        if (descriptor == null || descriptor.isEmpty()) throw new IllegalArgumentException("descriptor must not be empty");
        nativeSetBreakpoint(type, methodName, descriptor, location, enabled);
    }

    public static void setFieldWatch(Class<?> type, String fieldName, String descriptor,
            boolean modification, boolean enabled) {
        requireAvailable();
        if (type == null) throw new IllegalArgumentException("type must not be null");
        if (fieldName == null || fieldName.isEmpty()) throw new IllegalArgumentException("fieldName must not be empty");
        if (descriptor == null || descriptor.isEmpty()) throw new IllegalArgumentException("descriptor must not be empty");
        nativeSetFieldWatch(type, fieldName, descriptor, modification, enabled);
    }

    public static void notifyFramePop(Thread thread, int depth) {
        requireAvailable();
        if (thread == null) throw new IllegalArgumentException("thread must not be null");
        if (depth < 0) throw new IllegalArgumentException("depth must not be negative");
        nativeNotifyFramePop(thread, depth);
    }

    /** queued, dropped and currently pending native events, in that order. */
    public static long[] eventQueueStatistics() {
        requireAvailable();
        return nativeEventQueueStatistics();
    }

    public static void retransformClass(Class<?> type) {
        requireAvailable();
        nativeRetransformClass(type);
    }

    public static void redefineClass(Class<?> type, byte[] classBytes) {
        requireAvailable();
        nativeRedefineClass(type, classBytes);
    }

    public static String capabilities() {
        requireAvailable();
        return nativeCapabilities();
    }

    public static Thread[] getAllThreads() {
        requireAvailable();
        return nativeGetAllThreads();
    }

    public static int getThreadState(Thread thread) {
        requireAvailable();
        return nativeGetThreadState(thread);
    }

    public static String[] getStackTrace(Thread thread, int maxFrames) {
        requireAvailable();
        if (maxFrames < 1 || maxFrames > 100_000) {
            throw new IllegalArgumentException("maxFrames must be between 1 and 100000");
        }
        return nativeGetStackTrace(thread, maxFrames);
    }

    public static void suspendThread(Thread thread) {
        requireAvailable();
        nativeThreadControl(thread, 1);
    }

    public static void resumeThread(Thread thread) {
        requireAvailable();
        nativeThreadControl(thread, 2);
    }

    public static void interruptThread(Thread thread) {
        requireAvailable();
        nativeThreadControl(thread, 3);
    }

    public static long getObjectSize(Object object) {
        requireAvailable();
        return nativeGetObjectSize(object);
    }

    public static long getTag(Object object) {
        requireAvailable();
        return nativeGetTag(object);
    }

    public static void setTag(Object object, long tag) {
        requireAvailable();
        nativeSetTag(object, tag);
    }

    public static void forceGarbageCollection() {
        requireAvailable();
        nativeForceGarbageCollection();
    }

    public static String[] systemProperties() {
        requireAvailable();
        return nativeSystemProperties();
    }

    private static RuntimeInfo initialize() {
        try {
            DLLSupport.loadDllFromJar(DLLSupport.AGENT_RESOURCE);
            return new RuntimeInfo(true, nativeVersion(), nativeJvmtiVersion(), "");
        } catch (RuntimeException exception) {
            return new RuntimeInfo(false, "unavailable", 0, exception.toString());
        } catch (LinkageError error) {
            return new RuntimeInfo(false, "unavailable", 0, error.toString());
        }
    }

    private static native String nativeVersion();

    private static native int nativeJvmtiVersion();

    private static native byte[] nativeGetClassBytes(String className);

    private static native String nativeReadStaticFields(String className);

    private static native String nativeReadStaticField(String className, String fieldName);

    private static native String nativeCallStaticMethod(
            String className, String methodName, String descriptor, String[] arguments);

    private static native Class<?> nativeFindLoadedClass(String className);

    private static native String[] nativeListLoadedClassNames();

    private static native Class<?>[] nativeListLoadedClasses();

    private static native Class<?> nativeDefineClass(String className, byte[] classBytes, ClassLoader loader);

    private static native void nativeAddToClassLoaderSearch(String jarPath, boolean bootstrap);

    private static native void nativeSetEventNotification(String eventName, boolean enabled);

    private static native void nativeSetBreakpoint(Class<?> type, String methodName,
            String descriptor, long location, boolean enabled);

    private static native void nativeSetFieldWatch(Class<?> type, String fieldName,
            String descriptor, boolean modification, boolean enabled);

    private static native void nativeNotifyFramePop(Thread thread, int depth);

    private static native long[] nativeEventQueueStatistics();

    private static native void nativeRetransformClass(Class<?> type);

    private static native void nativeRedefineClass(Class<?> type, byte[] classBytes);

    private static native String nativeCapabilities();

    private static native Thread[] nativeGetAllThreads();

    private static native int nativeGetThreadState(Thread thread);

    private static native String[] nativeGetStackTrace(Thread thread, int maxFrames);

    private static native void nativeThreadControl(Thread thread, int operation);

    private static native long nativeGetObjectSize(Object object);

    private static native long nativeGetTag(Object object);

    private static native void nativeSetTag(Object object, long tag);

    private static native void nativeForceGarbageCollection();

    private static native String[] nativeSystemProperties();

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
