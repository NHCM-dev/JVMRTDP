package nhcm.jvmrtdp.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Small, dependency-free command line parser shared by both sides. */
public class CommandLine {
    private final String name;
    private final List<String> arguments;

    public CommandLine(String name, List<String> arguments) {
        this.name = name;
        this.arguments = Collections.unmodifiableList(new ArrayList<String>(arguments));
    }

    public String name() {
        return name;
    }

    public List<String> arguments() {
        return arguments;
    }

    public static CommandLine parse(String line) {
        if (line == null) {
            throw new IllegalArgumentException("Command line must not be null");
        }
        List<String> tokens = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean tokenStarted = false;

        for (int index = 0; index < line.length(); index++) {
            char value = line.charAt(index);
            if (value == '"') {
                quoted = !quoted;
                tokenStarted = true;
            } else if (value == '\\' && index + 1 < line.length()
                    && (line.charAt(index + 1) == '"' || line.charAt(index + 1) == '\\')) {
                current.append(line.charAt(++index));
                tokenStarted = true;
            } else if (Character.isWhitespace(value) && !quoted) {
                if (tokenStarted) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    tokenStarted = false;
                }
            } else {
                current.append(value);
                tokenStarted = true;
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("Unclosed quote in command line");
        }
        if (tokenStarted) {
            tokens.add(current.toString());
        }
        if (tokens.isEmpty()) {
            return new CommandLine("", Collections.<String>emptyList());
        }
        return new CommandLine(tokens.get(0).toLowerCase(Locale.ROOT), tokens.subList(1, tokens.size()));
    }

    public static String of(String name, String... arguments) {
        StringBuilder result = new StringBuilder(quote(name));
        for (String argument : Arrays.asList(arguments)) {
            result.append(' ').append(quote(argument));
        }
        return result.toString();
    }

    public static String quote(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Command argument must not be null");
        }
        if (!value.isEmpty() && value.chars().noneMatch(character ->
                Character.isWhitespace(character) || character == '"' || character == '\\')) {
            return value;
        }
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
