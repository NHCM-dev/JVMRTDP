package nhcm.jvmrtdp.localside.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ShellCommandRegistry<C> {
    private final Map<String, ShellCommand<C>> byName = new LinkedHashMap<String, ShellCommand<C>>();
    private final List<ShellCommand<C>> commands = new ArrayList<ShellCommand<C>>();

    public ShellCommandRegistry<C> register(ShellCommand<C> command) {
        put(command.name(), command);
        for (String alias : command.aliases()) {
            put(alias, command);
        }
        commands.add(command);
        return this;
    }

    public ShellCommand<C> find(String name) {
        return byName.get(name.toLowerCase(Locale.ROOT));
    }

    public Collection<ShellCommand<C>> commands() {
        return Collections.unmodifiableList(commands);
    }

    private void put(String name, ShellCommand<C> command) {
        String normalized = name.toLowerCase(Locale.ROOT);
        if (byName.put(normalized, command) != null) {
            throw new IllegalArgumentException("Duplicate shell command: " + name);
        }
    }
}
