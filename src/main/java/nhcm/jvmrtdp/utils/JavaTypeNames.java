package nhcm.jvmrtdp.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Converts JVM descriptors into source-like Java type names. */
public class JavaTypeNames {
    private JavaTypeNames() {
    }

    public static String of(Class<?> type) {
        if (type.isArray()) return of(type.getComponentType()) + "[]";
        return type.getName();
    }

    public static String fromDescriptor(String descriptor) {
        ParseResult result = parse(descriptor, 0);
        if (result.next != descriptor.length()) {
            throw new IllegalArgumentException("Invalid JVM type descriptor: " + descriptor);
        }
        return result.type;
    }

    public static List<String> parameterTypes(String methodDescriptor) {
        requireMethod(methodDescriptor);
        List<String> result = new ArrayList<String>();
        int offset = 1;
        while (methodDescriptor.charAt(offset) != ')') {
            ParseResult parameter = parse(methodDescriptor, offset);
            result.add(parameter.type);
            offset = parameter.next;
        }
        return Collections.unmodifiableList(result);
    }

    public static String returnType(String methodDescriptor) {
        requireMethod(methodDescriptor);
        int end = methodDescriptor.indexOf(')');
        return parse(methodDescriptor, end + 1).type;
    }

    private static void requireMethod(String descriptor) {
        if (descriptor == null || descriptor.length() < 3 || descriptor.charAt(0) != '('
                || descriptor.indexOf(')') < 1) {
            throw new IllegalArgumentException("Invalid JVM method descriptor: " + descriptor);
        }
    }

    private static ParseResult parse(String descriptor, int offset) {
        if (descriptor == null || offset < 0 || offset >= descriptor.length()) {
            throw new IllegalArgumentException("Invalid JVM type descriptor: " + descriptor);
        }
        int arrays = 0;
        while (offset < descriptor.length() && descriptor.charAt(offset) == '[') {
            arrays++;
            offset++;
        }
        if (offset >= descriptor.length()) throw new IllegalArgumentException("Invalid JVM descriptor: " + descriptor);
        char kind = descriptor.charAt(offset++);
        String type;
        switch (kind) {
        case 'V': type = "void"; break;
        case 'Z': type = "boolean"; break;
        case 'B': type = "byte"; break;
        case 'C': type = "char"; break;
        case 'S': type = "short"; break;
        case 'I': type = "int"; break;
        case 'J': type = "long"; break;
        case 'F': type = "float"; break;
        case 'D': type = "double"; break;
        case 'L':
            int end = descriptor.indexOf(';', offset);
            if (end < 0) throw new IllegalArgumentException("Invalid JVM descriptor: " + descriptor);
            type = descriptor.substring(offset, end).replace('/', '.');
            offset = end + 1;
            break;
        default:
            throw new IllegalArgumentException("Invalid JVM descriptor: " + descriptor);
        }
        StringBuilder name = new StringBuilder(type);
        for (int index = 0; index < arrays; index++) name.append("[]");
        return new ParseResult(name.toString(), offset);
    }

    private static class ParseResult {
        private final String type;
        private final int next;

        private ParseResult(String type, int next) {
            this.type = type;
            this.next = next;
        }
    }
}
