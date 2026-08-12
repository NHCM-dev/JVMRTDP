package nhcm.jvmrtdp.api.jvmti;

/** A JVMTI execution event that can suspend the event thread. */
public enum JvmEventBreakpointKind {
    METHOD_ENTRY("entry"),
    METHOD_EXIT("exit"),
    EXCEPTION_THROW("exception");

    private final String wireName;

    JvmEventBreakpointKind(String wireName) { this.wireName = wireName; }

    public String wireName() { return wireName; }
}
