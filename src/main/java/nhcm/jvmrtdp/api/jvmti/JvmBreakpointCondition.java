package nhcm.jvmrtdp.api.jvmti;

import nhcm.jvmrtdp.handles.java.RemoteObject;

/**
 * Optional persistent-breakpoint predicate shared by the Java library, CLI and TUI.
 * Empty caller components are wildcards; {@code *} and {@code ?} are supported by
 * target-side matching. Receiver matching uses target-JVM object identity, not
 * {@code equals}. The receiver handle (or a strong tracked reference) must remain alive until
 * the breakpoint is removed, including across redefinition-driven breakpoint relocation.
 */
public final class JvmBreakpointCondition {
    private static final JvmBreakpointCondition ANY =
            new JvmBreakpointCondition(null, "", "", "");

    private final RemoteObject receiver;
    private final String callerClass;
    private final String callerMethod;
    private final String callerDescriptor;

    private JvmBreakpointCondition(RemoteObject receiver, String callerClass,
            String callerMethod, String callerDescriptor) {
        this.receiver = receiver;
        this.callerClass = normalize(callerClass);
        this.callerMethod = normalize(callerMethod);
        this.callerDescriptor = normalize(callerDescriptor);
    }

    public static JvmBreakpointCondition any() { return ANY; }

    public static JvmBreakpointCondition receiver(RemoteObject receiver) {
        if (receiver == null || receiver.isNull()) {
            throw new IllegalArgumentException("A non-null receiver object is required");
        }
        return new JvmBreakpointCondition(receiver, "", "", "");
    }

    public JvmBreakpointCondition calledFrom(String classPattern, String methodPattern,
            String descriptorPattern) {
        return new JvmBreakpointCondition(receiver, classPattern, methodPattern, descriptorPattern);
    }

    public JvmBreakpointCondition withReceiver(RemoteObject value) {
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("A non-null receiver object is required");
        }
        return new JvmBreakpointCondition(value, callerClass, callerMethod, callerDescriptor);
    }

    public RemoteObject receiver() { return receiver; }
    public long receiverId() { return receiver == null ? 0L : receiver.remoteId(); }
    public String callerClass() { return callerClass; }
    public String callerMethod() { return callerMethod; }
    public String callerDescriptor() { return callerDescriptor; }
    public boolean isUnconditional() {
        return receiver == null && callerClass.isEmpty()
                && callerMethod.isEmpty() && callerDescriptor.isEmpty();
    }

    public String summary() {
        StringBuilder result = new StringBuilder();
        if (receiver != null) result.append("receiver#").append(receiver.remoteId());
        if (!callerClass.isEmpty() || !callerMethod.isEmpty() || !callerDescriptor.isEmpty()) {
            if (result.length() > 0) result.append(", ");
            result.append("caller=").append(callerClass.isEmpty() ? "*" : callerClass)
                    .append('#').append(callerMethod.isEmpty() ? "*" : callerMethod)
                    .append(callerDescriptor.isEmpty() ? "*" : callerDescriptor);
        }
        return result.length() == 0 ? "all receivers/callers" : result.toString();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replace('/', '.');
    }
}
