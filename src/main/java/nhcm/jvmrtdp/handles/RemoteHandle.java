package nhcm.jvmrtdp.handles;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import nhcm.jvmrtdp.protocol.CommandReply;

public abstract class RemoteHandle {
    private static final AtomicLong LOGICAL_IDS = new AtomicLong(1_000);

    private final ServerHandle server;
    private final long remoteId;

    protected RemoteHandle(ServerHandle server, long remoteId) {
        this.server = Objects.requireNonNull(server, "server");
        if (remoteId <= 0) {
            throw new IllegalArgumentException("Remote ID must be positive");
        }
        this.remoteId = remoteId;
    }

    public final ServerHandle server() {
        return server;
    }

    public final long remoteId() {
        return remoteId;
    }

    protected static long allocateRemoteId() {
        return LOGICAL_IDS.getAndIncrement();
    }

    protected final String executeForOutput(String commandLine) {
        CommandReply reply = server.execute(commandLine);
        if (!reply.successful()) {
            throw new IllegalStateException(reply.output());
        }
        return reply.output();
    }

    @Override
    public final int hashCode() {
        return Objects.hash(server.sessionId(), remoteId, getClass());
    }

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || other.getClass() != getClass()) {
            return false;
        }
        RemoteHandle that = (RemoteHandle) other;
        return remoteId == that.remoteId && server.sessionId().equals(that.server.sessionId());
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[session=" + server.sessionId() + ", id=" + remoteId + "]";
    }
}
