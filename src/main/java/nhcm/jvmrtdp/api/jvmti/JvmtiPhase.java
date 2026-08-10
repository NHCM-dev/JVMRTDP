package nhcm.jvmrtdp.api.jvmti;

/** VM phases defined by jvmtiPhase in jvmti.h. */
public enum JvmtiPhase {
    ONLOAD(1),
    PRIMORDIAL(2),
    LIVE(4),
    START(6),
    DEAD(8);

    private final int nativeValue;

    JvmtiPhase(int nativeValue) {
        this.nativeValue = nativeValue;
    }

    public int nativeValue() {
        return nativeValue;
    }

    public static JvmtiPhase fromNativeValue(int value) {
        for (JvmtiPhase phase : values()) {
            if (phase.nativeValue == value) return phase;
        }
        throw new IllegalArgumentException("Unknown JVMTI phase: " + value);
    }
}
