package nhcm.jvmrtdp.api;

/** Raised by {@link JvmRtdpCommandResult#requireSuccess()} for a failed embedded command. */
public final class JvmRtdpCommandException extends RuntimeException {
    private final JvmRtdpCommandResult result;

    JvmRtdpCommandException(JvmRtdpCommandResult result) {
        super(result.failureMessage().isEmpty()
                ? "JVMRTDP command failed: " + result.command() : result.failureMessage());
        this.result = result;
    }

    public JvmRtdpCommandResult result() {
        return result;
    }
}
