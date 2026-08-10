package nhcm.jvmrtdp.api.jvmti;

/** Thread metadata from GetThreadInfo plus current JVMTI thread state. */
public final class JvmtiThreadInfo {
    private final String name, threadGroupClass, contextClassLoaderClass;
    private final int priority, state;
    private final boolean daemon;

    public JvmtiThreadInfo(String name, int priority, boolean daemon, String threadGroupClass,
            String contextClassLoaderClass, int state) {
        this.name = name; this.priority = priority; this.daemon = daemon;
        this.threadGroupClass = threadGroupClass; this.contextClassLoaderClass = contextClassLoaderClass;
        this.state = state;
    }

    public String name() { return name; }
    public int priority() { return priority; }
    public boolean daemon() { return daemon; }
    public String threadGroupClass() { return threadGroupClass; }
    public String contextClassLoaderClass() { return contextClassLoaderClass; }
    public int state() { return state; }
}
