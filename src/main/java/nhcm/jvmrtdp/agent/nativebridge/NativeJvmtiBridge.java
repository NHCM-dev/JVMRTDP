package nhcm.jvmrtdp.agent.nativebridge;

/** JVMTI operations and operations whose implementation requires JVMTI state. */
public final class NativeJvmtiBridge {
    public static final String BINDING_CLASS =
            "nhcm/jvmrtdp/agent/nativebridge/NativeJvmtiBridge";

    private NativeJvmtiBridge() {
    }

    public static native byte[] getClassBytes(String className);

    public static native void addToClassLoaderSearch(String jarPath, boolean bootstrap);

    public static native void setEventNotification(String eventName, boolean enabled);

    public static native void setBreakpoint(Class<?> type, String methodName,
            String descriptor, long location, boolean enabled);

    public static native void setFieldWatch(Class<?> type, String fieldName,
            String descriptor, boolean modification, boolean enabled);

    public static native void notifyFramePop(Thread thread, int depth);

    public static native long[] eventQueueStatistics();

    public static native void retransformClass(Class<?> type);

    public static native void redefineClass(Class<?> type, byte[] classBytes);

    public static native String capabilities();

    public static native String[] capabilityStatuses();

    public static native Thread[] getAllThreads();

    public static native int getThreadState(Thread thread);

    public static native String[] getStackTrace(Thread thread, int maxFrames);

    public static native void threadControl(Thread thread, int operation);

    public static native long getObjectSize(Object object);

    public static native long getTag(Object object);

    public static native void setTag(Object object, long tag);

    public static native void forceGarbageCollection();

    public static native String[] systemProperties();
}
