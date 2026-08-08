package nhcm.jvmrtdp.protocol;

import java.util.Objects;

public class CommandRequest {
    private final String commandLine;

    public CommandRequest(String commandLine) {
        this.commandLine = Objects.requireNonNull(commandLine, "commandLine");
    }

    public String commandLine() {
        return commandLine;
    }
}
