package nhcm.jvmrtdp.api.jvmti;

/** Logical callback groups used by {@link JvmtiCategorizedEventHandler}. */
public enum JvmtiEventCategory {
    VM,
    THREAD,
    CLASS,
    EXECUTION,
    METHOD,
    FIELD,
    EXCEPTION,
    MONITOR,
    NATIVE_CODE,
    HEAP,
    GARBAGE_COLLECTION,
    RESOURCE
}
