package nhcm.jvmrtdp.agent.command.builtin;

import nhcm.jvmrtdp.agent.command.RemoteCommand;
import nhcm.jvmrtdp.handles.JRDHandle;
import nhcm.jvmrtdp.protocol.CommandReply;

import java.util.List;

public final class JniCommand implements RemoteCommand {
    @Override
    public String name() {
        return "jni";
    }

    @Override
    public String usage() {
        return "jni fields <class> | jni get <class> <field> | "
                + "jni call <class> <method> <descriptor> [args ...]";
    }

    @Override
    public String description() {
        return "Reads static fields or invokes a static method through JNI.";
    }

    @Override
    public CommandReply execute(JRDHandle handle, List<String> arguments) {
        if (arguments.size() == 2 && "fields".equalsIgnoreCase(arguments.get(0))) {
            return new CommandReply(true, handle.targetJvm().readStaticFields(arguments.get(1)));
        }
        if (arguments.size() == 3 && "get".equalsIgnoreCase(arguments.get(0))) {
            return new CommandReply(true,
                    handle.targetJvm().readStaticField(arguments.get(1), arguments.get(2)));
        }
        if (arguments.size() >= 4 && "call".equalsIgnoreCase(arguments.get(0))) {
            String[] methodArguments = arguments.subList(4, arguments.size()).toArray(new String[0]);
            return new CommandReply(true, handle.targetJvm().callStaticMethod(
                    arguments.get(1), arguments.get(2), arguments.get(3), methodArguments));
        }
        return new CommandReply(false, "Usage: " + usage());
    }
}
