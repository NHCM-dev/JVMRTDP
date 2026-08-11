package nhcm.jvmrtdp.handles.java;

import nhcm.jvmrtdp.handles.RemoteHandle;
import nhcm.jvmrtdp.handles.ServerHandle;
import nhcm.jvmrtdp.utils.JavaTypeNames;

import java.lang.reflect.Modifier;
import java.util.List;

public class RemoteMethod extends RemoteHandle {
    private final RemoteClass owner;
    private final String declaringClass;
    private final String name;
    private final String descriptor;
    private final int modifiers;
    private final boolean jvmSpecial;

    public RemoteMethod(
            ServerHandle server,
            long remoteId,
            RemoteClass owner,
            String declaringClass,
            String name,
            String descriptor,
            int modifiers) {
        this(server, remoteId, owner, declaringClass, name, descriptor, modifiers, false);
    }

    private RemoteMethod(ServerHandle server, long remoteId, RemoteClass owner,
            String declaringClass, String name, String descriptor, int modifiers,
            boolean jvmSpecial) {
        super(server, remoteId);
        this.owner = owner;
        this.declaringClass = declaringClass;
        this.name = name;
        this.descriptor = descriptor;
        this.modifiers = modifiers;
        this.jvmSpecial = jvmSpecial;
    }

    /** A classfile/JVMTI-only lifecycle method used for bytecode, source and breakpoints. */
    public static RemoteMethod jvmSpecial(RemoteClass owner, String name,
            String descriptor, int modifiers) {
        if (!"<init>".equals(name) && !"<clinit>".equals(name)) {
            throw new IllegalArgumentException("Not a JVM lifecycle method: " + name);
        }
        return new RemoteMethod(owner.server(), allocateRemoteId(), owner, owner.className(),
                name, descriptor, modifiers, true);
    }

    public RemoteClass owner() {
        return owner;
    }

    public String declaringClass() {
        return declaringClass;
    }

    public String name() {
        return name;
    }

    public String descriptor() {
        return descriptor;
    }

    public String returnTypeName() {
        return JavaTypeNames.returnType(descriptor);
    }

    public List<String> parameterTypeNames() {
        return JavaTypeNames.parameterTypes(descriptor);
    }

    public int modifiers() {
        return modifiers;
    }

    public boolean isStatic() {
        return Modifier.isStatic(modifiers);
    }

    public boolean isNative() { return Modifier.isNative(modifiers); }

    public boolean isAbstract() { return Modifier.isAbstract(modifiers); }

    public boolean isJvmSpecial() { return jvmSpecial; }

    public String implementationKind() {
        return isNative() ? "NATIVE" : isAbstract() ? "ABSTRACT" : "BYTECODE";
    }

    public RemoteObject call(RemoteObject receiver, RemoteObject... arguments) {
        if (jvmSpecial) {
            throw new UnsupportedOperationException(name
                    + " is exposed for analysis/breakpoints and cannot be invoked as java.lang.reflect.Method");
        }
        return owner.jniEnv().call(this, receiver, arguments);
    }

    public RemoteObject callStatic(RemoteObject... arguments) {
        if (!isStatic()) throw new IllegalStateException(name + " is not static");
        return call(null, arguments);
    }

    /** Calls this declaring-class implementation without virtual override dispatch. */
    public RemoteObject callSpecial(RemoteObject receiver, RemoteObject... arguments) {
        if (isStatic()) throw new IllegalStateException(name + " is static");
        return owner.jniEnv().callSpecial(this, receiver, arguments);
    }
}
