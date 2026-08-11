package nhcm.jvmrtdp.controllerside.tui;

import nhcm.jvmrtdp.handles.java.RemoteClassInfo;
import nhcm.jvmrtdp.handles.java.RemoteField;
import nhcm.jvmrtdp.handles.java.RemoteMethod;
import nhcm.jvmrtdp.handles.java.RemotePackage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Package-first browser rules, including the default runtime/noise exclusions. */
final class TuiBrowserModel {
    private TuiBrowserModel() {}

    static List<TuiBrowserEntry> packageEntries(RemotePackage source, boolean showRuntime) {
        return packageEntries(source, showRuntime, false);
    }

    static List<TuiBrowserEntry> packageEntries(RemotePackage source,
            boolean showRuntime, boolean showArrays) {
        List<TuiBrowserEntry> result = new ArrayList<TuiBrowserEntry>();
        String parent = parentPackage(source.name());
        if (!source.name().isEmpty()) result.add(TuiBrowserEntry.parent(parent));
        for (String name : source.packages()) {
            if (showRuntime || !runtimeNamespace(name)) result.add(TuiBrowserEntry.packageEntry(name));
        }
        for (String name : source.classes()) {
            if (visibleClass(name, showRuntime, showArrays)) {
                result.add(TuiBrowserEntry.classEntry(name, null));
            }
        }
        sort(result);
        return result;
    }

    static List<TuiBrowserEntry> searchEntries(List<String> packages,
            List<RemoteClassInfo> classes, boolean showRuntime) {
        return searchEntries(packages, classes, Collections.<RemoteField>emptyList(),
                Collections.<RemoteMethod>emptyList(), showRuntime, false);
    }

    static List<TuiBrowserEntry> searchEntries(List<String> packages,
            List<RemoteClassInfo> classes, List<RemoteField> fields,
            List<RemoteMethod> methods, boolean showRuntime) {
        return searchEntries(packages, classes, fields, methods, showRuntime, false);
    }

    static List<TuiBrowserEntry> searchEntries(List<String> packages,
            List<RemoteClassInfo> classes, List<RemoteField> fields,
            List<RemoteMethod> methods, boolean showRuntime, boolean showArrays) {
        List<TuiBrowserEntry> result = new ArrayList<TuiBrowserEntry>();
        for (String name : packages) {
            if (showRuntime || !runtimeNamespace(name)) result.add(TuiBrowserEntry.packageEntry(name));
        }
        for (RemoteClassInfo info : classes) {
            if (visibleClass(info.name(), showRuntime, showArrays)) {
                result.add(TuiBrowserEntry.classEntry(info.name(), info));
            }
        }
        for (RemoteField field : fields) {
            if (visibleClass(field.declaringClass(), showRuntime, showArrays)) {
                result.add(TuiBrowserEntry.fieldEntry(field));
            }
        }
        for (RemoteMethod method : methods) {
            if (visibleClass(method.declaringClass(), showRuntime, showArrays)) {
                result.add(TuiBrowserEntry.methodEntry(method));
            }
        }
        sort(result);
        return result;
    }

    static List<TuiBrowserEntry> filter(List<TuiBrowserEntry> entries, String filter) {
        String needle = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return new ArrayList<TuiBrowserEntry>(entries);
        List<TuiBrowserEntry> result = new ArrayList<TuiBrowserEntry>();
        for (TuiBrowserEntry entry : entries) {
            if (entry.kind() == TuiBrowserEntry.Kind.PARENT
                    || entry.name().toLowerCase(Locale.ROOT).contains(needle)
                    || entry.displayName().toLowerCase(Locale.ROOT).contains(needle)) result.add(entry);
        }
        return result;
    }

    static boolean visibleApplicationClass(String name) {
        return visibleClass(name, false, false);
    }

    static boolean inheritedObjectMethodHidden(String contextClass,
            String declaringClass, boolean hideInheritedObjectMethods) {
        return hideInheritedObjectMethods
                && contextClass != null
                && !"java.lang.Object".equals(contextClass)
                && "java.lang.Object".equals(declaringClass);
    }

    private static boolean visibleClass(String name, boolean showRuntime, boolean showArrays) {
        // These VM-created names are not useful package-browser entries. Keep them hidden
        // even when the user explicitly enables JDK/runtime namespaces.
        if (name == null || name.isEmpty()) return false;
        if (name.contains("/0x") || name.contains("$$Lambda$")) return false;
        if (name.charAt(0) == '[') return showArrays;
        return showRuntime || !runtimeNamespace(name);
    }

    static boolean runtimeNamespace(String name) {
        return root(name, "java") || root(name, "jdk") || root(name, "sun")
                || root(name, "com.sun");
    }

    static String parentPackage(String name) {
        if (name == null || name.isEmpty()) return "";
        int separator = name.lastIndexOf('.');
        return separator < 0 ? "" : name.substring(0, separator);
    }

    private static boolean root(String name, String root) {
        return name.equals(root) || name.startsWith(root + ".");
    }

    private static void sort(List<TuiBrowserEntry> values) {
        Collections.sort(values, new Comparator<TuiBrowserEntry>() {
            @Override public int compare(TuiBrowserEntry left, TuiBrowserEntry right) {
                if (left.kind() != right.kind()) return left.kind().ordinal() - right.kind().ordinal();
                return left.name().compareToIgnoreCase(right.name());
            }
        });
    }
}
