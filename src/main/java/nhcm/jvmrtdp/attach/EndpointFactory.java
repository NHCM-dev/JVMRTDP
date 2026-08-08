package nhcm.jvmrtdp.attach;

import nhcm.jvmrtdp.throwble.InjectionException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

public class EndpointFactory {
    private static final SecureRandom RANDOM = new SecureRandom();

    private EndpointFactory() {
    }

    public static AgentOptions create(long targetPid) {
        byte[] tokenBytes = new byte[32];
        RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        return new AgentOptions(findAvailableLoopbackPort(), token, UUID.randomUUID());
    }

    private static int findAvailableLoopbackPort() {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new InjectionException("Cannot allocate a loopback port for JVMRTDP", exception);
        }
    }
}
