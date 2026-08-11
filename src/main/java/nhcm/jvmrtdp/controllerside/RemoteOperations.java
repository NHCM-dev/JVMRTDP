package nhcm.jvmrtdp.controllerside;

import nhcm.jvmrtdp.handles.java.RemoteClass;
import nhcm.jvmrtdp.handles.java.RemoteField;
import nhcm.jvmrtdp.handles.java.RemoteMethod;
import nhcm.jvmrtdp.handles.java.RemoteObject;
import nhcm.jvmrtdp.api.jvmti.JvmDebuggerState;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/** High-level operations shared by the interactive shell and script engine. */
public class RemoteOperations {
    private static final String STATIC_PREFIX = "static:";

    private final TargetSession session;

    public RemoteOperations(TargetSession session) {
        this.session = session;
    }

    public RemoteClass defineClass(String variable, String className) {
        return session.workspace().defineClass(variable, className);
    }

    public RemoteObject defineValue(String variable, String type, String literal) {
        return session.workspace().defineObject(
                variable, session.jni().valueOf(localValue(type, literal)));
    }

    public RemoteObject construct(
            String variable, String classReference, String descriptor, List<String> argumentReferences) {
        RemoteObject result = session.workspace().classValue(classReference)
                .construct(descriptor, arguments(argumentReferences));
        return session.workspace().defineObject(variable, result);
    }

    public RemoteObject call(
            String variable,
            String receiverReference,
            String methodName,
            String descriptor,
            List<String> argumentReferences) {
        RemoteObject[] arguments = arguments(argumentReferences);
        RemoteObject result;
        String declaringClass = declaringClass(methodName);
        String simpleMethodName = simpleMember(methodName);
        if (isStatic(receiverReference)) {
            RemoteClass type = session.workspace().classValue(staticClass(receiverReference));
            result = (declaringClass == null
                    ? type.getStaticMethod(simpleMethodName, descriptor)
                    : type.getStaticMethod(declaringClass, simpleMethodName, descriptor)).callStatic(arguments);
        } else {
            RemoteObject receiver = session.workspace().objectValue(receiverReference);
            RemoteClass type = receiver.remoteClass();
            if (declaringClass == null) {
                result = type.getVirtualMethod(simpleMethodName, descriptor).call(receiver, arguments);
            } else {
                result = type.getVirtualMethod(declaringClass, simpleMethodName, descriptor)
                        .callSpecial(receiver, arguments);
            }
        }
        return session.workspace().defineObject(variable, result);
    }

    public RemoteObject get(String variable, String receiverReference, String fieldName) {
        RemoteObject result;
        String declaringClass = declaringClass(fieldName);
        String simpleFieldName = simpleMember(fieldName);
        if (isStatic(receiverReference)) {
            RemoteClass type = session.workspace().classValue(staticClass(receiverReference));
            result = (declaringClass == null ? type.getStaticField(simpleFieldName)
                    : type.getStaticField(declaringClass, simpleFieldName)).readStatic();
        } else {
            RemoteObject receiver = session.workspace().objectValue(receiverReference);
            RemoteClass type = receiver.remoteClass();
            result = (declaringClass == null ? type.getVirtualField(simpleFieldName)
                    : type.getVirtualField(declaringClass, simpleFieldName)).read(receiver);
        }
        return session.workspace().defineObject(variable, result);
    }

    public void set(String receiverReference, String fieldName, String valueReference) {
        RemoteObject value = session.workspace().objectValue(valueReference);
        String declaringClass = declaringClass(fieldName);
        String simpleFieldName = simpleMember(fieldName);
        if (isStatic(receiverReference)) {
            RemoteClass type = session.workspace().classValue(staticClass(receiverReference));
            (declaringClass == null ? type.getStaticField(simpleFieldName)
                    : type.getStaticField(declaringClass, simpleFieldName)).writeStatic(value);
        } else {
            RemoteObject receiver = session.workspace().objectValue(receiverReference);
            RemoteClass type = receiver.remoteClass();
            (declaringClass == null ? type.getVirtualField(simpleFieldName)
                    : type.getVirtualField(declaringClass, simpleFieldName)).write(receiver, value);
        }
    }

    /** Exact field operation used by both terminal interfaces. */
    public RemoteObject read(RemoteField field, RemoteObject receiver) {
        if (field == null) throw new IllegalArgumentException("field must not be null");
        return field.isStatic() ? field.readStatic() : field.read(receiver);
    }

    /** Exact field operation used by both terminal interfaces. */
    public void write(RemoteField field, RemoteObject receiver, RemoteObject value) {
        if (field == null) throw new IllegalArgumentException("field must not be null");
        if (field.isStatic()) field.writeStatic(value);
        else field.write(receiver, value);
    }

    /** Invokes the selected overload; exactDispatch calls its declaring implementation. */
    public RemoteObject invoke(RemoteMethod method, RemoteObject receiver,
            boolean exactDispatch, RemoteObject... arguments) {
        if (method == null) throw new IllegalArgumentException("method must not be null");
        if (method.isJvmSpecial()) {
            throw new UnsupportedOperationException(method.name()
                    + " is a JVM lifecycle method; use construct or Class.forName instead");
        }
        if (method.isStatic()) return method.callStatic(arguments);
        if (receiver == null) throw new IllegalStateException(
                "An object context is required to invoke " + method.name() + method.descriptor());
        return exactDispatch ? method.callSpecial(receiver, arguments)
                : method.call(receiver, arguments);
    }

