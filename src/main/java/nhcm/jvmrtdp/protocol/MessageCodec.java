package nhcm.jvmrtdp.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class MessageCodec {
    private static final int MAX_STRING_BYTES = 8 * 1024 * 1024;

    public byte[] encodeHello(HelloMessage message) throws IOException {
        return encode(output -> {
            writeString(output, message.token());
            output.writeLong(message.controllerPid());
            writeString(output, message.controllerVersion());
        });
    }

    public HelloMessage decodeHello(byte[] payload) throws IOException {
        return decode(payload, input -> new HelloMessage(
                readString(input),
                input.readLong(),
                readString(input)));
    }

    public byte[] encodeHelloAck(HelloAckMessage message) throws IOException {
        return encode(output -> {
            output.writeLong(message.jrdHandleId().getMostSignificantBits());
            output.writeLong(message.jrdHandleId().getLeastSignificantBits());
            output.writeLong(message.targetPid());
            writeString(output, message.targetDisplayName());
            writeString(output, message.agentVersion());
            output.writeBoolean(message.nativeAvailable());
            writeString(output, message.nativeDescription());
        });
    }

    public HelloAckMessage decodeHelloAck(byte[] payload) throws IOException {
        return decode(payload, input -> new HelloAckMessage(
                new UUID(input.readLong(), input.readLong()),
                input.readLong(),
                readString(input),
                readString(input),
                input.readBoolean(),
                readString(input)));
    }

    public byte[] encodeCommandRequest(CommandRequest request) throws IOException {
        return encode(output -> writeString(output, request.commandLine()));
    }

    public CommandRequest decodeCommandRequest(byte[] payload) throws IOException {
        return decode(payload, input -> new CommandRequest(readString(input)));
    }

    public byte[] encodeCommandReply(CommandReply reply) throws IOException {
        return encode(output -> {
            output.writeBoolean(reply.successful());
            writeString(output, reply.output());
        });
    }

    public CommandReply decodeCommandReply(byte[] payload) throws IOException {
        return decode(payload, input -> new CommandReply(input.readBoolean(), readString(input)));
    }

    public byte[] encodeRemoteError(RemoteError error) throws IOException {
        return encode(output -> {
            writeString(output, error.code());
            writeString(output, error.message());
        });
    }

    public RemoteError decodeRemoteError(byte[] payload) throws IOException {
        return decode(payload, input -> new RemoteError(readString(input), readString(input)));
    }

    private static byte[] encode(Encoder encoder) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            encoder.encode(output);
        }
        return bytes.toByteArray();
    }

    private static <T> T decode(byte[] payload, Decoder<T> decoder) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            T result = decoder.decode(input);
            if (input.available() != 0) {
                throw new IOException("Unexpected trailing bytes in JVMRTDP payload");
            }
            return result;
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException("String exceeds JVMRTDP protocol limit: " + bytes.length);
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("Invalid JVMRTDP string length: " + length);
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface Encoder {
        void encode(DataOutputStream output) throws IOException;
    }

    @FunctionalInterface
    private interface Decoder<T> {
        T decode(DataInputStream input) throws IOException;
    }
}
