package nhcm.jvmrtdp.api.jvmti;

/** Controller-managed JVMTI field access/modification watch shared by CLI and TUI. */
public final class JvmFieldWatchInfo {
    private final String className;
    private final String fieldName;
    private final String descriptor;
    private final boolean modification;
    private final String registrationId;
    private final long receiverId;

    public JvmFieldWatchInfo(String className, String fieldName, String descriptor,
            boolean modification) {
        this(className, fieldName, descriptor, modification,
                className + '|' + fieldName + '|' + descriptor + '|'
                        + (modification ? "write" : "read"), 0L);
    }

    public JvmFieldWatchInfo(String className, String fieldName, String descriptor,
            boolean modification, String registrationId, long receiverId) {
        this.className = className;
        this.fieldName = fieldName;
        this.descriptor = descriptor;
        this.modification = modification;
        this.registrationId = registrationId;
        this.receiverId = receiverId;
    }

    public String className() { return className; }
    public String fieldName() { return fieldName; }
    public String descriptor() { return descriptor; }
    public boolean modification() { return modification; }
    public String kind() { return modification ? "write" : "read"; }
    public String registrationId() { return registrationId; }
    public long receiverId() { return receiverId; }
    public boolean objectSpecific() { return receiverId != 0L; }
    public String id() { return registrationId; }
}
