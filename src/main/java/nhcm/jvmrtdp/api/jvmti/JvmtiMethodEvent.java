package nhcm.jvmrtdp.api.jvmti;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Strongly typed METHOD_ENTRY/METHOD_EXIT event including best-effort receiver, arguments, and
 * return value capture. Native/opaque frames, unavailable local access, or missing debug metadata
 * can make individual values unavailable; callers must inspect the corresponding availability
 * flag or argument error. A Java null is a valid available object value.
 */
public class JvmtiMethodEvent extends JvmtiEvent {
    private final Object receiver;
    private final boolean receiverAvailable;
    private final String receiverError;
    private final List<JvmtiMethodArgument> arguments;
    private final boolean staticMethod;
    private final boolean nativeMethod;
    private final boolean poppedByException;
    private final Object returnValue;

    public JvmtiMethodEvent(JvmtiEventType type, Thread thread, String className, String methodName,
            String methodDescriptor, long location, Object subject, long value,
            String relatedClassName, String relatedMethodName, String relatedMethodDescriptor,
            long relatedLocation, String memberName, String memberDescriptor,
            Object secondarySubject, String text, Object receiver, boolean receiverAvailable,
            String receiverError, List<JvmtiMethodArgument> arguments, boolean staticMethod,
            boolean nativeMethod, boolean poppedByException, Object returnValue) {
        super(type, thread, className, methodName, methodDescriptor, location, subject, value,
                relatedClassName, relatedMethodName, relatedMethodDescriptor, relatedLocation,
                memberName, memberDescriptor, secondarySubject, text);
        if (type != JvmtiEventType.METHOD_ENTRY && type != JvmtiEventType.METHOD_EXIT) {
            throw new IllegalArgumentException("JvmtiMethodEvent requires a method event type");
        }
        this.receiver = receiver;
        this.receiverAvailable = receiverAvailable;
        this.receiverError = emptyToNull(receiverError);
        this.arguments = Collections.unmodifiableList(new ArrayList<JvmtiMethodArgument>(arguments));
        this.staticMethod = staticMethod;
        this.nativeMethod = nativeMethod;
        this.poppedByException = poppedByException;
        this.returnValue = returnValue;
    }

    public boolean entry() { return type() == JvmtiEventType.METHOD_ENTRY; }
    public boolean exit() { return type() == JvmtiEventType.METHOD_EXIT; }
    public boolean hasReceiver() { return !staticMethod; }
    /** null is a valid receiver value when the method is static or the Java receiver is null. */
    public Object receiver() { return receiver; }
    /** True for a successfully captured receiver and for a static method, which has no receiver. */
    public boolean receiverAvailable() { return receiverAvailable; }
    public String receiverError() { return receiverError; }
    /** Arguments in descriptor order, including JVM slot, optional source name, value, and error. */
    public List<JvmtiMethodArgument> arguments() { return arguments; }
    public boolean argumentsAvailable() {
        for (JvmtiMethodArgument argument : arguments) if (!argument.available()) return false;
        return true;
    }
    public boolean staticMethod() { return staticMethod; }
    public boolean nativeMethod() { return nativeMethod; }
    public boolean poppedByException() { return poppedByException; }
    public boolean normalExit() { return exit() && !poppedByException; }
    public boolean voidReturn() {
        return methodDescriptor() != null && methodDescriptor().endsWith(")V");
    }
    /** True for a normal, non-void exit; the corresponding value can still be object-null. */
    public boolean returnValueAvailable() { return normalExit() && !voidReturn(); }
    /** Boxed return value for a normal METHOD_EXIT; null for void, object-null, entry or exception pop. */
    public Object returnValue() { return returnValue; }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
