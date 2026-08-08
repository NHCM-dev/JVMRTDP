package nhcm.jvmrtdp.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class BatchCodec {
    public static final int MAX_COMMANDS = 128;

    private BatchCodec() {
    }

    public static String encodeRequests(List<String> commands) {
        if (commands.isEmpty() || commands.size() > MAX_COMMANDS) {
            throw new IllegalArgumentException("Batch size must be between 1 and " + MAX_COMMANDS);
        }
        StringBuilder payload = new StringBuilder();
        for (String command : commands) {
            if (command == null || command.trim().isEmpty()) {
                throw new IllegalArgumentException("Batch commands must not be empty");
            }
            if (command.indexOf('\0') >= 0) throw new IllegalArgumentException("Batch command contains NUL");
            if (payload.length() != 0) payload.append('\0');
            payload.append(command);
        }
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static List<String> decodeRequests(String encoded) {
        String payload = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        String[] commands = payload.split("\\x00", -1);
        if (commands.length == 0 || commands.length > MAX_COMMANDS) {
            throw new IllegalArgumentException("Invalid batch command count: " + commands.length);
        }
        List<String> result = new ArrayList<String>(commands.length);
        java.util.Collections.addAll(result, commands);
        return result;
    }

    public static String encodeReplies(List<CommandReply> replies) {
        StringBuilder result = new StringBuilder();
        for (CommandReply reply : replies) {
            if (result.length() != 0) result.append('\n');
            result.append(TextWireCodec.encode(Boolean.toString(reply.successful()), reply.output()));
        }
        return result.toString();
    }

    public static List<CommandReply> decodeReplies(String encoded) {
        if (encoded.isEmpty()) return java.util.Collections.emptyList();
        List<CommandReply> result = new ArrayList<CommandReply>();
        for (String row : encoded.split("\\r?\\n")) {
            List<String> fields = TextWireCodec.decode(row, 2);
            result.add(new CommandReply(Boolean.parseBoolean(fields.get(0)), fields.get(1)));
        }
        return result;
    }
}
