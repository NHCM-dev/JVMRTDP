package nhcm.jvmrtdp.controllerside.tui;

import nhcm.jvmrtdp.handles.java.RemoteClassInfo;
import nhcm.jvmrtdp.handles.java.RemoteField;
import nhcm.jvmrtdp.handles.java.RemoteMethod;
import nhcm.jvmrtdp.utils.JavaTypeNames;

/** One package-browser or global-search row. */
final class TuiBrowserEntry {
    enum Kind { PARENT, PACKAGE, CLASS, FIELD, METHOD }

    private final Kind kind;
    private final String name;
    private final RemoteClassInfo classInfo;
    private final RemoteField field;
    private final RemoteMethod method;

    private TuiBrowserEntry(Kind kind, String name, RemoteClassInfo classInfo,
            RemoteField field, RemoteMethod method) {
        this.kind = kind;
        this.name = name;
        this.classInfo = classInfo;
        this.field = field;
        this.method = method;
    }

    static TuiBrowserEntry parent(String name) {
        return new TuiBrowserEntry(Kind.PARENT, name, null, null, null);
    }
    static TuiBrowserEntry packageEntry(String name) {
        return new TuiBrowserEntry(Kind.PACKAGE, name, null, null, null);
    }
    static TuiBrowserEntry classEntry(String name, RemoteClassInfo info) {
        return new TuiBrowserEntry(Kind.CLASS, name, info, null, null);
    }
    static TuiBrowserEntry fieldEntry(RemoteField field) {
        return new TuiBrowserEntry(Kind.FIELD,
                field.declaringClass() + "." + field.name(), null, field, null);
    }
    static TuiBrowserEntry methodEntry(RemoteMethod method) {
        return new TuiBrowserEntry(Kind.METHOD,
                method.declaringClass() + "." + method.name() + method.descriptor(),
                null, null, method);
    }

    Kind kind() { return kind; }
    String name() { return name; }
    RemoteClassInfo classInfo() { return classInfo; }
    RemoteField field() { return field; }
    RemoteMethod method() { return method; }

    String ownerName() {
        if (field != null) return field.declaringClass();
        if (method != null) return method.declaringClass();
        return name;
    }

    String displayName() {
        if (kind == Kind.PARENT) return "[..] " + (name.isEmpty() ? "<root>" : name);
        if (kind == Kind.PACKAGE) return "[+]  " + name;
        if (kind == Kind.FIELD) return "[F]  " + field.declaringClass() + "." + field.name()
                + " : " + field.typeName();
        if (kind == Kind.METHOD) return "[M]  " + method.declaringClass() + "." + method.name()
                + method.parameterTypeNames() + " : " + method.returnTypeName();
        if (kind == Kind.CLASS && name.startsWith("[")) {
            return "[A]  " + arrayTypeName(name) + "  (" + name + ")";
        }
        return "[C]  " + name;
    }

    private static String arrayTypeName(String descriptor) {
        try { return JavaTypeNames.fromDescriptor(descriptor); }
        catch (IllegalArgumentException ignored) { return descriptor; }
    }
}
