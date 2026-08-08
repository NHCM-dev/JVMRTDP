package nhcm.jvmrtdp;

import nhcm.jvmrtdp.handles.ServerHandle;
import nhcm.jvmrtdp.tools.JRDInjector;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

public class JVMProcess {
    private final long pid;
    private final String displayName;
    private final JRDInjector injector;

    public JVMProcess(long pid, String displayName, JRDInjector injector) {
        if (pid <= 0) {
            throw new IllegalArgumentException("PID must be positive");
        }
        this.pid = pid;
        this.displayName = displayName == null ? "" : displayName;
        this.injector = Objects.requireNonNull(injector, "injector");
    }

    public long pid() {
        return pid;
    }

    public String displayName() {
        return displayName;
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
