package nhcm.jvmrtdp.api.jvmti;

/** Controller-managed event breakpoint installed in the target JVM. */
public final class JvmEventBreakpointInfo {
    private final String id;
    private final JvmEventBreakpointSpec spec;

    public JvmEventBreakpointInfo(String id, JvmEventBreakpointSpec spec) {
        this.id = id;
        this.spec = spec;
    }

    public String id() { return id; }
    public JvmEventBreakpointSpec spec() { return spec; }

    @Override public String toString() {
        return spec.kind() + " " + spec.classPattern() + "." + spec.methodPattern()
                + spec.descriptorPattern() + (spec.includeSubtypes() ? " (including subtypes)" : "");
    }
}
