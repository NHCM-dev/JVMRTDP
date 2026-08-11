package nhcm.jvmrtdp.api.jvmti;

/** Controller-managed JVMTI field access/modification watch shared by CLI and TUI. */
public final class JvmFieldWatchInfo {
    private final String className;
    private final String fieldName;
    private final String descriptor;
    private final boolean modification;

    public JvmFieldWatchInfo(String className, String fieldName, String descriptor,
            boolean modification) {
        this.className = className;
        this.fieldName = fieldName;
        this.descriptor = descriptor;
        this.modification = modification;
    }

    public String className() { return className; }
    public String fieldName() { return fieldName; }
    public String descriptor() { return descriptor; }
    public boolean modification() { return modification; }
    public String kind() { return modification ? "write" : "read"; }
    public String id() {
        return className + '|' + fieldName + '|' + descriptor + '|'
                + (modification ? "write" : "read");
    }
}
