package nhcm.jvmrtdp.handles.jvm;

import nhcm.jvmrtdp.handles.RemoteHandle;
import nhcm.jvmrtdp.handles.ServerHandle;

public class RemoteJavaVM extends RemoteHandle {
    private final RemoteJNIEnv jniEnv;
    private final RemoteJVMTIEnv jvmtiEnv;

    public RemoteJavaVM(ServerHandle server, long remoteId) {
        super(server, remoteId);
        this.jniEnv = new RemoteJNIEnv(server, 2);
        this.jvmtiEnv = new RemoteJVMTIEnv(server, 3);
    }

    public RemoteJNIEnv jniEnv() {
        return jniEnv;
    }

    public RemoteJVMTIEnv jvmtiEnv() {
        return jvmtiEnv;
    }
}
