package nhcm.jvmrtdp.api.jvmti;

/** Enabled and still-requestable state of one JVMTI capability. */
public class JvmtiCapabilityStatus {
    private final JvmtiCapability capability;
    private final boolean enabled;
    private final boolean potential;

    public JvmtiCapabilityStatus(JvmtiCapability capability, boolean enabled, boolean potential) {
        if (capability == null) throw new IllegalArgumentException("capability must not be null");
        this.capability = capability;
        this.enabled = enabled;
        this.potential = potential;
    }

    public JvmtiCapability capability() { return capability; }
    public boolean enabled() { return enabled; }
    public boolean potential() { return potential; }
    public boolean unavailable() { return !enabled && !potential; }

    @Override
    public String toString() {
        return capability.wireName() + "[enabled=" + enabled + ", potential=" + potential + "]";
    }
}
