package nhcm.jvmrtdp.handles.jvm;

import nhcm.jvmrtdp.handles.java.RemoteObject;

/** A JVMTI thread snapshot backed by a normal remote object handle. */
public class RemoteJvmtiThread implements AutoCloseable {
    private final RemoteJVMTIEnv jvmti;
    private final RemoteObject thread;
    private final int capturedState;
    private final String name;
    private final int priority;
    private final boolean daemon;
    private final boolean debuggerPaused;

    RemoteJvmtiThread(RemoteJVMTIEnv jvmti, RemoteObject thread, int capturedState,
            String name, int priority, boolean daemon, boolean debuggerPaused) {
        this.jvmti = jvmti;
        this.thread = thread;
        this.capturedState = capturedState;
        this.name = name;
        this.priority = priority;
        this.daemon = daemon;
        this.debuggerPaused = debuggerPaused;
    }

    public RemoteObject object() { return thread; }
    public int capturedState() { return capturedState; }
    public String name() { return name; }
    public int priority() { return priority; }
    public boolean daemon() { return daemon; }
    public boolean debuggerPaused() { return debuggerPaused; }
    public String stateSummary() {
        if ((capturedState & 0x2) != 0) return "TERMINATED";
        String base = (capturedState & 0x4) != 0 ? "RUNNABLE"
                : (capturedState & 0x400) != 0 ? "BLOCKED"
                : (capturedState & 0x40) != 0 ? "SLEEPING"
                : (capturedState & 0x200) != 0 ? "PARKED"
                : (capturedState & 0x80) != 0 ? "WAITING"
                : (capturedState & 0x1) != 0 ? "ALIVE" : "NEW";
        return (capturedState & 0x100000) != 0 ? base + "/SUSPENDED" : base;
    }
    public int state() { return jvmti.threadState(thread); }
    public java.util.List<String> stackTrace(int maxFrames) { return jvmti.stackTrace(thread, maxFrames); }
    public java.util.List<nhcm.jvmrtdp.api.jvmti.JvmStackFrame> stackFrames(int maxFrames) {
        return jvmti.stackFrames(thread, maxFrames);
    }
    public void suspend() { jvmti.suspendThread(thread); }
    public void pauseInDebugger() { jvmti.pauseExecution(thread); }
    public void resume() { jvmti.resumeThread(thread); }
    public void interrupt() { jvmti.interruptThread(thread); }
    public void notifyFramePop(int depth) { jvmti.notifyFramePop(thread, depth); }
    @Override public void close() { thread.close(); }
}
