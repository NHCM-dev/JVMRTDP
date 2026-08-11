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

    public static native void debuggerConfigure(boolean enabled);

    public static native Object[] debuggerSnapshot();

    public static native Object[][] debuggerSnapshots();

    /** action: 0=continue, 1=single-step one bytecode. */
    public static native void debuggerResume(int action);

    /** A null thread with action 0 continues every paused debugger thread. */
    public static native void debuggerResumeThread(Thread thread, int action);

    /** Suspends an arbitrary live JVM thread and exposes it as a debugger stop. */
    public static native void debuggerPauseThread(Thread thread, String reason);

    /** Active local-variable table entries for a frame in a paused debugger thread. */
    public static native Object[][] debuggerLocals(Thread thread, int depth);

    public static native void setFieldWatch(Class<?> type, String fieldName,
            String descriptor, boolean modification, boolean enabled);

    public static native void notifyFramePop(Thread thread, int depth);

    public static native long[] eventQueueStatistics();

    public static native void retransformClass(Class<?> type);

    public static native void redefineClass(Class<?> type, byte[] classBytes);

    public static native String capabilities();

    public static native String[] capabilityStatuses();

    public static native void changeCapabilities(String[] capabilityNames, boolean add);

    public static native int phase();

    public static native long time();

    public static native int availableProcessors();

    public static native int locationFormat();

    public static native String[] classInfo(Class<?> type);

    public static native Class<?>[] implementedInterfaces(Class<?> type);

    public static native ClassLoader classLoader(Class<?> type);

    public static native Class<?>[] classLoaderClasses(ClassLoader loader);

    public static native String[] methodInfo(Class<?> type, String methodName, String descriptor);

    public static native byte[] methodBytecodes(Class<?> type, String methodName, String descriptor);

    public static native String[] lineNumberTable(Class<?> type, String methodName, String descriptor);

    public static native String[] fieldInfo(Class<?> type, String fieldName, String descriptor);

    public static native String sourceDebugExtension(Class<?> type);

    public static native byte[] constantPool(Class<?> type);

    public static native Thread[] getAllThreads();

    public static native int getThreadState(Thread thread);

    public static native String[] getStackTrace(Thread thread, int maxFrames);

    public static native void threadControl(Thread thread, int operation);

    public static native String[] threadInfo(Thread thread);

    public static native int frameCount(Thread thread);

    public static native long threadCpuTime(Thread thread);

    public static native Object[] ownedMonitors(Thread thread);

    public static native Object currentContendedMonitor(Thread thread);

    public static native long getObjectSize(Object object);

    public static native int getObjectHashCode(Object object);

    public static native String[] objectMonitorUsage(Object object);

    public static native Object[] objectsWithTag(long tag);

    public static native long getTag(Object object);

    public static native void setTag(Object object, long tag);

    public static native void forceGarbageCollection();

    public static native String[] systemProperties();

    public static native String getSystemProperty(String name);

    public static native void setSystemProperty(String name, String value);

    public static native long currentThreadCpuTime();

    public static native String[] timerInfo();

    public static native void generateEvents(String eventName);

    public static native void setVerboseFlag(String flagName, boolean enabled);
}
