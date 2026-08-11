package nhcm.jvmrtdp.handles.jvm;

/** Snapshot of one target-side callback registration and its delivery counters. */
public class JvmtiCallbackRegistration {
    private final String id;
    private final String handlerClass;
    private final String events;
    private final String delivery;
    private final boolean enabled;
    private final long delivered;
    private final long failed;
    private final String lastFailure;
    private final String lastEvent;
    private final long lastEventAt;

    public JvmtiCallbackRegistration(String id, String handlerClass, String events, String delivery,
            long delivered, long failed, String lastFailure) {
        this(id, handlerClass, events, delivery, true, delivered, failed, lastFailure, "", 0L);
    }

    public JvmtiCallbackRegistration(String id, String handlerClass, String events, String delivery,
            boolean enabled, long delivered, long failed, String lastFailure,
            String lastEvent, long lastEventAt) {
        this.id = id;
        this.handlerClass = handlerClass;
        this.events = events;
        this.delivery = delivery;
        this.enabled = enabled;
        this.delivered = delivered;
        this.failed = failed;
        this.lastFailure = lastFailure;
        this.lastEvent = lastEvent;
        this.lastEventAt = lastEventAt;
    }

    public String id() { return id; }
    public String handlerClass() { return handlerClass; }
    public String events() { return events; }
    public String delivery() { return delivery; }
    public boolean enabled() { return enabled; }
    public long delivered() { return delivered; }
    public long failed() { return failed; }
    public String lastFailure() { return lastFailure; }
    public String lastEvent() { return lastEvent; }
    public long lastEventAt() { return lastEventAt; }
}
