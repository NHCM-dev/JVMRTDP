package nhcm.jvmrtdp.api.jvmti;

import nhcm.jvmrtdp.handles.java.RemoteObject;

/** One live local-variable-table entry from a paused debugger frame. */
public final class JvmDebuggerLocal implements AutoCloseable {
    private final String name;
    private final String descriptor;
    private final String genericSignature;
    private final int slot;
    private final long scopeStart;
    private final long scopeLength;
    private final RemoteObject value;
    private final String error;

    public JvmDebuggerLocal(String name, String descriptor, String genericSignature, int slot,
            long scopeStart, long scopeLength, RemoteObject value, String error) {
        this.name = name;
        this.descriptor = descriptor;
        this.genericSignature = genericSignature;
        this.slot = slot;
        this.scopeStart = scopeStart;
        this.scopeLength = scopeLength;
        this.value = value;
        this.error = error;
    }

    public String name() { return name; }
    public String descriptor() { return descriptor; }
    public String genericSignature() { return genericSignature; }
    public int slot() { return slot; }
    public long scopeStart() { return scopeStart; }
    public long scopeLength() { return scopeLength; }
    public RemoteObject value() { return value; }
    public String error() { return error; }
    public boolean available() { return error == null || error.isEmpty(); }
    public boolean inferred() {
        return genericSignature != null && genericSignature.startsWith("inferred:");
    }

    @Override public void close() { if (value != null) value.close(); }

    @Override public String toString() {
        String label = (name == null || name.isEmpty() ? "slot" + slot : name)
                + ":" + descriptor;
        return available() ? label + " = " + (value == null ? "null" : value.displayValue())
                : label + " <" + error + ">";
    }
}
