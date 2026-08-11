package nhcm.jvmrtdp.api;

import java.util.Objects;

/** Immutable output from a context-oriented command executed through the library API. */
public final class JvmRtdpCommandResult {
    private final String command;
    private final boolean successful;
    private final boolean sessionContinuationRequested;
    private final String standardOutput;
    private final String standardError;
    private final String failureType;
    private final String failureMessage;

    static JvmRtdpCommandResult success(
            String command, boolean sessionContinuationRequested, String output, String error) {
        return new JvmRtdpCommandResult(
                command, true, sessionContinuationRequested, output, error, "", "");
    }

    static JvmRtdpCommandResult failure(
            String command, String output, String error, Throwable failure) {
        return new JvmRtdpCommandResult(
                command, false, true, output, error,
                failure.getClass().getName(), safe(failure.getMessage()));
    }

    static JvmRtdpCommandResult diagnosticFailure(
            String command,
            boolean sessionContinuationRequested,
            String output,
            String error) {
        String message = error.trim();
        return new JvmRtdpCommandResult(
                command, false, sessionContinuationRequested, output, error,
                "command-error", message);
    }

    private JvmRtdpCommandResult(
            String command,
            boolean successful,
            boolean sessionContinuationRequested,
            String standardOutput,
            String standardError,
            String failureType,
            String failureMessage) {
        this.command = Objects.requireNonNull(command, "command");
        this.successful = successful;
        this.sessionContinuationRequested = sessionContinuationRequested;
        this.standardOutput = Objects.requireNonNull(standardOutput, "standardOutput");
        this.standardError = Objects.requireNonNull(standardError, "standardError");
        this.failureType = Objects.requireNonNull(failureType, "failureType");
        this.failureMessage = Objects.requireNonNull(failureMessage, "failureMessage");
    }

    public String command() {
        return command;
    }

    public boolean successful() {
        return successful;
    }

    /** False when a CLI-only control command such as {@code back} or {@code exit} was requested. */
    public boolean sessionContinuationRequested() {
        return sessionContinuationRequested;
    }

    public String standardOutput() {
        return standardOutput;
    }

    public String standardError() {
        return standardError;
    }

    public String failureType() {
        return failureType;
    }

    public String failureMessage() {
        return failureMessage;
    }

    /** Returns this result or throws a {@link JvmRtdpCommandException} when it failed. */
    public JvmRtdpCommandResult requireSuccess() {
        if (!successful) throw new JvmRtdpCommandException(this);
        return this;
    }

    @Override
    public String toString() {
        return "JvmRtdpCommandResult{command='" + command + "', successful=" + successful
                + ", sessionContinuationRequested=" + sessionContinuationRequested + "}";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
