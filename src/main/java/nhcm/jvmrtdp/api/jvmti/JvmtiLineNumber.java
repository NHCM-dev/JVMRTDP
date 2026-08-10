package nhcm.jvmrtdp.api.jvmti;

/** One BCI/native location to source-line mapping. */
public final class JvmtiLineNumber {
    private final long location;
    private final int lineNumber;

    public JvmtiLineNumber(long location, int lineNumber) {
        this.location = location;
        this.lineNumber = lineNumber;
    }

    public long location() { return location; }
    public int lineNumber() { return lineNumber; }

    @Override public String toString() { return location + " -> line " + lineNumber; }
}
