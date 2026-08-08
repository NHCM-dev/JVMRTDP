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

    public RemoteMethod(
            ServerHandle server,
            long remoteId,
            RemoteClass owner,
            String declaringClass,
            String name,
            String descriptor,
            int modifiers) {
        super(server, remoteId);
        this.owner = owner;
        this.declaringClass = declaringClass;
        this.name = name;
        this.descriptor = descriptor;
        this.modifiers = modifiers;
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

    public RemoteObject call(RemoteObject receiver, RemoteObject... arguments) {
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
