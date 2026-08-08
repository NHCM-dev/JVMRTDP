package nhcm.jvmrtdp.protocol;

import java.util.Arrays;
import java.util.Objects;

public class Frame {
    private final MessageType type;
    private final long requestId;
    private final byte[] payload;

    public Frame(MessageType type, long requestId, byte[] payload) {
        this.type = Objects.requireNonNull(type, "type");
        this.requestId = requestId;
        this.payload = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
        if (this.payload.length > Protocol.MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Payload exceeds protocol limit: " + this.payload.length);
        }
    }

    public MessageType type() {
        return type;
    }

    public long requestId() {
        return requestId;
    }

    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
