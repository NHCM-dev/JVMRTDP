package nhcm.jvmrtdp.agent.command.builtin;

import nhcm.jvmrtdp.agent.command.RemoteCommand;
import nhcm.jvmrtdp.handles.JRDHandle;
import nhcm.jvmrtdp.protocol.CommandReply;

import java.util.List;

public class InfoCommand implements RemoteCommand {
    @Override
    public String name() {
        return "info";
    }

    @Override
    public String usage() {
        return "info";
    }

    @Override
    public String description() {
        return "Shows target JVM and injected runtime information.";
    }

    @Override
    public CommandReply execute(JRDHandle handle, List<String> arguments) {
        return new CommandReply(true, handle.describe());
    }
}
