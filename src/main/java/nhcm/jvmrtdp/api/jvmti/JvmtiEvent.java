package nhcm.jvmrtdp.api.jvmti;

import java.time.Instant;

/**
 * Immutable parameters captured from one native JVMTI callback.
 * {@code subject} is the callback's primary object/class/exception/return value. {@code value}
 * carries primitive return/new-value bits, timeout, allocation size, tag, flags or code size,
 * depending on {@code type}. Related-method fields describe an exception catch site; member
 * fields describe a watched field; {@code text} carries native code/resource descriptions.
 */
public class JvmtiEvent {
    private final JvmtiEventType type;
    private final Instant timestamp;
    private final Thread thread;
    private final String className;
    private final String methodName;
    private final String methodDescriptor;
    private final long location;
    private final Object subject;
    private final long value;
    private final String relatedClassName;
    private final String relatedMethodName;
    private final String relatedMethodDescriptor;
    private final long relatedLocation;
    private final String memberName;
    private final String memberDescriptor;
    private final Object secondarySubject;
    private final String text;

    public JvmtiEvent(JvmtiEventType type, Thread thread, String className, String methodName,
            String methodDescriptor, long location, Object subject, long value) {
        this(type, thread, className, methodName, methodDescriptor, location, subject, value,
                null, null, null, 0, null, null, null, null);
    }

    public JvmtiEvent(JvmtiEventType type, Thread thread, String className, String methodName,
            String methodDescriptor, long location, Object subject, long value,
            String relatedClassName, String relatedMethodName, String relatedMethodDescriptor,
            long relatedLocation, String memberName, String memberDescriptor,
            Object secondarySubject, String text) {
        this.type = type;
        this.timestamp = Instant.now();
        this.thread = thread;
        this.className = emptyToNull(className);
        this.methodName = emptyToNull(methodName);
        this.methodDescriptor = emptyToNull(methodDescriptor);
        this.location = location;
        this.subject = subject;
        this.value = value;
        this.relatedClassName = emptyToNull(relatedClassName);
        this.relatedMethodName = emptyToNull(relatedMethodName);
        this.relatedMethodDescriptor = emptyToNull(relatedMethodDescriptor);
        this.relatedLocation = relatedLocation;
        this.memberName = emptyToNull(memberName);
        this.memberDescriptor = emptyToNull(memberDescriptor);
        this.secondarySubject = secondarySubject;
        this.text = emptyToNull(text);
    }

    public JvmtiEventType type() { return type; }
    public JvmtiEventCategory category() { return type.category(); }
    public Instant timestamp() { return timestamp; }
    public Thread thread() { return thread; }
    public String className() { return className; }
    public String methodName() { return methodName; }
    public String methodDescriptor() { return methodDescriptor; }
    public long location() { return location; }
    public Object subject() { return subject; }
    public long value() { return value; }
    /** Catch/related method class, when the native event supplies one. */
    public String relatedClassName() { return relatedClassName; }
    public String relatedMethodName() { return relatedMethodName; }
    public String relatedMethodDescriptor() { return relatedMethodDescriptor; }
    public long relatedLocation() { return relatedLocation; }
    /** Field name/descriptor for field watch events. */
    public String memberName() { return memberName; }
    public String memberDescriptor() { return memberDescriptor; }
    /** Object-valued secondary parameter, such as a field's new value. */
    public Object secondarySubject() { return secondarySubject; }
    /** Event-specific text, such as a resource-exhaustion description or code name. */
    public String text() { return text; }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    @Override
    public String toString() {
        return "JvmtiEvent[type=" + type + ", thread=" + (thread == null ? "" : thread.getName())
                + ", class=" + className + ", method=" + methodName + methodDescriptor
                + ", location=" + location + ", subject=" + subject + ", value=" + value
                + ", member=" + memberName + memberDescriptor + ", text=" + text + "]";
    }
}
