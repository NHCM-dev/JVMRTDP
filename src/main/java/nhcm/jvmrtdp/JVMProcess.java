package nhcm.jvmrtdp;

import nhcm.jvmrtdp.handles.ServerHandle;
import nhcm.jvmrtdp.tools.JRDInjector;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public class JVMProcess {
    private final long pid;
    private final String displayName;
    private final String executableName;
    private final String windowTitle;
    private final Instant startedAt;
    private final JRDInjector injector;

    public JVMProcess(long pid, String displayName, JRDInjector injector) {
        this(pid, "", "", displayName, null, injector);
    }

    public JVMProcess(
            long pid, String executableName, String windowTitle, String displayName,
            Instant startedAt, JRDInjector injector) {
        if (pid <= 0) {
            throw new IllegalArgumentException("PID must be positive");
        }
        this.pid = pid;
        this.displayName = displayName == null ? "" : displayName;
        this.executableName = executableName == null ? "" : executableName;
        this.windowTitle = windowTitle == null ? "" : windowTitle;
        this.startedAt = startedAt;
        this.injector = Objects.requireNonNull(injector, "injector");
    }

    public long pid() {
        return pid;
    }

    public String displayName() {
        return displayName;
    }

    /** Executable image shown by the Task Manager Details tab, for example java.exe. */
    public String executableName() {
        return executableName;
    }

    /** Visible top-level window title associated with this PID, if the process owns one. */
    public String windowTitle() {
        return windowTitle;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Duration uptime() {
        return startedAt == null ? Duration.ZERO : Duration.between(startedAt, Instant.now());
    }

    public boolean isAlive() {
        return injector.isProcessAlive(pid);
    }

    public String architecture() {
        return injector.processArchitecture(pid);
    }

    public ServerHandle inject() {
        return injector.inject(this);
    }

    public ServerHandle inject(Path agentJar, Duration timeout) {
        return injector.inject(this, agentJar, timeout);
    }

    @Override
    public String toString() {
        return pid + (displayName.trim().isEmpty() ? "" : " " + displayName);
    }
}
