package nhcm.jvmrtdp.api.jvmti;

/** A parsed JVMTI stack-frame location in {@code owner.method(descriptor)@bci} form. */
public final class JvmStackFrame {
    private final int depth;
    private final String className;
    private final String methodName;
    private final String descriptor;
    private final long location;
    private final String raw;

    private JvmStackFrame(int depth, String className, String methodName,
            String descriptor, long location, String raw) {
        this.depth = depth;
        this.className = className;
        this.methodName = methodName;
        this.descriptor = descriptor;
        this.location = location;
        this.raw = raw;
    }

    public int depth() { return depth; }
    public String className() { return className; }
    public String methodName() { return methodName; }
    public String descriptor() { return descriptor; }
    public long location() { return location; }
    public String raw() { return raw; }
    public boolean isNative() { return location < 0; }
    public boolean hasJavaLocation() { return location >= 0; }

    public boolean isPlatformFrame() {
        return className.startsWith("java.") || className.startsWith("javax.")
                || className.startsWith("jdk.") || className.startsWith("sun.")
                || className.startsWith("com.sun.");
    }

    public String display() {
        return "#" + depth + " " + (isNative() ? "NATIVE " : "BCI " + location + " ")
                + className + "." + methodName + descriptor;
    }

    public static JvmStackFrame parse(int depth, String value) {
        String raw = value == null ? "" : value.trim();
        int at = raw.lastIndexOf('@');
        long location = Long.MIN_VALUE;
        if (at > 0 && at + 1 < raw.length()) {
            try { location = Long.parseLong(raw.substring(at + 1).trim()); }
            catch (NumberFormatException ignored) { at = -1; }
        }
        String member = at > 0 ? raw.substring(0, at) : raw;
        int descriptorStart = member.indexOf('(');
        int separator = descriptorStart < 0
                ? member.lastIndexOf('.') : member.lastIndexOf('.', descriptorStart);
        if (descriptorStart < 0 || separator <= 0 || location == Long.MIN_VALUE) {
            return new JvmStackFrame(depth, "<unparsed>", raw, "", -1L, raw);
        }
        return new JvmStackFrame(depth, member.substring(0, separator),
                member.substring(separator + 1, descriptorStart),
                member.substring(descriptorStart), location, raw);
    }

    @Override public String toString() { return display(); }
}
