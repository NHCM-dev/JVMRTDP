package nhcm.jvmrtdp.handles.java;

import nhcm.jvmrtdp.handles.RemoteHandle;
import nhcm.jvmrtdp.handles.ServerHandle;

import java.util.List;

public class RemoteObject extends RemoteHandle {
    public RemoteObject(ServerHandle server, long remoteId) {
        super(server, remoteId);
    }

    public <T> T asObject(Class<T> clazz) {
        throw unsupported("Materializing a remote object");
    }

    public List<RemoteMethod> getVirtualMethods() {
        throw unsupported("Listing virtual methods");
    }

    public List<RemoteField> getVirtualFields() {
        throw unsupported("Listing virtual fields");
    }
}
