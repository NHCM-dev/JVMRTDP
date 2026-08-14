import nhcm.jvmrtdp.api.JvmProcessInfo;
import nhcm.jvmrtdp.api.JvmRtdpClient;
import nhcm.jvmrtdp.api.JvmRtdpCommandResult;
import nhcm.jvmrtdp.api.JvmRtdpSession;

/** Minimal process discovery and attach example for the JVMRTDP Java API. */
public final class LibraryExample {
    private LibraryExample() {
    }

    public static void main(String[] arguments) {
        try (JvmRtdpClient client = JvmRtdpClient.open()) {
            if (arguments.length == 0) {
                for (JvmProcessInfo process : client.processes()) {
                    System.out.printf("%d  %s  %s%n",
                            process.pid(), process.architecture(), process.displayName());
                }
                return;
            }

            long pid = Long.parseLong(arguments[0]);
            try (JvmRtdpSession session = client.attach(pid)) {
                JvmRtdpCommandResult result = session.execute("jvmti phase").requireSuccess();
                System.out.print(result.standardOutput());
                System.out.printf("Tracked references: %d; String hooks: %d%n",
                        session.references().snapshot().size(),
                        session.stringHooks().snapshot().size());
            }
        }
    }
}
