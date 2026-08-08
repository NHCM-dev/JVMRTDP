package nhcm.jvmrtdp.agent.command.builtin;

import nhcm.jvmrtdp.agent.command.RemoteCommand;
import nhcm.jvmrtdp.handles.JRDHandle;
import nhcm.jvmrtdp.protocol.CommandReply;

import java.util.List;

public class NativeCommand implements RemoteCommand {
    @Override
    public String name() {
        return "native";
    }

    @Override
    public String usage() {
        return "native";
    }

    @Override
    public String description() {
        return "Shows the target-side JNI/JVMTI bridge status.";
    }

    @Override
    public CommandReply execute(JRDHandle handle, List<String> arguments) {
        return new CommandReply(handle.nativeRuntime().available(), handle.nativeRuntime().describe());
    }
}
