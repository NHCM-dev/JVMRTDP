package nhcm.jvmrtdp.remoteside;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public class JvmDescriptors {
    private JvmDescriptors() {
    }

    public static String of(Method method) {
        return method(method.getParameterTypes(), method.getReturnType());
    }

    public static String of(Constructor<?> constructor) {
        return method(constructor.getParameterTypes(), void.class);
    }

    public static String of(Class<?> type) {
        if (type.isArray()) {
            return type.getName().replace('.', '/');
        }
        if (type.isPrimitive()) {
            if (type == void.class) return "V";
            if (type == boolean.class) return "Z";
            if (type == byte.class) return "B";
            if (type == char.class) return "C";
            if (type == short.class) return "S";
            if (type == int.class) return "I";
            if (type == long.class) return "J";
            if (type == float.class) return "F";
            if (type == double.class) return "D";
        }
        return "L" + type.getName().replace('.', '/') + ";";
    }

    private static String method(Class<?>[] parameters, Class<?> returnType) {
        StringBuilder descriptor = new StringBuilder("(");
        for (Class<?> parameter : parameters) {
            descriptor.append(of(parameter));
        }
        return descriptor.append(')').append(of(returnType)).toString();
    }
}
