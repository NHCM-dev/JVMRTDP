package nhcm.jvmrtdp.agent.command.builtin;

import nhcm.jvmrtdp.agent.command.RemoteCommand;
import nhcm.jvmrtdp.handles.JRDHandle;
import nhcm.jvmrtdp.protocol.CommandReply;

import java.util.Base64;
import java.util.List;

public class JvmtiCommand implements RemoteCommand {
    @Override
    public String name() {
        return "jvmti";
    }

    @Override
    public String usage() {
        return "jvmti bytes <class>";
    }

    @Override
    public String description() {
        return "Returns the current class bytes through JVMTI retransformation.";
    }

    @Override
    public CommandReply execute(JRDHandle handle, List<String> arguments) {
        if (arguments.size() != 2 || !"bytes".equalsIgnoreCase(arguments.get(0))) {
            return new CommandReply(false, "Usage: " + usage());
        }
        byte[] bytes = handle.targetJvm().getClassBytes(arguments.get(1));
        return new CommandReply(true, Base64.getEncoder().encodeToString(bytes));
    }
}
