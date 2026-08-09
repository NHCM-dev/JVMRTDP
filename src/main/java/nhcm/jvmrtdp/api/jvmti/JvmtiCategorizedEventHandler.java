package nhcm.jvmrtdp.api.jvmti;

/**
 * Convenience callback that routes the complete JVMTI event set into logical categories.
 * Override only the groups of interest; all methods are no-op by default.
 */
public interface JvmtiCategorizedEventHandler extends JvmtiEventHandler {
    @Override
    default void onEvent(JvmtiEvent event) throws Exception {
        switch (event.category()) {
        case VM: onVmEvent(event); break;
        case THREAD: onThreadEvent(event); break;
        case CLASS: onClassEvent(event); break;
        case EXECUTION: onExecutionEvent(event); break;
        case METHOD: onMethodEvent((JvmtiMethodEvent) event); break;
        case FIELD: onFieldEvent(event); break;
        case EXCEPTION: onExceptionEvent(event); break;
        case MONITOR: onMonitorEvent(event); break;
        case NATIVE_CODE: onNativeCodeEvent(event); break;
        case HEAP: onHeapEvent(event); break;
        case GARBAGE_COLLECTION: onGarbageCollectionEvent(event); break;
        case RESOURCE: onResourceEvent(event); break;
        default: throw new AssertionError(event.category());
        }
    }

    default void onVmEvent(JvmtiEvent event) throws Exception { }
    default void onThreadEvent(JvmtiEvent event) throws Exception { }
    default void onClassEvent(JvmtiEvent event) throws Exception { }
    default void onExecutionEvent(JvmtiEvent event) throws Exception { }
    default void onMethodEvent(JvmtiMethodEvent event) throws Exception { }
    default void onFieldEvent(JvmtiEvent event) throws Exception { }
    default void onExceptionEvent(JvmtiEvent event) throws Exception { }
    default void onMonitorEvent(JvmtiEvent event) throws Exception { }
    default void onNativeCodeEvent(JvmtiEvent event) throws Exception { }
    default void onHeapEvent(JvmtiEvent event) throws Exception { }
    default void onGarbageCollectionEvent(JvmtiEvent event) throws Exception { }
    default void onResourceEvent(JvmtiEvent event) throws Exception { }
}
