package nhcm.jvmrtdp.api.reference;

/** Immutable inspection row returned by {@link JvmReferenceManager}. */
public final class JvmReferenceInfo {
    private final String name;
    private final JvmReferenceKind kind;
    private final JvmReferenceStrength strength;
    private final JvmReferenceState state;
    private final long remoteId;
    private final String className;
    private final String displayValue;
    private final String source;
    private final boolean assignable;
    private final String error;

    public JvmReferenceInfo(String name, JvmReferenceKind kind,
            JvmReferenceStrength strength, JvmReferenceState state, long remoteId,
            String className, String displayValue, String source,
            boolean assignable, String error) {
        this.name = name;
        this.kind = kind;
        this.strength = strength;
        this.state = state;
        this.remoteId = remoteId;
        this.className = className;
        this.displayValue = displayValue;
        this.source = source;
        this.assignable = assignable;
        this.error = error;
    }

    public String name() { return name; }
    public JvmReferenceKind kind() { return kind; }
    public JvmReferenceStrength strength() { return strength; }
    public JvmReferenceState state() { return state; }
    public long remoteId() { return remoteId; }
    public String className() { return className; }
    public String displayValue() { return displayValue; }
    public String source() { return source; }
    public boolean assignable() { return assignable; }
    public String error() { return error; }

    @Override public String toString() {
        return name + " [" + state + ", " + strength + ", " + kind + "] "
                + (state == JvmReferenceState.LIVE ? className + "#" + remoteId
                        + "(" + displayValue + ")" : state == JvmReferenceState.NULL ? "null"
                        : error.isEmpty() ? state.name().toLowerCase() : error);
    }
}
