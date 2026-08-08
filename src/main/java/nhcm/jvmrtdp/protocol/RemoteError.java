package nhcm.jvmrtdp.protocol;

import java.util.Objects;

public class RemoteError {
    private final String code;
    private final String message;

    public RemoteError(String code, String message) {
        this.code = Objects.requireNonNull(code, "code");
        this.message = Objects.requireNonNull(message, "message");
    }

    public String code() { return code; }
    public String message() { return message; }
}
