package nhcm.jvmrtdp.handles.jvm;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A target-side Java handler registered for one or more JVMTI events. The callback remains active
 * until disabled, explicitly unregistered, its deployment is closed, or this handle is closed.
 * Callback delivery/error/drop counters are available through {@link RemoteJVMTIEnv#callbacks()}
 * and {@link RemoteJVMTIEnv#callbackStatistics()}.
 */
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

    /** Returns the latest registration state and delivery counters for this callback. */
    public JvmtiCallbackRegistration statistics() {
        if (closed.get()) throw new IllegalStateException("Callback is closed");
        for (JvmtiCallbackRegistration registration : jvmti.callbacks()) {
            if (id.equals(registration.id())) return registration;
        }
        throw new IllegalStateException("Callback is no longer registered: " + id);
    }

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
