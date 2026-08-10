package nhcm.jvmrtdp.api.jvmti;

/** Timer properties returned by JVMTI GetTimerInfo. */
public final class JvmtiTimerInfo {
    private final long maxValue;
    private final boolean maySkipForward, maySkipBackward;
    private final int kind;

    public JvmtiTimerInfo(long maxValue, boolean maySkipForward, boolean maySkipBackward, int kind) {
        this.maxValue = maxValue;
        this.maySkipForward = maySkipForward;
        this.maySkipBackward = maySkipBackward;
        this.kind = kind;
    }

    public long maxValue() { return maxValue; }
    public boolean maySkipForward() { return maySkipForward; }
    public boolean maySkipBackward() { return maySkipBackward; }
    public int kind() { return kind; }

    @Override public String toString() {
        return "JvmtiTimerInfo[maxValue=" + maxValue + ", maySkipForward=" + maySkipForward
                + ", maySkipBackward=" + maySkipBackward + ", kind=" + kind + ']';
    }
}
