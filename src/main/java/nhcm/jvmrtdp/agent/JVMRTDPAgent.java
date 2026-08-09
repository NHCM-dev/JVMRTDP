package nhcm.jvmrtdp.agent;

public class JVMRTDPAgent {
    private JVMRTDPAgent() {
    }

    /** Entry used by the native manual-map bootstrap. */
    public static void bootstrap(String options) {
        AgentRuntime.start(options);
    }
}
