package nhcm.jvmrtdp.handles.jvm;

import nhcm.jvmrtdp.handles.java.RemoteObject;

/** A JVMTI thread snapshot backed by a normal remote object handle. */
public class RemoteJvmtiThread implements AutoCloseable {
    private final RemoteJVMTIEnv jvmti;
    private final RemoteObject thread;
    private final int capturedState;

    RemoteJvmtiThread(RemoteJVMTIEnv jvmti, RemoteObject thread, int capturedState) {
        this.jvmti = jvmti;
        this.thread = thread;
        this.capturedState = capturedState;
    }

    public RemoteObject object() { return thread; }
    public int capturedState() { return capturedState; }
    public int state() { return jvmti.threadState(thread); }
    public java.util.List<String> stackTrace(int maxFrames) { return jvmti.stackTrace(thread, maxFrames); }
    public void suspend() { jvmti.suspendThread(thread); }
    public void resume() { jvmti.resumeThread(thread); }
    public void interrupt() { jvmti.interruptThread(thread); }
    public void notifyFramePop(int depth) { jvmti.notifyFramePop(thread, depth); }
    @Override public void close() { thread.close(); }
}
