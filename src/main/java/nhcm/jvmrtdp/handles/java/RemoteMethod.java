package nhcm.jvmrtdp.handles.java;

import nhcm.jvmrtdp.handles.RemoteHandle;
import nhcm.jvmrtdp.handles.ServerHandle;

import java.util.Objects;

public class RemoteMethod extends RemoteHandle {
    private final RemoteClass owner;
    private final String name;
    private final String descriptor;

    public RemoteMethod(
            ServerHandle server,
            long remoteId,
            RemoteClass owner,
            String name,
            String descriptor) {
        super(server, remoteId);
        this.owner = Objects.requireNonNull(owner, "owner");
        this.name = Objects.requireNonNull(name, "name");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    }

    public RemoteClass owner() {
        return owner;
    }

    public String name() {
        return name;
    }

    public String descriptor() {
        return descriptor;
    }

    public String invoke(String... arguments) {
        return owner.jniEnv().callStaticMethod(owner.className(), name, descriptor, arguments);
    }
}
