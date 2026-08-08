package nhcm.jvmrtdp.controllerside;

import nhcm.jvmrtdp.handles.ServerHandle;
import nhcm.jvmrtdp.handles.java.RemoteClass;
import nhcm.jvmrtdp.handles.jvm.RemoteJNIEnv;
import nhcm.jvmrtdp.handles.jvm.RemoteJVMTIEnv;

import java.io.PrintStream;
import java.util.Objects;

/** Controller-side object graph for one attached JVM. */
public class TargetSession implements AutoCloseable {
    private final ServerHandle server;
    private final RemoteJNIEnv jni;
    private final RemoteJVMTIEnv jvmti;
    private final PrintStream baseOutput;
    private PrintStream output;
    private final PrintStream error;
    private final RemoteWorkspace workspace;
    private final RemoteOperations operations;
    private final RemoteContext context;
    private boolean controllerExitRequested;

    public TargetSession(ServerHandle server, PrintStream output, PrintStream error) {
        this.server = Objects.requireNonNull(server, "server");
        this.jni = server.javaVM().jniEnv();
        this.jvmti = server.javaVM().jvmtiEnv();
        this.baseOutput = Objects.requireNonNull(output, "output");
        this.output = this.baseOutput;
        this.error = Objects.requireNonNull(error, "error");
        this.workspace = new RemoteWorkspace(this);
        this.operations = new RemoteOperations(this);
        this.context = new RemoteContext();
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

    public PrintStream baseOutput() {
        return baseOutput;
    }

    public PrintStream error() {
        return error;
    }

    public RemoteWorkspace workspace() {
        return workspace;
    }

    public RemoteOperations operations() {
        return operations;
    }

    public RemoteContext context() {
        return context;
    }

    public void requestControllerExit() {
        controllerExitRequested = true;
    }

    public boolean controllerExitRequested() {
        return controllerExitRequested;
    }

    public <T> T withOutput(PrintStream temporaryOutput, OutputAction<T> action) throws Exception {
        PrintStream previous = output;
        output = Objects.requireNonNull(temporaryOutput, "temporaryOutput");
        try {
            return action.run();
        } finally {
            output = previous;
        }
    }

    @Override
    public void close() {
        try {
            context.close();
        } finally {
            workspace.close();
        }
    }

    public interface OutputAction<T> {
        T run() throws Exception;
    }
}
