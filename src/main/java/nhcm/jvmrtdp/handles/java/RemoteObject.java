package nhcm.jvmrtdp.handles.java;

import nhcm.jvmrtdp.handles.RemoteHandle;
import nhcm.jvmrtdp.handles.ServerHandle;
import nhcm.jvmrtdp.handles.jvm.RemoteJNIEnv;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** A strong target-side object reference scoped to one ServerHandle session. */
public class RemoteObject extends RemoteHandle implements AutoCloseable {
    private final RemoteJNIEnv jni;
    private volatile String className;
    private volatile boolean nullValue;
    private volatile String displayValue;
    private final AtomicBoolean released = new AtomicBoolean();

    public RemoteObject(
            ServerHandle server,
            long remoteId,
            RemoteJNIEnv jni,
            String className,
            boolean nullValue,
            String displayValue) {
        super(server, remoteId);
        this.jni = Objects.requireNonNull(jni, "jni");
        this.className = Objects.requireNonNull(className, "className");
        this.nullValue = nullValue;
        this.displayValue = Objects.requireNonNull(displayValue, "displayValue");
    }

    public String className() {
        return className;
    }

    public boolean isNull() {
        return nullValue;
    }

    public boolean isReleased() {
        return released.get();
    }

    public String displayValue() {
        return displayValue;
    }

    /** Refreshes the target-side type/null/display snapshot held by this handle. */
    public RemoteObject refresh() {
        ensureAvailable();
        jni.refresh(this);
        return this;
    }

    public void updateDescriptor(String className, boolean nullValue, String displayValue) {
        ensureAvailable();
        this.className = Objects.requireNonNull(className, "className");
        this.nullValue = nullValue;
        this.displayValue = Objects.requireNonNull(displayValue, "displayValue");
    }

    public RemoteClass remoteClass() {
        ensureAvailable();
        return jni.findClass(className);
    }

    public <T> T asObject(Class<T> clazz) {
        ensureAvailable();
        return jni.materialize(this, clazz);
    }

    public List<RemoteMethod> getVirtualMethods() {
        return remoteClass().getVirtualMethods();
    }

    public List<RemoteField> getVirtualFields() {
        return remoteClass().getVirtualFields();
    }

    public RemoteObject call(String name, String descriptor, RemoteObject... arguments) {
        return remoteClass().getVirtualMethod(name, descriptor).call(this, arguments);
    }

    public RemoteObject callSpecial(
            String declaringClass, String name, String descriptor, RemoteObject... arguments) {
        return remoteClass().getVirtualMethod(declaringClass, name, descriptor).callSpecial(this, arguments);
    }

    public boolean isInstanceOf(RemoteClass type) {
        ensureAvailable();
        return type.isInstance(this);
    }

    public RemoteObjectView viewAs(RemoteClass type) {
        ensureAvailable();
        return new RemoteObjectView(this, type);
    }

    public RemoteObject readField(String name) {
        return remoteClass().getVirtualField(name).read(this);
    }

    public int arrayLength() {
        ensureAvailable();
        return jni.arrayLength(this);
    }

    public RemoteObject arrayGet(int index) {
        ensureAvailable();
        return jni.arrayGet(this, index);
    }

    public void arraySet(int index, RemoteObject value) {
        ensureAvailable();
        jni.arraySet(this, index, value);
    }

    public List<RemoteObject> iterableElements(int limit) {
        ensureAvailable();
        return jni.iterableElements(this, limit);
    }

    public List<RemoteMapEntry> mapEntries(int limit) {
        ensureAvailable();
        return jni.mapEntries(this, limit);
    }

    public RemoteObjectDebugInfo debugInfo() {
        ensureAvailable();
        return jni.debug(this);
    }

    public RemoteArray asArray() {
        ensureAvailable();
        return new RemoteArray(this);
    }

    public RemoteIterable asIterable() {
        ensureAvailable();
        return new RemoteIterable(this);
    }

    public RemoteMap asMap() {
        ensureAvailable();
        return new RemoteMap(this);
    }

    @Override
    public void close() {
        if (released.compareAndSet(false, true)) {
            jni.release(this);
        }
    }

    private void ensureAvailable() {
        if (released.get()) {
            throw new IllegalStateException("Remote object has been released: " + remoteId());
        }
    }

    @Override
    public String toString() {
        return nullValue ? "null" : className + "#" + remoteId() + "(" + displayValue + ")";
    }
}
