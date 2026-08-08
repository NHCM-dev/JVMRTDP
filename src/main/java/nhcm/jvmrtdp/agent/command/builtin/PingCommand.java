package nhcm.jvmrtdp.agent.command.builtin;

import nhcm.jvmrtdp.agent.command.RemoteCommand;
import nhcm.jvmrtdp.handles.JRDHandle;
import nhcm.jvmrtdp.protocol.CommandReply;

import java.util.List;

public class PingCommand implements RemoteCommand {
    @Override
    public String name() {
        return "ping";
    }

    @Override
    public String usage() {
        return "ping";
    }

    @Override
    public String description() {
        return "Checks that the target JVM command loop is responsive.";
    }

    @Override
    public CommandReply execute(JRDHandle handle, List<String> arguments) {
        return new CommandReply(true, "pong");
    }
}
