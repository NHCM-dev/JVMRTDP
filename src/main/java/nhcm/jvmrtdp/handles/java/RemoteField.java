package nhcm.jvmrtdp.handles.java;

import nhcm.jvmrtdp.handles.RemoteHandle;
import nhcm.jvmrtdp.handles.ServerHandle;

import java.util.Objects;

public class RemoteField extends RemoteHandle {
    private final RemoteClass owner;
    private final String name;
    private final String descriptor;
    private final String snapshotValue;

    public RemoteField(
            ServerHandle server,
            long remoteId,
            RemoteClass owner,
            String name,
            String descriptor,
            String snapshotValue) {
        super(server, remoteId);
        this.owner = Objects.requireNonNull(owner, "owner");
        this.name = Objects.requireNonNull(name, "name");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.snapshotValue = Objects.requireNonNull(snapshotValue, "snapshotValue");
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

    public String snapshotValue() {
        return snapshotValue;
    }

    public String readValue() {
        String result = owner.jniEnv().readStaticField(owner.className(), name);
        int separator = result.indexOf('\t');
        return separator < 0 ? result : result.substring(separator + 1);
    }
}
