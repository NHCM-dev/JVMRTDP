package nhcm.jvmrtdp.controllerside.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Small brace-aware extractor for selecting one method from decompiler output. */
final class JavaMethodExtractor {
    private JavaMethodExtractor() {}

    static String extract(String source, String simpleClassName, String methodName, String descriptor) {
        Extraction extraction = extractDetails(source, simpleClassName, methodName, descriptor);
        return extraction == null ? null : extraction.source();
    }

    static Extraction extractDetails(
            String source, String simpleClassName, String methodName, String descriptor) {
        List<String> parameterTypes = descriptorParameterTypes(descriptor);
        if (parameterTypes == null) return null;
        String sourceName = "<init>".equals(methodName) ? simpleClassName : methodName;
        int search = 0;
        while (search < source.length()) {
            int name = identifier(source, sourceName, search);
            if (name < 0) return null;
            int openParen = skipWhitespace(source, name + sourceName.length());
            if (openParen >= source.length() || source.charAt(openParen) != '(') {
                search = name + sourceName.length();
                continue;
            }
            int closeParen = matching(source, openParen, '(', ')');
            if (closeParen < 0 || !parametersMatch(
                    source.substring(openParen + 1, closeParen), parameterTypes)) {
                search = openParen + 1;
                continue;
            }
            if (!isMethodDeclaration(source, name, closeParen, simpleClassName, methodName)) {
                search = closeParen + 1;
                continue;
            }
            int body = skipThrowsAndWhitespace(source, closeParen + 1);
            if (body >= source.length()) return null;
            int start = declarationStart(source, name);
            if (source.charAt(body) == ';') return extraction(source, start, body + 1);
            if (source.charAt(body) != '{') {
                search = closeParen + 1;
                continue;
            }
            int end = matching(source, body, '{', '}');
            return end < 0 ? null : extraction(source, start, end + 1);
        }
        return null;
    }

    /**
     * Distinguishes a declaration from an invocation with the same name and descriptor.
     * A parameter-only match is not sufficient: for example, {@code this.refresh();}
     * used to be returned instead of a later {@code private void refresh()} declaration.
     */
    private static boolean isMethodDeclaration(String source, int name, int closeParen,
            String simpleClassName, String methodName) {
        int previous = previousNonWhitespace(source, name - 1);
        if (previous >= 0) {
            char value = source.charAt(previous);
            if (value == '.' || value == ':' || value == ')' || value == ']') return false;
        }

        int start = declarationStart(source, name);
        String prefix = removeAnnotations(source.substring(start, name)).trim();
        if (prefix.isEmpty()) return "<init>".equals(methodName);

        String withoutGenerics = eraseGenerics(prefix).trim();
        if (containsExpressionSyntax(withoutGenerics)) return false;
        String declarationType = withoutGenerics.replaceFirst(
                "^(?:(?:public|protected|private|static|final|abstract|synchronized|native|strictfp|default)\\b\\s*)*",
                "").trim();

        if ("<init>".equals(methodName)) {
            // A constructor's name is excluded from prefix; only modifiers/type parameters remain.
            return declarationType.isEmpty();
        }
        if (declarationType.isEmpty() || startsWithStatementKeyword(declarationType)) return false;

        // Once annotations, modifiers and generic arguments are removed, a Java return type
        // contains identifiers, qualification dots, array brackets and whitespace only.
        return declarationType.matches("[\\p{javaJavaIdentifierPart}.$\\[\\]\\s]+")
                && !declarationType.equals(simpleClassName);
    }

    private static int previousNonWhitespace(String source, int index) {
        while (index >= 0 && Character.isWhitespace(source.charAt(index))) index--;
        return index;
    }

