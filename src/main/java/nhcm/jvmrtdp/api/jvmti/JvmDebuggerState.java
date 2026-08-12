package nhcm.jvmrtdp.api.jvmti;

import nhcm.jvmrtdp.handles.java.RemoteObject;

/** Snapshot of the target thread currently stopped inside a JVMTI debugger callback. */
public final class JvmDebuggerState implements AutoCloseable {
    private final RemoteObject thread;
    private final boolean enabled;
    private final boolean paused;
    private final String reason;
    private final String className;
    private final String methodName;
    private final String descriptor;
    private final long location;
    private final int sourceLine;
    private final long sequence;
    private final RemoteObject returnValue;
    private final String returnState;

    public JvmDebuggerState(RemoteObject thread, boolean enabled, boolean paused, String reason,
            String className, String methodName, String descriptor, long location,
            int sourceLine, long sequence) {
        this(thread, enabled, paused, reason, className, methodName, descriptor,
                location, sourceLine, sequence, null, "");
    }

    public JvmDebuggerState(RemoteObject thread, boolean enabled, boolean paused, String reason,
            String className, String methodName, String descriptor, long location,
            int sourceLine, long sequence, RemoteObject returnValue, String returnState) {
        this.thread = thread;
        this.enabled = enabled;
        this.paused = paused;
        this.reason = reason;
        this.className = className;
        this.methodName = methodName;
        this.descriptor = descriptor;
        this.location = location;
        this.sourceLine = sourceLine;
        this.sequence = sequence;
        this.returnValue = returnValue;
        this.returnState = returnState == null ? "" : returnState;
    }

    public RemoteObject thread() { return thread; }
    public boolean enabled() { return enabled; }
    public boolean paused() { return paused; }
    public String reason() { return reason; }
    public String className() { return className; }
    public String methodName() { return methodName; }
    public String descriptor() { return descriptor; }
    public long location() { return location; }
    public int sourceLine() { return sourceLine; }
    public long sequence() { return sequence; }
    /** Boxed return value captured by a method-exit stop, if the method is non-void. */
    public RemoteObject returnValue() { return returnValue; }
    /** Empty for ordinary stops; otherwise {@code value}, {@code void}, or {@code exception}. */
    public String returnState() { return returnState; }

    @Override public void close() {
        if (thread != null) thread.close();
        if (returnValue != null) returnValue.close();
    }

    @Override
    public String toString() {
        if (!paused) return "debugger " + (enabled ? "running" : "disabled");
        return reason + " at " + className + "." + methodName + descriptor
                + " bci=" + location + (sourceLine < 0 ? "" : " line=" + sourceLine)
                + (returnState.isEmpty() ? "" : " return=" + (returnValue == null ? returnState : returnValue));
    }
}