    public RemoteContext.Assignment fieldAssignment(final RemoteField field,
            final RemoteObject receiver) {
        return new RemoteContext.Assignment() {
            @Override public void write(RemoteObject value) {
                RemoteOperations.this.write(field, receiver, value);
            }

            @Override public String description() {
                return (field.isStatic() ? "static field " : "field ")
                        + field.declaringClass() + "." + field.name();
            }
        };
    }

    public RemoteContext.Assignment arrayAssignment(final RemoteObject array, final int index) {
        return new RemoteContext.Assignment() {
            @Override public void write(RemoteObject value) { array.arraySet(index, value); }
            @Override public String description() { return "array element [" + index + "]"; }
        };
    }

    /** Re-resolves a field-backed array for every write instead of retaining a stale array handle. */
    public RemoteContext.Assignment fieldArrayAssignment(final RemoteField field,
            final RemoteObject receiver, final int index) {
        return new RemoteContext.Assignment() {
            @Override public void write(RemoteObject value) {
                try (RemoteObject array = RemoteOperations.this.read(field, receiver)) {
                    array.arraySet(index, value);
                }
            }

            @Override public String description() {
                return (field.isStatic() ? "static field " : "field ")
                        + field.declaringClass() + "." + field.name() + "[" + index + "]";
            }
        };
    }

    /** Resolves the paused stop again by sequence so a refreshed UI cannot leave a stale thread handle. */
    public RemoteContext.Assignment debuggerLocalAssignment(final long stopSequence,
            final int depth, final int slot, final String descriptor) {
        return new RemoteContext.Assignment() {
            @Override public void write(RemoteObject value) {
                List<JvmDebuggerState> states = new ArrayList<JvmDebuggerState>(
                        session.jvmti().debuggerStates());
                try {
                    for (JvmDebuggerState state : states) {
                        if (state.paused() && state.sequence() == stopSequence) {
                            session.jvmti().setDebuggerLocal(
                                    state.thread(), depth, slot, descriptor, value);
                            return;
                        }
                    }
                    throw new IllegalStateException("Debugger stop " + stopSequence
                            + " is no longer paused; the local cannot be changed");
                } finally {
                    for (JvmDebuggerState state : states) state.close();
                }
            }

            @Override public String description() {
                return "debugger local frame=" + depth + " slot=" + slot
                        + " type=" + descriptor + " stop=" + stopSequence;
            }
        };
    }

    public Object materialize(String objectReference, String type) {
        RemoteObject value = session.workspace().objectValue(objectReference);
        if ("string".equalsIgnoreCase(type)) return value.asObject(String.class);
        if ("boolean".equalsIgnoreCase(type)) return value.asObject(Boolean.class);
        if ("byte".equalsIgnoreCase(type)) return value.asObject(Byte.class);
        if ("short".equalsIgnoreCase(type)) return value.asObject(Short.class);
        if ("int".equalsIgnoreCase(type)) return value.asObject(Integer.class);
        if ("long".equalsIgnoreCase(type)) return value.asObject(Long.class);
        if ("float".equalsIgnoreCase(type)) return value.asObject(Float.class);
        if ("double".equalsIgnoreCase(type)) return value.asObject(Double.class);
        if ("char".equalsIgnoreCase(type)) return value.asObject(Character.class);
        if ("bytes".equalsIgnoreCase(type)) {
            return Base64.getEncoder().encodeToString(value.asObject(byte[].class));
        }
        throw new IllegalArgumentException("Unsupported materialization type: " + type);
    }

    private RemoteObject[] arguments(List<String> references) {
        RemoteObject[] result = new RemoteObject[references.size()];
        for (int index = 0; index < references.size(); index++) {
            result[index] = session.workspace().objectValue(references.get(index));
        }
        return result;
    }

    private static boolean isStatic(String reference) {
        return reference.toLowerCase(Locale.ROOT).startsWith(STATIC_PREFIX);
    }

    private static String staticClass(String reference) {
        return reference.substring(STATIC_PREFIX.length());
    }

    private static String declaringClass(String member) {
        int separator = member.lastIndexOf("::");
        return separator < 0 ? null : member.substring(0, separator);
    }

    private static String simpleMember(String member) {
        int separator = member.lastIndexOf("::");
        return separator < 0 ? member : member.substring(separator + 2);
    }

    private static Object localValue(String type, String value) {
        if ("null".equalsIgnoreCase(type)) return null;
        if ("string".equalsIgnoreCase(type)) return value;
        if ("boolean".equalsIgnoreCase(type)) return Boolean.valueOf(value);
        if ("byte".equalsIgnoreCase(type)) return Byte.valueOf(value);
        if ("short".equalsIgnoreCase(type)) return Short.valueOf(value);
        if ("int".equalsIgnoreCase(type)) return Integer.valueOf(value);
        if ("long".equalsIgnoreCase(type)) return Long.valueOf(value);
        if ("float".equalsIgnoreCase(type)) return Float.valueOf(value);
        if ("double".equalsIgnoreCase(type)) return Double.valueOf(value);
        if ("char".equalsIgnoreCase(type) && value.length() == 1) return Character.valueOf(value.charAt(0));
        if ("bytes".equalsIgnoreCase(type)) return Base64.getDecoder().decode(value);
        throw new IllegalArgumentException("Unsupported value type: " + type);
    }
}
