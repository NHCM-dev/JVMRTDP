package nhcm.jvmrtdp.handles.java;

import nhcm.jvmrtdp.handles.RemoteHandle;
import nhcm.jvmrtdp.handles.ServerHandle;
import nhcm.jvmrtdp.handles.jvm.RemoteJNIEnv;
import nhcm.jvmrtdp.handles.jvm.RemoteJVMTIEnv;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public class RemoteClass extends RemoteHandle {
    private final String className;
    private final RemoteJNIEnv jni;
    private final RemoteJVMTIEnv jvmti;

    public RemoteClass(
            ServerHandle server,
            long remoteId,
            String className,
            RemoteJNIEnv jni,
            RemoteJVMTIEnv jvmti) {
        super(server, remoteId);
        this.className = Objects.requireNonNull(className, "className");
        if (className.trim().isEmpty()) {
            throw new IllegalArgumentException("className must not be empty");
        }
        this.jni = Objects.requireNonNull(jni, "jni");
        this.jvmti = Objects.requireNonNull(jvmti, "jvmti");
    }

    public String className() {
        return className;
    }

    public byte[] getClassBytes() {
        return jvmti.getClassBytes(className);
    }

    public Path dumpClass(Path outputFile) throws IOException {
        return jvmti.dumpClass(className, outputFile);
    }

    public List<RemoteField> getStaticFields() {
        return jni.listStaticFields(this);
    }

    public RemoteField getStaticField(String name) {
        return jni.getStaticField(this, name);
    }

    public RemoteMethod getStaticMethod(String name, String descriptor) {
        return new RemoteMethod(server(), allocateRemoteId(), this, name, descriptor);
    }

    RemoteJNIEnv jniEnv() {
        return jni;
    }

}
