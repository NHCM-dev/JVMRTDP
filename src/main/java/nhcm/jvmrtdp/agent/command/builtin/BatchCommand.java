package nhcm.jvmrtdp.agent.command.builtin;

import nhcm.jvmrtdp.agent.command.RemoteCommand;
import nhcm.jvmrtdp.handles.JRDHandle;
import nhcm.jvmrtdp.protocol.BatchCodec;
import nhcm.jvmrtdp.protocol.CommandReply;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public class BatchCommand implements RemoteCommand {
    private final Function<String, CommandReply> executor;

    public BatchCommand(Function<String, CommandReply> executor) {
        this.executor = executor;
    }

    public String name() { return "batch"; }
    public String usage() { return "batch <encoded-commands>"; }
    public String description() { return "Executes up to 128 commands in one transport request."; }

    public CommandReply execute(JRDHandle handle, List<String> arguments) {
        if (arguments.size() != 1) return new CommandReply(false, "Usage: " + usage());
        List<CommandReply> replies = new ArrayList<CommandReply>();
        for (String command : BatchCodec.decodeRequests(arguments.get(0))) {
            if (command.trim().toLowerCase(java.util.Locale.ROOT).startsWith("batch ")) {
                replies.add(new CommandReply(false, "Nested batches are not supported"));
                continue;
            }
            try {
                replies.add(executor.apply(command));
            } catch (RuntimeException failure) {
                replies.add(new CommandReply(false, failure.toString()));
            }
        }
        return new CommandReply(true, BatchCodec.encodeReplies(replies));
    }
}
