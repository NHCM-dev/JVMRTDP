package nhcm.jvmrtdp.api;

import nhcm.jvmrtdp.BuildInfo;
import nhcm.jvmrtdp.JVMProcess;
import nhcm.jvmrtdp.JVMRTDP;
import nhcm.jvmrtdp.attach.AgentJarLocator;
import nhcm.jvmrtdp.handles.ServerHandle;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Public entry point for embedding JVMRTDP in another Java application.
 *
 * <p>The client owns every session it creates. Closing it closes all remaining sessions.</p>
 */
public final class JvmRtdpClient implements AutoCloseable {
    private final JVMRTDP controller;
    private final Set<JvmRtdpSession> sessions = new LinkedHashSet<JvmRtdpSession>();
    private boolean closed;

    public JvmRtdpClient() {
        this(new JVMRTDP());
    }

    JvmRtdpClient(JVMRTDP controller) {
        this.controller = Objects.requireNonNull(controller, "controller");
    }

    public static JvmRtdpClient open() {
        return new JvmRtdpClient();
    }

    public String version() {
        return BuildInfo.VERSION;
    }

    /** Returns a stable snapshot of accessible local JVM processes. */
    public synchronized List<JvmProcessInfo> processes() {
        ensureOpen();
        List<JvmProcessInfo> result = new ArrayList<JvmProcessInfo>();
        for (JVMProcess process : controller.getProcesses()) result.add(JvmProcessInfo.from(process));
        return Collections.unmodifiableList(result);
    }

    /** Reads current information for one PID without attaching. */
    public synchronized JvmProcessInfo process(long pid) {
        ensureOpen();
        return JvmProcessInfo.from(controller.getProcess(positivePid(pid)));
    }

    public JvmRtdpSession attach(long pid) {
        return attach(pid, AttachOptions.defaults());
    }

    public JvmRtdpSession attach(JvmProcessInfo process) {
        Objects.requireNonNull(process, "process");
        return attach(process.pid(), AttachOptions.defaults());
    }

    public JvmRtdpSession attach(JvmProcessInfo process, AttachOptions options) {
        Objects.requireNonNull(process, "process");
        return attach(process.pid(), options);
    }

    public JvmRtdpSession attach(long pid, AttachOptions options) {
        Objects.requireNonNull(options, "options");
        synchronized (this) {
            ensureOpen();
        }

        Path agentJar = options.agentJarOrNull();
        if (agentJar == null) agentJar = AgentJarLocator.locateCurrentJar();
        ServerHandle handle = controller.inject(positivePid(pid), agentJar, options.timeout());
        boolean transferred = false;
        try {
            final JvmRtdpSession session = new JvmRtdpSession(handle);
            session.setCloseListener(new Runnable() {
                @Override
                public void run() {
                    removeClosedSession(session);
                }
            });
            synchronized (this) {
                if (closed) {
                    session.close();
                    throw new IllegalStateException("JVMRTDP client is closed");
                }
                sessions.add(session);
            }
            transferred = true;
            return session;
        } finally {
            if (!transferred) handle.close();
        }
    }

    private synchronized void removeClosedSession(JvmRtdpSession session) {
        if (session != null) sessions.remove(session);
    }

    @Override
    public void close() {
        List<JvmRtdpSession> snapshot;
        synchronized (this) {
            if (closed) return;
            closed = true;
            snapshot = new ArrayList<JvmRtdpSession>(sessions);
            sessions.clear();
        }
        for (JvmRtdpSession session : snapshot) session.close();
    }

    private synchronized void ensureOpen() {
        if (closed) throw new IllegalStateException("JVMRTDP client is closed");
    }

    private static long positivePid(long pid) {
        if (pid <= 0) throw new IllegalArgumentException("pid must be positive");
        return pid;
    }
}
