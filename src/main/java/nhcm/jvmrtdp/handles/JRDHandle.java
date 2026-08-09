package nhcm.jvmrtdp.handles;

import nhcm.jvmrtdp.BuildInfo;
import nhcm.jvmrtdp.agent.NativeAgent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import nhcm.jvmrtdp.utils.ProcessIds;
import nhcm.jvmrtdp.remoteside.TargetJvm;
import nhcm.jvmrtdp.remoteside.TargetCodeService;
import nhcm.jvmrtdp.remoteside.TargetObjectService;

/**
 * Handle that has the ability to write back data to JVMRTDP control side
 */
public class JRDHandle implements AutoCloseable {
    private final UUID id;
    private final long processId;
    private final Instant startedAt;
    private final NativeAgent.RuntimeInfo nativeRuntime;
    private final TargetJvm targetJvm;
    private final TargetObjectService targetObjects;
    private final TargetCodeService targetCode;

    public JRDHandle(UUID id) {
        this.id = Objects.requireNonNull(id, "id");
        this.processId = ProcessIds.current();
        this.startedAt = Instant.now();
        this.nativeRuntime = NativeAgent.runtimeInfo();
        this.targetJvm = new TargetJvm();
        this.targetObjects = new TargetObjectService();
        this.targetCode = new TargetCodeService(targetObjects);
    }

    public UUID id() {
        return id;
    }

    public long processId() {
        return processId;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public NativeAgent.RuntimeInfo nativeRuntime() {
        return nativeRuntime;
    }

    public TargetJvm targetJvm() {
        if (!nativeRuntime.available()) {
            throw new IllegalStateException("Target JNI/JVMTI bridge is unavailable: " + nativeRuntime.error());
        }
        return targetJvm;
    }

    public TargetObjectService targetObjects() {
        if (!nativeRuntime.available()) {
            throw new IllegalStateException("Target JNI/JVMTI bridge is unavailable: " + nativeRuntime.error());
        }
        return targetObjects;
    }

    public TargetCodeService targetCode() {
        if (!nativeRuntime.available()) {
            throw new IllegalStateException("Target JNI/JVMTI bridge is unavailable: " + nativeRuntime.error());
        }
        return targetCode;
    }

    @Override
    public void close() {
        try {
            targetCode.close();
        } finally {
            targetObjects.close();
        }
    }

    public String displayName() {
        return System.getProperty("sun.java.command", "");
    }

    public String describe() {
        return "pid=" + processId + System.lineSeparator()
                + "jvmrtdp.version=" + BuildInfo.VERSION + System.lineSeparator()
                + "command=" + displayName() + System.lineSeparator()
                + "java.version=" + System.getProperty("java.version") + System.lineSeparator()
                + "java.vm.name=" + System.getProperty("java.vm.name") + System.lineSeparator()
                + "runtime.startedAt=" + startedAt + System.lineSeparator()
                + "native=" + nativeRuntime.describe();
    }
}
