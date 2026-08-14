package nhcm.jvmrtdp.api.hook;

/** Immutable view of one managed String hook. */
public final class JvmStringHookInfo {
    private final String name;
    private final JvmStringHookKind kind;
    private final String className;
    private final String memberName;
    private final String descriptor;
    private final boolean objectSpecific;
    private final boolean enabled;
    private final long lastHitSequence;
    private final String lastHit;
    private final JvmStringAllocationSpec allocationSpec;
    private final long hitCount;
    private final String lastValue;

    public JvmStringHookInfo(String name, JvmStringHookKind kind, String className,
            String memberName, String descriptor, boolean objectSpecific,
            boolean enabled, long lastHitSequence, String lastHit) {
        this(name, kind, className, memberName, descriptor, objectSpecific,
                enabled, lastHitSequence, lastHit, null, 0L, "");
    }

    public JvmStringHookInfo(String name, JvmStringHookKind kind, String className,
            String memberName, String descriptor, boolean objectSpecific,
            boolean enabled, long lastHitSequence, String lastHit,
            JvmStringAllocationSpec allocationSpec, long hitCount, String lastValue) {
        this.name = name;
        this.kind = kind;
        this.className = className;
        this.memberName = memberName;
        this.descriptor = descriptor;
        this.objectSpecific = objectSpecific;
        this.enabled = enabled;
        this.lastHitSequence = lastHitSequence;
        this.lastHit = lastHit == null ? "" : lastHit;
        this.allocationSpec = allocationSpec;
        this.hitCount = hitCount;
        this.lastValue = lastValue == null ? "" : lastValue;
    }

    public String name() { return name; }
    public JvmStringHookKind kind() { return kind; }
    public String className() { return className; }
    public String memberName() { return memberName; }
    public String descriptor() { return descriptor; }
    public boolean objectSpecific() { return objectSpecific; }
    public boolean enabled() { return enabled; }
    public long lastHitSequence() { return lastHitSequence; }
    public String lastHit() { return lastHit; }
    public JvmStringAllocationSpec allocationSpec() { return allocationSpec; }
    public long hitCount() { return hitCount; }
    public String lastValue() { return lastValue; }
    public boolean allocationHook() { return kind == JvmStringHookKind.ALLOCATION; }
    public boolean fieldHook() {
        return kind == JvmStringHookKind.FIELD_READ || kind == JvmStringHookKind.FIELD_WRITE;
    }

    @Override public String toString() {
        return name + " [" + (enabled ? "ON" : "off") + "] " + kind + " "
                + className + "." + memberName + descriptor
                + (objectSpecific ? " [object]" : "")
                + (allocationSpec == null ? "" : " [" + allocationSpec.summary() + "]")
                + (hitCount == 0L ? "" : " hits=" + hitCount)
                + (lastHit.isEmpty() ? "" : " last=" + lastHit);
    }
}
