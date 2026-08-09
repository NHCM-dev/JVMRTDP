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

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) jvmti.unregisterCallback(id);
    }

    @Override
    public String toString() {
        return "RemoteJvmtiCallback[id=" + id + ", closed=" + closed.get() + "]";
    }
}
