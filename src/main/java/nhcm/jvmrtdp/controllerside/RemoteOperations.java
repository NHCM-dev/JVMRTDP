package nhcm.jvmrtdp.controllerside;

import nhcm.jvmrtdp.handles.java.RemoteClass;
import nhcm.jvmrtdp.handles.java.RemoteObject;

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
