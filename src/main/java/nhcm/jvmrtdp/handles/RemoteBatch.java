package nhcm.jvmrtdp.handles;

import nhcm.jvmrtdp.protocol.CommandReply;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RemoteBatch {
    private final ServerHandle server;
    private final List<String> commands = new ArrayList<String>();

    RemoteBatch(ServerHandle server) {
        this.server = server;
    }

    public RemoteBatch add(String commandLine) {
        commands.add(commandLine);
        return this;
    }

    public List<String> commands() {
        return Collections.unmodifiableList(commands);
    }

    public List<CommandReply> execute() {
        return server.executeBatch(commands);
    }
}
