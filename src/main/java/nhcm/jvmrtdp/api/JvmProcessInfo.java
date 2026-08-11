package nhcm.jvmrtdp.api;

import nhcm.jvmrtdp.JVMProcess;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Immutable process information returned by {@link JvmRtdpClient}. */
public final class JvmProcessInfo {
    private final long pid;
    private final String displayName;
    private final String executableName;
    private final String windowTitle;
    private final String architecture;
    private final Instant startedAt;
    private final boolean alive;

    static JvmProcessInfo from(JVMProcess process) {
        return new JvmProcessInfo(
                process.pid(), process.displayName(), process.executableName(), process.windowTitle(),
                process.architecture(), process.startedAt(), process.isAlive());
    }

    JvmProcessInfo(
            long pid,
            String displayName,
            String executableName,
            String windowTitle,
            String architecture,
            Instant startedAt,
            boolean alive) {
        if (pid <= 0) throw new IllegalArgumentException("pid must be positive");
        this.pid = pid;
        this.displayName = safe(displayName);
        this.executableName = safe(executableName);
        this.windowTitle = safe(windowTitle);
        this.architecture = safe(architecture);
        this.startedAt = startedAt;
        this.alive = alive;
    }

    public long pid() {
        return pid;
    }

    public String displayName() {
        return displayName;
    }

    public String executableName() {
        return executableName;
    }

    public String windowTitle() {
        return windowTitle;
    }

    public String architecture() {
        return architecture;
    }

    public Optional<Instant> startedAt() {
        return Optional.ofNullable(startedAt);
    }

    /** Returns uptime at the time this method is called, or zero if the start time is unavailable. */
    public Duration uptime() {
        return startedAt == null ? Duration.ZERO : Duration.between(startedAt, Instant.now());
    }

    /** Process liveness captured when this value was created. */
    public boolean isAlive() {
        return alive;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof JvmProcessInfo)) return false;
        JvmProcessInfo that = (JvmProcessInfo) other;
        return pid == that.pid && alive == that.alive
                && displayName.equals(that.displayName)
                && executableName.equals(that.executableName)
                && windowTitle.equals(that.windowTitle)
                && architecture.equals(that.architecture)
                && Objects.equals(startedAt, that.startedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pid, displayName, executableName, windowTitle, architecture, startedAt, alive);
    }

    @Override
    public String toString() {
        return "JvmProcessInfo{pid=" + pid + ", displayName='" + displayName
                + "', architecture='" + architecture + "', alive=" + alive + "}";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