    private static boolean containsExpressionSyntax(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '(' || current == ')' || current == '=' || current == ';'
                    || current == '{' || current == '}' || current == '?' || current == ':'
                    || current == ',' || current == '"' || current == '\'') return true;
        }
        return value.contains("->");
    }

    private static boolean startsWithStatementKeyword(String value) {
        String first = value.split("\\s+", 2)[0];
        return first.equals("if") || first.equals("for") || first.equals("while")
                || first.equals("switch") || first.equals("return") || first.equals("throw")
                || first.equals("new") || first.equals("case") || first.equals("assert")
                || first.equals("try") || first.equals("catch") || first.equals("else")
                || first.equals("do") || first.equals("break") || first.equals("continue");
    }

    private static Extraction extraction(String source, int start, int end) {
        while (start < end && Character.isWhitespace(source.charAt(start))) start++;
        while (end > start && Character.isWhitespace(source.charAt(end - 1))) end--;
        int startLine = 1;
        for (int index = 0; index < start; index++) if (source.charAt(index) == '\n') startLine++;
        return new Extraction(source.substring(start, end), startLine);
    }

    static final class Extraction {
        private final String source;
        private final int startLine;
        Extraction(String source, int startLine) { this.source = source; this.startLine = startLine; }
        String source() { return source; }
        int startLine() { return startLine; }
    }

    private static int identifier(String text, String value, int from) {
        int index = text.indexOf(value, from);
        while (index >= 0) {
            boolean left = index == 0 || !Character.isJavaIdentifierPart(text.charAt(index - 1));
            int rightIndex = index + value.length();
            boolean right = rightIndex == text.length() || !Character.isJavaIdentifierPart(text.charAt(rightIndex));
            if (left && right && !insideCommentOrString(text, index)) return index;
            index = text.indexOf(value, index + 1);
        }
        return -1;
    }

    private static boolean insideCommentOrString(String text, int position) {
        ScanState state = new ScanState();
        for (int index = 0; index < position; index++) index = state.accept(text, index);
        return state.quoted || state.lineComment || state.blockComment;
    }

    private static int matching(String text, int start, char open, char close) {
        int depth = 0;
        ScanState state = new ScanState();
        for (int index = start; index < text.length(); index++) {
            int consumed = state.accept(text, index);
            if (!state.inIgnoredText()) {
                char value = text.charAt(index);
                if (value == open) depth++;
                else if (value == close && --depth == 0) return index;
            }
            index = consumed;
        }
        return -1;
    }

    private static int skipWhitespace(String text, int index) {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
        return index;
    }

    private static int skipThrowsAndWhitespace(String text, int index) {
        while (index < text.length()) {
            char value = text.charAt(index);
            if (value == '{' || value == ';') return index;
            index++;
        }
        return index;
    }

    private static int declarationStart(String text, int name) {
        int line = text.lastIndexOf('\n', name);
        int previous = line < 0 ? -1 : text.lastIndexOf('\n', line - 1);
        while (previous >= 0) {
            String candidate = text.substring(previous + 1, line).trim();
            if (!candidate.startsWith("@")) break;
            line = previous;
            previous = text.lastIndexOf('\n', line - 1);
        }
        return line + 1;
    }

    private static boolean parametersMatch(String source, List<String> expected) {
        List<String> actual = splitParameters(source);
        if (actual.size() != expected.size()) return false;
        for (int index = 0; index < expected.size(); index++) {
            String sourceType = sourceParameterType(actual.get(index));
            String descriptorType = expected.get(index);
            if (sourceType.isEmpty() || (!sourceType.equals(descriptorType)
                    && !descriptorType.endsWith('.' + sourceType)
                    && !sourceType.endsWith('.' + descriptorType))) return false;
        }
        return true;
    }

    private static List<String> splitParameters(String parameters) {
        if (parameters.trim().isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<String>();
        int start = 0;
        int generic = 0;
        int nested = 0;
        for (int index = 0; index < parameters.length(); index++) {
            char value = parameters.charAt(index);
            if (value == '<') generic++;
            else if (value == '>') generic = Math.max(0, generic - 1);
            else if (value == '(' || value == '[' || value == '{') nested++;
            else if (value == ')' || value == ']' || value == '}') nested = Math.max(0, nested - 1);
            else if (value == ',' && generic == 0 && nested == 0) {
                result.add(parameters.substring(start, index));
                start = index + 1;
            }
        }
        result.add(parameters.substring(start));
        return result;
    }

    private static String sourceParameterType(String parameter) {
        String value = removeAnnotations(parameter).trim();
        value = value.replaceAll("\\b(final|volatile|transient)\\b", " ").trim();
        String arrayAfterName = "";
        while (value.endsWith("[]")) {
            arrayAfterName += "[]";
            value = value.substring(0, value.length() - 2).trim();
        }
        int end = value.length();
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) end--;
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(value.charAt(start - 1))) start--;
        if (start == end) return "";
        value = value.substring(0, start).trim() + arrayAfterName;
        value = eraseGenerics(value).replace("...", "[]").replace('$', '.');
        return value.replaceAll("\\s+", "");
    }

    private static String removeAnnotations(String value) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length();) {
            if (value.charAt(index) != '@') {
                result.append(value.charAt(index++));
                continue;
            }
            index++;
            while (index < value.length() && (Character.isJavaIdentifierPart(value.charAt(index))
                    || value.charAt(index) == '.')) index++;
            while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
            if (index < value.length() && value.charAt(index) == '(') {
                int end = matching(value, index, '(', ')');
                index = end < 0 ? value.length() : end + 1;
            }
        }
        return result.toString();
    }

    private static String eraseGenerics(String value) {
        StringBuilder result = new StringBuilder();
        int depth = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '<') depth++;
            else if (current == '>') depth = Math.max(0, depth - 1);
            else if (depth == 0) result.append(current);
        }
        return result.toString();
    }

    private static List<String> descriptorParameterTypes(String descriptor) {
        int index = descriptor.indexOf('(');
        int end = descriptor.indexOf(')', index + 1);
        if (index != 0 || end < 0) return null;
        List<String> result = new ArrayList<String>();
        index++;
        while (index < end) {
            int dimensions = 0;
            while (index < end && descriptor.charAt(index) == '[') {
                dimensions++;
                index++;
            }
            if (index >= end) return null;
            char kind = descriptor.charAt(index++);
            String type;
            switch (kind) {
                case 'B': type = "byte"; break;
                case 'C': type = "char"; break;
                case 'D': type = "double"; break;
                case 'F': type = "float"; break;
                case 'I': type = "int"; break;
                case 'J': type = "long"; break;
                case 'S': type = "short"; break;
                case 'Z': type = "boolean"; break;
                case 'L':
                    int terminator = descriptor.indexOf(';', index);
                    if (terminator < 0 || terminator > end) return null;
                    type = descriptor.substring(index, terminator).replace('/', '.').replace('$', '.');
                    index = terminator + 1;
                    break;
                default: return null;
            }
            StringBuilder arrayType = new StringBuilder(type);
            for (int dimension = 0; dimension < dimensions; dimension++) arrayType.append("[]");
            result.add(arrayType.toString());
        }
        return result;
    }

    private static final class ScanState {
        private boolean quoted;
        private char quote;
        private boolean escaped;
        private boolean lineComment;
        private boolean blockComment;

        private int accept(String text, int index) {
            char value = text.charAt(index);
            char next = index + 1 < text.length() ? text.charAt(index + 1) : '\0';
            if (lineComment) {
                if (value == '\n') lineComment = false;
                return index;
            }
            if (blockComment) {
                if (value == '*' && next == '/') {
                    blockComment = false;
                    return index + 1;
                }
                return index;
            }
            if (quoted) {
                if (escaped) escaped = false;
                else if (value == '\\') escaped = true;
                else if (value == quote) quoted = false;
                return index;
            }
            if (value == '/' && next == '/') {
                lineComment = true;
                return index + 1;
            }
            if (value == '/' && next == '*') {
                blockComment = true;
                return index + 1;
            }
            if (value == '"' || value == '\'') {
                quoted = true;
                quote = value;
            }
            return index;
        }

        private boolean inIgnoredText() {
            return quoted || lineComment || blockComment;
        }
    }
}
