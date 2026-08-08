package nhcm.jvmrtdp.protocol;

import java.util.Objects;

public class HelloMessage {
    private final String token;
    private final long controllerPid;
    private final String controllerVersion;

    public HelloMessage(String token, long controllerPid, String controllerVersion) {
        this.token = Objects.requireNonNull(token, "token");
        this.controllerPid = controllerPid;
        this.controllerVersion = Objects.requireNonNull(controllerVersion, "controllerVersion");
    }

    public String token() {
        return token;
    }

    public long controllerPid() {
        return controllerPid;
    }

    public String controllerVersion() {
        return controllerVersion;
    }
}
