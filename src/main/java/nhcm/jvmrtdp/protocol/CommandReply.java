package nhcm.jvmrtdp.protocol;

import java.util.Objects;

public class CommandReply {
    private final boolean successful;
    private final String output;

    public CommandReply(boolean successful, String output) {
        this.successful = successful;
        this.output = Objects.requireNonNull(output, "output");
    }

    public boolean successful() { return successful; }
    public String output() { return output; }
}
