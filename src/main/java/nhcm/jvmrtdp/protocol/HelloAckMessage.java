package nhcm.jvmrtdp.protocol;

import java.util.Objects;
import java.util.UUID;

public class HelloAckMessage {
    private final UUID jrdHandleId;
    private final long targetPid;
    private final String targetDisplayName;
    private final String runtimeVersion;
    private final boolean nativeAvailable;
    private final String nativeDescription;

    public HelloAckMessage(
            UUID jrdHandleId,
            long targetPid,
            String targetDisplayName,
            String runtimeVersion,
            boolean nativeAvailable,
            String nativeDescription) {
        this.jrdHandleId = Objects.requireNonNull(jrdHandleId, "jrdHandleId");
        this.targetPid = targetPid;
        this.targetDisplayName = Objects.requireNonNull(targetDisplayName, "targetDisplayName");
        this.runtimeVersion = Objects.requireNonNull(runtimeVersion, "runtimeVersion");
        this.nativeAvailable = nativeAvailable;
        this.nativeDescription = Objects.requireNonNull(nativeDescription, "nativeDescription");
    }

    public UUID jrdHandleId() { return jrdHandleId; }
    public long targetPid() { return targetPid; }
    public String targetDisplayName() { return targetDisplayName; }
    public String agentVersion() { return runtimeVersion; }
    public boolean nativeAvailable() { return nativeAvailable; }
    public String nativeDescription() { return nativeDescription; }
}
