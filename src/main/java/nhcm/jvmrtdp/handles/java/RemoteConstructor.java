package nhcm.jvmrtdp.handles.java;

import nhcm.jvmrtdp.handles.RemoteHandle;
import nhcm.jvmrtdp.handles.ServerHandle;
import nhcm.jvmrtdp.utils.JavaTypeNames;

import java.util.List;

public class RemoteConstructor extends RemoteHandle {
    private final RemoteClass owner;
    private final String descriptor;
    private final int modifiers;

    public RemoteConstructor(
            ServerHandle server, long remoteId, RemoteClass owner, String descriptor, int modifiers) {
        super(server, remoteId);
        this.owner = owner;
        this.descriptor = descriptor;
        this.modifiers = modifiers;
    }

    public RemoteClass owner() {
        return owner;
    }

    public String descriptor() {
        return descriptor;
    }

    public List<String> parameterTypeNames() {
        return JavaTypeNames.parameterTypes(descriptor);
    }

    public int modifiers() {
        return modifiers;
    }

    public RemoteObject construct(RemoteObject... arguments) {
        return owner.construct(descriptor, arguments);
    }
}
