package nhcm.jvmrtdp.api.jvmti;

import java.util.Locale;

/** JVMTI events that can be enabled for Java callbacks. */
public enum JvmtiEventType {
    VM_INIT(JvmtiEventCategory.VM, null),
    VM_DEATH(JvmtiEventCategory.VM, null),
    THREAD_START(JvmtiEventCategory.THREAD, null),
    THREAD_END(JvmtiEventCategory.THREAD, null),
    CLASS_LOAD(JvmtiEventCategory.CLASS, null),
    CLASS_PREPARE(JvmtiEventCategory.CLASS, null),
    CLASS_FILE_LOAD_HOOK(JvmtiEventCategory.CLASS, null),
    VM_START(JvmtiEventCategory.VM, null),
    SINGLE_STEP(JvmtiEventCategory.EXECUTION, JvmtiCapability.CAN_GENERATE_SINGLE_STEP_EVENTS),
    FRAME_POP(JvmtiEventCategory.EXECUTION, JvmtiCapability.CAN_GENERATE_FRAME_POP_EVENTS),
    BREAKPOINT(JvmtiEventCategory.EXECUTION, JvmtiCapability.CAN_GENERATE_BREAKPOINT_EVENTS),
    FIELD_ACCESS(JvmtiEventCategory.FIELD, JvmtiCapability.CAN_GENERATE_FIELD_ACCESS_EVENTS),
    FIELD_MODIFICATION(JvmtiEventCategory.FIELD, JvmtiCapability.CAN_GENERATE_FIELD_MODIFICATION_EVENTS),
    METHOD_ENTRY(JvmtiEventCategory.METHOD, JvmtiCapability.CAN_GENERATE_METHOD_ENTRY_EVENTS),
    METHOD_EXIT(JvmtiEventCategory.METHOD, JvmtiCapability.CAN_GENERATE_METHOD_EXIT_EVENTS),
    EXCEPTION(JvmtiEventCategory.EXCEPTION, JvmtiCapability.CAN_GENERATE_EXCEPTION_EVENTS),
    EXCEPTION_CATCH(JvmtiEventCategory.EXCEPTION, JvmtiCapability.CAN_GENERATE_EXCEPTION_EVENTS),
    NATIVE_METHOD_BIND(JvmtiEventCategory.NATIVE_CODE, JvmtiCapability.CAN_GENERATE_NATIVE_METHOD_BIND_EVENTS),
    COMPILED_METHOD_LOAD(JvmtiEventCategory.NATIVE_CODE, JvmtiCapability.CAN_GENERATE_COMPILED_METHOD_LOAD_EVENTS),
    COMPILED_METHOD_UNLOAD(JvmtiEventCategory.NATIVE_CODE, JvmtiCapability.CAN_GENERATE_COMPILED_METHOD_LOAD_EVENTS),
    DYNAMIC_CODE_GENERATED(JvmtiEventCategory.NATIVE_CODE, null),
    DATA_DUMP_REQUEST(JvmtiEventCategory.RESOURCE, null),
    MONITOR_CONTENDED_ENTER(JvmtiEventCategory.MONITOR, JvmtiCapability.CAN_GENERATE_MONITOR_EVENTS),
    MONITOR_CONTENDED_ENTERED(JvmtiEventCategory.MONITOR, JvmtiCapability.CAN_GENERATE_MONITOR_EVENTS),
    MONITOR_WAIT(JvmtiEventCategory.MONITOR, JvmtiCapability.CAN_GENERATE_MONITOR_EVENTS),
    MONITOR_WAITED(JvmtiEventCategory.MONITOR, JvmtiCapability.CAN_GENERATE_MONITOR_EVENTS),
    VM_OBJECT_ALLOC(JvmtiEventCategory.HEAP, JvmtiCapability.CAN_GENERATE_VM_OBJECT_ALLOC_EVENTS),
    GARBAGE_COLLECTION_START(JvmtiEventCategory.GARBAGE_COLLECTION, JvmtiCapability.CAN_GENERATE_GARBAGE_COLLECTION_EVENTS),
    GARBAGE_COLLECTION_FINISH(JvmtiEventCategory.GARBAGE_COLLECTION, JvmtiCapability.CAN_GENERATE_GARBAGE_COLLECTION_EVENTS),
    OBJECT_FREE(JvmtiEventCategory.HEAP, JvmtiCapability.CAN_GENERATE_OBJECT_FREE_EVENTS),
    RESOURCE_EXHAUSTED(JvmtiEventCategory.RESOURCE, null);

    private final JvmtiEventCategory category;
    private final JvmtiCapability requiredCapability;

    JvmtiEventType(JvmtiEventCategory category, JvmtiCapability requiredCapability) {
        this.category = category;
        this.requiredCapability = requiredCapability;
    }

    public JvmtiEventCategory category() { return category; }
    public JvmtiCapability requiredCapability() { return requiredCapability; }

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static JvmtiEventType parse(String value) {
        if (value == null) throw new IllegalArgumentException("Event name must not be null");
        return valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
    }
}
