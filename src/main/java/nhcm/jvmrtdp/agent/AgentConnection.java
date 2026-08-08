package nhcm.jvmrtdp.agent;

import nhcm.jvmrtdp.agent.command.CommandRegistry;
import nhcm.jvmrtdp.attach.AgentOptions;
import nhcm.jvmrtdp.handles.JRDHandle;
import nhcm.jvmrtdp.protocol.CommandReply;
import nhcm.jvmrtdp.protocol.CommandRequest;
import nhcm.jvmrtdp.protocol.ChannelFrameCodec;
import nhcm.jvmrtdp.protocol.Frame;
import nhcm.jvmrtdp.protocol.HelloAckMessage;
import nhcm.jvmrtdp.protocol.HelloMessage;
import nhcm.jvmrtdp.protocol.MessageCodec;
import nhcm.jvmrtdp.protocol.MessageType;
import nhcm.jvmrtdp.protocol.RemoteError;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

public class AgentConnection implements AutoCloseable {
    private final SocketChannel channel;
    private final JRDHandle handle;
    private final AgentOptions options;
    private final CommandRegistry commands;
    private final ChannelFrameCodec frameCodec = new ChannelFrameCodec();
    private final MessageCodec messageCodec = new MessageCodec();

    public AgentConnection(
            SocketChannel channel,
            JRDHandle handle,
            AgentOptions options,
            CommandRegistry commands) throws IOException {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.handle = Objects.requireNonNull(handle, "handle");
        this.options = Objects.requireNonNull(options, "options");
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    public void run() throws IOException {
        if (!handshake()) {
            return;
        }
        while (channel.isOpen()) {
            Frame frame = frameCodec.read(channel);
            if (frame.type() == MessageType.CLOSE) {
                return;
            }
            if (frame.type() == MessageType.PING) {
                frameCodec.write(channel, new Frame(MessageType.PONG, frame.requestId(), new byte[0]));
                continue;
            }
            if (frame.type() != MessageType.REQUEST || frame.requestId() <= 0) {
                sendError(frame.requestId(), "INVALID_FRAME", "Expected a command request");
                continue;
            }
            execute(frame);
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    private boolean handshake() throws IOException {
        Frame frame = frameCodec.read(channel);
        if (frame.type() != MessageType.HELLO || frame.requestId() != 0) {
            sendError(0, "HANDSHAKE_REQUIRED", "The first frame must be HELLO");
            return false;
        }
        HelloMessage hello = messageCodec.decodeHello(frame.payload());
        if (!secureEquals(options.token(), hello.token())) {
            sendError(0, "AUTHENTICATION_FAILED", "Invalid session token");
            return false;
        }
        if (hello.controllerPid() <= 0) {
            sendError(0, "INVALID_CONTROLLER", "Controller process ID is invalid");
            return false;
        }

        String agentVersion = AgentConnection.class.getPackage().getImplementationVersion();
        if (agentVersion == null) {
            agentVersion = "development";
        }
        HelloAckMessage ack = new HelloAckMessage(
                handle.id(),
                handle.processId(),
                handle.displayName(),
                agentVersion,
                handle.nativeRuntime().available(),
                handle.nativeRuntime().describe());
        frameCodec.write(channel, new Frame(MessageType.HELLO_ACK, 0, messageCodec.encodeHelloAck(ack)));
        return true;
    }

    private void execute(Frame frame) throws IOException {
        try {
            CommandRequest request = messageCodec.decodeCommandRequest(frame.payload());
            CommandReply reply = commands.execute(request.commandLine());
            frameCodec.write(channel, new Frame(
                    MessageType.RESPONSE,
                    frame.requestId(),
                    messageCodec.encodeCommandReply(reply)));
        } catch (IllegalArgumentException exception) {
            sendError(frame.requestId(), "INVALID_COMMAND", exception.getMessage());
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            sendError(frame.requestId(), "COMMAND_FAILED", failure.toString());
        }
    }

    private void sendError(long requestId, String code, String message) throws IOException {
        frameCodec.write(channel, new Frame(
                MessageType.ERROR,
                requestId,
                messageCodec.encodeRemoteError(new RemoteError(code, message == null ? "" : message))));
    }

    private static boolean secureEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
