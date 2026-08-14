package nhcm.jvmrtdp.api.jvmti;

import java.util.Objects;

/**
 * Immutable method-entry, method-exit, or exception breakpoint specification.
 * Patterns support {@code *} and {@code ?}. Unlike a BCI breakpoint, an event breakpoint can
 * stop native and abstract declarations because it does not require a Java Code attribute.
 * Symbolic registrations remain useful for future class loads; {@link #includingSubtypes()}
 * instead requires one exact, already-loaded base class or interface.
 */
public final class JvmEventBreakpointSpec {
    private final JvmEventBreakpointKind kind;
    private final String classPattern;
    private final String methodPattern;
    private final String descriptorPattern;
    private final boolean includeSubtypes;

    public JvmEventBreakpointSpec(JvmEventBreakpointKind kind, String classPattern,
            String methodPattern, String descriptorPattern, boolean includeSubtypes) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.classPattern = pattern(classPattern);
        this.methodPattern = pattern(methodPattern);
        this.descriptorPattern = pattern(descriptorPattern);
        this.includeSubtypes = includeSubtypes;
        if (kind == JvmEventBreakpointKind.EXCEPTION_THROW && includeSubtypes) {
            throw new IllegalArgumentException("Exception subtype matching is expressed with a class glob");
        }
    }

    public static JvmEventBreakpointSpec methodEntry(String className, String methodName,
            String descriptor) {
        return new JvmEventBreakpointSpec(JvmEventBreakpointKind.METHOD_ENTRY,
                className, methodName, descriptor, false);
    }

    public static JvmEventBreakpointSpec methodExit(String className, String methodName,
            String descriptor) {
        return new JvmEventBreakpointSpec(JvmEventBreakpointKind.METHOD_EXIT,
                className, methodName, descriptor, false);
    }

    public static JvmEventBreakpointSpec exception(String classPattern) {
        return new JvmEventBreakpointSpec(JvmEventBreakpointKind.EXCEPTION_THROW,
                classPattern, "*", "*", false);
    }

    /** Matches implementations declared by subclasses/implementors of the selected loaded type. */
    public JvmEventBreakpointSpec includingSubtypes() {
        if (kind == JvmEventBreakpointKind.EXCEPTION_THROW) return this;
        if (classPattern.indexOf('*') >= 0 || classPattern.indexOf('?') >= 0) {
            throw new IllegalStateException("Subtype matching requires one exact loaded class name");
        }
        return new JvmEventBreakpointSpec(kind, classPattern, methodPattern, descriptorPattern, true);
    }

    public JvmEventBreakpointKind kind() { return kind; }
    public String classPattern() { return classPattern; }
    public String methodPattern() { return methodPattern; }
    public String descriptorPattern() { return descriptorPattern; }
    public boolean includeSubtypes() { return includeSubtypes; }

    private static String pattern(String value) {
        return value == null || value.trim().isEmpty() ? "*" : value.trim();
    }
}
