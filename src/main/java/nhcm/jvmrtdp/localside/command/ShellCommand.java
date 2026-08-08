package nhcm.jvmrtdp.localside.command;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** One controller-side command with its presentation and execution kept together. */
public abstract class ShellCommand<C> {
    private final String name;
    private final String usage;
    private final String description;
    private final List<String> aliases;

    protected ShellCommand(String name, String usage, String description, String... aliases) {
        this.name = Objects.requireNonNull(name, "name");
        this.usage = Objects.requireNonNull(usage, "usage");
        this.description = Objects.requireNonNull(description, "description");
        this.aliases = Collections.unmodifiableList(Arrays.asList(aliases.clone()));
    }

    public final String name() {
        return name;
    }

    public final String usage() {
        return usage;
    }

    public final String description() {
        return description;
    }

    public final List<String> aliases() {
        return aliases;
    }

    /** Returns false when the enclosing prompt should close. */
    public abstract boolean execute(C context, List<String> arguments) throws Exception;
}
