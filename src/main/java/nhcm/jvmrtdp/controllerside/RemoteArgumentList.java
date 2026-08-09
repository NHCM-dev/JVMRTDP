package nhcm.jvmrtdp.controllerside;

import nhcm.jvmrtdp.command.CommandLine;
import nhcm.jvmrtdp.handles.java.RemoteClass;
import nhcm.jvmrtdp.handles.java.RemoteField;
import nhcm.jvmrtdp.handles.java.RemoteMethod;
import nhcm.jvmrtdp.handles.java.RemoteObject;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/** Resolves CLI literals, references and nested target-side value expressions. */
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
                values[index] = resolveValue(session, expressions.get(index), owned);
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

    /** Transfers ownership of a single generated value to the caller. References remain borrowed. */
    public RemoteObject transferOnly() {
        RemoteObject value = only();
        owned.remove(value);
        return value;
    }

    @Override
    public void close() {
        closeAll(owned);
    }

    private static RemoteObject resolveValue(
            TargetSession session, String expression, List<RemoteObject> owned) {
        if (isCompoundExpression(expression)) {
            ExpressionValue value = evaluate(session,
                    expression.substring(1, expression.length() - 1).trim(), owned);
            if (value.isClass()) {
                throw new IllegalArgumentException(
                        "A type reference cannot be used as an object value; use class:<name> for java.lang.Class");
            }
            return value.object;
        }

        RemoteObject referenced = reference(session, expression);
        if (referenced != null) return referenced;

        RemoteObject literal;
        String lower = expression.toLowerCase(Locale.ROOT);
        if (lower.startsWith("class:") && expression.length() > "class:".length()) {
            literal = session.jni().classValue(expression.substring("class:".length()));
        } else if (lower.startsWith("enum:")) {
            int constantSeparator = expression.lastIndexOf(':');
            if (constantSeparator <= "enum:".length() || constantSeparator == expression.length() - 1) {
                throw new IllegalArgumentException("Enum literal must be enum:<class>:<constant>");
            }
            literal = session.jni().enumValue(
                    expression.substring("enum:".length(), constantSeparator),
                    expression.substring(constantSeparator + 1));
        } else {
            literal = session.jni().valueOf(localValue(expression));
        }
        owned.add(literal);
        return literal;
    }

    private static boolean isCompoundExpression(String expression) {
        return expression.length() >= 2 && expression.charAt(0) == '{'
                && expression.charAt(expression.length() - 1) == '}';
    }

    /** Evaluates an expression without ever selecting it as the interactive context. */
    private static ExpressionValue evaluate(
            TargetSession session, String source, List<RemoteObject> owned) {
        List<String> chain = splitReferenceChain(source);
        ExpressionValue value = evaluateAtomic(session, chain.get(0), owned);
        for (int index = 1; index < chain.size(); index++) {
            value = evaluateStep(session, value, chain.get(index), owned);
        }
        return value;
    }

    private static ExpressionValue evaluateAtomic(
            TargetSession session, String source, List<RemoteObject> owned) {
        String expression = source.trim();
        if (expression.isEmpty()) throw new IllegalArgumentException("Value expression must not be empty");
        if (isCompoundExpression(expression)) {
            return evaluate(session, expression.substring(1, expression.length() - 1).trim(), owned);
        }

        CommandLine line = CommandLine.parse(expression);
        String operation = line.name();
        List<String> arguments = line.arguments();
        if (("type".equals(operation) || "class".equals(operation)) && arguments.size() == 1) {
            RemoteClass type = session.findClass(arguments.get(0));
            type.info();
            return ExpressionValue.of(type);
        }
        if (("new".equals(operation) || "construct".equals(operation)) && arguments.size() >= 2) {
            RemoteClass type = session.findClass(arguments.get(0));
            RemoteObject result = type.construct(arguments.get(1),
                    resolveValues(session, arguments.subList(2, arguments.size()), owned));
            return own(result, owned, "Constructor expression");
        }
        if ("static-field".equals(operation) && arguments.size() == 2) {
            return readField(ExpressionValue.of(session.findClass(arguments.get(0))),
                    arguments.get(1), owned);
        }
        if ("static".equals(operation)) {
            int offset = !arguments.isEmpty() && "invoke".equalsIgnoreCase(arguments.get(0)) ? 1 : 0;
            if (arguments.size() >= offset + 3) {
                ExpressionValue receiver = ExpressionValue.of(session.findClass(arguments.get(offset)));
                return invoke(session, receiver, arguments.get(offset + 1), arguments.get(offset + 2),
                        arguments.subList(offset + 3, arguments.size()), owned);
            }
        }
        if (("invoke".equals(operation) || "call".equals(operation)) && arguments.size() >= 3) {
            ExpressionValue receiver = evaluateToken(session, arguments.get(0), owned);
            return invoke(session, receiver, arguments.get(1), arguments.get(2),
                    arguments.subList(3, arguments.size()), owned);
        }
        if ("field".equals(operation) && arguments.size() == 2) {
            return readField(evaluateToken(session, arguments.get(0), owned), arguments.get(1), owned);
        }
        if ("index".equals(operation) && arguments.size() == 2) {
            return arrayElement(evaluateToken(session, arguments.get(0), owned),
                    integer(arguments.get(1), "array index"), owned);
        }
        if (("context".equals(operation) || "this".equals(operation)) && arguments.isEmpty()) {
            return current(session);
        }
        if (arguments.isEmpty()) {
            return ExpressionValue.of(resolveValue(session, expression, owned));
        }
        throw new IllegalArgumentException("Unknown or malformed value expression: {" + source + "}");
    }

    private static ExpressionValue evaluateStep(
            TargetSession session, ExpressionValue receiver, String source, List<RemoteObject> owned) {
        CommandLine line = CommandLine.parse(source);
        String operation = line.name();
        List<String> arguments = line.arguments();
        if ("field".equals(operation) && arguments.size() == 1) {
            return readField(receiver, arguments.get(0), owned);
        }
        if (("invoke".equals(operation) || "call".equals(operation)) && arguments.size() >= 2) {
            return invoke(session, receiver, arguments.get(0), arguments.get(1),
                    arguments.subList(2, arguments.size()), owned);
        }
        if ("index".equals(operation) && arguments.size() == 1) {
            return arrayElement(receiver, integer(arguments.get(0), "array index"), owned);
        }
        if (("new".equals(operation) || "construct".equals(operation)) && arguments.size() >= 1) {
            if (!receiver.isClass()) {
                throw new IllegalArgumentException("construct in a reference chain requires a type receiver");
            }
            RemoteObject result = receiver.type.construct(arguments.get(0),
                    resolveValues(session, arguments.subList(1, arguments.size()), owned));
            return own(result, owned, "Constructor expression");
        }
        if ("as".equals(operation) && arguments.size() == 1) {
            RemoteObject object = receiver.requireObject("as");
            RemoteClass view = session.findClass(arguments.get(0));
            view.info();
            if (!object.isNull() && !view.isInstance(object)) {
                throw new IllegalArgumentException(object.className() + " is not assignable to " + view.className());
            }
            return ExpressionValue.of(object, view);
        }
        if ("runtime".equals(operation) && arguments.isEmpty()) {
            RemoteObject object = receiver.requireObject("runtime");
            return ExpressionValue.of(object, object.remoteClass());
        }
        throw new IllegalArgumentException("Unknown or malformed reference-chain step: " + source);
    }

    private static ExpressionValue evaluateToken(
            TargetSession session, String token, List<RemoteObject> owned) {
        if (isCompoundExpression(token)) {
            return evaluate(session, token.substring(1, token.length() - 1).trim(), owned);
        }
        if ("context".equalsIgnoreCase(token) || "this".equalsIgnoreCase(token)) return current(session);
        return ExpressionValue.of(resolveValue(session, token, owned));
    }

    private static ExpressionValue current(TargetSession session) {
        if (session.context().isClass()) return ExpressionValue.of(session.context().remoteClass());
        return ExpressionValue.of(session.context().remoteObject(), session.context().remoteClass());
    }

    private static ExpressionValue readField(
            ExpressionValue receiver, String expression, List<RemoteObject> owned) {
        MemberSelection selection = MemberSelection.parse(expression, "field");
        RemoteObject fieldValue;
        if (receiver.isClass()) {
            RemoteField field = selection.declaringClass == null
                    ? receiver.type.getStaticField(selection.member)
                    : receiver.type.getStaticField(selection.declaringClass, selection.member);
            fieldValue = field.readStatic();
        } else {
            RemoteField field = selection.declaringClass == null
                    ? receiver.lookupType().getVirtualField(selection.member)
                    : receiver.lookupType().getVirtualField(selection.declaringClass, selection.member);
            fieldValue = field.read(receiver.object);
        }
        if (selection.index == null) return own(fieldValue, owned, "Field expression");
        try {
            return own(fieldValue.arrayGet(selection.index.intValue()), owned, "Array field expression");
        } finally {
            fieldValue.close();
        }
    }

    private static ExpressionValue invoke(
            TargetSession session,
            ExpressionValue receiver,
            String expression,
            String descriptor,
            List<String> argumentExpressions,
            List<RemoteObject> owned) {
        MemberSelection selection = MemberSelection.parse(expression, "method");
        RemoteObject[] arguments = resolveValues(session, argumentExpressions, owned);
        RemoteObject result;
        if (receiver.isClass()) {
            RemoteMethod method = selection.declaringClass == null
                    ? receiver.type.getStaticMethod(selection.member, descriptor)
                    : receiver.type.getStaticMethod(selection.declaringClass, selection.member, descriptor);
            result = method.callStatic(arguments);
        } else {
            RemoteMethod method = selection.declaringClass == null
                    ? receiver.lookupType().getVirtualMethod(selection.member, descriptor)
                    : receiver.lookupType().getVirtualMethod(selection.declaringClass, selection.member, descriptor);
            result = selection.declaringClass == null
                    ? method.call(receiver.object, arguments)
                    : method.callSpecial(receiver.object, arguments);
        }
        return own(result, owned, "Invocation expression");
    }

    private static ExpressionValue arrayElement(
            ExpressionValue receiver, int index, List<RemoteObject> owned) {
        return own(receiver.requireObject("index").arrayGet(index), owned, "Array expression");
    }

    private static ExpressionValue own(
            RemoteObject value, List<RemoteObject> owned, String description) {
        if ("void".equals(value.className())) {
            value.close();
            throw new IllegalArgumentException(description + " returned void and cannot be used as a value");
        }
        owned.add(value);
        return ExpressionValue.of(value);
    }

    private static RemoteObject[] resolveValues(
            TargetSession session, List<String> expressions, List<RemoteObject> owned) {
        RemoteObject[] result = new RemoteObject[expressions.size()];
        for (int index = 0; index < expressions.size(); index++) {
            result[index] = resolveValue(session, expressions.get(index), owned);
        }
        return result;
    }

    static List<String> splitReferenceChain(String source) {
        List<String> result = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        int expressionDepth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < source.length(); index++) {
            char value = source.charAt(index);
            if (escaped) {
                current.append(value);
                escaped = false;
            } else if (value == '\\' && quoted) {
                current.append(value);
                escaped = true;
            } else if (value == '"') {
                quoted = !quoted;
                current.append(value);
            } else if (!quoted && value == '{') {
                expressionDepth++;
                current.append(value);
            } else if (!quoted && value == '}') {
                if (expressionDepth == 0) throw new IllegalArgumentException("Unexpected } in value expression");
                expressionDepth--;
                current.append(value);
            } else if (!quoted && expressionDepth == 0 && value == '-'
                    && index + 1 < source.length() && source.charAt(index + 1) == '>') {
                addChainSegment(result, current);
                current.setLength(0);
                index++;
            } else {
                current.append(value);
            }
        }
        if (quoted) throw new IllegalArgumentException("Unclosed quote in value expression");
        if (expressionDepth != 0) throw new IllegalArgumentException("Unclosed { in value expression");
        addChainSegment(result, current);
        return result;
    }

    private static void addChainSegment(List<String> result, StringBuilder current) {
        String segment = current.toString().trim();
        if (segment.isEmpty()) throw new IllegalArgumentException("Reference chain contains an empty step");
        result.add(segment);
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

    private static int integer(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer: " + value);
        }
    }

    private static final class ExpressionValue {
        private final RemoteClass type;
        private final RemoteObject object;
        private final RemoteClass viewType;

        private ExpressionValue(RemoteClass type, RemoteObject object, RemoteClass viewType) {
            this.type = type;
            this.object = object;
            this.viewType = viewType;
        }

        private static ExpressionValue of(RemoteClass type) {
            return new ExpressionValue(type, null, null);
        }

        private static ExpressionValue of(RemoteObject object) {
            return new ExpressionValue(null, object, null);
        }

        private static ExpressionValue of(RemoteObject object, RemoteClass viewType) {
            return new ExpressionValue(null, object, viewType);
        }

        private boolean isClass() {
            return object == null;
        }

        private RemoteClass lookupType() {
            return viewType == null ? object.remoteClass() : viewType;
        }

        private RemoteObject requireObject(String operation) {
            if (object == null) throw new IllegalArgumentException(operation + " requires an object receiver");
            return object;
        }
    }

    private static final class MemberSelection {
        private final String declaringClass;
        private final String member;
        private final Integer index;

        private MemberSelection(String declaringClass, String member, Integer index) {
            this.declaringClass = declaringClass;
            this.member = member;
            this.index = index;
        }

        private static MemberSelection parse(String expression, String kind) {
            int open = "field".equals(kind) ? expression.lastIndexOf('[') : -1;
            String name = open < 0 || !expression.endsWith("]")
                    ? expression : expression.substring(0, open);
            Integer index = null;
            if (open >= 0 && expression.endsWith("]")) {
                index = Integer.valueOf(integer(
                        expression.substring(open + 1, expression.length() - 1), "array index"));
            }
            int qualifier = name.lastIndexOf("::");
            String declaringClass = qualifier < 0 ? null : name.substring(0, qualifier);
            String member = qualifier < 0 ? name : name.substring(qualifier + 2);
            if (member.isEmpty()) throw new IllegalArgumentException(kind + " name must not be empty");
            if (qualifier >= 0 && declaringClass.isEmpty()) {
                throw new IllegalArgumentException("Declaring class must not be empty before ::");
            }
            return new MemberSelection(declaringClass, member, index);
        }
    }
}
