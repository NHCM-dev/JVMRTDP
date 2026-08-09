package nhcm.jvmrtdp.api.jvmti;

import java.util.Locale;

/** JVMTI events that can be enabled for Java callbacks. */
public enum JvmtiEventType {
    VM_INIT,
    VM_DEATH,
    THREAD_START,
    THREAD_END,
    CLASS_LOAD,
    CLASS_PREPARE,
    CLASS_FILE_LOAD_HOOK,
    VM_START,
    SINGLE_STEP,
    FRAME_POP,
    BREAKPOINT,
    FIELD_ACCESS,
    FIELD_MODIFICATION,
    METHOD_ENTRY,
    METHOD_EXIT,
    EXCEPTION,
    EXCEPTION_CATCH,
    NATIVE_METHOD_BIND,
    COMPILED_METHOD_LOAD,
    COMPILED_METHOD_UNLOAD,
    DYNAMIC_CODE_GENERATED,
    DATA_DUMP_REQUEST,
    MONITOR_CONTENDED_ENTER,
    MONITOR_CONTENDED_ENTERED,
    MONITOR_WAIT,
    MONITOR_WAITED,
    VM_OBJECT_ALLOC,
    GARBAGE_COLLECTION_START,
    GARBAGE_COLLECTION_FINISH,
    OBJECT_FREE,
    RESOURCE_EXHAUSTED;

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static JvmtiEventType parse(String value) {
        if (value == null) throw new IllegalArgumentException("Event name must not be null");
        return valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
    }
}
