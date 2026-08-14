package nhcm.jvmrtdp.api;

import nhcm.jvmrtdp.controllerside.InteractiveCli;
import nhcm.jvmrtdp.controllerside.RemoteContext;
import nhcm.jvmrtdp.controllerside.RemoteOperations;
import nhcm.jvmrtdp.controllerside.RemoteWorkspace;
import nhcm.jvmrtdp.controllerside.TargetSession;
import nhcm.jvmrtdp.controllerside.debug.DebuggerControlService;
import nhcm.jvmrtdp.api.hook.JvmStringHookManager;
import nhcm.jvmrtdp.api.reference.JvmReferenceManager;
import nhcm.jvmrtdp.handles.ServerHandle;
import nhcm.jvmrtdp.handles.java.RemoteClass;
import nhcm.jvmrtdp.handles.jvm.RemoteJNIEnv;
import nhcm.jvmrtdp.handles.jvm.RemoteJVMTIEnv;
import nhcm.jvmrtdp.protocol.CommandReply;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** One attached target JVM and its controller-side object graph. */
public final class JvmRtdpSession implements AutoCloseable {
    private final ServerHandle server;
    private final PrintStream discard;
    private final TargetSession target;
    private final InteractiveCli commands;
    private final JvmInstrumentation instrumentation;
    private final Object commandLock = new Object();
    private final AtomicBoolean open = new AtomicBoolean(true);
    private volatile Runnable closeListener;

    JvmRtdpSession(ServerHandle server) {
        this.server = Objects.requireNonNull(server, "server");
        this.closeListener = new Runnable() {
            @Override
            public void run() {
                // A client-owned session installs its removal callback after construction.
            }
        };
        this.discard = new PrintStream(new OutputStream() {
            @Override
            public void write(int value) {
                // Embedded commands redirect output for each invocation.
            }
        });
        this.target = new TargetSession(server, discard, discard);
        this.commands = new InteractiveCli(new ByteArrayInputStream(new byte[0]), discard, discard);
        this.instrumentation = target.instrumentation();
    }

    void setCloseListener(Runnable listener) {
        this.closeListener = Objects.requireNonNull(listener, "listener");
    }

    public boolean isOpen() {
        return open.get() && server.isOpen();
    }

    public JvmProcessInfo process() {
        ensureOpen();
        return JvmProcessInfo.from(server.process());
    }

    public UUID sessionId() {
        ensureOpen();
        return server.sessionId();
    }

    public String targetDisplayName() {
        ensureOpen();
        return server.targetDisplayName();
    }

    public String agentVersion() {
        ensureOpen();
        return server.agentVersion();
    }

    public boolean nativeAvailable() {
        ensureOpen();
        return server.nativeAvailable();
    }

    public String nativeDescription() {
        ensureOpen();
        return server.nativeDescription();
    }

    /** Executes any context-oriented CLI command and captures its output. */
    public JvmRtdpCommandResult execute(final String command) {
        Objects.requireNonNull(command, "command");
        synchronized (commandLock) {
            ensureOpen();
            final ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
            final ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
            try (PrintStream output = new PrintStream(outputBytes, true, "UTF-8");
                    PrintStream error = new PrintStream(errorBytes, true, "UTF-8")) {
                try {
                    boolean keepGoing = target.withStreams(output, error, new TargetSession.OutputAction<Boolean>() {
                        @Override
                        public Boolean run() throws Exception {
                            return Boolean.valueOf(commands.executeCommand(target, command));
                        }
                    }).booleanValue();
                    output.flush();
                    error.flush();
                    String capturedOutput = text(outputBytes);
                    String capturedError = text(errorBytes);
                    return capturedError.isEmpty()
                            ? JvmRtdpCommandResult.success(
                                    command, keepGoing, capturedOutput, capturedError)
                            : JvmRtdpCommandResult.diagnosticFailure(
                                    command, keepGoing, capturedOutput, capturedError);
                } catch (Exception failure) {
                    output.flush();
                    error.flush();
                    return JvmRtdpCommandResult.failure(
                            command, text(outputBytes), text(errorBytes), failure);
                }
            } catch (java.io.UnsupportedEncodingException impossible) {
                throw new IllegalStateException("UTF-8 is unavailable", impossible);
            }
        }
    }

    /** Executes a target-agent command without the controller-side context language. */
    public CommandReply executeAgent(String command) {
        ensureOpen();
        return server.execute(command);
    }

    public CommandReply executeAgent(String command, Duration timeout) {
        ensureOpen();
        return server.execute(command, timeout);
    }

    public CompletableFuture<CommandReply> executeAgentAsync(String command) {
        ensureOpen();
        return server.executeAsync(command);
    }

    public List<CommandReply> executeAgentBatch(List<String> commands) {
        ensureOpen();
        return server.executeBatch(commands);
    }

    public RemoteClass findClass(String className) {
        ensureOpen();
        return target.findClass(className);
    }

    public RemoteClass forceLoadClass(String className) {
        ensureOpen();
        return target.forceLoadClass(className);
    }

    public RemoteClass loadClassWithoutInitialization(String className) {
        ensureOpen();
        return target.loadClassWithoutInitialization(className);
    }

    public RemoteJNIEnv jni() {
        ensureOpen();
        return target.jni();
    }

    public RemoteJVMTIEnv jvmti() {
        ensureOpen();
        return target.jvmti();
    }

    /** High-level target-code deployment, hook, transformer and redefine facade. */
    public JvmInstrumentation instrumentation() {
        ensureOpen();
        return instrumentation;
    }

    /** Session-owned tracked objects/fields. Close or release entries explicitly when no longer needed. */
    public JvmReferenceManager references() {
        ensureOpen();
        return target.references();
    }

    /** Precise String field watches and String-bearing method entry/exit hooks. */
    public JvmStringHookManager stringHooks() {
        ensureOpen();
        return target.stringHooks();
    }

    public RemoteOperations operations() {
        ensureOpen();
        return target.operations();
    }

    public RemoteWorkspace workspace() {
        ensureOpen();
        return target.workspace();
    }

    public RemoteContext context() {
        ensureOpen();
        return target.context();
    }

    public DebuggerControlService debugger() {
        ensureOpen();
        return target.debugger();
    }

    /** Advanced access to the authenticated protocol handle. */
    public ServerHandle serverHandle() {
        ensureOpen();
        return server;
    }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) return;
        try {
            // Controller-owned stops must never strand application threads after a library client exits.
            try { target.jvmti().clearManagedEventBreakpoints(); } catch (RuntimeException ignored) { }
            try { target.jvmti().clearManagedBreakpoints(); } catch (RuntimeException ignored) { }
            try { target.jvmti().clearManagedFieldWatches(); } catch (RuntimeException ignored) { }
            try { target.jvmti().configureDebugger(false); } catch (RuntimeException ignored) { }
            target.close();
        } finally {
            try {
                server.close();
            } finally {
                discard.close();
                closeListener.run();
            }
        }
    }

    private void ensureOpen() {
        if (!isOpen()) throw new IllegalStateException("JVMRTDP session is closed");
    }

    private static String text(ByteArrayOutputStream bytes) {
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
}
