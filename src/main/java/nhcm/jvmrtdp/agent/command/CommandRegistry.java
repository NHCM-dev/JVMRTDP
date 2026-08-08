package nhcm.jvmrtdp.agent.command;

import nhcm.jvmrtdp.agent.command.builtin.EchoCommand;
import nhcm.jvmrtdp.agent.command.builtin.HelpCommand;
import nhcm.jvmrtdp.agent.command.builtin.InfoCommand;
import nhcm.jvmrtdp.agent.command.builtin.NativeCommand;
import nhcm.jvmrtdp.agent.command.builtin.PingCommand;
import nhcm.jvmrtdp.agent.command.builtin.JniCommand;
import nhcm.jvmrtdp.agent.command.builtin.JvmtiCommand;
import nhcm.jvmrtdp.agent.command.builtin.ObjectCommand;
import nhcm.jvmrtdp.agent.command.builtin.BatchCommand;
import nhcm.jvmrtdp.command.CommandLine;
import nhcm.jvmrtdp.handles.JRDHandle;
import nhcm.jvmrtdp.protocol.CommandReply;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public class CommandRegistry {
    private final JRDHandle handle;
    private final Map<String, RemoteCommand> commands = new LinkedHashMap<>();

    public CommandRegistry(JRDHandle handle) {
        this.handle = handle;
        register(new PingCommand());
        register(new InfoCommand());
        register(new EchoCommand());
        register(new NativeCommand());
        register(new JvmtiCommand());
        register(new JniCommand());
        register(new ObjectCommand());
        register(new BatchCommand(this::execute));
        register(new HelpCommand(this::commands));
    }

    public CommandReply execute(String rawCommandLine) {
        CommandLine commandLine = CommandLine.parse(rawCommandLine);
        if (commandLine.name().isEmpty()) {
            return new CommandReply(true, "");
        }
        RemoteCommand command = commands.get(commandLine.name());
        if (command == null) {
            return new CommandReply(false, "Unknown command: " + commandLine.name() + ". Use 'help'.");
        }
        return command.execute(handle, commandLine.arguments());
    }

    public Collection<RemoteCommand> commands() {
        return java.util.Collections.unmodifiableList(new java.util.ArrayList<RemoteCommand>(commands.values()));
    }

    private void register(RemoteCommand command) {
        RemoteCommand previous = commands.putIfAbsent(command.name().toLowerCase(java.util.Locale.ROOT), command);
        if (previous != null) {
            throw new IllegalArgumentException("Duplicate command: " + command.name());
        }
    }
}
