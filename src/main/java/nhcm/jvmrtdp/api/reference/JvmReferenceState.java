package nhcm.jvmrtdp.api.reference;

/** Current state of a controller-managed reference. */
public enum JvmReferenceState {
    LIVE,
    NULL,
    COLLECTED,
    RELEASED,
    ERROR
}
