package nhcm.jvmrtdp.controllerside;

import nhcm.jvmrtdp.api.JvmInstrumentation;
import nhcm.jvmrtdp.api.hook.JvmStringHookManager;
import nhcm.jvmrtdp.api.reference.JvmReferenceManager;
import nhcm.jvmrtdp.controllerside.debug.DebuggerControlService;
import nhcm.jvmrtdp.controllerside.analysis.JvmClassPathCatalog;
import nhcm.jvmrtdp.handles.ServerHandle;
import nhcm.jvmrtdp.handles.java.RemoteClass;
import nhcm.jvmrtdp.handles.jvm.RemoteJNIEnv;
import nhcm.jvmrtdp.handles.jvm.RemoteJVMTIEnv;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Controller-side object graph for one attached JVM. */
public class TargetSession implements AutoCloseable {
    private final ServerHandle server;
    private final RemoteJNIEnv jni;
    private final RemoteJVMTIEnv jvmti;
    private final JvmInstrumentation instrumentation;
    private final JvmReferenceManager references;
    private final JvmStringHookManager stringHooks;
    private final PrintStream baseOutput;
    private PrintStream output;
    private PrintStream error;
    private final RemoteWorkspace workspace;
    private final RemoteOperations operations;
    private final RemoteContext context;
    private final DebuggerControlService debugger;
    private volatile JvmClassPathCatalog classPathCatalog;
    private boolean controllerExitRequested;
    private boolean tuiRequested;

    public TargetSession(ServerHandle server, PrintStream output, PrintStream error) {
        this.server = Objects.requireNonNull(server, "server");
        this.jni = server.javaVM().jniEnv();
        this.jvmti = server.javaVM().jvmtiEnv();
        this.instrumentation = new JvmInstrumentation(jvmti);
        this.references = new JvmReferenceManager(jni);
        this.stringHooks = new JvmStringHookManager(jni, jvmti);
        this.baseOutput = Objects.requireNonNull(output, "output");
        this.output = this.baseOutput;
        this.error = Objects.requireNonNull(error, "error");
        this.workspace = new RemoteWorkspace(this);
        this.operations = new RemoteOperations(this);
        this.context = new RemoteContext();
        this.debugger = new DebuggerControlService(jvmti);
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

    /** Shared instrumentation state used by CLI, TUI, and the Java Library facade. */
    public JvmInstrumentation instrumentation() { return instrumentation; }

    /** Shared object/field reference registry used by CLI, TUI, and the Java API. */
    public JvmReferenceManager references() { return references; }

    /** Shared String allocation/watch/method-hook registry. */
    public JvmStringHookManager stringHooks() { return stringHooks; }

    public RemoteClass findClass(String className) {
        return jni.findClass(className);
    }

    public RemoteClass forceLoadClass(String className) {
        return jni.forceLoadClass(className);
    }

    public RemoteClass loadClassWithoutInitialization(String className) {
        return jni.loadClassWithoutInitialization(className);
    }

    public RemoteClass startForceLoadClass(String className) {
        return jni.startForceLoadClass(className);
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

    public DebuggerControlService debugger() { return debugger; }

    /**
     * Returns a cached, non-loading view of class files on the target's application
     * class path. Call {@link #refreshClassPathCatalog()} after the target loads classes
     * or changes its class path.
     */
    public JvmClassPathCatalog classPathCatalog() throws IOException {
        JvmClassPathCatalog current = classPathCatalog;
        if (current != null) return current;
        return refreshClassPathCatalog();
    }

    /** Re-scans class-path files and the current loaded-class snapshot. */
    public synchronized JvmClassPathCatalog refreshClassPathCatalog() throws IOException {
        List<String> loaded = new ArrayList<String>(jni.loadedClassNames());
        classPathCatalog = JvmClassPathCatalog.discover(
                jni.systemProperty("java.class.path"),
                jni.systemProperty("user.dir"),
                jni.systemProperty("java.home"), loaded);
        return classPathCatalog;
    }

    public void requestControllerExit() {
        controllerExitRequested = true;
    }

    public boolean controllerExitRequested() {
        return controllerExitRequested;
    }

    public void requestTui() { tuiRequested = true; }

    public boolean consumeTuiRequest() {
        boolean requested = tuiRequested;
        tuiRequested = false;
        return requested;
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

    /** Temporarily redirects both output streams for an embedded command invocation. */
    public <T> T withStreams(
            PrintStream temporaryOutput,
            PrintStream temporaryError,
            OutputAction<T> action) throws Exception {
        PrintStream previousOutput = output;
        PrintStream previousError = error;
        output = Objects.requireNonNull(temporaryOutput, "temporaryOutput");
        error = Objects.requireNonNull(temporaryError, "temporaryError");
        try {
            return action.run();
        } finally {
            output = previousOutput;
            error = previousError;
        }
    }

    @Override
    public void close() {
        try {
            stringHooks.close();
        } finally {
            try {
                references.close();
            } finally {
                try {
                    debugger.close();
                } finally {
                    try {
                        context.close();
                    } finally {
                        workspace.close();
                    }
                }
            }
        }
    }

    public interface OutputAction<T> {
        T run() throws Exception;
    }
}
