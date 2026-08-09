package nhcm.jvmrtdp.agent.nativebridge;

/** Process-level native runtime metadata. Registered explicitly from JNI_OnLoad. */
public final class NativeRuntimeBridge {
    public static final String BINDING_CLASS =
            "nhcm/jvmrtdp/agent/nativebridge/NativeRuntimeBridge";

    private NativeRuntimeBridge() {
    }

    public static native String version();

    public static native int jvmtiVersion();
}
