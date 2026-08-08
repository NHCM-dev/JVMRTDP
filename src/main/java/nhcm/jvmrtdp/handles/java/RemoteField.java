package nhcm.jvmrtdp.handles.java;

import nhcm.jvmrtdp.handles.RemoteHandle;
import nhcm.jvmrtdp.handles.ServerHandle;
import nhcm.jvmrtdp.utils.JavaTypeNames;

import java.lang.reflect.Modifier;

public class RemoteField extends RemoteHandle {
    private final RemoteClass owner;
    private final String declaringClass;
    private final String name;
    private final String descriptor;
    private final int modifiers;

    public RemoteField(
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

    public String typeName() {
        return JavaTypeNames.fromDescriptor(descriptor);
    }

    public int modifiers() {
        return modifiers;
    }

    public boolean isStatic() {
        return Modifier.isStatic(modifiers);
    }

    public RemoteObject read(RemoteObject receiver) {
        return owner.jniEnv().readField(this, receiver);
    }

    public RemoteObject readStatic() {
        if (!isStatic()) throw new IllegalStateException(name + " is not static");
        return read(null);
    }

    public void write(RemoteObject receiver, RemoteObject value) {
        owner.jniEnv().writeField(this, receiver, value);
    }

    public void writeStatic(RemoteObject value) {
        if (!isStatic()) throw new IllegalStateException(name + " is not static");
        write(null, value);
    }
}
