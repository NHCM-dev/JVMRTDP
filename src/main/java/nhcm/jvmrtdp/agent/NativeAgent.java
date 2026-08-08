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
