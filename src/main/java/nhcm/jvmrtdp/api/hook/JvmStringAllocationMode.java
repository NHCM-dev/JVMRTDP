package nhcm.jvmrtdp.api.hook;

/** Coverage/performance trade-off for conditional String allocation hooks. */
public enum JvmStringAllocationMode {
    /**
     * Uses lightweight probes at {@code java.lang.String} constructor returns.
     * Content is prefiltered in the bootstrap bridge, and unrelated method exits and object
     * allocations do not enter a global JVMTI callback.
     */
    FAST,

    /**
     * Adds {@code VM_OBJECT_ALLOC} coverage for Strings produced without an observable
     * constructor return. This has JVM-wide allocation-event overhead while enabled.
     */
    COMPLETE;

    public static JvmStringAllocationMode parse(String value) {
        if (value == null) throw new IllegalArgumentException("mode must not be null");
        return valueOf(value.trim().replace('-', '_').toUpperCase(java.util.Locale.ROOT));
    }
}
