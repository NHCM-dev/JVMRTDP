package nhcm.jvmrtdp.controllerside.tui;

import nhcm.jvmrtdp.controllerside.analysis.JvmClassPathCatalog;
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
    private final JvmClassPathCatalog.ClassEntry unloadedClass;
    private final JvmClassPathCatalog.Member unloadedMember;
    private final boolean unloaded;

    private TuiBrowserEntry(Kind kind, String name, RemoteClassInfo classInfo,
            RemoteField field, RemoteMethod method,
            JvmClassPathCatalog.ClassEntry unloadedClass,
            JvmClassPathCatalog.Member unloadedMember, boolean unloaded) {
        this.kind = kind;
        this.name = name;
        this.classInfo = classInfo;
        this.field = field;
        this.method = method;
        this.unloadedClass = unloadedClass;
        this.unloadedMember = unloadedMember;
        this.unloaded = unloaded;
    }

    static TuiBrowserEntry parent(String name) {
        return new TuiBrowserEntry(Kind.PARENT, name, null, null, null, null, null, false);
    }
    static TuiBrowserEntry packageEntry(String name) {
        return new TuiBrowserEntry(Kind.PACKAGE, name, null, null, null, null, null, false);
    }
    static TuiBrowserEntry classEntry(String name, RemoteClassInfo info) {
        return new TuiBrowserEntry(Kind.CLASS, name, info, null, null, null, null, false);
    }
    static TuiBrowserEntry fieldEntry(RemoteField field) {
        return new TuiBrowserEntry(Kind.FIELD,
                field.declaringClass() + "." + field.name(), null, field, null, null, null, false);
    }
    static TuiBrowserEntry methodEntry(RemoteMethod method) {
        return new TuiBrowserEntry(Kind.METHOD,
                method.declaringClass() + "." + method.name() + method.descriptor(),
                null, null, method, null, null, false);
    }

    static TuiBrowserEntry unloadedParent(String name) {
        return new TuiBrowserEntry(Kind.PARENT, name, null, null, null, null, null, true);
    }
    static TuiBrowserEntry unloadedPackage(String name) {
        return new TuiBrowserEntry(Kind.PACKAGE, name, null, null, null, null, null, true);
    }
    static TuiBrowserEntry unloadedClass(JvmClassPathCatalog.ClassEntry entry) {
        return new TuiBrowserEntry(Kind.CLASS, entry.name(), null, null, null,
                entry, null, true);
    }
    static TuiBrowserEntry unloadedMember(JvmClassPathCatalog.ClassEntry owner,
            JvmClassPathCatalog.Member member) {
        Kind kind = member.kind() == JvmClassPathCatalog.MemberKind.FIELD
                ? Kind.FIELD : Kind.METHOD;
        return new TuiBrowserEntry(kind, owner.name() + "." + member.name()
                + (kind == Kind.METHOD ? member.descriptor() : ""), null, null, null,
                owner, member, true);
    }

    Kind kind() { return kind; }
    String name() { return name; }
    RemoteClassInfo classInfo() { return classInfo; }
    RemoteField field() { return field; }
    RemoteMethod method() { return method; }
    JvmClassPathCatalog.ClassEntry unloadedClass() { return unloadedClass; }
    JvmClassPathCatalog.Member unloadedMember() { return unloadedMember; }
    boolean unloaded() { return unloaded; }

    String ownerName() {
        if (field != null) return field.declaringClass();
        if (method != null) return method.declaringClass();
        if (unloadedClass != null) return unloadedClass.name();
        return name;
    }

    String displayName() {
        if (kind == Kind.PARENT) return "[..] " + (name.isEmpty() ? "<root>" : name);
        if (kind == Kind.PACKAGE) return "[+]  " + name;
        if (unloadedMember != null && kind == Kind.FIELD) {
            return "[U:F] " + unloadedClass.name() + "." + unloadedMember.name()
                    + " : " + unloadedMember.typeSummary();
        }
        if (unloadedMember != null && kind == Kind.METHOD) {
            return "[U:M] " + unloadedClass.name() + "." + unloadedMember.name()
                    + " " + unloadedMember.typeSummary();
        }
        if (kind == Kind.FIELD) return "[F]  " + field.declaringClass() + "." + field.name()
                + " : " + field.typeName();
        if (kind == Kind.METHOD) return "[M]  " + method.declaringClass() + "." + method.name()
                + method.parameterTypeNames() + " : " + method.returnTypeName();
        if (kind == Kind.CLASS && name.startsWith("[")) {
            return "[A]  " + arrayTypeName(name) + "  (" + name + ")";
        }
        if (unloadedClass != null) return "[U:C] " + name;
        return "[C]  " + name;
    }

    private static String arrayTypeName(String descriptor) {
        try { return JavaTypeNames.fromDescriptor(descriptor); }
        catch (IllegalArgumentException ignored) { return descriptor; }
    }
}
