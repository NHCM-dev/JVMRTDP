package nhcm.jvmrtdp.handles.jvm;

import nhcm.jvmrtdp.handles.java.RemoteObject;
import nhcm.jvmrtdp.api.jvmti.JvmtiEventType;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** A group of target-side classes or one target-side JAR class loader. */
public class RemoteCodeDeployment implements AutoCloseable {
    private final RemoteJVMTIEnv jvmti;
    private final String id;
    private final String name;
    private final String mode;
    private final int definedClassCount;
    private final String loader;
    private final String targetLoader;
    private final AtomicBoolean closed = new AtomicBoolean();

    RemoteCodeDeployment(RemoteJVMTIEnv jvmti, String id, String name, String mode,
            int definedClassCount, String loader, String targetLoader) {
        this.jvmti = Objects.requireNonNull(jvmti, "jvmti");
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.definedClassCount = definedClassCount;
        this.loader = Objects.requireNonNull(loader, "loader");
        this.targetLoader = Objects.requireNonNull(targetLoader, "targetLoader");
    }

    public String id() { return id; }
    public String name() { return name; }
    public String mode() { return mode; }
    public int definedClassCount() { return definedClassCount; }
    public String loader() { return loader; }
    public String targetLoader() { return targetLoader; }
    public boolean isClosed() { return closed.get(); }

    public RemoteObject execute(String className, String methodName, String descriptor,
            RemoteObject receiver, RemoteObject... arguments) {
        ensureOpen();
        return jvmti.execute(this, className, methodName, descriptor, receiver, arguments);
    }

    public RemoteJvmtiCallback registerCallback(String handlerClass, String events, boolean synchronous) {
        ensureOpen();
        return jvmti.registerCallback(this, handlerClass, events, synchronous);
    }

    public RemoteJvmtiCallback registerCallback(String handlerClass, Set<JvmtiEventType> events,
            boolean synchronous) {
        StringBuilder names = new StringBuilder();
        if (events != null) {
            for (JvmtiEventType event : events) {
                if (names.length() != 0) names.append(',');
                names.append(event.wireName());
            }
        }
        return registerCallback(handlerClass, names.toString(), synchronous);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) jvmti.closeDeployment(id);
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("Code deployment is closed: " + id);
    }

    @Override
    public String toString() {
        return "RemoteCodeDeployment[id=" + id + ", name=" + name + ", mode=" + mode
                + ", classes=" + definedClassCount + "]";
    }
}
