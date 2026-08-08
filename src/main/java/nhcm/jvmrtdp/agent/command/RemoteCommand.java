package nhcm.jvmrtdp.agent.command;

import nhcm.jvmrtdp.handles.JRDHandle;
import nhcm.jvmrtdp.protocol.CommandReply;

import java.util.List;

public interface RemoteCommand {
    String name();

    String usage();

    String description();

    CommandReply execute(JRDHandle handle, List<String> arguments);
}
