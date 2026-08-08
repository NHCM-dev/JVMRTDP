package nhcm.jvmrtdp.agent.command.builtin;

import nhcm.jvmrtdp.agent.command.RemoteCommand;
import nhcm.jvmrtdp.handles.JRDHandle;
import nhcm.jvmrtdp.protocol.CommandReply;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class HelpCommand implements RemoteCommand {
    private final Supplier<Collection<RemoteCommand>> commands;

    public HelpCommand(Supplier<Collection<RemoteCommand>> commands) {
        this.commands = commands;
    }

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String usage() {
        return "help [command]";
    }

    @Override
    public String description() {
        return "Lists commands supported by this target agent.";
    }

    @Override
    public CommandReply execute(JRDHandle handle, List<String> arguments) {
        if (arguments.size() > 1) {
            return new CommandReply(false, "Usage: " + usage());
        }
        if (arguments.size() == 1) {
            String requested = arguments.get(0);
            for (RemoteCommand command : commands.get()) {
                if (command.name().equalsIgnoreCase(requested)) {
                    return new CommandReply(true,
                            "Usage: " + command.usage() + System.lineSeparator() + command.description());
                }
            }
            return new CommandReply(false, "Unknown command: " + requested);
        }
        String output = commands.get().stream()
                .sorted(Comparator.comparing(RemoteCommand::name))
                .map(command -> String.format("%-24s %s", command.usage(), command.description()))
                .collect(Collectors.joining(System.lineSeparator()));
        return new CommandReply(true, output);
    }
}
