package nhcm.jvmrtdp.agent;

import nhcm.jvmrtdp.agent.command.CommandRegistry;
import nhcm.jvmrtdp.attach.AgentOptions;
import nhcm.jvmrtdp.handles.JRDHandle;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public class AgentServer implements AutoCloseable {
    private static final Duration ACCEPT_TIMEOUT = Duration.ofSeconds(30);

    private final JRDHandle handle;
    private final AgentOptions options;
    private final Runnable onClosed;
    private final AtomicBoolean open = new AtomicBoolean();
    private ServerSocketChannel serverChannel;
    private Thread serverThread;

    public AgentServer(JRDHandle handle, AgentOptions options, Runnable onClosed) {
        this.handle = Objects.requireNonNull(handle, "handle");
        this.options = Objects.requireNonNull(options, "options");
        this.onClosed = Objects.requireNonNull(onClosed, "onClosed");
    }

    public synchronized void start() throws IOException {
        if (!open.compareAndSet(false, true)) {
            throw new IllegalStateException("Agent server is already running");
        }
        try {
            serverChannel = ServerSocketChannel.open();
            serverChannel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), options.port()));
            serverChannel.configureBlocking(false);
            serverThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    serve();
                }
            }, "jvmrtdp-agent-server-" + options.sessionId());
            serverThread.setDaemon(true);
            serverThread.start();
        } catch (IOException exception) {
            open.set(false);
            closeQuietly(serverChannel);
            throw exception;
        } catch (RuntimeException exception) {
            open.set(false);
            closeQuietly(serverChannel);
            throw exception;
        }
    }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        closeQuietly(serverChannel);
        handle.close();
        onClosed.run();
    }

    private void serve() {
        try {
            SocketChannel client = awaitClient();
            if (client == null) {
                return;
            }
            client.configureBlocking(true);
            try (SocketChannel acceptedClient = client;
                 AgentConnection connection = new AgentConnection(
                         acceptedClient, handle, options, new CommandRegistry(handle))) {
                connection.run();
            }
        } catch (IOException ignored) {
            // Closing either process tears down the authenticated loopback session.
        } finally {
            close();
        }
    }

    private SocketChannel awaitClient() throws IOException {
        long deadline = System.nanoTime() + ACCEPT_TIMEOUT.toNanos();
        while (open.get() && System.nanoTime() < deadline) {
            SocketChannel client = serverChannel.accept();
            if (client != null) {
                return client;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private static void closeQuietly(ServerSocketChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
        }
    }
}
