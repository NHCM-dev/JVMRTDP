package nhcm.jvmrtdp.api.jvmti;

/**
 * Transforms class-file bytes inside the target JVM during load, redefine, or retransform.
 * Implementations must be public, have a public no-argument constructor, and be registered for
 * {@link JvmtiEventType#CLASS_FILE_LOAD_HOOK}. Multiple transformers run in registration order;
 * each receives the previous transformer's bytes. HotSpot redefine schema restrictions still
 * apply.
 */
public interface JvmtiClassFileTransformer {
    /**
     * Returns null to keep the current bytes, or a complete verifier-valid replacement class
     * file. Transformer callbacks are synchronous because the bytes must be returned to JVMTI.
     */
    byte[] transform(JvmtiClassFileEvent event) throws Exception;
}
