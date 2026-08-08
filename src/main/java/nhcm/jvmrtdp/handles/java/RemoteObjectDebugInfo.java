package nhcm.jvmrtdp.handles.java;

/** Diagnostic metadata for a remote value without transferring that value locally. */
public class RemoteObjectDebugInfo {
    private final long objectId;
    private final String className;
    private final String shape;
    private final String size;
    private final String identityHash;
    private final int declaredFields;
    private final int declaredMethods;
    private final String displayValue;

    public RemoteObjectDebugInfo(long objectId, String className, String shape, String size,
            String identityHash, int declaredFields, int declaredMethods, String displayValue) {
        this.objectId = objectId;
        this.className = className;
        this.shape = shape;
        this.size = size;
        this.identityHash = identityHash;
        this.declaredFields = declaredFields;
        this.declaredMethods = declaredMethods;
        this.displayValue = displayValue;
    }

    public long objectId() { return objectId; }
    public String className() { return className; }
    public String shape() { return shape; }
    public String size() { return size; }
    public String identityHash() { return identityHash; }
    public int declaredFields() { return declaredFields; }
    public int declaredMethods() { return declaredMethods; }
    public String displayValue() { return displayValue; }
}
