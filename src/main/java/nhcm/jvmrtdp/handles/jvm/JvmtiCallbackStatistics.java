package nhcm.jvmrtdp.handles.jvm;

/** Aggregate callback delivery counters from the target JVM. */
public class JvmtiCallbackStatistics {
    private final int registrations;
    private final long delivered;
    private final long failed;
    private final String lastFailure;
    private final long nativeQueued;
    private final long nativeDropped;
    private final long nativeQueueDepth;

    public JvmtiCallbackStatistics(int registrations, long delivered, long failed, String lastFailure) {
        this(registrations, delivered, failed, lastFailure, 0, 0, 0);
    }

    public JvmtiCallbackStatistics(int registrations, long delivered, long failed, String lastFailure,
            long nativeQueued, long nativeDropped, long nativeQueueDepth) {
        this.registrations = registrations;
        this.delivered = delivered;
        this.failed = failed;
        this.lastFailure = lastFailure;
        this.nativeQueued = nativeQueued;
        this.nativeDropped = nativeDropped;
        this.nativeQueueDepth = nativeQueueDepth;
    }

    public int registrations() { return registrations; }
    public long delivered() { return delivered; }
    public long failed() { return failed; }
    public String lastFailure() { return lastFailure; }
    public long nativeQueued() { return nativeQueued; }
    public long nativeDropped() { return nativeDropped; }
    public long nativeQueueDepth() { return nativeQueueDepth; }
}
