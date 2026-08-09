package nhcm.jvmrtdp.api.jvmti;

/** Implement this in deployed source or a JAR to receive selected JVMTI events. */
public interface JvmtiEventHandler {
    void onEvent(JvmtiEvent event) throws Exception;
}
