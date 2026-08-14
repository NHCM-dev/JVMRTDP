package nhcm.jvmrtdp.api.jvmti;

/**
 * Receives selected JVMTI events inside the target JVM.
 *
 * <p>The implementation is deployed with {@code JvmInstrumentation.deploySource},
 * {@code deployClasses}, or {@code addJar}, then registered with
 * {@code JvmInstrumentation.hook}. It must be public and have a public no-argument
 * constructor. Event objects and their subjects are ordinary objects in the target JVM;
 * they are not controller-side {@code RemoteObject} handles.</p>
 *
 * <p>Asynchronous delivery is recommended for telemetry and uses a bounded dispatcher queue.
 * Synchronous delivery runs before the native callback returns and must not block, acquire
 * application locks, or recursively produce an unbounded stream of the same event. Exceptions
 * are recorded in callback statistics rather than thrown into the target application. Close the
 * returned {@code RemoteJvmtiCallback} to unregister the handler.</p>
 *
 * @see JvmtiCategorizedEventHandler
 * @see JvmtiMethodEvent
 * @see JvmtiEventType
 */
public interface JvmtiEventHandler {
    /** Handles one enabled event. Accessors not applicable to its type return null or zero. */
    void onEvent(JvmtiEvent event) throws Exception;
}
