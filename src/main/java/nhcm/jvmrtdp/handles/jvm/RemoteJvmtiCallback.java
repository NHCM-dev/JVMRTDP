package nhcm.jvmrtdp.handles.jvm;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** A target-side Java handler registered for one or more JVMTI events. */
public class RemoteJvmtiCallback implements AutoCloseable {
    private final RemoteJVMTIEnv jvmti;
    private final String id;
    private final AtomicBoolean closed = new AtomicBoolean();

    RemoteJvmtiCallback(RemoteJVMTIEnv jvmti, String id) {
        this.jvmti = Objects.requireNonNull(jvmti, "jvmti");
        this.id = Objects.requireNonNull(id, "id");
    }

    public String id() { return id; }
    public boolean isClosed() { return closed.get(); }

    public boolean setEnabled(boolean enabled) {
        if (closed.get()) throw new IllegalStateException("Callback is closed");
        return jvmti.setCallbackEnabled(id, enabled);
    }

    public boolean enable() { return setEnabled(true); }
    public boolean disable() { return setEnabled(false); }

    public boolean resetStatistics() {
        if (closed.get()) throw new IllegalStateException("Callback is closed");
        return jvmti.resetCallback(id);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) jvmti.unregisterCallback(id);
    }

    @Override
    public String toString() {
        return "RemoteJvmtiCallback[id=" + id + ", closed=" + closed.get() + "]";
    }
}
