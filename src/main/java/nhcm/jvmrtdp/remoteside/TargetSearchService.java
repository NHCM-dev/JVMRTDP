package nhcm.jvmrtdp.remoteside;

import nhcm.jvmrtdp.agent.NativeAgent;
import nhcm.jvmrtdp.protocol.TextWireCodec;
import nhcm.jvmrtdp.utils.GlobMatcher;
import nhcm.jvmrtdp.utils.JavaTypeNames;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Target-side indexed searches over currently loaded classes and their declared members. */
public class TargetSearchService {
    public static final int MAX_LIMIT = 10000;

    public List<String> classes(
            String nameGlob, String kind, String packageGlob, String extendsGlob,
            String implementsGlob, int requestedLimit) {
        GlobMatcher names = GlobMatcher.of(nameGlob);
        PackageMatcher packages = new PackageMatcher(packageGlob);
        GlobMatcher superclasses = optional(extendsGlob);
        GlobMatcher interfaces = optional(implementsGlob);
        int limit = limit(requestedLimit);
        List<Class<?>> loaded = loadedClasses();
        List<String> result = new ArrayList<String>();
        for (Class<?> type : loaded) {
            if (result.size() >= limit) break;
            if ((!names.matches(type.getName()) && !names.matches(simpleName(type.getName())))
                    || !packages.matches(packageName(type))) continue;
            if (!matchesKind(type, kind)) continue;
            if (superclasses != null && !matchesSuperclass(type, superclasses)) continue;
            if (interfaces != null && !matchesInterface(type, interfaces, new LinkedHashSet<Class<?>>())) continue;
            result.add(classRow(type));
        }
        return result;
    }

    public List<String> packages(String glob, int requestedLimit) {
        GlobMatcher matcher = GlobMatcher.of(glob);
        int limit = limit(requestedLimit);
        Set<String> names = new LinkedHashSet<String>();
        names.add("");
        for (Class<?> type : loadedClasses()) {
            String name = packageName(type);
            while (!name.isEmpty()) {
                names.add(name);
                int separator = name.lastIndexOf('.');
                name = separator < 0 ? "" : name.substring(0, separator);
            }
        }
        List<String> sorted = new ArrayList<String>(names);
        Collections.sort(sorted);
        List<String> result = new ArrayList<String>();
        for (String name : sorted) {
            if (matcher.matches(name)) {
                result.add(TextWireCodec.encode(name));
                if (result.size() >= limit) break;
            }
        }
        return result;
    }

    public List<String> fields(
            String classGlob, String memberGlob, String typeGlob, String mode, int requestedLimit) {
        GlobMatcher classes = GlobMatcher.of(classGlob);
        GlobMatcher members = GlobMatcher.of(memberGlob);
        GlobMatcher types = GlobMatcher.of(typeGlob);
        int limit = limit(requestedLimit);
        List<String> result = new ArrayList<String>();
        for (Class<?> owner : loadedClasses()) {
            if (!classes.matches(owner.getName())) continue;
            Field[] declared;
            try {
                declared = owner.getDeclaredFields();
            } catch (LinkageError failure) {
                continue;
            }
            Arrays.sort(declared, new Comparator<Field>() {
                @Override public int compare(Field left, Field right) { return left.getName().compareTo(right.getName()); }
            });
            for (Field field : declared) {
                if (!members.matches(field.getName()) || !types.matches(JavaTypeNames.of(field.getType()))
                        || !matchesMode(field.getModifiers(), mode)) continue;
                result.add(TextWireCodec.encode(
                        owner.getName(), field.getName(), JvmDescriptors.of(field.getType()),
                        Integer.toString(field.getModifiers()), Boolean.toString(Modifier.isStatic(field.getModifiers()))));
                if (result.size() >= limit) return result;
            }
        }
        return result;
    }

    public List<String> methods(
            String classGlob, String memberGlob, String returnGlob, String parametersGlob,
            String mode, int requestedLimit) {
        GlobMatcher classes = GlobMatcher.of(classGlob);
        GlobMatcher members = GlobMatcher.of(memberGlob);
        GlobMatcher returns = GlobMatcher.of(returnGlob);
        GlobMatcher parameters = GlobMatcher.of(parametersGlob);
        int limit = limit(requestedLimit);
        List<String> result = new ArrayList<String>();
        for (Class<?> owner : loadedClasses()) {
            if (!classes.matches(owner.getName())) continue;
            Method[] declared;
            try {
                declared = owner.getDeclaredMethods();
            } catch (LinkageError failure) {
                continue;
            }
            Arrays.sort(declared, new Comparator<Method>() {
                @Override public int compare(Method left, Method right) {
                    int name = left.getName().compareTo(right.getName());
                    return name != 0 ? name : JvmDescriptors.of(left).compareTo(JvmDescriptors.of(right));
                }
            });
            for (Method method : declared) {
                String descriptor = JvmDescriptors.of(method);
                String parameterText = joinTypes(method.getParameterTypes());
                if (!members.matches(method.getName()) || !returns.matches(JavaTypeNames.of(method.getReturnType()))
                        || !parameters.matches(parameterText) || !matchesMode(method.getModifiers(), mode)) continue;
                result.add(TextWireCodec.encode(
                        owner.getName(), method.getName(), descriptor,
                        Integer.toString(method.getModifiers()), Boolean.toString(Modifier.isStatic(method.getModifiers()))));
                if (result.size() >= limit) return result;
            }
        }
        return result;
    }

