package nhcm.jvmrtdp.api.jvmti;

/** Controller-managed persistent bytecode breakpoint shared by CLI and TUI. */
public final class JvmBreakpointInfo {
    private final String className;
    private final String methodName;
    private final String descriptor;
    private final long location;

    public JvmBreakpointInfo(String className, String methodName, String descriptor, long location) {
        this.className = className;
        this.methodName = methodName;
        this.descriptor = descriptor;
        this.location = location;
    }

    public String className() { return className; }
    public String methodName() { return methodName; }
    public String descriptor() { return descriptor; }
    public long location() { return location; }
    public String id() { return className + '|' + methodName + '|' + descriptor + '|' + location; }
}
