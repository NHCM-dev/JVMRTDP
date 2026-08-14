package nhcm.jvmrtdp.api.hook;

/** Supported precise String-related stop points. */
public enum JvmStringHookKind {
    ALLOCATION,
    FIELD_READ,
    FIELD_WRITE,
    METHOD_ENTRY,
    METHOD_EXIT
}
