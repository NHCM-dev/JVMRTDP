package nhcm.jvmrtdp.agent.command.builtin;

import nhcm.jvmrtdp.agent.command.RemoteCommand;
import nhcm.jvmrtdp.handles.JRDHandle;
import nhcm.jvmrtdp.protocol.CommandReply;

import java.util.List;

public class EchoCommand implements RemoteCommand {
    @Override
    public String name() {
        return "echo";
    }

    @Override
    public String usage() {
        return "echo [text ...]";
    }

    @Override
    public String description() {
        return "Returns its arguments to verify request and response payloads.";
    }

    @Override
    public CommandReply execute(JRDHandle handle, List<String> arguments) {
        return new CommandReply(true, String.join(" ", arguments));
    }
}
