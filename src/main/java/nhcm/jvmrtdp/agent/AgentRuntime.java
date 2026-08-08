package nhcm.jvmrtdp.agent;

import nhcm.jvmrtdp.attach.AgentOptions;
import nhcm.jvmrtdp.handles.JRDHandle;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AgentRuntime {
    private static final Map<UUID, AgentServer> SERVERS = new ConcurrentHashMap<>();

    private AgentRuntime() {
    }

    public static void start(String encodedOptions) {
        AgentOptions options = AgentOptions.decode(encodedOptions);
        JRDHandle handle = new JRDHandle(UUID.randomUUID());
        AgentServer server = new AgentServer(handle, options, () -> SERVERS.remove(options.sessionId()));
        AgentServer existing = SERVERS.putIfAbsent(options.sessionId(), server);
        if (existing != null) {
            throw new IllegalStateException("JVMRTDP session is already running: " + options.sessionId());
        }
        try {
            server.start();
        } catch (IOException exception) {
            SERVERS.remove(options.sessionId(), server);
            throw new IllegalStateException("Cannot start JVMRTDP agent server", exception);
        }
    }
}
