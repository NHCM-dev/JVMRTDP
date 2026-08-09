package nhcm.jvmrtdp.api.jvmti;

/** Implemented by deployed Java code that transforms class bytes in a JVMTI hook. */
public interface JvmtiClassFileTransformer {
    /** Return null to keep the original bytes, or a complete replacement class file. */
    byte[] transform(JvmtiClassFileEvent event) throws Exception;
}
