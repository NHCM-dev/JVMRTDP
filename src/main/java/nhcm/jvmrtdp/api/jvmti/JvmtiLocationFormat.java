package nhcm.jvmrtdp.api.jvmti;

/** Method-location representation reported by GetJLocationFormat. */
public enum JvmtiLocationFormat {
    OTHER(0),
    JVM_BCI(1),
    MACHINE_PC(2);

    private final int nativeValue;

    JvmtiLocationFormat(int nativeValue) {
        this.nativeValue = nativeValue;
    }

    public int nativeValue() {
        return nativeValue;
    }

    public static JvmtiLocationFormat fromNativeValue(int value) {
        for (JvmtiLocationFormat format : values()) {
            if (format.nativeValue == value) return format;
        }
        throw new IllegalArgumentException("Unknown JVMTI location format: " + value);
    }
}
