package nhcm.jvmrtdp.localside;

import nhcm.jvmrtdp.handles.ServerHandle;
import nhcm.jvmrtdp.handles.java.RemoteClass;
import nhcm.jvmrtdp.handles.jvm.RemoteJNIEnv;
import nhcm.jvmrtdp.handles.jvm.RemoteJVMTIEnv;

import java.io.PrintStream;
import java.util.Objects;

/** Controller-side object graph for one attached JVM. */
public final class TargetSession {
    private final ServerHandle server;
    private final RemoteJNIEnv jni;
    private final RemoteJVMTIEnv jvmti;
    private final PrintStream output;
    private final PrintStream error;

    public TargetSession(ServerHandle server, PrintStream output, PrintStream error) {
        this.server = Objects.requireNonNull(server, "server");
        this.jni = server.javaVM().jniEnv();
        this.jvmti = server.javaVM().jvmtiEnv();
        this.output = Objects.requireNonNull(output, "output");
        this.error = Objects.requireNonNull(error, "error");
    }

    public ServerHandle server() {
        return server;
    }

    public RemoteJNIEnv jni() {
        return jni;
    }

    public RemoteJVMTIEnv jvmti() {
        return jvmti;
    }

    public RemoteClass findClass(String className) {
        return jni.findClass(className);
    }

    public PrintStream output() {
        return output;
    }

    public PrintStream error() {
        return error;
    }
}
