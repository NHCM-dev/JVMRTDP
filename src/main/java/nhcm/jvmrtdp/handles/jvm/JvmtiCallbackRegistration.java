package nhcm.jvmrtdp.handles.jvm;

/** Snapshot of one target-side callback registration and its delivery counters. */
public class JvmtiCallbackRegistration {
    private final String id;
    private final String handlerClass;
    private final String events;
    private final String delivery;
    private final long delivered;
    private final long failed;
    private final String lastFailure;

    public JvmtiCallbackRegistration(String id, String handlerClass, String events, String delivery,
            long delivered, long failed, String lastFailure) {
        this.id = id;
        this.handlerClass = handlerClass;
        this.events = events;
        this.delivery = delivery;
        this.delivered = delivered;
        this.failed = failed;
        this.lastFailure = lastFailure;
    }

    public String id() { return id; }
    public String handlerClass() { return handlerClass; }
    public String events() { return events; }
    public String delivery() { return delivery; }
    public long delivered() { return delivered; }
    public long failed() { return failed; }
    public String lastFailure() { return lastFailure; }
}
