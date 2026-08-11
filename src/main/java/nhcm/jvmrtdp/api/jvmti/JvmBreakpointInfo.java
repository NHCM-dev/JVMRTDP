package nhcm.jvmrtdp.api.jvmti;

/** Controller-managed persistent bytecode breakpoint shared by CLI and TUI. */
public final class JvmBreakpointInfo {
    private final String className;
    private final String methodName;
    private final String descriptor;
    private final long location;
    private final String registrationId;
    private final long receiverId;
    private final String conditionSummary;

    public JvmBreakpointInfo(String className, String methodName, String descriptor, long location) {
        this(className, methodName, descriptor, location,
                className + '|' + methodName + '|' + descriptor + '|' + location,
                0L, "all receivers/callers");
    }

    public JvmBreakpointInfo(String className, String methodName, String descriptor, long location,
            String registrationId, long receiverId, String conditionSummary) {
        this.className = className;
        this.methodName = methodName;
        this.descriptor = descriptor;
        this.location = location;
        this.registrationId = registrationId;
        this.receiverId = receiverId;
        this.conditionSummary = conditionSummary;
    }

    public String className() { return className; }
    public String methodName() { return methodName; }
    public String descriptor() { return descriptor; }
    public long location() { return location; }
    public String registrationId() { return registrationId; }
    public long receiverId() { return receiverId; }
    public boolean objectSpecific() { return receiverId != 0L; }
    public String conditionSummary() { return conditionSummary; }
    public String id() { return registrationId; }
}
