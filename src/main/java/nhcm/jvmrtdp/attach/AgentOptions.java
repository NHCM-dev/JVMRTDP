package nhcm.jvmrtdp.attach;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class AgentOptions {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final int port;
    private final String token;
    private final UUID sessionId;

    public AgentOptions(int port, String token, UUID sessionId) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid loopback port: " + port);
        }
        this.port = port;
        this.token = Objects.requireNonNull(token, "token");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
    }

    public int port() {
        return port;
    }

    public String token() {
        return token;
    }

    public UUID sessionId() {
        return sessionId;
    }

    public String encode() {
        return "v=1;port=" + port
                + ";token=" + encodeString(token)
                + ";session=" + sessionId;
    }

    public static AgentOptions decode(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        Map<String, String> values = new HashMap<String, String>();
        for (String entry : encoded.split(";")) {
            int separator = entry.indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException("Malformed agent option: " + entry);
            }
            values.put(entry.substring(0, separator), entry.substring(separator + 1));
        }
        if (!"1".equals(values.get("v"))) {
            throw new IllegalArgumentException("Unsupported agent option version: " + values.get("v"));
        }
        return new AgentOptions(
                Integer.parseInt(require(values, "port")),
                decodeString(require(values, "token")),
                UUID.fromString(require(values, "session")));
    }

    private static String require(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing agent option: " + name);
        }
        return value;
    }

    private static String encodeString(String value) {
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeString(String value) {
        return new String(DECODER.decode(value), StandardCharsets.UTF_8);
    }
}
