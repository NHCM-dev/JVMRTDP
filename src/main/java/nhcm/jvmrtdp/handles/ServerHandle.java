package nhcm.jvmrtdp.handles;

import nhcm.jvmrtdp.JVMProcess;
import nhcm.jvmrtdp.attach.AgentOptions;
import nhcm.jvmrtdp.handles.jvm.RemoteJavaVM;
import nhcm.jvmrtdp.protocol.CommandReply;
import nhcm.jvmrtdp.protocol.CommandRequest;
import nhcm.jvmrtdp.protocol.Frame;
import nhcm.jvmrtdp.protocol.ChannelFrameCodec;
import nhcm.jvmrtdp.protocol.HelloAckMessage;
import nhcm.jvmrtdp.protocol.HelloMessage;
import nhcm.jvmrtdp.protocol.MessageCodec;
import nhcm.jvmrtdp.protocol.MessageType;
import nhcm.jvmrtdp.protocol.RemoteError;
import nhcm.jvmrtdp.throwble.InjectionException;
import nhcm.jvmrtdp.throwble.RemoteCommandException;
import nhcm.jvmrtdp.utils.ProcessIds;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handle that has ability to send command to JVMRTDP agent in target JVM
 */
public class ServerHandle implements AutoCloseable {
    private static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofSeconds(30);

    private final JVMProcess process;
    private final UUID sessionId;
    private final UUID jrdHandleId;
    private final HelloAckMessage target;
    private final SocketChannel channel;
    private final ChannelFrameCodec frameCodec = new ChannelFrameCodec();
    private final MessageCodec messageCodec = new MessageCodec();
    private final Object writeLock = new Object();
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final AtomicLong requestIds = new AtomicLong(1);
    private final Map<Long, CompletableFuture<CommandReply>> pending = new ConcurrentHashMap<>();
    private final ExecutorService reader;
    private final RemoteJavaVM javaVM;

    private ServerHandle(JVMProcess process, AgentOptions options, SocketChannel channel) throws IOException {
        this.process = process;
        this.sessionId = options.sessionId();
        this.channel = channel;
        this.target = handshake(options);
        this.jrdHandleId = target.jrdHandleId();
        this.javaVM = new RemoteJavaVM(this, 1);
        this.reader = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "jvmrtdp-controller-reader-" + process.pid());
            thread.setDaemon(true);
            return thread;
        });
        this.reader.execute(this::readLoop);
    }

    public static ServerHandle connect(JVMProcess process, AgentOptions options, Duration timeout) {
        Objects.requireNonNull(process, "process");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Timeout must be positive");
        }

        long deadline = System.nanoTime() + timeout.toNanos();
        IOException lastFailure = null;
        while (System.nanoTime() < deadline) {
            SocketChannel candidate = null;
            try {
                candidate = SocketChannel.open();
                candidate.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), options.port()));
                return new ServerHandle(process, options, candidate);
            } catch (IOException exception) {
                lastFailure = exception;
                closeQuietly(candidate);
                try {
                    Thread.sleep(25);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new InjectionException("Interrupted while connecting to target agent", interrupted);
                }
            }
        }
        throw new InjectionException(
                "Timed out connecting to JVMRTDP agent on loopback port " + options.port(), lastFailure);
    }

    public JVMProcess process() {
        return process;
    }

    public UUID sessionId() {
        return sessionId;
    }

    public UUID jrdHandleId() {
        return jrdHandleId;
    }

    public boolean isOpen() {
        return open.get();
    }

    public String targetDisplayName() {
        return target.targetDisplayName();
    }

    public boolean nativeAvailable() {
        return target.nativeAvailable();
    }

    public String nativeDescription() {
        return target.nativeDescription();
    }

    public RemoteJavaVM javaVM() {
        return javaVM;
    }

    public CommandReply execute(String commandLine) {
        return execute(commandLine, DEFAULT_COMMAND_TIMEOUT);
    }

    public CommandReply execute(String commandLine, Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        CompletableFuture<CommandReply> future = executeAsync(commandLine);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for target JVM", exception);
        } catch (TimeoutException exception) {
            future.cancel(false);
            throw new IllegalStateException("Target command timed out after " + timeout, exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException) {
                throw (RuntimeException) exception.getCause();
            }
            throw new IllegalStateException("Target command failed", exception.getCause());
        }
    }

    public CompletableFuture<CommandReply> executeAsync(String commandLine) {
        Objects.requireNonNull(commandLine, "commandLine");
        ensureOpen();
        long requestId = requestIds.getAndIncrement();
        CompletableFuture<CommandReply> future = new CompletableFuture<>();
        pending.put(requestId, future);
        future.whenComplete((ignored, failure) -> pending.remove(requestId, future));
        try {
            write(new Frame(
                    MessageType.REQUEST,
                    requestId,
                    messageCodec.encodeCommandRequest(new CommandRequest(commandLine))));
        } catch (IOException exception) {
            pending.remove(requestId);
            future.completeExceptionally(exception);
            terminate(exception, false);
        }
        return future;
    }

    @Override
    public void close() {
        terminate(new EOFException("JVMRTDP session closed"), true);
    }

    private HelloAckMessage handshake(AgentOptions options) throws IOException {
        String version = ServerHandle.class.getPackage().getImplementationVersion();
        if (version == null) {
            version = "development";
        }
        write(new Frame(MessageType.HELLO, 0, messageCodec.encodeHello(new HelloMessage(
                options.token(), ProcessIds.current(), version))));
        Frame response = frameCodec.read(channel);
        if (response.type() == MessageType.ERROR) {
            RemoteError error = messageCodec.decodeRemoteError(response.payload());
            throw new IOException("Agent rejected handshake: " + error.code() + ": " + error.message());
        }
        if (response.type() != MessageType.HELLO_ACK || response.requestId() != 0) {
            throw new IOException("Unexpected response during JVMRTDP handshake: " + response.type());
        }
        HelloAckMessage ack = messageCodec.decodeHelloAck(response.payload());
        if (ack.targetPid() != process.pid()) {
            throw new IOException("Connected agent PID does not match target PID");
        }
        return ack;
    }

    private void readLoop() {
        try {
            while (open.get()) {
                Frame frame = frameCodec.read(channel);
                CompletableFuture<CommandReply> future = pending.remove(frame.requestId());
                if (future == null) {
                    continue;
                }
                if (frame.type() == MessageType.RESPONSE) {
                    future.complete(messageCodec.decodeCommandReply(frame.payload()));
                } else if (frame.type() == MessageType.ERROR) {
                    RemoteError error = messageCodec.decodeRemoteError(frame.payload());
                    future.completeExceptionally(new RemoteCommandException(error.code(), error.message()));
                } else {
                    future.completeExceptionally(new IOException("Unexpected response type: " + frame.type()));
                }
            }
        } catch (IOException exception) {
            terminate(exception, false);
        }
    }

    private void write(Frame frame) throws IOException {
        synchronized (writeLock) {
            frameCodec.write(channel, frame);
        }
    }

    private void terminate(Throwable cause, boolean sendClose) {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        if (sendClose) {
            try {
                synchronized (writeLock) {
                    frameCodec.write(channel, new Frame(MessageType.CLOSE, 0, new byte[0]));
                }
            } catch (IOException ignored) {
            }
        }
        closeQuietly(channel);
        pending.values().forEach(future -> future.completeExceptionally(cause));
        pending.clear();
        reader.shutdownNow();
    }

    private void ensureOpen() {
        if (!open.get()) {
            throw new IllegalStateException("JVMRTDP session is closed");
        }
    }

    private static void closeQuietly(SocketChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
        }
    }
}