    private static List<Class<?>> loadedClasses() {
        List<Class<?>> result = new ArrayList<Class<?>>(Arrays.asList(NativeAgent.listLoadedClasses()));
        Collections.sort(result, new Comparator<Class<?>>() {
            @Override public int compare(Class<?> left, Class<?> right) { return left.getName().compareTo(right.getName()); }
        });
        return result;
    }

    private static String classRow(Class<?> type) {
        Class<?> superclass = type.getSuperclass();
        StringBuilder interfaces = new StringBuilder();
        for (Class<?> interfaceType : type.getInterfaces()) {
            if (interfaces.length() > 0) interfaces.append(',');
            interfaces.append(interfaceType.getName());
        }
        return TextWireCodec.encode(
                type.getName(), Integer.toString(type.getModifiers()),
                superclass == null ? "" : superclass.getName(), interfaces.toString(),
                Boolean.toString(type.isInterface()), Boolean.toString(type.isEnum()),
                Boolean.toString(type.isArray()));
    }

    private static boolean matchesKind(Class<?> type, String kind) {
        if (kind == null || kind.isEmpty() || "any".equalsIgnoreCase(kind)) return true;
        if ("interface".equalsIgnoreCase(kind)) return type.isInterface() && !type.isAnnotation();
        if ("annotation".equalsIgnoreCase(kind)) return type.isAnnotation();
        if ("enum".equalsIgnoreCase(kind)) return type.isEnum();
        if ("array".equalsIgnoreCase(kind)) return type.isArray();
        if ("class".equalsIgnoreCase(kind)) return !type.isInterface() && !type.isEnum() && !type.isArray();
        throw new IllegalArgumentException("Unknown class kind: " + kind);
    }

    private static boolean matchesSuperclass(Class<?> type, GlobMatcher matcher) {
        if (type.isInterface()) return matchesInterface(type, matcher, new LinkedHashSet<Class<?>>());
        for (Class<?> current = type.getSuperclass(); current != null; current = current.getSuperclass()) {
            if (matcher.matches(current.getName())) return true;
        }
        return false;
    }

    private static boolean matchesInterface(Class<?> type, GlobMatcher matcher, Set<Class<?>> visited) {
        if (type == null || !visited.add(type)) return false;
        for (Class<?> interfaceType : type.getInterfaces()) {
            if (matcher.matches(interfaceType.getName())
                    || matchesInterface(interfaceType, matcher, visited)) return true;
        }
        return matchesInterface(type.getSuperclass(), matcher, visited);
    }

    private static boolean matchesMode(int modifiers, String mode) {
        if (mode == null || mode.isEmpty() || "all".equalsIgnoreCase(mode)) return true;
        if ("static".equalsIgnoreCase(mode)) return Modifier.isStatic(modifiers);
        if ("virtual".equalsIgnoreCase(mode)) return !Modifier.isStatic(modifiers);
        throw new IllegalArgumentException("Member mode must be all, static or virtual: " + mode);
    }

    private static String packageName(Class<?> type) {
        if (type.isArray() || type.isPrimitive()) return "";
        String name = type.getName();
        int separator = name.lastIndexOf('.');
        return separator < 0 ? "" : name.substring(0, separator);
    }

    private static String simpleName(String className) {
        int separator = className.lastIndexOf('.');
        return separator < 0 ? className : className.substring(separator + 1);
    }

    private static String joinTypes(Class<?>[] types) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < types.length; index++) {
            if (index > 0) result.append(',');
            result.append(JavaTypeNames.of(types[index]));
        }
        return result.toString();
    }

    private static GlobMatcher optional(String glob) {
        return glob == null || glob.isEmpty() ? null : GlobMatcher.of(glob);
    }

    private static class PackageMatcher {
        private final boolean all;
        private final String treeRoot;
        private final GlobMatcher glob;

        private PackageMatcher(String value) {
            String pattern = value == null || value.isEmpty() ? "*" : value;
            all = "**".equals(pattern);
            treeRoot = pattern.endsWith(".**") ? pattern.substring(0, pattern.length() - 3) : null;
            glob = all || treeRoot != null ? null : GlobMatcher.of(pattern);
        }

        private boolean matches(String packageName) {
            if (all) return true;
            if (treeRoot != null) return packageName.equals(treeRoot) || packageName.startsWith(treeRoot + ".");
            return glob.matches(packageName);
        }
    }

    private static int limit(int requested) {
        if (requested < 1 || requested > MAX_LIMIT) {
            throw new IllegalArgumentException("Search limit must be between 1 and " + MAX_LIMIT);
        }
        return requested;
    }
}
