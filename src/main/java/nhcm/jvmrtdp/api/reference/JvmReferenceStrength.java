package nhcm.jvmrtdp.api.reference;

/** Lifetime policy for a tracked target-JVM object. */
public enum JvmReferenceStrength {
    /** Keeps the target object reachable until the tracked reference is released. */
    STRONG,
    /** Allows normal garbage collection and reports {@link JvmReferenceState#COLLECTED}. */
    WEAK
}
