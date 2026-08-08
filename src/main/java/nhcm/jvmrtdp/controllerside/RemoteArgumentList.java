package nhcm.jvmrtdp.controllerside;

import nhcm.jvmrtdp.handles.java.RemoteObject;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/** Resolves CLI literals and references into target-side object handles. */
public class RemoteArgumentList implements AutoCloseable {
    private final RemoteObject[] values;
    private final List<RemoteObject> owned;

    private RemoteArgumentList(RemoteObject[] values, List<RemoteObject> owned) {
        this.values = values;
        this.owned = owned;
    }

    public static RemoteArgumentList resolve(TargetSession session, List<String> expressions) {
        RemoteObject[] values = new RemoteObject[expressions.size()];
        List<RemoteObject> owned = new ArrayList<RemoteObject>();
        try {
            for (int index = 0; index < expressions.size(); index++) {
                String expression = expressions.get(index);
                RemoteObject referenced = reference(session, expression);
                if (referenced != null) {
                    values[index] = referenced;
                } else {
                    RemoteObject literal = session.jni().valueOf(localValue(expression));
                    values[index] = literal;
                    owned.add(literal);
                }
            }
        } catch (RuntimeException failure) {
            closeAll(owned);
            throw failure;
        }
        return new RemoteArgumentList(values, owned);
    }

    public RemoteObject[] values() {
        return values.clone();
    }

    public RemoteObject only() {
        if (values.length != 1) throw new IllegalStateException("Exactly one value is required");
        return values[0];
    }

    /** Transfers ownership of a single generated literal to the caller. References remain borrowed. */
    public RemoteObject transferOnly() {
        RemoteObject value = only();
        owned.remove(value);
        return value;
    }

    @Override
    public void close() {
        closeAll(owned);
    }

    private static void closeAll(List<RemoteObject> objects) {
        for (RemoteObject object : objects) {
            try {
                object.close();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static RemoteObject reference(TargetSession session, String expression) {
        if ("this".equalsIgnoreCase(expression) || "context".equalsIgnoreCase(expression)) {
            return session.context().remoteObject();
        }
        if (!expression.isEmpty()) {
            String normalized = RemoteWorkspace.normalize(expression);
            RemoteObject value = session.workspace().objects().get(normalized);
            if (value != null) return value;
        }
        if (expression.startsWith("$") || expression.startsWith("@")) {
            throw new IllegalArgumentException("Unknown object variable: " + expression);
        }
        return null;
    }

    private static Object localValue(String expression) {
        String lower = expression.toLowerCase(Locale.ROOT);
        if ("null".equals(lower)) return null;
        if ("true".equals(lower) || "false".equals(lower)) return Boolean.valueOf(lower);

        int separator = expression.indexOf(':');
        if (separator > 0) {
            String type = lower.substring(0, separator);
            String value = expression.substring(separator + 1);
            if ("string".equals(type) || "str".equals(type)) return value;
            if ("boolean".equals(type) || "bool".equals(type)) return Boolean.valueOf(value);
            if ("byte".equals(type)) return Byte.valueOf(value);
            if ("short".equals(type)) return Short.valueOf(value);
            if ("int".equals(type)) return Integer.valueOf(value);
            if ("long".equals(type)) return Long.valueOf(value);
            if ("float".equals(type)) return Float.valueOf(value);
            if ("double".equals(type)) return Double.valueOf(value);
            if ("char".equals(type) && value.length() == 1) return Character.valueOf(value.charAt(0));
            if ("bytes".equals(type)) return Base64.getDecoder().decode(value);
        }

        try {
            if (lower.endsWith("l")) return Long.valueOf(expression.substring(0, expression.length() - 1));
            if (lower.endsWith("f")) return Float.valueOf(expression.substring(0, expression.length() - 1));
            if (expression.indexOf('.') >= 0) return Double.valueOf(expression);
            return Integer.valueOf(expression);
        } catch (NumberFormatException ignored) {
            return expression;
        }
    }
}
