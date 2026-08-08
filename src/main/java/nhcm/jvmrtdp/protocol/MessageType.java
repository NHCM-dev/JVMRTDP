package nhcm.jvmrtdp.protocol;

import java.io.IOException;

public enum MessageType {
    HELLO(1),
    HELLO_ACK(2),
    REQUEST(3),
    RESPONSE(4),
    ERROR(5),
    PING(6),
    PONG(7),
    CLOSE(8);

    private final int code;

    MessageType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static MessageType fromCode(int code) throws IOException {
        for (MessageType value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IOException("Unknown JVMRTDP message type: " + code);
    }
}
